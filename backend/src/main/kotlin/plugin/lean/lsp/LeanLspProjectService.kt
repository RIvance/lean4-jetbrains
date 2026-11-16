package plugin.lean.lsp

import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.Endpoint
import plugin.lean.project.LeanProjectRoots
import plugin.lean.toolchain.LeanToolchainService
import plugin.lean.tooling.LeanWorkspace
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Owns server scopes. Every open Lean file belongs to exactly one nearest project/toolchain boundary. */
@Service(Service.Level.PROJECT)
internal class LeanLspProjectService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = ensureOpenWorkspaces()
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (file.extension == "lean") ensureOpenWorkspaces()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file.extension == "lean") ensureOpenWorkspaces()
            }
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val affectedDirectories = events.filter { it.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL }
                    .filter { event ->
                        Path.of(event.path).fileName.toString() in PROJECT_FILES ||
                        event is VFileMoveEvent ||
                        (event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME)
                    }
                    .flatMap { event -> listOfNotNull(Path.of(event.path).parent, event.file?.parent?.toNioPath()) }
                if (affectedDirectories.isEmpty()) return
                scope.launch {
                    val affectsOpenFile = readAction {
                        FileEditorManager.getInstance(project).openFilesWithRemotes.any { file ->
                            file.isValid && file.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL &&
                            file.extension == "lean" &&
                            affectedDirectories.any { file.toNioPath().startsWith(it) }
                        }
                    }
                    if (affectsOpenFile) {
                        project.service<LeanToolchainService>().restart()
                    } else {
                        val affectsProject = readAction {
                            ProjectRootManager.getInstance(project).contentRoots.any { root ->
                                root.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL &&
                                affectedDirectories.any { it.startsWith(root.toNioPath()) }
                            }
                        }
                        if (affectsProject) {
                            readAction { refreshLibraryWorkspaces() }
                            project.service<LeanProjectRoots>().refresh()
                        }
                    }
                }
            }
        })
    }

    fun workspaceFor(file: VirtualFile): LeanWorkspace =
        LeanWorkspace.discover(file.toNioPath(), Path.of(project.basePath ?: file.parent.path))

    /** The platform's file-open callback accepts one descriptor: the requested file's nearest workspace. */
    fun descriptorFor(file: VirtualFile): LeanLspClientDescriptor? {
        if (!project.service<LeanToolchainService>().configuration.enabled) return null
        return workspacesFor(listOf(file)).singleOrNull()?.let(::descriptor)
    }

    /** Called under a read action in the editor client's context, which native document startup also uses. */
    fun openWorkspaceDescriptors(): List<LeanLspClientDescriptor> {
        if (!project.service<LeanToolchainService>().configuration.enabled) return emptyList()
        val files = FileEditorManager.getInstance(project).openFiles.asList()
        return workspacesFor(files).map(::descriptor)
    }

    private fun refreshLibraryWorkspaces() {
        val libraryWorkspaces = workspacesFor(FileEditorManager.getInstance(project).openFilesWithRemotes)
        val configuredWorkspaces = ProjectRootManager.getInstance(project).contentRoots.filter { root ->
            root.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL &&
            PROJECT_FILES.any { root.findChild(it) != null }
        }.map { root -> LeanWorkspace.discoverDirectory(root.toNioPath(), root.toNioPath()) }
        project.service<LeanProjectRoots>().ensureWorkspaces(
            (configuredWorkspaces + libraryWorkspaces).distinctBy { it.root() }
        )
    }

    private fun descriptor(workspace: LeanWorkspace): LeanLspClientDescriptor {
        val root = checkNotNull(LocalFileSystem.getInstance().findFileByNioFile(workspace.root())) {
            "The directory of an open Lean file is missing from the virtual file system"
        }
        return LeanLspClientDescriptor(project, root, workspace)
    }

    private fun workspacesFor(files: List<VirtualFile>): List<LeanWorkspace> {
        val index = ProjectFileIndex.getInstance(project)
        return files.filter {
            it.isValid && it.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL &&
            it.extension == "lean" && index.isInContent(it)
        }.map(::workspaceFor).distinctBy { it.root() }
    }

    fun ensureOpenWorkspaces() {
        // Register the calling editor session before broadcasting to all sessions that have opened Lean files.
        project.service<LeanLspEditorSession>()
        scope.launch {
            readAction { refreshLibraryWorkspaces() }
            project.messageBus.syncPublisher(LeanWorkspaceListener.TOPIC).workspacesChanged()
        }
    }

    fun restart() {
        project.service<LeanProjectRoots>().refresh()
        LspClientManager.getInstance(project).stopClients(LeanLspIntegrationProvider::class.java)
        ensureOpenWorkspaces()
    }

    suspend fun restartFile(file: VirtualFile) {
        val hub = project.service<LeanServerHub>()
        val server = readAction { hub.serverFor(file) }
            ?: throw LeanServerHub.unavailable("The Lean server is not running for this file. Check Lean → Toolchains.")
        restartFile(server.id, hub.documentUri(server.id, file))
    }

    /** Restarts one open worker, preserving unsaved text and asking Lean to refresh its dependencies once. */
    suspend fun restartFile(serverId: String, uri: String) {
        val hub = project.service<LeanServerHub>()
        hub.awaitDocument(serverId, uri)
        val dispatched = readActionBlocking {
            val client = hub.client(serverId)
            val file = client.descriptor.findFileByUri(uri)
                ?: throw LeanServerHub.unavailable("The Lean file is no longer available.")
            if (!file.isValid || !FileEditorManager.getInstance(project).isFileOpenWithRemotes(file)) {
                throw LeanServerHub.unavailable("The Lean file was closed before it could be restarted.")
            }
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: throw LeanServerHub.unavailable("The Lean document is no longer available.")
            val language = client.descriptor.getLanguageId(file)
            val version = client.getDocumentVersion(document)
            val item = TextDocumentItem(uri, language, version, document.text)
            val open = LeanServerHub.gson.toJsonTree(DidOpenTextDocumentParams(item)).asJsonObject.apply {
                addProperty("dependencyBuildMode", "once")
            }
            // Native didChange notifications are queued under the write lock. Enqueue here before edits can resume.
            hub.enqueue(serverId) { server ->
                server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
                (server as Endpoint).notify("textDocument/didOpen", open)
                CompletableFuture.completedFuture(Unit)
            }
        }
        dispatched.await()
    }

    override fun dispose() = Unit

    companion object {
        private val PROJECT_FILES = setOf("lean-toolchain", "lakefile.lean", "lakefile.toml", "lake-manifest.json")
    }
}

/** The platform owns this per-client service's lifetime, including remote reconnects. */
internal class LeanLspEditorSession(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val clientId = ClientId.current

    init {
        project.messageBus.connect(this).subscribe(LeanWorkspaceListener.TOPIC, object : LeanWorkspaceListener {
            override fun workspacesChanged() = ensureOpenWorkspaces()
        })
    }

    fun ensureOpenWorkspaces() {
        scope.launch(clientId.asContextElement()) {
            val descriptors = readAction { project.service<LeanLspProjectService>().openWorkspaceDescriptors() }
            val manager = LspClientManager.getInstance(project)
            descriptors.forEach { manager.ensureClientStarted(LeanLspIntegrationProvider::class.java, it) }
        }
    }

    override fun dispose() = Unit
}

private interface LeanWorkspaceListener {
    fun workspacesChanged()

    companion object {
        val TOPIC = Topic.create("Lean workspace changes", LeanWorkspaceListener::class.java)
    }
}

internal class LeanProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<LeanLspProjectService>().ensureOpenWorkspaces()
    }
}

package plugin.lean.project

import com.intellij.navigation.ItemPresentation
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import plugin.lean.language.LeanIcons
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.toolchain.LeanToolchainService
import plugin.lean.toolchain.execute
import plugin.lean.tooling.LeanSourceRoots
import plugin.lean.tooling.LeanWorkspace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Owns project cache exclusions and discovered libraries; superseded discovery cancels its child process. */
@Service(Service.Level.PROJECT)
internal class LeanProjectRoots(private val project: Project, scope: CoroutineScope) {
    private data class Request(
        val workspaces: List<LeanWorkspace>,
        val configuration: LeanConfiguration,
        val contentRoots: List<VirtualFile>,
        val revision: Long
    )

    private val requests = MutableStateFlow<Request?>(null)
    private var failures: Map<Path, String> = emptyMap()

    @Volatile
    private var lakeDirectories: List<Path> = emptyList()

    val excludedUrls: List<String> get() = lakeDirectories.map(::lakeCacheUrl)

    @Volatile
    var libraries: List<LeanSourceLibrary> = emptyList()
        private set

    init {
        scope.launch {
            requests.filterNotNull().collectLatest { request ->
                refreshExclusions(request)
                val workspaces = if (request.configuration.enabled) request.workspaces else emptyList()
                val results = workspaces.map { workspace ->
                    try {
                        val roots = discover(workspace, request.configuration)
                        workspace to Result.success(LeanSourceLibrary(workspace.root(), roots))
                    } catch (error: IOException) {
                        workspace to Result.failure<LeanSourceLibrary>(error)
                    }
                }
                val next = results.mapNotNull { it.second.getOrNull() }.filter { it.sourceRoots.isNotEmpty() }
                val errors = results.mapNotNull { (workspace, result) ->
                    result.exceptionOrNull()?.let { workspace.root() to (it.message ?: "Source discovery failed") }
                }.toMap()
                withContext(Dispatchers.EDT) {
                    writeAction {
                        if (requests.value == request) publish(next, errors)
                    }
                }
            }
        }
    }

    fun ensureWorkspaces(workspaces: List<LeanWorkspace>) {
        val configuration = project.service<LeanToolchainService>().configuration
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots.toList()
        requests.update { Request(workspaces.toList(), configuration, contentRoots, it?.revision ?: 0) }
    }

    fun refresh() {
        val configuration = project.service<LeanToolchainService>().configuration
        requests.update { current ->
            current?.copy(
                configuration = configuration,
                revision = current.revision + 1
            )
        }
    }

    private suspend fun refreshExclusions(request: Request) {
        val known = readAction {
            val roots = request.contentRoots.filter { it.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL }
                .map { it.toNioPath() }
            (lakeDirectories + roots + request.workspaces.map { it.root() }).distinct().filter { directory ->
                roots.any { directory.startsWith(it) } &&
                LocalFileSystem.getInstance().findFileByNioFile(directory)?.isLakeDirectory() == true
            }
        }
        publishExclusions(request, known)
        // Discover nested projects without walking their source trees or starting Lean.
        val indexed = smartReadAction(project) {
            LAKE_FILES.flatMap { FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.projectScope(project)) }
                .filter { !it.isDirectory && it.fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL }
                .map { it.parent.toNioPath() }
        }
        publishExclusions(request, known + indexed)
    }

    private suspend fun publishExclusions(request: Request, directories: List<Path>) {
        withContext(Dispatchers.EDT) {
            writeAction {
                if (requests.value != request) return@writeAction
                val next = directories.distinct().sorted()
                if (lakeDirectories == next) return@writeAction
                val removed = lakeDirectories.minus(next.toSet()).mapNotNull { directory ->
                    VirtualFileManager.getInstance().findFileByUrl(lakeCacheUrl(directory))
                }
                lakeDirectories = java.util.List.copyOf(next)
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, null, emptyList(), removed, "Lean cache exclusions"
                )
            }
        }
    }

    private suspend fun discover(workspace: LeanWorkspace, configuration: LeanConfiguration): List<VirtualFile> {
        val roots = runInterruptible(Dispatchers.IO) {
            val script = Files.createTempFile("lean-source-roots-", ".lean")
            try {
                val resource = checkNotNull(javaClass.getResourceAsStream("/lean/sourceRoots.lean"))
                resource.use { Files.copy(it, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                val arguments = buildList {
                    if (workspace.usesLake()) addAll(listOf("lake", "env"))
                    addAll(listOf("lean", "--run", script.toString()))
                }
                val command = project.service<LeanToolchainService>().command(
                    workspace.root(), arguments, configuration
                )
                val paths = LeanSourceRoots.parse(command.execute(30_000), workspace.root()).paths()
                paths.mapNotNull(LocalFileSystem.getInstance()::refreshAndFindFileByNioFile)
            } finally {
                Files.deleteIfExists(script)
            }
        }
        return readAction {
            val index = ProjectFileIndex.getInstance(project)
            roots.filter { it.isValid && it.isDirectory && !index.isInContent(it) }
        }
    }

    private fun publish(next: List<LeanSourceLibrary>, errors: Map<Path, String>) {
        val previous = libraries.associateBy { it.workspace }
        val current = next.associateBy { it.workspace }
        libraries = java.util.List.copyOf(next)
        for (workspace in previous.keys + current.keys) {
            val before = previous[workspace]
            val after = current[workspace]
            if (before == after) continue
            AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                project, (after ?: before)?.presentableText,
                before?.sourceRoots.orEmpty(), after?.sourceRoots.orEmpty(), "Lean sources: $workspace"
            )
        }
        for ((workspace, message) in errors) {
            if (failures[workspace] == message) continue
            NotificationGroupManager.getInstance().getNotificationGroup("Lean")
                .createNotification(
                    "Cannot refresh Lean library sources (${workspace.fileName})",
                    StringUtil.escapeXmlEntities(message), NotificationType.WARNING
                ).notify(project)
        }
        failures = errors
    }
}

internal class LeanSourceLibrary(val workspace: Path, roots: List<VirtualFile>) :
    SyntheticLibrary(workspace.toString(), ExcludeFileCondition { directory, name, root, _, _ ->
        directory && name == LAKE_CACHE_DIRECTORY && !root.asBoolean
    }), ItemPresentation {
    private val sources = java.util.List.copyOf(roots)
    override fun getSourceRoots(): Collection<VirtualFile> = sources
    override fun getPresentableText() = "Lean sources (${workspace.fileName})"
    override fun getLocationString() = workspace.toString()
    override fun getIcon(unused: Boolean) = LeanIcons.LEAN
    override fun equals(other: Any?): Boolean =
        other is LeanSourceLibrary && workspace == other.workspace && sources == other.sources

    override fun hashCode() = 31 * workspace.hashCode() + sources.hashCode()
}

internal class LeanProjectRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        project.service<LeanProjectRoots>().libraries

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        project.service<LeanProjectRoots>().libraries.flatMap { it.sourceRoots }.distinct()
}

internal class LeanCacheExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> {
        // Content-root caches are excluded even before asynchronous project discovery finishes.
        val roots = ProjectRootManager.getInstance(project).contentRoots.filter { it.isLakeDirectory() }
            .map { lakeCacheUrl(it.toNioPath()) }
        return (roots + project.service<LeanProjectRoots>().excludedUrls).distinct().toTypedArray()
    }
}

private val LAKE_FILES = listOf("lakefile.lean", "lakefile.toml")
private const val LAKE_CACHE_DIRECTORY = ".lake"

private fun VirtualFile.isLakeDirectory(): Boolean =
    isValid && isDirectory && fileSystem.protocol == StandardFileSystems.FILE_PROTOCOL &&
    LAKE_FILES.any { findChild(it)?.isDirectory == false }

private fun lakeCacheUrl(directory: Path): String =
    VfsUtilCore.pathToUrl(directory.resolve(LAKE_CACHE_DIRECTORY).toString())

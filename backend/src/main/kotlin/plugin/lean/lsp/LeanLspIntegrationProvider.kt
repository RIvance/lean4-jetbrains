package plugin.lean.lsp

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.psi.PsiFile
import com.intellij.util.io.BaseDataReader
import com.intellij.util.io.BaseOutputReader
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InlayHintCapabilities
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import plugin.lean.editor.LeanEditorMarkup
import plugin.lean.editor.LeanInlayService
import plugin.lean.highlighting.LeanEnhancedHighlighting
import plugin.lean.highlighting.HighlightingSession
import plugin.lean.language.LeanIcons
import plugin.lean.language.LeanLanguage
import plugin.lean.rpc.MessageDirection
import plugin.lean.toolchain.LeanToolchainService
import plugin.lean.toolchain.LeanToolchainHost
import plugin.lean.tooling.LeanWorkspace
import plugin.lean.tooling.lsp.ObservedLspProcess
import java.io.IOException

internal class LeanLspIntegrationProvider : LspIntegrationProvider {
    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?) =
        LspClientWidgetItem(lspClient, currentFile, LeanIcons.LEAN)

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter
    ) {
        project.service<LeanLspEditorSession>()
        val descriptor = project.service<LeanLspProjectService>().descriptorFor(file) ?: return
        clientStarter.ensureClientStarted(descriptor)
    }
}

internal class LeanLspClientDescriptor(
    project: Project,
    root: VirtualFile,
    private val workspace: LeanWorkspace
) : LspClientDescriptor(project, "Lean 4", root) {
    @Volatile
    var connectionId: String? = null
        private set

    private var highlighting: HighlightingSession? = null

    override fun isSupportedFile(file: VirtualFile): Boolean {
        if (file.extension != "lean" || file.fileSystem.protocol != StandardFileSystems.FILE_PROTOCOL) return false
        val owner = project.service<LeanLspProjectService>().workspaceFor(file)
        return owner.root() == workspace.root()
    }
    override fun getLanguageId(file: VirtualFile) = "lean4"

    override fun createInitializationOptions(): Any = mapOf("hasWidgets" to true)

    override fun createInitializeParams() = LeanInitializeParams.from(super.createInitializeParams()).apply {
        // Hint data arrives complete; the declarative renderer owns presentation and server refreshes.
        capabilities.textDocument.inlayHint = InlayHintCapabilities(false)
        val foldingKinds = capabilities.textDocument.foldingRange.foldingRangeKind
        foldingKinds.valueSet = foldingKinds.valueSet + "proof"
    }

    override val lsp4jServerClass = LeanLanguageServer::class.java

    override val lspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            project.service<LeanServerHub>().initialized(checkNotNull(connectionId))
        }

        override fun serverStopped(shutdownNormally: Boolean) {
            highlighting?.stopped(shutdownNormally)
        }
    }

    override val lspCustomization = object : LspCustomization() {
        override val semanticTokensCustomizer = LeanSemanticTokensSupport()
        override val diagnosticsCustomizer = LeanDiagnosticsSupport()
        override val renameCustomizer = LeanRenameSupport()
        override val optimizeImportsCustomizer = LspOptimizeImportsDisabled
        override val inlayHintCustomizer = LspInlayHintDisabled
        override val documentHighlightsCustomizer = object : LspDocumentHighlightsSupport() {
            override fun shouldAskServerForDocumentHighlights(psiFile: PsiFile) = psiFile.language == LeanLanguage
        }
    }

    override fun startServerProcess(): OSProcessHandler {
        val hub = project.service<LeanServerHub>()
        val id = checkNotNull(connectionId) { "The Lean client must be created before its process starts" }
        val session = project.service<LeanEnhancedHighlighting>().session()
        highlighting = session
        val started = try {
            val configuration = project.service<LeanToolchainService>().configuration
            val toolchain = LeanToolchainHost.resolve(workspace.root(), configuration)
            val command = toolchain.command(workspace.root(), workspace.serverArguments(configuration.serverArguments))
            session.start(toolchain.toolchain, command, configuration.enhancedSemanticHighlighting)
        } catch (error: IOException) {
            hub.failed(id, error.message ?: "Cannot configure the Lean server")
            throw ExecutionException(error.message, error)
        } catch (error: ExecutionException) {
            hub.failed(id, error.message ?: "Cannot start the Lean server")
            throw error
        }
        val observed = ObservedLspProcess(
            started.process,
            {
                session.received(it)
                hub.message(id, MessageDirection.Server, it)
            },
            {
                session.sent(it)
                hub.message(id, MessageDirection.Client, it)
            }
        )
        return object : OSProcessHandler(observed, started.command, Charsets.UTF_8) {
            override fun readerOptions(): BaseOutputReader.Options = object : BaseOutputReader.Options() {
                override fun policy(): BaseDataReader.SleepingPolicy = forMostlySilentProcess().policy()
                override fun splitToLines(): Boolean = false
            }
        }.apply {
            addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) = hub.stopped(id, event.exitCode)
            })
        }
    }

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient {
        val hub = project.service<LeanServerHub>()
        val id = hub.started(this)
        connectionId = id
        hub.attachHandler(id, handler)
        val notifications = object : LspServerNotificationsHandler by handler {
            // The transport observer publishes normalized snapshots before LSP4j discards Lean's extension fields.
            override fun publishDiagnostics(params: PublishDiagnosticsParams) = Unit

            override fun refreshInlayHints() = handler.refreshInlayHints().also {
                project.service<LeanInlayService>().refresh(id)
            }
        }
        return LeanLanguageClient(notifications, id, project.service<LeanEditorMarkup>())
    }

}

internal class LeanLanguageClient(
    handler: LspServerNotificationsHandler,
    private val serverId: String,
    private val markup: LeanEditorMarkup
) : Lsp4jClient(handler) {
    @JsonNotification("$/lean/fileProgress")
    fun fileProgress(params: com.google.gson.JsonElement) = markup.receivedProgress(serverId, params)
}

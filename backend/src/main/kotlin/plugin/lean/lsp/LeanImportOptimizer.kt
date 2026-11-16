package plugin.lean.lsp

import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import plugin.lean.language.LeanLanguage

/** Resolves Lean's import action while the platform owns the write command and Undo. */
internal class LeanImportOptimizer : ImportOptimizer {
    private val noChanges = Runnable {}

    override fun supports(file: PsiFile): Boolean = importClient(file) != null

    override fun processFile(file: PsiFile): Runnable {
        val client = importClient(file) ?: return noChanges
        val virtualFile = file.virtualFile
        val hub = file.project.service<LeanServerHub>()
        val lifetime = hub.synchronizedDocument(virtualFile) ?: return noChanges
        if (hub.currentClient(lifetime.serverId) !== client) return noChanges
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return noChanges
        val stamp = document.modificationStamp
        val context = CodeActionContext(emptyList()).apply {
            only = listOf(CodeActionKind.SourceOrganizeImports)
            triggerKind = CodeActionTriggerKind.Invoked
        }
        val params = CodeActionParams(
            TextDocumentIdentifier(lifetime.uri),
            Range(Position(0, 0), Position(0, 0)),
            context
        )
        val actions = client.sendRequestSync { it.textDocumentService.codeAction(params) }.orEmpty()
            .mapNotNull { it.right }
            .filter { isImportAction(it) }
        if (actions.size > 1) {
            return object : ImportOptimizer.CollectingInfoRunnable {
                override fun run() = Unit
                override fun getUserNotificationInfo() =
                    "Lean offered several import actions. Choose one using Show Context Actions."
            }
        }
        val action = actions.singleOrNull() ?: return noChanges
        val canResolve = client.initializeResult?.capabilities?.codeActionProvider?.right?.resolveProvider == true
        val resolved = if (action.edit == null && canResolve) {
            client.sendRequestSync { it.textDocumentService.resolveCodeAction(action) } ?: return noChanges
        } else {
            action
        }
        resolved.edit = importEdits(resolved, lifetime.uri) ?: return noChanges
        val intention = LspIntentionAction(client, resolved)
        if (!intention.isAvailable()) return noChanges

        return Runnable {
            if (
                virtualFile.isValid && document.modificationStamp == stamp &&
                hub.synchronizedDocument(virtualFile) == lifetime
            ) {
                intention.invoke(virtualFile)
            }
        }
    }

    private fun importClient(file: PsiFile): LspClient? {
        if (file.language != LeanLanguage) return null
        val virtualFile = file.virtualFile ?: return null
        val hub = file.project.service<LeanServerHub>()
        val lifetime = hub.synchronizedDocument(virtualFile) ?: return null
        val client = hub.currentClient(lifetime.serverId) ?: return null
        val kinds = client.initializeResult?.capabilities?.codeActionProvider?.right?.codeActionKinds.orEmpty()
        return client.takeIf { CodeActionKind.SourceOrganizeImports in kinds }
    }
}

private fun isImportAction(action: CodeAction): Boolean {
    val kind = action.kind ?: return false
    val organizeImports = CodeActionKind.SourceOrganizeImports
    val matchesKind = kind == organizeImports || kind.startsWith("$organizeImports.")
    return action.disabled == null && action.command == null && matchesKind
}

/**
 * Optimize Imports may change only the current file. Prefer versioned edits over their unversioned
 * fallback as required by LSP; LSP4j also initializes an empty fallback map when that field is absent.
 * The native intention validates document versions and applies the selected edit representation.
 */
internal fun importEdits(action: CodeAction, uri: String): WorkspaceEdit? {
    if (!isImportAction(action)) return null
    val edit = action.edit ?: return null
    val documentChanges = edit.documentChanges
    val selected = if (documentChanges != null) {
        if (documentChanges.isEmpty()) return null
        if (documentChanges.any { it.isRight || it.left.textDocument.uri != uri }) return null
        WorkspaceEdit(documentChanges)
    } else {
        val changes = edit.changes ?: return null
        if (changes.isEmpty() || changes.keys.any { it != uri }) return null
        WorkspaceEdit(changes)
    }
    selected.changeAnnotations = edit.changeAnnotations
    return selected
}

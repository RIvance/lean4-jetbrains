package plugin.lean.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.FakePsiElement
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.util.Positions.isBefore
import plugin.lean.language.LeanLanguage

/** Rider's composite shortcut action hides the action ID used by the platform's LSP definition provider. */
internal class LeanGotoDeclarationHandler : GotoDeclarationHandler {
    init {
        if (ApplicationInfo.getInstance().build.productCode != "RD") throw ExtensionNotApplicableException.create()
    }

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?, offset: Int, editor: Editor
    ): Array<PsiElement>? {
        if (sourceElement?.language != LeanLanguage || ApplicationManager.getApplication().isDispatchThread) return null
        val file = sourceElement.containingFile.virtualFile ?: return null
        val project = sourceElement.project
        val hub = project.service<LeanServerHub>()
        val lifetime = hub.synchronizedDocument(file) ?: return null
        val client = hub.currentClient(lifetime.serverId) ?: return null
        val capability = client.initializeResult?.capabilities?.definitionProvider ?: return null
        if (capability.left == false) return null

        val params = DefinitionParams(TextDocumentIdentifier(lifetime.uri), getLsp4jPosition(editor.document, offset))
        val result = client.sendRequestSync { it.textDocumentService.definition(params) } ?: return null
        if (hub.synchronizedDocument(file) != lifetime) return null

        val psiManager = PsiManager.getInstance(project)
        val targets = definitionTargets(params, result) { uri ->
            client.descriptor.findFileByUri(uri)?.let(psiManager::findFile)
        }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}

internal fun definitionTargets(
    params: DefinitionParams,
    response: Either<out List<Location>, out List<LocationLink>>,
    findFile: (String) -> PsiFile?
): List<PsiElement> {
    val locations = response.map({ it }, { links ->
        // At a token boundary Lean can return both neighbors. The editor prefers the token on the right.
        val hasTargetOnRight = links.any { link ->
            link.originSelectionRange?.let { isBefore(params.position, it.end) } == true
        }
        links.filterNot { link ->
            val origin = link.originSelectionRange ?: return@filterNot false
            val selfLink = link.targetUri == params.textDocument.uri && link.targetSelectionRange == origin
            selfLink || (hasTargetOnRight && !isBefore(params.position, origin.end))
        }.map { Location(it.targetUri, it.targetSelectionRange) }
    })
    return locations.distinct().mapNotNull { location ->
        val file = findFile(location.uri) ?: return@mapNotNull null
        val document = file.viewProvider.document ?: return@mapNotNull null
        val range = getRangeInDocument(document, location.range) ?: return@mapNotNull null
        file.findElementAt(range.startOffset)?.takeIf { it.textRange == range && it.textOffset == range.startOffset }
            ?: DefinitionRangeTarget(file, range)
    }
}

/** A server location can start inside a lexical token or at EOF without an exact PSI element. */
private class DefinitionRangeTarget(file: PsiFile, range: TextRange) : FakePsiElement() {
    private val pointer = SmartPointerManager.getInstance(file.project).createSmartPsiFileRangePointer(file, range)

    override fun getParent() = pointer.element
    override fun getTextRange() = pointer.psiRange?.let(TextRange::create)
    override fun getTextOffset() = textRange?.startOffset ?: -1
    override fun isValid() = pointer.element != null && pointer.psiRange != null

    override fun getName(): String? {
        val file = pointer.element ?: return null
        return textRange?.subSequence(file.node.chars)?.toString()?.ifEmpty { file.name }
    }

    override fun navigationRequest(): NavigationRequest? {
        val file = pointer.element ?: return null
        return textRange?.let { NavigationRequest.sourceNavigationRequest(file, it) }
    }
}

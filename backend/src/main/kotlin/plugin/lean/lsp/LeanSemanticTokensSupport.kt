package plugin.lean.lsp

import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile
import plugin.lean.language.LeanColor
import plugin.lean.language.LeanLanguage

/** Uses the server's classification to select configurable Lean colors. */
class LeanSemanticTokensSupport : LspSemanticTokensSupport() {
    override fun shouldAskServerForSemanticTokens(psiFile: PsiFile) = psiFile.language == LeanLanguage

    override val tokenTypes = (super.tokenTypes + LeanColor.entries.mapNotNull { it.semanticTokenType }).distinct()

    override val tokenModifiers = (super.tokenModifiers + LeanColor.entries.flatMap { it.semanticTokenModifiers })
        .distinct()

    override fun getTextAttributesKey(tokenType: String, modifiers: List<String>) =
        LeanColor.forSemanticToken(tokenType, modifiers)?.key ?: super.getTextAttributesKey(tokenType, modifiers)
}

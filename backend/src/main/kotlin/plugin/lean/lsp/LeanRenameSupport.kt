package plugin.lean.lsp

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.customization.LspRenameSupport
import com.intellij.psi.PsiFile
import plugin.lean.language.LeanLanguage
import plugin.lean.language.LeanLexer
import plugin.lean.language.LeanTokens

/** Lean's server owns semantic renameability; the local parser supplies lexical editor support only. */
class LeanRenameSupport : LspRenameSupport() {
    override fun shouldRunRename(psiFile: PsiFile) = psiFile.language == LeanLanguage

    /** Used only when the server omits prepareRename or explicitly requests the client's default range. */
    override fun getRenameableRangeAtOffset(document: Document, offset: Int): TextRange? {
        val text = document.charsSequence
        if (offset !in 0..text.length) return null
        val lexer = LeanLexer().apply { start(text) }
        while (lexer.tokenType != null && lexer.tokenStart <= offset) {
            if (lexer.tokenType == LeanTokens.IDENTIFIER && offset <= lexer.tokenEnd) {
                if (text[lexer.tokenStart] == '«' && text[lexer.tokenEnd - 1] != '»') return null
                return TextRange(lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return null
    }
}

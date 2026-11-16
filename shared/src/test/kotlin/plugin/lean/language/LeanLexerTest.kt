package plugin.lean.language

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import junit.framework.TestCase

class LeanLexerTest : TestCase() {
    private data class Token(val type: IElementType, val text: String)

    private fun lex(text: String): List<Token> {
        val lexer = LeanLexer()
        lexer.start(text)
        val result = mutableListOf<Token>()
        while (lexer.tokenType != null) {
            assertTrue("Every token must advance", lexer.tokenEnd > lexer.tokenStart)
            result += Token(lexer.tokenType!!, text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        assertEquals(text, result.joinToString("") { it.text })
        return result.filter { it.type != TokenType.WHITE_SPACE }
    }

    fun testNestedCommentsAndDocumentationAreSingleTokens() {
        val text = "/- outer /- nested -/ outer -/ /-- documentation -/ /-! module -/ -- line\ndef"
        assertEquals(
            listOf(
                LeanTokens.BLOCK_COMMENT, LeanTokens.DOC_COMMENT, LeanTokens.DOC_COMMENT,
                LeanTokens.LINE_COMMENT, LeanTokens.IDENTIFIER
            ),
            lex(text).map { it.type }
        )
    }

    fun testUnterminatedCommentsAndStringsRetainAllText() {
        for ((text, type) in listOf("/- /- open -/" to LeanTokens.BLOCK_COMMENT, "\"open\n" to LeanTokens.STRING)) {
            assertEquals(listOf(Token(type, text)), lex(text))
        }
    }

    fun testStringsCharacterLiteralsAndQuotedIdentifiersDoNotCreateComments() {
        val literals = listOf("\"a \\\" -- /- b\"", "r##\"raw \"# /-\"##", "'λ'", "'\\u03bb'", "'\\n'")
        for (literal in literals) assertEquals(listOf(Token(LeanTokens.STRING, literal)), lex(literal))
        assertEquals(listOf(Token(LeanTokens.IDENTIFIER, "«a /- b»")), lex("«a /- b»"))
    }

    fun testUnicodeNamesPrimesAndProjectionBoundaries() {
        val names = listOf("α'", "ℕ", "𝕒", "n₁", "nₐ", "nᵢ", "nⱼ", "get!", "find?")
        assertEquals(names.map { Token(LeanTokens.IDENTIFIER, it) }, lex(names.joinToString(" ")))
        assertEquals(listOf("λ", "Π", "Σ").map { Token(LeanTokens.SYMBOL, it) }, lex("λ Π Σ"))
        assertEquals(listOf("1", ".", "field", "1.25e-3", "0xFF"), lex("1.field 1.25e-3 0xFF").map { it.text })
    }

    fun testRestartingAtAnyTokenBoundaryPreservesTheRemainingTokens() {
        val text = "def α' := r#\"text /-\"# /- nested /- x -/ -/ (42)\n-- comment\nα'"
        val full = LeanLexer()
        full.start(text)
        while (full.tokenType != null) {
            val restarted = LeanLexer()
            restarted.start(text, full.tokenStart, text.length, full.state)
            assertEquals(full.tokenType, restarted.tokenType)
            assertEquals(full.tokenStart, restarted.tokenStart)
            assertEquals(full.tokenEnd, restarted.tokenEnd)
            full.advance()
        }
    }
}

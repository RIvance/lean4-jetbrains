package plugin.lean.language

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object LeanTokens {
    val IDENTIFIER = IElementType("identifier", LeanLanguage)
    val NUMBER = IElementType("number", LeanLanguage)
    val STRING = IElementType("string", LeanLanguage)
    val LINE_COMMENT = IElementType("line comment", LeanLanguage)
    val BLOCK_COMMENT = IElementType("block comment", LeanLanguage)
    val DOC_COMMENT = IElementType("documentation comment", LeanLanguage)
    val SYMBOL = IElementType("symbol", LeanLanguage)
    val LEFT_PAREN = IElementType("(", LeanLanguage)
    val RIGHT_PAREN = IElementType(")", LeanLanguage)
    val LEFT_BRACKET = IElementType("[", LeanLanguage)
    val RIGHT_BRACKET = IElementType("]", LeanLanguage)
    val LEFT_BRACE = IElementType("{", LeanLanguage)
    val RIGHT_BRACE = IElementType("}", LeanLanguage)
}

/**
 * Lexical editor support only. Lean's server owns syntax, extensible keywords, and semantic classification.
 * Multiline literals and nested comments are whole tokens, so every restartable token boundary has state zero.
 */
class LeanLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var start = 0
    private var position = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        end = endOffset
        position = startOffset
        advance()
    }

    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = position
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end

    override fun advance() {
        start = position
        if (position >= end) {
            type = null
            return
        }
        val character = buffer[position]
        type = when {
            character.isWhitespace() -> {
                while (position < end && buffer[position].isWhitespace()) position++
                TokenType.WHITE_SPACE
            }
            matches("--") -> {
                while (position < end && buffer[position] != '\n' && buffer[position] != '\r') position++
                LeanTokens.LINE_COMMENT
            }
            matches("/-") -> blockComment()
            character == '"' -> quotedString()
            character == 'r' && rawString() -> LeanTokens.STRING
            character == '\'' && characterLiteral() -> LeanTokens.STRING
            character == '«' -> {
                position++
                while (position < end && buffer[position] != '»') position++
                if (position < end) position++
                LeanTokens.IDENTIFIER
            }
            character in '0'..'9' -> number()
            LeanIdentifiers.isFirst(codePoint()) -> {
                position += Character.charCount(codePoint())
                while (position < end && LeanIdentifiers.isRest(codePoint())) {
                    position += Character.charCount(codePoint())
                }
                LeanTokens.IDENTIFIER
            }
            else -> {
                position += Character.charCount(codePoint())
                when (character) {
                    '(' -> LeanTokens.LEFT_PAREN
                    ')' -> LeanTokens.RIGHT_PAREN
                    '[' -> LeanTokens.LEFT_BRACKET
                    ']' -> LeanTokens.RIGHT_BRACKET
                    '{' -> LeanTokens.LEFT_BRACE
                    '}' -> LeanTokens.RIGHT_BRACE
                    else -> LeanTokens.SYMBOL
                }
            }
        }
    }

    private fun blockComment(): IElementType {
        val documentation = matches("/--") || matches("/-!")
        position += 2
        var depth = 1
        while (position < end && depth > 0) {
            when {
                matches("/-") -> { depth++; position += 2 }
                matches("-/") -> { depth--; position += 2 }
                else -> position++
            }
        }
        return if (documentation) LeanTokens.DOC_COMMENT else LeanTokens.BLOCK_COMMENT
    }

    private fun quotedString(): IElementType {
        position++
        while (position < end) {
            when (buffer[position++]) {
                '\\' -> if (position < end) position++
                '"' -> break
            }
        }
        return LeanTokens.STRING
    }

    private fun rawString(): Boolean {
        var quote = position + 1
        while (quote < end && buffer[quote] == '#') quote++
        if (quote >= end || buffer[quote] != '"') return false
        val hashes = quote - position - 1
        position = quote + 1
        val closing = "\"" + "#".repeat(hashes)
        while (position < end && !matches(closing)) position++
        if (position < end) position += closing.length
        return true
    }

    private fun characterLiteral(): Boolean {
        var cursor = position + 1
        if (cursor >= end) return false
        if (buffer[cursor] == '\\') {
            cursor++
            if (cursor >= end) return false
            cursor += when (buffer[cursor]) {
                'x' -> 3
                'u' -> 5
                else -> 1
            }
        } else {
            cursor += Character.charCount(Character.codePointAt(buffer, cursor))
        }
        if (cursor >= end || buffer[cursor] != '\'') return false
        position = cursor + 1
        return true
    }

    private fun number(): IElementType {
        if (matches("0x") || matches("0X")) {
            position += 2
            while (position < end && buffer[position].digitToIntOrNull(16) != null) position++
        } else {
            while (position < end && buffer[position] in '0'..'9') position++
            if (position + 1 < end && buffer[position] == '.' && buffer[position + 1] in '0'..'9') {
                position++
                while (position < end && buffer[position] in '0'..'9') position++
            }
            if (position < end && buffer[position] in "eE") {
                var exponent = position + 1
                if (exponent < end && buffer[exponent] in "+-") exponent++
                if (exponent < end && buffer[exponent] in '0'..'9') {
                    position = exponent + 1
                    while (position < end && buffer[position] in '0'..'9') position++
                }
            }
        }
        return LeanTokens.NUMBER
    }

    private fun matches(text: String): Boolean =
        position + text.length <= end && text.indices.all { buffer[position + it] == text[it] }

    private fun codePoint(): Int =
        if (position + 1 < end) Character.codePointAt(buffer, position) else buffer[position].code
}

/*
 * Adapted from Lean's identifier character rules, which deliberately differ from Unicode identifier categories.
 * https://github.com/leanprover/lean4/blob/v4.35.0/src/Init/Meta/Defs.lean
 * Copyright (c) 2019 Microsoft Corporation. Authors: Leonardo de Moura and Sebastian Ullrich.
 * Apache-2.0; the license is included in META-INF/licenses/lean4-LICENSE.txt.
 */
private object LeanIdentifiers {
    fun isFirst(codePoint: Int): Boolean =
        codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code ||
        codePoint == '_'.code || isLetterLike(codePoint)

    fun isRest(codePoint: Int): Boolean =
        isFirst(codePoint) || codePoint in '0'.code..'9'.code ||
        codePoint == '\''.code || codePoint == '!'.code || codePoint == '?'.code ||
        codePoint in 0x2080..0x2089 || codePoint in 0x2090..0x209c || codePoint in 0x1d62..0x1d6a || codePoint == 0x2c7c

    private fun isLetterLike(codePoint: Int): Boolean =
        (codePoint in 0x3b1..0x3c9 && codePoint != 0x3bb) ||
        (codePoint in 0x391..0x3a9 && codePoint != 0x3a0 && codePoint != 0x3a3) ||
        codePoint in 0x3ca..0x3fb || codePoint in 0x1f00..0x1ffe || codePoint in 0x2100..0x214f ||
        codePoint in 0x1d49c..0x1d59f ||
        (codePoint in 0x00c0..0x00ff && codePoint != 0x00d7 && codePoint != 0x00f7) ||
        codePoint in 0x0100..0x017f
}

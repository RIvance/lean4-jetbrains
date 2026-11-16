package plugin.lean.language

import com.intellij.codeInsight.editorActions.QuoteHandler
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class LeanParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = LeanLexer()
    override fun createParser(project: Project?) = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType() = FILE
    override fun getWhitespaceTokens() = TokenSet.create(TokenType.WHITE_SPACE)
    override fun getCommentTokens() = TokenSet.create(
        LeanTokens.LINE_COMMENT, LeanTokens.BLOCK_COMMENT, LeanTokens.DOC_COMMENT
    )

    override fun getStringLiteralElements() = TokenSet.create(LeanTokens.STRING)
    override fun createFile(viewProvider: FileViewProvider): PsiFile {
        return object : PsiFileBase(viewProvider, LeanLanguage) {
            override fun getFileType() = LeanFileType
        }
    }

    override fun createElement(node: ASTNode): PsiElement = error("Lean lexical PSI has no composite nodes")

    companion object {
        private val FILE = IFileElementType(LeanLanguage)
    }
}

class LeanSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = object : SyntaxHighlighterBase() {
        override fun getHighlightingLexer() = LeanLexer()
        override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
            pack(LeanColor.forToken(tokenType)?.key)
    }
}

class LeanCommenter : Commenter {
    override fun getLineCommentPrefix() = "--"
    override fun getBlockCommentPrefix() = "/-"
    override fun getBlockCommentSuffix() = "-/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class LeanBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(
        BracePair(LeanTokens.LEFT_PAREN, LeanTokens.RIGHT_PAREN, false),
        BracePair(LeanTokens.LEFT_BRACKET, LeanTokens.RIGHT_BRACKET, false),
        BracePair(LeanTokens.LEFT_BRACE, LeanTokens.RIGHT_BRACE, true)
    )

    override fun isPairedBracesAllowedBeforeType(leftBraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}

/** Ordinary string quotes use native editing; apostrophes and backticks remain Lean syntax. */
class LeanQuoteHandler : QuoteHandler {
    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean =
        isInsideLiteral(iterator) && offset == iterator.start

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (!isInsideLiteral(iterator) || offset != iterator.end - 1 || offset <= iterator.start) return false
        val text = iterator.document.charsSequence
        if (text[offset] != '"') return false
        var preceding = offset - 1
        while (preceding > iterator.start && text[preceding] == '\\') preceding--
        return (offset - preceding) % 2 == 1
    }

    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean =
        isOpeningQuote(iterator, offset) && !isClosingQuote(iterator, iterator.end - 1)

    override fun isInsideLiteral(iterator: HighlighterIterator): Boolean =
        !iterator.atEnd() &&
        iterator.tokenType == LeanTokens.STRING &&
        iterator.document.charsSequence[iterator.start] == '"'
}

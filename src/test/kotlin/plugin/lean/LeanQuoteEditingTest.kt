package plugin.lean

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings

class LeanQuoteEditingTest : BasePlatformTestCase() {
    private var previousPairQuotes = true

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        previousPairQuotes = CodeInsightSettings.getInstance().AUTOINSERT_PAIR_QUOTE
        CodeInsightSettings.getInstance().AUTOINSERT_PAIR_QUOTE = true
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOINSERT_PAIR_QUOTE = previousPairQuotes
        } finally {
            super.tearDown()
        }
    }

    fun testStringQuotesPairAndTypingTheClosingQuoteMovesPastIt() {
        myFixture.configureByText("Quotes.lean", "def message := <caret>")
        myFixture.type('"')
        myFixture.checkResult("def message := \"<caret>\"")
        myFixture.type("hello\"")
        myFixture.checkResult("def message := \"hello\"<caret>")
    }

    fun testEmptyQuotePairDeletesTogetherAndSupportsUndo() {
        myFixture.configureByText("Quotes.lean", "def message := <caret>")
        myFixture.type('"')
        myFixture.performEditorAction("EditorBackSpace")
        myFixture.checkResult("def message := <caret>")
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(editor)
        myFixture.checkResult("def message := \"<caret>\"")
    }

    fun testQuotesInCommentsAndQuotedNamesAreOrdinaryText() {
        for (before in listOf("-- <caret>", "/- <caret> -/", "/-- <caret> -/", "def «some <caret>name» := 1")) {
            checkTyping(before, '"', before.replace("<caret>", "\"<caret>"))
        }
    }

    fun testApostrophesAndSyntaxQuotationAreNotPairedOrSkipped() {
        checkTyping("def value<caret> := 1", '\'', "def value'<caret> := 1")
        checkTyping("def α<caret>' := 1", '\'', "def α'<caret>' := 1")
        checkTyping("def character := <caret>", '\'', "def character := '<caret>")
        checkTyping("#check <caret>", '`', "#check `<caret>")
    }

    fun testRawStringsAndCharacterLiteralsKeepTheirOwnDelimiters() {
        checkTyping("r#\"text <caret>\"#", '"', "r#\"text \"<caret>\"#")
        checkTyping("'<caret>'", '"', "'\"<caret>'")
        checkTyping("r##<caret>", '"', "r##\"<caret>")
    }

    fun testEscapedQuotesAreInsertedAndUnescapedClosingQuotesAreSkipped() {
        checkTyping("\"escaped \\<caret>\"", '"', "\"escaped \\\"<caret>\"")
        checkTyping("\"backslash \\\\<caret>\"", '"', "\"backslash \\\\\"<caret>")
        checkTyping("\"first\nsecond<caret>\"", '"', "\"first\nsecond\"<caret>")
        checkTyping("\"<caret>text", '"', "\"\"<caret>text")
    }

    fun testMultipleCaretsAndNativePairQuotePreference() {
        checkTyping("<caret>\n<caret>", '"', "\"<caret>\"\n\"<caret>\"")
        CodeInsightSettings.getInstance().AUTOINSERT_PAIR_QUOTE = false
        checkTyping("def message := <caret>", '"', "def message := \"<caret>")
        checkTyping("\"text<caret>\"", '"', "\"text\"<caret>\"")
    }

    private fun checkTyping(before: String, character: Char, after: String) {
        myFixture.configureByText("Quotes.lean", before)
        myFixture.type(character)
        myFixture.checkResult(after)
    }
}

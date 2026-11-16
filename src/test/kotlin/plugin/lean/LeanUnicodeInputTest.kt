package plugin.lean

import com.intellij.openapi.components.service
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings
import plugin.lean.settings.UnicodeInputConfiguration
import plugin.lean.settings.UnicodeInputSettings
import plugin.lean.language.LeanUnicodeInsertion

/** Exercises actual typing and completion extensions through the platform editor. */
class LeanUnicodeInputTest : BasePlatformTestCase() {
    private lateinit var previousConfiguration: UnicodeInputConfiguration

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        previousConfiguration = service<UnicodeInputSettings>().configuration
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration())
    }

    override fun tearDown() {
        try {
            service<UnicodeInputSettings>().update(previousConfiguration)
        } finally {
            super.tearDown()
        }
    }

    fun testUniqueAliasesConvertWhileTyping() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alpha")
        myFixture.checkResult("α<caret>")
    }

    fun testSubmittingAnEditorFieldFinishesPendingInputWithoutAddingASeparator() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alph")
        LeanUnicodeInsertion.finishAbbreviations(myFixture.editor)
        myFixture.checkResult("α<caret>")
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(editor)
        myFixture.checkResult("\\alph<caret>")
    }

    fun testTabChoosesTheShortestPrefixAndSpacePreservesItsSeparator() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        myFixture.performEditorAction("EditorTab")
        myFixture.type(" \\to ")
        myFixture.checkResult("α → <caret>")
    }

    fun testPairedSymbolsPlaceTheCaretInsideWithoutAutomaticExtraBrackets() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\<>x")
        myFixture.checkResult("⟨x<caret>⟩")
    }

    fun testBackspaceKeepsAnActiveAbbreviationEditable() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\al")
        myFixture.performEditorAction("EditorBackSpace")
        myFixture.type("lp")
        myFixture.performEditorAction("EditorTab")
        myFixture.checkResult("α<caret>")
    }

    fun testExistingTextIsOnlyConvertedByExplicitCompletion() {
        myFixture.configureByText("Input.lean", "\\alp<caret>")
        myFixture.type(' ')
        myFixture.checkResult("\\alp <caret>")
        myFixture.configureByText("Complete.lean", "\\alp<caret>")
        val items = checkNotNull(myFixture.completeBasic())
        assertTrue(items.joinToString { it.lookupString }, items.any { it.lookupString == "\\alpha" })
        myFixture.lookup.currentItem = items.first { it.lookupString == "\\alpha" }
        myFixture.finishLookup('\t')
        myFixture.checkResult("α<caret>")
    }

    fun testEnterConvertsThePrefixThenInsertsANewline() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        myFixture.performEditorAction("EditorEnter")
        myFixture.checkResult("α\n<caret>")
    }

    fun testMultipleCaretsHaveIndependentInputRanges() {
        myFixture.configureByText("Input.lean", "<caret>\n<caret>")
        myFixture.type("\\alp")
        myFixture.performEditorAction("EditorTab")
        myFixture.checkResult("α<caret>\nα<caret>")
    }

    fun testOtherLanguagesKeepTheirTypingBehavior() {
        myFixture.configureByText("Input.txt", "<caret>")
        myFixture.type("\\alpha ")
        myFixture.checkResult("\\alpha <caret>")
    }

    fun testSelectedCompletionWinsOverAutomaticPrefixResolution() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\la")
        val items = checkNotNull(myFixture.completeBasic())
        myFixture.lookup.currentItem = items.first { it.lookupString == "\\langle" }
        myFixture.performEditorAction("EditorChooseLookupItemReplace")
        myFixture.checkResult("⟨<caret>")
    }

    fun testCompletionPlacesTheCaretInsidePairedSymbols() {
        myFixture.configureByText("Input.lean", "\\<<caret>")
        val items = checkNotNull(myFixture.completeBasic())
        myFixture.lookup.currentItem = items.first { it.lookupString == "\\<>" }
        myFixture.finishLookup('\t')
        myFixture.checkResult("⟨<caret>⟩")
    }

    fun testLeavingTheAbbreviationConvertsItWithoutMovingTheNewCaret() {
        myFixture.configureByText("Input.lean", "<caret>\nnext")
        myFixture.type("\\alp")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        UIUtil.dispatchAllInvocationEvents()
        myFixture.checkResult("α\nnext<caret>")
    }

    fun testEditingInsideAnAbbreviationKeepsItsWholeRange() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        myFixture.performEditorAction("EditorLeft")
        myFixture.performEditorAction("EditorDelete")
        myFixture.type('p')
        myFixture.performEditorAction("EditorTab")
        myFixture.checkResult("α<caret>")
    }

    fun testEscapeCancelsConversionAndDoubleBackslashInsertsALiteralBackslash() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        myFixture.performEditorAction("EditorEscape")
        myFixture.type(" \\\\alpha ")
        myFixture.checkResult("\\alp \\alpha <caret>")
    }

    fun testConversionCanBeUndoneAndRedone() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        myFixture.performEditorAction("EditorTab")
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        myFixture.checkResult("\\alp<caret>")
        undo.redo(editor)
        myFixture.checkResult("α<caret>")
    }

    fun testContinuingThroughTheCompletionPopupRetainsEagerConversion() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        myFixture.completeBasic()
        myFixture.type("ha")
        UIUtil.dispatchAllInvocationEvents()
        myFixture.checkResult("α<caret>")
    }

    fun testTypingThroughCompletionPlacesFollowingTextInsideACustomPair() {
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(
            customTranslations = mapOf("wrap" to "«\$CURSOR»")
        ))
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\wr")
        myFixture.completeBasic()
        myFixture.type("apx")
        UIUtil.dispatchAllInvocationEvents()
        myFixture.checkResult("«x<caret>»")
    }

    fun testReplacingTheLeaderCancelsItsTrackedInput() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        myFixture.editor.selectionModel.setSelection(0, 4)
        myFixture.type("plain ")
        UIUtil.dispatchAllInvocationEvents()
        myFixture.checkResult("plain <caret>")
    }

    fun testDisablingInputCancelsAnUnfinishedAbbreviation() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(enabled = false))
        myFixture.type("ha ")
        UIUtil.dispatchAllInvocationEvents()
        myFixture.checkResult("\\alpha <caret>")
    }

    fun testEagerConversionCanBeDisabledWithoutLosingTabOrSeparatorConversion() {
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(eagerReplacement = false))
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alpha")
        UIUtil.dispatchAllInvocationEvents()
        myFixture.checkResult("\\alpha<caret>")
        myFixture.performEditorAction("EditorTab")
        myFixture.type(" \\beta ")
        myFixture.checkResult("α β <caret>")
    }

    fun testCustomLeadersTranslationsAndCaretTemplates() {
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(
            leader = ";", customTranslations = mapOf("alpha" to "𝔸", "wrap" to "«\$CURSOR»")
        ))
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type(";alpha ;;alpha ;wrapx")
        myFixture.checkResult("𝔸 ;alpha «x<caret>»")
    }

    fun testSupplementaryUnicodeLeadersUseCompleteCharacters() {
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(leader = "🦄"))
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("🦄alpha 🦄🦄alpha ")
        myFixture.checkResult("α 🦄alpha <caret>")
        myFixture.configureByText("Completion.lean", "🦄alp<caret>")
        val items = checkNotNull(myFixture.completeBasic())
        myFixture.lookup.currentItem = items.first { it.lookupString == "🦄alpha" }
        myFixture.finishLookup('\t')
        myFixture.checkResult("α<caret>")
    }

    fun testNewTranslationsDoNotReinterpretAlreadyStartedInput() {
        myFixture.configureByText("Input.lean", "<caret>")
        myFixture.type("\\alp")
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(customTranslations = mapOf("alpha" to "A")))
        myFixture.type("ha \\alpha")
        myFixture.checkResult("\\alpha A<caret>")
    }

    fun testSymbolInsertionReplacesMultipleSelectionsAndIsUndoable() {
        service<UnicodeInputSettings>().update(UnicodeInputConfiguration(enabled = false))
        myFixture.configureByText("Input.lean", "<selection>old<caret></selection>\n<selection>more<caret></selection>")
        val replacement = service<UnicodeInputSettings>().configuration.catalog.replacement("<>").orElseThrow()
        assertTrue(LeanUnicodeInsertion.insert(myFixture.editor, replacement))
        myFixture.checkResult("⟨<caret>⟩\n⟨<caret>⟩")
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(editor)
        assertEquals("old\nmore", myFixture.editor.document.text)
    }
}

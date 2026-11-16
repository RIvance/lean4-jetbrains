package plugin.lean

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.components.service
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import plugin.lean.language.LeanLanguage
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings

class LeanIndentationTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        CodeStyle.setTemporarySettings(project, CodeStyle.createTestSettings())
    }

    override fun tearDown() {
        try {
            CodeStyle.dropTemporarySettings(project)
        } finally {
            super.tearDown()
        }
    }

    fun testLeanDefaultsUseTwoSpacesWithoutChangingOtherFileTypes() {
        myFixture.configureByText("Example.lean", "<caret>")
        val leanOptions = CodeStyle.getIndentOptions(myFixture.file)
        assertEquals(2, leanOptions.INDENT_SIZE)
        assertEquals(2, leanOptions.TAB_SIZE)
        myFixture.performEditorAction("EditorTab")
        myFixture.checkResult("  <caret>")

        myFixture.configureByText("Other.txt", "<caret>")
        assertEquals(4, CodeStyle.getIndentOptions(myFixture.file).INDENT_SIZE)
    }

    fun testIndentSettingsPageLoadsAndResetsWithoutAFormatter() {
        val settings = CodeStyle.getSettings(project)
        val provider = checkNotNull(LanguageCodeStyleSettingsProvider.forLanguage(LeanLanguage))
        val configurable = provider.createConfigurable(settings, CodeStyle.createTestSettings())
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset(settings)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testEnterContinuesIndentAndAddsOneLevelAfterEitherTacticBullet() {
        checkEnter("  exact h<caret>", "  exact h\n  <caret>")
        checkEnter("  · exact h<caret>", "  · exact h\n    <caret>")
        checkEnter("  . exact h<caret>", "  . exact h\n    <caret>")
        checkEnter("  · <caret>exact h", "  · \n    <caret>exact h")
        checkEnter("  ·<caret>", "  ·\n  <caret>")
        checkEnter("  .field<caret>", "  .field\n  <caret>")
        checkEnter("    <caret>", "    \n    <caret>")
    }

    fun testBulletsInsideMultilineTokensDoNotIncreaseIndent() {
        checkEnter("/-\n  · text<caret>\n-/", "/-\n  · text\n  <caret>\n-/")
        checkEnter("\"\n  · text<caret>\n\"", "\"\n  · text\n  <caret>\n\"")
        checkEnter("«\n  · text<caret>\n»", "«\n  · text\n  <caret>\n»")
    }

    fun testEnterUsesConfiguredIndentAndTabWidths() {
        val options = CodeStyle.getSettings(project).getCommonSettings(LeanLanguage).indentOptions!!
        options.INDENT_SIZE = 4
        checkEnter("  · exact h<caret>", "  · exact h\n      <caret>")

        options.USE_TAB_CHARACTER = true
        options.TAB_SIZE = 4
        options.INDENT_SIZE = 2
        checkEnter(" \t· exact h<caret>", " \t· exact h\n\t  <caret>")
    }

    fun testMultipleCaretsKeepIndependentIndentation() {
        checkEnter(
            "  · exact h<caret>\n    exact h<caret>",
            "  · exact h\n    <caret>\n    exact h\n    <caret>"
        )
    }

    private fun checkEnter(before: String, after: String) {
        myFixture.configureByText("Example.lean", before)
        myFixture.performEditorAction("EditorEnter")
        myFixture.checkResult(after)
    }
}

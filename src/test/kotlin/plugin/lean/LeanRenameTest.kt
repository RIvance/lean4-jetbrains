package plugin.lean

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import plugin.lean.lsp.LeanRenameSupport
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings

class LeanRenameTest : BasePlatformTestCase() {
    private val support = LeanRenameSupport()

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
    }

    fun testLeanFilesDelegateRenameToTheirLanguageServer() {
        myFixture.configureByText("Example.lean", "def value := 1")
        assertTrue(support.shouldRunRename(myFixture.file))
        myFixture.configureByText("Other.txt", "def value := 1")
        assertFalse(support.shouldRunRename(myFixture.file))
    }

    fun testFallbackRangesFollowLeanIdentifiersAndIncludeTheirEndPosition() {
        checkRange("def st<caret>ep := 1", "step")
        checkRange("step<caret>", "step")
        checkRange("Checks.<caret>step", "step")
        checkRange("Checks<caret>.step", "Checks")
        checkRange("α₁'<caret>", "α₁'")
        checkRange("𝒜<caret>₂", "𝒜₂")
        checkRange("check<caret>!", "check!")
        checkRange("«quoted na<caret>me»", "«quoted name»")
    }

    fun testFallbackDoesNotSelectCommentsLiteralsOrIncompleteNames() {
        for (source in listOf(
            "-- va<caret>lue", "/- outer /- va<caret>lue -/ -/", "\"va<caret>lue\"",
            "r#\"va<caret>lue\"#", "'<caret>x'", "12<caret>3", "f <caret> g", "<caret>",
            "«unfinished<caret>"
        )) {
            checkRange(source, null)
        }
        val document = EditorFactory.getInstance().createDocument("value")
        assertNull(support.getRenameableRangeAtOffset(document, -1))
        assertNull(support.getRenameableRangeAtOffset(document, document.textLength + 1))
    }

    private fun checkRange(source: String, expected: String?) {
        val offset = source.indexOf("<caret>")
        val document = EditorFactory.getInstance().createDocument(source.replace("<caret>", ""))
        val range = support.getRenameableRangeAtOffset(document, offset)
        assertEquals(source, expected, range?.let(document::getText))
    }
}

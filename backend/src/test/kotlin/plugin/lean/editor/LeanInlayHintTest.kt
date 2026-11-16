package plugin.lean.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintLabelPart
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Assert.assertThrows
import java.io.IOException

class LeanInlayHintTest : BasePlatformTestCase() {
    fun testSnapshotRetainsIndependentActionsAndEscapesPlainText() {
        val document = EditorFactory.getInstance().createDocument("example")
        val part = InlayHintLabelPart("Type").apply {
            location = Location("file:///declarations.lean", Range(Position(2, 0), Position(2, 4)))
            command = Command("Explain type", "lean.explain", listOf("Type"))
            tooltip = Either.forLeft("Type <u>")
        }
        val edit = TextEdit(Range(Position(0, 7), Position(0, 7)), " : Type")
        val raw = InlayHint(Position(0, 7), Either.forRight(listOf(part))).apply {
            textEdits = listOf(edit)
            tooltip = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "**Inferred type**"))
        }
        val hint = LeanInlayHint.from(document, raw)
        part.value = "changed"
        part.command.command = "changed"
        edit.newText = "changed"

        assertEquals("Type", hint.parts.single().text)
        assertEquals(3, hint.parts.single().actions.size)
        assertTrue(hint.tooltip!!.contains("<strong>Inferred type</strong>"))
        assertTrue(hint.tooltip.contains("Type &lt;u&gt;"))
        val operations = hint.parts.single().actions.map { it.operation }
        assertTrue(operations[0] is LeanInlayHint.Operation.Navigate)
        assertTrue((operations[1] as LeanInlayHint.Operation.Command).command.toString().contains("lean.explain"))
        assertTrue((operations[2] as LeanInlayHint.Operation.Insert).edits.toString().contains(" : Type"))
    }

    fun testInsertionOrderAtTheSamePositionMatchesTheServerOrder() {
        val document = EditorFactory.getInstance().createDocument("ab")
        val range = Range(Position(0, 1), Position(0, 1))
        WriteCommandAction.runWriteCommandAction(project) {
            LeanInlayService.applyEdits(document, listOf(TextEdit(range, "X"), TextEdit(range, "Y")))
        }
        assertEquals("aXYb", document.text)
    }

    fun testInvalidEditBatchesLeaveTheEntireDocumentUnchanged() {
        val document = EditorFactory.getInstance().createDocument("abcd")
        val valid = TextEdit(Range(Position(0, 0), Position(0, 1)), "X")
        val invalid = TextEdit(Range(Position(5, 0), Position(5, 1)), "Y")
        val overlapping = TextEdit(Range(Position(0, 0), Position(0, 3)), "Z")
        WriteCommandAction.runWriteCommandAction(project) {
            for (edits in listOf(listOf(valid, invalid), listOf(valid, overlapping))) {
                assertThrows(IOException::class.java) { LeanInlayService.applyEdits(document, edits) }
                assertEquals("abcd", document.text)
            }
        }
    }

    fun testMalformedHintPositionsAndLabelsAreRejected() {
        val document = EditorFactory.getInstance().createDocument("example")
        assertThrows(IOException::class.java) {
            LeanInlayHint.from(document, InlayHint(Position(2, 0), Either.forLeft("hint")))
        }
        assertThrows(IOException::class.java) {
            LeanInlayHint.from(document, InlayHint(Position(0, 0), Either.forLeft("")))
        }
    }
}

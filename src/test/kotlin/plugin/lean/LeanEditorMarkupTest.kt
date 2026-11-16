package plugin.lean

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import plugin.lean.editor.LeanDocumentMarkup
import plugin.lean.editor.LeanProofMarker
import plugin.lean.editor.LeanProofMarkers
import plugin.lean.editor.LeanProofStatus
import plugin.lean.language.LeanColor
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanSettings
import plugin.lean.tooling.lsp.LeanProcessingKind
import plugin.lean.tooling.lsp.LeanProcessingRange
import plugin.lean.tooling.lsp.LspPosition
import java.io.IOException

class LeanEditorMarkupTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
    }

    fun testProofMarkersUseTagsAndFullRangesIncludingSilentDiagnostics() {
        val snapshot = snapshot("""
            {
                "leanTags": [2], "isSilent": true, "message": "A translated completion message",
                "range": {"start": {"line": 1, "character": 3}, "end": {"line": 1, "character": 9}}
            },
            {
                "leanTags": [1], "severity": 1,
                "range": {"start": {"line": 3, "character": 0}, "end": {"line": 3, "character": 4}},
                "fullRange": {"start": {"line": 3, "character": 0}, "end": {"line": 6, "character": 0}}
            },
            {"message": "Goals accomplished!"},
            {"leanTags": [99], "message": "unsolved goals"}
        """)
        val proofs = LeanProofMarkers.fromDiagnostics(snapshot)
        assertEquals("file:///Example.lean", proofs.uri)
        assertEquals(7, proofs.version)
        assertEquals(listOf(
            LeanProofMarker(1, LeanProofStatus.ACCOMPLISHED),
            LeanProofMarker(5, LeanProofStatus.UNSOLVED)
        ), proofs.markers)
        assertThrows(UnsupportedOperationException::class.java) { (proofs.markers as MutableList).clear() }
    }

    fun testProofMarkersHandleEmptyRangesAndMergeStatusesOnTheSameLine() {
        val snapshot = snapshot("""
            {
                "leanTags": [2, 2],
                "range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 0}}
            },
            {
                "leanTags": [1], "fullRange": null,
                "range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 0}}
            },
            {
                "leanTags": [1],
                "range": {"start": {"line": 1, "character": 2}, "end": {"line": 3, "character": 4}}
            }
        """)
        val proofs = LeanProofMarkers.fromDiagnostics(JsonObject(snapshot + ("version" to JsonNull)))
        assertNull(proofs.version)
        assertEquals(listOf(
            LeanProofMarker(0, LeanProofStatus.UNSOLVED),
            LeanProofMarker(3, LeanProofStatus.UNSOLVED)
        ), proofs.markers)
        assertTrue(LeanProofMarkers.fromDiagnostics(snapshot("")).markers.isEmpty())
    }

    fun testMalformedProofMetadataDoesNotBecomeAStatusMarker() {
        val valid = """
            {
                "leanTags": [1],
                "range": {"start": {"line": 1, "character": 2}, "end": {"line": 3, "character": 4}}
            }
        """
        for (invalid in listOf(
            valid.replace("[1]", "[\"1\"]"),
            valid.replace("[1]", "1"),
            valid.replace("\"line\": 1", "\"line\": -1"),
            valid.replace("\"line\": 3", "\"line\": 0"),
            valid.replace("\"line\": 1", "\"line\": 1.5"),
            valid.replace("\"character\": 4", "\"character\": 2147483648")
        )) {
            assertThrows(IOException::class.java) { LeanProofMarkers.fromDiagnostics(snapshot(invalid)) }
        }
    }

    fun testNativeProofAndProcessingMarkupReplaceIndependentlyAndDisposeOnlyOwnedMarkers() {
        myFixture.configureByText("Example.lean", "example : True := by\n  trivial\n")
        val document = myFixture.editor.document
        val model = DocumentMarkupModel.forDocument(document, project, true)
        val foreign = model.addLineHighlighter(null, 0, HighlighterLayer.ADDITIONAL_SYNTAX)
        val markup = LeanDocumentMarkup(project, document)
        try {
            markup.replaceProgress(listOf(LeanProcessingRange(
                LspPosition(0, 0), LspPosition(2, 0), LeanProcessingKind.PROCESSING
            )))
            val progress = model.allHighlighters.single { it.textAttributesKey == LeanColor.PROCESSING_STRIPE.key }
            markup.replaceProofs(listOf(LeanProofMarker(0, LeanProofStatus.ACCOMPLISHED)))
            val complete = model.allHighlighters.single { it.gutterIconRenderer != null }
            assertEquals("Lean: Goals accomplished", complete.gutterIconRenderer!!.accessibleName)
            assertTrue(complete.gutterIconRenderer!!.icon.iconWidth > 0)
            assertTrue(IconLoader.getDarkIcon(complete.gutterIconRenderer!!.icon, true).iconWidth > 0)

            markup.replaceProofs(listOf(LeanProofMarker(1, LeanProofStatus.UNSOLVED)))
            assertFalse(complete.isValid)
            assertTrue(progress.isValid)
            val unsolved = model.allHighlighters.single { it.gutterIconRenderer != null }
            assertEquals("Lean: Unsolved goals", unsolved.gutterIconRenderer!!.tooltipText)
            assertEquals(document.getLineStartOffset(1), unsolved.startOffset)
            assertTrue(IconLoader.getDarkIcon(unsolved.gutterIconRenderer!!.icon, true).iconWidth > 0)
            markup.replaceProgress(emptyList())
            assertFalse(progress.isValid)
            assertTrue(unsolved.isValid)
            markup.dispose()
            assertFalse(unsolved.isValid)
            assertTrue(foreign.isValid)
        } finally {
            markup.dispose()
            foreign.dispose()
        }
    }

    fun testProofMarkupClampsAtDocumentEndAndCanBeClearedOnAnEmptyDocument() {
        myFixture.configureByText("Empty.lean", "")
        val document = myFixture.editor.document
        val model = DocumentMarkupModel.forDocument(document, project, true)
        val markup = LeanDocumentMarkup(project, document)
        try {
            markup.replaceProofs(listOf(LeanProofMarker(4, LeanProofStatus.UNSOLVED)))
            val marker = model.allHighlighters.single { it.gutterIconRenderer != null }
            assertEquals(0, marker.startOffset)
            markup.replaceProofs(emptyList())
            assertFalse(marker.isValid)
        } finally {
            markup.dispose()
        }
    }

    private fun snapshot(diagnostics: String): JsonObject = JsonObject(mapOf(
        "uri" to JsonPrimitive("file:///Example.lean"),
        "version" to JsonPrimitive(7),
        "diagnostics" to Json.parseToJsonElement("[$diagnostics]") as JsonArray
    ))
}

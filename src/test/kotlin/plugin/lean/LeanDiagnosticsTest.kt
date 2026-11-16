package plugin.lean

import com.intellij.lang.annotation.HighlightSeverity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.Assert.*
import org.junit.Test
import plugin.lean.lsp.LeanDiagnostics
import plugin.lean.lsp.LeanDiagnosticsSupport
import java.io.IOException

class LeanDiagnosticsTest {
    @Test
    fun incrementalBatchesAppendAndFullBatchesReplace() {
        val first = notification(listOf(message("first")))
        val initial = LeanDiagnostics.accumulate(null, first)
        val next = notification(listOf(message("second")), incremental = true)
        val combined = LeanDiagnostics.accumulate(initial, next)
        assertEquals(listOf("first", "second"), messages(combined))
        assertEquals(JsonPrimitive(false), combined["isIncremental"])
        assertEquals(listOf("first"), messages(initial))
        assertEquals(listOf("first"), messages(first))
        assertEquals(listOf("second"), messages(next))
        assertEquals(emptyList<String>(), messages(LeanDiagnostics.accumulate(combined, notification(emptyList()))))
    }

    @Test
    fun snapshotsDoNotMixDocumentsOrRevisions() {
        val initial = LeanDiagnostics.accumulate(null, notification(listOf(message("old"))))
        val changed = notification(listOf(message("new")), version = 2, incremental = true)
        val otherFile = notification(listOf(message("other")), uri = "file:///Other.lean", incremental = true)
        assertEquals(listOf("new"), messages(LeanDiagnostics.accumulate(initial, changed)))
        assertEquals(listOf("other"), messages(LeanDiagnostics.accumulate(initial, otherFile)))
        assertEquals(listOf("old"), messages(initial))
    }

    @Test
    fun initialAndUnversionedIncrementalBatchesAreSupported() {
        val first = notification(listOf(message("first")), version = null, incremental = true)
        val missingVersion = JsonObject(first - "version")
        val initial = LeanDiagnostics.accumulate(null, missingVersion)
        val next = notification(listOf(message("second")), version = null, incremental = true)
        assertEquals(listOf("first", "second"), messages(LeanDiagnostics.accumulate(initial, next)))
    }

    @Test
    fun silentMessagesRemainAvailableToInfoviewAndMetadataSurvivesEditorProjection() {
        val visible = Json.parseToJsonElement("""
            {
                "range": {"start": {"line": 1, "character": 0}, "end": {"line": 1, "character": 3}},
                "fullRange": {"start": {"line": 1, "character": 0}, "end": {"line": 4, "character": 7}},
                "message": "visible", "severity": 3, "code": "Lean.suggestion", "leanTags": [1],
                "data": {"suggestion": {"replacement": "rfl"}}
            }
        """).jsonObject
        val silent = JsonObject(message("at the cursor") + ("isSilent" to JsonPrimitive(true)))
        val snapshot = LeanDiagnostics.accumulate(null, notification(listOf(visible, silent)))
        val editor = LeanDiagnostics.forEditor(snapshot)
        assertEquals(listOf("visible", "at the cursor"), messages(snapshot))
        assertEquals(listOf(visible), editor.getValue("diagnostics").jsonArray.toList())
        val native = gson.fromJson(editor.toString(), PublishDiagnosticsParams::class.java)
        assertEquals("Lean.suggestion", native.diagnostics.single().code.left)
        assertEquals(
            gson.fromJson(visible.getValue("data").toString(), Any::class.java),
            gson.fromJson(gson.toJson(native.diagnostics.single().data), Any::class.java)
        )
    }

    @Test
    fun malformedExtensionFlagsLeaveThePreviousSnapshotIntact() {
        val initial = LeanDiagnostics.accumulate(null, notification(listOf(message("retained"))))
        val invalidFlag = JsonObject(notification(emptyList()) + ("isIncremental" to JsonPrimitive("true")))
        val invalidSilent = JsonObject(message("invalid") + ("isSilent" to JsonPrimitive(1)))
        assertThrows(IOException::class.java) { LeanDiagnostics.accumulate(initial, invalidFlag) }
        assertThrows(IOException::class.java) {
            LeanDiagnostics.accumulate(initial, notification(listOf(invalidSilent)))
        }
        assertEquals(listOf("retained"), messages(initial))
    }

    @Test
    fun informationalDiagnosticsAreNotWarnings() {
        val support = LeanDiagnosticsSupport()
        val diagnostic = Diagnostic()
        diagnostic.severity = DiagnosticSeverity.Information
        assertSame(HighlightSeverity.INFORMATION, support.getHighlightSeverity(diagnostic))
        diagnostic.severity = DiagnosticSeverity.Hint
        assertSame(HighlightSeverity.INFORMATION, support.getHighlightSeverity(diagnostic))
        diagnostic.severity = DiagnosticSeverity.Warning
        assertSame(HighlightSeverity.WARNING, support.getHighlightSeverity(diagnostic))
        diagnostic.severity = DiagnosticSeverity.Error
        assertSame(HighlightSeverity.ERROR, support.getHighlightSeverity(diagnostic))
    }

    private fun notification(
        diagnostics: List<JsonObject>,
        version: Int? = 1,
        incremental: Boolean? = null,
        uri: String = "file:///Main.lean"
    ) = buildJsonObject {
        put("uri", uri)
        put("version", version?.let(::JsonPrimitive) ?: JsonNull)
        if (incremental != null) put("isIncremental", incremental)
        put("diagnostics", JsonArray(diagnostics))
    }

    private fun message(text: String) = buildJsonObject {
        put("range", Json.parseToJsonElement(
            """{"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 1}}"""
        ))
        put("message", text)
    }

    private fun messages(snapshot: JsonObject) = snapshot.getValue("diagnostics").jsonArray.map {
        it.jsonObject.getValue("message").jsonPrimitive.content
    }

    companion object {
        private val gson = MessageJsonHandler(emptyMap()).gson
    }
}

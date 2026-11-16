package plugin.lean.lsp

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.IOException

/** Normalizes Lean's append notifications to full snapshots without losing diagnostic extension fields. */
object LeanDiagnostics {
    /** The previous value, when present, is a snapshot returned by this operation. Inputs remain unchanged. */
    fun accumulate(previous: JsonObject?, incoming: JsonObject): JsonObject {
        val diagnostics = incoming["diagnostics"] as? JsonArray
            ?: throw IOException("Lean diagnostics must contain an array of messages")
        diagnostics.forEach { diagnostic ->
            val fields = diagnostic as? JsonObject ?: throw IOException("A Lean diagnostic must be an object")
            boolean(fields["isSilent"], "isSilent")
        }
        val incremental = boolean(incoming["isIncremental"], "isIncremental")
        val accumulated = if (
            incremental && previous != null &&
            previous["uri"] == incoming["uri"] && version(previous) == version(incoming)
        ) {
            JsonArray(previous.getValue("diagnostics").jsonArray + diagnostics)
        } else {
            diagnostics
        }
        return JsonObject(incoming + mapOf(
            "isIncremental" to JsonPrimitive(false),
            "diagnostics" to accumulated
        ))
    }

    /** Accepts a normalized snapshot. Native diagnostics exclude silent messages; InfoView receives them all. */
    fun forEditor(snapshot: JsonObject): JsonObject {
        val visible = snapshot.getValue("diagnostics").jsonArray.filter { diagnostic ->
            (diagnostic as JsonObject)["isSilent"]?.jsonPrimitive?.booleanOrNull != true
        }
        return JsonObject(snapshot + ("diagnostics" to JsonArray(visible)))
    }

    private fun boolean(value: JsonElement?, field: String): Boolean {
        if (value == null || value == JsonNull) return false
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw IOException("Lean diagnostic field '$field' must be a boolean")
    }

    private fun version(params: JsonObject): JsonElement? = params["version"]?.takeUnless { it == JsonNull }
}

/** Results from commands such as #check and #eval are information, rather than inspection warnings. */
class LeanDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun getHighlightSeverity(diagnostic: Diagnostic): HighlightSeverity? = when (diagnostic.severity) {
        DiagnosticSeverity.Information, DiagnosticSeverity.Hint -> HighlightSeverity.INFORMATION
        else -> super.getHighlightSeverity(diagnostic)
    }
}

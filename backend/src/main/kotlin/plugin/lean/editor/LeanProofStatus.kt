package plugin.lean.editor

import com.intellij.openapi.util.IconLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import plugin.lean.language.LeanIcons
import plugin.lean.tooling.lsp.LspPosition
import java.io.IOException
import javax.swing.Icon

/** Proof status comes from Lean's diagnostic tags, never from the diagnostic's displayed message. */
enum class LeanProofStatus(val tag: Int, val description: String, private val iconPath: String) {
    UNSOLVED(1, "Unsolved goals", "/icons/unsolvedGoals.svg"),
    ACCOMPLISHED(2, "Goals accomplished", "/icons/goalsAccomplished.svg");

    val icon: Icon get() = IconLoader.getIcon(iconPath, LeanIcons::class.java)

    companion object {
        fun fromTag(tag: Int): LeanProofStatus? = entries.firstOrNull { it.tag == tag }
    }
}

data class LeanProofMarker(val line: Int, val status: LeanProofStatus) {
    init {
        require(line >= 0) { "A proof marker must have a nonnegative line" }
    }
}

/** A gutter projection of a complete diagnostic snapshot, including silent diagnostics. */
class LeanProofMarkers private constructor(
    val uri: String,
    val version: Int?,
    val markers: List<LeanProofMarker>
) {
    companion object {
        fun fromDiagnostics(snapshot: JsonObject): LeanProofMarkers {
            val uri = (snapshot["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotEmpty() } ?: throw IOException("Lean diagnostics require a document URI")
            val version = snapshot["version"]?.takeUnless { it == JsonNull }?.let { integer(it, "version") }
            val diagnostics = snapshot["diagnostics"] as? JsonArray
                ?: throw IOException("Lean diagnostics require an array of messages")
            val markers = diagnostics.flatMap { value ->
                val diagnostic = objectValue(value, "diagnostic")
                val tags = diagnostic["leanTags"]?.takeUnless { it == JsonNull } ?: return@flatMap emptyList()
                if (tags !is JsonArray) throw IOException("Lean diagnostic tags must be an array")
                tags.mapNotNull { LeanProofStatus.fromTag(integer(it, "diagnostic tag")) }.map { status ->
                    val rangeValue = if (status == LeanProofStatus.UNSOLVED) {
                        diagnostic["fullRange"]?.takeUnless { it == JsonNull } ?: diagnostic["range"]
                    } else {
                        diagnostic["range"]
                    }
                    val range = objectValue(rangeValue, "diagnostic range")
                    val start = position(range["start"])
                    val end = position(range["end"])
                    if (end.isBefore(start)) throw IOException("A diagnostic range ends before its start")
                    val line = when (status) {
                        LeanProofStatus.ACCOMPLISHED -> start.line()
                        LeanProofStatus.UNSOLVED -> {
                            if (end.character() == 0 && end.line() > start.line()) end.line() - 1 else end.line()
                        }
                    }
                    LeanProofMarker(line, status)
                }
            }
            // Several proofs can share a line. An unfinished proof takes precedence over a completed one.
            val combined = markers.groupBy { it.line }.map { (line, entries) ->
                val status = if (entries.any { it.status == LeanProofStatus.UNSOLVED }) {
                    LeanProofStatus.UNSOLVED
                } else {
                    LeanProofStatus.ACCOMPLISHED
                }
                LeanProofMarker(line, status)
            }.sortedBy { it.line }
            return LeanProofMarkers(uri, version, java.util.List.copyOf(combined))
        }

        private fun position(value: JsonElement?): LspPosition {
            val fields = objectValue(value, "position")
            val line = integer(fields["line"], "line")
            val character = integer(fields["character"], "character")
            if (line < 0 || character < 0) throw IOException("LSP positions must be nonnegative")
            return LspPosition(line, character)
        }

        private fun objectValue(value: JsonElement?, name: String): JsonObject =
            value as? JsonObject ?: throw IOException("Expected an object for $name")

        private fun integer(value: JsonElement?, name: String): Int =
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw IOException("Expected an integer for $name")
    }
}

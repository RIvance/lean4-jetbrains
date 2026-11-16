package plugin.lean.rpc

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanProofFoldsTest {
    @Test
    fun exactDocumentIdentitySurvivesRpcIncludingIncompleteSurrogates() {
        val text = "example α := by\n  exact \uD83D"
        val original = LeanProofFolds(LeanProofFolds.digest(text), listOf(LeanProofFold(15, text.length)))
        val received = Json.decodeFromString<LeanProofFolds>(Json.encodeToString(original))
        assertEquals(original, received)
        assertTrue(received.matches(text))
        assertFalse(received.matches(text.replace('α', 'β')))
        assertFalse(received.matches(text.replace('\uD83D', '\uD83E')))
        assertFalse(received.matches(text + "\n"))
    }
}

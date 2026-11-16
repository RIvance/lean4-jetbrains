package plugin.lean.rpc

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LeanConfigurationTest {
    @Test
    fun configurationRetainsProcessValuesAcrossRpcAndDefaultsOlderSavedFields() {
        val configuration = LeanConfiguration(
            serverArguments = listOf("-j", "2", "", "quoted \"value\"", "α β"),
            environment = mapOf("LEAN_SAMPLE" to "space; equals= and\nnew line", "EMPTY" to "")
        )
        assertEquals(configuration, Json.decodeFromString<LeanConfiguration>(Json.encodeToString(configuration)))
        val previous = Json.decodeFromString<LeanConfiguration>("""{"enabled":false}""")
        assertEquals(LeanConfiguration(enabled = false), previous)
    }
}

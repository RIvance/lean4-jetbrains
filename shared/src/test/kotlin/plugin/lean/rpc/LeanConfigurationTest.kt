package plugin.lean.rpc

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import plugin.lean.tooling.ElanSelectionKind

class LeanConfigurationTest {
    @Test
    fun inspectionCarriesTheHostSelectionSourceAcrossRpc() {
        val unavailable = ActiveToolchainInfo.Unavailable("Missing toolchain", "requested")
        val inventory = ToolchainInspection.Available("elan", "4.2.1", "/work/project", emptyList(), unavailable)
        val pin = ToolchainSelectionOrigin(ElanSelectionKind.CONFIGURATION_FILE, "/work/lean-toolchain")
        for (source in listOf(null, pin)) {
            val inspection: ToolchainInspection = inventory.copy(origin = source)
            assertEquals(inspection, Json.decodeFromString<ToolchainInspection>(Json.encodeToString(inspection)))
        }
    }

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

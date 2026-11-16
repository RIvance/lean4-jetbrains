package plugin.lean.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.InvalidDataException
import com.intellij.util.xmlb.XmlSerializer
import kotlinx.serialization.json.Json
import org.jdom.Element
import plugin.lean.persistence.decodeLiteralText
import plugin.lean.persistence.encodeLiteralText
import plugin.lean.rpc.LeanConfiguration

@Service(Service.Level.PROJECT)
@State(name = "LeanSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LeanSettings : PersistentStateComponent<Element> {
    @Volatile
    var configuration = LeanConfiguration()
        private set

    @Synchronized
    fun update(configuration: LeanConfiguration) {
        this.configuration = configuration.snapshot()
    }

    /** A draft may replace only the saved values from which it was edited. */
    @Synchronized
    fun compareAndSet(expected: LeanConfiguration, replacement: LeanConfiguration): Boolean {
        if (configuration != expected) return false
        configuration = replacement.snapshot()
        return true
    }

    override fun getState(): Element {
        // Native storage expands path macros before loadState, including in arguments and environment keys.
        val encoded = encodeLiteralText(json.encodeToString(configuration))
        return Element("state").setAttribute("literalConfiguration", encoded)
    }

    override fun loadState(state: Element) {
        val encoded = state.getAttributeValue("literalConfiguration")
        val restored = if (encoded == null) {
            // Read existing workspace.xml preferences; subsequent saves use the literal format.
            XmlSerializer.deserialize(state, LegacyState::class.java).configuration()
        } else {
            try {
                json.decodeFromString<LeanConfiguration>(decodeLiteralText(encoded))
            } catch (error: IllegalArgumentException) {
                throw InvalidDataException("Saved Lean settings are invalid.", error)
            }
        }
        update(restored)
    }

    private class LegacyState {
        var elanExecutable = ""
        var toolchain = ""
        var enabled = true
        var serverArguments: MutableList<String> = mutableListOf()
        var environment: MutableMap<String, String> = linkedMapOf()

        fun configuration() = LeanConfiguration(elanExecutable, toolchain, enabled, serverArguments, environment)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

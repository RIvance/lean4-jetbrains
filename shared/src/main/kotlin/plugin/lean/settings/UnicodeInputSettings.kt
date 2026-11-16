package plugin.lean.settings

import com.intellij.ide.settings.RemoteSettingInfo
import com.intellij.ide.settings.RemoteSettingInfoProvider
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plugin.lean.tooling.UnicodeAbbreviations
import java.util.Collections

/** A validated snapshot owns its catalog; an in-progress abbreviation retains that snapshot's identity. */
class UnicodeInputConfiguration(
    val enabled: Boolean = true,
    val eagerReplacement: Boolean = true,
    val leader: String = "\\",
    customTranslations: Map<String, String> = emptyMap()
) {
    val customTranslations: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(customTranslations))
    val catalog: UnicodeAbbreviations

    init {
        require(leader.codePointCount(0, leader.length) == 1) { "The leader must be a single character." }
        val codePoint = leader.codePointAt(0)
        require(!Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint)) {
            "The leader cannot be whitespace or a control character."
        }
        require(codePoint !in 0xD800..0xDFFF) { "The leader must be a complete Unicode character." }
        for ((abbreviation, template) in this.customTranslations) {
            require(abbreviation.isNotEmpty() && abbreviation.none { it == '\n' || it == '\r' }) {
                "An abbreviation must be nonempty and stay on one line."
            }
            require(template.isNotEmpty()) { "An abbreviation's replacement cannot be empty." }
        }
        catalog = UnicodeAbbreviations(defaultTranslations + mapOf(leader to leader) + this.customTranslations)
    }

    private companion object {
        val defaultTranslations: Map<String, String> by lazy {
            val resource = checkNotNull(UnicodeInputConfiguration::class.java.getResourceAsStream(
                "/lean/unicode/abbreviations.json"
            ))
            val translations = resource.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }
            translations.jsonObject.mapValues { it.value.jsonPrimitive.content }
        }
    }
}

@Service
@State(name = "LeanUnicodeInput", storages = [Storage("lean.xml")], category = SettingsCategory.CODE)
class UnicodeInputSettings : PersistentStateComponent<UnicodeInputSettings.StoredState> {
    class StoredState {
        var enabled = true
        var eagerReplacement = true
        var leader = "\\"
        var customTranslations: MutableMap<String, String> = linkedMapOf()
    }

    @Volatile
    var configuration = UnicodeInputConfiguration()
        private set

    fun update(configuration: UnicodeInputConfiguration) { this.configuration = configuration }

    override fun getState(): StoredState {
        val snapshot = configuration
        return StoredState().also {
            it.enabled = snapshot.enabled
            it.eagerReplacement = snapshot.eagerReplacement
            it.leader = snapshot.leader
            it.customTranslations = LinkedHashMap(snapshot.customTranslations)
        }
    }

    override fun loadState(state: StoredState) {
        configuration = UnicodeInputConfiguration(
            state.enabled, state.eagerReplacement, state.leader, state.customTranslations
        )
    }

    override fun noStateLoaded() { configuration = UnicodeInputConfiguration() }
}

class UnicodeInputRemoteSettings : RemoteSettingInfoProvider {
    override fun getRemoteSettingsInfo(): Map<String, RemoteSettingInfo> = mapOf(
        "LeanUnicodeInput" to RemoteSettingInfo(RemoteSettingInfo.Direction.InitialFromFrontend)
    )
}

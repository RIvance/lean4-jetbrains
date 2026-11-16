package plugin.lean.infoview

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The renderer owns this schema; the frontend persists it without creating a second settings model. */
@Service(Service.Level.PROJECT)
@State(name = "LeanInfoview", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class InfoviewSettings : PersistentStateComponent<InfoviewSettings.StoredState> {
    class StoredState {
        var configuration: String? = null
        var autoOpen: Boolean = true
    }

    var configuration: JsonObject? = null
        private set
    var autoOpen: Boolean = true

    fun update(configuration: JsonObject) { this.configuration = configuration }

    override fun getState() = StoredState().also {
        it.configuration = configuration?.toString()
        it.autoOpen = autoOpen
    }

    override fun loadState(state: StoredState) {
        configuration = state.configuration?.let { Json.parseToJsonElement(it).jsonObject }
        autoOpen = state.autoOpen
    }
}

package plugin.lean.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** Frontend editor preferences do not change the host's toolchain or restart language services. */
@Service(Service.Level.PROJECT)
@State(name = "LeanEditor", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class LeanEditorSettings : PersistentStateComponent<LeanEditorSettings.StoredState> {
    class StoredState {
        var foldProofsOnOpen: Boolean = false
    }

    var foldProofsOnOpen: Boolean = false

    override fun getState() = StoredState().also { it.foldProofsOnOpen = foldProofsOnOpen }
    override fun loadState(state: StoredState) { foldProofsOnOpen = state.foldProofsOnOpen }
}

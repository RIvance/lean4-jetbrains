package plugin.lean.toolchain

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.FixedComboBoxEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.TextComponentEmptyText
import plugin.lean.rpc.ActiveToolchainInfo
import plugin.lean.rpc.ToolchainInspection
import javax.swing.DefaultComboBoxModel

/** An empty selection follows elan. Its resolved name is a hint, never an explicit override. */
class LeanToolchainComboBox : ComboBox<String>(arrayOf("")) {
    private val nameEditor = FixedComboBoxEditor()

    init {
        nameEditor.field.emptyText.text = "Automatic"
        TextComponentEmptyText.setupPlaceholderVisibility(nameEditor.field)
        setEditor(nameEditor)
        setEditable(true)
        setRenderer(SimpleListCellRenderer.create("Automatic") { it.ifEmpty { nameEditor.field.emptyText.text } })
    }

    var toolchainName: String
        get() = nameEditor.field.text.trim()
        set(value) {
            selectedItem = value
            nameEditor.item = value
        }

    fun showInspection(inspection: ToolchainInspection, selection: String) {
        val available = inspection as? ToolchainInspection.Available
        val active = available?.active as? ActiveToolchainInfo.Available
        nameEditor.field.emptyText.text = if (selection.isEmpty() && active != null) {
            "Automatic (${active.toolchain.name})"
        } else {
            "Automatic"
        }
        val choices = listOf("") + available?.installed.orEmpty().map { it.name }
        model = DefaultComboBoxModel(choices.toTypedArray()).apply { selectedItem = selection }
    }
}

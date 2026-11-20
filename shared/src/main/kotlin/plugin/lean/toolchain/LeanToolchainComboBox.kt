package plugin.lean.toolchain

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.FixedComboBoxEditor
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.TextComponentEmptyText
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import plugin.lean.rpc.ActiveToolchainInfo
import plugin.lean.rpc.ToolchainInspection
import javax.swing.DefaultComboBoxModel
import javax.swing.event.DocumentEvent

/** An empty selection follows elan. Its resolved name is a hint, never an explicit override. */
class LeanToolchainComboBox : ComboBox<String>(arrayOf("")) {
    private val nameEditor = object : FixedComboBoxEditor() {
        override fun setItem(value: Any?) {
            // Swing configures the editor again when replacing the inventory, even for the same draft.
            if (getField().text != value?.toString().orEmpty()) super.setItem(value)
        }
    }

    init {
        // The form supplies the available width. A long path must not push the dropdown button out of view.
        setMinimumAndPreferredWidth(JBUI.scale(160))
        nameEditor.field.emptyText.text = "Automatic"
        TextComponentEmptyText.setupPlaceholderVisibility(nameEditor.field)
        setEditor(nameEditor)
        setEditable(true)
        setRenderer(textListCellRenderer<String?> { it?.ifEmpty { nameEditor.field.emptyText.text } ?: "Automatic" })
    }

    var toolchainName: String
        get() = nameEditor.field.text.trim()
        set(value) {
            selectedItem = value
            nameEditor.item = value
        }

    fun onSelectionChanged(action: () -> Unit) {
        nameEditor.field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = action()
        })
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
        val draft = nameEditor.field.text.takeIf { it.trim() == selection } ?: selection
        model = DefaultComboBoxModel(choices.toTypedArray()).apply { selectedItem = draft }
    }
}

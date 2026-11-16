package plugin.lean.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.CellEditorComponentWithBrowseButton
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.ListTableModel
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.AbstractCellEditor
import javax.swing.table.TableCellEditor
import plugin.lean.language.UnicodeSymbolDialog

class UnicodeInputConfigurable : SearchableConfigurable {
    private var form: UnicodeInputForm? = null

    override fun getId() = "plugin.lean.unicode"
    override fun getDisplayName() = "Lean Unicode Input"

    override fun createComponent(): JComponent = UnicodeInputForm().also { form = it }.component

    override fun isModified(): Boolean {
        val form = form ?: return false
        val settings = service<UnicodeInputSettings>().configuration
        val optionsChanged =
            form.enabled.isSelected != settings.enabled ||
            form.eager.isSelected != settings.eagerReplacement ||
            form.leader.text != settings.leader
        val translations = form.translations.items.map { it.abbreviation to it.replacement }
        return optionsChanged || translations != settings.customTranslations.toList()
    }

    override fun apply() {
        val form = form ?: return
        TableUtil.stopEditing(form.table)
        val custom = linkedMapOf<String, String>()
        for ((abbreviation, replacement) in form.translations.items) {
            if (custom.put(abbreviation, replacement) != null) {
                throw ConfigurationException("Duplicate abbreviation: $abbreviation")
            }
        }
        val configuration = try {
            UnicodeInputConfiguration(form.enabled.isSelected, form.eager.isSelected, form.leader.text, custom)
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message ?: "Invalid abbreviation settings")
        }
        service<UnicodeInputSettings>().update(configuration)
    }

    override fun reset() {
        val form = form ?: return
        val settings = service<UnicodeInputSettings>().configuration
        form.enabled.isSelected = settings.enabled
        form.eager.isSelected = settings.eagerReplacement
        form.leader.text = settings.leader
        form.translations.items = settings.customTranslations.map { Translation(it.key, it.value) }.toMutableList()
    }

    override fun disposeUIResources() { form = null }
}

private data class Translation(var abbreviation: String = "", var replacement: String = "")

private class UnicodeInputForm {
    val enabled = JBCheckBox("Enable Unicode abbreviation input")
    val eager = JBCheckBox("Convert complete abbreviations as you type")
    val leader = JBTextField(3)
    val translations = ListTableModel<Translation>(
        object : ColumnInfo<Translation, String>("Abbreviation") {
            override fun valueOf(item: Translation) = item.abbreviation
            override fun isCellEditable(item: Translation) = true
            override fun setValue(item: Translation, value: String) { item.abbreviation = value }
        },
        object : ColumnInfo<Translation, String>("Replacement") {
            override fun valueOf(item: Translation) = item.replacement
            override fun isCellEditable(item: Translation) = true
            override fun setValue(item: Translation, value: String) { item.replacement = value }
            override fun getEditor(item: Translation): TableCellEditor = ReplacementEditor()
        }
    )
    val table = TableView(translations).apply {
        preferredScrollableViewportSize = JBDimension(500, 200)
        emptyText.text = "The built-in Lean abbreviations are available without custom entries"
        accessibleContext.accessibleName = "Custom Unicode abbreviations"
    }
    private val translationPanel = ToolbarDecorator.createDecorator(table).setAddAction {
        TableUtil.stopEditing(table)
        translations.addRow(Translation())
        val row = translations.rowCount - 1
        table.setRowSelectionInterval(row, row)
        table.editCellAt(row, 0)
        table.editorComponent?.requestFocusInWindow()
    }.createPanel()

    val component = panel {
        row { cell(enabled) }
        row { cell(eager).comment("Tab, separators, and moving the caret away also convert an abbreviation.") }
        row("Leader:") { cell(leader).comment("A single character, normally a backslash.") }
        row { button("Browse Unicode symbols…") { UnicodeSymbolDialog(null).show() } }
        group("Custom abbreviations") {
            row { cell(translationPanel).align(Align.FILL) }.resizableRow()
            row {
                comment("Enter abbreviations without the leader. Custom entries override built-in translations.")
            }
            row { comment("Use \$CURSOR in a replacement to place the caret, for example ⟨\$CURSOR⟩.") }
            row { comment("Expand a replacement field to edit multiple lines.") }
        }.resizableRow()
    }
}

private class ReplacementEditor : AbstractCellEditor(), TableCellEditor {
    private val field = object : TextFieldWithBrowseButton() {
        override fun getDefaultIcon() = AllIcons.General.ExpandComponent
        override fun getHoveredIcon() = AllIcons.General.ExpandComponentHover
        override fun getIconTooltip() = "Edit Unicode replacement"
    }.apply {
        textField.accessibleContext.accessibleName = "Unicode replacement"
        textField.document.putProperty("filterNewlines", false)
        addActionListener {
            val dialog = ReplacementDialog(text)
            if (dialog.showAndGet()) text = dialog.replacement
        }
    }
    private val component = CellEditorComponentWithBrowseButton(field, this)

    override fun getCellEditorValue(): String = field.text

    override fun getTableCellEditorComponent(
        table: JTable, value: Any?, selected: Boolean, row: Int, column: Int
    ): JComponent {
        field.text = value as String
        return component
    }
}

private class ReplacementDialog(initial: String) : DialogWrapper(false) {
    private val text = JBTextArea(initial, 8, 45)
    val replacement: String get() = text.text

    init {
        title = "Edit Unicode Replacement"
        setOKButtonText("Apply")
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = text
    override fun createCenterPanel(): JComponent = JBScrollPane(text)
}

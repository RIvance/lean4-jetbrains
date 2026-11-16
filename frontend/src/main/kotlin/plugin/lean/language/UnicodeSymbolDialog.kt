package plugin.lean.language

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.ListTableModel
import plugin.lean.settings.UnicodeInputSettings
import plugin.lean.tooling.UnicodeAbbreviation
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

class LeanUnicodeSymbolsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)?.takeUnless { it.isViewer }
        UnicodeSymbolDialog(event.project, editor).show()
    }
}

/** Browsing and copying work without a project, server, or enabled automatic input. */
class UnicodeSymbolDialog(project: Project?, private val editor: Editor? = null) : DialogWrapper(project) {
    private val configuration = service<UnicodeInputSettings>().configuration
    private val search = JBTextField().apply {
        emptyText.text = "Search by abbreviation or symbol"
        accessibleContext.accessibleName = "Search Unicode symbols"
        text = editor?.selectionModel?.selectedText.orEmpty()
    }
    private val model = ListTableModel<UnicodeAbbreviation>(
        object : ColumnInfo<UnicodeAbbreviation, String>("Symbol") {
            override fun valueOf(item: UnicodeAbbreviation) = item.replacement().text()
        },
        object : ColumnInfo<UnicodeAbbreviation, String>("Abbreviation") {
            override fun valueOf(item: UnicodeAbbreviation) = configuration.leader + item.abbreviation()
        }
    )
    private val table = TableView(model).apply {
        preferredScrollableViewportSize = JBDimension(530, 330)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = "Unicode symbols"
        emptyText.text = "No matching abbreviations"
    }
    private val copy = object : DialogWrapperAction("Copy") {
        override fun doAction(event: ActionEvent) { copySelection() }
    }

    init {
        title = "Lean Unicode Symbols"
        setOKButtonText(if (editor == null) "Copy" else "Insert")
        setCancelButtonText("Close")
        init()
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { filter() }
        })
        table.selectionModel.addListSelectionListener { updateActions() }
        for ((key, action) in listOf(KeyEvent.VK_UP to "selectPreviousRow", KeyEvent.VK_DOWN to "selectNextRow")) {
            search.registerKeyboardAction({
                table.actionMap.get(action)?.actionPerformed(ActionEvent(table, ActionEvent.ACTION_PERFORMED, action))
            }, KeyStroke.getKeyStroke(key, 0), JComponent.WHEN_FOCUSED)
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                doOKAction()
                return true
            }
        }.installOn(table)
        filter()
    }

    private fun filter() {
        val query = search.text.removePrefix(configuration.leader)
        model.items = configuration.catalog.search(query)
        if (model.rowCount > 0) table.setRowSelectionInterval(0, 0)
        updateActions()
    }

    private fun updateActions() {
        isOKActionEnabled = table.selectedObject != null && editor?.isDisposed != true
        copy.isEnabled = table.selectedObject != null
    }

    private fun copySelection() {
        val entry = table.selectedObject ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(entry.replacement().text()))
        close(OK_EXIT_CODE)
    }

    override fun doOKAction() {
        if (!isOKActionEnabled) return
        if (editor == null) {
            copySelection()
        } else {
            val entry = table.selectedObject ?: return
            if (LeanUnicodeInsertion.insert(editor, entry.replacement())) close(OK_EXIT_CODE)
            else setErrorText("The selected editor cannot be modified. You can copy the symbol instead.")
        }
    }

    override fun createActions(): Array<Action> =
        if (editor == null) arrayOf(okAction, cancelAction) else arrayOf(okAction, copy, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = search
    override fun getDimensionServiceKey() = "plugin.lean.unicode.symbols"

    override fun createCenterPanel(): JComponent = panel {
        row { cell(search).align(Align.FILL) }
        row { cell(JBScrollPane(table)).align(Align.FILL) }.resizableRow()
        row { comment("Search by an abbreviation, with or without its leader, or by a Unicode symbol.") }
    }
}

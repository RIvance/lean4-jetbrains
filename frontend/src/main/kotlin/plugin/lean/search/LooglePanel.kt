package plugin.lean.search

import com.intellij.ide.BrowserUtil
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import plugin.lean.LeanToolWindowFactory
import plugin.lean.language.LeanLanguage
import plugin.lean.language.LeanUnicodeInsertion
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.io.IOException
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer

/** Search history and requests belong to this panel; a later search or disposal cancels the previous request. */
class LooglePanel(
    private val project: Project,
    parentScope: CoroutineScope,
    private val source: LoogleSearch = LoogleSearch { service<LoogleClient>().search(it) }
) : JBPanel<LooglePanel>(), Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var request: Job? = null
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var draft = ""
    private val query = LanguageTextField(LeanLanguage, project, "").apply {
        setPlaceholder("Name, quoted substring, or type pattern")
        accessibleContext.accessibleName = "Loogle query"
    }
    private val find = JButton("Search").apply { addActionListener { submit() } }
    private val cancel = JButton("Cancel").apply { addActionListener { cancelSearch() }; isEnabled = false }
    private val previous = JButton("Previous").apply { addActionListener { moveHistory(-1) }; isEnabled = false }
    private val next = JButton("Next").apply { addActionListener { moveHistory(1) }; isEnabled = false }
    private val open = JButton("Open Documentation").apply { addActionListener { openSelected() }; isEnabled = false }
    private val copy = JButton("Copy Name").apply { addActionListener { copySelected() }; isEnabled = false }
    private val status = JBTextArea("Search Lean and Mathlib using the online Loogle service.").apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = JBLabel().font
        accessibleContext.accessibleName = "Loogle search status"
    }
    private val model = ListTableModel<LoogleHit>(
        object : ColumnInfo<LoogleHit, String>("Declaration") {
            override fun valueOf(item: LoogleHit) = item.name
        },
        object : ColumnInfo<LoogleHit, String>("Module") {
            override fun valueOf(item: LoogleHit) = item.module
        }
    )
    private val results = TableView(model).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "Enter a query and press Search."
        accessibleContext.accessibleName = "Loogle results"
        setDefaultRenderer(String::class.java, DefaultTableCellRenderer().apply {
            putClientProperty("html.disable", true)
        })
    }
    private val details = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, JBLabel().font.size)
        border = JBUI.Borders.empty(6)
        accessibleContext.accessibleName = "Selected declaration type and documentation"
    }
    private val suggestions = JBList<String>().apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No suggested queries. Edit the query and try again."
        accessibleContext.accessibleName = "Suggested Loogle queries"
        cellRenderer = javax.swing.DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
    }
    private val cards = CardLayout()
    private val content = JPanel(cards)

    init {
        layout = BorderLayout(0, JBUI.scale(6))
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(query, BorderLayout.CENTER)
                add(find, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
                add(previous); add(next); add(cancel)
                add(ActionLink("Query syntax") { BrowserUtil.browse("https://loogle.lean-lang.org/") })
            }, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        content.add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(results), JBScrollPane(details)).apply {
            resizeWeight = 0.6
            border = null
        }, "results")
        content.add(JPanel(BorderLayout()).apply {
            add(JBLabel("Suggested queries — press Enter or double-click to search:"), BorderLayout.NORTH)
            add(JBScrollPane(suggestions), BorderLayout.CENTER)
        }, "suggestions")
        add(content, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
                add(open); add(copy)
            }, BorderLayout.NORTH)
            add(JBLabel("Submitted queries are sent to loogle.lean-lang.org."), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        results.selectionModel.addListSelectionListener { showSelection() }
        shortcut(query, "ENTER") { submit() }
        shortcut(query, "UP") { moveHistory(-1) }
        shortcut(query, "DOWN") { moveHistory(1) }
        shortcut(results, "ENTER") { openSelected() }
        shortcut(suggestions, "ENTER") { useSuggestion() }
        object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) = copySelected()
        }.registerCustomShortcutSet(CommonShortcuts.getCopy(), results, this)
        doubleClick(results, ::openSelected)
        doubleClick(suggestions, ::useSuggestion)
    }

    val focusComponent: JComponent get() = query

    /** Opening the tab alone does not submit a query. An explicit selection or Search action does. */
    fun submit(text: String? = null) {
        if (text != null) query.text = text
        query.editor?.let { editor ->
            if (LookupManager.getActiveLookup(editor) != null) LookupManager.hideActiveLookup(project)
            LeanUnicodeInsertion.finishAbbreviations(editor)
        }
        val submitted = query.text.trim()
        if (submitted.isEmpty()) {
            status.text = "Enter a name, quoted substring, or type pattern."
            return
        }
        request?.cancel()
        if (history.lastOrNull() != submitted) history.add(submitted)
        if (history.size > 50) history.removeAt(0)
        historyIndex = history.size
        draft = submitted
        updateHistory()
        model.items = emptyList()
        suggestions.setListData(emptyArray())
        cards.show(content, "results")
        results.emptyText.text = "Searching…"
        status.text = "Searching Loogle…"
        cancel.isEnabled = true
        request = scope.launch(Dispatchers.EDT) {
            try {
                val response = source.search(submitted)
                ensureActive()
                when (response) {
                    is LoogleResponse.Matches -> {
                        model.items = response.hits
                        results.emptyText.text = "No declarations matched this query."
                        status.text = response.header.trim()
                        if (response.hits.size < response.count) {
                            status.append("\nShowing ${response.hits.size} of ${response.count} matches.")
                            status.append(" Refine the query for more precise results.")
                        }
                        if (model.rowCount > 0) results.setRowSelectionInterval(0, 0)
                    }
                    is LoogleResponse.Problem -> {
                        status.text = response.message
                        suggestions.setListData(response.suggestions.toTypedArray())
                        cards.show(content, "suggestions")
                        if (!suggestions.isEmpty) suggestions.selectedIndex = 0
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: IOException) {
                ensureActive()
                status.text = "Loogle search failed: ${error.message ?: "Cannot connect to the search service."}"
                results.emptyText.text = "Search failed. Edit the query or try again."
            } catch (error: Exception) {
                ensureActive()
                logger<LooglePanel>().warn("Loogle search failed", error)
                status.text = "Loogle search failed. See the IDE log for details."
            } finally {
                if (coroutineContext[Job]?.isActive == true) cancel.isEnabled = false
            }
        }
    }

    fun cancelSearch() {
        request?.cancel()
        request = null
        cancel.isEnabled = false
        status.text = "Search cancelled."
        results.emptyText.text = "Search cancelled."
    }

    private fun moveHistory(direction: Int) {
        val index = historyIndex + direction
        if (index !in 0..history.size) return
        if (historyIndex == history.size) draft = query.text
        historyIndex = index
        query.text = history.getOrElse(index) { draft }
        updateHistory()
    }

    private fun updateHistory() {
        previous.isEnabled = historyIndex > 0
        next.isEnabled = historyIndex < history.size
    }

    private fun showSelection() {
        val hit = results.selectedObject
        details.text = hit?.let { "${it.name}\n${it.type.trimStart()}\n\n${it.documentation.orEmpty()}" }.orEmpty()
        details.caretPosition = 0
        open.isEnabled = hit != null
        copy.isEnabled = hit != null
    }

    private fun openSelected() {
        results.selectedObject?.let { BrowserUtil.browse(it.documentationUri) }
    }

    private fun copySelected() {
        results.selectedObject?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.name)) }
    }

    private fun useSuggestion() {
        suggestions.selectedValue?.let { submit(it) }
    }

    private fun shortcut(component: JComponent, key: String, block: () -> Unit) {
        object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) = block()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(key)), component, this)
    }

    private fun doubleClick(component: JComponent, block: () -> Unit) {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                block()
                return true
            }
        }.installOn(component)
    }

    override fun dispose() = scope.cancel()
}

internal class SearchLoogleAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project != null }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val selection = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }
        LeanToolWindowFactory.showLoogle(project, selection)
    }
}

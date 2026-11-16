package plugin.lean.navigation

import com.intellij.icons.AllIcons
import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.update.UiNotifyConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import plugin.lean.language.LeanIcons
import plugin.lean.rpc.LeanImportKind
import plugin.lean.rpc.LeanImportMetaKind
import plugin.lean.rpc.LeanModule
import plugin.lean.rpc.LeanModuleHierarchy
import plugin.lean.rpc.LeanModuleHierarchyApi
import plugin.lean.rpc.LeanModuleHierarchyDirection
import plugin.lean.rpc.LeanRpcResult
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Each displayed tree owns its requests. Replacing the root or direction cancels that entire lifetime. */
internal class LeanModuleHierarchyPanel(
    private val project: Project,
    parentScope: CoroutineScope
) : JBPanel<LeanModuleHierarchyPanel>(), Disposable {
    private class RequestFailure(val details: LeanRpcResult.Failure) : Exception(details.message)

    private enum class LoadState { UNLOADED, LOADING, LOADED, FAILED }

    private class ModuleNode(
        val module: LeanModule, val kind: LeanImportKind? = null, val recursive: Boolean = false
    ) : DefaultMutableTreeNode(module.name) {
        var state = if (recursive) LoadState.LOADED else LoadState.UNLOADED
        init {
            if (!recursive) add(DefaultMutableTreeNode("Expand to load"))
        }
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var requests = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var hierarchy: LeanModuleHierarchy? = null
    private var rootFile: VirtualFile? = null
    private val model = DefaultTreeModel(null)
    private val tree = Tree(model).apply {
        isRootVisible = true
        showsRootHandles = true
        emptyText.text = "Choose a Lean file to browse its module hierarchy."
    }
    private val direction = ComboBox(LeanModuleHierarchyDirection.entries.toTypedArray())
    private val status = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = JBLabel().font
        border = JBUI.Borders.empty(8)
    }

    init {
        layout = BorderLayout()
        direction.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean
            ) = super.getListCellRendererComponent(list, directionName(value), index, selected, focus)
        }
        val actions = DefaultActionGroup(
            action("Use Current File", AllIcons.General.Locate) { showCurrentFile() },
            action("Refresh", AllIcons.Actions.Refresh) { load(rootFile) },
            action("Collapse All", AllIcons.Actions.Collapseall) {
                for (row in tree.rowCount - 1 downTo 0) tree.collapseRow(row)
            },
            action("Open Source", AllIcons.Actions.EditSource, { selected() != null }) { navigateSelected() },
            action("Copy Module Name", AllIcons.Actions.Copy, { selected() != null }) {
                selected()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.module.name)) }
            }
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("LeanModuleHierarchy", actions, true)
        toolbar.targetComponent = tree
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                border = JBUI.Borders.empty(4)
                add(JBLabel("Show:").apply { labelFor = direction }, BorderLayout.WEST)
                add(direction, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean
            ) {
                toolTipText = null
                if (value is ModuleNode) {
                    icon = LeanIcons.LEAN
                    append(value.module.name)
                    value.kind?.let { append("  ${modifiers(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    if (value.recursive) append("  (cycle)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = value.module.uri
                } else {
                    append(value.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        TreeSpeedSearch.installOn(tree, false) { path ->
            (path.lastPathComponent as? ModuleNode)?.module?.name.orEmpty()
        }
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? ModuleNode)?.let(::loadChildren)
            }
            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val path = tree.getPathForLocation(event.x, event.y)
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1 && path != null) navigateSelected()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) {
                    navigateSelected()
                    event.consume()
                }
            }
        })
        direction.addActionListener { load(rootFile) }
        UiNotifyConnector.doWhenFirstShown(this) {
            if (rootFile == null) showCurrentFile()
        }
    }

    fun showCurrentFile() {
        val document = FileEditorManager.getInstance(project).selectedTextEditor?.document
        showFile(document?.let { FileDocumentManager.getInstance().getFile(it) })
    }

    fun showFile(file: VirtualFile?) {
        load(file)
        tree.requestFocusInWindow()
    }

    private fun selected() = tree.lastSelectedPathComponent as? ModuleNode

    private fun load(file: VirtualFile?) {
        requests.cancel()
        requests = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        hierarchy = null
        rootFile = file
        model.setRoot(null)
        status.text = ""
        if (file == null || !file.isValid || file.isDirectory || file.extension != "lean") {
            tree.emptyText.text = "Choose an open Lean source file to browse its module hierarchy."
            return
        }
        val fileId = file.rpcId()
        tree.emptyText.text = "Loading module hierarchy…"
        requests.launch(Dispatchers.EDT) {
            try {
                val result = LeanModuleHierarchyApi.getInstance().prepare(project.projectId(), fileId).valueOrThrow()
                ensureActive()
                hierarchy = result
                if (result == null) {
                    tree.emptyText.text = "Lean has no module information for this file."
                    return@launch
                }
                val root = ModuleNode(result.root)
                model.setRoot(root)
                tree.selectionPath = TreePath(root.path)
                status.text = result.root.name
                loadChildren(root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                tree.emptyText.text = "Module hierarchy is unavailable."
                status.text = failureMessage(error, "Could not load module hierarchy.")
            }
        }
    }

    private fun loadChildren(node: ModuleNode) {
        if (node.state == LoadState.LOADING || node.state == LoadState.LOADED) return
        val owner = hierarchy ?: return
        val selectedDirection = direction.selectedItem as LeanModuleHierarchyDirection
        node.state = LoadState.LOADING
        replaceChildren(node, listOf(DefaultMutableTreeNode("Loading…")))
        requests.launch(Dispatchers.EDT) {
            try {
                val children = LeanModuleHierarchyApi.getInstance().children(
                    project.projectId(), owner.serverId, node.module, selectedDirection
                ).valueOrThrow()
                ensureActive()
                val ancestors = node.path.filterIsInstance<ModuleNode>().map { it.module.name }.toSet()
                val nodes = children.map { ModuleNode(it.module, it.kind, it.module.name in ancestors) }
                node.state = LoadState.LOADED
                replaceChildren(node, nodes)
                if (node === model.root) {
                    status.text = "${owner.root.name} · ${directionName(selectedDirection)}: ${children.size}"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                node.state = LoadState.FAILED
                replaceChildren(node, listOf(DefaultMutableTreeNode("Could not load related modules.")))
                val message = failureMessage(error, "Could not load related modules.")
                status.text = "$message\nRefresh the hierarchy to retry."
            }
        }
    }

    private fun replaceChildren(node: ModuleNode, children: List<DefaultMutableTreeNode>) {
        val path = TreePath(node.path)
        val expanded = tree.isExpanded(path)
        val selection = tree.selectionPaths.orEmpty()
        node.removeAllChildren()
        children.forEach(node::add)
        model.nodeStructureChanged(node)
        if (expanded) tree.expandPath(path)
        tree.selectionPaths = selection.filter {
            (it.lastPathComponent as? DefaultMutableTreeNode)?.root === model.root
        }.toTypedArray()
    }

    private fun navigateSelected() {
        val module = selected()?.module ?: return
        val owner = hierarchy ?: return
        requests.launch(Dispatchers.EDT) {
            try {
                val api = LeanModuleHierarchyApi.getInstance()
                api.navigate(project.projectId(), owner.serverId, module).valueOrThrow()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                status.text = failureMessage(error, "Could not open module source.")
            }
        }
    }

    private fun action(
        text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, perform: () -> Unit
    ): DumbAwareAction {
        return object : DumbAwareAction(text, text, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = enabled()
            }
            override fun actionPerformed(event: AnActionEvent) = perform()
        }
    }

    private fun directionName(value: Any?) = when (value) {
        LeanModuleHierarchyDirection.IMPORTED_BY -> "Imported by"
        else -> "Imports"
    }

    private fun modifiers(kind: LeanImportKind): String = buildList {
        add(if (kind.isPrivate) "private" else "public")
        if (kind.isAll) add("all")
        when (kind.metaKind) {
            LeanImportMetaKind.META -> add("meta")
            LeanImportMetaKind.FULL -> add("meta + non-meta")
            LeanImportMetaKind.NON_META -> Unit
        }
    }.joinToString(", ")

    private fun <T> LeanRpcResult<T>.valueOrThrow(): T = when (this) {
        is LeanRpcResult.Success -> value
        is LeanRpcResult.Failure -> throw RequestFailure(this)
    }

    private fun failureMessage(error: Exception, fallback: String): String {
        if (error is RequestFailure) return error.details.message
        logger<LeanModuleHierarchyPanel>().warn(fallback, error)
        return "$fallback See the IDE log for details."
    }

    override fun dispose() = scope.cancel()
}


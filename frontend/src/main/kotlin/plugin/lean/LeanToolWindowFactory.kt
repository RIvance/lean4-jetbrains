package plugin.lean

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import plugin.lean.infoview.InfoviewActionKind
import plugin.lean.infoview.LeanInfoviewPanel
import plugin.lean.navigation.LeanModuleHierarchyPanel
import plugin.lean.search.LooglePanel
import plugin.lean.toolchain.LeanToolchainPanel

@Service(Service.Level.PROJECT)
internal class LeanFrontendScope(val scope: CoroutineScope)

internal class LeanToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val infoview = LeanInfoviewPanel(project, project.service<LeanFrontendScope>().scope)
        val infoviewContent = ContentFactory.getInstance().createContent(infoview, "InfoView", false)
        infoviewContent.preferredFocusableComponent = infoview.focusComponent
        infoviewContent.setDisposer(infoview)
        toolWindow.contentManager.addContent(infoviewContent)
        val panel = LeanToolchainPanel(project, project.service<LeanFrontendScope>().scope)
        val content = ContentFactory.getInstance().createContent(panel, "Toolchains", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        val hierarchy = LeanModuleHierarchyPanel(project, project.service<LeanFrontendScope>().scope)
        val hierarchyContent = ContentFactory.getInstance().createContent(hierarchy, "Modules", false)
        hierarchyContent.setDisposer(hierarchy)
        toolWindow.contentManager.addContent(hierarchyContent)
        val search = LooglePanel(project, project.service<LeanFrontendScope>().scope)
        val searchContent = ContentFactory.getInstance().createContent(search, "Loogle", false)
        searchContent.preferredFocusableComponent = search.focusComponent
        searchContent.setDisposer(search)
        toolWindow.contentManager.addContent(searchContent)
    }

    companion object {
        /** Looking up command availability must not initialize a hidden tool window or create a browser. */
        fun infoview(project: Project): LeanInfoviewPanel? =
            window(project)?.let(::infoviewContent)?.component as? LeanInfoviewPanel

        fun showInfoview(project: Project) {
            revealInfoview(project, requestFocus = true)
        }

        fun showLoogle(project: Project, query: String?) {
            val window = window(project) ?: return
            window.activate {
                val content = window.contentManager.contents.firstOrNull { it.component is LooglePanel }
                    ?: return@activate
                window.contentManager.setSelectedContent(content, true)
                if (query != null) (content.component as LooglePanel).submit(query)
            }
        }

        fun revealInfoview(project: Project, requestFocus: Boolean): Boolean {
            val window = window(project) ?: return false
            val select = Runnable {
                val content = infoviewContent(window) ?: return@Runnable
                window.contentManager.setSelectedContent(content, requestFocus)
            }
            if (requestFocus) window.activate(select) else window.show(select)
            return true
        }

        fun requestInfoviewAction(project: Project, kind: InfoviewActionKind) {
            val window = window(project) ?: return
            val content = infoviewContent(window) ?: return
            val panel = content.component as LeanInfoviewPanel
            if (!panel.canRequestAction) return
            window.show()
            window.contentManager.setSelectedContent(content, false)
            panel.requestAction(kind)
        }

        private fun window(project: Project): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow("Lean")

        private fun infoviewContent(window: ToolWindow): Content? =
            window.contentManagerIfCreated?.contents?.firstOrNull { it.component is LeanInfoviewPanel }
    }
}

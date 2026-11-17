package plugin.lean

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
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
import plugin.lean.ui.ToolWindowPanel
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
internal class LeanFrontendScope(val scope: CoroutineScope)

internal class LeanToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        addTab(project, toolWindow, Tab.INFOVIEW, { LeanInfoviewPanel(project, it) }, { it.focusComponent })
        addTab(project, toolWindow, Tab.TOOLCHAINS, { LeanToolchainPanel(project, it) })
        addTab(project, toolWindow, Tab.MODULES, { LeanModuleHierarchyPanel(project, it) })
        addTab(project, toolWindow, Tab.LOOGLE, { LooglePanel(project, it) }, { it.focusComponent })
    }

    private fun <T> addTab(
        project: Project,
        window: ToolWindow,
        tab: Tab,
        create: (CoroutineScope) -> T,
        preferredFocus: (T) -> JComponent = { it }
    ) where T : JComponent, T : Disposable {
        val panel = ToolWindowPanel(tab.title, { project.service<LeanFrontendScope>().scope }, create, preferredFocus)
        val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
        content.putUserData(TAB, tab)
        content.setPreferredFocusedComponent { panel.focusComponent }
        content.setDisposer(panel)
        window.contentManager.addContent(content)
    }

    private enum class Tab(val title: String) {
        INFOVIEW("InfoView"), TOOLCHAINS("Toolchains"), MODULES("Modules"), LOOGLE("Loogle")
    }

    companion object {
        private val TAB = Key.create<Tab>("plugin.lean.toolWindowTab")

        /** Looking up command availability must not initialize a hidden tool window or create a browser. */
        fun infoview(project: Project): LeanInfoviewPanel? =
            window(project)?.let(::infoviewContent)?.let(::panel) as? LeanInfoviewPanel

        fun showInfoview(project: Project) {
            revealInfoview(project, requestFocus = true)
        }

        fun showLoogle(project: Project, query: String?) {
            val window = window(project) ?: return
            window.activate {
                val content = window.contentManager.contents.firstOrNull { it.getUserData(TAB) == Tab.LOOGLE }
                    ?: return@activate
                window.contentManager.setSelectedContent(content, true)
                if (query != null) (panel(content) as? LooglePanel)?.submit(query)
            }
        }

        fun showModuleHierarchy(project: Project, file: VirtualFile) {
            val window = window(project) ?: return
            window.activate {
                val content = window.contentManager.contents.firstOrNull { it.getUserData(TAB) == Tab.MODULES }
                    ?: return@activate
                window.contentManager.setSelectedContent(content)
                (panel(content) as? LeanModuleHierarchyPanel)?.showFile(file)
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
            val panel = panel(content) as? LeanInfoviewPanel ?: return
            if (!panel.canRequestAction) return
            window.show()
            window.contentManager.setSelectedContent(content, false)
            panel.requestAction(kind)
        }

        private fun window(project: Project): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow("Lean")

        private fun infoviewContent(window: ToolWindow): Content? =
            window.contentManagerIfCreated?.contents?.firstOrNull { it.getUserData(TAB) == Tab.INFOVIEW }

        private fun panel(content: Content): JComponent? = (content.component as? ToolWindowPanel<*>)?.content
    }
}

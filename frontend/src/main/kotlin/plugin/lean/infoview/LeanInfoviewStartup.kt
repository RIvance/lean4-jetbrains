package plugin.lean.infoview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import plugin.lean.LeanToolWindowFactory
import plugin.lean.language.LeanFileType

/** Opens once per frontend project session; subsequent editor changes respect the user's window layout. */
@Service(Service.Level.PROJECT)
internal class LeanInfoviewStartup(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private var shown = false

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = checkSelection()
        })
        connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowsRegistered(ids: List<String>, toolWindowManager: ToolWindowManager) {
                if ("Lean" in ids) checkSelection()
            }

            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (toolWindow.id == "Lean") scope.launch(Dispatchers.EDT) { shown = true }
            }
        })
    }

    fun checkSelection() {
        scope.launch(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            if (shown || !project.service<InfoviewSettings>().autoOpen) return@launch
            val window = ToolWindowManager.getInstance(project).getToolWindow("Lean") ?: return@launch
            if (window.isVisible) {
                shown = true
                return@launch
            }
            val selected = FileEditorManager.getInstance(project).selectedFiles
            if (selected.none { it.fileType == LeanFileType }) return@launch
            shown = LeanToolWindowFactory.revealInfoview(project, requestFocus = false)
        }
    }

    override fun dispose() = Unit
}

internal class LeanInfoviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<LeanInfoviewStartup>().checkSelection()
    }
}

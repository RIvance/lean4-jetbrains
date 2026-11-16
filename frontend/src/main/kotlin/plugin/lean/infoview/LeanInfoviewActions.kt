package plugin.lean.infoview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import plugin.lean.LeanToolWindowFactory

/** Commands provided by the upstream InfoView renderer. */
internal enum class InfoviewActionKind(val wireName: String) {
    TOGGLE_PIN("togglePin"),
    TOGGLE_PAUSED("togglePaused"),
    TOGGLE_ALL_MESSAGES("toggleAllMessages"),
    TOGGLE_EXPECTED_TYPE("toggleExpectedType"),
    COPY_TO_COMMENT("copyToComment")
}

internal class ShowLeanInfoviewAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let(LeanToolWindowFactory::showInfoview)
    }
}

internal class ReloadLeanInfoviewAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let { LeanToolWindowFactory.infoview(it)?.canReload } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { LeanToolWindowFactory.infoview(it)?.reload() }
    }
}

/** Operates on the position displayed in InfoView, without changing editor focus. */
internal abstract class LeanInfoviewAction(private val kind: InfoviewActionKind) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let {
            LeanToolWindowFactory.infoview(it)?.canRequestAction
        } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { LeanToolWindowFactory.requestInfoviewAction(it, kind) }
    }

    class TogglePin : LeanInfoviewAction(InfoviewActionKind.TOGGLE_PIN)
    class TogglePaused : LeanInfoviewAction(InfoviewActionKind.TOGGLE_PAUSED)
    class ToggleAllMessages : LeanInfoviewAction(InfoviewActionKind.TOGGLE_ALL_MESSAGES)
    class ToggleExpectedType : LeanInfoviewAction(InfoviewActionKind.TOGGLE_EXPECTED_TYPE)
    class CopyToComment : LeanInfoviewAction(InfoviewActionKind.COPY_TO_COMMENT)
}

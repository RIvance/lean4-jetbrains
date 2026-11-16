package plugin.lean.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plugin.lean.LeanFrontendScope
import plugin.lean.rpc.LeanRpcResult
import plugin.lean.rpc.LeanToolchainApi

internal class LeanRestartServersAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        project.service<LeanFrontendScope>().scope.launch {
            val failure = try {
                withBackgroundProgress(project, "Restarting Lean servers", cancellable = true) {
                    when (val result = LeanToolchainApi.getInstance().restart(project.projectId())) {
                        is LeanRpcResult.Success -> null
                        is LeanRpcResult.Failure -> result.message
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                logger<LeanRestartServersAction>().warn("Cannot restart Lean servers", error)
                "The Lean servers could not be restarted. See the IDE log for details."
            }
            if (failure != null) {
                NotificationGroupManager.getInstance().getNotificationGroup("Lean")
                    .createNotification("Lean Server Restart", failure, NotificationType.ERROR)
                    .notify(project)
            }
        }
    }
}

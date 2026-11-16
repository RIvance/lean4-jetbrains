package plugin.lean.editor

import com.intellij.ide.vfs.rpcId
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plugin.lean.LeanFrontendScope
import plugin.lean.rpc.LeanFileApi
import plugin.lean.rpc.LeanRpcResult

internal class LeanRestartFileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible =
            event.project != null &&
            event.getData(CommonDataKeys.EDITOR) != null &&
            file?.isValid == true && file.extension == "lean"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val fileId = file.rpcId()
        project.service<LeanFrontendScope>().scope.launch {
            val failure = try {
                withBackgroundProgress(project, "Restarting ${file.name}", cancellable = true) {
                    when (val result = LeanFileApi.getInstance().restart(project.projectId(), fileId)) {
                        is LeanRpcResult.Success -> null
                        is LeanRpcResult.Failure -> result.message
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                logger<LeanRestartFileAction>().warn("Cannot restart the Lean file", error)
                "The Lean file could not be restarted. See the IDE log for details."
            }
            if (failure != null) {
                NotificationGroupManager.getInstance().getNotificationGroup("Lean")
                    .createNotification("Lean File Restart", failure, NotificationType.ERROR)
                    .notify(project)
            }
        }
    }
}

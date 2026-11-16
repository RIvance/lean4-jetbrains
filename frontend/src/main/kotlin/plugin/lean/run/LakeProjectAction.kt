package plugin.lean.run

import com.intellij.ide.vfs.rpcId
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.project.projectId
import com.intellij.platform.project.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plugin.lean.LeanFrontendScope
import plugin.lean.rpc.LakeProjectTask
import plugin.lean.rpc.LeanProjectApi
import plugin.lean.rpc.LeanRpcResult

internal sealed class LakeProjectAction(private val task: LakeProjectTask) : DumbAwareAction() {
    class Build : LakeProjectAction(LakeProjectTask.BUILD)
    class Clean : LakeProjectAction(LakeProjectTask.CLEAN)
    class Test : LakeProjectAction(LakeProjectTask.TEST)
    class Update : LakeProjectAction(LakeProjectTask.UPDATE)
    class FetchCache : LakeProjectAction(LakeProjectTask.FETCH_CACHE)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val fileId = event.getData(CommonDataKeys.VIRTUAL_FILE)?.rpcId()
        runLakeTask(event) { api, projectId -> api.runLake(projectId, fileId, task) }
    }
}

internal sealed class FetchLeanFileCacheAction(private val selection: Selection) : DumbAwareAction() {
    enum class Selection { SELECTED, OPEN }
    class SelectedFiles : FetchLeanFileCacheAction(Selection.SELECTED)
    class OpenFiles : FetchLeanFileCacheAction(Selection.OPEN)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val files = selectedFiles(event)
        event.presentation.isEnabled = event.project != null && when (selection) {
            Selection.OPEN -> true
            Selection.SELECTED -> files.isNotEmpty() && files.all(::isLeanFile)
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val files = when (selection) {
            Selection.SELECTED -> selectedFiles(event)
            Selection.OPEN -> FileEditorManager.getInstance(project).openFiles.filter(::isLeanFile)
        }.map { it.rpcId() }
        runLakeTask(event) { api, projectId -> api.fetchCacheForFiles(projectId, files) }
    }

    private fun selectedFiles(event: AnActionEvent): List<VirtualFile> =
        event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(event.getData(CommonDataKeys.VIRTUAL_FILE))

    private fun isLeanFile(file: VirtualFile): Boolean = file.isValid && !file.isDirectory && file.extension == "lean"
}

private fun runLakeTask(
    event: AnActionEvent, request: suspend (LeanProjectApi, ProjectId) -> LeanRpcResult<Unit>
) {
    val project = event.project ?: return
    val title = checkNotNull(event.presentation.text)
    project.service<LeanFrontendScope>().scope.launch {
        val failure = try {
            withBackgroundProgress(project, title, cancellable = true) {
                when (val result = request(LeanProjectApi.getInstance(), project.projectId())) {
                    is LeanRpcResult.Success -> null
                    is LeanRpcResult.Failure -> result.message
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: Exception) {
            logger<LakeProjectAction>().warn("Cannot start the Lake project task", error)
            "The Lake task could not be started. See the IDE log for details."
        }
        if (failure != null) {
            NotificationGroupManager.getInstance().getNotificationGroup("Lean")
                .createNotification(title, failure, NotificationType.ERROR)
                .notify(project)
        }
    }
}

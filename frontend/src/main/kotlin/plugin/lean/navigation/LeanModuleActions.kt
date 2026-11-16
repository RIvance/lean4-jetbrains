package plugin.lean.navigation

import com.intellij.ide.vfs.rpcId
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import plugin.lean.rpc.LeanModuleHierarchyApi
import plugin.lean.rpc.LeanRpcResult
import java.awt.datatransfer.StringSelection

/** These operations use Lean's module identity for one open file, captured before tool-window activation or RPC. */
internal sealed class LeanModuleFileAction : DumbAwareAction() {
    final override fun getActionUpdateThread() = ActionUpdateThread.BGT

    final override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = selectedFile(event) != null
    }

    final override fun actionPerformed(event: AnActionEvent) {
        val file = selectedFile(event) ?: return
        perform(checkNotNull(event.project), file)
    }

    protected abstract fun perform(project: Project, file: VirtualFile)

    private fun selectedFile(event: AnActionEvent): VirtualFile? {
        val project = event.project ?: return null
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val file = if (files == null) event.getData(CommonDataKeys.VIRTUAL_FILE) else files.singleOrNull()
        return file?.takeIf {
            it.isValid && !it.isDirectory && it.extension == "lean" &&
            FileEditorManager.getInstance(project).isFileOpen(it)
        }
    }
}

internal class LeanModuleHierarchyAction : LeanModuleFileAction() {
    override fun perform(project: Project, file: VirtualFile) {
        val window = ToolWindowManager.getInstance(project).getToolWindow("Lean") ?: return
        window.activate {
            val content = window.contentManager.contents.firstOrNull { it.component is LeanModuleHierarchyPanel }
                ?: return@activate
            window.contentManager.setSelectedContent(content)
            (content.component as LeanModuleHierarchyPanel).showFile(file)
        }
    }
}

internal class CopyLeanModuleNameAction : LeanModuleFileAction() {
    override fun perform(project: Project, file: VirtualFile) = project.service<LeanModuleCopyService>().copy(file)
}

/** A newer copy request cancels the previous one before it can replace the clipboard. */
@Service(Service.Level.PROJECT)
internal class LeanModuleCopyService(private val project: Project, private val scope: CoroutineScope) {
    private var copying: Job? = null

    fun copy(file: VirtualFile) {
        val fileId = file.rpcId()
        copying?.cancel()
        copying = scope.launch(Dispatchers.EDT) {
            val failure = try {
                val result = withBackgroundProgress(project, "Copying Lean module name", cancellable = true) {
                    LeanModuleHierarchyApi.getInstance().prepare(project.projectId(), fileId)
                }
                ensureActive()
                when (result) {
                    is LeanRpcResult.Success -> {
                        val module = result.value?.root
                        if (module == null) {
                            "Lean has no module information for this file."
                        } else {
                            CopyPasteManager.getInstance().setContents(StringSelection(module.name))
                            null
                        }
                    }
                    is LeanRpcResult.Failure -> result.message
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                logger<LeanModuleCopyService>().warn("Cannot copy Lean module name", error)
                "Could not copy the Lean module name. See the IDE log for details."
            }
            ensureActive()
            if (failure != null) {
                NotificationGroupManager.getInstance().getNotificationGroup("Lean")
                    .createNotification(
                        "Copy Lean Module Name", StringUtil.escapeXmlEntities(failure), NotificationType.ERROR
                    ).notify(project)
            }
        }
    }
}

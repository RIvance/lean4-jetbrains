package plugin.lean.editor

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.ide.DataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.io.IOException

internal class LeanInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val actions = Json.decodeFromString<LeanInlayActionGroup>((payload as StringInlayActionPayload).text).actions
        if (actions.size == 1) {
            performLeanInlayAction(editor, actions.single())
        } else if (actions.isNotEmpty()) {
            val group = DefaultActionGroup(actions.map { reference ->
                object : DumbAwareAction(reference.title) {
                    override fun actionPerformed(actionEvent: AnActionEvent) {
                        performLeanInlayAction(editor, reference)
                    }
                }
            })
            JBPopupFactory.getInstance().createActionGroupPopup(
                "Lean Hint", group, DataManager.getInstance().getDataContext(editor.contentComponent),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
            ).showInBestPositionFor(editor)
        }
    }
}

internal class LeanInsertInlayHintAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.getData(InlayHintsProvider.INLAY_PAYLOADS)?.containsKey(LeanInlayActionGroup.INSERT_PAYLOAD) == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val payload = event.getData(InlayHintsProvider.INLAY_PAYLOADS)?.get(LeanInlayActionGroup.INSERT_PAYLOAD)
            as? StringInlayActionPayload ?: return
        val reference = Json.decodeFromString<LeanInlayActionGroup>(payload.text).actions.single()
        performLeanInlayAction(editor, reference)
    }
}

private fun performLeanInlayAction(editor: Editor, reference: LeanInlayActionReference) {
    val project = editor.project ?: return
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
    project.service<LeanInlayActions>().perform(file, reference)
}

/** Native declarative-inlay callbacks already arrive on the backend in the originating editor's client context. */
@Service(Service.Level.PROJECT)
internal class LeanInlayActions(private val project: Project, private val scope: CoroutineScope) {
    fun perform(file: VirtualFile, reference: LeanInlayActionReference) {
        scope.launch(ClientId.current.asContextElement()) {
            val failure = try {
                withBackgroundProgress(project, reference.title, cancellable = true) {
                    project.service<LeanInlayService>().perform(file, reference.id)
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: IOException) {
                error.message ?: "The hint action could not be completed."
            } catch (error: ResponseErrorException) {
                error.responseError.message
            } catch (error: Exception) {
                logger<LeanInlayActions>().warn("Cannot perform the Lean hint action", error)
                "The hint action could not be completed. See the IDE log for details."
            }
            if (failure != null) {
                NotificationGroupManager.getInstance().getNotificationGroup("Lean")
                    .createNotification("Lean Hint", failure, NotificationType.ERROR).notify(project)
            }
        }
    }
}

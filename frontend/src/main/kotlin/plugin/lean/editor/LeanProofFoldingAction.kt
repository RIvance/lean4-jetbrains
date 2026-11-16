package plugin.lean.editor

import com.intellij.ide.vfs.rpcId
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import plugin.lean.LeanFrontendScope
import plugin.lean.rpc.LeanFileApi
import plugin.lean.rpc.LeanProofFold
import plugin.lean.rpc.LeanProofFolds
import plugin.lean.rpc.LeanRpcResult
import java.io.IOException

internal abstract class LeanProofFoldingAction(private val expanded: Boolean) : DumbAwareAction() {
    class Fold : LeanProofFoldingAction(expanded = false)
    class Unfold : LeanProofFoldingAction(expanded = true)

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
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val fileId = file.rpcId()
        val stamp = editor.document.modificationStamp
        cancelAutomaticProofFolding(editor)
        editor.getUserData(COMMAND)?.cancel()
        val command = project.service<LeanFrontendScope>().scope.launch {
            val failure = try {
                val title = if (expanded) "Unfolding Lean proofs" else "Folding Lean proofs"
                withBackgroundProgress(project, title, cancellable = true) {
                    when (val result = LeanFileApi.getInstance().proofFolds(project.projectId(), fileId)) {
                        is LeanRpcResult.Failure -> result.message
                        is LeanRpcResult.Success -> {
                            changeProofFolds(editor, result.value, stamp) { regions ->
                                regions.forEach { it.isExpanded = expanded }
                            }
                            null
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: IOException) {
                error.message
            } catch (error: Exception) {
                logger<LeanProofFoldingAction>().warn("Cannot change Lean proof folding", error)
                "Proof folding could not be updated. See the IDE log for details."
            }
            currentCoroutineContext().ensureActive()
            if (failure != null) {
                NotificationGroupManager.getInstance().getNotificationGroup("Lean")
                    .createNotification("Lean Proof Folding", failure, NotificationType.ERROR)
                    .notify(project)
            }
        }
        editor.putUserData(COMMAND, command)
    }

    private companion object {
        val COMMAND = Key.create<Job>("Lean proof folding command")
    }
}

/** Invoke [change] in one EDT folding batch, only after every fold matches the unchanged server snapshot. */
internal suspend fun changeProofFolds(
    editor: Editor,
    folds: LeanProofFolds,
    stamp: Long,
    change: (List<FoldRegion>) -> Unit
) {
    val text = readAction {
        requireCurrentEditor(editor, stamp)
        editor.document.immutableCharSequence
    }
    if (!withContext(Dispatchers.Default) { folds.matches(text) }) {
        throw IOException("The editor and Lean server are still synchronizing. Try the folding command again.")
    }
    withContext(Dispatchers.EDT) {
        val model = editor.foldingModel as? FoldingModelEx
            ?: throw IOException("This editor does not support code folding.")
        withTimeoutOrNull(10_000) {
            updateProofFolds(editor, model, folds.ranges, stamp, change)
        } ?: throw IOException("Proof folds are not available yet. Try again after Lean finishes checking the file.")
    }
}

private suspend fun updateProofFolds(
    editor: Editor,
    model: FoldingModelEx,
    ranges: List<LeanProofFold>,
    stamp: Long,
    change: (List<FoldRegion>) -> Unit
) {
    val lifetime = Disposer.newDisposable("Lean proof folding command")
    val changes = Channel<Unit>(Channel.CONFLATED)
    try {
        model.addListener(object : FoldingListener {
            override fun onFoldProcessingEnd() {
                changes.trySend(Unit)
            }
        }, lifetime)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                changes.trySend(Unit)
            }
        }, lifetime)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                if (event.editor === editor) changes.close(IOException("The Lean editor has been closed."))
            }
        }, lifetime)

        while (true) {
            currentCoroutineContext().ensureActive()
            requireCurrentEditor(editor, stamp)
            if (!model.isFoldingEnabled) throw IOException("Code folding is disabled in this editor.")
            val regions = ranges.map { model.getFoldRegion(it.start, it.end) }
            if (regions.all { it != null }) {
                model.runBatchFoldingOperation {
                    change(regions.map { checkNotNull(it) })
                }
                return
            }
            changes.receive()
        }
    } finally {
        Disposer.dispose(lifetime)
        changes.close()
    }
}

private fun requireCurrentEditor(editor: Editor, stamp: Long) {
    if (editor.isDisposed) throw IOException("The Lean editor has been closed.")
    if (editor.document.modificationStamp != stamp) {
        throw IOException("The Lean document changed. Try the folding command again.")
    }
}

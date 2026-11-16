package plugin.lean.editor

import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plugin.lean.language.LeanFileType
import plugin.lean.rpc.LeanFileApi
import plugin.lean.rpc.LeanProofFolds
import plugin.lean.rpc.LeanRpcResult
import plugin.lean.settings.LeanEditorSettings
import java.io.IOException

private val AUTOMATIC_FOLD = Key.create<Job>("Lean automatic proof folding")

internal fun cancelAutomaticProofFolding(editor: Editor) {
    editor.getUserData(AUTOMATIC_FOLD)?.cancel()
}

/** Each main editor gets one opening policy; reopening creates a new lifetime, server restarts do not. */
@Service(Service.Level.PROJECT)
internal class LeanProofFoldingStartup(private val project: Project, private val scope: CoroutineScope) : Disposable {
    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) { consider(event.editor) }
            override fun editorReleased(event: EditorFactoryEvent) { cancelAutomaticProofFolding(event.editor) }
        }, this)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    source.getAllEditors(file).filterIsInstance<TextEditor>().forEach { consider(it.editor) }
                }
            }
        )
    }

    suspend fun start() = withContext(Dispatchers.EDT) {
        EditorFactory.getInstance().allEditors.forEach(::consider)
    }

    fun cancelPending() {
        EditorFactory.getInstance().allEditors.filter { it.project === project }.forEach(::cancelAutomaticProofFolding)
    }

    private fun consider(editor: Editor) {
        if (editor.project !== project || editor.editorKind != EditorKind.MAIN_EDITOR || editor.isDisposed) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file.fileType != LeanFileType || editor.getUserData(CONSIDERED) == true) return
        editor.putUserData(CONSIDERED, true)
        if (!project.service<LeanEditorSettings>().foldProofsOnOpen) return
        val fileId = file.rpcId()
        val stamp = editor.document.modificationStamp
        val job = scope.launch(Dispatchers.EDT, start = CoroutineStart.LAZY) {
            try {
                foldProofsOnOpen(editor, stamp) { LeanFileApi.getInstance().proofFolds(project.projectId(), fileId) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                logger<LeanProofFoldingStartup>().warn("Cannot apply initial Lean proof folding", error)
            } finally {
                editor.putUserData(AUTOMATIC_FOLD, null)
            }
        }
        editor.putUserData(AUTOMATIC_FOLD, job)
        job.start()
    }

    override fun dispose() = Unit

    private companion object {
        val CONSIDERED = Key.create<Boolean>("Lean initial folding considered")
    }
}

internal class LeanProofFoldingStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) { project.service<LeanProofFoldingStartup>().start() }
}

/**
 * Wait for native folds before querying their proof kinds. Editing or changing existing fold states cancels this
 * opening policy. Server availability failures can retry on a later native folding update; there is no polling.
 */
internal suspend fun foldProofsOnOpen(
    editor: Editor, stamp: Long, request: suspend () -> LeanRpcResult<LeanProofFolds>
) = withContext(Dispatchers.EDT) {
    val model = editor.foldingModel as? FoldingModelEx ?: return@withContext
    if (editor.isDisposed || !model.isFoldingEnabled || editor.document.modificationStamp != stamp) return@withContext
    val lifetime = Disposer.newDisposable("Lean initial proof folding")
    val changes = Channel<Unit>(Channel.CONFLATED)
    val operation = currentCoroutineContext().job
    var finished = false
    var previousStates = emptyMap<FoldRegion, Boolean>()
    try {
        model.addListener(object : FoldingListener {
            override fun onFoldProcessingStart() {
                if (!finished) previousStates = model.allFoldRegions.associateWith { it.isExpanded }
            }

            override fun onFoldProcessingEnd() {
                if (finished) return
                if (previousStates.any { (region, expanded) -> region.isValid && region.isExpanded != expanded }) {
                    operation.cancel()
                } else {
                    changes.trySend(Unit)
                }
                previousStates = emptyMap()
            }
        }, lifetime)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { operation.cancel() }
        }, lifetime)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                if (event.editor === editor) operation.cancel()
            }
        }, lifetime)
        changes.trySend(Unit)
        while (true) {
            changes.receive()
            currentCoroutineContext().ensureActive()
            if (!model.isFoldingEnabled) return@withContext
            if (model.allFoldRegions.isEmpty()) continue
            try {
                when (val result = request()) {
                    is LeanRpcResult.Failure -> logger<LeanProofFoldingStartup>().debug(result.message)
                    is LeanRpcResult.Success -> {
                        changeProofFolds(editor, result.value, stamp) { regions ->
                            finished = true
                            val carets = editor.caretModel.allCarets
                            regions.filter { region ->
                                carets.none { caret ->
                                    caret.offset > region.startOffset && caret.offset < region.endOffset ||
                                    caret.hasSelection() && caret.selectionStart < region.endOffset &&
                                    caret.selectionEnd > region.startOffset
                                }
                            }.forEach { it.isExpanded = false }
                        }
                        return@withContext
                    }
                }
            } catch (error: IOException) {
                logger<LeanProofFoldingStartup>().debug("Initial Lean proof folds are not available yet", error)
            }
        }
    } finally {
        Disposer.dispose(lifetime)
        changes.close()
    }
}

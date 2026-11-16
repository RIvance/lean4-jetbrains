package plugin.lean.infoview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import plugin.lean.lsp.LeanServerHub
import plugin.lean.rpc.LeanInfoviewEvent

/** Tracks one view's editor selection in its client context, including changes in the owning server. */
internal class LeanInfoviewCursor(
    private val project: Project,
    private val hub: LeanServerHub,
    scope: CoroutineScope,
    changed: (LeanInfoviewEvent.Cursor) -> Unit
) : Disposable {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val job = scope.launch(Dispatchers.EDT, start = CoroutineStart.LAZY) {
        var previous: LeanServerHub.CursorSnapshot? = null
        for (change in changes) {
            val next = current()
            if (next != previous) {
                previous = next
                changed(next.event)
            }
        }
    }

    init {
        val events = EditorFactory.getInstance().eventMulticaster
        events.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor.project == project) refresh()
            }
        }, this)
        events.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) {
                if (event.editor.project == project) refresh()
            }
        }, this)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            }
        )
    }

    /** Start after the view receives the transport snapshot, so cursors cannot refer to unknown servers. */
    fun start() {
        refresh()
        job.start()
    }

    fun refresh() {
        changes.trySend(Unit)
    }

    private fun current(): LeanServerHub.CursorSnapshot {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return LeanServerHub.CursorSnapshot(LeanInfoviewEvent.Cursor(null, null))
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document)
        if (file == null || !file.isValid || file.extension != "lean") {
            return LeanServerHub.CursorSnapshot(LeanInfoviewEvent.Cursor(null, null))
        }
        val caret = editor.caretModel.primaryCaret
        fun position(offset: Int): JsonObject = buildJsonObject {
            val line = document.getLineNumber(offset)
            put("line", line)
            put("character", offset - document.getLineStartOffset(line))
        }
        val range = buildJsonObject {
            put("start", position(caret.selectionStart))
            put("end", position(caret.selectionEnd))
        }
        return hub.cursor(file, range)
    }

    override fun dispose() {
        job.cancel()
        changes.close()
    }
}

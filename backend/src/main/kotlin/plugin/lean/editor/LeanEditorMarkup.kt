package plugin.lean.editor

import com.google.gson.JsonElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.FillingLineMarkerRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plugin.lean.language.LeanColor
import plugin.lean.lsp.LeanServerHub
import plugin.lean.rpc.LeanInfoviewEvent
import plugin.lean.rpc.MessageDirection
import plugin.lean.tooling.lsp.LeanFileProgress
import plugin.lean.tooling.lsp.LeanProcessingKind
import plugin.lean.tooling.lsp.LeanProcessingRange
import plugin.lean.tooling.lsp.LspPosition
import java.io.IOException

/** Owns Lean's document markup lifetimes. Document and highlighter access is serialized on the EDT. */
@Service(Service.Level.PROJECT)
internal class LeanEditorMarkup(private val project: Project, scope: CoroutineScope) : Disposable {
    private data class DocumentKey(val serverId: String, val uri: String)

    private sealed interface Update {
        data class Progress(val serverId: String, val progress: LeanFileProgress) : Update
        data class Proofs(val serverId: String, val proofs: LeanProofMarkers) : Update
        data class Closed(val key: DocumentKey) : Update
        data class Stopped(val serverId: String) : Update
        data class Edited(val document: Document) : Update
    }

    private val hub = project.service<LeanServerHub>()
    private val updates = Channel<Update>(Channel.UNLIMITED)
    private val markers = mutableMapOf<DocumentKey, LeanDocumentMarkup>()

    init {
        Disposer.register(this, hub.listen { event ->
            when (event) {
                is LeanInfoviewEvent.Server -> {
                    if (event.server.stopped != null) updates.trySend(Update.Stopped(event.server.id))
                }
                is LeanInfoviewEvent.Notification -> {
                    if (event.direction == MessageDirection.Client && event.method == "textDocument/didClose") {
                        val uri = event.params.jsonObject.getValue("textDocument")
                            .jsonObject.getValue("uri").jsonPrimitive.content
                        updates.trySend(Update.Closed(DocumentKey(event.serverId, uri)))
                    }
                    if (
                        event.direction == MessageDirection.Server && event.method == "textDocument/publishDiagnostics"
                    ) {
                        val proofs = try {
                            LeanProofMarkers.fromDiagnostics(event.params.jsonObject)
                        } catch (error: IOException) {
                            logger<LeanEditorMarkup>().warn("Invalid Lean proof status notification", error)
                            return@listen
                        }
                        updates.trySend(Update.Proofs(event.serverId, proofs))
                    }
                }
                else -> Unit
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                updates.trySend(Update.Edited(event.document))
            }
        }, this)
        scope.launch(Dispatchers.EDT) {
            try {
                for (update in updates) {
                    when (update) {
                        is Update.Progress -> {
                            val progress = update.progress
                            val version = progress.version().let { if (it.isPresent) it.asInt else null }
                            show(update.serverId, progress.uri(), version) { it.replaceProgress(progress.ranges()) }
                        }
                        is Update.Proofs -> {
                            val proofs = update.proofs
                            show(update.serverId, proofs.uri, proofs.version) { it.replaceProofs(proofs.markers) }
                        }
                        is Update.Closed -> remove(update.key)
                        is Update.Stopped -> removeWhere { key, _ -> key.serverId == update.serverId }
                        is Update.Edited -> removeWhere { _, value -> value.document === update.document }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) { removeWhere { _, _ -> true } }
            }
        }
    }

    fun receivedProgress(serverId: String, params: JsonElement) {
        val progress = try {
            LeanFileProgress.parse(params)
        } catch (error: IOException) {
            logger<LeanEditorMarkup>().warn("Invalid Lean file progress notification", error)
            return
        }
        updates.trySend(Update.Progress(serverId, progress))
    }

    private fun show(serverId: String, uri: String, version: Int?, update: (LeanDocumentMarkup) -> Unit) {
        val client = hub.currentClient(serverId) ?: return
        val file = client.descriptor.findFileByUri(uri) ?: return
        if (!FileEditorManager.getInstance(project).isFileOpenWithRemotes(file)) return
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
        if (version != null && version != client.getDocumentVersion(document)) return
        val key = DocumentKey(serverId, uri)
        if (markers[key]?.document !== document) remove(key)
        update(markers.getOrPut(key) { LeanDocumentMarkup(project, document) })
    }

    private fun remove(key: DocumentKey) {
        markers.remove(key)?.dispose()
    }

    private fun removeWhere(predicate: (DocumentKey, LeanDocumentMarkup) -> Boolean) {
        markers.filter { (key, value) -> predicate(key, value) }.keys.forEach(::remove)
    }

    override fun dispose() {
        updates.close()
    }
}

/** Owns one document's independent processing and proof markers. Callers supply current snapshots on the EDT. */
class LeanDocumentMarkup(project: Project, val document: Document) : Disposable {
    private enum class Style(val gutter: LeanColor, val stripe: LeanColor, val tooltip: String) {
        PROCESSING(LeanColor.PROCESSING, LeanColor.PROCESSING_STRIPE, "Lean is processing this code"),
        STOPPED(LeanColor.PROCESSING_STOPPED, LeanColor.PROCESSING_STOPPED_STRIPE, "Lean processing stopped")
    }

    private val model = DocumentMarkupModel.forDocument(document, project, true)
    private var processing = emptyList<RangeHighlighter>()
    private var proofs = emptyList<RangeHighlighter>()

    fun replaceProgress(ranges: List<LeanProcessingRange>) {
        processing.forEach { it.dispose() }
        processing = ranges.map { range ->
            val style = when (range.kind()) {
                LeanProcessingKind.PROCESSING -> Style.PROCESSING
                LeanProcessingKind.FATAL_ERROR -> Style.STOPPED
            }
            val highlighter = model.addRangeHighlighter(
                style.stripe.key, offset(document, range.start()), offset(document, range.end()),
                HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.LINES_IN_RANGE
            )
            highlighter.lineMarkerRenderer = object : FillingLineMarkerRenderer {
                override fun getTextAttributesKey() = style.gutter.key
                override fun getMaxWidth() = 3
                override fun getPosition() = LineMarkerRendererEx.Position.LEFT
            }
            highlighter.isThinErrorStripeMark = true
            highlighter.errorStripeTooltip = style.tooltip
            highlighter
        }
    }

    fun replaceProofs(markers: List<LeanProofMarker>) {
        proofs.forEach { it.dispose() }
        proofs = markers.map { marker ->
            val start = offset(document, LspPosition(marker.line, 0))
            model.addRangeHighlighter(
                null, start, start, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.LINES_IN_RANGE
            ).apply {
                gutterIconRenderer = ProofGutterIcon(marker.status)
            }
        }
    }

    private class ProofGutterIcon(private val status: LeanProofStatus) : GutterIconRenderer(), DumbAware {
        override fun getIcon() = status.icon
        override fun getTooltipText() = "Lean: ${status.description}"
        override fun getAccessibleName() = tooltipText
        override fun equals(other: Any?) = other is ProofGutterIcon && other.status == status
        override fun hashCode() = status.hashCode()
    }

    override fun dispose() {
        processing.forEach { it.dispose() }
        proofs.forEach { it.dispose() }
        processing = emptyList()
        proofs = emptyList()
    }

    companion object {
        private fun offset(document: Document, position: LspPosition): Int {
            if (position.line() >= document.lineCount) return document.textLength
            val start = document.getLineStartOffset(position.line())
            val length = document.getLineEndOffset(position.line()) - start
            return start + position.character().coerceAtMost(length)
        }
    }
}

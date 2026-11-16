package plugin.lean.editor

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import plugin.lean.lsp.LeanServerHub
import plugin.lean.rpc.LeanInfoviewEvent
import plugin.lean.rpc.MessageDirection
import java.io.IOException
import java.util.concurrent.CompletableFuture

/** Owns requests and immutable hint snapshots for each synchronized document revision. */
@Service(Service.Level.PROJECT)
internal class LeanInlayService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private data class Revision(
        val file: VirtualFile,
        val document: Document,
        val stamp: Long,
        val lifetime: LeanServerHub.DocumentSnapshot
    )

    private class Entry(val revision: Revision, val request: CompletableFuture<List<InlayHint>>) {
        // Read and published under lock. A failed request remains empty until an edit or server refresh retries it.
        var hints: List<LeanInlayHint> = emptyList()
    }

    private val hub = project.service<LeanServerHub>()
    private val lock = Any()
    private val entries = mutableMapOf<VirtualFile, Entry>()

    init {
        Disposer.register(this, hub.listen { event ->
            // Transport callbacks hold the hub lock; defer cache and editor work to avoid lock inversion.
            when (event) {
                is LeanInfoviewEvent.Server -> scope.launch { refresh(event.server.id) }
                is LeanInfoviewEvent.Notification -> if (
                    event.direction == MessageDirection.Client &&
                    event.method in setOf("textDocument/didOpen", "textDocument/didClose")
                ) {
                    val uri = event.params.jsonObject.getValue("textDocument")
                        .jsonObject.getValue("uri").jsonPrimitive.content
                    scope.launch {
                        val files = invalidate {
                            it.revision.lifetime.serverId == event.serverId && it.revision.lifetime.uri == uri
                        }
                        val file = files.firstOrNull() ?: readAction {
                            hub.currentClient(event.serverId)?.descriptor?.findFileByUri(uri)
                        }
                        if (file != null) restartHighlighting(file)
                    }
                }
                else -> Unit
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                invalidate { it.revision.document === event.document }
            }
        }, this)
    }

    /** Called by a background daemon pass under a read action. It never waits for the server. */
    fun hints(file: PsiFile): List<LeanInlayHint> {
        val virtualFile = file.virtualFile ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return emptyList()
        val lifetime = hub.synchronizedDocument(virtualFile) ?: return emptyList()
        val client = hub.currentClient(lifetime.serverId) ?: return emptyList()
        val capability = client.initializeResult?.capabilities?.inlayHintProvider ?: return emptyList()
        if (capability.left != true && !capability.isRight) return emptyList()
        val revision = Revision(virtualFile, document, document.modificationStamp, lifetime)
        val entry = synchronized(lock) {
            entries[virtualFile]?.takeIf { it.revision == revision } ?: run {
                entries.remove(virtualFile)?.request?.cancel(true)
                val params = InlayHintParams(
                    TextDocumentIdentifier(lifetime.uri),
                    Range(Position(0, 0), getLsp4jPosition(document, document.textLength))
                )
                val request = try {
                    hub.enqueue(
                        lifetime.serverId, { it.textDocumentService.inlayHint(params) }, { it.orEmpty() }
                    )
                } catch (error: ResponseErrorException) {
                    CompletableFuture.failedFuture(error)
                }
                Entry(revision, request).also { created ->
                    entries[virtualFile] = created
                    scope.launch { receive(created) }
                }
            }
        }
        return synchronized(lock) { entry.hints }
    }

    private suspend fun receive(entry: Entry) {
        try {
            val response = entry.request.await()
            val hints = readAction {
                if (!isCurrent(entry.revision)) return@readAction null
                response.map { LeanInlayHint.from(entry.revision.document, it) }
            } ?: return
            val published = synchronized(lock) {
                if (entries[entry.revision.file] !== entry) return@synchronized false
                entry.hints = hints
                true
            }
            if (published) restartHighlighting(entry.revision.file)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseErrorException) {
            logger<LeanInlayService>().debug("Lean inlay request ended", error)
        } catch (error: IOException) {
            logger<LeanInlayService>().warn("Invalid Lean inlay hints", error)
        }
    }

    /** Server-driven refreshes may change hints without changing the editor's text. */
    fun refresh(serverId: String) {
        val files = invalidate { it.revision.lifetime.serverId == serverId }
        scope.launch { files.forEach { restartHighlighting(it) } }
    }

    private fun invalidate(predicate: (Entry) -> Boolean): List<VirtualFile> {
        val removed = synchronized(lock) {
            entries.values.filter(predicate).also { values -> values.forEach { entries.remove(it.revision.file) } }
        }
        removed.forEach { it.request.cancel(true) }
        return removed.map { it.revision.file }
    }

    private suspend fun restartHighlighting(file: VirtualFile) = withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@withContext
        EditorFactory.getInstance().getEditors(document, project).forEach {
            DeclarativeInlayHintsPassFactory.scheduleRecompute(it, project)
        }
    }

    private fun isCurrent(revision: Revision): Boolean =
        revision.file.isValid && revision.document.modificationStamp == revision.stamp &&
        hub.synchronizedDocument(revision.file) == revision.lifetime

    private fun action(file: VirtualFile, id: String): Pair<Revision, LeanInlayHint.Action> {
        val entry = synchronized(lock) { entries[file] } ?: throw outdated()
        if (!isCurrent(entry.revision)) throw outdated()
        val action = synchronized(lock) {
            if (entries[file] !== entry) throw outdated()
            entry.hints.asSequence().flatMap { it.parts.asSequence() }.flatMap { it.actions.asSequence() }
                .firstOrNull { it.reference.id == id }
        } ?: throw outdated()
        return entry.revision to action
    }

    suspend fun perform(file: VirtualFile, id: String) {
        val (revision, action) = readAction { action(file, id) }
        when (val operation = action.operation) {
            is LeanInlayHint.Operation.Insert -> withContext(Dispatchers.EDT) {
                if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(project, listOf(file))) {
                    throw CancellationException("Hint insertion was cancelled")
                }
                val edits = LeanServerHub.gson.fromJson(
                    operation.edits.toString(), Array<TextEdit>::class.java
                ).toList()
                WriteCommandAction.runWriteCommandAction(project, "Insert Lean hint", null, Runnable {
                    if (action(file, id).first != revision) throw outdated()
                    applyEdits(revision.document, edits)
                })
            }
            is LeanInlayHint.Operation.Navigate -> {
                val location = LeanServerHub.gson.fromJson(operation.location.toString(), Location::class.java)
                val params = ShowDocumentParams(location.uri).apply {
                    selection = location.range
                    takeFocus = true
                }
                val result = hub.handler(revision.lifetime.serverId).showDocument(params).await()
                if (!result.isSuccess) throw IOException("The hint's source location could not be opened.")
            }
            is LeanInlayHint.Operation.Command -> {
                val command = LeanServerHub.gson.fromJson(operation.command.toString(), Command::class.java)
                val params = ExecuteCommandParams(command.command, command.arguments)
                val request = readAction {
                    action(file, id)
                    hub.enqueue(
                        revision.lifetime.serverId,
                        { it.workspaceService.executeCommand(params) },
                        { Unit }
                    )
                }
                request.await()
            }
        }
    }

    override fun dispose() {
        invalidate { true }
    }

    companion object {
        private fun outdated() = IOException("This Lean hint is out of date. Wait for the updated hint and try again.")

        /** Validate the whole batch before delegating ordered application to the platform. */
        internal fun applyEdits(document: Document, edits: List<TextEdit>) {
            val ranges = edits.map { edit ->
                getRangeInDocument(document, edit.range)
                    ?: throw IOException("The hint contains an edit outside the document.")
            }.sortedWith(compareBy({ it.startOffset }, { it.endOffset }))
            if (ranges.zipWithNext().any { (first, second) -> first.endOffset > second.startOffset }) {
                throw IOException("The hint contains overlapping edits.")
            }
            check(applyTextEdits(document, edits)) { "Validated inlay edits could not be applied" }
        }
    }
}

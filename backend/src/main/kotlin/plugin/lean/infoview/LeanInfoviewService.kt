package plugin.lean.infoview

import com.google.gson.JsonParser
import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import plugin.lean.lsp.LeanLanguageServer
import plugin.lean.lsp.LeanLspProjectService
import plugin.lean.lsp.LeanServerHub
import plugin.lean.rpc.LeanInfoviewCommand
import plugin.lean.rpc.LeanInfoviewEvent
import plugin.lean.rpc.LeanInfoviewResponse
import plugin.lean.rpc.MessageDirection
import plugin.lean.rpc.TextInsertKind
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Owns RPC leases for one subscribed view; the browser never receives a reusable process-local session ID. */
@Service(Service.Level.PROJECT)
internal class LeanInfoviewService(private val project: Project) {
    /** A reserved alias has no remote ID until connect completes in the same document lifetime. */
    private data class Session(val serverId: String, val uri: String, val remoteId: String? = null)

    private class View(val events: SendChannel<LeanInfoviewEvent>, val scope: CoroutineScope) {
        val sessions = linkedMapOf<String, Session>()
        var closed = false
    }

    private val hub get() = project.service<LeanServerHub>()
    private val lock = Any()
    private val views = mutableMapOf<String, View>()

    fun events(viewId: String): Flow<LeanInfoviewEvent> = callbackFlow {
        val view = View(channel, CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job])))
        synchronized(lock) {
            require(viewId !in views) { "An InfoView with this identity is already connected" }
            views[viewId] = view
        }
        val cursor = LeanInfoviewCursor(project, hub, view.scope) { view.events.trySend(it) }
        try {
            val subscription = hub.listen { event ->
                if (event is LeanInfoviewEvent.Server && event.server.stopped != null) {
                    synchronized(lock) { view.sessions.entries.removeIf { it.value.serverId == event.server.id } }
                }
                if (event is LeanInfoviewEvent.Notification && event.direction == MessageDirection.Client &&
                    event.method == "textDocument/didClose"
                ) {
                    val document = event.params.jsonObject.getValue("textDocument").jsonObject
                    val uri = document.getValue("uri").jsonPrimitive.content
                    synchronized(lock) {
                        view.sessions.entries.removeIf { it.value.serverId == event.serverId && it.value.uri == uri }
                    }
                }
                view.events.trySend(event)
                cursor.refresh()
            }
            try {
                cursor.start()
                send(LeanInfoviewEvent.Ready)
                launch {
                    while (isActive) {
                        delay(10_000)
                        val sessions = synchronized(lock) { view.sessions.toMap() }
                        for ((alias, session) in sessions) {
                            val remoteId = session.remoteId ?: continue
                            try {
                                notify(session.serverId, "$/lean/rpc/keepAlive", buildJsonObject {
                                    put("uri", session.uri)
                                    put("sessionId", remoteId)
                                })
                            } catch (error: ResponseErrorException) {
                                synchronized(lock) { view.sessions.remove(alias) }
                            }
                        }
                    }
                }
                awaitCancellation()
            } finally {
                subscription.dispose()
            }
        } finally {
            Disposer.dispose(cursor)
            view.scope.cancel()
            synchronized(lock) {
                view.closed = true
                view.sessions.clear()
                views.remove(viewId)
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(ClientId.current.asContextElement())

    suspend fun execute(viewId: String, command: LeanInfoviewCommand): LeanInfoviewResponse {
        return try {
            val view = synchronized(lock) { views[viewId] }
                ?: throw LeanServerHub.unavailable("The InfoView has disconnected")
            val pending = view.scope.async { perform(view, command) }
            val result = try {
                pending.await()
            } finally {
                pending.cancel()
            }
            LeanInfoviewResponse.Success(result)
        } catch (error: ResponseErrorException) {
            val response = error.responseError
            LeanInfoviewResponse.Error(response.code, response.message, LeanServerHub.json(response.data))
        }
    }

    private suspend fun perform(view: View, command: LeanInfoviewCommand): JsonElement {
        return when (command) {
            is LeanInfoviewCommand.Request -> request(
                command.serverId, command.uri, command.method,
                translateSession(view, command.serverId, command.uri, command.method, command.params)
            )
            is LeanInfoviewCommand.Notify -> notify(
                command.serverId, command.method,
                translateSession(view, command.serverId, command.uri, command.method, command.params)
            )
            is LeanInfoviewCommand.Connect -> connect(view, command.serverId, command.uri)
            is LeanInfoviewCommand.Disconnect -> {
                synchronized(lock) { view.sessions.remove(command.sessionId) }
                JsonNull
            }
            is LeanInfoviewCommand.Insert -> insert(command)
            is LeanInfoviewCommand.Edit -> {
                val edit = LeanServerHub.gson.fromJson(command.edit.toString(), WorkspaceEdit::class.java)
                val result = hub.handler(command.serverId).applyEdit(ApplyWorkspaceEditParams(edit)).await()
                if (!result.isApplied) fail(result.failureReason ?: "The workspace edit could not be applied")
                JsonNull
            }
            is LeanInfoviewCommand.Show -> {
                val params = LeanServerHub.gson.fromJson(command.location.toString(), ShowDocumentParams::class.java)
                val result = hub.handler(command.serverId).showDocument(params).await()
                if (!result.isSuccess) fail("The requested location could not be opened")
                JsonNull
            }
            is LeanInfoviewCommand.Restart -> {
                project.service<LeanLspProjectService>().restartFile(command.serverId, command.uri)
                JsonNull
            }
        }
    }

    private suspend fun connect(view: View, serverId: String, uri: String): JsonElement {
        val alias = UUID.randomUUID().toString()
        val pending = Session(serverId, uri)
        synchronized(lock) {
            if (view.closed) throw LeanServerHub.unavailable("The InfoView has disconnected")
            view.sessions[alias] = pending
        }
        try {
            val result = request(serverId, uri, "$/lean/rpc/connect", buildJsonObject { put("uri", uri) })
            val remoteId = result.jsonObject.getValue("sessionId").jsonPrimitive.content
            synchronized(lock) {
                if (view.sessions[alias] !== pending) {
                    throw LeanServerHub.unavailable("The Lean document closed or restarted while connecting")
                }
                view.sessions[alias] = pending.copy(remoteId = remoteId)
            }
            return JsonPrimitive(alias)
        } catch (error: Exception) {
            synchronized(lock) { view.sessions.remove(alias) }
            throw error
        }
    }

    private fun translateSession(
        view: View, serverId: String, uri: String, method: String, params: JsonElement
    ): JsonElement {
        if (!method.startsWith("$/lean/rpc/")) return params
        val fields = params.jsonObject
        val alias = fields["sessionId"]?.jsonPrimitive?.content ?: return params
        val session = synchronized(lock) { view.sessions[alias] }
            ?: throw LeanServerHub.unavailable("This Lean RPC session has expired")
        if (session.serverId != serverId || session.uri != uri) {
            throw LeanServerHub.unavailable("This Lean RPC session belongs to another document or server")
        }
        val remoteId = session.remoteId ?: throw LeanServerHub.unavailable("This Lean RPC session is still connecting")
        return JsonObject(fields + ("sessionId" to JsonPrimitive(remoteId)))
    }

    private suspend fun request(serverId: String, uri: String, method: String, params: JsonElement): JsonElement {
        if (method !in SUPPORTED_REQUESTS) fail("Unsupported Lean LSP method: $method", -32601)
        hub.awaitDocument(serverId, uri)
        return hub.request(serverId) { server ->
            val pending = (server as Endpoint).request(method, JsonParser.parseString(params.toString()))
            val mapped = pending.thenApply(LeanServerHub::json)
            // CompletableFuture transformations do not propagate cancellation to their source.
            mapped.whenComplete { _, _ -> if (mapped.isCancelled) pending.cancel(true) }
            mapped
        }
    }

    private suspend fun notify(serverId: String, method: String, params: JsonElement): JsonElement {
        // Await dispatch so callers observe failures and cancellation instead of a queued success.
        return hub.request(serverId) { server ->
            (server as Endpoint).notify(method, JsonParser.parseString(params.toString()))
            CompletableFuture.completedFuture(JsonNull)
        }
    }

    private suspend fun insert(command: LeanInfoviewCommand.Insert): JsonElement = withContext(Dispatchers.EDT) {
        val selected = FileEditorManager.getInstance(project).selectedTextEditor
        val position = command.position?.let {
            LeanServerHub.gson.fromJson(it.toString(), TextDocumentPositionParams::class.java)
        }
        val file = if (position == null) {
            selected?.document?.let { FileDocumentManager.getInstance().getFile(it) }
        } else {
            val url = VfsUtilCore.urlToPath(position.textDocument.uri)
            LocalFileSystem.getInstance().findFileByPath(url)
        } ?: fail("Open a Lean file before inserting text")
        if (file.extension != "lean") fail("Text can only be inserted into a Lean document")
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file)).hasReadonlyFiles()) {
            fail("The Lean document is read-only")
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: fail("Cannot read the Lean document")
        val target = position?.position?.let { offset(document, it) } ?: checkNotNull(selected).caretModel.offset
        val line = document.getLineNumber(target)
        val lineStart = document.getLineStartOffset(line)
        val (insertionOffset, text) = if (command.kind == TextInsertKind.Above) {
            val lineText = document.getText(TextRange(lineStart, document.getLineEndOffset(line)))
            val margin = lineText.takeWhile { it == ' ' || it == '\t' }
            lineStart to (margin + command.text.replace("\n", "\n$margin") + "\n")
        } else {
            target to command.text
        }
        WriteCommandAction.runWriteCommandAction(project, "Insert Lean InfoView Text", null, Runnable {
            document.insertString(insertionOffset, text)
        })
        OpenFileDescriptor(project, file, insertionOffset + text.length).navigate(true)
        JsonNull
    }

    private fun offset(document: Document, position: Position): Int {
        if (position.line < 0 || position.character < 0) fail("A document position cannot be negative", -32602)
        if (position.line >= document.lineCount) return document.textLength
        val start = document.getLineStartOffset(position.line)
        val length = document.getLineEndOffset(position.line) - start
        return start + position.character.coerceAtMost(length)
    }

    private fun fail(message: String, code: Int = -32603): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))

    companion object {
        private val SUPPORTED_REQUESTS = ServiceEndpoints.getSupportedMethods(LeanLanguageServer::class.java).keys
    }
}

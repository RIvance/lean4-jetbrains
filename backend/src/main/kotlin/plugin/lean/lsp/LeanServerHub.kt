package plugin.lean.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.services.LanguageServer
import plugin.lean.rpc.LeanInfoviewEvent
import plugin.lean.rpc.LeanDocumentStatus
import plugin.lean.rpc.LeanServerInfo
import plugin.lean.rpc.MessageDirection
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The transport registry owns process identity and ordered, lossless notification subscriptions. */
@Service(Service.Level.PROJECT)
internal class LeanServerHub(private val project: Project) : Disposable {
    private sealed interface DocumentState {
        data object Opening : DocumentState
        data class Open(val version: Int, val id: UUID = UUID.randomUUID()) : DocumentState
        data object Closed : DocumentState
    }

    /** Equal positions in different document lifetimes still require a cursor update. */
    data class CursorSnapshot(val event: LeanInfoviewEvent.Cursor, val documentId: UUID? = null)

    /** A reopened document has a new identity even if its text and protocol version are unchanged. */
    data class DocumentSnapshot(val serverId: String, val uri: String, val id: UUID)

    private sealed interface Documents {
        data class Active(val states: Map<String, DocumentState> = emptyMap()) : Documents
        data class Stopped(val reason: String) : Documents
    }

    private class Connection(
        val descriptor: LeanLspClientDescriptor,
        var info: LeanServerInfo,
        var handler: LspServerNotificationsHandler? = null,
        var initializeRequest: JsonElement? = null,
        var initializeResult: JsonElement? = null,
        val documents: MutableStateFlow<Documents> = MutableStateFlow(Documents.Active()),
        val notifications: MutableMap<Pair<String, String>, LeanInfoviewEvent.Notification> = linkedMapOf(),
        val requests: MutableSet<CompletableFuture<*>> = linkedSetOf()
    )

    // Process callbacks can arrive on different I/O threads. This lock also makes snapshot + subscribe atomic.
    private val lock = Any()
    private val connections = linkedMapOf<String, Connection>()
    private val listeners = linkedSetOf<(LeanInfoviewEvent) -> Unit>()

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    documentState(file, DocumentState.Opening)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (!source.isFileOpenWithRemotes(file)) documentState(file, DocumentState.Closed)
                }
            }
        )
    }

    fun started(descriptor: LeanLspClientDescriptor): String = synchronized(lock) {
        val root = descriptor.getFileUri(descriptor.roots.single())
        connections.values.firstOrNull { it.info.root == root }?.let { previous ->
            stop(previous, "Lean server restarted")
            connections.remove(previous.info.id)
        }
        val info = LeanServerInfo(UUID.randomUUID().toString(), root)
        connections[info.id] = Connection(descriptor, info)
        emit(LeanInfoviewEvent.Server(info))
        info.id
    }

    fun attachHandler(id: String, handler: LspServerNotificationsHandler) = synchronized(lock) {
        connection(id).handler = handler
    }

    fun initialized(id: String): Unit = synchronized(lock) {
        val connection = connections[id]?.takeIf { it.info.stopped == null } ?: return
        connection.info = connection.info.copy(result = checkNotNull(connection.initializeResult))
        emit(LeanInfoviewEvent.Server(connection.info))
    }

    fun stopped(id: String, exitCode: Int) {
        synchronized(lock) { connections[id]?.let { stop(it, "Lean server stopped (exit code $exitCode)") } }
    }

    fun failed(id: String, reason: String) {
        synchronized(lock) { connections[id]?.let { stop(it, reason) } }
    }

    private fun stop(connection: Connection, reason: String) {
        if (connection.info.stopped != null) return
        connection.info = connection.info.copy(stopped = reason)
        connection.documents.value = Documents.Stopped(reason)
        connection.notifications.clear()
        connection.handler = null
        val requests = connection.requests.toList()
        connection.requests.clear()
        requests.forEach { it.completeExceptionally(unavailable(reason)) }
        emit(LeanInfoviewEvent.Server(connection.info))
    }

    /** Called directly by the observed process streams; extension fields never pass through LSP4j DTOs. */
    fun message(id: String, direction: MessageDirection, bytes: ByteArray) {
        val envelope = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        var publishDiagnostics: (() -> Unit)? = null
        synchronized(lock) {
            val connection = connections[id]?.takeIf { it.info.stopped == null } ?: return
            val method = envelope["method"]?.jsonPrimitive?.content
            if (direction == MessageDirection.Client && method == "initialize") {
                connection.initializeRequest = envelope["id"]
            }
            if (direction == MessageDirection.Server && method == null &&
                envelope["id"] == connection.initializeRequest && connection.initializeRequest != null
            ) {
                val result = envelope["result"]
                if (result != null && result != JsonNull) {
                    connection.initializeResult = result
                } else if (envelope["error"] != null) {
                    val message = envelope.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
                    stop(connection, message)
                }
            }
            if (method == null || "id" in envelope) return
            var params = envelope["params"] ?: JsonNull
            if (direction == MessageDirection.Server && method in REPLAYED_NOTIFICATIONS) {
                val fields = params.jsonObject
                val uri = (fields["uri"] ?: fields.getValue("textDocument").jsonObject.getValue("uri"))
                    .jsonPrimitive.content
                val documents = connection.documents.value as Documents.Active
                val document = documents.states[uri]
                val version = fields["version"] ?: fields["textDocument"]?.jsonObject?.get("version")
                if (version != null && version != JsonNull && document is DocumentState.Open) {
                    if (version.jsonPrimitive.int != document.version) return
                }
                if (method == "textDocument/publishDiagnostics") {
                    val previous = connection.notifications[method to uri]?.params?.jsonObject
                    val snapshot = try {
                        LeanDiagnostics.accumulate(previous, fields)
                    } catch (error: IOException) {
                        logger<LeanServerHub>().warn("Invalid Lean diagnostic notification", error)
                        return
                    }
                    val editorParams = gson.fromJson(
                        LeanDiagnostics.forEditor(snapshot).toString(), PublishDiagnosticsParams::class.java
                    )
                    val handler = checkNotNull(connection.handler)
                    publishDiagnostics = { handler.publishDiagnostics(editorParams) }
                    params = snapshot
                }
                // Native LSP diagnostics may clear a closed file; InfoView state ends with its document lifetime.
                if (document == DocumentState.Closed) return@synchronized
                connection.notifications[method to uri] = LeanInfoviewEvent.Notification(id, direction, method, params)
            }
            if (direction == MessageDirection.Client && method == "textDocument/didClose") {
                val uri = params.jsonObject.getValue("textDocument").jsonObject.getValue("uri").jsonPrimitive.content
                setDocumentState(connection, uri, DocumentState.Closed)
                connection.notifications.keys.removeIf { it.second == uri }
            }
            if (direction == MessageDirection.Client && method == "textDocument/didOpen") {
                val document = params.jsonObject.getValue("textDocument").jsonObject
                val uri = document.getValue("uri").jsonPrimitive.content
                setDocumentState(connection, uri, DocumentState.Open(document.getValue("version").jsonPrimitive.int))
            }
            if (direction == MessageDirection.Client && method == "textDocument/didChange") {
                val document = params.jsonObject.getValue("textDocument").jsonObject
                val uri = document.getValue("uri").jsonPrimitive.content
                val documents = connection.documents.value as Documents.Active
                val previous = documents.states[uri] as? DocumentState.Open
                if (previous != null) {
                    val version = document.getValue("version").jsonPrimitive.int
                    setDocumentState(connection, uri, previous.copy(version = version))
                }
                connection.notifications.keys.removeIf { it.second == uri }
            }
            emit(LeanInfoviewEvent.Notification(id, direction, method, params))
        }
        // Native diagnostics touch editor caches. Keep that work outside the transport registry's lock.
        publishDiagnostics?.invoke()
    }

    /** The callback must be nonblocking. Disposal removes it before returning. */
    fun listen(listener: (LeanInfoviewEvent) -> Unit): Disposable = synchronized(lock) {
        connections.values.forEach { connection ->
            listener(LeanInfoviewEvent.Server(connection.info))
            connection.notifications.values.forEach(listener)
        }
        listeners.add(listener)
        Disposable { synchronized(lock) { listeners.remove(listener) } }
    }

    fun requireCurrent(id: String) {
        synchronized(lock) { connection(id) }
    }

    /** Dispatch and completion belong to the same generation. Submitters return explicit non-null protocol values. */
    suspend fun <T : Any> request(id: String, submit: (LanguageServer) -> CompletableFuture<T>): T =
        enqueue(id, submit).await()

    /**
     * Enqueues synchronously on the native document-sync dispatcher. Callers may capture a document and enqueue
     * its operation under one read action, then await outside the read lock. Shutdown also completes queued work.
     */
    fun <T : Any> enqueue(id: String, submit: (LanguageServer) -> CompletableFuture<T>): CompletableFuture<T> =
        enqueue(id, submit) { checkNotNull(it) { "A Lean request returned an implicit null result" } }

    /** Convert protocol responses without losing cancellation of the original JSON-RPC request. */
    fun <T, R : Any> enqueue(
        id: String,
        submit: (LanguageServer) -> CompletableFuture<T>,
        receive: (T) -> R
    ): CompletableFuture<R> {
        val result = CompletableFuture<R>()
        val client = synchronized(lock) {
            val connection = connection(id)
            val client = client(connection)
                ?: throw unavailable("The Lean server is starting. Try again when it is ready.")
            connection.requests.add(result)
            result.whenComplete { _, _ -> synchronized(lock) { connection.requests.remove(result) } }
            client
        }
        try {
            client.sendNotification { server ->
                if (result.isDone) return@sendNotification
                try {
                    requireCurrent(id)
                    val pending = submit(server)
                    pending.whenComplete { value, error ->
                        if (error != null) {
                            result.completeExceptionally(error)
                        } else {
                            try {
                                requireCurrent(id)
                                result.complete(receive(value))
                            } catch (error: Exception) {
                                result.completeExceptionally(error)
                            }
                        }
                    }
                    result.whenComplete { _, _ ->
                        if (result.isCancelled) client.sendNotification { pending.cancel(true) }
                    }
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
        } catch (error: Exception) {
            result.completeExceptionally(error)
        }
        return result
    }

    /** Initialization and document opening are distinct platform stages. Never send a request between them. */
    suspend fun awaitDocument(id: String, uri: String) {
        val connection = synchronized(lock) { connection(id) }
        val isOpen = readAction {
            val file = connection.descriptor.findFileByUri(uri)
            if (file != null && !ProjectFileIndex.getInstance(project).isInContent(file)) {
                throw ResponseErrorException(ResponseError(
                    -32602, "Add this file's folder to the project content roots to use the Lean language server", null
                ))
            }
            file != null && FileEditorManager.getInstance(project).isFileOpenWithRemotes(file) &&
            connection.descriptor.isSupportedFile(file)
        }
        if (!isOpen) throw ResponseErrorException(ResponseError(-32801, "The Lean document is no longer open", null))
        val documents = connection.documents.first { documents ->
            when (documents) {
                is Documents.Stopped -> true
                is Documents.Active -> when (documents.states[uri]) {
                    is DocumentState.Open, DocumentState.Closed -> true
                    DocumentState.Opening, null -> false
                }
            }
        }
        if (documents is Documents.Stopped) throw unavailable(documents.reason)
        if ((documents as Documents.Active).states[uri] == DocumentState.Closed) {
            throw ResponseErrorException(ResponseError(-32801, "The Lean document was closed", null))
        }
    }

    private fun documentState(file: VirtualFile, state: DocumentState) = synchronized(lock) {
        connections.values.filter { it.info.stopped == null && it.descriptor.isSupportedFile(file) }.forEach {
            val uri = it.descriptor.getFileUri(file)
            // Opening an already open editor does not invalidate a document the server has received.
            val received = (it.documents.value as Documents.Active).states[uri] is DocumentState.Open
            if (state != DocumentState.Opening || !received) {
                setDocumentState(it, uri, state)
            }
        }
    }

    private fun setDocumentState(connection: Connection, uri: String, state: DocumentState) {
        val documents = connection.documents.value as? Documents.Active ?: return
        connection.documents.value = Documents.Active(documents.states + (uri to state))
    }

    fun client(id: String): LspClient = synchronized(lock) {
        client(connection(id)) ?: throw unavailable("The Lean server is starting. Try again when it is ready.")
    }

    fun currentClient(id: String): LspClient? = synchronized(lock) {
        connections[id]?.takeIf { it.info.stopped == null }?.let(::client)
    }

    private fun client(connection: Connection): LspClient? = LspClientManager.getInstance(project)
        .getClients(LeanLspIntegrationProvider::class.java)
        .singleOrNull { it.descriptor === connection.descriptor && it.state == LspServerState.Running }

    fun handler(id: String): LspServerNotificationsHandler = synchronized(lock) {
        connection(id).handler ?: throw unavailable("The Lean server is still starting")
    }

    private fun connection(id: String): Connection = connections[id]?.takeIf { it.info.stopped == null }
        ?: throw unavailable("This Lean server has stopped. Reconnect to the current server.")

    private fun emit(event: LeanInfoviewEvent) {
        listeners.forEach { it(event) }
    }

    /** Resolves a view's editor selection against the current process and native document synchronization. */
    fun cursor(file: VirtualFile, range: JsonObject): CursorSnapshot = synchronized(lock) {
        val connection = connectionFor(file)
        if (connection == null) return CursorSnapshot(LeanInfoviewEvent.Cursor(null, null))
        val uri = connection.descriptor.getFileUri(file)
        val documents = connection.documents.value as? Documents.Active
        val state = documents?.states[uri]
        val document = state as? DocumentState.Open
        val isSynchronized = connection.info.result != null && document != null
        val status = when {
            isSynchronized -> LeanDocumentStatus.Synchronized
            state == DocumentState.Closed -> LeanDocumentStatus.Closed
            else -> LeanDocumentStatus.Waiting
        }
        val location = if (!isSynchronized) null else buildJsonObject {
            put("uri", uri)
            put("range", range)
        }
        CursorSnapshot(LeanInfoviewEvent.Cursor(connection.info.id, location, status), document?.id)
    }

    fun serverFor(file: VirtualFile): LeanServerInfo? = synchronized(lock) { connectionFor(file)?.info }

    fun synchronizedDocument(file: VirtualFile): DocumentSnapshot? = synchronized(lock) {
        val connection = connectionFor(file)?.takeIf { it.info.result != null && it.info.stopped == null }
            ?: return null
        val uri = connection.descriptor.getFileUri(file)
        val documents = connection.documents.value as? Documents.Active ?: return null
        val document = documents.states[uri] as? DocumentState.Open ?: return null
        DocumentSnapshot(connection.info.id, uri, document.id)
    }

    fun documentUri(id: String, file: VirtualFile): String = synchronized(lock) {
        connection(id).descriptor.getFileUri(file)
    }

    private fun connectionFor(file: VirtualFile): Connection? {
        if (!file.isValid) return null
        val candidates = connections.values.filter { it.descriptor.isSupportedFile(file) }
        return candidates.firstOrNull { it.info.stopped == null } ?: candidates.lastOrNull()
    }

    override fun dispose() = Unit

    companion object {
        private val REPLAYED_NOTIFICATIONS = setOf("textDocument/publishDiagnostics", "$/lean/fileProgress")
        // The LSP serializer preserves Either and other protocol representations when crossing into JSON.
        val gson = MessageJsonHandler(emptyMap()).gson

        fun json(value: Any?): JsonElement = Json.parseToJsonElement(gson.toJson(value))

        fun unavailable(message: String): ResponseErrorException =
            ResponseErrorException(ResponseError(-32900, message, null))
    }
}

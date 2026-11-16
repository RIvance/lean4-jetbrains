package plugin.lean

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.Assert.*
import org.junit.Test
import plugin.lean.lsp.LeanInitializeParams

class LeanInitializationTest {
    @Test
    fun initializationDeclaresLeanExtensionsOnTheWireAndRetainsNativeFields() {
        val connection = Connection()
        val initialize = InitializeParams().apply {
            capabilities = ClientCapabilities().apply {
                workspace = WorkspaceClientCapabilities()
                textDocument = TextDocumentClientCapabilities()
                window = WindowClientCapabilities()
                general = GeneralClientCapabilities()
                experimental = mapOf("anotherExtension" to true)
            }
            initializationOptions = mapOf("hasWidgets" to true)
            processId = 123
        }
        val original = connection.json.gson.toJsonTree(initialize)
        val params = LeanInitializeParams.from(initialize)
        assertEquals(123, params.processId)
        assertEquals(initialize.capabilities.workspace, params.capabilities.workspace)
        val pending = connection.server.initialize(params)
        val request = connection.sent.single()
        assertEquals("initialize", request.get("method").asString)
        val encoded = request.getAsJsonObject("params").deepCopy()
        val lean = encoded.getAsJsonObject("capabilities").remove("lean").asJsonObject
        assertTrue(lean.get("incrementalDiagnosticSupport").asBoolean)
        assertTrue(lean.get("silentDiagnosticSupport").asBoolean)
        assertEquals("v1", lean.get("rpcWireFormat").asString)
        assertEquals(original, encoded)
        assertEquals(original, connection.json.gson.toJsonTree(initialize))

        connection.remote.consume(connection.json.parseMessage("""
            {"jsonrpc": "2.0", "id": ${request.get("id")}, "result": {"capabilities": {"hoverProvider": true}}}
        """))
        assertTrue(pending.join().capabilities.hoverProvider.left)
    }

    @Test
    fun nativeNotificationsGenericRequestsAndCancellationKeepTheirProtocolBehavior() {
        val connection = Connection()
        connection.server.initialized(InitializedParams())
        assertEquals("initialized", connection.sent.single().get("method").asString)

        val endpoint = connection.server as Endpoint
        val params = JsonObject().apply { addProperty("uri", "file:///Main.lean") }
        val pending = endpoint.request("$/lean/rpc/connect", params)
        val request = connection.sent.last()
        assertEquals("$/lean/rpc/connect", request.get("method").asString)
        assertEquals(params, request.get("params"))
        assertTrue(pending.cancel(true))
        val cancellation = connection.sent.last()
        assertEquals("$/cancelRequest", cancellation.get("method").asString)
        assertEquals(request.get("id"), cancellation.getAsJsonObject("params").get("id"))
    }

    private class Connection {
        val sent = mutableListOf<JsonObject>()
        val json = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(LanguageServer::class.java))
        val remote = RemoteEndpoint(
            { message -> sent.add(JsonParser.parseString(json.serialize(message)).asJsonObject) },
            ServiceEndpoints.toEndpoint(emptyList<Any>())
        ).apply { jsonHandler = json }
        val server = ServiceEndpoints.toServiceObject(remote, LanguageServer::class.java)

        init {
            json.methodProvider = remote
        }
    }
}

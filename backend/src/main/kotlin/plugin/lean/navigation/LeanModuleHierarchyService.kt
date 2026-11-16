package plugin.lean.navigation

import com.google.gson.JsonParser
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.ShowDocumentParams
import plugin.lean.lsp.LeanLanguageServer
import plugin.lean.lsp.LeanServerHub
import plugin.lean.rpc.LeanImport
import plugin.lean.rpc.LeanModule
import plugin.lean.rpc.LeanModuleHierarchy
import plugin.lean.rpc.LeanModuleHierarchyDirection
import java.io.IOException

@Service(Service.Level.PROJECT)
internal class LeanModuleHierarchyService(private val project: Project) {
    private val hub get() = project.service<LeanServerHub>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(file: VirtualFile): LeanModuleHierarchy? {
        val server = readAction {
            if (!file.isValid || file.isDirectory || file.extension != "lean") {
                throw IOException("Choose an open Lean source file to inspect its module.")
            }
            hub.serverFor(file)
        } ?: throw IOException("The Lean server is not running for this file. Check Lean → Toolchains.")
        val documentUri = hub.documentUri(server.id, file)
        hub.awaitDocument(server.id, documentUri)
        val initialized = readAction { hub.serverFor(file) }
        if (initialized?.id != server.id) throw LeanServerHub.unavailable("Lean restarted. Refresh the hierarchy.")
        val capabilities = (initialized.result as? JsonObject)?.get("capabilities") as? JsonObject
        val experimental = capabilities?.get("experimental") as? JsonObject
        val support = experimental?.get("moduleHierarchyProvider")
        if (support == null || support == JsonNull) {
            throw IOException("This Lean server does not support module hierarchy.")
        }
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", documentUri) })
        }
        val result = hub.request(server.id) { languageServer ->
            (languageServer as LeanLanguageServer).prepareModuleHierarchy(JsonParser.parseString(params.toString()))
                .thenApply { it ?: com.google.gson.JsonNull.INSTANCE }
        }
        if (result.isJsonNull) return null
        return LeanModuleHierarchy(server.id, decode(result.toString()))
    }

    suspend fun children(
        serverId: String, module: LeanModule, direction: LeanModuleHierarchyDirection
    ): List<LeanImport> {
        val params = buildJsonObject { put("module", json.encodeToJsonElement(module)) }
        val request = JsonParser.parseString(params.toString())
        val result = hub.request(serverId) { languageServer ->
            val server = languageServer as LeanLanguageServer
            when (direction) {
                LeanModuleHierarchyDirection.IMPORTS -> server.moduleImports(request)
                LeanModuleHierarchyDirection.IMPORTED_BY -> server.moduleImportedBy(request)
            }
        }
        return decode(result.toString())
    }

    suspend fun navigate(serverId: String, module: LeanModule) {
        val params = ShowDocumentParams(module.uri).apply { takeFocus = true }
        val result = hub.handler(serverId).showDocument(params).await()
        if (!result.isSuccess) throw IOException("Cannot open the source of ${module.name}.")
    }

    private inline fun <reified T> decode(value: String): T = try {
        json.decodeFromString<T>(value)
    } catch (error: SerializationException) {
        throw IOException("Lean returned an invalid module hierarchy response.", error)
    }
}

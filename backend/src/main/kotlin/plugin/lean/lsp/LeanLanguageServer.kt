package plugin.lean.lsp

import com.google.gson.JsonElement
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * Lean's LSP extensions. LSP4j needs their response types even when requests use its generic Endpoint API;
 * undeclared response types are discarded. Widget-specific methods remain data inside the generic RPC call.
 */
internal interface LeanLanguageServer : LanguageServer {
    @JsonRequest("$/lean/rpc/connect")
    fun connect(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("$/lean/rpc/call")
    fun call(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("$/lean/plainGoal")
    fun plainGoal(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("$/lean/plainTermGoal")
    fun plainTermGoal(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("textDocument/waitForDiagnostics")
    fun waitForDiagnostics(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("$/lean/prepareModuleHierarchy")
    fun prepareModuleHierarchy(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("$/lean/moduleHierarchy/imports")
    fun moduleImports(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("$/lean/moduleHierarchy/importedBy")
    fun moduleImportedBy(params: JsonElement): CompletableFuture<JsonElement>

    @JsonRequest("$/lean/waitForILeans")
    fun waitForILeans(params: JsonElement): CompletableFuture<JsonElement>
}

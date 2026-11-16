package plugin.lean.rpc

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import plugin.lean.lsp.LeanServerHub
import java.io.IOException

/** Keep expected failures readable across IDE processes; cancellation and programming errors still propagate. */
internal suspend fun <T> leanRpcResult(operation: suspend () -> T): LeanRpcResult<T> = try {
    LeanRpcResult.Success(operation())
} catch (error: IOException) {
    LeanRpcResult.Failure(error.message ?: "The Lean operation failed.")
} catch (error: ResponseErrorException) {
    val response = error.responseError
    LeanRpcResult.Failure(response.message, response.code, LeanServerHub.json(response.data))
}

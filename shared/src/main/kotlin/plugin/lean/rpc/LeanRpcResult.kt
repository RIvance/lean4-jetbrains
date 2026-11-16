package plugin.lean.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Expected operation failures cross RPC as values; remote exception messages can contain stack traces. */
@Serializable
sealed interface LeanRpcResult<out T> {
    @Serializable
    data class Success<T>(val value: T) : LeanRpcResult<T>

    @Serializable
    data class Failure(
        val message: String, val responseCode: Int? = null, val data: JsonElement? = null
    ) : LeanRpcResult<Nothing>
}

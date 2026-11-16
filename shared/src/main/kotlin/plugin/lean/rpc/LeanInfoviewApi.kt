package plugin.lean.rpc

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A server ID denotes one process lifetime, even when its workspace is restarted. */
@Serializable
data class LeanServerInfo(
    val id: String,
    val root: String,
    val result: JsonElement? = null,
    val stopped: String? = null
)

@Serializable
enum class MessageDirection { Server, Client }

@Serializable
enum class LeanDocumentStatus { Waiting, Synchronized, Closed }

@Serializable
sealed interface LeanInfoviewEvent {
    @Serializable
    @SerialName("server")
    data class Server(val server: LeanServerInfo) : LeanInfoviewEvent

    @Serializable
    @SerialName("notification")
    data class Notification(
        val serverId: String,
        val direction: MessageDirection,
        val method: String,
        val params: JsonElement
    ) : LeanInfoviewEvent

    /** Location is available only after synchronization; a closed document is distinct from pending startup. */
    @Serializable
    @SerialName("cursor")
    data class Cursor(
        val serverId: String?,
        val location: JsonElement?,
        val documentStatus: LeanDocumentStatus? = null
    ) : LeanInfoviewEvent

    @Serializable
    @SerialName("ready")
    data object Ready : LeanInfoviewEvent
}

@Serializable
enum class TextInsertKind { Here, Above }

/** Operations exposed by the upstream InfoView's EditorApi; JSON is retained at the LSP boundary. */
@Serializable
sealed interface LeanInfoviewCommand {
    @Serializable
    @SerialName("request")
    data class Request(
        val serverId: String, val uri: String, val method: String, val params: JsonElement
    ) : LeanInfoviewCommand

    @Serializable
    @SerialName("notify")
    data class Notify(
        val serverId: String, val uri: String, val method: String, val params: JsonElement
    ) : LeanInfoviewCommand

    @Serializable
    @SerialName("connect")
    data class Connect(val serverId: String, val uri: String) : LeanInfoviewCommand

    @Serializable
    @SerialName("disconnect")
    data class Disconnect(val sessionId: String) : LeanInfoviewCommand

    @Serializable
    @SerialName("insert")
    data class Insert(
        val text: String, val kind: TextInsertKind, val position: JsonElement? = null
    ) : LeanInfoviewCommand

    @Serializable
    @SerialName("edit")
    data class Edit(val serverId: String, val edit: JsonElement) : LeanInfoviewCommand

    @Serializable
    @SerialName("show")
    data class Show(val serverId: String, val location: JsonElement) : LeanInfoviewCommand

    @Serializable
    @SerialName("restart")
    data class Restart(val serverId: String, val uri: String) : LeanInfoviewCommand
}

@Serializable
sealed interface LeanInfoviewResponse {
    @Serializable
    @SerialName("success")
    data class Success(val result: JsonElement = JsonNull) : LeanInfoviewResponse

    @Serializable
    @SerialName("error")
    data class Error(val code: Int, val message: String, val data: JsonElement = JsonNull) : LeanInfoviewResponse
}

@Rpc
interface LeanInfoviewApi : RemoteApi<Unit> {
    /** Collecting owns the view and its RPC sessions. Cancelling collection releases them together. */
    suspend fun events(projectId: ProjectId, viewId: String): Flow<LeanInfoviewEvent>
    suspend fun execute(
        projectId: ProjectId, viewId: String, command: LeanInfoviewCommand
    ): LeanInfoviewResponse

    companion object {
        suspend fun getInstance(): LeanInfoviewApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<LeanInfoviewApi>())
    }
}

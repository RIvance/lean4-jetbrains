package plugin.lean.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The server's module identity, including opaque data that must survive subsequent hierarchy requests. */
@Serializable
data class LeanModule(val name: String, val uri: String, val data: JsonElement? = null)

@Serializable
enum class LeanImportMetaKind {
    @SerialName("nonMeta") NON_META,
    @SerialName("meta") META,
    @SerialName("full") FULL
}

@Serializable
data class LeanImportKind(val isPrivate: Boolean, val isAll: Boolean, val metaKind: LeanImportMetaKind)

@Serializable
data class LeanImport(val module: LeanModule, val kind: LeanImportKind)

@Serializable
enum class LeanModuleHierarchyDirection { IMPORTS, IMPORTED_BY }

/** All descendants belong to this process lifetime; a restart requires preparing a new root. */
@Serializable
data class LeanModuleHierarchy(val serverId: String, val root: LeanModule)

/** Files and navigation belong to the project host, including in split mode. */
@Rpc
interface LeanModuleHierarchyApi : RemoteApi<Unit> {
    /** Inspect this open file, independently of subsequent editor selections. Null means Lean has no module for it. */
    suspend fun prepare(projectId: ProjectId, fileId: VirtualFileId): LeanRpcResult<LeanModuleHierarchy?>
    suspend fun children(
        projectId: ProjectId,
        serverId: String,
        module: LeanModule,
        direction: LeanModuleHierarchyDirection
    ): LeanRpcResult<List<LeanImport>>
    suspend fun navigate(projectId: ProjectId, serverId: String, module: LeanModule): LeanRpcResult<Unit>

    companion object {
        suspend fun getInstance(): LeanModuleHierarchyApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<LeanModuleHierarchyApi>())
    }
}

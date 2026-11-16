package plugin.lean.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable

/** Shortcuts for standard Lake commands; run configurations still accept arbitrary Lake arguments. */
@Serializable
enum class LakeProjectTask(vararg arguments: String) {
    BUILD("build"), CLEAN("clean"), TEST("test"), UPDATE("update"), FETCH_CACHE("exe", "cache", "get");

    val arguments: List<String> = java.util.List.of(*arguments)
}

@Rpc
interface LeanProjectApi : RemoteApi<Unit> {
    /** Schedule a native run. Startup, process output, cancellation, and completion belong to the Run tool window. */
    suspend fun runLake(
        projectId: ProjectId, fileId: VirtualFileId?, task: LakeProjectTask
    ): LeanRpcResult<Unit>

    /** Fetch selected files and their imports, grouped by the nearest Lake workspace on the project host. */
    suspend fun fetchCacheForFiles(projectId: ProjectId, files: List<VirtualFileId>): LeanRpcResult<Unit>

    companion object {
        suspend fun getInstance(): LeanProjectApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<LeanProjectApi>())
    }
}

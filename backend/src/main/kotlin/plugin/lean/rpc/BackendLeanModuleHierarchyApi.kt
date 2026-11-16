package plugin.lean.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import plugin.lean.navigation.LeanModuleHierarchyService
import java.io.IOException

internal class BackendLeanModuleHierarchyApi : LeanModuleHierarchyApi {
    private fun service(projectId: ProjectId): LeanModuleHierarchyService =
        checkNotNull(projectId.findProjectOrNull()) { "The Lean project has been closed" }.service()

    override suspend fun prepare(projectId: ProjectId, fileId: VirtualFileId) = leanRpcResult {
        val file = fileId.virtualFile() ?: throw IOException("The Lean file is no longer available.")
        service(projectId).prepare(file)
    }

    override suspend fun children(
        projectId: ProjectId,
        serverId: String,
        module: LeanModule,
        direction: LeanModuleHierarchyDirection
    ) = leanRpcResult { service(projectId).children(serverId, module, direction) }

    override suspend fun navigate(projectId: ProjectId, serverId: String, module: LeanModule) =
        leanRpcResult { service(projectId).navigate(serverId, module) }
}

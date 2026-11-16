package plugin.lean.rpc

import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor
import plugin.lean.toolchain.LeanToolchainService
import java.io.IOException

internal class BackendLeanToolchainApi : LeanToolchainApi {
    private fun service(projectId: ProjectId): LeanToolchainService =
        (projectId.findProjectOrNull() ?: throw IOException("The Lean project has been closed.")).service()

    override suspend fun configuration(projectId: ProjectId) = service(projectId).configuration
    override suspend fun inspect(projectId: ProjectId, configuration: LeanConfiguration) =
        service(projectId).inspect(configuration)

    override suspend fun apply(
        projectId: ProjectId, expected: LeanConfiguration, configuration: LeanConfiguration
    ) = service(projectId).apply(expected, configuration)

    override suspend fun install(
        projectId: ProjectId, configuration: LeanConfiguration, toolchain: String
    ) = leanRpcResult {
        service(projectId).install(configuration, toolchain)
    }

    override suspend fun restart(projectId: ProjectId) = leanRpcResult { service(projectId).restart() }
}

internal class BackendLeanApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<LeanToolchainApi>()) { BackendLeanToolchainApi() }
        remoteApi(remoteApiDescriptor<LeanInfoviewApi>()) { BackendLeanInfoviewApi() }
        remoteApi(remoteApiDescriptor<LeanModuleHierarchyApi>()) { BackendLeanModuleHierarchyApi() }
        remoteApi(remoteApiDescriptor<LeanFileApi>()) { BackendLeanFileApi() }
        remoteApi(remoteApiDescriptor<LeanProjectApi>()) { BackendLeanProjectApi() }
    }
}

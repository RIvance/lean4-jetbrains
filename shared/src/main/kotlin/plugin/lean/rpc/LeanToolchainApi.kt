package plugin.lean.rpc

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable
import plugin.lean.tooling.ElanSelectionKind

@Serializable
data class LeanConfiguration(
    val elanExecutable: String = "",
    val toolchain: String = "",
    val enabled: Boolean = true,
    val serverArguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val enhancedSemanticHighlighting: Boolean = true
) {
    /** Retained configurations must not share mutable collections with an editor or saved state. */
    fun snapshot(): LeanConfiguration = copy(
        serverArguments = java.util.List.copyOf(serverArguments),
        environment = java.util.Map.copyOf(environment)
    )
}

@Serializable
sealed interface LeanConfigurationUpdate {
    @Serializable
    data class Applied(val configuration: LeanConfiguration) : LeanConfigurationUpdate

    @Serializable
    data class Conflict(val current: LeanConfiguration) : LeanConfigurationUpdate

    @Serializable
    data class Failed(val message: String) : LeanConfigurationUpdate
}

@Serializable
data class InstalledToolchainInfo(val name: String, val path: String)

@Serializable
data class ToolchainSelectionOrigin(val kind: ElanSelectionKind, val location: String? = null)

@Serializable
sealed interface ActiveToolchainInfo {
    @Serializable
    data class Available(val toolchain: InstalledToolchainInfo, val cached: Boolean) : ActiveToolchainInfo

    @Serializable
    data class Unavailable(val message: String, val installTarget: String? = null) : ActiveToolchainInfo
}

@Serializable
sealed interface ToolchainInspection {
    @Serializable
    data class Available(
        val executable: String,
        val elanVersion: String,
        val directory: String,
        val installed: List<InstalledToolchainInfo>,
        val active: ActiveToolchainInfo,
        val origin: ToolchainSelectionOrigin? = null
    ) : ToolchainInspection

    @Serializable
    data class Failed(val message: String) : ToolchainInspection
}

/** All paths and processes belong to the backend machine, including in remote development. */
@Rpc
interface LeanToolchainApi : RemoteApi<Unit> {
    suspend fun configuration(projectId: ProjectId): LeanConfiguration
    suspend fun inspect(projectId: ProjectId, configuration: LeanConfiguration): ToolchainInspection
    /** Validate and replace the expected saved snapshot, then restart the affected servers. */
    suspend fun apply(
        projectId: ProjectId, expected: LeanConfiguration, configuration: LeanConfiguration
    ): LeanConfigurationUpdate
    suspend fun install(
        projectId: ProjectId, configuration: LeanConfiguration, toolchain: String
    ): LeanRpcResult<Unit>
    /** Schedule all open Lean workspaces to restart using saved settings; startup failures remain visible in LSP. */
    suspend fun restart(projectId: ProjectId): LeanRpcResult<Unit>

    companion object {
        suspend fun getInstance(): LeanToolchainApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<LeanToolchainApi>())
    }
}

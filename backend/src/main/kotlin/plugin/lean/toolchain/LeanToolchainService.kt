package plugin.lean.toolchain

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import plugin.lean.lsp.LeanLspProjectService
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.rpc.LeanConfigurationUpdate
import plugin.lean.rpc.ToolchainInspection
import plugin.lean.settings.LeanSettings
import plugin.lean.tooling.ToolCommand
import java.io.IOException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class LeanToolchainService(private val project: Project) {
    val configuration: LeanConfiguration get() = project.service<LeanSettings>().configuration

    /** Blocking resolution boundary, shared by language servers and user-launched tool commands. */
    fun command(
        directory: Path,
        arguments: List<String>,
        configuration: LeanConfiguration = this.configuration,
        environment: Map<String, String> = LeanToolchainHost.environmentFor(configuration)
    ): ToolCommand = LeanToolchainHost.command(directory, arguments, configuration, environment)

    suspend fun inspect(configuration: LeanConfiguration): ToolchainInspection = try {
        LeanToolchainHost.inspect(projectDirectory(), configuration)
    } catch (error: IOException) {
        ToolchainInspection.Failed(error.message ?: "Could not inspect elan")
    }

    suspend fun apply(expected: LeanConfiguration, configuration: LeanConfiguration): LeanConfigurationUpdate {
        val settings = project.service<LeanSettings>()
        if (settings.configuration != expected) return LeanConfigurationUpdate.Conflict(settings.configuration)
        val normalized = try {
            val normalized = LeanToolchainHost.normalize(configuration)
            if (normalized.enabled) {
                runInterruptible(Dispatchers.IO) {
                    if (normalized.toolchain.isNotEmpty()) {
                        LeanToolchainHost.resolve(projectDirectory(), normalized)
                    } else LeanToolchainHost.discover(normalized)
                }
            }
            normalized
        } catch (error: IOException) {
            return LeanConfigurationUpdate.Failed(error.message ?: "Could not validate the Lean configuration.")
        }
        currentCoroutineContext().ensureActive()
        if (!settings.compareAndSet(expected, normalized)) {
            return LeanConfigurationUpdate.Conflict(settings.configuration)
        }
        restart()
        return LeanConfigurationUpdate.Applied(normalized)
    }

    suspend fun install(configuration: LeanConfiguration, toolchain: String) {
        LeanToolchainHost.install(projectDirectory(), configuration, toolchain)
    }

    fun restart() {
        project.service<LeanLspProjectService>().restart()
    }

    private fun projectDirectory(): Path = Path.of(
        project.basePath ?: throw IOException("Open a project directory to configure Lean.")
    )
}

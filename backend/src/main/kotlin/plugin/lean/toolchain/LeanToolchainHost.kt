package plugin.lean.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import plugin.lean.rpc.ActiveToolchainInfo
import plugin.lean.rpc.InstalledToolchainInfo
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.rpc.ToolchainInspection
import plugin.lean.tooling.ElanInstallation
import plugin.lean.tooling.ElanNotFoundException
import plugin.lean.tooling.ElanState
import plugin.lean.tooling.ResolutionSource
import plugin.lean.tooling.ToolCommand
import plugin.lean.tooling.ToolchainUnavailableException
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Optional
import java.util.TreeSet

/** Host-side discovery and execution, usable before a project exists as well as by project services. */
internal object LeanToolchainHost {
    fun normalize(configuration: LeanConfiguration): LeanConfiguration {
        if (
            '\u0000' in configuration.elanExecutable || '\u0000' in configuration.toolchain ||
            configuration.serverArguments.any { '\u0000' in it }
        ) {
            throw IOException("Elan paths, toolchain names, and server arguments cannot contain NUL characters.")
        }
        val ordering = if (SystemInfo.isWindows) String.CASE_INSENSITIVE_ORDER else Comparator.naturalOrder<String>()
        val names = TreeSet(ordering)
        for ((name, value) in configuration.environment) {
            if (!EnvironmentUtil.isValidName(name)) throw IOException("An environment variable has an invalid name.")
            if (!EnvironmentUtil.isValidValue(value)) {
                throw IOException("Environment variable $name cannot contain NUL characters.")
            }
            if (!names.add(name)) throw IOException("Environment variable $name is configured more than once.")
        }
        return configuration.copy(
            elanExecutable = configuration.elanExecutable.trim(), toolchain = configuration.toolchain.trim()
        ).snapshot()
    }

    /** Use the host platform's environment rules, with project values below per-run overrides. */
    fun environmentFor(
        configuration: LeanConfiguration,
        parent: GeneralCommandLine.ParentEnvironmentType = GeneralCommandLine.ParentEnvironmentType.CONSOLE,
        overrides: Map<String, String> = emptyMap()
    ): Map<String, String> = GeneralCommandLine()
        .withParentEnvironmentType(parent)
        .withEnvironment(configuration.environment)
        .withEnvironment(overrides)
        .effectiveEnvironment

    fun command(
        directory: Path,
        arguments: List<String>,
        configuration: LeanConfiguration,
        environment: Map<String, String> = environmentFor(configuration)
    ): ToolCommand {
        val installation = discover(configuration, environment)
        val state = inspectState(installation, directory, configuration)
        return installation.run(directory, state.selectedToolchain().toolchain().name(), arguments)
    }

    suspend fun inspect(directory: Path, configuration: LeanConfiguration): ToolchainInspection =
        runInterruptible(Dispatchers.IO) {
            try {
                val normalized = normalize(configuration)
                val installation = discover(normalized)
                val state = inspectState(installation, directory, normalized)
                val active = try {
                    val selected = state.selectedToolchain()
                    val toolchain = selected.toolchain()
                    ActiveToolchainInfo.Available(
                        InstalledToolchainInfo(toolchain.name(), toolchain.path().toString()),
                        selected.source() == ResolutionSource.CACHE
                    )
                } catch (error: ToolchainUnavailableException) {
                    ActiveToolchainInfo.Unavailable(error.message ?: "No active Lean toolchain")
                }
                ToolchainInspection.Available(
                    installation.executable().toString(), state.version(), directory.toString(),
                    state.installedToolchains().map { InstalledToolchainInfo(it.name(), it.path().toString()) }, active
                )
            } catch (error: IOException) {
                ToolchainInspection.Failed(error.message ?: "Could not inspect elan")
            } catch (error: InvalidPathException) {
                ToolchainInspection.Failed("Invalid elan path: ${error.input}")
            }
        }

    suspend fun install(directory: Path, configuration: LeanConfiguration, toolchain: String) {
        runInterruptible(Dispatchers.IO) {
            discover(normalize(configuration)).install(directory, toolchain.trim()).execute(INSTALL_TIMEOUT)
        }
    }

    fun discover(
        configuration: LeanConfiguration, environment: Map<String, String> = environmentFor(configuration)
    ): ElanInstallation = try {
        ElanInstallation.discover(
            environment, Path.of(System.getProperty("user.home")),
            Optional.ofNullable(configuration.elanExecutable.takeIf { it.isNotBlank() }?.let(Path::of)),
            SystemInfo.isWindows
        )
    } catch (error: ElanNotFoundException) {
        val locations = error.searchedPaths().joinToString("\n") { it.toString() }
        throw IOException(
            "Cannot find an executable elan at the checked locations:\n$locations\n\n" +
            "Check the configured elan executable, ELAN_HOME, or PATH.",
            error
        )
    } catch (error: InvalidPathException) {
        throw IOException("Invalid elan path: ${error.input}", error)
    }

    fun inspectState(
        installation: ElanInstallation,
        directory: Path,
        configuration: LeanConfiguration
    ): ElanState {
        val selection = Optional.ofNullable(configuration.toolchain.takeIf { it.isNotBlank() })
        return ElanState.parse(installation.inspect(directory, selection).execute(INSPECT_TIMEOUT))
    }

    private const val INSPECT_TIMEOUT = 15_000
    private const val INSTALL_TIMEOUT = 15 * 60_000
}

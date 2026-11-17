package plugin.lean.highlighting

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import plugin.lean.lsp.LeanLspProjectService
import plugin.lean.toolchain.commandLine
import plugin.lean.toolchain.execute
import plugin.lean.tooling.ToolCommand
import plugin.lean.tooling.InstalledToolchain
import plugin.lean.tooling.highlighting.HighlightingBuild
import plugin.lean.tooling.highlighting.HighlightingCompiler
import plugin.lean.tooling.highlighting.HighlightingFailureDetector
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Optional server augmentation. Quarantined build identities live only for this project's lifetime. */
@Service(Service.Level.PROJECT)
internal class LeanEnhancedHighlighting(private val project: Project, private val scope: CoroutineScope) {
    private val disabled = ConcurrentHashMap.newKeySet<String>()
    private val compiler by lazy {
        val archive = checkNotNull(javaClass.getResourceAsStream("/lean/semantic-highlighting.zip")) {
            "Bundled semantic highlighting sources are missing"
        }.use { it.readBytes() }
        HighlightingCompiler(
            Path.of(PathManager.getSystemPath(), "lean", "semantic-highlighting"), archive,
            { command, timeout -> command.execute(timeout) },
            { ProgressManager.checkCanceled() }, com.intellij.openapi.util.SystemInfo.isWindows
        )
    }

    fun session(): HighlightingSession = HighlightingSession(
        { compiler }, disabled,
        { command ->
            val line = command.commandLine()
            StartedLeanProcess(line.createProcess(), line.commandLineString)
        },
        { message, error -> logger<LeanEnhancedHighlighting>().warn(message, error) },
        { scope.launch { project.service<LeanLspProjectService>().restart() } }
    )
}

internal data class StartedLeanProcess(val process: Process, val command: String)

/** One process generation. The host owns process creation, reporting, project-wide quarantine, and restart. */
internal class HighlightingSession(
    private val compiler: () -> HighlightingCompiler,
    private val disabled: MutableSet<String>,
    private val startProcess: (ToolCommand) -> StartedLeanProcess,
    private val report: (String, Throwable?) -> Unit,
    private val restart: () -> Unit
) {
    private val failures = HighlightingFailureDetector()
    @Volatile private var active: HighlightingBuild? = null

    fun start(toolchain: InstalledToolchain, plain: ToolCommand, enabled: Boolean): StartedLeanProcess {
        var build: HighlightingBuild? = null
        if (enabled) {
            try {
                build = compiler().prepare(toolchain, plain.environmentMap()).orElse(null)
                if (build != null && build.identity() !in disabled) {
                    val library = build.library()
                    val command = ToolCommand(
                        plain.executable(), plain.argumentsList() + "--plugin=$library",
                        plain.directory(), plain.environmentMap()
                    )
                    val started = startProcess(command)
                    active = build
                    return started
                }
            } catch (error: Exception) {
                propagateCancellation(error)
                if (build != null) disabled.add(build.identity())
                report(
                    "Richer semantic highlighting is unavailable; starting the standard Lean server", error
                )
            }
        }
        return startProcess(plain)
    }

    fun sent(bytes: ByteArray) = observe { failures.sent(bytes) }

    fun received(bytes: ByteArray) = observe {
        failures.received(bytes).ifPresent {
            disable("Augmented Lean server request failed (${it.code()}): ${it.message()}")
        }
    }

    fun stopped(shutdownNormally: Boolean) {
        if (shutdownNormally) active = null
        else disable("The augmented Lean server stopped unexpectedly")
    }

    private fun observe(action: () -> Unit) {
        if (active == null) return
        try {
            action()
        } catch (error: Exception) {
            propagateCancellation(error)
            disable("Invalid response from the augmented Lean server", error)
        }
    }

    private fun disable(reason: String, error: Throwable? = null) {
        val build = active ?: return
        active = null
        if (!disabled.add(build.identity())) return
        report("$reason; restarting Lean with standard highlighting", error)
        restart()
    }

    private fun propagateCancellation(error: Exception) {
        when (error) {
            is CancellationException, is ProcessCanceledException -> throw error
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw ProcessCanceledException(error)
            }
        }
        ProgressManager.checkCanceled()
        if (Thread.currentThread().isInterrupted) throw ProcessCanceledException(error)
    }
}

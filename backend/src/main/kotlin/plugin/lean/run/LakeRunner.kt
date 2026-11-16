package plugin.lean.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Disposer
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.toolchain.LeanToolchainHost
import plugin.lean.toolchain.LeanToolchainService
import plugin.lean.toolchain.commandLine
import java.io.IOException
import java.nio.file.Path
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/** A run owns a configuration snapshot; toolchain discovery and process startup happen off the UI thread. */
internal class LakeRunState(
    private val configuration: LakeRunConfiguration,
    private val toolchainConfiguration: LeanConfiguration,
    environment: ExecutionEnvironment
) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
        val parameters = configuration.createParameters()
        val directory = parameters.workingDirectory ?: throw ExecutionException("Choose a working directory for Lake.")
        val parent = if (parameters.isPassParentEnvs) {
            GeneralCommandLine.ParentEnvironmentType.CONSOLE
        } else {
            GeneralCommandLine.ParentEnvironmentType.NONE
        }
        val variables = LeanToolchainHost.environmentFor(toolchainConfiguration, parent, parameters.env)
        val arguments = listOf("lake") + parameters.programParametersList.list
        val command = try {
            configuration.project.service<LeanToolchainService>().command(
                Path.of(directory), arguments, toolchainConfiguration, variables
            )
        } catch (error: IOException) {
            throw ExecutionException(error.message ?: "Could not resolve the Lean toolchain for Lake.", error)
        }
        ProgressManager.checkCanceled()
        addConsoleFilters(LeanConsoleFilter(configuration.project, command.directory()))
        return KillableColoredProcessHandler(command.commandLine()).also { ProcessTerminatedListener.attach(it) }
    }
}

class LakeProgramRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "LeanLakeRunner"
    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultRunExecutor.EXECUTOR_ID && profile is LakeRunConfiguration

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        FileDocumentManager.getInstance().saveAllDocuments()
        val promise = AsyncPromise<RunContentDescriptor?>()
        ProgressManager.getInstance().run(object : Task.Backgroundable(environment.project, "Starting Lake", true) {
            private var result: ExecutionResult? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Resolving the Lean toolchain and starting Lake"
                result = state.execute(environment.executor, this@LakeProgramRunner)
            }

            override fun onSuccess() {
                try {
                    promise.setResult(showRunContent(result, environment))
                    result = null
                } catch (error: Throwable) {
                    release()
                    promise.setError(error)
                }
            }

            override fun onThrowable(error: Throwable) {
                release()
                promise.setError(error)
            }

            override fun onCancel() {
                release()
                promise.cancel()
            }

            private fun release() {
                result?.processHandler?.destroyProcess()
                result?.executionConsole?.let(Disposer::dispose)
                result = null
            }
        })
        return promise
    }
}

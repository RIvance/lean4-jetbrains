package plugin.lean.toolchain

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.util.Key
import plugin.lean.tooling.ToolCommand
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun ToolCommand.commandLine(): GeneralCommandLine = GeneralCommandLine()
    .withExePath(executable().toString())
    .withParameters(argumentsList())
    .withWorkDirectory(directory().toFile())
    .withEnvironment(environmentMap())
    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
    .withCharset(Charsets.UTF_8)

internal class ToolProcessException(
    val command: ToolCommand,
    val exitCode: Int?,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/** Indicator cancellation or thread interruption stops the child before this blocking operation returns. */
internal fun ToolCommand.execute(timeoutMillis: Int, onOutput: (String) -> Unit = {}): String {
    ProgressManager.checkCanceled()
    val handler = try {
        OSProcessHandler(commandLine())
    } catch (error: ExecutionException) {
        throw ToolProcessException(this, null, "Could not start elan: ${error.message}", error)
    }
    val output = ProcessOutput()
    val completed = CompletableFuture<ProcessOutput>()
    handler.setShouldDestroyProcessRecursively(true)
    handler.addProcessListener(object : CapturingProcessAdapter(output) {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            super.onTextAvailable(event, outputType)
            if (outputType == ProcessOutputTypes.STDOUT || outputType == ProcessOutputTypes.STDERR) onOutput(event.text)
        }

        override fun processTerminated(event: ProcessEvent) {
            super.processTerminated(event)
            completed.complete(output)
        }
    })
    try {
        handler.startNotify()
        val started = System.nanoTime()
        ProgressIndicatorUtils.awaitWithCheckCanceled {
            try {
                completed.get(100, TimeUnit.MILLISECONDS)
                true
            } catch (error: TimeoutException) {
                timeoutMillis > 0 && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= timeoutMillis
            }
        }
        ProgressManager.checkCanceled()
        if (Thread.currentThread().isInterrupted) throw ProcessCanceledException()
        if (!completed.isDone) throw ToolProcessException(this, null, "elan did not finish within the time limit.")
        if (output.exitCode != 0) {
            val detail = output.stderr.trim().ifEmpty { output.stdout.trim() }
            throw ToolProcessException(this, output.exitCode, "elan failed (${output.exitCode}): $detail")
        }
        return output.stdout
    } finally {
        if (!completed.isDone) {
            handler.destroyProcess()
            // Project-generation cleanup must not race a child that can still write into its staging directory.
            completed.join()
        }
    }
}

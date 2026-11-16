package plugin.lean.toolchain

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PathUtil
import plugin.lean.tooling.ToolCommand
import plugin.lean.tooling.project.ProjectGeneration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ToolProcessesTest : BasePlatformTestCase() {
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("lean-process-test-")
    }

    override fun tearDown() {
        try {
            FileUtil.delete(directory.toFile())
        } finally {
            super.tearDown()
        }
    }

    fun testInterruptedGenerationCannotPublishPartialFiles() {
        assertCancelledGeneration(null) { it.interrupt() }
    }

    fun testIndicatorCancellationCannotPublishPartialFiles() {
        val indicator = EmptyProgressIndicator()
        assertCancelledGeneration(indicator) { indicator.cancel() }
    }

    fun testSuccessfulExitRetainsOutput() {
        val command = command("0")
        val output = CompletableFuture.supplyAsync { command.execute(10_000) }.get(15, TimeUnit.SECONDS)
        assertEquals("ready\nα output\n", output.replace("\r\n", "\n"))
    }

    fun testNonzeroExitRetainsDiagnostics() {
        val command = command("7")
        val failure = CompletableFuture.supplyAsync {
            try {
                command.execute(10_000)
                null
            } catch (error: ToolProcessException) {
                error
            }
        }.get(15, TimeUnit.SECONDS)
        assertNotNull(failure)
        assertEquals(7, failure!!.exitCode)
        assertTrue(failure.message!!.contains("process diagnostic"))
    }

    fun testTimeoutStopsTheUnfinishedProcess() {
        val command = command("wait")
        val ready = CountDownLatch(1)
        val completed = CompletableFuture.supplyAsync {
            try {
                command.execute(2_000) { ready.countDown() }
                null
            } catch (error: ToolProcessException) {
                error
            }
        }
        assertTrue("The child process did not start", ready.await(10, TimeUnit.SECONDS))
        val child = ProcessHandle.of(Files.readString(directory.resolve("process.pid")).toLong()).orElseThrow()
        try {
            val failure = completed.get(10, TimeUnit.SECONDS)
            assertNotNull(failure)
            assertNull(failure!!.exitCode)
            assertFalse(child.isAlive)
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    private fun assertCancelledGeneration(indicator: ProgressIndicator?, cancel: (Thread) -> Unit) {
        val command = command("wait")
        val target = command.directory().resolve("project")
        val ready = CountDownLatch(1)
        val completed = CompletableFuture<Throwable?>()
        val generation = Runnable {
            ProjectGeneration.generate(target) { staging ->
                Files.writeString(staging.resolve("generated.txt"), "unfinished output")
                command.inDirectory(staging).execute(0) { ready.countDown() }
            }
        }
        val worker = Thread({
            completed.complete(try {
                if (indicator == null) generation.run()
                else ProgressManager.getInstance().runProcess(generation, indicator)
                null
            } catch (error: Throwable) {
                error
            })
        }, "Lean process cancellation check")
        var child: ProcessHandle? = null
        worker.start()
        try {
            assertTrue("The child process did not start", ready.await(10, TimeUnit.SECONDS))
            val pid = Files.readString(command.directory().resolve("process.pid")).toLong()
            child = ProcessHandle.of(pid).orElseThrow()
            cancel(worker)
            val failure = try {
                completed.get(10, TimeUnit.SECONDS)
            } catch (error: TimeoutException) {
                throw AssertionError("Process worker did not stop:\n" + worker.stackTrace.joinToString("\n"), error)
            }
            assertTrue(failure is ProcessCanceledException)
            child.onExit().get(10, TimeUnit.SECONDS)
            assertFalse(Files.exists(target))
            Files.list(command.directory()).use { files ->
                assertEquals(listOf("process.pid"), files.map { it.fileName.toString() }.toList())
            }
        } finally {
            indicator?.cancel()
            worker.interrupt()
            child?.takeIf { it.isAlive }?.destroyForcibly()
            worker.join(10_000)
        }
    }

    private fun command(mode: String): ToolCommand {
        val executableName = if (SystemInfo.isWindows) "java.exe" else "java"
        val executable = Path.of(System.getProperty("java.home"), "bin", executableName)
        val classes = PathUtil.getJarPathForClass(ToolProcessFixture::class.java)
        val arguments = listOf(
            "-cp", classes, ToolProcessFixture::class.java.name,
            directory.resolve("process.pid").toString(), mode
        )
        return ToolCommand(executable, arguments, directory, emptyMap())
    }
}

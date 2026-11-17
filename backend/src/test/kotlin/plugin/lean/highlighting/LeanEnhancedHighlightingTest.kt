package plugin.lean.highlighting

import com.google.gson.Gson
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertThrows
import plugin.lean.tooling.InstalledToolchain
import plugin.lean.tooling.ToolCommand
import plugin.lean.tooling.highlighting.HighlightingCompiler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class LeanEnhancedHighlightingTest : BasePlatformTestCase() {
    private lateinit var temporary: Path
    private lateinit var toolchain: InstalledToolchain
    private lateinit var plain: ToolCommand
    private lateinit var compiler: HighlightingCompiler
    private val disabled = ConcurrentHashMap.newKeySet<String>()
    private val starts = mutableListOf<ToolCommand>()
    private val executed = mutableListOf<ToolCommand>()
    private var version = "4.35.0"
    private var commit = "original"
    private var buildError: Exception? = null
    private var loadError: Exception? = null
    private var startError: Exception? = null
    private var buildCount = 0
    private var restarts = 0

    override fun setUp() {
        super.setUp()
        temporary = Files.createTempDirectory("highlighting with spaces ")
        val home = Files.createDirectories(temporary.resolve("toolchain/bin")).parent
        val suffix = if (SystemInfo.isWindows) ".exe" else ""
        Files.writeString(home.resolve("bin/lean$suffix"), "toolchain executable")
        Files.writeString(home.resolve("bin/lake$suffix"), "toolchain executable")
        toolchain = InstalledToolchain("linked-toolchain", home)
        plain = ToolCommand(
            temporary.resolve("elan"), listOf("run", "--", toolchain.name(), "lake", "serve", "--", "project"),
            temporary, mapOf("LEAN_PATH" to "project libraries", "LEAN_CC" to "project compiler", "SAMPLE" to "kept")
        )
        val archive = checkNotNull(javaClass.getResourceAsStream("/lean/semantic-highlighting.zip"))
            .use { it.readBytes() }
        compiler = HighlightingCompiler(
            temporary.resolve("cache"), archive, { command, _ -> execute(command) }, {}, SystemInfo.isWindows
        )
    }

    override fun tearDown() {
        try {
            FileUtil.delete(temporary.toFile())
        } finally {
            super.tearDown()
        }
    }

    private fun execute(command: ToolCommand): String {
        executed.add(command)
        val arguments = command.argumentsList()
        val library = command.directory().resolve(".lake/build/lib/native library")
        return when {
            arguments.first() == "build" -> {
                buildCount++
                buildError?.let { throw it }
                Files.createDirectories(library.parent)
                Files.writeString(library, "native library")
                ""
            }
            arguments == listOf("run", "plugin-path") -> Gson().toJson(library.toString())
            else -> {
                if (arguments.any { it.startsWith("--plugin=") }) loadError?.let { throw it }
                Gson().toJson(mapOf("version" to version, "githash" to commit))
            }
        }
    }

    private fun session() = HighlightingSession(
        { compiler }, disabled,
        { command ->
            starts.add(command)
            if (command.argumentsList().any { it.startsWith("--plugin=") }) startError?.let { throw it }
            StartedLeanProcess(object : Process() {
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() = Unit
            }, "Lean")
        },
        { _, _ -> }, { restarts++ }
    )

    fun testOnlyListedStableReleasesHaveBuildPlansIncludingLinkedToolchains() {
        for (release in listOf("4.24.0", "4.25.0", "4.25.1", "4.25.2", "4.26.0", "4.28.0", "4.34.1", "4.35.0")) {
            version = release
            assertTrue(compiler.prepare(toolchain, plain.environmentMap()).isPresent)
        }
        for (release in listOf("4.23.0", "4.25.0-rc2", "4.30.0", "4.35.0-pre", "4.36.0")) {
            version = release
            session().start(toolchain, plain, true)
            assertSame(plain, starts.last())
        }
        assertEquals(0, buildCount)
    }

    fun testDisabledFeatureDoesNotInspectOrBuildTheToolchain() {
        session().start(toolchain, plain, false)
        assertTrue(executed.isEmpty())
        assertSame(plain, starts.single())
    }

    fun testCacheIsReusedAndBuildEnvironmentDoesNotChangeTheServerEnvironment() {
        repeat(2) { session().start(toolchain, plain, true) }
        assertEquals(1, buildCount)
        assertTrue(executed.all { "LEAN_PATH" !in it.environmentMap() && "LEAN_CC" !in it.environmentMap() })
        assertTrue(executed.all { it.environmentMap()["SAMPLE"] == "kept" })
        for (command in starts) {
            assertEquals(plain.environmentMap(), command.environmentMap())
            assertEquals(plain.argumentsList(), command.argumentsList().dropLast(1))
            assertTrue(command.argumentsList().last().startsWith("--plugin="))
        }
        commit = "replaced toolchain"
        session().start(toolchain, plain, true)
        assertEquals(2, buildCount)
    }

    fun testBuildAndLoadFailuresStartThePlainServerAndDoNotRetryTheFailedBuild() {
        for (failure in listOf("build", "load")) {
            commit = failure
            buildError = if (failure == "build") IOException("compiler failed") else null
            loadError = if (failure == "load") IOException("library failed to load") else null
            val before = buildCount
            repeat(2) {
                session().start(toolchain, plain, true)
                assertSame(plain, starts.last())
            }
            assertEquals(before + 1, buildCount)
        }
        assertEquals(0, restarts)
    }

    fun testAugmentedProcessCreationFailureRetriesTheUnmodifiedCommand() {
        startError = IOException("process could not start")
        session().start(toolchain, plain, true)
        assertEquals(2, starts.size)
        assertSame(plain, starts.last())
        session().start(toolchain, plain, true)
        assertSame(plain, starts.last())
        assertEquals(3, starts.size)
    }

    fun testUnexpectedExitQuarantinesOnlyThatBuildAndRestartsOnce() {
        val session = session()
        session.start(toolchain, plain, true)
        repeat(2) { session.stopped(false) }
        assertEquals(1, restarts)
        session().start(toolchain, plain, true)
        assertSame(plain, starts.last())
        commit = "another toolchain"
        session().start(toolchain, plain, true)
        assertTrue(starts.last().argumentsList().last().startsWith("--plugin="))
    }

    fun testSemanticFailureRestartsButDiagnosticsAndCancellationDoNot() {
        val session = session()
        session.start(toolchain, plain, true)
        session.received("""
            {"method":"textDocument/publishDiagnostics","params":{"diagnostics":[{"severity":1}]}}
        """.trimIndent().toByteArray())
        for (code in listOf(-32800, -32801, -32802)) {
            session.sent("""{"id":1,"method":"textDocument/semanticTokens/full"}""".toByteArray())
            session.received("""{"id":1,"error":{"code":$code,"message":"cancelled"}}""".toByteArray())
        }
        assertEquals(0, restarts)
        session.sent("""{"id":2,"method":"textDocument/semanticTokens/range"}""".toByteArray())
        // A server-initiated request can legitimately use the same ID as our pending request.
        session.received("""{"id":2,"method":"workspace/semanticTokens/refresh"}""".toByteArray())
        session.received("""{"id":2,"error":{"code":-32603,"message":"classification failed"}}""".toByteArray())
        assertEquals(1, restarts)
        session().start(toolchain, plain, true)
        assertSame(plain, starts.last())
    }

    fun testNormalShutdownAndExplicitRequestCancellationDoNotTriggerFallback() {
        val session = session()
        session.start(toolchain, plain, true)
        session.sent("""{"id":1,"method":"textDocument/semanticTokens/full"}""".toByteArray())
        session.sent("""{"method":"$/cancelRequest","params":{"id":1}}""".toByteArray())
        session.received("""{"id":1,"error":{"code":-32603,"message":"too late"}}""".toByteArray())
        session.sent("""{"id":2,"method":"textDocument/semanticTokens/full"}""".toByteArray())
        session.sent("""{"id":3,"method":"shutdown"}""".toByteArray())
        session.received("""{"id":2,"error":{"code":-32603,"message":"shutting down"}}""".toByteArray())
        session.stopped(true)
        assertEquals(0, restarts)
        assertTrue(disabled.isEmpty())
    }

    fun testWorkerCrashesReportedOnOtherRequestsAlsoTriggerFallback() {
        for (code in listOf(-32901, -32902)) {
            commit = "worker failure $code"
            val session = session()
            session.start(toolchain, plain, true)
            session.received("""{"id":42,"error":{"code":$code,"message":"worker failed"}}""".toByteArray())
        }
        assertEquals(2, restarts)
    }

    fun testCancellationPropagatesWithoutStartingAnotherProcess() {
        buildError = ProcessCanceledException()
        assertThrows(ProcessCanceledException::class.java) { session().start(toolchain, plain, true) }
        assertTrue(starts.isEmpty())
        assertTrue(disabled.isEmpty())
    }
}

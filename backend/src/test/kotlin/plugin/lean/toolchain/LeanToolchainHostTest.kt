package plugin.lean.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.EnvironmentUtil
import org.junit.Assert.assertThrows
import plugin.lean.rpc.LeanConfiguration
import java.io.IOException
import java.nio.file.Files

class LeanToolchainHostTest : BasePlatformTestCase() {
    fun testProjectEnvironmentAndRunOverridesUseTheHostPlatformRules() {
        val configuration = LeanConfiguration(environment = mapOf(
            "LEAN_TEST_VALUE" to "project", "LEAN_TEST_EMPTY" to "", "PATH" to "project path"
        ))
        val environment = LeanToolchainHost.environmentFor(
            configuration, GeneralCommandLine.ParentEnvironmentType.NONE,
            mapOf(
                "LEAN_TEST_VALUE" to "run", "LEAN_TEST_LITERAL" to "\$VALUE\$; a path with spaces", "Path" to "run path"
            )
        )
        assertEquals("run", environment["LEAN_TEST_VALUE"])
        assertEquals("", environment["LEAN_TEST_EMPTY"])
        assertEquals("\$VALUE\$; a path with spaces", environment["LEAN_TEST_LITERAL"])
        assertEquals(if (SystemInfo.isWindows) "run path" else "project path", environment["PATH"])
        assertEquals("run path", environment["Path"])
        assertEquals("project", configuration.environment["LEAN_TEST_VALUE"])

        val parent = EnvironmentUtil.getEnvironmentMap()
        val inherited = LeanToolchainHost.environmentFor(LeanConfiguration())
        assertTrue("Host variables should be inherited", parent.all { (key, value) -> inherited[key] == value })
    }

    fun testNormalizationPreservesLiteralOptionsAndRejectsInvalidProcessValues() {
        val arguments = listOf("-j", "2", "  literal  ", "")
        val configuration = LeanConfiguration(
            elanExecutable = "  /configured/elan  ", toolchain = " linked-build ", enabled = false,
            serverArguments = arguments, environment = mapOf("LEAN_SAMPLE" to "  literal value  ")
        )
        val normalized = LeanToolchainHost.normalize(configuration)
        assertEquals("/configured/elan", normalized.elanExecutable)
        assertEquals("linked-build", normalized.toolchain)
        assertEquals(arguments, normalized.serverArguments)
        assertEquals(configuration.environment, normalized.environment)
        for (invalid in listOf(
            configuration.copy(serverArguments = listOf("bad\u0000argument")),
            configuration.copy(environment = mapOf("" to "value")),
            configuration.copy(environment = mapOf("INVALID=NAME" to "value")),
            configuration.copy(environment = mapOf("LEAN_SAMPLE" to "bad\u0000value"))
        )) {
            assertThrows(IOException::class.java) { LeanToolchainHost.normalize(invalid) }
        }
    }

    fun testProjectElanHomeControlsDiscoveryAndThePreparedProcessEnvironment() {
        val directory = Files.createTempDirectory("lean host environment ")
        try {
            Files.createDirectories(directory.resolve("bin"))
            val name = if (SystemInfo.isWindows) "elan.exe" else "elan"
            val executable = Files.createFile(directory.resolve("bin").resolve(name))
            if (!SystemInfo.isWindows) assertTrue(executable.toFile().setExecutable(true))
            val configuration = LeanConfiguration(environment = mapOf(
                "ELAN_HOME" to directory.toString(), "LEAN_SAMPLE" to "project value"
            ))
            val installation = LeanToolchainHost.discover(configuration)
            assertEquals(executable, installation.executable())
            val command = installation.inspect(directory, java.util.Optional.empty())
            assertEquals(directory.toString(), command.environmentMap()["ELAN_HOME"])
            assertEquals("project value", command.environmentMap()["LEAN_SAMPLE"])
        } finally {
            FileUtil.delete(directory.toFile())
        }
    }
}

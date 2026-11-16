package plugin.lean

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.service
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.execution.ParametersListUtil
import org.jdom.Element
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.rpc.LakeProjectTask
import plugin.lean.run.LakeConfigurationProducer
import plugin.lean.run.LakeConfigurationType
import plugin.lean.run.LakeRunConfiguration
import plugin.lean.run.LakeProjectTasks
import plugin.lean.settings.LeanSettings
import plugin.lean.tooling.LeanWorkspace
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException

class LeanLakeRunConfigurationTest : BasePlatformTestCase() {
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        directory = Files.createTempDirectory("lake configuration ")
    }

    override fun tearDown() {
        try {
            FileUtil.delete(directory.toFile())
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(): LakeRunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType(LakeConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration("Lake test", type.factory)
        return settings.configuration as LakeRunConfiguration
    }

    fun testRegistrationDefaultsAndSupportedExecutor() {
        val configuration = configuration()
        assertEquals("build", configuration.programParameters)
        assertTrue(configuration.isPassParentEnvs)
        assertNotNull(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration))
        assertNull(ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration))
    }

    fun testPersistenceAndCloningPreserveIndependentEnvironmentValues() {
        val configuration = configuration()
        configuration.programParameters = "exe app \"one two\""
        configuration.workingDirectory = "\$PROJECT_DIR\$/nested"
        configuration.toolchainOverride = "linked-build"
        configuration.envs = linkedMapOf("LEAN_PATH" to "extra libraries")
        configuration.isPassParentEnvs = false
        configuration.envFilePaths = listOf("\$PROJECT_DIR\$/.env")
        configuration.expandMacros = false
        val stored = Element("configuration")
        configuration.writeExternal(stored)
        val restored = configuration()
        restored.readExternal(stored)
        assertEquals(configuration.programParameters, restored.programParameters)
        assertEquals(configuration.workingDirectory, restored.workingDirectory)
        assertEquals("linked-build", restored.toolchainOverride)
        assertEquals(configuration.envs, restored.envs)
        assertFalse(restored.isPassParentEnvs)
        assertEquals(configuration.envFilePaths, restored.envFilePaths)
        assertFalse(restored.expandMacros)

        val copy = configuration.clone() as LakeRunConfiguration
        copy.envs = linkedMapOf("LEAN_PATH" to "other")
        copy.envFilePaths = emptyList()
        copy.programParameters = "test"
        copy.expandMacros = true
        assertEquals("extra libraries", configuration.envs["LEAN_PATH"])
        assertEquals(listOf("\$PROJECT_DIR\$/.env"), configuration.envFilePaths)
        assertEquals("exe app \"one two\"", configuration.programParameters)
        assertFalse(configuration.expandMacros)
    }

    fun testPlatformArgumentsKeepQuotedEmptyAndShellLikeValuesLiteral() {
        val configuration = configuration()
        configuration.programParameters = "exe app \"one two\" \"\" \"\$(echo literal)\""
        configuration.workingDirectory = directory.toString()
        configuration.envs = linkedMapOf("LEAN_EXAMPLE" to "value with spaces")
        configuration.isPassParentEnvs = false
        val parameters = configuration.createParameters()
        assertEquals(listOf("exe", "app", "one two", "", "\$(echo literal)"), parameters.programParametersList.list)
        assertEquals(configuration.workingDirectory, parameters.workingDirectory)
        assertEquals("value with spaces", parameters.env["LEAN_EXAMPLE"])
        assertFalse(parameters.isPassParentEnvs)
    }

    fun testLiteralRunArgumentsPreserveMacroLikePathsAndNativeEnvironmentSettings() {
        val configuration = configuration()
        val literalDirectory = Files.createDirectory(directory.resolve("\$USER_HOME\$"))
        val environmentFile = Files.writeString(
            directory.resolve("run.env"), "FROM_FILE=file value\nLEAN_SAMPLE=from file\n"
        )
        configuration.expandMacros = false
        configuration.workingDirectory = literalDirectory.toString()
        val arguments = listOf("exe", "cache", "get", "./\$USER_HOME\$/Main.lean", "./\${LEAN_SAMPLE} file.lean", "")
        configuration.programParameters = ParametersListUtil.join(arguments)
        configuration.envs = linkedMapOf("LEAN_SAMPLE" to "run value")
        configuration.envFilePaths = listOf(environmentFile.toString())
        configuration.isPassParentEnvs = false
        configuration.checkConfiguration()
        val literal = configuration.createParameters()
        assertEquals(arguments, literal.programParametersList.list)
        assertEquals(literalDirectory.toString(), literal.workingDirectory)
        assertEquals("file value", literal.env["FROM_FILE"])
        assertEquals("run value", literal.env["LEAN_SAMPLE"])
        assertFalse(literal.isPassParentEnvs)

        val stored = Element("configuration")
        configuration.writeExternal(stored)
        val macros = PathMacroManager.getInstance(project)
        assertFalse(macros.expandPath("\$USER_HOME\$") == "\$USER_HOME\$")
        macros.collapsePaths(stored)
        macros.expandPaths(stored)
        val restored = configuration()
        restored.readExternal(stored)
        assertFalse(restored.expandMacros)
        assertEquals(arguments, restored.createParameters().programParametersList.list)
        assertEquals(literalDirectory.toString(), restored.workingDirectory)

        configuration.expandMacros = true
        configuration.workingDirectory = directory.toString()
        configuration.programParameters = "env lean \"\${LEAN_SAMPLE}\""
        assertEquals(listOf("env", "lean", "run value"), configuration.createParameters().programParametersList.list)
    }

    fun testValidationAllowsOpenEndedLakeCommandsAndRejectsInvalidWorkingDirectories() {
        val configuration = configuration()
        configuration.workingDirectory = directory.toString()
        for (arguments in listOf("build", "--help", "script run custom-script", "env lean --version")) {
            configuration.programParameters = arguments
            configuration.checkConfiguration()
        }
        configuration.programParameters = ""
        assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
        configuration.programParameters = "build"
        configuration.workingDirectory += "/missing"
        assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
    }

    fun testContextConfigurationsUseTheNearestLakeProject() {
        Files.writeString(directory.resolve("lakefile.toml"), "name = \"outer\"")
        val nested = Files.createDirectories(directory.resolve("nested/src")).parent
        Files.writeString(nested.resolve("lakefile.lean"), "import Lake")
        val path = Files.writeString(nested.resolve("src/Main.lean"), "def main : IO Unit := pure ()")
        val virtualFile = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val file = checkNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        val context = ConfigurationContext(file)
        val produced = checkNotNull(LakeConfigurationProducer().createConfigurationFromContext(context))
        val configuration = produced.configurationSettings.configuration as LakeRunConfiguration
        assertEquals(file.virtualFile.parent.parent.path, configuration.workingDirectory)
        assertEquals("build", configuration.programParameters)
        assertTrue(LakeConfigurationProducer().isConfigurationFromContext(configuration, context))
        configuration.programParameters = "test"
        assertFalse(LakeConfigurationProducer().isConfigurationFromContext(configuration, context))
        assertTrue(Files.isDirectory(file.virtualFile.parent.parent.toNioPath()))
    }

    fun testProjectTasksInheritTheLakeTemplateWithoutReplacingSavedConfigurations() {
        val manager = RunManager.getInstance(project)
        val factory = ConfigurationTypeUtil.findConfigurationType(LakeConfigurationType::class.java).factory
        val template = manager.getConfigurationTemplate(factory).configuration as LakeRunConfiguration
        val previousEnvironment = template.envs
        val previousToolchain = template.toolchainOverride
        val saved = manager.createConfiguration("Lake build (${directory.fileName})", factory)
        (saved.configuration as LakeRunConfiguration).programParameters = "exe custom"
        manager.addConfiguration(saved)
        try {
            template.envs = linkedMapOf("LEAN_EXAMPLE" to "template value")
            template.toolchainOverride = "linked-build"
            for (task in LakeProjectTask.entries) {
                val settings = LakeProjectTasks.configuration(project, LeanWorkspace(directory, true), task)
                val configuration = settings.configuration as LakeRunConfiguration
                assertEquals(directory.toString(), configuration.workingDirectory)
                assertEquals(task.arguments, configuration.createParameters().programParametersList.list)
                assertFalse(configuration.expandMacros)
                assertEquals(template.envs, configuration.envs)
                assertEquals("linked-build", configuration.toolchainOverride)
                configuration.checkConfiguration()
                if (task == LakeProjectTask.BUILD) assertFalse(saved.name == settings.name)
                configuration.envs = linkedMapOf()
                assertEquals("template value", template.envs["LEAN_EXAMPLE"])
            }
            assertSame(saved, manager.findConfigurationByName(saved.name))
            assertFalse(saved.isTemporary)
            assertEquals("exe custom", (saved.configuration as LakeRunConfiguration).programParameters)
        } finally {
            template.envs = previousEnvironment
            template.toolchainOverride = previousToolchain
            manager.removeConfiguration(saved)
        }
    }

    fun testFileCacheRunsGroupNearestWorkspacesAndKeepLiteralFileArguments() {
        Files.writeString(directory.resolve("lakefile.toml"), "name = \"outer\"")
        val nested = Files.createDirectories(directory.resolve("first/nested project"))
        val sibling = Files.createDirectories(directory.resolve("second/nested project"))
        Files.writeString(nested.resolve("lakefile.toml"), "name = \"nested\"")
        Files.writeString(sibling.resolve("lakefile.toml"), "name = \"sibling\"")
        val paths = listOf(
            directory.resolve("\$PROJECT_DIR\$ file.lean"), directory.resolve("--scope=sample.lean"),
            nested.resolve("Main.lean"), sibling.resolve("Main.lean")
        ).map { Files.writeString(it, "example : True := by trivial") }
        val files = paths.map { checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)) }
        val manager = RunManager.getInstance(project)
        val savedConfigurations = manager.allSettings.toList()
        val prepared = runWithModalProgressBlocking(project, "Preparing cache runs") {
            LakeProjectTasks.cacheConfigurations(project, files + files.first())
        }
        assertEquals(prepared.size, prepared.map { it.name }.distinct().size)
        val configurations = prepared.map { it.configuration as LakeRunConfiguration }
            .associateBy { Path.of(it.workingDirectory) }
        assertEquals(setOf(directory, nested, sibling), configurations.keys)
        val prefix = listOf("exe", "cache", "get")
        assertEquals(
            prefix + paths.take(2).map { Path.of(".").resolve(directory.relativize(it)).toString() },
            configurations.getValue(directory).createParameters().programParametersList.list
        )
        assertEquals(
            prefix + Path.of(".").resolve("Main.lean").toString(),
            configurations.getValue(nested).createParameters().programParametersList.list
        )
        configurations.values.forEach { it.checkConfiguration() }
        assertEquals(savedConfigurations, manager.allSettings.toList())
    }

    fun testInvalidOrEmptyFileCacheSelectionsNeverBecomeWholeProjectRuns() {
        Files.writeString(directory.resolve("lakefile.toml"), "name = \"outer\"")
        val standalone = Files.createDirectories(directory.resolve("standalone"))
        Files.writeString(standalone.resolve("lean-toolchain"), "linked-build")
        val paths = listOf(
            directory.resolve("Main.lean"), directory.resolve("README.md"), standalone.resolve("Main.lean")
        ).map { Files.writeString(it, "source") }
        val files = paths.map { checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)) }
        val manager = RunManager.getInstance(project)
        val savedConfigurations = manager.allSettings.toList()
        for (selection in listOf(emptyList(), files.take(2), listOf(files[0], files[2]))) {
            assertThrows(IOException::class.java) {
                runWithModalProgressBlocking(project, "Preparing cache runs") {
                    LakeProjectTasks.cacheConfigurations(project, selection)
                }
            }
            assertEquals(savedConfigurations, manager.allSettings.toList())
        }
    }

    fun testProjectActionsResolveFoldersAndFilesWithoutCrossingStandaloneToolchainBoundaries() {
        Files.writeString(directory.resolve("lakefile.toml"), "name = \"outer\"")
        val nested = Files.createDirectories(directory.resolve("nested/src")).parent
        Files.writeString(nested.resolve("lean-toolchain"), "linked-build")
        val path = Files.writeString(nested.resolve("src/README.md"), "A project file")
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        assertNull(LakeProjectTasks.workspace(project, file))
        Files.writeString(nested.resolve("lakefile.toml"), "name = \"nested\"")
        assertEquals(nested, checkNotNull(LakeProjectTasks.workspace(project, file)).root())
        assertEquals(nested, checkNotNull(LakeProjectTasks.workspace(project, file.parent)).root())
    }
}

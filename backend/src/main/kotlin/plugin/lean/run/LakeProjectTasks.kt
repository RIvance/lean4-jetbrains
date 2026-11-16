package plugin.lean.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.text.UniqueNameGenerator
import com.intellij.util.execution.ParametersListUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import plugin.lean.rpc.LakeProjectTask
import plugin.lean.tooling.LeanWorkspace
import java.io.IOException
import java.nio.file.Path

/** Resolves project context and creates native Lake runs, sharing the ordinary run-configuration pipeline. */
object LakeProjectTasks {
    fun workspace(project: Project, file: VirtualFile?): LeanWorkspace? {
        if (file != null && (!file.isValid || file.fileSystem.protocol != StandardFileSystems.FILE_PROTOCOL)) {
            return null
        }
        val directory = when {
            file == null -> project.basePath?.let(Path::of) ?: return null
            file.isDirectory -> file.toNioPath()
            else -> file.parent.toNioPath()
        }
        val projectDirectory = project.basePath?.let(Path::of) ?: directory
        return LeanWorkspace.discoverDirectory(directory, projectDirectory).takeIf { it.usesLake() }
    }

    /** Uses the user's Lake template without replacing or editing any existing configuration. */
    fun configuration(
        project: Project,
        workspace: LeanWorkspace,
        task: LakeProjectTask,
        arguments: List<String> = emptyList(),
        reservedNames: Collection<String> = emptyList()
    ): RunnerAndConfigurationSettings {
        require(workspace.usesLake()) { "A Lake task requires a Lake workspace" }
        val manager = RunManager.getInstance(project)
        val factory = ConfigurationTypeUtil.findConfigurationType(LakeConfigurationType::class.java).factory
        val directory = workspace.root()
        val name = "Lake ${task.arguments.joinToString(" ")} (${directory.fileName ?: directory})"
        val occupiedNames = manager.allSettings.map { it.name } + reservedNames
        val uniqueName = UniqueNameGenerator.generateUniqueName(name, occupiedNames)
        return manager.createConfiguration(uniqueName, factory).apply {
            val lake = configuration as LakeRunConfiguration
            lake.workingDirectory = directory.toString()
            lake.programParameters = ParametersListUtil.join(task.arguments + arguments)
            lake.expandMacros = false
        }
    }

    /** Validate the complete selection before constructing any runs; an empty selection never means the whole cache. */
    suspend fun cacheConfigurations(project: Project, files: List<VirtualFile>): List<RunnerAndConfigurationSettings> {
        val groups = readAction {
            if (files.isEmpty()) throw IOException("Select or open at least one Lean file to fetch its build cache.")
            files.distinct().groupBy { file ->
                if (!file.isValid || file.isDirectory || file.extension != "lean") {
                    throw IOException("Select Lean source files to fetch their build cache.")
                }
                workspace(project, file)
                    ?: throw IOException("${file.presentableUrl} does not belong to a Lake project.")
            }.mapValues { (workspace, files) ->
                files.map { file -> Path.of(".").resolve(workspace.root().relativize(file.toNioPath())).toString() }
            }
        }
        return withContext(Dispatchers.EDT) {
            if (project.isDisposed) throw IOException("The Lean project has been closed.")
            groups.entries.fold(emptyList<RunnerAndConfigurationSettings>()) { prepared, (workspace, arguments) ->
                prepared + configuration(
                    project, workspace, LakeProjectTask.FETCH_CACHE, arguments, prepared.map { it.name }
                )
            }
        }
    }

    suspend fun fetchCacheForFiles(project: Project, files: List<VirtualFile>) {
        val configurations = cacheConfigurations(project, files)
        withContext(Dispatchers.EDT) { start(project, configurations) }
    }

    suspend fun run(project: Project, file: VirtualFile?, task: LakeProjectTask) {
        val workspace = readAction { workspace(project, file) }
            ?: throw IOException("Select a file or folder in a Lake project, or open a Lake project directory.")
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) throw IOException("The Lean project has been closed.")
            start(project, listOf(configuration(project, workspace, task)))
        }
    }

    private fun start(project: Project, configurations: List<RunnerAndConfigurationSettings>) {
        if (project.isDisposed) throw IOException("The Lean project has been closed.")
        for (settings in configurations) {
            RunManager.getInstance(project).setTemporaryConfiguration(settings)
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }
}

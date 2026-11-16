package plugin.lean.project

import plugin.lean.rpc.LeanConfiguration
import plugin.lean.toolchain.LeanToolchainHost
import plugin.lean.toolchain.execute
import plugin.lean.tooling.LeanWorkspace
import plugin.lean.tooling.ToolCommand
import plugin.lean.tooling.project.ProjectGeneration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A prepared command fixes the installed toolchain before any project files are generated. */
class PreparedLeanProject internal constructor(
    val directory: Path,
    val name: String,
    val configuration: LeanConfiguration,
    private val command: ToolCommand
) {
    /** Blocking operation. Lake owns template semantics, file contents, and its generated toolchain selection. */
    fun create(onOutput: (String) -> Unit = {}) {
        ProjectGeneration.generate(directory) { staging ->
            command.inDirectory(staging).execute(0, onOutput)
            val workspace = LeanWorkspace.discoverDirectory(staging, staging)
            if (workspace.root() != staging || !workspace.usesLake()) {
                throw IOException("Lake finished without creating a project configuration.")
            }
            if (!Files.isRegularFile(staging.resolve("lean-toolchain"))) {
                throw IOException("Lake did not record the selected toolchain in the generated project.")
            }
        }
    }
}

object LeanProjectInitializer {
    /** Resolve against the nearest existing location without creating directories or installing Lean. */
    fun prepare(
        directory: Path,
        name: String,
        template: String,
        configuration: LeanConfiguration
    ): PreparedLeanProject {
        if (name.isBlank() || '\u0000' in name || '\u0000' in template) {
            throw IOException("Enter a project name and template without NUL characters.")
        }
        val target = directory.toAbsolutePath().normalize()
        val context = selectionDirectory(target)
        val packageName = if (name.trim() == ".") {
            target.fileName?.toString() ?: throw IOException("Choose a project directory with a name.")
        } else {
            name.trim()
        }
        val normalized = LeanToolchainHost.normalize(configuration)
        val arguments = buildList {
            addAll(listOf("lake", "init", packageName))
            template.trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
        val command = LeanToolchainHost.command(context, arguments, normalized)
        return PreparedLeanProject(target, packageName, normalized, command)
    }

    internal fun selectionDirectory(directory: Path): Path =
        generateSequence(directory.toAbsolutePath().normalize()) { it.parent }.firstOrNull { Files.isDirectory(it) }
            ?: throw IOException("The project location has no existing parent directory.")
}

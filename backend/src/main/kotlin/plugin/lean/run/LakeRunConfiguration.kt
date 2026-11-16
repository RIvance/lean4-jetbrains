package plugin.lean.run

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.CompositeParameterTargetedValue
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleProgramParameters
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.CommonProgramParametersPanel
import com.intellij.execution.util.ProgramParametersConfigurator
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.InvalidDataException
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import plugin.lean.language.LeanIcons
import plugin.lean.persistence.decodeLiteralText
import plugin.lean.persistence.encodeLiteralText
import plugin.lean.toolchain.LeanToolchainService
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComponent

class LakeConfigurationType : ConfigurationTypeBase(
    "LeanLake", "Lake", "Build and run Lean projects with Lake", LeanIcons.LEAN
) {
    val factory = object : ConfigurationFactory(this) {
        override fun getId() = "Lake"
        override fun getOptionsClass() = LakeRunConfigurationOptions::class.java
        override fun isEditableInDumbMode() = true
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            LakeRunConfiguration(project, this, "Lake")
    }

    init { addFactory(factory) }
}

class LakeRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var arguments: String? by string("build")
    var workingDirectory: String? by string()
    var toolchain: String? by string()
    var environment: MutableMap<String, String> by linkedMap()
    var inheritEnvironment: Boolean by property(true)
    var environmentFiles: MutableList<String> by list()
    var expandMacros: Boolean by property(true)
}

/** Paths and environment variables refer to the project host, including in split mode. */
class LakeRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<LakeRunConfigurationOptions>(project, factory, name),
    CommonProgramRunConfigurationParameters, EnvFilesOptions {

    override fun getOptions(): LakeRunConfigurationOptions = super.getOptions() as LakeRunConfigurationOptions
    override fun getConfigurationEditor(): SettingsEditor<LakeRunConfiguration> = LakeSettingsEditor(project)

    var toolchainOverride: String
        get() = options.toolchain.orEmpty()
        set(value) { options.toolchain = value }

    var expandMacros: Boolean
        get() = options.expandMacros
        set(value) { options.expandMacros = value }

    override fun getProgramParameters(): String = options.arguments.orEmpty()
    override fun setProgramParameters(value: String?) { options.arguments = value }
    override fun getWorkingDirectory(): String = options.workingDirectory.orEmpty()
    override fun setWorkingDirectory(value: String?) { options.workingDirectory = value }
    override fun getEnvs(): MutableMap<String, String> = LinkedHashMap(options.environment)
    override fun setEnvs(envs: MutableMap<String, String>) { options.environment = LinkedHashMap(envs) }
    override fun isPassParentEnvs() = options.inheritEnvironment
    override fun setPassParentEnvs(value: Boolean) { options.inheritEnvironment = value }
    override var envFilePaths: List<String>
        get() = options.environmentFiles.toList()
        set(value) { options.environmentFiles = value.toMutableList() }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        if (!expandMacros) {
            for (option in element.getChildren("option").filter { it.getAttributeValue("name") in LITERAL_OPTIONS }) {
                val value = checkNotNull(option.getAttributeValue("value"))
                option.name = "literalOption"
                option.setAttribute("value", encodeLiteralText(value))
            }
        }
    }

    override fun readExternal(element: Element) {
        val decoded = element.clone()
        for (option in decoded.getChildren("literalOption")) {
            if (option.getAttributeValue("name") !in LITERAL_OPTIONS) continue
            val encoded = option.getAttributeValue("value")
                ?: throw InvalidDataException("A literal Lake option has no value.")
            val value = try {
                decodeLiteralText(encoded)
            } catch (error: IllegalArgumentException) {
                throw InvalidDataException("A literal Lake option has invalid encoding.", error)
            }
            option.name = "option"
            option.setAttribute("value", value)
        }
        super.readExternal(decoded)
    }

    override fun checkConfiguration() {
        val arguments = ParametersListUtil.parse(programParameters)
        if (arguments.isEmpty() || arguments.first().isBlank()) {
            throw RuntimeConfigurationError("Enter a Lake command or option, such as build or --help.")
        }
        if (arguments.any { '\u0000' in it } || '\u0000' in toolchainOverride) {
            throw RuntimeConfigurationError("Lake arguments and toolchain names cannot contain NUL characters.")
        }
        val directory = try {
            parametersConfigurator().apply { setValidation(true) }.getWorkingDir(this, project, null)
        } catch (error: InvalidPathException) {
            throw RuntimeConfigurationError("Invalid Lake working directory: ${error.input}")
        } ?: throw RuntimeConfigurationError("Choose a working directory for Lake.")
        val validDirectory = try { Files.isDirectory(Path.of(directory)) } catch (_: InvalidPathException) { false }
        if (!validDirectory) throw RuntimeConfigurationError("Lake working directory does not exist: $directory")
    }

    /** Resolve native environment settings once; literal commands bypass both IDE and environment argument macros. */
    fun createParameters(): SimpleProgramParameters {
        val parameters = SimpleProgramParameters()
        val input = if (expandMacros) this else object :
            CommonProgramRunConfigurationParameters by this, EnvFilesOptions by this {
            override fun getProgramParameters(): String? = null
        }
        parametersConfigurator().configureConfiguration(parameters, input)
        if (!expandMacros) {
            ParametersListUtil.parse(programParameters).forEach {
                parameters.programParametersList.add(CompositeParameterTargetedValue(it))
            }
        }
        return parameters
    }

    private fun parametersConfigurator() = object : ProgramParametersConfigurator() {
        override fun getWorkingDir(
            configuration: CommonProgramRunConfigurationParameters, project: Project, module: Module?
        ): String? {
            if (expandMacros) return super.getWorkingDir(configuration, project, module)
            val directory = configuration.workingDirectory?.takeIf { it.isNotBlank() } ?: return project.basePath
            val path = Path.of(directory)
            return if (path.isAbsolute) {
                path.toString()
            } else {
                project.basePath?.let { Path.of(it).resolve(path).toString() }
            }
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val snapshot = clone() as LakeRunConfiguration
        val projectConfiguration = project.service<LeanToolchainService>().configuration
        val selection = snapshot.toolchainOverride.trim().ifEmpty { projectConfiguration.toolchain }
        return LakeRunState(snapshot, projectConfiguration.copy(toolchain = selection), environment)
    }

    private companion object {
        // Native project storage expands macros before readExternal. Encode literal UTF-16 values at this boundary.
        val LITERAL_OPTIONS = setOf("arguments", "workingDirectory")
    }
}

/** The platform owns run-configuration editing and forwards this editor to remote clients. */
private class LakeSettingsEditor(project: Project) : SettingsEditor<LakeRunConfiguration>() {
    private val parameters = object : CommonProgramParametersPanel(project) {
        override fun isMacroSupportEnabled() = true
    }.apply { setProgramParametersLabel("Lake arguments:") }
    private val toolchain = JBTextField()
    private val expandMacros = JBCheckBox("Expand macros in arguments and working directory")
    private val component = panel {
        row { cell(parameters).align(Align.FILL) }
        row { comment("Examples: build, exe my_app \"argument\", test, or a project-defined script.") }
        row { cell(expandMacros) }
        row("Toolchain:") {
            cell(toolchain).align(Align.FILL).comment("Blank uses the project’s Lean toolchain selection.")
        }
    }

    override fun resetEditorFrom(configuration: LakeRunConfiguration) {
        parameters.reset(configuration)
        toolchain.text = configuration.toolchainOverride
        expandMacros.isSelected = configuration.expandMacros
    }

    override fun applyEditorTo(configuration: LakeRunConfiguration) {
        parameters.applyTo(configuration)
        configuration.toolchainOverride = toolchain.text.trim()
        configuration.expandMacros = expandMacros.isSelected
    }

    override fun createEditor(): JComponent = component
}

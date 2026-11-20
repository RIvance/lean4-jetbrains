package plugin.lean.project

import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GeneratorNewProjectWizardBuilderAdapter
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.initOnShow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import plugin.lean.language.LeanIcons
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.rpc.ToolchainInspection
import plugin.lean.settings.LeanSettings
import plugin.lean.toolchain.LeanToolchainComboBox
import plugin.lean.toolchain.LeanToolchainHost
import plugin.lean.toolchain.chooseLeanToolchainToInstall
import plugin.lean.toolchain.describe
import plugin.lean.toolchain.execute
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.event.DocumentEvent

/** The platform's project generator owns this UI and project-model creation on the project host. */
class LeanProjectModuleBuilder : GeneratorNewProjectWizardBuilderAdapter(LeanProjectWizard()) {
    override fun canCreateModule() = false
}

class LeanProjectWizard : GeneratorNewProjectWizard {
    override val id = "lean4"
    override val name = "Lean 4"
    override val icon = LeanIcons.LEAN
    override val description = "Create a Lean project using Lake and an installed Lean toolchain."

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context).nextStep(::NewProjectWizardBaseStep).nextStep(::LeanProjectStep)
}

private class LeanProjectStep(private val base: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(base) {
    private val executable = JBTextField().apply { emptyText.text = "Detect from ELAN_HOME and PATH" }
    private val toolchain = LeanToolchainComboBox()
    // Suggestions remain editable: the selected Lake version owns the supported template identifiers.
    private val template = ComboBox(arrayOf("std", "exe", "lib", "math", "math-lax")).apply { isEditable = true }
    private val inspect = JButton("Inspect Toolchains")
    private val install = JButton("Install Toolchain")
    private val help = JButton("Template Help")
    private val details = JBTextArea(5, 32).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        accessibleContext.accessibleName = "Lean project setup details"
    }
    private var prepared: PreparedLeanProject? = null
    private var inspection: Job? = null
    private var templateHelp: Job? = null

    init {
        Disposer.register(context.disposable) {
            inspection?.cancel()
            templateHelp?.cancel()
        }
        inspect.addActionListener { inspectToolchains() }
        install.addActionListener { installToolchain() }
        toolchain.onSelectionChanged { inspectToolchains(debounce = true) }
        help.addActionListener {
            templateHelp?.cancel()
            templateHelp = readSelection(
                "Reading Lake template help…", waitFor = inspection, action = { directory, configuration ->
                    runInterruptible(Dispatchers.IO) {
                        LeanToolchainHost.command(directory, listOf("lake", "help", "init"), configuration)
                            .execute(15_000)
                    }
                }
            ) { text, _ -> details.text = text.trim() }
        }
        base.pathProperty.afterChange(context.disposable) { inspectToolchains(debounce = true) }
        base.nameProperty.afterChange(context.disposable) { inspectToolchains(debounce = true) }
        executable.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = inspectToolchains(debounce = true)
        })
        inspectToolchains()
    }

    private fun inspectToolchains(debounce: Boolean = false) {
        templateHelp?.cancel()
        inspection?.cancel()
        inspection = readSelection(
            "Inspecting installed Lean toolchains…", debounce = debounce, action = LeanToolchainHost::inspect
        ) { result, config -> showInspection(result, config) }
    }

    private fun showInspection(result: ToolchainInspection, config: LeanConfiguration) {
        toolchain.showInspection(result, config.toolchain)
        details.text = result.describe(config.toolchain)
        details.caretPosition = 0
    }

    private fun installToolchain() {
        templateHelp?.cancel()
        inspection?.cancel()
        val config = configuration()
        val owner = ModalTaskOwner.component(toolchain)
        try {
            val target = destination()
            val (directory, inventory) = runWithModalProgressBlocking(owner, "Checking installed Lean toolchains") {
                val directory = runInterruptible(Dispatchers.IO) { LeanProjectInitializer.selectionDirectory(target) }
                directory to LeanToolchainHost.inspect(directory, config)
            }
            showInspection(inventory, config)
            if (inventory !is ToolchainInspection.Available) return
            val name = chooseLeanToolchainToInstall(
                context.project, toolchain, inventory, "Install and Use"
            ) ?: return
            runWithModalProgressBlocking(owner, "Installing $name") {
                LeanToolchainHost.install(directory, config, name)
            }
            toolchain.toolchainName = name
            inspectToolchains()
        } catch (error: IOException) {
            details.text = error.message ?: "Could not install the Lean toolchain."
            details.caretPosition = 0
        } catch (error: InvalidPathException) {
            details.text = "Invalid project or executable path: ${error.input}"
            details.caretPosition = 0
        }
    }

    override fun setupUI(builder: Panel) {
        with(builder) {
            row("Lean toolchain:") {
                cell(toolchain).align(Align.FILL)
                    .comment("Automatic follows elan’s selection at the project location.")
                    .validationOnApply {
                        try {
                            val directory = destination()
                            val name = base.name
                            val template = templateName()
                            val configuration = configuration()
                            prepared = runWithModalProgressBlocking(
                                ModalTaskOwner.component(toolchain), "Checking Lean toolchain"
                            ) {
                                runInterruptible(Dispatchers.IO) {
                                    LeanProjectInitializer.prepare(directory, name, template, configuration)
                                }
                            }
                            null
                        } catch (error: IOException) {
                            error(error.message ?: "Cannot prepare the Lean project.")
                        } catch (error: InvalidPathException) {
                            error("Invalid project or executable path: ${error.input}")
                        }
                    }
            }
            row {
                cell(inspect)
                cell(install)
            }
            row("Template:") {
                cell(template).align(Align.FILL)
                cell(help)
            }
            row { comment("Use std.lean or std.toml to choose the Lake file format.") }
            row { cell(JBScrollPane(details)).align(Align.FILL) }
            collapsibleGroup("Elan executable") {
                row("Elan executable:") {
                    cell(executable).align(Align.FILL).comment("Leave empty to find elan on the project host.")
                }
            }
            row { comment("Lake writes the selected version to lean-toolchain.") }
            row { comment("Some templates initialize Git and download dependencies.") }
        }
    }

    override fun setupProject(project: Project) {
        val plan = checkNotNull(prepared) { "The Lean project must be validated before creation" }
        invokeAndWaitIfNeeded {
            runWithModalProgressBlocking(ModalTaskOwner.guess(), "Creating Lean project") {
                withContext(Dispatchers.IO) {
                    coroutineToIndicator { progress ->
                        plan.create { output ->
                            progress.text2 = output.lineSequence().lastOrNull { it.isNotBlank() }?.take(250)
                        }
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(plan.directory)?.refresh(false, true)
                    }
                }
            }
        }
        project.service<LeanSettings>().update(plan.configuration.copy(toolchain = ""))
        val builder = object : ModuleBuilder() {
            override fun getModuleType() = GeneralModuleType.INSTANCE

            override fun setupRootModel(model: ModifiableRootModel) {
                model.addContentEntry(VfsUtilCore.pathToUrl(plan.directory.toString()))
            }
        }.apply {
            name = plan.name
            contentEntryPath = plan.directory.toString()
            moduleFilePath = plan.directory.resolve(Project.DIRECTORY_STORE_FOLDER)
                .resolve(plan.name + ModuleFileType.DOT_DEFAULT_EXTENSION).toString()
        }
        invokeAndWaitIfNeeded {
            runWriteAction { builder.createAndCommitIfNeeded(project, null, true) }
        }
    }

    private fun configuration() = LeanConfiguration(
        elanExecutable = executable.text.trim(), toolchain = toolchain.toolchainName
    )

    private fun templateName(): String = template.editor.item?.toString()?.trim().orEmpty()
    private fun destination(): Path = Path.of(base.path).resolve(base.name).toAbsolutePath().normalize()

    private fun <T> readSelection(
        message: String,
        debounce: Boolean = false,
        waitFor: Job? = null,
        action: suspend (Path, LeanConfiguration) -> T,
        show: (T, LeanConfiguration) -> Unit
    ): Job = toolchain.initOnShow(message) {
        waitFor?.join()
        if (debounce) delay(200)
        val configuration = configuration()
        details.text = message
        try {
            val target = destination()
            val directory = runInterruptible(Dispatchers.IO) { LeanProjectInitializer.selectionDirectory(target) }
            val result = action(directory, configuration)
            if (configuration() == configuration) {
                show(result, configuration)
            } else {
                inspectToolchains(debounce = true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: IOException) {
            details.text = error.message
        } catch (error: InvalidPathException) {
            details.text = "Invalid project or executable path: ${error.input}"
        } catch (error: Exception) {
            logger<LeanProjectWizard>().warn("Could not inspect the new project's Lean environment", error)
            details.text = "Could not inspect Lean. See the IDE log for details."
        }
        details.caretPosition = 0
    }
}

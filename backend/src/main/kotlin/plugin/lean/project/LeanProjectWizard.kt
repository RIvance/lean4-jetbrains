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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import plugin.lean.language.LeanIcons
import plugin.lean.rpc.ActiveToolchainInfo
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.rpc.ToolchainInspection
import plugin.lean.settings.LeanSettings
import plugin.lean.toolchain.LeanToolchainHost
import plugin.lean.toolchain.execute
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton

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

@Service(Service.Level.APP)
internal class LeanProjectWizardScope(val scope: CoroutineScope)

private class LeanProjectStep(private val base: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(base) {
    private val parentScope = service<LeanProjectWizardScope>().scope
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT
    )
    private val executable = JBTextField().apply { emptyText.text = "Detect from ELAN_HOME and PATH" }
    private val toolchain = ComboBox<String>().apply {
        isEditable = true
        model = DefaultComboBoxModel(arrayOf(""))
    }
    // Suggestions remain editable: the selected Lake version owns the supported template identifiers.
    private val template = ComboBox(arrayOf("std", "exe", "lib", "math", "math-lax")).apply { isEditable = true }
    private val inspect = JButton("Inspect Toolchains")
    private val help = JButton("Template Help")
    private val details = JBTextArea(5, 45).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        accessibleContext.accessibleName = "Lean project setup details"
    }
    private var prepared: PreparedLeanProject? = null

    init {
        Disposer.register(context.disposable) { scope.cancel() }
        inspect.addActionListener {
            inspectSelection({ directory, configuration ->
                LeanToolchainHost.inspect(directory, configuration)
            }) { result, configuration ->
                details.text = when (result) {
                    is ToolchainInspection.Failed -> result.message
                    is ToolchainInspection.Available -> {
                        toolchain.model = DefaultComboBoxModel(
                            (listOf("") + result.installed.map { it.name }).toTypedArray()
                        )
                        toolchain.editor.item = configuration.toolchain
                        val active = when (val selection = result.active) {
                            is ActiveToolchainInfo.Available -> selection.toolchain.name
                            is ActiveToolchainInfo.Unavailable -> selection.message
                        }
                        "Elan ${result.elanVersion}\n${result.executable}\n\nSelected toolchain: $active"
                    }
                }
            }
        }
        help.addActionListener {
            inspectSelection({ directory, configuration ->
                runInterruptible(Dispatchers.IO) {
                    LeanToolchainHost.command(directory, listOf("lake", "help", "init"), configuration).execute(15_000)
                }
            }) { text, _ -> details.text = text.trim() }
        }
    }

    override fun setupUI(builder: Panel) {
        with(builder) {
            row("Elan executable:") {
                cell(executable).align(Align.FILL).comment("Blank uses automatic discovery on the project host.")
            }
            row("Lean toolchain:") {
                cell(toolchain).align(Align.FILL)
                    .comment("Choose an installed toolchain, or leave blank to use elan’s selection at this location.")
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
            row("Template:") {
                cell(template).align(Align.FILL)
                    .comment("std: library and executable; lib: library; exe: executable.")
            }
            row {
                comment("A .lean or .toml suffix selects the configuration format. Template Help shows Lake’s options.")
            }
            row {
                cell(inspect)
                cell(help)
            }
            row { cell(JBScrollPane(details)).align(Align.FILL) }
            row { comment("Lake generates the files and records the selected Lean version in lean-toolchain.") }
            row { comment("Templates may initialize Git and download project dependencies, including Mathlib.") }
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
        elanExecutable = executable.text.trim(), toolchain = toolchain.editor.item?.toString()?.trim().orEmpty()
    )

    private fun templateName(): String = template.editor.item?.toString()?.trim().orEmpty()
    private fun destination(): Path = Path.of(base.path).resolve(base.name).toAbsolutePath().normalize()

    private fun <T> inspectSelection(
        action: suspend (Path, LeanConfiguration) -> T,
        show: (T, LeanConfiguration) -> Unit
    ) {
        if (!inspect.isEnabled) return
        val configuration = configuration()
        val target = try {
            destination()
        } catch (error: InvalidPathException) {
            details.text = error.message
            return
        }
        scope.launch {
            setBusy(true)
            details.text = "Reading the selected Lean environment…"
            try {
                val directory = runInterruptible(Dispatchers.IO) { LeanProjectInitializer.selectionDirectory(target) }
                val result = action(directory, configuration)
                if (destination() == target && configuration() == configuration) {
                    show(result, configuration)
                } else {
                    details.text = "The project location changed. Inspect the new location to refresh these details."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: IOException) {
                details.text = error.message
            } catch (error: Exception) {
                logger<LeanProjectWizard>().warn("Could not inspect the new project's Lean environment", error)
                details.text = "Could not inspect Lean. See the IDE log for details."
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        listOf(executable, toolchain, template, inspect, help).forEach { it.isEnabled = !busy }
    }
}

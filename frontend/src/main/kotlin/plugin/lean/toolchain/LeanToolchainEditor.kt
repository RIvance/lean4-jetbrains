package plugin.lean.toolchain

import com.intellij.execution.util.EnvVariablesTable
import com.intellij.execution.util.EnvironmentVariable
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.initOnShow
import com.intellij.util.execution.ParametersListUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.rpc.LeanConfigurationUpdate
import plugin.lean.rpc.LeanRpcResult
import plugin.lean.rpc.LeanToolchainApi
import plugin.lean.rpc.ToolchainInspection
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.event.DocumentEvent

/** Owns one editable draft and its cancellable host operations, shared by Settings and the tool window. */
internal class LeanToolchainEditor(
    private val project: Project,
    parentScope: CoroutineScope
) : JBPanel<LeanToolchainEditor>(), Disposable {
    class Failure(message: String) : Exception(message)

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT
    )
    private val enabled = JBCheckBox("Enable Lean language server")
    private val enhancedHighlighting = JBCheckBox("Enable richer semantic highlighting on supported toolchains")
    private val executable = JBTextField().apply {
        emptyText.text = "Detect from ELAN_HOME and PATH"
        accessibleContext.accessibleName = "Elan executable"
    }
    private val toolchain = LeanToolchainComboBox()
    private val serverArguments = RawCommandLineEditor().apply {
        textField.accessibleContext.accessibleName = "Lean server arguments"
    }
    private val environment = EnvVariablesTable().apply {
        setPasteActionEnabled(true)
        tableView.accessibleContext.accessibleName = "Lean project environment"
        tableView.preferredScrollableViewportSize = JBUI.size(350, 100)
        tableView.putClientProperty("terminateEditOnFocusLost", true)
    }
    private val details = JBTextArea(5, 32).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        accessibleContext.accessibleName = "Lean toolchain details"
    }
    private val status = JBLabel("Reading Lean configuration…")
    private val inspect = JButton("Inspect")
    private val install = JButton("Install Toolchain")
    private val cancel = JButton("Cancel Operation")
    private var saved: LeanConfiguration? = null
    private var preview: Job? = null
    private var background: Job? = null
    private var operation: Job? = null

    val isLoaded: Boolean get() = saved != null
    val isBusy: Boolean get() = operation != null
    val isModified: Boolean
        get() {
            val saved = saved ?: return false
            return try { saved != readForm() } catch (_: Failure) { true }
        }

    init {
        layout = BorderLayout(0, JBUI.scale(12))
        add(panel {
            row("Lean toolchain:") {
                cell(toolchain).align(Align.FILL)
                    .comment("Automatic follows the project’s lean-toolchain file or elan selection.")
            }
            row {
                cell(inspect)
                cell(install)
                cell(cancel)
            }
            row { cell(status) }
            row { cell(JBScrollPane(details)).align(Align.FILL) }.resizableRow()
            row { cell(enabled) }
            collapsibleGroup("Elan and server options") {
                row("Elan executable:") {
                    cell(executable).align(Align.FILL).comment("Leave empty to find elan on the project host.")
                }
                row { cell(enhancedHighlighting) }
                row("Server arguments:") {
                    cell(serverArguments).align(Align.FILL)
                        .comment("Additional Lean server arguments. Quote values containing spaces.")
                }
                row { label("Project environment variables:") }
                row { cell(environment.component).align(Align.FILL) }
                row {
                    comment("Override the project host’s environment for elan, Lean, and Lake. Values are literal.")
                }
            }
        }, BorderLayout.CENTER)
        toolchain.onSelectionChanged { previewSelection() }
        executable.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = previewSelection()
        })
        inspect.addActionListener {
            launchOperation("Inspecting elan…") { inspect(readForm()) }
        }
        install.addActionListener {
            launchOperation("Checking installed toolchains…") { chooseAndInstall(readForm()) }
        }
        cancel.addActionListener {
            background?.cancel()
            operation?.cancel()
        }
        updateControls()
    }

    private fun readForm(): LeanConfiguration {
        val variables = environment.environmentVariables.filter { it.name.isNotEmpty() || it.value.isNotEmpty() }
        if (variables.map { it.name }.distinct().size != variables.size) {
            throw Failure("Each project environment variable must have a unique name.")
        }
        return LeanConfiguration(
            elanExecutable = executable.text.trim(),
            toolchain = toolchain.toolchainName,
            enabled = enabled.isSelected,
            serverArguments = ParametersListUtil.parse(serverArguments.text),
            environment = variables.associate { it.name to it.value },
            enhancedSemanticHighlighting = enhancedHighlighting.isSelected
        )
    }

    private fun writeForm(configuration: LeanConfiguration) {
        enabled.isSelected = configuration.enabled
        enhancedHighlighting.isSelected = configuration.enhancedSemanticHighlighting
        executable.text = configuration.elanExecutable
        toolchain.toolchainName = configuration.toolchain
        serverArguments.text = ParametersListUtil.join(configuration.serverArguments)
        environment.setValues(configuration.environment.map { (name, value) ->
            EnvironmentVariable(name, value, false)
        })
    }

    /** Reset discards this draft, cancels its pending operation, and reads a fresh saved snapshot. */
    fun reset() {
        val previous = background
        previous?.cancel()
        preview?.cancel()
        operation?.cancel()
        saved = null
        updateControls()
        background = initOnShow("Load Lean toolchains") {
            previous?.join()
            reportFailure {
                perform("Reading Lean configuration…") {
                    val configuration = LeanToolchainApi.getInstance().configuration(project.projectId())
                    saved = configuration.snapshot()
                    writeForm(configuration)
                    updateControls()
                    showStatus("Inspecting elan…")
                    inspect(configuration)
                }
            }
        }
    }

    /** Failures leave the draft intact; a successful commit adopts the backend's normalized snapshot. */
    suspend fun applyChanges() {
        perform("Applying Lean configuration…") {
            val expected = saved ?: throw Failure("Wait for the Lean configuration to finish loading.")
            when (val result = LeanToolchainApi.getInstance().apply(project.projectId(), expected, readForm())) {
                is LeanConfigurationUpdate.Applied -> {
                    saved = result.configuration.snapshot()
                    writeForm(result.configuration)
                    scheduleInspection("Settings saved.", ModalityState.stateForComponent(this))
                }
                is LeanConfigurationUpdate.Conflict -> throw Failure(
                    "Lean settings changed in another window. Reset to reload them before applying your changes."
                )
                is LeanConfigurationUpdate.Failed -> throw Failure(result.message)
            }
        }
    }

    fun applyInBackground() {
        if (isBusy || background?.isActive == true) return
        background = scope.launch(ModalityState.current().asContextElement(), start = CoroutineStart.UNDISPATCHED) {
            reportFailure { applyChanges() }
        }
    }

    private suspend fun chooseAndInstall(configuration: LeanConfiguration) {
        val api = LeanToolchainApi.getInstance()
        val projectId = project.projectId()
        val inventory = when (val result = api.inspect(projectId, configuration)) {
            is ToolchainInspection.Available -> result
            is ToolchainInspection.Failed -> throw Failure(result.message)
        }
        val name = chooseLeanToolchainToInstall(project, this, inventory)
        if (name == null) {
            showStatus("Installation cancelled.")
            return
        }
        currentCoroutineContext().ensureActive()
        showStatus("Installing $name…")
        val result = api.install(projectId, configuration, name)
        if (result is LeanRpcResult.Failure) throw Failure(result.message)
        scheduleInspection("Installed $name.")
    }

    private fun previewSelection() {
        if (isLoaded && !isBusy) scheduleInspection()
    }

    /** A preview belongs to the editor. A committed change can finish even while its refresh is pending. */
    private fun scheduleInspection(notice: String = "", modality: ModalityState = ModalityState.current()) {
        preview?.cancel()
        showStatus(notice.ifEmpty { "Inspecting selection…" })
        details.text = ""
        preview = scope.launch(modality.asContextElement()) {
            delay(200)
            reportFailure {
                val configuration = readForm()
                val result = LeanToolchainApi.getInstance().inspect(project.projectId(), configuration)
                ensureActive()
                if (configuration == readForm()) {
                    showInspection(result, configuration.toolchain)
                    showStatus(notice)
                } else showStatus("Use Inspect to check the edited configuration.")
            }
        }
    }

    private suspend fun inspect(configuration: LeanConfiguration) {
        val result = LeanToolchainApi.getInstance().inspect(project.projectId(), configuration)
        showInspection(result, configuration.toolchain)
    }

    private fun showInspection(result: ToolchainInspection, selection: String) {
        toolchain.showInspection(result, selection)
        details.text = result.describe(selection)
        details.caretPosition = 0
        showStatus("")
    }

    private fun showStatus(message: String) {
        status.text = message
        status.isVisible = message.isNotEmpty()
    }

    private fun launchOperation(message: String, action: suspend () -> Unit) {
        if (isBusy || background?.isActive == true) return
        background = scope.launch(ModalityState.current().asContextElement(), start = CoroutineStart.UNDISPATCHED) {
            reportFailure { perform(message, action) }
        }
    }

    private suspend fun perform(message: String, action: suspend () -> Unit) = withContext(Dispatchers.EDT) {
        scope.coroutineContext.ensureActive()
        if (isBusy) throw Failure("Finish or cancel the current Lean operation before applying settings.")
        preview?.cancel()
        environment.stopEditing()
        operation = currentCoroutineContext().job
        updateControls()
        firePropertyChange("busy", false, true)
        showStatus(message)
        try {
            action()
        } catch (error: Exception) {
            showStatus(when (error) {
                is CancellationException, is ProcessCanceledException -> "Cancelled."
                else -> "Lean operation failed."
            })
            throw error
        } finally {
            operation = null
            updateControls()
            firePropertyChange("busy", true, false)
        }
    }

    private suspend fun reportFailure(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: Failure) {
            details.text = error.message
            details.caretPosition = 0
            showStatus("Lean settings need attention.")
        } catch (error: Exception) {
            logger<LeanToolchainEditor>().warn("Lean configuration operation failed", error)
            details.text = "Could not complete the Lean configuration operation. See the IDE log for details."
            details.caretPosition = 0
            showStatus("Lean configuration is unavailable.")
        }
    }

    private fun updateControls() {
        val available = isLoaded && !isBusy
        listOf(enabled, enhancedHighlighting, executable, toolchain, serverArguments, inspect, install).forEach {
            it.isEnabled = available
        }
        if (available) environment.setEnabled() else environment.setDisabled()
        cancel.isVisible = isBusy && isLoaded
    }

    override fun dispose() {
        background?.cancel()
        operation?.cancel()
        scope.cancel()
    }
}

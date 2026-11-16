package plugin.lean.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import plugin.lean.LeanFrontendScope
import plugin.lean.editor.LeanProofFoldingStartup
import plugin.lean.infoview.InfoviewSettings
import plugin.lean.infoview.LeanInfoviewStartup
import plugin.lean.toolchain.LeanToolchainEditor
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The native Settings dialog owns Apply/Reset; editing and host operations are shared with the tool window. */
class LeanProjectConfigurable(private val project: Project) : SearchableConfigurable {
    private var editor: LeanProjectSettingsPanel? = null

    override fun getId() = "plugin.lean.project"
    override fun getDisplayName() = "Lean"

    override fun createComponent(): JComponent =
        editor ?: LeanProjectSettingsPanel(project, project.service<LeanFrontendScope>().scope).also { editor = it }

    override fun isModified(): Boolean = editor?.isModified == true

    override fun reset() { editor?.reset() }

    override fun apply() {
        val editor = editor ?: return
        if (!editor.isModified) return
        try {
            runWithModalProgressBlocking(project, "Applying Lean configuration") { editor.applyChanges() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: LeanToolchainEditor.Failure) {
            throw ConfigurationException(error.message, error, "Cannot Apply Lean Settings")
        } catch (error: Exception) {
            logger<LeanProjectConfigurable>().warn("Cannot apply Lean settings", error)
            throw ConfigurationException(
                "Could not apply Lean settings. See the IDE log for details.",
                error, "Cannot Apply Lean Settings"
            )
        }
    }

    override fun disposeUIResources() {
        editor?.let(Disposer::dispose)
        editor = null
    }
}

/** Keeps frontend display preferences independent of toolchain validation and server restarts. */
private class LeanProjectSettingsPanel(
    private val project: Project, scope: CoroutineScope
) : JPanel(BorderLayout(0, JBUI.scale(12))), Disposable {
    private val toolchain = LeanToolchainEditor(project, scope)
    private val autoOpen = JBCheckBox("Open InfoView automatically when selecting a Lean file")
    private val foldProofs = JBCheckBox("Fold proofs when opening a Lean file")
    private val settings: InfoviewSettings get() = project.service()
    private val editorSettings: LeanEditorSettings get() = project.service()

    val isModified: Boolean get() =
        toolchain.isModified || autoOpen.isSelected != settings.autoOpen ||
        foldProofs.isSelected != editorSettings.foldProofsOnOpen

    init {
        Disposer.register(this, toolchain)
        add(panel {
            group("Editor") {
                row { cell(foldProofs) }
                row {
                    comment("Applies to newly opened editors. Proofs containing a caret or selection stay expanded.")
                }
            }
            group("InfoView") {
                row { cell(autoOpen) }
                row { comment("Opens once per project session without moving focus away from the editor.") }
            }
        }, BorderLayout.NORTH)
        add(toolchain, BorderLayout.CENTER)
    }

    fun reset() {
        autoOpen.isSelected = settings.autoOpen
        foldProofs.isSelected = editorSettings.foldProofsOnOpen
        toolchain.reset()
    }

    suspend fun applyChanges() = withContext(Dispatchers.EDT) {
        val requestedAutoOpen = autoOpen.isSelected
        val requestedFolding = foldProofs.isSelected
        if (toolchain.isModified) toolchain.applyChanges()
        settings.autoOpen = requestedAutoOpen
        editorSettings.foldProofsOnOpen = requestedFolding
        if (!requestedFolding) project.service<LeanProofFoldingStartup>().cancelPending()
        project.service<LeanInfoviewStartup>().checkSelection()
    }

    override fun dispose() = Unit
}

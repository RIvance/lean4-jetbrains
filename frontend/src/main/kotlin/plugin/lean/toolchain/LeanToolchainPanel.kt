package plugin.lean.toolchain

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class LeanToolchainPanel(project: Project, parentScope: CoroutineScope) :
    JBPanel<LeanToolchainPanel>(), Disposable {
    private val editor = LeanToolchainEditor(project, parentScope)

    init {
        layout = BorderLayout(0, JBUI.scale(12))
        border = JBUI.Borders.empty(12)
        Disposer.register(this, editor)
        add(editor, BorderLayout.CENTER)
        val apply = JButton("Apply and Restart").apply {
            isEnabled = false
            addActionListener { editor.applyInBackground() }
        }
        val reset = JButton("Reset").apply { addActionListener { editor.reset() } }
        editor.addPropertyChangeListener("busy") {
            apply.isEnabled = editor.isLoaded && !editor.isBusy
            reset.isEnabled = !editor.isBusy
        }
        add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), 0)).apply {
            add(apply)
            add(reset)
        }, BorderLayout.SOUTH)
        editor.reset()
    }

    override fun dispose() = Unit
}

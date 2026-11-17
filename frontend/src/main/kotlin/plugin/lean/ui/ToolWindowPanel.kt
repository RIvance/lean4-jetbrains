package plugin.lean.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Owns one tab's content and initialization attempts on the EDT. Failed attempts cancel their child scopes;
 * successful content lives until the tab is disposed. Constructors still own partial non-coroutine resources.
 */
internal class ToolWindowPanel<T>(
    private val title: String,
    private val parentScope: () -> CoroutineScope,
    private val createContent: (CoroutineScope) -> T,
    private val preferredFocus: (T) -> JComponent = { it }
) : JPanel(BorderLayout()), Disposable where T : JComponent, T : Disposable {
    var content: T? = null
        private set
    private var disposed = false
    private val retry = JButton("Retry $title").apply { addActionListener { initialize() } }

    val focusComponent: JComponent get() = content?.let(preferredFocus) ?: retry

    init {
        initialize()
    }

    private fun initialize() {
        if (disposed || content != null) return
        val lifetime = Disposer.newDisposable("$title initialization")
        Disposer.register(this, lifetime)
        var initialized = false
        try {
            val parent = parentScope()
            val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
            Disposer.register(lifetime, Disposable { scope.cancel() })
            scope.ensureActive()
            val candidate = createContent(scope)
            Disposer.register(lifetime, candidate)
            removeAll()
            add(candidate, BorderLayout.CENTER)
            content = candidate
            initialized = true
        } catch (failure: Throwable) {
            if (failure is CancellationException || failure is ControlFlowException ||
                (failure is Error && failure !is LinkageError && failure !is AssertionError)
            ) throw failure
            logger<ToolWindowPanel<*>>().warn("Could not initialize the Lean $title tab", failure)
            removeAll()
            val message = JBTextArea(
                "$title could not be opened.\n\n" +
                    "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}\n\n" +
                    "Try again using the button below. If the problem persists, restart the IDE. " +
                    "The full error is recorded in the IDE log (Help → Show Log)."
            ).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                font = JBLabel().font
                border = JBUI.Borders.empty(12)
                accessibleContext.accessibleName = "$title initialization error"
            }
            add(JBScrollPane(message), BorderLayout.CENTER)
            add(JPanel().apply { add(retry) }, BorderLayout.SOUTH)
        } finally {
            if (!initialized) Disposer.dispose(lifetime)
            revalidate()
            repaint()
        }
    }

    override fun dispose() {
        disposed = true
        retry.isEnabled = false
        content = null
    }
}

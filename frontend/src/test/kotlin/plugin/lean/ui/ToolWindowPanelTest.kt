package plugin.lean.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

class ToolWindowPanelTest : BasePlatformTestCase() {
    fun testInitializationFailuresShowTheCauseAndCancelUnfinishedWork() {
        for (failure in listOf(
            IllegalStateException("Service initialization failed"),
            NoClassDefFoundError("com/intellij/ui/jcef/JBCefBrowser"),
            UnsatisfiedLinkError("Native browser library is unavailable"),
            AssertionError("Invalid component state")
        )) {
            val parent = CoroutineScope(SupervisorJob())
            lateinit var unfinished: Job
            val panel = ToolWindowPanel<TestContent>("InfoView", { parent }, { scope ->
                unfinished = Job(scope.coroutineContext[Job])
                throw failure
            })
            try {
                assertNull(panel.content)
                assertTrue(unfinished.isCancelled)
                assertFalse(parent.coroutineContext[Job]!!.children.any())
                val message = UIUtil.findComponentsOfType(panel, JTextArea::class.java).single()
                assertTrue(message.text.contains("InfoView could not be opened"))
                assertTrue(message.text.contains(failure.message!!))
                assertTrue(message.text.contains("IDE log"))
                assertTrue(retry(panel).isEnabled)
                assertSame(retry(panel), panel.focusComponent)
            } finally {
                Disposer.dispose(panel)
                parent.cancel()
            }
        }
    }

    fun testRetryReplacesTheErrorAndOwnsTheSuccessfulContentsLifetime() {
        val parent = CoroutineScope(SupervisorJob())
        val content = TestContent()
        var attempts = 0
        lateinit var work: Job
        val panel = ToolWindowPanel("InfoView", { parent }, { scope ->
            attempts++
            work = Job(scope.coroutineContext[Job])
            if (attempts < 3) throw IllegalStateException("Initialization failed")
            content
        }, { it.focus })
        try {
            val button = retry(panel)
            button.doClick()
            assertEquals(2, attempts)
            assertTrue(work.isCancelled)
            assertNull(panel.content)
            button.doClick()
            assertEquals(3, attempts)
            assertSame(content, panel.content)
            assertTrue(UIUtil.findComponentsOfType(panel, JTextArea::class.java).isEmpty())
            assertTrue(work.isActive)
            assertSame(content.focus, panel.focusComponent)
            content.focus = JButton("New focus target")
            assertSame(content.focus, panel.focusComponent)
            button.doClick()
            assertEquals(3, attempts)
            Disposer.dispose(panel)
            assertEquals(1, content.disposals)
            assertTrue(work.isCancelled)
            assertNull(panel.content)
            assertFalse(parent.coroutineContext[Job]!!.children.any())
            button.doClick()
            assertEquals(3, attempts)
        } finally {
            Disposer.dispose(panel)
            parent.cancel()
        }
    }

    fun testFailureToObtainTheParentScopeAlsoHasVisibleRecovery() {
        val panel = ToolWindowPanel<TestContent>("Toolchains", {
            throw IllegalStateException("Project service unavailable")
        }, { error("Must not construct content without a parent scope") })
        try {
            val message = UIUtil.findComponentsOfType(panel, JTextArea::class.java).single()
            assertTrue(message.text.contains("Project service unavailable"))
            assertTrue(retry(panel).isEnabled)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testCancellationAndFatalErrorsPropagateAfterCancellingTheAttempt() {
        for (failure in listOf(
            CancellationException("Cancelled"), ProcessCanceledException(), OutOfMemoryError("Out of memory")
        )) {
            val parent = CoroutineScope(SupervisorJob())
            try {
                val thrown = Assert.assertThrows(failure.javaClass) {
                    ToolWindowPanel<TestContent>("InfoView", { parent }, { scope ->
                        Job(scope.coroutineContext[Job])
                        throw failure
                    })
                }
                assertSame(failure, thrown)
                assertFalse(parent.coroutineContext[Job]!!.children.any())
                assertTrue(parent.coroutineContext[Job]!!.isActive)
            } finally {
                parent.cancel()
            }
        }
    }

    private fun retry(panel: JPanel) = UIUtil.findComponentsOfType(panel, JButton::class.java).single()

    private class TestContent : JPanel(), Disposable {
        var focus = JButton("Initial focus target")
        var disposals = 0
        override fun dispose() { disposals++ }
    }
}

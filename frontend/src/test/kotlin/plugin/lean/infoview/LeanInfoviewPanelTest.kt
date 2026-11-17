package plugin.lean.infoview

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert
import javax.swing.JButton
import javax.swing.JTextArea

class LeanInfoviewPanelTest : BasePlatformTestCase() {
    fun testBrowserStartupFailuresKeepThePanelUsableAndReleasePartialResources() {
        for (failure in listOf(
            IllegalStateException("CefApp is terminated"), UnsatisfiedLinkError("Native browser library is unavailable")
        )) {
            val parent = CoroutineScope(SupervisorJob())
            var disposed = 0
            val panel = LeanInfoviewPanel(project, parent) { lifetime ->
                Disposer.register(lifetime, Disposable { disposed++ })
                throw failure
            }
            try {
                assertEquals(1, disposed)
                assertTrue(status(panel).text.contains("embedded browser could not start"))
                assertTrue(status(panel).isVisible)
                assertTrue(status(panel).parent.isVisible)
                assertSame(status(panel), panel.focusComponent)
                assertTrue(panel.canReload)
                assertFalse(panel.canRequestAction)
                assertTrue(UIUtil.findComponentsOfType(panel, JButton::class.java).single().isEnabled)
            } finally {
                Disposer.dispose(panel)
                parent.cancel()
            }
            assertEquals(1, disposed)
        }
    }

    fun testReloadRetriesFailedBrowserCreationAndDisposalStopsRetries() {
        val parent = CoroutineScope(SupervisorJob())
        var attempts = 0
        var disposed = 0
        val panel = LeanInfoviewPanel(project, parent) { lifetime ->
            attempts++
            Disposer.register(lifetime, Disposable { disposed++ })
            if (attempts == 1) throw IllegalStateException("Browser runtime stopped")
            null
        }
        try {
            assertEquals(1, attempts)
            panel.reload()
            assertEquals(2, attempts)
            assertEquals(2, disposed)
            assertTrue(status(panel).text.contains("requires a JetBrains Runtime with JCEF"))
            assertFalse(panel.canRequestAction)
            Disposer.dispose(panel)
            assertFalse(panel.canReload)
            panel.reload()
            assertEquals(2, attempts)
            assertEquals(2, disposed)
        } finally {
            Disposer.dispose(panel)
            parent.cancel()
        }
    }

    fun testUnsupportedRuntimeHasAnExplanationWithoutCreatingABrowser() {
        val parent = CoroutineScope(SupervisorJob())
        val panel = LeanInfoviewPanel(project, parent) { null }
        try {
            assertTrue(status(panel).text.contains("requires a JetBrains Runtime with JCEF"))
            assertTrue(status(panel).parent.isVisible)
            assertFalse(panel.canRequestAction)
        } finally {
            Disposer.dispose(panel)
            parent.cancel()
        }
    }

    fun testCancellationPropagatesAndReleasesResourcesAndChildScopes() {
        for (failure in listOf(CancellationException("Cancelled"), ProcessCanceledException())) {
            val parentJob = SupervisorJob()
            val parent = CoroutineScope(parentJob)
            var disposed = 0
            try {
                val thrown = Assert.assertThrows(failure.javaClass) {
                    LeanInfoviewPanel(project, parent) { lifetime ->
                        Disposer.register(lifetime, Disposable { disposed++ })
                        throw failure
                    }
                }
                assertSame(failure, thrown)
                assertEquals(1, disposed)
                assertFalse(parentJob.children.any())
                assertTrue(parentJob.isActive)
            } finally {
                parent.cancel()
            }
        }
    }

    private fun status(panel: LeanInfoviewPanel) = UIUtil.findComponentsOfType(panel, JTextArea::class.java).single {
        it.accessibleContext.accessibleName == "InfoView connection status"
    }
}

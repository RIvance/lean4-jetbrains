package plugin.lean.toolchain

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ComponentUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import kotlinx.coroutines.CompletableDeferred
import plugin.lean.tooling.LeanRelease
import plugin.lean.tooling.LeanReleaseChannel
import java.io.IOException
import javax.swing.JComboBox
import javax.swing.SwingUtilities

class LeanToolchainInstallDialogTest : BasePlatformTestCase() {
    private val stable = LeanRelease(LeanReleaseChannel.STABLE, "v4.30.0")
    private val candidate = LeanRelease(LeanReleaseChannel.BETA, "v4.31.0-rc1")
    private val nightly = LeanRelease(LeanReleaseChannel.NIGHTLY, "nightly-2026-04-01")

    fun testRequestedVersionIsFirstAndInstalledVersionsCannotBeInstalledAgain() {
        val dialog = LeanToolchainInstallDialog(project, null, setOf(stable.toolchain()), candidate.toolchain()) {
            listOf(stable, candidate, nightly)
        }
        withDialog(dialog) { list, tabs ->
            waitFor("Published versions did not load") { list.model.size == 6 }
            assertEquals(candidate.toolchain(), dialog.selectedToolchain)
            assertTrue(dialog.isOKActionEnabled)
            val channels = UIUtil.findComponentsOfType(tabs, JComboBox::class.java).single()
            val search = UIUtil.findComponentsOfType(tabs, SearchTextField::class.java).single()
            channels.selectedItem = LeanReleaseChannel.STABLE
            search.text = stable.name()
            assertEquals(1, list.model.size)
            assertNull(dialog.selectedToolchain)
            assertFalse(dialog.isOKActionEnabled)
            channels.selectedItem = LeanReleaseChannel.NIGHTLY
            search.text = nightly.name()
            assertEquals(nightly.toolchain(), dialog.selectedToolchain)
            assertTrue(dialog.isOKActionEnabled)
        }
    }

    fun testFailedCatalogCanBeRetriedAndCustomInstallsAreExplicit() {
        var attempts = 0
        val dialog = LeanToolchainInstallDialog(project, null, emptySet(), null) {
            if (++attempts == 1) throw IOException("Release service unavailable")
            listOf(stable)
        }
        withDialog(dialog) { list, tabs ->
            val retry = UIUtil.findComponentsOfType(tabs, ActionLink::class.java).single {
                it.text == "Retry"
            }
            waitFor("Catalog failure did not offer retry") { retry.isVisible && list.model.size == 3 }
            assertEquals("leanprover/lean4:stable", dialog.selectedToolchain)
            retry.doClick()
            waitFor("Retry did not load the release list") { list.model.size == 4 }
            assertEquals("leanprover/lean4:stable", dialog.selectedToolchain)
            UIUtil.findComponentsOfType(tabs, SearchTextField::class.java).single().text = stable.name()
            assertEquals(stable.toolchain(), dialog.selectedToolchain)
            tabs.selectedIndex = 1
            assertFalse(dialog.isOKActionEnabled)
            UIUtil.findComponentsOfType(tabs, JBTextField::class.java).single {
                it.accessibleContext.accessibleName == "Custom toolchain name"
            }.text = " example/lean4:my-build "
            assertTrue(dialog.isOKActionEnabled)
            assertEquals("example/lean4:my-build", dialog.selectedToolchain)
        }
    }

    fun testTypingAVersionFindsTheMatchingReleaseAndKeepsInstalledVersionsMarked() {
        val older = LeanRelease(LeanReleaseChannel.STABLE, "v4.0.0")
        val dialog = LeanToolchainInstallDialog(project, null, setOf(older.toolchain()), null) {
            listOf(stable, older, candidate)
        }
        withDialog(dialog) { list, tabs ->
            waitFor("Release list did not load") { list.model.size == 6 }
            val search = UIUtil.findComponentsOfType(tabs, SearchTextField::class.java).single()
            search.text = older.name()
            assertEquals(1, list.model.size)
            assertFalse(dialog.isOKActionEnabled)
            search.text = candidate.name()
            assertEquals(candidate.toolchain(), dialog.selectedToolchain)
            search.text = "unpublished"
            assertEquals(0, list.model.size)
            assertFalse(dialog.isOKActionEnabled)
        }
    }

    fun testTrackingChannelRemainsSelectedWhenTheCatalogArrivesWhileOnTheCustomTab() {
        val catalog = CompletableDeferred<List<LeanRelease>>()
        var requests = 0
        val dialog = LeanToolchainInstallDialog(project, null, emptySet(), null) {
            requests++
            catalog.await()
        }
        withDialog(dialog) { list, tabs ->
            val channels = UIUtil.findComponentsOfType(tabs, JComboBox::class.java).single()
            channels.selectedItem = LeanReleaseChannel.NIGHTLY
            val tracking = "leanprover/lean4-nightly:nightly"
            assertEquals(tracking, dialog.selectedToolchain)
            tabs.selectedIndex = 1
            assertFalse(dialog.isOKActionEnabled)
            catalog.complete(listOf(stable, candidate, nightly))
            waitFor("Catalog did not load while the custom tab was open") { list.model.size == 2 }
            tabs.selectedIndex = 0
            assertEquals(tracking, dialog.selectedToolchain)
            assertEquals(1, requests)
        }
    }

    private fun withDialog(dialog: LeanToolchainInstallDialog, action: (JBList<*>, JBTabbedPane) -> Unit) {
        val tabs = SwingUtilities.getAncestorOfClass(JBTabbedPane::class.java, dialog.preferredFocusedComponent)
            as JBTabbedPane
        val list = UIUtil.findComponentsOfType(tabs, JBList::class.java).single()
        withForcedRespectIsShowingClientProperty {
            ComponentUtil.forceMarkAsShowing(tabs, true)
            try {
                action(list, tabs)
            } finally {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                ComponentUtil.forceMarkAsShowing(tabs, false)
            }
        }
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)
    }
}

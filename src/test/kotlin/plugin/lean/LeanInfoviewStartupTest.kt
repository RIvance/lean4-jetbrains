package plugin.lean

import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.runBlocking
import plugin.lean.language.LeanFileType
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanProjectConfigurable
import plugin.lean.settings.LeanSettings

/** Exercises editor/window events through platform APIs; no browser, Lean process, or input emulation. */
class LeanInfoviewStartupTest : HeavyPlatformTestCase() {
    private lateinit var window: RecordingWindow
    private var registered = false

    override fun setUp() {
        super.setUp()
        project.service<LeanSettings>().update(LeanConfiguration(enabled = false))
        window = RecordingWindow()
        val manager = object : ToolWindowHeadlessManagerImpl(project) {
            override fun getToolWindow(id: String?): ToolWindow? = if (id == "Lean" && registered) window else null
        }
        project.replaceService(ToolWindowManager::class.java, manager, testRootDisposable)
        val activityClass = Class.forName(
            "plugin.lean.infoview.LeanInfoviewStartupActivity", true, LeanProjectConfigurable::class.java.classLoader
        ).asSubclass(ProjectActivity::class.java)
        val activity = ExtensionPointName.create<ProjectActivity>("com.intellij.postStartupActivity")
            .findExtensionOrFail(activityClass)
        runBlocking { activity.execute(project) }
    }

    fun testDelayedRegistrationOpensForLeanWithoutActivatingAndRespectsDismissal() {
        select("Notes.txt", false)
        dispatch()
        assertEquals(0, window.shows)
        select("Proof.lean", true)
        dispatch()
        assertEquals(0, window.shows)
        registerWindow()
        PlatformTestUtil.waitWithEventsDispatching("InfoView did not open", { window.shows == 1 }, 10)
        assertEquals(0, window.activations)
        window.hide(null)
        select("Another.lean", true)
        dispatch()
        assertEquals(1, window.shows)
        assertFalse(window.isVisible)
    }

    fun testDisabledPreferenceCanBeEnabledForAnAlreadySelectedLeanFile() {
        val configurable = LeanProjectConfigurable(project)
        try {
            val component = configurable.createComponent()
            configurable.reset()
            val autoOpen = UIUtil.findComponentsOfType(component, JBCheckBox::class.java).single {
                it.text == "Open InfoView automatically when selecting a Lean file"
            }
            autoOpen.isSelected = false
            configurable.apply()
            registerWindow()
            select("Proof.lean", true)
            dispatch()
            assertEquals(0, window.shows)
            autoOpen.isSelected = true
            configurable.apply()
            PlatformTestUtil.waitWithEventsDispatching("InfoView did not open", { window.shows == 1 }, 10)
            assertEquals(0, window.activations)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testManuallyShownAndDismissedWindowIsNotReopenedBySelectingLean() {
        registerWindow()
        window.show(null)
        dispatch()
        window.hide(null)
        select("Proof.lean", true)
        dispatch()
        assertEquals(1, window.shows)
        assertFalse(window.isVisible)
    }

    private fun select(name: String, lean: Boolean) {
        val file = if (lean) {
            LightVirtualFile(name, LeanFileType, "example : True := by trivial")
        } else {
            LightVirtualFile(name, "Notes")
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun registerWindow() {
        registered = true
        project.messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowsRegistered(
            listOf("Lean"), ToolWindowManager.getInstance(project)
        )
    }

    private fun dispatch() = UIUtil.dispatchAllInvocationEvents()

    private inner class RecordingWindow : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
        var shows = 0
        var activations = 0
        private var visible = false

        override fun getId() = "Lean"
        override fun isVisible() = visible
        override fun show(runnable: Runnable?) {
            shows++
            visible = true
            project.messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown(this)
            runnable?.run()
        }

        override fun hide(runnable: Runnable?) {
            visible = false
            runnable?.run()
        }

        override fun activate(runnable: Runnable?, autoFocusContents: Boolean, forced: Boolean) {
            activations++
            show(runnable)
        }
    }
}

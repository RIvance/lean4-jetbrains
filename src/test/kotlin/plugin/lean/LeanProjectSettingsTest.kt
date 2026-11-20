package plugin.lean

import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ComponentUtil
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.forceRespectIsShowingClientProperty
import plugin.lean.rpc.LeanConfiguration
import plugin.lean.settings.LeanProjectConfigurable
import plugin.lean.settings.LeanSettings
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JButton
import javax.swing.JTable

class LeanProjectSettingsTest : BasePlatformTestCase() {
    private val settings: LeanSettings get() = project.service()

    override fun setUp() {
        super.setUp()
        settings.update(LeanConfiguration(elanExecutable = "/saved/elan", enabled = false))
    }

    fun testApplyNormalizesAndPersistsTheDraftAndResetDiscardsEdits() {
        withEditor { configurable, component, executable ->
            assertEquals("/saved/elan", executable.text)
            assertFalse(configurable.isModified)
            executable.text = "  /configured/elan  "
            val toolchain = UIUtil.findComponentsOfType(component, JComboBox::class.java).single()
            toolchain.editor.item = "  local-build  "
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals(LeanConfiguration("/configured/elan", "local-build", false), settings.configuration)
            assertEquals("/configured/elan", executable.text)
            assertFalse(configurable.isModified)
            executable.text = "/discarded/elan"
            configurable.reset()
            waitForLoad(executable)
            assertEquals("/configured/elan", executable.text)
            assertFalse(configurable.isModified)
        }
    }

    fun testFailedValidationPreservesBothTheSavedConfigurationAndTheDraft() {
        withEditor { configurable, component, executable ->
            val saved = settings.configuration
            val missing = Path.of(myFixture.tempDirFixture.tempDirPath, "missing-elan").toString()
            executable.text = missing
            UIUtil.findComponentsOfType(component, JBCheckBox::class.java).single {
                it.text == "Enable Lean language server"
            }.isSelected = true
            val autoOpen = UIUtil.findComponentsOfType(component, JBCheckBox::class.java).single {
                it.text == "Open InfoView automatically when selecting a Lean file"
            }
            val original = autoOpen.isSelected
            autoOpen.isSelected = !original
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals(saved, settings.configuration)
            assertEquals(missing, executable.text)
            assertTrue(configurable.isModified)
            withEditor { _, reopened, _ ->
                val persisted = UIUtil.findComponentsOfType(reopened, JBCheckBox::class.java).single {
                    it.text == autoOpen.text
                }
                assertEquals(original, persisted.isSelected)
            }
        }
    }

    fun testAStaleEditorCannotOverwriteSettingsFromAnotherEditor() {
        withEditor { first, _, firstExecutable ->
            withEditor { second, _, secondExecutable ->
                firstExecutable.text = "/first/elan"
                secondExecutable.text = "/second/elan"
                first.apply()
                assertThrows(ConfigurationException::class.java) { second.apply() }
                assertEquals("/first/elan", settings.configuration.elanExecutable)
                assertEquals("/second/elan", secondExecutable.text)
                assertTrue(second.isModified)
                second.reset()
                waitForLoad(secondExecutable)
                assertEquals("/first/elan", secondExecutable.text)
                assertFalse(second.isModified)
                secondExecutable.text = "/resolved/elan"
                second.apply()
                assertEquals("/resolved/elan", settings.configuration.elanExecutable)
            }
        }
    }

    fun testClosingSettingsDiscardsAnUnappliedDraft() {
        val saved = settings.configuration
        withEditor { configurable, _, executable ->
            executable.text = "/unsaved/elan"
            assertTrue(configurable.isModified)
        }
        assertEquals(saved, settings.configuration)
    }

    fun testSelectionPreviewsTheNewVersionAndItsSourceWithoutChangingSavedSettings() {
        withTestElan { directory, saved ->
            withEditor { configurable, component, _ ->
                val details = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single()
                val toolchain = UIUtil.findComponentsOfType(component, JComboBox::class.java).single()
                val pin = directory.resolve("lean-toolchain").toString()
                assertTrue(details.text, details.text.contains(pin))
                LaterInvocator.enterModal(component)
                try {
                    toolchain.selectedItem = "linked"
                    PlatformTestUtil.waitWithEventsDispatching("Selected toolchain was not previewed", {
                        details.text.startsWith("Selected toolchain · linked") && details.text.contains("explicitly")
                    }, 10)
                    assertEquals(saved, settings.configuration)
                    assertTrue(configurable.isModified)
                    val field = toolchain.editor.editorComponent as JBTextField
                    field.text = " custom-channel "
                    field.caretPosition = 3
                    PlatformTestUtil.waitWithEventsDispatching("Typed toolchain was not previewed", {
                        details.text.contains("custom-channel") && details.text.contains("not installed")
                    }, 10)
                    assertEquals(" custom-channel ", field.text)
                    assertEquals(3, field.caretPosition)
                    toolchain.selectedItem = ""
                    PlatformTestUtil.waitWithEventsDispatching("Automatic did not restore the pinned version", {
                        details.text.startsWith("Selected toolchain · release") && details.text.contains(pin)
                    }, 10)
                    assertEquals(saved, settings.configuration)
                    assertFalse(configurable.isModified)
                } finally {
                    LaterInvocator.leaveModal(component)
                }
            }
        }
    }

    fun testServerArgumentsAndEnvironmentApplyAndResetThroughNativeSettingsControls() {
        settings.update(settings.configuration.copy(environment = mapOf("LEAN_SAMPLE" to "saved")))
        withEditor { configurable, component, executable ->
            val arguments = UIUtil.findComponentsOfType(component, RawCommandLineEditor::class.java).single()
            val environment = environmentTable(component)
            arguments.text = "-j 2 \"a value with spaces\" \"\""
            environment.setValueAt("\$VALUE\$; a path with spaces", 0, 1)
            assertTrue(configurable.isModified)
            configurable.apply()
            val saved = settings.configuration
            assertEquals(listOf("-j", "2", "a value with spaces", ""), saved.serverArguments)
            assertEquals("\$VALUE\$; a path with spaces", saved.environment["LEAN_SAMPLE"])
            assertFalse(configurable.isModified)
            arguments.text = "discarded"
            environment.setValueAt("discarded", 0, 1)
            configurable.reset()
            waitForLoad(executable)
            assertFalse(configurable.isModified)
            assertEquals(saved.environment["LEAN_SAMPLE"], environment.getValueAt(0, 1))
        }
    }

    fun testCancellingAnActiveDownloadRestoresTheDraftAndARetryUsesTheOriginalProjectPin() {
        withTestElan("custom-channel") { directory, saved ->
            withEditor { configurable, component, _ ->
                interceptToolchainInstaller(testRootDisposable) { dialog ->
                    assertTrue(dialog.isOKActionEnabled)
                    dialog.close(DialogWrapper.OK_EXIT_CODE)
                }
                val buttons = UIUtil.findComponentsOfType(component, JButton::class.java)
                val install = buttons.single { it.text == "Install Toolchain" }
                val cancel = buttons.single { it.text == "Cancel Operation" }
                val details = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single()
                val toolchain = UIUtil.findComponentsOfType(component, JComboBox::class.java).single()
                val installed = directory.resolve("installed")
                val pause = Files.createFile(directory.resolve("pause-install"))
                install.doClick()
                PlatformTestUtil.waitWithEventsDispatching("The host did not start the download", {
                    Files.exists(directory.resolve("install-started")) && cancel.isVisible
                }, 10)
                cancel.doClick()
                PlatformTestUtil.waitWithEventsDispatching("Cancelled download did not release the controls", {
                    install.isEnabled && !cancel.isVisible
                }, 10)
                assertFalse(Files.exists(installed))
                assertEquals("", toolchain.editor.item)
                Files.delete(pause)
                install.doClick()
                PlatformTestUtil.waitWithEventsDispatching("Retry did not refresh the installed project pin", {
                    install.isEnabled && details.text.startsWith("Selected toolchain · custom-channel")
                }, 10)
                assertEquals("custom-channel", Files.readString(installed).trim())
                assertEquals(saved, settings.configuration)
                assertEquals("", toolchain.editor.item)
                assertFalse(configurable.isModified)
            }
        }
    }

    fun testInvalidEnvironmentDraftsCannotReplaceSavedSettings() {
        settings.update(settings.configuration.copy(environment = mapOf("FIRST" to "one", "SECOND" to "two")))
        withEditor { configurable, component, executable ->
            val saved = settings.configuration
            val environment = environmentTable(component)
            for (name in listOf("FIRST", "INVALID=NAME")) {
                environment.setValueAt(name, 1, 0)
                assertTrue(configurable.isModified)
                assertThrows(ConfigurationException::class.java) { configurable.apply() }
                assertEquals(saved, settings.configuration)
                assertEquals(name, environment.getValueAt(1, 0))
                configurable.reset()
                waitForLoad(executable)
            }
        }
    }

    fun testFrontendPreferencesApplyAndResetWithoutValidatingOrChangingTheToolchain() {
        val missing = Path.of(myFixture.tempDirFixture.tempDirPath, "missing-elan").toString()
        settings.update(LeanConfiguration(elanExecutable = missing))
        val saved = settings.configuration
        withEditor { configurable, component, _ ->
            val labels = setOf(
                "Open InfoView automatically when selecting a Lean file", "Fold proofs when opening a Lean file"
            )
            val preferences = UIUtil.findComponentsOfType(component, JBCheckBox::class.java).filter { it.text in labels }
            assertEquals(labels.size, preferences.size)
            for (preference in preferences) {
                val original = preference.isSelected
                try {
                    preference.isSelected = !original
                    assertTrue(configurable.isModified)
                    configurable.apply()
                    assertEquals(saved, settings.configuration)
                    assertFalse(configurable.isModified)
                    preference.isSelected = original
                    configurable.reset()
                    assertEquals(!original, preference.isSelected)
                    withEditor { reopened, reopenedComponent, _ ->
                        val persisted = UIUtil.findComponentsOfType(reopenedComponent, JBCheckBox::class.java).single {
                            it.text == preference.text
                        }
                        assertEquals(!original, persisted.isSelected)
                        assertFalse(reopened.isModified)
                    }
                } finally {
                    preference.isSelected = original
                    configurable.apply()
                }
            }
        }
    }

    fun testHostOperationFailuresRestoreControlsAndPreserveTheDraftWhileSettingsIsModal() {
        withEditor { configurable, component, executable ->
            val saved = settings.configuration
            val details = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single()
            assertTrue(details.text, details.text.contains(saved.elanExecutable))
            val missing = Path.of(myFixture.tempDirFixture.tempDirPath, "missing-elan").toString()
            executable.text = missing
            val toolchain = UIUtil.findComponentsOfType(component, JComboBox::class.java).single()
            assertEquals("", toolchain.editor.item)
            val buttons = UIUtil.findComponentsOfType(component, JButton::class.java)
            LaterInvocator.enterModal(component)
            try {
                for (label in listOf("Install Toolchain", "Inspect")) {
                    val button = buttons.single { it.text == label }
                    button.doClick()
                    PlatformTestUtil.waitWithEventsDispatching("$label did not finish", { button.isEnabled }, 10)
                    assertTrue(details.text, details.text.contains(missing))
                    assertEquals(saved, settings.configuration)
                    assertEquals("", toolchain.editor.item)
                    assertEquals(missing, executable.text)
                    assertTrue(configurable.isModified)
                }
            } finally {
                LaterInvocator.leaveModal(component)
            }
        }
    }

    fun testRepeatedResetAndDisposalDoNotLeaveConfigurationRequestsRunning() {
        val configurable = configurable()
        try {
            val component = configurable.createComponent()
            withShowingComponent(component) {
                val executable = executableField(component)
                repeat(3) { configurable.reset() }
                waitForLoad(executable)
                assertEquals("/saved/elan", executable.text)
                configurable.reset()
            }
        } finally {
            configurable.disposeUIResources()
        }
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("/saved/elan", settings.configuration.elanExecutable)
    }

    private fun withTestElan(defaultName: String = "release", action: (Path, LeanConfiguration) -> Unit) {
        val directory = Files.createTempDirectory("lean settings ")
        // Light projects can exist only in the test VFS. Elan needs a physical working directory.
        val projectDirectory = Path.of(checkNotNull(project.basePath))
        val created = Files.notExists(projectDirectory)
        if (created) Files.createDirectories(projectDirectory)
        try {
            val elan = createTestElan(directory, defaultName)
            settings.update(settings.configuration.copy(elanExecutable = elan.toString()))
            action(directory, settings.configuration)
        } finally {
            FileUtil.delete(directory.toFile())
            if (created) Files.deleteIfExists(projectDirectory)
        }
    }

    private fun withEditor(action: (LeanProjectConfigurable, JComponent, JBTextField) -> Unit) {
        val configurable = configurable()
        try {
            val component = configurable.createComponent()
            val executable = executableField(component)
            configurable.reset()
            withShowingComponent(component) {
                waitForLoad(executable)
                action(configurable, component, executable)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun waitForLoad(executable: JBTextField) {
        PlatformTestUtil.waitWithEventsDispatching("Lean settings did not load", { executable.isEnabled }, 10)
    }

    private fun executableField(component: JComponent): JBTextField =
        UIUtil.findComponentsOfType(component, JBTextField::class.java).single {
            it.accessibleContext.accessibleName == "Elan executable"
        }

    private fun environmentTable(component: JComponent): JTable =
        UIUtil.findComponentsOfType(component, JTable::class.java).single {
            it.accessibleContext.accessibleName == "Lean project environment"
        }

    private fun configurable(): LeanProjectConfigurable {
        val registration = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).single {
            it.id == "plugin.lean.project"
        }
        assertEquals("language", registration.parentId)
        return registration.createConfigurable() as LeanProjectConfigurable
    }
}

internal fun withShowingComponent(component: JComponent, action: () -> Unit) {
    val previous = forceRespectIsShowingClientProperty
    forceRespectIsShowingClientProperty = true
    ComponentUtil.forceMarkAsShowing(component, true)
    try {
        action()
    } finally {
        ComponentUtil.forceMarkAsShowing(component, false)
        forceRespectIsShowingClientProperty = previous
    }
}

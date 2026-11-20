package plugin.lean

import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import plugin.lean.project.LeanProjectModuleBuilder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel

class LeanProjectWizardTest : BasePlatformTestCase() {
    private fun withWizard(action: (Path, JComponent) -> Unit) {
        val directory = Files.createTempDirectory("lean wizard ")
        val builder = ModuleBuilder.getAllBuilders().filterIsInstance<LeanProjectModuleBuilder>().single()
        try {
            val context = WizardContext(null, testRootDisposable)
            context.setProjectFileDirectory(directory, true)
            val bridge = builder.getCustomOptionsStep(context, testRootDisposable)
            val base = checkNotNull((bridge as NewProjectWizardStep).data.getUserData(NewProjectWizardBaseData.KEY))
            base.path = directory.toString()
            base.name = "Example"
            withShowingComponent(bridge.component) { action(directory, bridge.component) }
        } finally {
            builder.cleanup()
            FileUtil.delete(directory.toFile())
        }
    }

    fun testWizardDiscoversInstalledToolchainsAndReadsLakeHelpForTheSelectedToolchain() {
        withWizard { directory, component ->
            val labels = UIUtil.findComponentsOfType(component, JLabel::class.java)
            val executable = labels.single { it.text == "Elan executable:" }.labelFor as JBTextField
            val toolchains = labels.single { it.text == "Lean toolchain:" }.labelFor as JComboBox<*>
            val buttons = UIUtil.findComponentsOfType(component, JButton::class.java)
            val details = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single()
            executable.text = createTestElan(directory).toString()
            PlatformTestUtil.waitWithEventsDispatching("Installed toolchains did not load", {
                toolchains.itemCount == 3
            }, 10)
            val choices = (0 until toolchains.itemCount).map(toolchains::getItemAt)
            assertEquals(setOf("", "release", "linked"), choices.toSet())
            assertEquals("", toolchains.editor.item)
            assertEquals("Automatic (release)", (toolchains.editor.editorComponent as JBTextField).emptyText.text)
            assertTrue(details.text, details.text.contains(directory.resolve("lean-toolchain").toString()))

            toolchains.selectedItem = "linked"
            buttons.single { it.text == "Template Help" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Did not receive the selected Lake's help", {
                details.text == "run -- linked lake help init"
            }, 10)
            buttons.single { it.text == "Inspect Toolchains" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Inspection did not show the active selection", {
                details.text.lineSequence().first() == "Selected toolchain · linked"
            }, 10)
            assertEquals("linked", toolchains.editor.item)

            toolchains.editor.item = "custom-channel"
            PlatformTestUtil.waitWithEventsDispatching("Uninstalled selection was not reported", {
                details.text.contains("custom-channel") && details.text.contains("not installed")
            }, 10)
            assertEquals(3, toolchains.itemCount)
            assertEquals("custom-channel", toolchains.editor.item)
            val missing = directory.resolve("missing-elan").toString()
            executable.text = missing
            PlatformTestUtil.waitWithEventsDispatching("Changed elan path did not trigger discovery", {
                details.text.contains(missing)
            }, 10)
            assertEquals(1, toolchains.itemCount)
            assertEquals("custom-channel", toolchains.editor.item)
        }
    }

    fun testInstallingTheRequestedVersionInTheWizardSelectsItOnlyAfterSuccess() {
        withWizard { directory, component ->
            val labels = UIUtil.findComponentsOfType(component, JLabel::class.java)
            val executable = labels.single { it.text == "Elan executable:" }.labelFor as JBTextField
            val toolchains = labels.single { it.text == "Lean toolchain:" }.labelFor as JComboBox<*>
            val install = UIUtil.findComponentsOfType(component, JButton::class.java).single {
                it.text == "Install Toolchain"
            }
            val details = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single()
            executable.text = createTestElan(directory, defaultName = "custom-channel").toString()
            var confirm = false
            var dialogs = 0
            interceptToolchainInstaller(testRootDisposable) { dialog ->
                dialogs++
                assertTrue("The requested version should be ready to install", dialog.isOKActionEnabled)
                dialog.close(if (confirm) DialogWrapper.OK_EXIT_CODE else DialogWrapper.CANCEL_EXIT_CODE)
            }

            install.doClick()
            assertEquals(1, dialogs)
            assertEquals("", toolchains.editor.item)
            val installed = directory.resolve("installed")
            assertFalse(Files.exists(installed))

            confirm = true
            val refuse = Files.createFile(directory.resolve("refuse-install"))
            install.doClick()
            assertTrue(details.text, details.text.contains("Download failed"))
            assertEquals("", toolchains.editor.item)
            assertFalse(Files.exists(installed))

            Files.delete(refuse)
            install.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Installed version was not selected", {
                toolchains.itemCount == 4 && details.text.startsWith("Selected toolchain · custom-channel")
            }, 10)
            assertEquals("custom-channel", Files.readString(installed).trim())
            assertEquals("custom-channel", toolchains.editor.item)
            assertFalse(Files.exists(directory.resolve("Example")))
        }
    }

    fun testRegisteredProjectWizardRejectsAMissingElanBeforeCreatingFiles() {
        val builder = ModuleBuilder.getAllBuilders().filterIsInstance<LeanProjectModuleBuilder>().single()
        assertEquals("Lean 4", builder.presentableName)
        assertFalse(builder.canCreateModule())
        val context = WizardContext(null, testRootDisposable)
        context.setProjectFileDirectory(Path.of(myFixture.tempDirFixture.tempDirPath), true)
        val bridge = builder.getCustomOptionsStep(context, testRootDisposable)
        try {
            val step = bridge as NewProjectWizardStep
            val base = checkNotNull(step.data.getUserData(NewProjectWizardBaseData.KEY))
            base.path = myFixture.tempDirFixture.tempDirPath
            base.name = "NewLeanProject"
            val component = bridge.component
            val label = UIUtil.findComponentsOfType(component, JLabel::class.java).single {
                it.text == "Elan executable:"
            }
            val executable = label.labelFor as JBTextField
            val missing = Path.of(base.path, "missing-elan").toString()
            executable.text = missing
            assertFalse(bridge.validate())
            assertEquals(missing, executable.text)
            assertFalse(Files.exists(Path.of(base.path, base.name)))
        } finally {
            builder.cleanup()
        }
    }
}

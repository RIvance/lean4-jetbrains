package plugin.lean

import com.google.gson.Gson
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.util.SystemInfo
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
import javax.swing.JLabel

class LeanProjectWizardTest : BasePlatformTestCase() {
    fun testWizardDiscoversInstalledToolchainsAndReadsLakeHelpForTheSelectedToolchain() {
        val directory = Files.createTempDirectory("lean wizard ")
        val builder = ModuleBuilder.getAllBuilders().filterIsInstance<LeanProjectModuleBuilder>().single()
        try {
            val context = WizardContext(null, testRootDisposable)
            context.setProjectFileDirectory(directory, true)
            val bridge = builder.getCustomOptionsStep(context, testRootDisposable)
            val base = checkNotNull((bridge as NewProjectWizardStep).data.getUserData(NewProjectWizardBaseData.KEY))
            base.path = directory.toString()
            base.name = "Example"
            val component = bridge.component
            val labels = UIUtil.findComponentsOfType(component, JLabel::class.java)
            val executable = labels.single { it.text == "Elan executable:" }.labelFor as JBTextField
            val toolchains = labels.single { it.text == "Lean toolchain:" }.labelFor as JComboBox<*>
            val buttons = UIUtil.findComponentsOfType(component, JButton::class.java)
            val details = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single()
            executable.text = elanFixture(directory).toString()
            withShowingComponent(component) {
                PlatformTestUtil.waitWithEventsDispatching("Installed toolchains did not load", {
                    toolchains.itemCount == 3
                }, 10)
                val choices = (0 until toolchains.itemCount).map(toolchains::getItemAt)
                assertEquals(setOf("", "release", "linked"), choices.toSet())
                assertEquals("", toolchains.editor.item)
                assertEquals("Automatic (release)", (toolchains.editor.editorComponent as JBTextField).emptyText.text)

                toolchains.selectedItem = "linked"
                buttons.single { it.text == "Template Help" }.doClick()
                PlatformTestUtil.waitWithEventsDispatching("Did not receive the selected Lake's help", {
                    details.text == "run -- linked lake help init"
                }, 10)
                buttons.single { it.text == "Inspect Toolchains" }.doClick()
                PlatformTestUtil.waitWithEventsDispatching("Inspection did not show the active selection", {
                    details.text.endsWith("\nlinked")
                }, 10)
                assertEquals("linked", toolchains.editor.item)

                toolchains.editor.item = "custom-channel"
                buttons.single { it.text == "Inspect Toolchains" }.doClick()
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
        } finally {
            builder.cleanup()
            FileUtil.delete(directory.toFile())
        }
    }

    private fun elanFixture(directory: Path): Path {
        val installed = listOf("release", "linked").map { mapOf("resolved_name" to it, "path" to directory.toString()) }
        for (name in listOf("release", "linked", "custom-channel")) {
            val toolchains = mapOf("installed" to installed, "resolved_active" to mapOf("live" to mapOf("Ok" to name)))
            val state = mapOf("elan_version" to mapOf("current" to "4.2.1"), "toolchains" to toolchains)
            Files.writeString(directory.resolve("$name.json"), Gson().toJson(state))
        }
        val executable = if (SystemInfo.isWindows) {
            Files.writeString(directory.resolve("elan.cmd"), """
                @echo off
                if not "%1"=="dump-state" (echo %* & exit /b)
                set "name=release"
                if "%ELAN_TOOLCHAIN%"=="linked" set "name=linked"
                if "%ELAN_TOOLCHAIN%"=="custom-channel" set "name=custom-channel"
                type "%~dp0%name%.json"
            """.trimIndent().replace("\n", "\r\n"))
        } else {
            Files.writeString(directory.resolve("elan"), """
                #!/bin/sh
                if [ "$1" != dump-state ]; then echo "$*"; exit; fi
                case "${'$'}ELAN_TOOLCHAIN" in
                    linked|custom-channel) name="${'$'}ELAN_TOOLCHAIN" ;;
                    *) name=release ;;
                esac
                cat "${'$'}{0%/*}/${'$'}name.json"
            """.trimIndent())
        }
        check(SystemInfo.isWindows || executable.toFile().setExecutable(true))
        return executable
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

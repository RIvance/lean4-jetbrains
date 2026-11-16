package plugin.lean

import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import plugin.lean.project.LeanProjectModuleBuilder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JLabel

class LeanProjectWizardTest : BasePlatformTestCase() {
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

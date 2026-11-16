package plugin.lean.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.util.ProgramParametersConfigurator
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import plugin.lean.tooling.LeanWorkspace

class LakeConfigurationProducer : LazyRunConfigurationProducer<LakeRunConfiguration>() {
    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(LakeConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(
        configuration: LakeRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>
    ): Boolean {
        val workspace = workspace(context) ?: return false
        configuration.workingDirectory = workspace.root().toString()
        configuration.programParameters = "build"
        configuration.name = "Lake build (${workspace.root().fileName})"
        return true
    }

    override fun isConfigurationFromContext(
        configuration: LakeRunConfiguration, context: ConfigurationContext
    ): Boolean {
        val workspace = workspace(context) ?: return false
        val directory = ProgramParametersConfigurator().getWorkingDir(configuration, configuration.project, null)
        return directory == workspace.root().toString() && configuration.programParameters.trim() == "build"
    }

    private fun workspace(context: ConfigurationContext): LeanWorkspace? {
        val element = context.psiLocation ?: return null
        val file = if (element is PsiDirectory) element.virtualFile else element.containingFile?.virtualFile
        if (file == null || file.fileSystem.protocol != StandardFileSystems.FILE_PROTOCOL) return null
        if (!file.isDirectory && file.extension != "lean" && file.name != "lakefile.toml") return null
        return LakeProjectTasks.workspace(context.project, file)
    }
}

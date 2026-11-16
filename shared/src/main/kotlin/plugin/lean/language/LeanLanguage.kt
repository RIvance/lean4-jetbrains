package plugin.lean.language

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object LeanLanguage : Language("Lean4")

object LeanIcons {
    @JvmField
    val LEAN: Icon = IconLoader.getIcon("/icons/lean.svg", LeanIcons::class.java)
}

object LeanFileType : LanguageFileType(LeanLanguage) {
    override fun getName() = "Lean 4"
    override fun getDescription() = "Lean source file"
    override fun getDefaultExtension() = "lean"
    override fun getIcon() = LeanIcons.LEAN
}

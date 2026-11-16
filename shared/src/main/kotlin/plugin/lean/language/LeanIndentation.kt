package plugin.lean.language

import com.intellij.application.options.CodeStyle
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.formatting.IndentInfo
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.util.ui.JBUI

class LeanCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = LeanLanguage
    override fun getIndentOptionsEditor() = IndentOptionsEditor(this)
    override fun getCodeSample(settingsType: SettingsType): String? = null

    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions
    ) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.INDENT_SETTINGS) {
            consumer.showStandardOptions("INDENT_SIZE", "TAB_SIZE", "USE_TAB_CHARACTER")
        }
    }

    override fun createConfigurable(
        settings: CodeStyleSettings,
        modelSettings: CodeStyleSettings
    ): CodeStyleConfigurable {
        return object : CodeStyleAbstractConfigurable(settings, modelSettings, LeanLanguage.displayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel {
                return object : CodeStyleAbstractPanel(LeanLanguage, currentSettings, settings) {
                    private val optionsEditor = getIndentOptionsEditor()
                    private val component = optionsEditor.createPanel().apply {
                        border = JBUI.Borders.empty(10)
                        addPanelToWatch(this)
                    }

                    override fun getPanel() = component
                    override fun getFileType() = LeanFileType
                    override fun getRightMargin() = -1
                    override fun getPreviewText(): String? = null
                    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter? = null

                    override fun resetImpl(settings: CodeStyleSettings) {
                        optionsEditor.reset(settings, settings.getIndentOptions(LeanFileType))
                    }

                    override fun apply(settings: CodeStyleSettings) {
                        optionsEditor.apply(settings, settings.getIndentOptions(LeanFileType))
                    }

                    override fun isModified(settings: CodeStyleSettings) =
                        optionsEditor.isModified(settings, settings.getIndentOptions(LeanFileType))
                }
            }
        }
    }
}

/** Continue the previous line's indent, adding one level after a tactic bullet. No Lean syntax is inferred. */
class LeanLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?) = language == LeanLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (!isSuitableFor(language)) return null
        val document = editor.document
        val line = document.getLineNumber(offset)
        if (line == 0) return ""

        val text = document.charsSequence
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        var content = start
        while (content < end && text[content] in " \t") content++

        val bullet = content + 1 < end && text[content] in "·." && text[content + 1] in " \t"
        if (!bullet || editor.highlighter.createIterator(content).tokenType != LeanTokens.SYMBOL) {
            return text.subSequence(start, content).toString()
        }

        val options = CodeStyle.getIndentOptions(project, document)
        val columns = EditorUtil.calcColumnNumber(null, text, start, content, options.TAB_SIZE)
        return IndentInfo(0, columns + options.INDENT_SIZE, 0).generateNewWhiteSpace(options)
    }
}

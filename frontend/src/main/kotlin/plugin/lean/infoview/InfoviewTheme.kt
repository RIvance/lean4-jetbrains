package plugin.lean.infoview

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Color

internal fun infoviewTheme(): JsonObject {
    val scheme = EditorColorsManager.getInstance().globalScheme
    fun color(value: Color) = "#${ColorUtil.toHex(value)}"
    val foreground = color(scheme.defaultForeground)
    val background = color(scheme.defaultBackground)
    val border = color(UIUtil.getBoundsColor())
    val keyword = color(
        scheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD)?.foregroundColor ?: scheme.defaultForeground
    )
    val identifier = color(
        scheme.getAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)?.foregroundColor ?: scheme.defaultForeground
    )
    val properties = mapOf(
        "color-scheme" to if (ColorUtil.isDark(scheme.defaultBackground)) "dark" else "light",
        "--vscode-editor-background" to background,
        "--vscode-editor-foreground" to foreground,
        "--vscode-editor-font-family" to "\"${scheme.editorFontName.replace("\"", "\\\"")}\", monospace",
        "--vscode-editor-font-size" to "${scheme.editorFontSize}px",
        "--vscode-editor-line-height" to scheme.lineSpacing.toString(),
        "--vscode-editorHoverWidget-background" to color(UIUtil.getPanelBackground()),
        "--vscode-editorHoverWidget-foreground" to foreground,
        "--vscode-editorHoverWidget-border" to border,
        "--vscode-dropdown-background" to color(UIUtil.getTextFieldBackground()),
        "--vscode-dropdown-foreground" to foreground,
        "--vscode-dropdown-border" to border,
        "--vscode-button-background" to color(UIUtil.getPanelBackground()),
        "--vscode-button-foreground" to foreground,
        "--vscode-input-background" to background,
        "--vscode-input-foreground" to foreground,
        "--vscode-input-border" to border,
        "--vscode-focusBorder" to color(UIUtil.getFocusedBorderColor()),
        "--vscode-editor-selectionBackground" to color(
            scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR) ?: UIUtil.getListSelectionBackground(true)
        ),
        "--vscode-textLink-foreground" to color(JBColor.namedColor("Link.activeForeground", JBColor.BLUE)),
        "--vscode-descriptionForeground" to color(UIUtil.getContextHelpForeground()),
        "--vscode-lean4-infoView.goalCount" to keyword,
        "--vscode-lean4-infoView.turnstile" to keyword,
        "--vscode-lean4-infoView.caseLabel" to keyword,
        "--vscode-lean4-infoView.hypothesisName" to identifier,
        "--vscode-lean4-infoView.inaccessibleHypothesisName" to color(UIUtil.getContextHelpForeground())
    )
    return JsonObject(properties.mapValues { JsonPrimitive(it.value) })
}

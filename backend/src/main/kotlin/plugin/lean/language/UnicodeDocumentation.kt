package plugin.lean.language

import com.intellij.model.Pointer
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import plugin.lean.settings.UnicodeInputSettings

/** A separate documentation target augments the server's semantic hover without replacing it. */
class LeanUnicodeDocumentation : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (file.language != LeanLanguage) return emptyList()
        val settings = service<UnicodeInputSettings>().configuration
        val text = file.text
        val atCaret = settings.catalog.symbolAt(text, offset)
        // Quick documentation is also useful immediately after typing a symbol, including at the end of the file.
        val found = atCaret.or { settings.catalog.symbolAt(text, offset - 1) }.orElse(null) ?: return emptyList()
        if (found.text().all { it.code < 128 }) return emptyList()
        val aliases = found.abbreviations().map { settings.leader + it.abbreviation() }
        return listOf(UnicodeDocumentation(found.text(), aliases))
    }
}

private class UnicodeDocumentation(
    private val symbol: String, private val aliases: List<String>
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder("Unicode input: $symbol").presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.documentation(computeDocumentationHint())

    override fun computeDocumentationHint(): String {
        val escapedSymbol = StringUtil.escapeXmlEntities(symbol)
        val inputs = aliases.joinToString(", ") { "<code>${StringUtil.escapeXmlEntities(it)}</code>" }
        val definition = "<div class='definition'><pre>$escapedSymbol</pre></div>"
        return definition + "<div class='content'>Type $inputs to enter this symbol.</div>"
    }
}

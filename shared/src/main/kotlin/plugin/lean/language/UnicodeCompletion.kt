package plugin.lean.language

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.AutoCompletionPolicy
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbAware

class LeanUnicodeCompletion : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val configuration = UnicodeInput.configuration
        val start = UnicodeInput.completionStart(parameters.editor, parameters.offset, configuration) ?: return
        val prefix = parameters.editor.document.charsSequence.subSequence(start, parameters.offset).toString()
        val results = result.withPrefixMatcher(AbbreviationPrefix(prefix))
        for (entry in configuration.catalog.matches(prefix.drop(configuration.leader.length))) {
            val element = LookupElementBuilder.create(configuration.leader + entry.abbreviation())
                .withTypeText(entry.replacement().text())
                .withInsertHandler { context, _ ->
                    val caret = context.editor.caretModel.currentCaret
                    UnicodeInput.replace(caret, context.startOffset, context.tailOffset, entry.replacement())
                }
            results.addElement(element.withAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE))
        }
        results.stopHere()
    }

    private class AbbreviationPrefix(prefix: String) : PrefixMatcher(prefix) {
        override fun prefixMatches(element: LookupElement) = prefixMatches(element.lookupString)
        override fun prefixMatches(name: String) = name.startsWith(prefix)
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = AbbreviationPrefix(prefix)
    }
}

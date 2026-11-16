package plugin.lean.editor

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import plugin.lean.language.LeanLanguage
import plugin.lean.lsp.LeanServerHub
import java.io.IOException
import java.util.UUID

/** References remain valid only while their originating document snapshot is retained. */
@Serializable
internal data class LeanInlayActionReference(val id: String, val title: String)

@Serializable
internal data class LeanInlayActionGroup(val actions: List<LeanInlayActionReference>) {
    companion object {
        const val HANDLER_ID = "lean.inlay.actions"
        const val INSERT_PAYLOAD = "lean.inlay.insert"
    }
}

/** Immutable presentation and operations copied from a single server response. */
internal data class LeanInlayHint(
    val offset: Int,
    val parts: List<Part>,
    val tooltip: String?,
    val kind: InlayHintKind?,
    val paddingLeft: Boolean,
    val paddingRight: Boolean
) {
    data class Part(val text: String, val actions: List<Action>)

    data class Action(val reference: LeanInlayActionReference, val operation: Operation) {
        constructor(title: String, operation: Operation) : this(
            LeanInlayActionReference(UUID.randomUUID().toString(), title), operation
        )
    }

    sealed interface Operation {
        data class Insert(val edits: JsonElement) : Operation
        data class Navigate(val location: JsonElement) : Operation
        data class Command(val command: JsonElement) : Operation
    }

    companion object {
        fun from(document: Document, hint: InlayHint): LeanInlayHint {
            val offset = getOffsetInDocument(document, hint.position)
                ?: throw IOException("Lean returned an inlay hint outside the document.")
            val insert = hint.textEdits?.takeIf { it.isNotEmpty() }?.let {
                Action("Insert hint", Operation.Insert(LeanServerHub.json(it)))
            }
            val parts = hint.label.map(
                { listOf(Part(it, listOfNotNull(insert))) },
                { labels ->
                    labels.map { label ->
                        val navigation = label.location?.let {
                            Action("Go to ${label.value}", Operation.Navigate(LeanServerHub.json(it)))
                        }
                        val command = label.command?.let {
                            Action(it.title, Operation.Command(LeanServerHub.json(it)))
                        }
                        Part(label.value, listOfNotNull(navigation, command, insert))
                    }
                }
            )
            if (parts.isEmpty() || parts.any { it.text.isEmpty() }) {
                throw IOException("Lean returned an inlay hint with an empty label.")
            }
            val descriptions = listOfNotNull(tooltip(hint.tooltip)) + hint.label.right.orEmpty().mapNotNull { part ->
                tooltip(part.tooltip)?.let { "<b>${StringUtil.escapeXmlEntities(part.value)}</b><br>$it" }
            }
            val tooltip = descriptions.takeIf { it.isNotEmpty() }?.joinToString("<hr>", "<html>", "</html>")
            return LeanInlayHint(
                offset, parts, tooltip, hint.kind, hint.paddingLeft == true, hint.paddingRight == true
            )
        }

        private fun tooltip(value: Either<String, MarkupContent>?): String? = value?.map(
            { "<pre>${StringUtil.escapeXmlEntities(it)}</pre>" },
            {
                if (it.kind == MarkupKind.MARKDOWN) convertMarkdownToHtml(it.value)
                else "<pre>${StringUtil.escapeXmlEntities(it.value)}</pre>"
            }
        )
    }
}

/** The platform serializes declarative presentations to the editor frontend, including remote editors. */
internal class LeanInlayHintsProvider : InlayHintsProvider, DumbAware {
    override fun createCollector(file: PsiFile, editor: Editor): OwnBypassCollector? {
        if (file.language != LeanLanguage) return null
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                val hints = file.project.service<LeanInlayService>().hints(file)
                var priority = Int.MAX_VALUE
                for (hint in hints) {
                    fun padding() {
                        sink.addPresentation(
                            InlineInlayPosition(hint.offset, false, priority--),
                            hintFormat = HintFormat.default.withColorKind(HintColorKind.TextWithoutBackground)
                        ) { text(" ") }
                    }
                    if (hint.paddingLeft) padding()
                    val format = if (hint.kind == InlayHintKind.Parameter) {
                        HintFormat.default.withColorKind(HintColorKind.Parameter)
                    } else HintFormat.default
                    val insert = hint.parts.asSequence().flatMap { it.actions.asSequence() }
                        .firstOrNull { it.operation is LeanInlayHint.Operation.Insert }
                    val payloads = insert?.let {
                        val payload = Json.encodeToString(LeanInlayActionGroup(listOf(it.reference)))
                        listOf(InlayPayload(LeanInlayActionGroup.INSERT_PAYLOAD, StringInlayActionPayload(payload)))
                    }
                    sink.addPresentation(
                        InlineInlayPosition(hint.offset, false, priority--), payloads = payloads,
                        tooltip = hint.tooltip, hintFormat = format
                    ) {
                        for (part in hint.parts) {
                            val action = part.actions.takeIf { it.isNotEmpty() }?.let { actions ->
                                val payload = Json.encodeToString(LeanInlayActionGroup(actions.map { it.reference }))
                                InlayActionData(StringInlayActionPayload(payload), LeanInlayActionGroup.HANDLER_ID)
                            }
                            text(part.text, action)
                        }
                    }
                    if (hint.paddingRight) padding()
                }
            }
        }
    }
}

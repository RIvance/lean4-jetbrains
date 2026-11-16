package plugin.lean.language

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import plugin.lean.settings.UnicodeInputConfiguration
import plugin.lean.settings.UnicodeInputSettings
import plugin.lean.tooling.UnicodeReplacement

/** The catalog is shared and immutable; an unfinished input belongs to one caret's lifetime. */
internal object UnicodeInput {
    val configuration: UnicodeInputConfiguration get() = service<UnicodeInputSettings>().configuration

    private class Active(
        val range: RangeMarker, var expected: String, val configuration: UnicodeInputConfiguration
    ) : Disposable {
        var queued = false

        fun afterTyping(offset: Int, character: Char): String {
            val relative = offset - range.startOffset
            return expected.take(relative) + character + expected.drop(relative)
        }

        override fun dispose() = range.dispose()
    }

    private val key = Key.create<Active>("plugin.lean.unicode.input")

    fun hasActive(caret: Caret): Boolean = caret.getUserData(key) != null

    fun clear(caret: Caret) {
        caret.getUserData(key)?.let { Disposer.dispose(it) }
        caret.putUserData(key, null)
    }

    private fun active(caret: Caret): Active? {
        val input = caret.getUserData(key) ?: return null
        val range = input.range
        val valid =
            input.configuration === configuration && range.isValid && !caret.hasSelection() &&
            caret.offset in range.startOffset + input.configuration.leader.length..range.endOffset &&
            caret.editor.document.getText(TextRange(range.startOffset, range.endOffset)) == input.expected
        if (valid) return input
        clear(caret)
        return null
    }

    /** Returns true when this character has been inserted here, bypassing quote/bracket pairing. */
    fun type(caret: Caret, character: Char): Boolean {
        val configuration = configuration
        if (!configuration.enabled) {
            clear(caret)
            return false
        }
        val leader = configuration.leader
        val catalog = configuration.catalog
        val input = active(caret)
        if (input != null) {
            val updated = input.afterTyping(caret.offset, character)
            val next = updated.drop(leader.length)
            if (caret.offset < input.range.endOffset || catalog.canContinue(next)) {
                insert(caret, character)
                input.expected = updated
                if (configuration.eagerReplacement && catalog.isUniqueAndComplete(next)) commit(caret)
                return true
            }
            commit(caret)
            clear(caret)
        }
        if (character != leader.last()) return false
        val start = caret.offset - leader.length + 1
        if (start < 0) return false
        val preceding = caret.editor.document.getText(TextRange(start, caret.offset))
        if (preceding != leader.dropLast(1)) return false
        insert(caret, character)
        val range = caret.editor.document.createRangeMarker(start, caret.offset)
        range.isGreedyToRight = true
        val started = Active(range, leader, configuration)
        Disposer.register(caret, started)
        caret.putUserData(key, started)
        watchInput(caret, started)
        return true
    }

    /** Completion must yield the final character so conversion and caret placement finish in the typing command. */
    fun completesOnTyping(caret: Caret, character: Char): Boolean {
        val input = active(caret) ?: return false
        val settings = input.configuration
        if (!settings.eagerReplacement) return false
        val abbreviation = input.afterTyping(caret.offset, character).drop(settings.leader.length)
        return settings.catalog.isUniqueAndComplete(abbreviation)
    }

    private fun watchInput(caret: Caret, input: Active) {
        val editor = caret.editor
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.caret == caret) scheduleReplacement(caret, input)
            }
        }, input)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                val project = editor.project ?: return
                val range = input.range
                if (!range.isValid || UndoManager.getInstance(project).isUndoOrRedoInProgress) {
                    clear(caret)
                    return
                }
                val start = range.startOffset
                val end = range.endOffset
                val changeEnd = event.offset + event.oldLength
                if (changeEnd <= start || event.offset > end) return
                if (event.offset < start + input.configuration.leader.length || changeEnd > end) {
                    clear(caret)
                    return
                }
                val before = input.expected.take(event.offset - start)
                val after = input.expected.drop(changeEnd - start)
                input.expected = before + event.newFragment + after
            }

            override fun documentChanged(event: DocumentEvent) { scheduleReplacement(caret, input) }
        }, input)
    }

    private fun scheduleReplacement(caret: Caret, input: Active) {
        if (input.queued) return
        input.queued = true
        val editor = caret.editor
        val group = CommandProcessor.getInstance().currentCommandGroupId
        // Document and caret notifications can precede the editor's final caret positions.
        ApplicationManager.getApplication().invokeLater({
            input.queued = false
            if (editor.isDisposed || !caret.isValid || caret.getUserData(key) !== input) return@invokeLater
            val range = input.range
            if (input.configuration !== configuration || !range.isValid || !editor.document.isWritable) {
                clear(caret)
                return@invokeLater
            }
            if (editor.document.getRangeGuard(range.startOffset, range.endOffset) != null) {
                clear(caret)
                return@invokeLater
            }
            val settings = input.configuration
            val inside = caret.offset in range.startOffset + settings.leader.length..range.endOffset
            val complete = settings.eagerReplacement && settings.catalog.isUniqueAndComplete(
                input.expected.drop(settings.leader.length)
            )
            if (inside && (caret.hasSelection() || !complete)) return@invokeLater
            val project = editor.project ?: return@invokeLater
            val replacement = Runnable {
                ApplicationManager.getApplication().runWriteAction {
                    if (inside) {
                        LookupManager.hideActiveLookup(project)
                        commit(caret)
                    } else {
                        commitAfterMovement(caret, input)
                    }
                }
            }
            CommandProcessor.getInstance().executeCommand(project, replacement, "Convert Lean abbreviation", group)
        }, ModalityState.stateForComponent(editor.component))
    }

    private fun commitAfterMovement(caret: Caret, input: Active) {
        val document = caret.editor.document
        val range = input.range
        if (!range.isValid || !document.isWritable) {
            clear(caret)
            return
        }
        val start = range.startOffset
        val end = range.endOffset
        val unchanged = document.getText(TextRange(start, end)) == input.expected
        val settings = input.configuration
        val abbreviation = input.expected.drop(settings.leader.length)
        val replacement = if (unchanged) settings.catalog.replacement(abbreviation).orElse(null) else null
        clear(caret)
        // The editor translates the user's new selection through the edit; do not move it back to the input.
        if (replacement != null) document.replaceString(start, end, replacement.text())
    }

    private fun insert(caret: Caret, character: Char) {
        val offset = caret.offset
        caret.editor.document.insertString(offset, character.toString())
        caret.moveToOffset(offset + 1)
    }

    fun commit(caret: Caret): Boolean {
        val input = active(caret) ?: return false
        val settings = input.configuration
        val abbreviation = input.expected.drop(settings.leader.length)
        val replacement = settings.catalog.replacement(abbreviation).orElse(null) ?: return false
        val start = input.range.startOffset
        val end = input.range.endOffset
        clear(caret)
        replace(caret, start, end, replacement)
        return true
    }

    fun replace(caret: Caret, start: Int, end: Int, replacement: UnicodeReplacement) {
        clear(caret)
        caret.editor.document.replaceString(start, end, replacement.text())
        caret.moveToOffset(start + replacement.caretOffset())
    }

    /** Explicit completion can also convert existing text. Automatic typing only uses tracked input. */
    fun completionStart(editor: Editor, offset: Int, configuration: UnicodeInputConfiguration): Int? {
        if (!configuration.enabled) return null
        val text = editor.document.charsSequence
        val catalog = configuration.catalog
        val leader = configuration.leader
        val minimum = (offset - catalog.maximumLength() - leader.length).coerceAtLeast(0)
        for (index in offset - leader.length downTo minimum) {
            if (text[index] == '\n') return null
            if (text.subSequence(index, index + leader.length).toString() == leader) {
                return index.takeIf { catalog.canContinue(text.subSequence(index + leader.length, offset).toString()) }
            }
        }
        return null
    }
}

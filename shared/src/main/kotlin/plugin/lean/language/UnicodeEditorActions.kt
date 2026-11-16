package plugin.lean.language

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import plugin.lean.tooling.UnicodeReplacement

class LeanUnicodeTypedHandler : TypedHandlerDelegate() {
    override fun beforeCharTyped(
        character: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType
    ): Result {
        if (file.language != LeanLanguage) return Result.CONTINUE
        return if (UnicodeInput.type(editor.caretModel.currentCaret, character)) Result.STOP else Result.CONTINUE
    }
}

class LeanUnicodeLookupFilter : CharFilter() {
    override fun acceptChar(character: Char, prefixLength: Int, lookup: Lookup): Result? {
        if (!lookup.isCompletion || lookup.psiFile?.language != LeanLanguage) return null
        val completes = lookup.editor.caretModel.allCarets.any { UnicodeInput.completesOnTyping(it, character) }
        return if (completes) Result.HIDE_LOOKUP else null
    }
}

abstract class LeanUnicodeBoundaryHandler(
    private val original: EditorActionHandler, private val consume: Boolean
) : EditorWriteActionHandler(true) {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        UnicodeInput.hasActive(caret) || original.isEnabled(editor, caret, dataContext)

    override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) {
        if (LookupManager.getActiveLookup(editor) != null) {
            original.execute(editor, caret, dataContext)
            return
        }
        val current = caret ?: editor.caretModel.currentCaret
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val converted = file?.fileType == LeanFileType && UnicodeInput.commit(current)
        if (!converted || !consume) original.execute(editor, caret, dataContext)
    }
}

class LeanUnicodeTabHandler(original: EditorActionHandler) : LeanUnicodeBoundaryHandler(original, true)
class LeanUnicodeEnterHandler(original: EditorActionHandler) : LeanUnicodeBoundaryHandler(original, false)

class LeanUnicodeEscapeHandler(private val original: EditorActionHandler) : EditorActionHandler(true) {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        UnicodeInput.clear(caret ?: editor.caretModel.currentCaret)
        original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        UnicodeInput.hasActive(caret) || original.isEnabled(editor, caret, dataContext)
}

/** One undoable insertion replaces each selection and preserves explicit cursor positions. */
object LeanUnicodeInsertion {
    /** Commit pending input before an editor-backed field submits its text. */
    fun finishAbbreviations(editor: Editor) {
        if (editor.isDisposed || !EditorModificationUtil.checkModificationAllowed(editor)) return
        if (!editor.caretModel.allCarets.any(UnicodeInput::hasActive)) return
        if (!EditorModificationUtil.requestWriting(editor)) return
        WriteCommandAction.runWriteCommandAction(editor.project, "Convert Lean abbreviation", null, Runnable {
            editor.caretModel.allCarets.forEach { UnicodeInput.commit(it) }
        })
    }

    fun insert(editor: Editor, replacement: UnicodeReplacement): Boolean {
        if (editor.isDisposed || !EditorModificationUtil.checkModificationAllowed(editor)) return false
        if (!EditorModificationUtil.requestWriting(editor)) return false
        var inserted = false
        WriteCommandAction.runWriteCommandAction(editor.project, "Insert Lean Unicode symbol", null, Runnable {
            val carets = editor.caretModel.allCarets
            val guarded = carets.any { editor.document.getRangeGuard(it.selectionStart, it.selectionEnd) != null }
            if (guarded) return@Runnable
            editor.caretModel.runForEachCaret { caret ->
                val start = caret.selectionStart
                val end = caret.selectionEnd
                caret.removeSelection()
                UnicodeInput.replace(caret, start, end, replacement)
            }
            inserted = true
        })
        return inserted
    }
}

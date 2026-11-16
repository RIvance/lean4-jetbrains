package plugin.lean.editor

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertThrows
import plugin.lean.rpc.LeanProofFold
import plugin.lean.rpc.LeanProofFolds
import java.io.IOException

class LeanProofFoldingActionTest : BasePlatformTestCase() {
    private lateinit var editor: Editor
    private val text = """
        namespace Example
        example : True := by
          trivial
        example : True := by
          trivial
        end Example
    """.trimIndent()
    private val first = LeanProofFold(text.indexOf("by") + 2, text.indexOf("\nexample", text.indexOf("by")))
    private val second = LeanProofFold(text.lastIndexOf("by") + 2, text.indexOf("\nend"))

    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        runInEdtAndWait {
            val factory = EditorFactory.getInstance()
            editor = factory.createEditor(factory.createDocument(text), project)
        }
    }

    override fun tearDown() {
        try {
            runInEdtAndWait {
                if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
            }
        } finally {
            super.tearDown()
        }
    }

    fun testCommandsChangeProofsAndPreserveOrdinaryFolds() = runBlocking {
        val (outer, proofs) = withContext(Dispatchers.EDT) {
            val outer = addFold(LeanProofFold(0, text.length))
            outer to listOf(addFold(first), addFold(second))
        }
        val stamp = editor.document.modificationStamp
        setProofsExpanded(editor, snapshot(), stamp, expanded = false)
        withContext(Dispatchers.EDT) {
            assertTrue(outer.isExpanded)
            assertTrue(proofs.none { it.isExpanded })
            editor.foldingModel.runBatchFoldingOperation { outer.isExpanded = false }
        }
        setProofsExpanded(editor, snapshot(), stamp, expanded = true)
        withContext(Dispatchers.EDT) {
            assertFalse(outer.isExpanded)
            assertTrue(proofs.all { it.isExpanded })
            assertEquals(text, editor.document.text)
        }
    }

    fun testMismatchedReplicaAndChangedRevisionLeaveFoldsUnchanged() {
        val stamp = editor.document.modificationStamp
        runInEdtAndWait { addFold(first) }
        val differentText = LeanProofFolds(LeanProofFolds.digest(text.replace("True", "Bool")), listOf(first))
        assertThrows(IOException::class.java) {
            runBlocking { setProofsExpanded(editor, differentText, stamp, expanded = false) }
        }
        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(text.length, " ")
                editor.document.deleteString(text.length, text.length + 1)
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { setProofsExpanded(editor, snapshot(), stamp, expanded = false) }
        }
        runInEdtAndWait {
            assertTrue(editor.foldingModel.getFoldRegion(first.start, first.end)!!.isExpanded)
        }
    }

    fun testNativeFoldingUpdatesCompleteAPendingCommand() = runBlocking {
        val proof = withContext(Dispatchers.EDT) { addFold(first) }
        val stamp = editor.document.modificationStamp
        val pending = async { setProofsExpanded(editor, snapshot(), stamp, expanded = false) }
        withContext(Dispatchers.EDT) {
            assertTrue(proof.isExpanded)
            addFold(second)
        }
        pending.await()
        withContext(Dispatchers.EDT) {
            assertTrue(editor.foldingModel.allFoldRegions.none { it.isExpanded })
        }
    }

    fun testCancellationLeavesAvailableAndLaterFoldsUnchanged() = runBlocking {
        withContext(Dispatchers.EDT) { addFold(first) }
        val stamp = editor.document.modificationStamp
        val pending = async { setProofsExpanded(editor, snapshot(), stamp, expanded = false) }
        withContext(Dispatchers.EDT) { assertTrue(editor.foldingModel.allFoldRegions.all { it.isExpanded }) }
        pending.cancelAndJoin()
        withContext(Dispatchers.EDT) {
            addFold(second)
            assertTrue(editor.foldingModel.allFoldRegions.all { it.isExpanded })
        }
    }

    fun testEmptyProofResponseDoesNotChangeOrdinaryFolding() = runBlocking {
        withContext(Dispatchers.EDT) { addFold(LeanProofFold(0, text.length)) }
        val snapshot = LeanProofFolds(LeanProofFolds.digest(text), emptyList())
        setProofsExpanded(editor, snapshot, editor.document.modificationStamp, expanded = false)
        withContext(Dispatchers.EDT) { assertTrue(editor.foldingModel.allFoldRegions.single().isExpanded) }
    }

    private fun snapshot() = LeanProofFolds(LeanProofFolds.digest(text), listOf(first, second))

    private suspend fun setProofsExpanded(editor: Editor, folds: LeanProofFolds, stamp: Long, expanded: Boolean) {
        changeProofFolds(editor, folds, stamp) { regions -> regions.forEach { it.isExpanded = expanded } }
    }

    private fun addFold(range: LeanProofFold): FoldRegion {
        var region: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperation {
            region = editor.foldingModel.addFoldRegion(range.start, range.end, "…")
        }
        return checkNotNull(region)
    }
}

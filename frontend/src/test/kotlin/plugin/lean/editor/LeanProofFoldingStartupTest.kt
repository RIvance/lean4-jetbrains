package plugin.lean.editor

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import plugin.lean.rpc.LeanProofFold
import plugin.lean.rpc.LeanProofFolds
import plugin.lean.rpc.LeanRpcResult

class LeanProofFoldingStartupTest : BasePlatformTestCase() {
    private lateinit var editor: Editor
    private val text = """
        namespace Example
        example : True := by
          trivial
        example : True := by
          trivial
        example : True := by
          trivial
        end Example
    """.trimIndent()
    private val ranges = Regex("by\\n  trivial").findAll(text).map {
        LeanProofFold(it.range.first + 2, it.range.last + 1)
    }.toList()

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

    fun testInitialFoldingWaitsForNativeRegionsAndDoesNotRepeat() = runBlocking {
        withContext(Dispatchers.EDT) {
            var requests = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                foldProofsOnOpen(editor, editor.document.modificationStamp) {
                    requests++
                    LeanRpcResult.Success(snapshot())
                }
            }
            assertEquals(0, requests)
            val ordinary = addFold(LeanProofFold(0, text.length))
            val proofs = ranges.map(::addFold)
            pending.await()
            assertEquals(1, requests)
            assertTrue(ordinary.isExpanded)
            assertTrue(proofs.none { it.isExpanded })
            editor.foldingModel.runBatchFoldingOperation { proofs.first().isExpanded = true }
            assertTrue(proofs.first().isExpanded)
            assertEquals(1, requests)
        }
    }

    fun testInitialFoldingPreservesCaretsAndSelectionsAtTheTimeItApplies() = runBlocking {
        withContext(Dispatchers.EDT) {
            val proofs = ranges.map(::addFold)
            val response = CompletableDeferred<LeanRpcResult<LeanProofFolds>>()
            val requested = CompletableDeferred<Unit>()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                foldProofsOnOpen(editor, editor.document.modificationStamp) {
                    requested.complete(Unit)
                    response.await()
                }
            }
            requested.await()
            val caret = editor.caretModel.primaryCaret
            caret.moveToOffset(ranges[0].start + 2)
            val second = checkNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(ranges[1].end + 1)))
            second.setSelection(ranges[1].end - 2, ranges[1].end + 1)
            response.complete(LeanRpcResult.Success(snapshot()))
            pending.await()
            assertTrue(proofs[0].isExpanded)
            assertTrue(proofs[1].isExpanded)
            assertFalse(proofs[2].isExpanded)
            assertEquals(ranges[0].start + 2, caret.offset)
            assertEquals(ranges[1].end - 2, second.selectionStart)
            assertEquals(text, editor.document.text)
        }
    }

    fun testEditingOrManualFoldingCancelsAPendingDefaultWithoutOverwritingTheUser() = runBlocking {
        withContext(Dispatchers.EDT) {
            val proofs = ranges.map(::addFold)
            for (editDocument in listOf(false, true)) {
                val requested = CompletableDeferred<Unit>()
                val response = CompletableDeferred<LeanRpcResult<LeanProofFolds>>()
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    foldProofsOnOpen(editor, editor.document.modificationStamp) {
                        requested.complete(Unit)
                        response.await()
                    }
                }
                requested.await()
                if (editDocument) {
                    WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(text.length, " ") }
                } else {
                    editor.foldingModel.runBatchFoldingOperation { proofs.first().isExpanded = false }
                }
                pending.join()
                assertTrue(pending.isCancelled)
                response.complete(LeanRpcResult.Success(snapshot()))
                assertFalse(proofs.first().isExpanded)
                assertTrue(proofs.drop(1).all { it.isExpanded })
            }
        }
    }

    fun testServerAvailabilityFailureRetriesOnlyAfterANativeFoldingUpdate() = runBlocking {
        withContext(Dispatchers.EDT) {
            val ordinary = addFold(LeanProofFold(0, text.length))
            val failed = CompletableDeferred<Unit>()
            var requests = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                foldProofsOnOpen(editor, editor.document.modificationStamp) {
                    requests++
                    if (requests == 1) {
                        failed.complete(Unit)
                        LeanRpcResult.Failure("The Lean server is starting.")
                    } else {
                        LeanRpcResult.Success(snapshot())
                    }
                }
            }
            failed.await()
            assertEquals(1, requests)
            val proofs = ranges.map(::addFold)
            pending.await()
            assertEquals(2, requests)
            assertTrue(ordinary.isExpanded)
            assertTrue(proofs.none { it.isExpanded })
        }
    }

    fun testClosingTheEditorCancelsAPendingRequest() = runBlocking {
        withContext(Dispatchers.EDT) {
            ranges.forEach(::addFold)
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<LeanRpcResult<LeanProofFolds>>()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                foldProofsOnOpen(editor, editor.document.modificationStamp) {
                    requested.complete(Unit)
                    response.await()
                }
            }
            requested.await()
            EditorFactory.getInstance().releaseEditor(editor)
            pending.join()
            assertTrue(pending.isCancelled)
        }
    }

    fun testAnEditBeforeStartupAndAnOlderServerLeaveTheEditorUnchanged() = runBlocking {
        withContext(Dispatchers.EDT) {
            val proofs = ranges.map(::addFold)
            val stamp = editor.document.modificationStamp
            WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(text.length, " ") }
            foldProofsOnOpen(editor, stamp) { error("An edited document must not be queried") }
            val noProofs = LeanProofFolds(LeanProofFolds.digest(editor.document.text), emptyList())
            foldProofsOnOpen(editor, editor.document.modificationStamp) { LeanRpcResult.Success(noProofs) }
            assertTrue(proofs.all { it.isExpanded })
        }
    }

    private fun snapshot() = LeanProofFolds(LeanProofFolds.digest(text), ranges)

    private fun addFold(range: LeanProofFold): FoldRegion {
        var region: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperation {
            region = editor.foldingModel.addFoldRegion(range.start, range.end, "…")
        }
        return checkNotNull(region)
    }
}

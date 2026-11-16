package plugin.lean.editor

import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.FoldingRange
import org.junit.Assert.assertThrows
import plugin.lean.lsp.LeanLspClientDescriptor
import plugin.lean.rpc.LeanProofFold
import plugin.lean.tooling.LeanWorkspace
import java.io.IOException
import java.nio.file.Path

class LeanProofFoldingTest : BasePlatformTestCase() {
    fun testInitializationAdvertisesProofKindsAlongsideNativeFoldingCapabilities() {
        val root = myFixture.tempDirFixture.findOrCreateDir("workspace")
        val descriptor = LeanLspClientDescriptor(project, root, LeanWorkspace(Path.of(root.path), false))
        val capabilities = descriptor.createInitializeParams().capabilities.textDocument.foldingRange
        assertTrue(capabilities.foldingRangeKind.valueSet.containsAll(listOf("proof", "imports", "region")))
        assertFalse(capabilities.lineFoldingOnly)
        assertTrue(capabilities.foldingRange.collapsedText)
    }

    fun testOnlyProofRangesAreSelectedAndDuplicateRangesFoldOnce() {
        val document = EditorFactory.getInstance().createDocument("namespace Example\n  first\n    second\nend Example")
        val proof = FoldingRange(1, 2).apply { kind = "proof" }
        val ranges = listOf(
            FoldingRange(0, 3).apply { kind = "region" },
            FoldingRange(0, 3).apply { kind = "future-kind" },
            proof,
            FoldingRange(1, 2).apply { kind = "comment" },
            proof
        )
        assertEquals(
            listOf(LeanProofFold(document.getLineEndOffset(1), document.getLineEndOffset(2))),
            proofFoldRanges(document, ranges)
        )
        assertTrue(proofFoldRanges(document, emptyList()).isEmpty())
    }

    fun testCharacterRangesUseUtf16OffsetsAndNativeEndOfLineClamping() {
        val document = EditorFactory.getInstance().createDocument("😀 theorem\n  proof α\n  done")
        val proof = FoldingRange(0, 1).apply {
            kind = "proof"
            startCharacter = 3
            endCharacter = 100
        }
        assertEquals(listOf(LeanProofFold(3, document.getLineEndOffset(1))), proofFoldRanges(document, listOf(proof)))
        val empty = FoldingRange(1, 1).apply { kind = "proof" }
        assertTrue(proofFoldRanges(document, listOf(empty)).isEmpty())
    }

    fun testInvalidProofRangesFailTheWholeRequest() {
        val document = EditorFactory.getInstance().createDocument("first\nsecond\nthird")
        val valid = FoldingRange(0, 1).apply { kind = "proof" }
        for (invalid in listOf(FoldingRange(3, 4), FoldingRange(-1, 1), FoldingRange(2, 0))) {
            invalid.kind = "proof"
            assertThrows(IOException::class.java) { proofFoldRanges(document, listOf(valid, invalid)) }
        }
    }
}

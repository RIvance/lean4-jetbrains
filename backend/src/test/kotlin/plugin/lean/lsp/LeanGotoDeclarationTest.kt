package plugin.lean.lsp

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

class LeanGotoDeclarationTest : BasePlatformTestCase() {
    private val params = DefinitionParams(TextDocumentIdentifier("file:///Main.lean"), Position(0, 5))
    private val targetUri = "file:///Definitions.lean"

    fun testLinksUseSelectionRangesAndPreferTheRightNeighborAtTheCaret() {
        val file = myFixture.addFileToProject("Definitions.lean", "def first := 0\ndef right := 1\ndef other := 2")
        fun link(line: Int, origin: Range?) = LocationLink(
            targetUri, Range(Position(line, 0), Position(line, 14)), Range(Position(line, 4), Position(line, 9)), origin
        )
        val left = link(0, Range(Position(0, 1), params.position))
        val right = link(1, Range(params.position, Position(0, 9)))
        val other = link(2, null)
        val both = Either.forRight<List<Location>, List<LocationLink>>(listOf(left, right, right, other))
        val targets = definitionTargets(params, both) { file }
        assertEquals(listOf(file.text.indexOf("right"), file.text.indexOf("other")), targets.map { it.textOffset })
        val onlyLeft = Either.forRight<List<Location>, List<LocationLink>>(listOf(left))
        assertEquals(file.text.indexOf("first"), definitionTargets(params, onlyLeft) { file }.single().textOffset)
    }

    fun testNoDestinationForSelfLinksMissingFilesOrEmptyResults() {
        val file = myFixture.addFileToProject("Main.lean", "def value := 0")
        val name = Range(Position(0, 4), Position(0, 9))
        val self = LocationLink(params.textDocument.uri, Range(Position(0, 0), Position(0, 14)), name, name)
        val missing = LocationLink(targetUri, name, name)
        val links = Either.forRight<List<Location>, List<LocationLink>>(listOf(self, missing))
        assertEmpty(definitionTargets(params, links) { uri -> if (uri == params.textDocument.uri) file else null })
        assertEmpty(definitionTargets(params, Either.forLeft(emptyList())) { file })
    }

    fun testLocationsKeepExactUtf16PositionsInsideTokensAndAtEndOfFile() {
        val file = myFixture.addFileToProject("Definitions.lean", "-- 🐑 symbol")
        val selection = Range(Position(0, 6), Position(0, 12))
        val eof = Range(selection.end, selection.end)
        val invalid = Range(Position(8, 0), Position(8, 1))
        val locations = listOf(selection, eof, invalid).map { Location(targetUri, it) }
        val targets = definitionTargets(params, Either.forLeft(locations)) { file }
        assertEquals(listOf(6, 12), targets.map { it.textOffset })
        assertEquals(listOf("symbol", "Definitions.lean"), targets.map { (it as PsiNamedElement).name })

        val documents = PsiDocumentManager.getInstance(project)
        val document = checkNotNull(documents.getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "--\n") }
        documents.commitDocument(document)
        assertEquals(listOf(9, 15), targets.map { it.textOffset })
    }
}

package plugin.lean.editor

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.getRangeInDocument
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import plugin.lean.lsp.LeanServerHub
import plugin.lean.rpc.LeanProofFold
import plugin.lean.rpc.LeanProofFolds
import java.io.IOException
import java.util.concurrent.CompletableFuture

/** A command queries the existing server; native LSP folding continues to own the editor's fold regions. */
internal suspend fun requestProofFolds(project: Project, file: VirtualFile): LeanProofFolds {
    val hub = project.service<LeanServerHub>()
    val server = readAction { hub.serverFor(file) }
        ?: throw IOException("The Lean server is not running for this file.")
    hub.awaitDocument(server.id, hub.documentUri(server.id, file))
    val pending = readAction {
        val lifetime = hub.synchronizedDocument(file)
            ?: throw IOException("The Lean document is not synchronized.")
        if (lifetime.serverId != server.id) throw IOException("Lean restarted. Try the folding command again.")
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: throw IOException("The Lean document is no longer available.")
        val stamp = document.modificationStamp
        val params = FoldingRangeRequestParams(TextDocumentIdentifier(lifetime.uri))
        val request = hub.enqueue(server.id, { it.textDocumentService.foldingRange(params) }, { it.orEmpty() })
        ProofFoldingRequest(document, stamp, lifetime, request)
    }
    val response = pending.request.await()
    val snapshot = readAction {
        if (
            !file.isValid || pending.document.modificationStamp != pending.stamp ||
            hub.synchronizedDocument(file) != pending.lifetime
        ) {
            throw IOException("The Lean document changed. Try the folding command again.")
        }
        pending.document.immutableCharSequence to proofFoldRanges(pending.document, response)
    }
    return LeanProofFolds(LeanProofFolds.digest(snapshot.first), snapshot.second)
}

private data class ProofFoldingRequest(
    val document: Document,
    val stamp: Long,
    val lifetime: LeanServerHub.DocumentSnapshot,
    val request: CompletableFuture<List<FoldingRange>>
)

/** Use the same public LSP position conversion as the native folding renderer. */
internal fun proofFoldRanges(document: Document, ranges: List<FoldingRange>): List<LeanProofFold> {
    return ranges.filter { it.kind == "proof" }.mapNotNull { folding ->
        val range = Range(
            Position(folding.startLine, folding.startCharacter ?: Int.MAX_VALUE),
            Position(folding.endLine, folding.endCharacter ?: Int.MAX_VALUE)
        )
        val offsets = getRangeInDocument(document, range)
            ?: throw IOException("Lean returned a proof folding range outside the document.")
        if (offsets.isEmpty) null else LeanProofFold(offsets.startOffset, offsets.endOffset)
    }.distinct()
}

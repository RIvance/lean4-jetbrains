package plugin.lean.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.HexFormat

@Serializable
data class LeanProofFold(val start: Int, val end: Int)

/** Fold locations are valid only for this exact document text, including across frontend/backend replicas. */
@Serializable
data class LeanProofFolds(val textDigest: String, val ranges: List<LeanProofFold>) {
    fun matches(text: CharSequence): Boolean = textDigest == digest(text)

    companion object {
        fun digest(text: CharSequence): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // Hash UTF-16 code units directly, preserving unfinished surrogate pairs during editing.
            for (character in text) {
                digest.update((character.code ushr 8).toByte())
                digest.update(character.code.toByte())
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}

@Rpc
interface LeanFileApi : RemoteApi<Unit> {
    /** Restart an open file with its current editor text and refresh dependencies on the project host. */
    suspend fun restart(projectId: ProjectId, fileId: VirtualFileId): LeanRpcResult<Unit>

    /** Query Lean's proof folding ranges for a synchronized document revision. */
    suspend fun proofFolds(projectId: ProjectId, fileId: VirtualFileId): LeanRpcResult<LeanProofFolds>

    companion object {
        suspend fun getInstance(): LeanFileApi = RemoteApiProviderService.resolve(remoteApiDescriptor<LeanFileApi>())
    }
}

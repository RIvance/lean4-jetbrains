package plugin.lean.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import plugin.lean.editor.requestProofFolds
import plugin.lean.lsp.LeanLspProjectService
import java.io.IOException

internal class BackendLeanFileApi : LeanFileApi {
    override suspend fun restart(projectId: ProjectId, fileId: VirtualFileId) = leanRpcResult {
        val project = projectId.findProjectOrNull() ?: throw IOException("The Lean project has been closed.")
        val file = fileId.virtualFile() ?: throw IOException("The Lean file is no longer available.")
        project.service<LeanLspProjectService>().restartFile(file)
    }

    override suspend fun proofFolds(projectId: ProjectId, fileId: VirtualFileId) = leanRpcResult {
        val project = projectId.findProjectOrNull() ?: throw IOException("The Lean project has been closed.")
        val file = fileId.virtualFile() ?: throw IOException("The Lean file is no longer available.")
        requestProofFolds(project, file)
    }
}

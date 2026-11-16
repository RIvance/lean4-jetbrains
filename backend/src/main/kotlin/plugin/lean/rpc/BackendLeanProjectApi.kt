package plugin.lean.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.virtualFile
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import plugin.lean.run.LakeProjectTasks
import java.io.IOException

internal class BackendLeanProjectApi : LeanProjectApi {
    override suspend fun runLake(projectId: ProjectId, fileId: VirtualFileId?, task: LakeProjectTask) = leanRpcResult {
        val project = projectId.findProjectOrNull() ?: throw IOException("The Lean project has been closed.")
        val file = fileId?.let { it.virtualFile() ?: throw IOException("The selected file is no longer available.") }
        LakeProjectTasks.run(project, file, task)
    }

    override suspend fun fetchCacheForFiles(projectId: ProjectId, files: List<VirtualFileId>) = leanRpcResult {
        val project = projectId.findProjectOrNull() ?: throw IOException("The Lean project has been closed.")
        val selected = files.map {
            it.virtualFile() ?: throw IOException("A selected Lean file is no longer available.")
        }
        LakeProjectTasks.fetchCacheForFiles(project, selected)
    }
}

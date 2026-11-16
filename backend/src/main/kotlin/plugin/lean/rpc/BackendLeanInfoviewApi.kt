package plugin.lean.rpc

import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import plugin.lean.infoview.LeanInfoviewService

internal class BackendLeanInfoviewApi : LeanInfoviewApi {
    private fun service(projectId: ProjectId): LeanInfoviewService =
        checkNotNull(projectId.findProjectOrNull()) { "The Lean project has been closed" }.service()

    override suspend fun events(projectId: ProjectId, viewId: String) = service(projectId).events(viewId)

    override suspend fun execute(projectId: ProjectId, viewId: String, command: LeanInfoviewCommand) =
        service(projectId).execute(viewId, command)
}

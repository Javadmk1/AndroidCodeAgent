package com.codeagent.android.agent

import java.io.File
import org.json.JSONObject

/**
 * Composition root for the Agent runtime.
 *
 * Keeps workspace/build/queue wiring out of Compose while leaving all
 * interactive approval decisions with the UI/controller that owns them.
 */
class AgentRuntimeFactory(
    private val buildService: BuildProjectService,
    private val workQueueStore: WorkQueueStore
) {
    fun create(
        workspace: WorkspaceTools,
        projectDirectory: File,
        projectId: String,
        onQueueChanged: suspend (List<WorkItem>) -> Unit
    ): AgentRuntime {
        return AgentRuntime(
            ToolRegistry(
                workspace = workspace,
                buildProject = { task, report ->
                    buildService.build(projectDirectory, task, report)
                },
                createPlanStep = { title, details ->
                    val item = workQueueStore.addPlanStep(projectId, title, details)
                    onQueueChanged(workQueueStore.list(projectId))
                    JSONObject().put("ok", true).put("id", item.id).put("title", item.title)
                },
                queuePrompt = { prompt ->
                    val item = workQueueStore.addPrompt(projectId, prompt)
                    onQueueChanged(workQueueStore.list(projectId))
                    JSONObject().put("ok", true).put("id", item.id).put("title", item.title)
                }
            )
        )
    }
}

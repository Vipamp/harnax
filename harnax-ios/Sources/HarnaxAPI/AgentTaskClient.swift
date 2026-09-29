import Foundation
import HarnaxCore

extension AdminClient: AgentTaskCataloging {
    public func agentTaskPage(
        name: String?,
        taskStatus: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskSummary>, APIError> {
        await client.send(
            Page<AgentTaskSummary>.self,
            AgentTaskEndpoint.page(name: name, taskStatus: taskStatus, num: num, size: size)
        )
    }

    public func createAgentTask(_ draft: AgentTaskDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? AgentTaskEndpoint.create(draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    /// A refusal carrying `AgentTaskCode.schedulerSyncFailed` still reaches the caller as a failure — the code
    /// is a fact about the *scheduler*, and only the two screens know that a 40902 edit counts as saved while a
    /// 40902 toggle does not. `ResponseMapper` therefore stays the single place that reads `code`.
    public func updateAgentTask(id: Int64, _ change: AgentTaskChange) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? AgentTaskEndpoint.update(id: id, change) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    public func setAgentTaskStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, AgentTaskEndpoint.toggle(id: id, status: enabled.hxInt))
    }

    public func triggerAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, AgentTaskEndpoint.trigger(id: id))
    }

    public func deleteAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, AgentTaskEndpoint.delete(id: id))
    }

    public func agentTaskAgents() async -> Result<[AgentTaskAgentOption], APIError> {
        await client.send([AgentTaskAgentOption].self, AgentTaskEndpoint.agents())
    }

    public func agentTaskLogs(
        taskID: Int64,
        filter: AgentTaskLogFilter,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskLog>, APIError> {
        await client.send(
            Page<AgentTaskLog>.self,
            AgentTaskEndpoint.logs(taskID: taskID, filter: filter, num: num, size: size)
        )
    }

    /// The route's `data` is the sentence `"Task stopped"` and nothing else (`SchedulerController.kt:144-150`),
    /// so it decodes as empty; which of the three interrupt outcomes actually landed is only visible on the next
    /// page read (`§5.5`).
    public func stopAgentTaskLog(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, AgentTaskEndpoint.stopLog(id: id))
    }
}

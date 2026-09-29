import Foundation
import HarnaxCore

/// One double per tab a test never opens: `UnwiredCatalogs` for the five context catalogs and the scheduled
/// tasks, `UnwiredChat` for the four chat dependencies and `UnwiredSystem` for the three system catalogs.
///
/// `AppModelTests` builds a `HarnaxDependencies` to exercise restore, login and the tab bar; the catalogs
/// it carries exist only because the composition root holds them. Every method fails rather than returning
/// an empty page, so a screen that reaches one of these objects in a test shows up as a failing call instead
/// of a list that quietly renders nothing.
struct UnwiredCatalogs: ModelCataloging, ToolCataloging, SkillCataloging, McpCataloging, CliCataloging,
    AgentTaskCataloging {
    private func unwired() -> APIError {
        .business(code: -1, message: "this test never wires the context tab")
    }

    // MARK: - models

    func providerPage(
        name: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ModelProviderSummary>, APIError> { .failure(unwired()) }

    func providerStats(id: Int64) async -> Result<ModelProviderStats, APIError> { .failure(unwired()) }

    func setProviderStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteProvider(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func saveProvider(
        id: Int64?,
        request: ModelProviderSaveRequest
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func testProvider(id: Int64) async -> Result<Bool, APIError> { .failure(unwired()) }

    func modelPage(
        providerID: Int64,
        name: String?,
        status: Int?,
        tags: [String],
        num: Int,
        size: Int
    ) async -> Result<Page<ModelSummary>, APIError> { .failure(unwired()) }

    func setModelStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteModel(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func saveModel(
        id: Int64?,
        request: ModelSaveRequest
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    // MARK: - tools

    func toolPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ToolSummary>, APIError> { .failure(unwired()) }

    func toolDetail(id: Int64) async -> Result<ToolSummary, APIError> { .failure(unwired()) }

    // MARK: - skills

    func sourcePage(
        name: String?,
        sourceType: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillSourceSummary>, APIError> { .failure(unwired()) }

    func source(id: Int64) async -> Result<SkillSourceSummary, APIError> { .failure(unwired()) }

    func preview(sourceID: Int64) async -> Result<[SkillPreviewItem], APIError> { .failure(unwired()) }

    func install(
        sourceID: Int64,
        names: [String]?
    ) async -> Result<SkillInstallOutcome, APIError> { .failure(unwired()) }

    func createSource(
        _ payload: SkillSourceCreatePayload
    ) async -> Result<SkillSourceInstallResult, APIError> { .failure(unwired()) }

    func updateSource(
        id: Int64,
        _ payload: SkillSourceUpdatePayload
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteSource(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func setSourceStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func uploadSource(
        name: String,
        fileName: String,
        payload: Data
    ) async -> Result<SkillSourceInstallResult, APIError> { .failure(unwired()) }

    func skillPage(
        name: String?,
        repositoryID: Int64?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillItem>, APIError> { .failure(unwired()) }

    func skill(id: Int64) async -> Result<SkillItem, APIError> { .failure(unwired()) }

    func setSkillStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    // MARK: - MCP

    func mcpPage(
        keyword: String?,
        status: Int?,
        type: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<McpServerRow>, APIError> { .failure(unwired()) }

    func mcpServer(id: Int64) async -> Result<McpServerRow, APIError> { .failure(unwired()) }

    func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func updateMCPServer(
        id: Int64,
        patch: McpServerPatch
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func mcpRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> { .failure(unwired()) }

    func deleteMCPServer(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func testMcpConnectivity(id: Int64) async -> Result<Bool, APIError> { .failure(unwired()) }

    func mcpTools(id: Int64) async -> Result<[McpToolRow], APIError> { .failure(unwired()) }

    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError> { .failure(unwired()) }

    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError> { .failure(unwired()) }

    func mcpAuthorizeURL(
        id: Int64,
        scope: String?
    ) async -> Result<McpOAuthAuthorization, APIError> { .failure(unwired()) }

    // MARK: - CLI packages

    func cliPage(
        name: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<CliSummary>, APIError> { .failure(unwired()) }

    func cliDetail(id: Int64) async -> Result<CliSummary, APIError> { .failure(unwired()) }

    func cliRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> { .failure(unwired()) }

    func cliRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> { .failure(unwired()) }

    func setCliStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    // MARK: - scheduled tasks

    func agentTaskPage(
        name: String?,
        taskStatus: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskSummary>, APIError> { .failure(unwiredTasks()) }

    func createAgentTask(_ draft: AgentTaskDraft) async -> Result<EmptyResponse, APIError> {
        .failure(unwiredTasks())
    }

    func updateAgentTask(id: Int64, _ change: AgentTaskChange) async -> Result<EmptyResponse, APIError> {
        .failure(unwiredTasks())
    }

    func setAgentTaskStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        .failure(unwiredTasks())
    }

    func triggerAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwiredTasks()) }

    func deleteAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwiredTasks()) }

    func agentTaskAgents() async -> Result<[AgentTaskAgentOption], APIError> { .failure(unwiredTasks()) }

    func agentTaskLogs(
        taskID: Int64,
        filter: AgentTaskLogFilter,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskLog>, APIError> { .failure(unwiredTasks()) }

    func stopAgentTaskLog(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwiredTasks()) }

    /// The scheduled-task screens hang off the agents tab, not the context tab, so a test that reaches one of
    /// these nine routes by accident names the surface it stumbled into.
    private func unwiredTasks() -> APIError {
        .business(code: -1, message: "this test never wires the scheduled tasks")
    }
}

/// The four chat-tab dependencies for tests that never open the tab.
///
/// Same rule as `UnwiredCatalogs`: every method fails, so a screen reaching this object in a test shows up as
/// a failing call rather than as a list or a stream that quietly renders nothing.
struct UnwiredChat: SessionCataloging, ChatHistoryReading, AgentCommanding, AgentStreaming {
    private func unwired() -> APIError {
        .business(code: -1, message: "this test never wires the chat tab")
    }

    func sessionPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SessionSummary>, APIError> { .failure(unwired()) }

    func renameSession(_ session: SessionSummary, to title: String) async -> Result<EmptyResponse, APIError> {
        .failure(unwired())
    }

    func setSessionStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteSession(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func clearMessages(sessionId: String) async -> Result<AgentCommandReply, APIError> { .failure(unwired()) }

    func history(sessionId: String) async -> Result<[ChatHistoryLog], APIError> { .failure(unwired()) }

    func command(_ request: CommandAgentRequest) async -> Result<AgentCommandReply, APIError> { .failure(unwired()) }

    func chat(_ request: ChatAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        AsyncThrowingStream { $0.finish(throwing: unwired()) }
    }

    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        AsyncThrowingStream { $0.finish(throwing: unwired()) }
    }
}

/// The four system-tab catalogs for tests that never open the tab.
///
/// Same rule as the other two doubles: every method fails, so a screen reaching this object in a test shows up
/// as a failing call rather than as a list that quietly renders nothing.
struct UnwiredSystem: EnvVarCataloging, ApiKeyCataloging, ChannelCataloging, TokenStatsCataloging {
    private func unwired() -> APIError {
        .business(code: -1, message: "this test never wires the system tab")
    }

    func envVarPage(keyword: String?, num: Int, size: Int) async -> Result<Page<EnvVarSummary>, APIError> {
        .failure(unwired())
    }

    func createEnvVar(_ draft: EnvVarDraft) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func updateEnvVar(id: Int64, _ change: EnvVarChange) async -> Result<EmptyResponse, APIError> {
        .failure(unwired())
    }

    func setEnvVarStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteEnvVar(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func apiKeyPage(
        keyword: String?,
        enabled: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ApiKeySummary>, APIError> { .failure(unwired()) }

    func createApiKey(_ draft: ApiKeyDraft) async -> Result<ApiKeyCreatedSummary, APIError> { .failure(unwired()) }

    func updateApiKey(id: Int64, _ change: ApiKeyChange) async -> Result<EmptyResponse, APIError> {
        .failure(unwired())
    }

    func setApiKeyStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteApiKey(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func regenerateApiKey(id: Int64) async -> Result<ApiKeyCreatedSummary, APIError> { .failure(unwired()) }

    func channelPage(
        keyword: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ChannelSummary>, APIError> { .failure(unwired()) }

    func createChannel(_ draft: ChannelDraft) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func updateChannel(id: Int64, _ change: ChannelChange) async -> Result<EmptyResponse, APIError> {
        .failure(unwired())
    }

    func setChannelStatus(id: Int64, running: Bool) async -> Result<EmptyResponse, APIError> {
        .failure(unwired())
    }

    func deleteChannel(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func sandboxStatuses(sessionIds: [String]) async -> Result<SandboxStatusMap, APIError> { .failure(unwired()) }

    func startWechatLogin(id: Int64) async -> Result<WechatQrCode, APIError> { .failure(unwired()) }

    func wechatLoginStatus(id: Int64) async -> Result<WechatLoginUpdate, APIError> { .failure(unwired()) }

    func cancelWechatLogin(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func tokenAggregation(
        startTime: String?,
        endTime: String?
    ) async -> Result<TokenStatsPayload, APIError> { .failure(unwired()) }

    func tokenTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> { .failure(unwired()) }

    func tokenModelTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> { .failure(unwired()) }

    func tokenAgentTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> { .failure(unwired()) }

    func tokenSessionTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> { .failure(unwired()) }
}

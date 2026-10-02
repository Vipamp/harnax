import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The MCP surface for the screens' tests.
///
/// A request with nothing queued answers as a decoding failure and still shows up in the call log, which is
/// the discipline the other doubles use — a screen that reaches past the flow under test fails loudly instead
/// of reading an empty page.
///
/// `gateWrites` parks the writes — the form's and the client registration — because the interesting behaviour
/// is the window between the tap and the answer: the button is disabled on `isSaving`, and a second tap that
/// lands before SwiftUI has redrawn must not post the server twice.
final class FakeMcpServers: McpCataloging, @unchecked Sendable {
    private(set) var pageRequests: [(keyword: String?, status: Int?, type: String?, num: Int, size: Int)] = []
    var pageReplies: [Result<Page<McpServerRow>, APIError>] = []

    private(set) var detailRequests: [Int64] = []
    var detailReplies: [Result<McpServerRow, APIError>] = []

    private(set) var createRequests: [McpServerDraft] = []
    var createReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var updateRequests: [(id: Int64, patch: McpServerPatch)] = []
    var updateReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var statusCalls: [(id: Int64, enabled: Bool)] = []
    private(set) var relatedAgentRequests: [Int64] = []
    private(set) var deleteRequests: [Int64] = []
    private(set) var testCalls: [Int64] = []
    private(set) var toolRequests: [Int64] = []
    private(set) var oauthStatusRequests: [Int64] = []
    var oauthStatusReplies: [Result<McpOAuthStatus, APIError>] = []
    private(set) var oauthRevokeRequests: [Int64] = []
    var oauthRevokeReplies: [Result<McpOAuthRevokeResult, APIError>] = []
    private(set) var oauthURLRequests: [(id: Int64, scope: String?)] = []
    var oauthURLReplies: [Result<McpOAuthAuthorization, APIError>] = []
    private(set) var oauthDiscoverRequests: [Int64] = []
    var oauthDiscoverReplies: [Result<McpOAuthDiscovery, APIError>] = []
    private(set) var oauthClientRequests: [(id: Int64, draft: McpOAuthClientDraft)] = []
    var oauthClientReplies: [Result<McpOAuthDiscovery, APIError>] = []
    private(set) var oauthExchangeRequests: [McpOAuthExchangeDraft] = []
    var oauthExchangeReplies: [Result<McpOAuthExchangeOutcome, APIError>] = []

    var gateWrites = false

    /// The list's switch parks on its own dial, so a second tap can be thrown while the refusal is still out
    /// (`RowWriteReentryTests`).
    var gateStatusWrites = false

    private var parked: [() -> Void] = []

    /// The page read is parkable: an append has to be able to stay in flight while the reader changes the
    /// query (`ListAppendIdentityTests`).
    let pageGate = PageReadGate<Result<Page<McpServerRow>, APIError>>()

    func mcpPage(
        keyword: String?,
        status: Int?,
        type: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<McpServerRow>, APIError> {
        pageRequests.append((keyword: keyword, status: status, type: type, num: num, size: size))
        return await pageGate.absorb(pageReplies.isEmpty ? .failure(.decoding) : pageReplies.removeFirst())
    }

    func mcpServer(id: Int64) async -> Result<McpServerRow, APIError> {
        detailRequests.append(id)
        return take(from: \.detailReplies)
    }

    func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError> {
        createRequests.append(draft)
        return await gated(\.createReplies)
    }

    func updateMCPServer(id: Int64, patch: McpServerPatch) async -> Result<EmptyResponse, APIError> {
        updateRequests.append((id: id, patch: patch))
        return await gated(\.updateReplies)
    }

    func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusCalls.append((id: id, enabled: enabled))
        guard gateStatusWrites else { return .failure(.decoding) }
        // Still a refusal, only later: the point of the park is the window, not the verdict.
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: .failure(.decoding)) }
        }
    }

    func mcpRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> {
        relatedAgentRequests.append(id)
        return .failure(.decoding)
    }

    func deleteMCPServer(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteRequests.append(id)
        return .failure(.decoding)
    }

    func testMcpConnectivity(id: Int64) async -> Result<Bool, APIError> {
        testCalls.append(id)
        return .failure(.decoding)
    }

    func mcpTools(id: Int64) async -> Result<[McpToolRow], APIError> {
        toolRequests.append(id)
        return .failure(.decoding)
    }

    func discoverMcpOAuth(id: Int64) async -> Result<McpOAuthDiscovery, APIError> {
        oauthDiscoverRequests.append(id)
        return await gated(\.oauthDiscoverReplies)
    }

    func saveOAuthClient(
        id: Int64,
        _ draft: McpOAuthClientDraft
    ) async -> Result<McpOAuthDiscovery, APIError> {
        oauthClientRequests.append((id: id, draft: draft))
        return await gated(\.oauthClientReplies)
    }

    func exchangeOAuthCode(_ draft: McpOAuthExchangeDraft) async -> Result<McpOAuthExchangeOutcome, APIError> {
        oauthExchangeRequests.append(draft)
        return take(from: \.oauthExchangeReplies)
    }

    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError> {
        oauthStatusRequests.append(id)
        return take(from: \.oauthStatusReplies)
    }

    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError> {
        oauthRevokeRequests.append(id)
        return take(from: \.oauthRevokeReplies)
    }

    func mcpAuthorizeURL(id: Int64, scope: String?) async -> Result<McpOAuthAuthorization, APIError> {
        oauthURLRequests.append((id: id, scope: scope))
        return take(from: \.oauthURLReplies)
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func gated<T>(
        _ queue: ReferenceWritableKeyPath<FakeMcpServers, [Result<T, APIError>]>
    ) async -> Result<T, APIError> {
        guard gateWrites else { return take(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.take(from: queue)) }
        }
    }

    /// An unqueued request is a test bug, and `.decoding` is what the real facade answers with when the
    /// envelope it got cannot be read.
    private func take<T>(
        from queue: ReferenceWritableKeyPath<FakeMcpServers, [Result<T, APIError>]>
    ) -> Result<T, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

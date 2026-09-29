import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The MCP surface for the form's tests.
///
/// Only the two writes are modelled: everything else answers as a decoding failure and still shows up in the
/// call log, which is the discipline the other doubles use — a screen that reaches past the write under test
/// fails loudly instead of reading an empty page.
///
/// `gateWrites` parks both, because the interesting behaviour is the window between the tap and the answer:
/// the save button is disabled on `isSaving`, and a second tap that lands before SwiftUI has redrawn must not
/// post the server a second time.
final class FakeMcpServers: McpCataloging, @unchecked Sendable {
    private(set) var pageRequests: [(keyword: String?, status: Int?, type: String?, num: Int, size: Int)] = []
    var pageReplies: [Result<Page<McpServerRow>, APIError>] = []

    private(set) var detailRequests: [Int64] = []
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
    private(set) var oauthRevokeRequests: [Int64] = []
    private(set) var oauthURLRequests: [(id: Int64, scope: String?)] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    func mcpPage(
        keyword: String?,
        status: Int?,
        type: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<McpServerRow>, APIError> {
        pageRequests.append((keyword: keyword, status: status, type: type, num: num, size: size))
        return pageReplies.isEmpty ? .failure(.decoding) : pageReplies.removeFirst()
    }

    func mcpServer(id: Int64) async -> Result<McpServerRow, APIError> {
        detailRequests.append(id)
        return .failure(.decoding)
    }

    func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError> {
        createRequests.append(draft)
        return await write(\.createReplies)
    }

    func updateMCPServer(id: Int64, patch: McpServerPatch) async -> Result<EmptyResponse, APIError> {
        updateRequests.append((id: id, patch: patch))
        return await write(\.updateReplies)
    }

    func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusCalls.append((id: id, enabled: enabled))
        return .failure(.decoding)
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

    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError> {
        oauthStatusRequests.append(id)
        return .failure(.decoding)
    }

    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError> {
        oauthRevokeRequests.append(id)
        return .failure(.decoding)
    }

    func mcpAuthorizeURL(id: Int64, scope: String?) async -> Result<McpOAuthAuthorization, APIError> {
        oauthURLRequests.append((id: id, scope: scope))
        return .failure(.decoding)
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func write(
        _ queue: ReferenceWritableKeyPath<FakeMcpServers, [Result<EmptyResponse, APIError>]>
    ) async -> Result<EmptyResponse, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    /// An unqueued write is a test bug, and `.decoding` is what the real facade answers with when the envelope
    /// it got cannot be read.
    private func next(
        from queue: ReferenceWritableKeyPath<FakeMcpServers, [Result<EmptyResponse, APIError>]>
    ) -> Result<EmptyResponse, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

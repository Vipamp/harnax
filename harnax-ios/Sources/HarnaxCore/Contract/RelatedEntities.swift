import Foundation

/// Blast-radius rows shared by three domains: MCP, CLI and the agent wizard all ask "who is bound to
/// this?" before a write, and all three get the same two shapes back.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:179-198`
public struct RelatedAgent: Decodable, Equatable, Sendable {
    public let agentId: Int64?
    public let agentName: String?
    public let status: Int?

    public var isEnabled: Bool { status.hxFlag }
}

/// `sourceType` is the free string `"channel"` or `"session"`; `agentName` only arrives when the query
/// was made by CLI, which is why the owning agent has to be shown conditionally.
public struct RelatedSession: Decodable, Equatable, Identifiable, Sendable {
    public let sessionId: String?
    public let sourceType: String?
    public let sourceName: String?
    public let agentName: String?

    public var id: String { sessionId ?? "" }
    public var isChannel: Bool { sourceType == "channel" }
    /// The console shows the channel or session title, and the id stands in when the row carries none
    /// (`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:200-212`).
    public var displayName: String { hxPresented(sourceName) ?? id }
    public var ownerName: String? { hxPresented(agentName) }
}

/// One line of `POST /api/admin/agents/refresh-sessions`. The endpoint answers `200` with a per-session
/// verdict, so a partially failed refresh is a success response the UI must not report as clean.
public struct SessionRefreshOutcome: Decodable, Equatable, Identifiable, Sendable {
    public let sessionId: String?
    public let success: Bool?
    public let error: String?

    public var id: String { sessionId ?? "" }
    public var failed: Bool { success == false }
    /// The router's own sentence for a refused refresh; the panel shows it on the row rather than in a banner.
    public var reason: String? { hxPresented(error) }
}

public struct SessionRefreshRequest: Encodable, Sendable {
    private let sessionIds: [String]

    public init(sessionIds: [String]) {
        self.sessionIds = sessionIds
    }
}

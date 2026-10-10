import Foundation

/// The answer the user gave for one tool a `ToolConfirmEvent` is asking about.
///
/// Three answers, because the wire has three shapes: the run may go ahead, it may go ahead and stop asking
/// next time, or it may not go ahead at all. The console only ever sends the first and the third
/// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:1735-1754`); 「总是允许」 is the iOS affordance
/// `FEATURES.md` §3 asks for, and it can only be expressed in per-tool mode
/// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:150-160`) — the bulk
/// body has no field that could carry it.
public enum ToolConfirmAnswer: String, Codable, Sendable, Equatable, CaseIterable {
    /// Let this one call run.
    case allowed
    /// Let it run and remember the tool, so a later call does not park again.
    case alwaysAllowed
    /// Refuse it.
    case denied

    /// The `isConfirmed` this answer contributes. A standing rule is still an approval
    /// (`DefaultAgentRunner.kt:342-442` reads `confirmed` and `alwaysAllow` as two separate fields).
    public var isConfirmed: Bool { self != .denied }

    /// Whether this answer needs the per-tool leg of the request. Only `ToolConfirmResult.alwaysAllow` can
    /// say it, so an answer that says it cannot go bulk.
    public var addsPermissionRule: Bool { self == .alwaysAllowed }

    /// The label of the choice, as an action.
    public var titleKey: String {
        switch self {
        case .allowed: return "chat.confirm.allow"
        case .alwaysAllowed: return "chat.confirm.always"
        case .denied: return "chat.confirm.deny"
        }
    }

    /// The badge the row keeps once the answer has gone out — past tense, because the choice is made.
    public var settledTitleKey: String {
        switch self {
        case .allowed: return "chat.confirm.answer.allowed"
        case .alwaysAllowed: return "chat.confirm.answer.alwaysAllowed"
        case .denied: return "chat.confirm.answer.denied"
        }
    }
}

/// The screen's door to `POST /api/router/agent/confirm`.
///
/// Narrower than `AgentStreaming` on purpose: a host that can start a turn is not automatically a host that
/// can answer one, and the chat screen has to be able to say "this build shows the request and waits"
/// rather than offer a control that can only fail. The signature is the point of the whole protocol — an
/// answer is not a fire-and-forget call, it answers with a stream of its own that carries the resumed run
/// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:221`),
/// and that stream may hold another confirmation.
public protocol ToolConfirming: Sendable {
    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error>
}

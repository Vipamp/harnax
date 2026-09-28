import Foundation
import HarnaxCore
import HarnaxKit

/// The three places the MCP screens decide words rather than layout: what a transport or auth value is
/// called, what a connectivity probe means, and what an OAuth row may claim.
///
/// They live together and outside the views because all three are the parts with a rule attached to them —
/// the labels fall back to the server's own text for a value this build does not know, the probe has three
/// outcomes and not two, and the OAuth badge is only ever allowed to say 需要授权.
public enum McpLabels {
    /// An unknown transport (`McpTransport.other`) shows its raw string: inventing a label for a value the
    /// deployment added later would be a guess the user cannot check.
    public static func transport(_ value: McpTransport) -> String {
        guard let key = value.titleKey else {
            if case let .other(raw) = value, let raw { return raw }
            return ""
        }
        return hx(key)
    }

    public static func auth(_ value: McpAuthKind) -> String {
        guard let key = value.titleKey else {
            if case let .other(raw) = value, let raw { return raw }
            return ""
        }
        return hx(key)
    }
}

/// The result of one `POST /{id}/connectivity-test`, including the case where the server never answered.
///
/// Three outcomes because the endpoint conflates two different "no": a refusal carries the reason in the
/// envelope's `message` — stdio policy, a disabled row, an OAUTH2 row that cannot be probed without a user
/// token (`McpServerServiceImpl.kt:441-464`) — while the console's own 15 s deadline
/// (`harnax-webui/src/pages/mcp/index.tsx:381-438`) is a local judgement about a request still in flight.
public enum McpTestOutcome: Equatable, Sendable {
    case passed
    /// `nil` when the server answered success with `false` and gave no reason.
    case refused(reason: String?)
    case timedOut

    public var copy: String {
        switch self {
        case .passed: hx("mcp.test.passed")
        case .refused(.some(let reason)) where !reason.isEmpty: reason
        case .refused: hx("mcp.test.failed")
        case .timedOut: hx("mcp.test.timeout")
        }
    }

    public var tone: PaletteSlot {
        switch self {
        case .passed: .success
        case .refused: .danger
        case .timedOut: .warning
        }
    }

    public var isPassed: Bool { self == .passed }
}

public enum McpConnectivity {
    /// The web console races the probe against a 15 s timer and keeps the request running either way
    /// (`harnax-webui/src/pages/mcp/index.tsx:381-438`). The work task is deliberately *not* a child of the
    /// group, so timing out leaves it on the wire instead of cancelling a probe the server is still doing.
    public static func probe(
        timeout: TimeInterval,
        _ work: @escaping @Sendable () async -> Result<Bool, APIError>
    ) async -> McpTestOutcome {
        let task = Task { await work() }
        let timedOut = await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(timeout, 0.001) * 1_000_000_000))
                return true
            }
            group.addTask {
                _ = await task.value
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
        if timedOut { return .timedOut }
        switch await task.value {
        case .success(true): return .passed
        case .success(false): return .refused(reason: nil)
        case let .failure(error): return .refused(reason: ErrorMessage.text(for: error))
        }
    }
}

/// Whether the tool list may be asked for at all, and what to say when it comes back empty or refused.
///
/// `GET /{id}/list_tools` refuses a disabled row before it opens a connection
/// (`McpServerServiceImpl.kt:445-447`), so the un-enabled case is its own state rather than a failed call
/// (`harnax-webui/src/pages/mcp/detail.tsx:79-83,383-392`).
public enum McpToolState: Equatable {
    /// The row is switched off, so nothing was asked.
    case notEnabled
    case loading
    case loaded(tools: [McpToolRow])
    /// A refusal is not "no tools": the message is the server's, and it usually says the tool surface is
    /// unreachable for a reason the operator has to fix.
    case failed(message: String)

    public var message: String? {
        switch self {
        case .notEnabled: hx("mcp.tools.disabled")
        case .loading: nil
        case .loaded(let tools) where tools.isEmpty: hx("mcp.tools.empty")
        case .loaded: nil
        case .failed(let message): message
        }
    }

    public var tools: [McpToolRow] {
        if case let .loaded(tools) = self { return tools }
        return []
    }
}

/// What the OAuth block of the detail screen may and may not say.
///
/// One entry, one badge: `authorized` is the only field read for the verdict, because an expired token and
/// a user who never authorized both answer `authorized = false` and the DTO cannot separate them
/// (`McpOAuthStatusResponse.kt:15-19`, `McpOAuthUserServiceImpl.kt:233-237`). Saying "已过期" would be a
/// claim the payload does not support, so the copy stays at 需要授权.
public enum McpOAuthPresentation {
    public static func badge(_ status: McpOAuthStatus?) -> String? {
        guard let status else { return nil }
        return hx(status.authorized ? "mcp.oauth.authorized" : "mcp.oauth.required")
    }

    public static func tone(for status: McpOAuthStatus?) -> PaletteSlot {
        guard let status else { return .textTertiary }
        return status.authorized ? .success : .warning
    }

    public static func needsAuthorization(_ status: McpOAuthStatus?) -> Bool {
        status?.authorized == false
    }

    public static func actionKey(for status: McpOAuthStatus?) -> String {
        status?.authorized == true ? "mcp.oauth.action.reauthorize" : "mcp.oauth.action.authorize"
    }
}

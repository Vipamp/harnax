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

/// What the delete dialog is allowed to claim about the blast radius.
///
/// The web console names the dependents rather than only counting them
/// (`harnax-webui/src/pages/mcp/index.tsx:327-337`: `agents.slice(0, 5).map(a => a.agentName).join('、')`),
/// because an operator who is about to unbind five agents reads a name differently from a number. The join
/// character is that language's own list separator and it comes from the catalog, not from this file: 顿号 in
/// Chinese, comma-space in English, and a hardcoded 「、」 would read as broken punctuation on an English row.
///
/// The console truncates at five silently — the sentence still says seven. Here the tail says so
/// (`mcp.delete.names.more`); that marker is this app's addition, not a transcription.
public enum McpDeleteCopy {
    /// How many names the sentence carries before it stops being readable on a phone-width dialog.
    public static let nameLimit = 5

    public static func message(for agents: [RelatedAgent]) -> String {
        guard !agents.isEmpty else { return hx("mcp.delete.clear") }
        let names = nameList(for: agents)
        guard !names.isEmpty else {
            // RelatedAgentInfo declares `agentName` non-null server-side, so this is a payload that has lost
            // the names in transit. The count-only sentence is still true; a bare pair of brackets is not.
            return hx("mcp.delete.bound", agents.count)
        }
        return hx("mcp.delete.boundNamed", agents.count, names)
    }

    /// The first `nameLimit` names, joined for this language. Empty when the rows carry no readable name at
    /// all, which is the caller's signal to fall back to the count.
    public static func nameList(for agents: [RelatedAgent]) -> String {
        let names = agents.prefix(nameLimit).compactMap { hxPresented($0.agentName) }
        guard !names.isEmpty else { return "" }
        let joined = names.joined(separator: hx("mcp.delete.nameSeparator"))
        return agents.count > nameLimit ? joined + hx("mcp.delete.names.more") : joined
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

    /// How the issuer was located, in the console's own words
    /// (`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:22-35`).
    ///
    /// A source this build does not know shows the raw column: the discovery response names one of three
    /// values today, and a fourth way of finding an authorization server should read as itself rather than
    /// as one of these three.
    public static func issuerSource(_ raw: String?) -> String? {
        guard let value = hxPresented(raw) else { return nil }
        switch value {
        case "CONFIG": return hx("mcp.oauth.setup.issuerSource.config")
        case "PROTECTED_RESOURCE": return hx("mcp.oauth.setup.issuerSource.protectedResource")
        case "RESOURCE_METADATA": return hx("mcp.oauth.setup.issuerSource.resourceMetadata")
        default: return value
        }
    }
}

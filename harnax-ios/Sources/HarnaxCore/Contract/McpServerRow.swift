import Foundation

/// One row of `GET /api/admin/mcp/page`, and the same shape `GET /api/admin/mcp/{id}` answers.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt:14-44`.
/// Every field is declared `val x: T? = null`, so any of them may arrive as `null` or be dropped by the
/// server's `non_null` inclusion; `Identifiable.ID` is `Int64?` for the same reason `AgentSummary`'s is.
///
/// The two list columns the console derives (`harnax-webui/src/pages/mcp/index.tsx:44`) are modelled here
/// rather than in the view: an stdio row has no URL to show and a network row has no command.
public struct McpServerRow: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    public let type: String?
    public let command: String?
    public let url: String?
    public let authType: String?
    public let oauthConfig: McpOAuthConfigValues?
    public let status: Int?
    public let isPublic: Int?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?
    public let headers: [McpConfigEntry]?
    public let envParams: [EnvParamEntry]?

    /// Only an explicit `0` is off. `listTools` refuses with `status == 0`
    /// (`McpServerServiceImpl.kt:445`), so a row that answered no status column at all is treated as
    /// enabled here exactly as it is by the server — and as the agent and team rows do.
    public var isEnabled: Bool { status != 0 }
    public var isShared: Bool { isPublic == 1 }
    public var title: String? { hxPresented(name) }
    public var transport: McpTransport { McpTransport(raw: type) }
    public var auth: McpAuthKind { McpAuthKind(raw: authType) }
    public var requiresPerUserOAuth: Bool { auth == .oauth2 }

    /// stdio shows the command line, every other transport the URL
    /// (`harnax-webui/src/pages/mcp/index.tsx:44`).
    public var endpoint: String? {
        transport.usesCommand ? hxPresented(command) : hxPresented(url)
    }

    /// Header entries whose value came back masked. The edit form has to echo those strings back for the
    /// stored secret to survive (`SecretFieldEncryptor.kt:46-49`), so the count is not a display detail.
    public var maskedHeaderKeys: Set<String> {
        Set((headers ?? []).filter(\.isMasked).map(\.key))
    }

    public var headerEntries: [McpConfigEntry] { headers ?? [] }
    public var envEntries: [EnvParamEntry] { envParams ?? [] }
}

/// The transport a row stores. Recognition is trimmed and case-insensitive because
/// `McpStdioPolicy.isStdio` trims and lowercases (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt:25`);
/// an unrecognised value is still a row that has to render, so it keeps its own text.
public enum McpTransport: Equatable, Sendable {
    case stdio
    case sse
    case streamablehttp
    case other(raw: String?)

    static let known: [McpTransport] = [.stdio, .sse, .streamablehttp]

    /// The two transports a new row may be created with: stdio is refused by the deployment policy
    /// (`McpStdioPolicy.kt:20-31`), so the create form never offers it
    /// (`harnax-webui/src/pages/mcp/components/CreateForm.tsx:26-32`).
    public static let creatable: [McpTransport] = [.sse, .streamablehttp]

    public init(raw: String?) {
        switch hxPresented(raw)?.lowercased() {
        case "stdio": self = .stdio
        case "sse": self = .sse
        case "streamablehttp": self = .streamablehttp
        default: self = .other(raw: hxPresented(raw))
        }
    }

    /// What has to go back on the wire. The server matches these three strings exactly in
    /// `validateTypeAndFields` (`McpServerServiceImpl.kt:371-388`), so an unknown row's value is preserved
    /// rather than normalised.
    public var wireValue: String? {
        switch self {
        case .stdio: "stdio"
        case .sse: "sse"
        case .streamablehttp: "streamablehttp"
        case let .other(raw): raw
        }
    }

    public var isStdio: Bool { self == .stdio }
    public var usesCommand: Bool { isStdio }

    /// `nil` for an unknown value, which the caller shows verbatim instead of labelling.
    public var titleKey: String? {
        switch self {
        case .stdio: "mcp.type.stdio"
        case .sse: "mcp.type.sse"
        case .streamablehttp: "mcp.type.streamablehttp"
        case .other: nil
        }
    }
}

/// `authType` on the wire. `McpAuthTypes.BASIC` (`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt:18`)
/// is declared by the schema but rejected on write (`McpServerServiceImpl.kt:396-404`), so it only ever
/// shows up on a legacy row and gets a label rather than a picker entry.
public enum McpAuthKind: Equatable, Sendable {
    case none
    case staticHeader
    case oauth2
    case basic
    case other(raw: String?)

    /// The three the form offers — BASIC is gone from the console's picker
    /// (`harnax-webui/src/pages/mcp/components/CreateForm.tsx:35-43`).
    public static let selectable: [McpAuthKind] = [.none, .staticHeader, .oauth2]

    /// An omitted auth type is NONE on the server (`McpServerServiceImpl.kt:394-395`).
    public init(raw: String?) {
        switch hxPresented(raw)?.uppercased() {
        case nil, "NONE": self = .none
        case "STATIC_HEADER": self = .staticHeader
        case "OAUTH2": self = .oauth2
        case "BASIC": self = .basic
        default: self = .other(raw: hxPresented(raw)?.uppercased())
        }
    }

    public var wireValue: String? {
        switch self {
        case .none: "NONE"
        case .staticHeader: "STATIC_HEADER"
        case .oauth2: "OAUTH2"
        case .basic: "BASIC"
        case let .other(raw): raw
        }
    }

    public var titleKey: String? {
        switch self {
        case .none: "mcp.auth.none"
        case .staticHeader: "mcp.auth.staticHeader"
        case .oauth2: "mcp.auth.oauth"
        case .basic: "mcp.auth.basic"
        case .other: nil
        }
    }
}

/// One `headers[]` entry of `McpServerResponse`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpConfigEntry.kt:10-18`).
///
/// `key`, `value` and `secret` all carry non-null Kotlin defaults, so they are always serialised — hence
/// non-optional here. A secret entry's `value` arrives masked
/// (`McpServerResponse.kt:109-116`: `前3****后4`, or `******` for anything short or unreadable).
public struct McpConfigEntry: Codable, Equatable, Sendable {
    public let key: String
    public let value: String
    public let secret: Bool

    public init(key: String = "", value: String = "", secret: Bool = false) {
        self.key = key
        self.value = value
        self.secret = secret
    }

    public var name: String? { hxPresented(key) }

    /// Either mask shape contains four asterisks, which is the same test the server applies before it
    /// decides a value is "unchanged" rather than "typed" (`SecretFieldEncryptor.kt:62`).
    public var isMasked: Bool { secret && value.contains("****") }
}

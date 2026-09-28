import Foundation

/// One environment parameter a tool, MCP server or CLI package declares.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt:12-31`.
/// `required` and `secret` are the only flags in this contract that arrive as JSON booleans rather than
/// 0/1 integers — the Kotlin side declares them `val required: Boolean = false`, so they are always
/// present. Everything else on the wire goes through `hxFlag`.
///
/// `Encodable` because the binding forms post entries back; the read-only context lists only decode them.
public struct EnvParamEntry: Codable, Equatable, Sendable {
    public let id: Int64?
    public let envParamName: String
    public let description: String?
    public let required: Bool
    public let secret: Bool
    public let defaultValue: String?

    public init(
        id: Int64? = nil,
        envParamName: String,
        description: String? = nil,
        required: Bool = false,
        secret: Bool = false,
        defaultValue: String? = nil
    ) {
        self.id = id
        self.envParamName = envParamName
        self.description = description
        self.required = required
        self.secret = secret
        self.defaultValue = defaultValue
    }
}

public extension Array where Element == EnvParamEntry {
    /// `requiredEnvParamKeys` is a server-side shortcut for the same set; the client falls back to it when
    /// a list endpoint ships the key names but not the full entries.
    var requiredNames: [String] {
        filter(\.required).map(\.envParamName)
    }
}

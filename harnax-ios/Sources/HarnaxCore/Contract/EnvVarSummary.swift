import Foundation

/// One row of `GET /api/admin/env-variables/page`, and the same shape `GET /api/admin/env-variables/{id}`
/// answers (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/EnvVariableResponse.kt:7-34`).
///
/// Every property on that DTO is declared nullable, and the stack serialises non-null only
/// (`default-property-inclusion: non_null`), so any of the nine keys can be absent on the wire.
///
/// For a sensitive row `envValue` is already the display mask: the service decrypts, then runs
/// `maskValue` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:221-243`,
/// `:274-278`), and a value it cannot open answers as `******`. iOS never sees the plaintext and must
/// never send what it saw back — see `EnvVarChange`.
public struct EnvVarSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let envKey: String?
    public let envValue: String?
    public let description: String?
    /// 0/1 on this DTO. The one place this flag really is a JSON boolean is `GET /env-variables/list`,
    /// the agent-config projection (`EnvVariableServiceImpl.kt:245-271`), which this row is not.
    public let sensitive: Int?
    public let enabled: Int?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?

    /// A row whose switch never arrived reads as enabled, which is the column's own default
    /// (`EnvVariableCreateRequest` falls back to `enabled = 1`, `EnvVariableServiceImpl.kt:75`).
    public var isEnabled: Bool { enabled != 0 }
    public var isSensitive: Bool { sensitive.hxFlag }

    /// The key as the console prints it; `nil` when the row carries none, which the wire allows.
    public var key: String? { hxPresented(envKey) }

    /// The value column — a mask for a sensitive row, the stored text for any other.
    public var displayValue: String? { hxPresented(envValue) }
    public var note: String? { hxPresented(description) }
    public var title: String? { key }
}

/// The dropdown projection the *agent* binding form consumes: `GET /api/admin/env-variables/list`, whose
/// `data` is a bare array of four-key maps (`EnvVariableController.kt:39-46`, built by
/// `EnvVariableServiceImpl.listForAgentConfig()` at `:245-271`).
///
/// Three differences from `EnvVarSummary` on the same table, all of them server behaviour:
///
/// * `sensitive` is a real JSON boolean here, because the builder puts a Kotlin `Boolean` in the map
///   (`:269`) where the response DTO declares `Int`. It is the one row shape in this stack that is.
/// * There is no `description`/`enabled`/`creator` — the projection answers exactly four keys, so a
///   candidate cannot be managed from this read; the management screen uses `EnvVarSummary`.
/// * `displayValue` is the **masked** text for a sensitive row (`maskValue`, `:274-278`: `******` for
///   four characters or fewer, else `1st****last` or `take(3)****takeLast(2)`) and the plaintext for any
///   other. Either way it is a label.
///
/// Decodable only, and with no public initializer: an agent binding submits the variable's **id**
/// (`AgentEnvBindingDraft.referenced`), never what was shown next to it, so a value that could only be
/// read from the server can never be echoed back into a request.
public struct EnvVarCandidate: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let envKey: String?
    /// For display only — see the type doc. Never part of a request body.
    public let displayValue: String?
    public let sensitive: Bool?

    /// The key as the console prints it; `nil` when the row carries none, which the wire allows.
    public var key: String? { hxPresented(envKey) }
    public var isSensitive: Bool { sensitive ?? false }
    public var title: String? { key }

    /// Whether the row can bind at all: the map may omit the key, and a row without one is not selectable
    /// whatever the server did with the id.
    public var isBindable: Bool { id != nil && key != nil }
}

/// The create body (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/EnvVariableCreateRequest.kt:9-30`).
///
/// `envKey` and `envValue` are the two `@NotBlank` fields, so they always go out; the rest are left off
/// the JSON when unset rather than sent as nulls, because the server reads a missing `sensitive` as 0 and
/// a missing `enabled` as 1 (`EnvVariableServiceImpl.kt:74-75`).
public struct EnvVarDraft: Encodable, Equatable, Sendable {
    public let envKey: String
    public let envValue: String
    public let description: String?
    public let sensitive: Int?
    public let enabled: Int?

    public init(
        envKey: String,
        envValue: String,
        description: String? = nil,
        sensitive: Int? = nil,
        enabled: Int? = nil
    ) {
        self.envKey = envKey
        self.envValue = envValue
        self.description = description
        self.sensitive = sensitive
        self.enabled = enabled
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encode(envKey, forKey: .envKey)
        try box.encode(envValue, forKey: .envValue)
        try box.encodeIfPresent(description, forKey: .description)
        try box.encodeIfPresent(sensitive, forKey: .sensitive)
        try box.encodeIfPresent(enabled, forKey: .enabled)
    }

    private enum Key: String, CodingKey {
        case envKey, envValue, description, sensitive, enabled
    }
}

/// The edit body (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/EnvVariableUpdateRequest.kt:8-24`).
///
/// Three things this shape has to get right, all of them server behaviour rather than preference:
/// a `nil` field is left off the JSON and means "keep what is stored"; there is **no** `enabled` here, so
/// the switch is the separate `PUT /{id}/toggle`; and `envValue` must never carry the mask back — the
/// service would call `isUnchangedMask` on it, but only for a sensitive row, so an echoed mask on a
/// variable that has since been switched to non-sensitive would store the asterisks
/// (`EnvVariableServiceImpl.kt:137-161`, `:288-291`).
public struct EnvVarChange: Encodable, Equatable, Sendable {
    public let envKey: String?
    public let envValue: String?
    public let description: String?
    public let sensitive: Int?

    public init(
        envKey: String? = nil,
        envValue: String? = nil,
        description: String? = nil,
        sensitive: Int? = nil
    ) {
        self.envKey = envKey
        self.envValue = envValue
        self.description = description
        self.sensitive = sensitive
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encodeIfPresent(envKey, forKey: .envKey)
        try box.encodeIfPresent(envValue, forKey: .envValue)
        try box.encodeIfPresent(description, forKey: .description)
        try box.encodeIfPresent(sensitive, forKey: .sensitive)
    }

    private enum Key: String, CodingKey {
        case envKey, envValue, description, sensitive
    }
}

/// The key format both request DTOs validate with `@Pattern`
/// (`EnvVariableCreateRequest.kt:13`, `EnvVariableUpdateRequest.kt:11`).
///
/// Lives here because the console only checks it on create and not on rename
/// (`harnax-webui/src/pages/env-variable/components/UpdateForm.tsx:68-71`), so an iOS edit that skipped
/// the check would simply be refused by the backend.
public enum EnvVarKeyPattern {
    static var expression: NSRegularExpression? {
        try? NSRegularExpression(pattern: "^[A-Za-z_][A-Za-z0-9_]*$")
    }

    /// The 200-character ceiling is the other half of the same annotation.
    public static let lengthLimit = 200

    public static func isValid(_ key: String) -> Bool {
        guard !key.isEmpty, key.count <= lengthLimit else { return false }
        guard let expression else { return false }
        return expression.firstMatch(in: key, range: NSRange(key.startIndex..., in: key)) != nil
    }
}

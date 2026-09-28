import Foundation

/// One row of `GET /api/admin/api-keys/page` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyResponse.kt:8-41`).
///
/// All eleven keys can be absent — every property is declared nullable on that DTO and the stack ships
/// non-null only.
///
/// What is *not* on it matters as much: the web console's own typings claim `keyHash` and `active`
/// (`harnax-webui/src/services/ant-design-pro/typings.d.ts:299-341`), the backend returns neither
/// (`harnax-ios/specs/03-system-domain.md:111`), and the raw key is never among the columns at all. There
/// is also no `keyType` here, so the PERMANENT/SYSTEM rows the write paths protect cannot be picked out
/// client-side — and do not need to be, because the page query is `key_type = 'TEMPORARY'` already
/// (`harnax-entity/src/main/resources/mapper/ApiKeyMapper.xml:128-147`).
public struct ApiKeySummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    /// First 12 characters + `...` + last 4 of the key that was shown once at creation
    /// (`ApiKeyServiceImpl.kt:82`). The digest itself is never sent.
    public let keyPrefix: String?
    /// Comma-separated, and the server checks no value set (`ApiKeyCreateRequest.kt:14-16`).
    public let scopes: String?
    public let tenantId: Int64?
    public let rateLimit: Int?
    public let enabled: Int?
    public let expiresAt: String?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?

    /// 60 is what the insert defaults to when the request carries no rate limit (`ApiKeyServiceImpl.kt:93`).
    public var isEnabled: Bool { enabled != 0 }
    public var title: String? { hxPresented(name) }
    public var displayKey: String? { hxPresented(keyPrefix) }

    /// Every token as it arrived, in the order the server stored them. Unknown values stay listed: the
    /// console splits the same string on commas rather than mapping a fixed set
    /// (`harnax-webui/src/pages/api-key/index.tsx:181-308`), and a scope this side does not know is still
    /// a fact about the row.
    public var scopeTokens: [String] {
        guard let scopes = hxPresented(scopes) else { return [] }
        return scopes
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    /// `nil` for a row that carries no expiry, which the console words as “never expires”.
    public var expiryDate: Date? { hxServerDateTime(expiresAt) }

    /// The red “已过期” marker on the console's expiry column. Defaults to the wall clock so a row renders
    /// itself; a test passes its own instant.
    public func isExpired(at now: Date = Date()) -> Bool {
        guard let expiry = expiryDate else { return false }
        return expiry < now
    }

    /// “Never expires” is the column being absent or blank (`expiresAt = null`,
    /// `harnax-webui/src/pages/api-key/index.tsx:181-308`); a stamp this side cannot read is a separate
    /// case and is shown as the raw text rather than as a decision.
    public var hasNoExpiry: Bool { hxPresented(expiresAt) == nil }

    /// The row's own scopes, split into the pair the form offers and whatever else the string holds.
    public var scopeSelection: ApiKeyScopeSplit { ApiKeyScopeSplit(scopes: scopes) }

    public func manageable(by account: AccountSnapshot?) -> Bool {
        account?.canManage(creator: creator) ?? false
    }
}

/// The split of one `scopes` string. Kept as a value so an edit cannot quietly drop a token the form does
/// not have a switch for: the write-back re-joins the remainder untouched.
public struct ApiKeyScopeSplit: Equatable, Sendable {
    public let known: Set<ApiKeyScope>
    public let remainder: [String]

    public init(scopes: String?) {
        var known = Set<ApiKeyScope>()
        var remainder: [String] = []
        for token in (scopes ?? "").split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) })
        where !token.isEmpty {
            if let scope = ApiKeyScope(rawValue: token) { known.insert(scope) } else { remainder.append(token) }
        }
        self.known = known
        self.remainder = remainder
    }

    /// Canonical order first, foreign tokens after, so the string round-trips without losing either.
    public var joined: String {
        (ApiKeyScope.allCases.filter { known.contains($0) }.map(\.rawValue) + remainder).joined(separator: ",")
    }
}

/// The two scopes the console offers (`harnax-webui/src/pages/api-key/components/CreateForm.tsx:13-16`).
///
/// They are labels, not permissions: `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/AuthContext.kt:15-16`
/// says of the field “Retained for logging/observability only — not used for access control”, so iOS must
/// not branch on them (`harnax-ios/specs/03-system-domain.md:113-118`).
public enum ApiKeyScope: String, CaseIterable, Sendable {
    case chat
    case manager

    public var titleKey: String { "apikey.scope.\(rawValue)" }
}

/// `POST /api/admin/api-keys` and `POST /{id}/regenerate` both answer this, and the raw key exists *only*
/// in it (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyResponse.kt:59-72`).
///
/// The four fields are non-null on the Kotlin side over non-null entity columns
/// (`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ApiKeyEntity.kt:15-36`), so unlike a list row
/// none of them can be missing.
public struct ApiKeyCreatedSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64
    public let name: String
    public let rawKey: String
    public let keyPrefix: String

    public init(id: Int64, name: String, rawKey: String, keyPrefix: String) {
        self.id = id
        self.name = name
        self.rawKey = rawKey
        self.keyPrefix = keyPrefix
    }
}

/// The create body (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyCreateRequest.kt:8-26`).
///
/// `expiresAt` is a *string* the server parses with `ISO_LOCAL_DATE_TIME`
/// (`ApiKeyServiceImpl.kt:38`, `:95`), so it has to carry the `T` — the space form the response uses would
/// throw. Unset fields are left off the JSON so the server's own defaults apply: scope `chat` and rate
/// limit 60 (`:91-93`).
public struct ApiKeyDraft: Encodable, Equatable, Sendable {
    public let name: String
    public let scopes: String
    public let tenantId: Int64?
    public let rateLimit: Int?
    public let expiresAt: String?

    public init(
        name: String,
        scopes: String,
        tenantId: Int64? = nil,
        rateLimit: Int? = nil,
        expiresAt: String? = nil
    ) {
        self.name = name
        self.scopes = scopes
        self.tenantId = tenantId
        self.rateLimit = rateLimit
        self.expiresAt = expiresAt
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encode(name, forKey: .name)
        try box.encode(scopes, forKey: .scopes)
        try box.encodeIfPresent(tenantId, forKey: .tenantId)
        try box.encodeIfPresent(rateLimit, forKey: .rateLimit)
        try box.encodeIfPresent(expiresAt, forKey: .expiresAt)
    }

    private enum Key: String, CodingKey {
        case name, scopes, tenantId, rateLimit, expiresAt
    }
}

/// The edit body (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyUpdateRequest.kt:6-21`).
///
/// No `name`: the console disables that field because the route has nowhere to put it
/// (`harnax-webui/src/pages/api-key/components/UpdateForm.tsx:83`). A `nil` field means “unchanged”, and
/// for `expiresAt` an *empty* string means “clear it” — the server branches on `isBlank()`
/// (`ApiKeyServiceImpl.kt:130-132`), which is the only way back to “never expires”.
public struct ApiKeyChange: Encodable, Equatable, Sendable {
    public let scopes: String?
    public let tenantId: Int64?
    public let rateLimit: Int?
    public let enabled: Int?
    public let expiresAt: String?

    public init(
        scopes: String? = nil,
        tenantId: Int64? = nil,
        rateLimit: Int? = nil,
        enabled: Int? = nil,
        expiresAt: String? = nil
    ) {
        self.scopes = scopes
        self.tenantId = tenantId
        self.rateLimit = rateLimit
        self.enabled = enabled
        self.expiresAt = expiresAt
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encodeIfPresent(scopes, forKey: .scopes)
        try box.encodeIfPresent(tenantId, forKey: .tenantId)
        try box.encodeIfPresent(rateLimit, forKey: .rateLimit)
        try box.encodeIfPresent(enabled, forKey: .enabled)
        try box.encodeIfPresent(expiresAt, forKey: .expiresAt)
    }

    private enum Key: String, CodingKey {
        case scopes, tenantId, rateLimit, expiresAt, enabled
    }
}

/// Reads a server timestamp in either serialisation the stack answers with —
/// `2026-09-12 10:20:30` or the ISO `T` form (`harnax-ios/HARNESS-NOTES.md:54`).
///
/// Deliberately no time zone: these columns are a server's wall clock, and the alternative — assuming UTC
/// — would shift every expiry on the screen by the device's offset. An unreadable stamp is `nil`, which
/// the rows render as “no expiry” rather than as a guess.
func hxServerDateTime(_ raw: String?) -> Date? {
    guard let raw = hxPresented(raw) else { return nil }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = raw.contains("T") ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd HH:mm:ss"
    return formatter.date(from: raw)
}

/// The same clock in the one shape the write side accepts: `ISO_LOCAL_DATE_TIME`, seconds included,
/// because `ApiKeyServiceImpl.kt:95` and `:131` parse exactly that.
public func hxServerDateTimeString(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
    return formatter.string(from: date)
}

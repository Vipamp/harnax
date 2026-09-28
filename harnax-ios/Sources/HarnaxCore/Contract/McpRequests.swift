import Foundation

/// Body of `POST /api/admin/mcp` (`.../dto/McpServerCreateRequest.kt:11-56`).
///
/// Encoding notes that are load-bearing:
/// - `type` is the only field with a non-null Kotlin default (`"streamablehttp"`), so it is always sent.
/// - An omitted `authType` is NONE and an omitted `status`/`isPublic` is 1
///   (`McpServerServiceImpl.kt:135-136`, `:394-395`), so a plain create sends three fields and nothing else.
/// - `envParams` only survive on a stdio row: `createMcpServer` nulls the column for a network transport
///   (`McpServerServiceImpl.kt:147-151`), which is why the iOS form hides that section off stdio rows.
public struct McpServerDraft: Encodable, Equatable, Sendable {
    public let name: String
    public let description: String?
    public let type: String
    public let command: String?
    public let url: String?
    public let authType: String?
    public let oauthConfig: McpOAuthConfigValues?
    public let status: Int?
    public let isPublic: Int?
    public let headers: [McpConfigEntry]?
    public let envParams: [EnvParamEntry]?

    public init(
        name: String,
        description: String? = nil,
        type: String,
        command: String? = nil,
        url: String? = nil,
        authType: String? = nil,
        oauthConfig: McpOAuthConfigValues? = nil,
        status: Int? = nil,
        isPublic: Int? = nil,
        headers: [McpConfigEntry]? = nil,
        envParams: [EnvParamEntry]? = nil
    ) {
        self.name = name
        self.description = description
        self.type = type
        self.command = command
        self.url = url
        self.authType = authType
        self.oauthConfig = oauthConfig
        self.status = status
        self.isPublic = isPublic
        self.headers = headers
        self.envParams = envParams
    }
}

/// Body of `PUT /api/admin/mcp/update/{id}` (`.../dto/McpServerUpdateRequest.kt:10-58`).
///
/// Every field is nullable and the service applies each one inside `?.let`
/// (`McpServerServiceImpl.kt:175-252`), so *absent means unchanged* — Swift's synthesised `encodeIfPresent`
/// already gives that, and a form must therefore send only what the operator touched. Two non-obvious
/// consequences the form has to respect:
/// - `headers: []` clears the column while an omitted `headers` leaves it alone
///   (`McpServerServiceImpl.kt:241-243` plus `SecretFieldEncryptor.kt:35`).
/// - A secret entry that comes back still masked keeps the stored ciphertext; the same entry sent blank
///   clears it (`SecretFieldEncryptor.kt:43-49`). So the edit form echoes the mask it was given.
public struct McpServerPatch: Encodable, Equatable, Sendable {
    public var name: String?
    public var description: String?
    public var type: String?
    public var command: String?
    public var url: String?
    public var authType: String?
    public var oauthConfig: McpOAuthConfigValues?
    public var status: Int?
    public var isPublic: Int?
    public var headers: [McpConfigEntry]?
    public var envParams: [EnvParamEntry]?

    /// `nil` for a field the operator did not touch. `changed` on the arrays distinguishes "leave it"
    /// (`nil`) from "remove every entry" (`[]`).
    public init(
        name: String? = nil,
        description: String? = nil,
        type: String? = nil,
        command: String? = nil,
        url: String? = nil,
        authType: String? = nil,
        oauthConfig: McpOAuthConfigValues? = nil,
        status: Int? = nil,
        isPublic: Int? = nil,
        headers: [McpConfigEntry]? = nil,
        envParams: [EnvParamEntry]? = nil
    ) {
        self.name = name
        self.description = description
        self.type = type
        self.command = command
        self.url = url
        self.authType = authType
        self.oauthConfig = oauthConfig
        self.status = status
        self.isPublic = isPublic
        self.headers = headers
        self.envParams = envParams
    }

    /// `id` is on the Kotlin DTO but ignored by the controller, which takes it from the path
    /// (`McpServerController.kt:87-95`); it is deliberately not encoded.
    enum CodingKeys: String, CodingKey {
        case name, description, type, command, url, authType
        case oauthConfig, status, isPublic, headers, envParams
    }
}

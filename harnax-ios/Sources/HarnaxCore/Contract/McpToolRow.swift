import Foundation

/// One tool of `GET /api/admin/mcp/{id}/list_tools`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpToolResponse.kt:9-29`).
///
/// The controller flattens the upstream `inputSchema.properties` into `{name, type, description}` and
/// defaults a missing `type` to `"string"`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:154-171`).
/// Nothing else from the schema survives: `required`, `enum` and nesting are gone server-side, which is why
/// the detail screen shows a chip per parameter instead of an expandable tree
/// (`harnax-ios/specs/04-context-domains.md` 的「规格修正」).
public struct McpToolRow: Decodable, Equatable, Identifiable, Sendable {
    public let name: String
    public let parameters: [McpToolParameter]

    /// Two tools may share a name on a badly behaved server; the list is keyed on position at the call
    /// site, so this only has to be stable within one decoded answer.
    public var id: String { name }

    public var displayName: String? { hxPresented(name) }
}

/// `parameters[]` of `McpToolResponse`; all three columns are non-null Kotlin `String`s with `""` defaults.
public struct McpToolParameter: Decodable, Equatable, Sendable {
    public let name: String
    public let type: String
    public let description: String

    public init(name: String = "", type: String = "string", description: String = "") {
        self.name = name
        self.type = type
        self.description = description
    }

    /// The declared type, which is what `list_tools` kept of the schema besides the name
    /// (`McpServerController.kt:157-168`) and what the console shows as its own cyan tag
    /// (`harnax-webui/src/pages/mcp/detail.tsx:176-189`).
    ///
    /// `nil` means the column arrived blank, and nothing invents a type for it: the controller's own
    /// `"string"` default is applied upstream, so a blank here is a payload that did not go through that
    /// flattening, and calling it `string` would be a claim nobody made.
    public var declaredType: String? { hxPresented(type) }

    /// What the parameter says about itself, or `nil` when it says nothing. The screen then shows the
    /// catalog's own 「暂无描述」 — the console's fallback (`harnax-webui/src/pages/mcp/detail.tsx:192`) — so
    /// the absence is a stated fact rather than a blank line.
    public var documentation: String? { hxPresented(description) }
}

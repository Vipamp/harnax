import Foundation

/// One row of `GET /api/admin/model-providers/page` — the first level of the model screen.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderResponse.kt:13-43`.
/// Where `AgentResponse` declares every field nullable, this DTO gives `id`, `type`, `name`, `status`,
/// `isPublic`, `creator`, `createTime` and `updateTime` non-null defaults, and
/// `default-property-inclusion: non_null`
/// (`harnax-admin/src/main/resources/application.yml:25`) keeps those keys on the wire. Only
/// `description`, `apiKey` and `baseUrl` may go missing, so only those three are optional here.
///
/// `id` is stored under its own name and handed to `Identifiable` as an optional: the row is always
/// addressable, while `PagedState.removeRow(id:)` keys on `Int64?` like every other list element.
public struct ModelProviderSummary: Decodable, Identifiable, Equatable, Hashable, Sendable {
    public let providerID: Int64
    /// The technical type (`dashscope` / `openai` / `ollama`), which the console locks once created.
    public let type: String
    public let name: String
    public let description: String?
    /// Never the stored secret: the response DTO runs every key through `maskApiKey`
    /// (`ModelProviderResponse.kt:64-68`), so this is `sk-****abcd` — or `****` for a key of six
    /// characters or fewer.
    public let apiKeyMasked: String?
    public let baseUrl: String?
    public let status: Int
    public let isPublic: Int
    public let creator: String
    public let createTime: String
    public let updateTime: String

    enum CodingKeys: String, CodingKey {
        case providerID = "id"
        case type, name, description
        case apiKeyMasked = "apiKey"
        case baseUrl, status, isPublic, creator, createTime, updateTime
    }

    public var id: Int64? { providerID }

    /// A row with no status column reads as enabled, the way every other admin list treats `status ?? 1`.
    public var isEnabled: Bool { status != 0 }
    public var isShared: Bool { isPublic == 1 }
    public var title: String? { hxPresented(name) }
    public var technicalType: String? { hxPresented(type) }

    /// `maskApiKey` returns a null or empty key untouched, so an absent key and a blank one both mean
    /// "this provider carries no credential" — which is why the edit form may prefill nothing.
    public var hasCredential: Bool { hxPresented(apiKeyMasked) != nil }
    public var maskedCredential: String? { hxPresented(apiKeyMasked) }
}

/// `GET /api/admin/model-providers/{id}/stats` — the three counts the card shows.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelStatsInfo.kt:10-16`; all
/// three carry a non-null `0` default, so the keys always arrive.
///
/// The read is owner-only: `ModelProviderServiceImpl.kt:152-161` resolves the id through `ownedProvider`,
/// so another tenant's public provider answers with an error rather than zeroes. The card has to say
/// "counts unavailable" instead of showing the same zeroes a genuinely empty provider shows.
public struct ModelProviderStats: Decodable, Equatable, Sendable {
    public let totalModels: Int
    public let enabledModels: Int
    public let disabledModels: Int

    /// Spelled out rather than left to the synthesised memberwise init, which is internal: the counts are
    /// what a card shows, so a stand-in catalog outside this module has to be able to answer them.
    public init(totalModels: Int = 0, enabledModels: Int = 0, disabledModels: Int = 0) {
        self.totalModels = totalModels
        self.enabledModels = enabledModels
        self.disabledModels = disabledModels
    }
}

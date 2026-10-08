import Foundation

/// One row of `GET /api/admin/models/page` — the second level of the model screen.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelResponse.kt:11-53` declares
/// every field `var x: T? = null`, so each key may arrive as `null` or go missing entirely under
/// `default-property-inclusion: non_null`. Optionality is therefore mirrored field by field, and a row
/// with no id still renders.
///
/// `providerName` is not part of `fromEntity`: the service fills it in per row after the DTO is built
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ModelServiceImpl.kt:223-233`), so
/// a model whose provider row has gone answers without that key.
public struct ModelSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    /// The technical name sent to the provider (`gpt-4`, `qwen-max`).
    public let modelName: String?
    public let providerId: Int64?
    public let providerName: String?
    public let description: String?
    public let modelType: String?
    /// Server-computed from the five capability columns (`ModelResponse.kt:82-89`); iOS reads the columns
    /// themselves, which is the same rule and survives a page that omits the computed key.
    public let tags: [String]?
    public let supportInternet: Int?
    public let supportReasoning: Int?
    public let thinkingMode: Int?
    public let supportTool: Int?
    public let supportMcp: Int?
    public let supportVision: Int?
    /// Token budget of one call, and the denominator of the occupancy readout. The key is absent whenever the
    /// row was left to the runtime's inference (`ModelServiceImpl.kt:132` assigns a create's null as-is), so a
    /// missing window reads as no answer rather than as 0.
    public let contextWindow: Int?
    /// CNY per million tokens (`ModelCreateRequest.kt:56-58` only floors it at 0).
    public let price: Double?
    public let status: Int?
    public let isPublic: Int?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?

    public var isEnabled: Bool { status != 0 }
    public var isShared: Bool { isPublic == 1 }
    public var title: String? { hxPresented(name) }
    public var technicalName: String? { hxPresented(modelName) }
    public var providerTitle: String? { hxPresented(providerName) }

    public var supportsInternet: Bool { supportInternet.hxFlag }
    public var supportsReasoning: Bool { supportReasoning.hxFlag }
    public var supportsTool: Bool { supportTool.hxFlag }
    public var supportsMcp: Bool { supportMcp.hxFlag }
    public var supportsVision: Bool { supportVision.hxFlag }

    /// The three-value reading of `thinkingMode`, including the compatibility derivation the web form
    /// applies to rows written before the column existed.
    public var thinking: ThinkingMode {
        ThinkingMode.resolve(stored: thinkingMode, supportReasoning: supportReasoning)
    }

    /// The capability tags in the order the backend appends them
    /// (`ModelResponse.kt:82-89`), so a row and the filter chips cannot list the same set differently.
    public var capabilities: [ModelCapability] {
        ModelCapability.allCases.filter { $0.isSet(on: self) }
    }
}

/// The five capability tags of a model row. These are wire values — the same strings the page endpoint
/// accepts in `tags` (`ModelController.kt:39-42`) and the same ones the provider stores in its own tag
/// column — never copy.
///
/// `reasoning` has no switch of its own: the form drives it from `thinkingMode` on both sides
/// (`ModelServiceImpl.kt:160-171`).
public enum ModelCapability: String, CaseIterable, Identifiable, Sendable {
    case internet
    case reasoning
    case tool
    case mcp
    case vision

    public var id: String { rawValue }

    public func isSet(on model: ModelSummary) -> Bool {
        switch self {
        case .internet: model.supportsInternet
        case .reasoning: model.supportsReasoning
        case .tool: model.supportsTool
        case .mcp: model.supportsMcp
        case .vision: model.supportsVision
        }
    }
}

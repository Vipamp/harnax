import Foundation
import SwiftUI
import HarnaxCore
import HarnaxKit

/// The model screen's naming and formatting rules, resolved to the words of the current language.
///
/// A presenter rather than view code so the three rules that are easy to get wrong can be unit tested:
/// a row that names no creator falls back to its own technical name, a price of `0` is still a price, and
/// an unknown provider type renders as itself instead of being renamed to a type the picker knows.
enum ModelProviderPresenter {
    /// The card's headline. A provider whose `name` came back blank still needs a headline
    /// (`hxPresented` treats null, empty and whitespace as the same answer).
    static func title(for provider: ModelProviderSummary) -> String {
        provider.title ?? hx("model.provider.unnamed")
    }

    /// The technical type under its own label, so the row is identifiable even when two providers share a
    /// display name.
    static func byline(for provider: ModelProviderSummary) -> String {
        RowMeta.byline(creator: provider.creator, createTime: provider.createTime)
    }

    static func typeLabel(for provider: ModelProviderSummary) -> String {
        guard let known = ModelProviderType.resolve(provider.type) else {
            return provider.technicalType ?? hx("model.provider.type.unknown")
        }
        return hx(known.titleKey)
    }

    static func icon(for type: String?) -> String {
        switch ModelProviderType.resolve(type) {
        case .dashscope: "cloud"
        case .openai: "hexagon"
        case .ollama: "desktopcomputer"
        case nil: "sparkles"
        }
    }

    static func tone(for type: String?) -> PaletteSlot {
        switch ModelProviderType.resolve(type) {
        case .dashscope: .brand
        case .openai: .teal
        case .ollama: .purple
        case nil: .indigo
        }
    }
}

extension ModelProviderType {
    /// Copy for the three types the console lists. The keys are `model.*`, so a new type needs a key in
    /// both catalogues before it can join this list.
    var titleKey: String {
        switch self {
        case .dashscope: return "model.type.dashscope"
        case .openai: return "model.type.openai"
        case .ollama: return "model.type.ollama"
        }
    }
}

/// One model row, already resolved to text.
enum ModelPresenter {
    static func title(for model: ModelSummary) -> String {
        model.title ?? model.technicalName ?? hx("model.unnamed")
    }

    /// The technical name is the one string an operator searches for in a provider's model list, so it is
    /// shown next to the display name whenever the two differ
    /// (`harnax-webui/src/pages/model/components/ModelListTable.tsx:178-255`).
    static func technicalName(for model: ModelSummary) -> String? {
        guard let technical = model.technicalName else { return nil }
        return technical == title(for: model) ? nil : technical
    }

    static func byline(for model: ModelSummary) -> String {
        RowMeta.byline(creator: model.creator, createTime: model.createTime)
    }

    /// Thinking mode leads the chip row: `2` is the one state the console colours as a warning, because a
    /// forced-thinking model changes what a session can be asked to do
    /// (`harnax-webui/src/pages/model/components/ModelListTable.tsx:137-176`).
    static func chips(for model: ModelSummary) -> [ModelChip] {
        var chips: [ModelChip] = []
        if model.thinking == .required {
            chips.append(ModelChip(text: hx("model.thinking.required"), tone: .danger))
        }
        chips.append(contentsOf: model.capabilities.map { ModelChip(text: hx($0.titleKey), tone: nil) })
        if let price = priceText(model.price) {
            chips.append(ModelChip(text: hx("model.chip.price", price), tone: .brand))
        }
        return chips
    }

    static func thinkingLabel(_ mode: ThinkingMode) -> String {
        hx(mode.titleKey)
    }

    /// CNY per million tokens with the trailing zeros cut: `0.0000` on the wire reads as `0`, and a
    /// four-decimal price keeps its four
    /// (`harnax-webui/src/pages/model/components/ModelForm.tsx:178-184` steps by 0.0001).
    static func priceText(_ price: Double?) -> String? {
        guard let price else { return nil }
        var text = String(format: "%.4f", price)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return text
    }
}

/// One resolved chip on a model row.
struct ModelChip: Equatable {
    let text: String
    let tone: PaletteSlot?
}

extension ModelCapability {
    var titleKey: String {
        switch self {
        case .internet: return "model.capability.internet"
        case .reasoning: return "model.capability.reasoning"
        case .tool: return "model.capability.tool"
        case .mcp: return "model.capability.mcp"
        case .vision: return "model.capability.vision"
        }
    }
}

extension ThinkingMode {
    var titleKey: String {
        switch self {
        case .off: return "model.thinking.off"
        case .optional: return "model.thinking.optional"
        case .required: return "model.thinking.required"
        }
    }
}

extension ModelType {
    var titleKey: String {
        switch self {
        case .chat: return "model.typeKind.chat"
        case .embedding: return "model.typeKind.embedding"
        }
    }
}

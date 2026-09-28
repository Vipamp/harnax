import Foundation
import HarnaxKit

/// The five domains that live under the 上下文 tab. Order and titles follow the mockup's D1 segment row.
public enum ContextDomain: Int, CaseIterable, Identifiable, Sendable {
    case model
    case tool
    case mcp
    case skill
    case cli

    public var id: Int { rawValue }

    public var titleKey: String {
        switch self {
        case .model: return "context.domain.model"
        case .tool: return "context.domain.tool"
        case .mcp: return "context.domain.mcp"
        case .skill: return "context.domain.skill"
        case .cli: return "context.domain.cli"
        }
    }

    /// Icon names avoid the copy namespaces on purpose: a dotted literal that starts with one of them
    /// reads as a localisation key to the gate, and `model` is a namespace here.
    public var systemImage: String {
        switch self {
        case .model: return "sparkles"
        case .tool: return "wrench.and.screwdriver"
        case .mcp: return "network"
        case .skill: return "books.vertical"
        case .cli: return "terminal"
        }
    }

    public static var segments: [HXSegmentOption] {
        allCases.map { HXSegmentOption(id: $0.rawValue, $0.titleKey) }
    }
}

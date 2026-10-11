import Foundation
import HarnaxCore

/// The three read-only monitors that share the 「集群监控」 block on 「我的」, as one navigation value so the
/// group can be a `ForEach` and its last row knows it is last.
///
/// The console keeps these as a first-level 「集群监控」 group with no access rule on any of the three
/// (`harnax-webui/config/routes.ts:160-181`): a token bill, a skill-usage table and a call-metric table say
/// nothing the operator can write, so nothing here is gated. Administration is the different kind of thing —
/// variables, keys and channels are records somebody creates and deletes — which is why 「我的」 draws two
/// blocks rather than one long list, and why `SystemRoute` no longer carries a monitor at all
/// (`routes.ts:199-204` is the console making the same split).
///
/// A member still sees all three rows: the console gates the API Key route and not the monitor routes
/// (`:205-210` carries `access: 'canAccessUserManagement'`, `:165-179` carry none).
public enum MonitorRoute: Hashable, CaseIterable, Sendable {
    /// Which skills anybody has actually used — `routes.ts:165-169`.
    case skillUsage
    /// What the tools, MCP servers and CLI packages cost when they are called — `routes.ts:170-174`.
    case callMetrics
    /// Token and fee use by model, agent and session — `routes.ts:175-179`.
    case token

    public var titleKey: String {
        switch self {
        case .token: return "monitor.title"
        case .skillUsage: return "skill.usage.title"
        case .callMetrics: return "monitor.calls.title"
        }
    }

    public var subtitleKey: String {
        switch self {
        case .token: return "system.monitor.subtitle"
        case .skillUsage: return "monitor.skillUsage.subtitle"
        case .callMetrics: return "monitor.calls.subtitle"
        }
    }

    /// Icon names avoid the copy namespaces on purpose: a dotted literal that starts with one of them
    /// reads as a localisation key to the gate, and `monitor` is a namespace here.
    public var systemImage: String {
        switch self {
        case .token: return "chart.line.uptrend.xyaxis"
        case .skillUsage: return "books.vertical"
        case .callMetrics: return "wrench.and.screwdriver"
        }
    }
}

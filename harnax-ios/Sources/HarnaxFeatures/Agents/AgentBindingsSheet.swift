import SwiftUI
import HarnaxCore
import HarnaxKit

/// One line of the card's drill-down, already resolved to the words the current language shows.
///
/// The presenter exists so the naming rules — which column wins per language, what a nameless row falls
/// back to, which value an environment row may display — are unit-testable without a view.
enum AgentBindingsPresenter {
    /// Dimensions in the order the wizard lists them; a dimension the agent has none of is dropped rather
    /// than shown as an empty section.
    static func sections(for agent: AgentSummary, chinese: Bool) -> [HXBindingSection] {
        var sections: [HXBindingSection] = []
        if let tools = agent.toolList, !tools.isEmpty {
            sections.append(HXBindingSection(
                titleKey: "agent.section.tools",
                rows: tools.map { tool in
                    HXBindingRow(
                        title: tool.name(chinese: chinese) ?? fallbackName(tool.toolId),
                        subtitle: tool.description,
                        badges: badges(confirm: tool.requiresConfirmation, envCount: tool.envCount)
                    )
                }
            ))
        }
        if let servers = agent.mcpList, !servers.isEmpty {
            sections.append(HXBindingSection(
                titleKey: "agent.section.mcp",
                rows: servers.map { server in
                    HXBindingRow(
                        title: server.name ?? unnamed(),
                        subtitle: server.description,
                        badges: badges(confirm: false, envCount: server.envCount)
                    )
                }
            ))
        }
        if let skills = agent.skillList, !skills.isEmpty {
            sections.append(HXBindingSection(
                titleKey: "agent.section.skills",
                rows: skills.map { skill in
                    HXBindingRow(
                        title: skill.name ?? unnamed(),
                        subtitle: skill.description,
                        badges: skill.repository.map { [$0] } ?? []
                    )
                }
            ))
        }
        if let packages = agent.cliList, !packages.isEmpty {
            sections.append(HXBindingSection(
                titleKey: "agent.section.cli",
                rows: packages.map { cli in
                    var marks: [String] = []
                    if let version = cli.packageVersion { marks.append(version) }
                    if cli.envCount > 0 { marks.append(hx("env.count", cli.envCount)) }
                    return HXBindingRow(
                        title: cli.name ?? unnamed(),
                        subtitle: cli.description ?? (cli.skillNames.isEmpty ? nil : cli.skillNames.joined(separator: " · ")),
                        badges: marks
                    )
                }
            ))
        }
        if let sessions = agent.sessionList, !sessions.isEmpty {
            sections.append(HXBindingSection(
                titleKey: "agent.section.sessions",
                rows: sessions.map { session in
                    HXBindingRow(
                        title: session.name
                            ?? session.id.map { hx("agent.binding.sessionFallback", Int($0)) }
                            ?? hxPresented(session.sessionId)
                            ?? unnamed(),
                        subtitle: hxPresented(session.sessionDescription),
                        badges: []
                    )
                }
            ))
        }
        return sections
    }

    /// The console falls back to `Tool #<id>` when all three name columns are blank
    /// (`harnax-webui/src/pages/agent/index.tsx:141`), and to a placeholder when even the id is missing.
    private static func fallbackName(_ id: Int64?) -> String {
        guard let id else { return unnamed() }
        return hx("agent.binding.toolFallback", Int(id))
    }

    private static func unnamed() -> String { hx("agent.binding.unnamed") }

    /// A mark only appears when the binding carries that attribute — a count of zero would be a second way
    /// of saying "none" that the row already says.
    private static func badges(confirm: Bool, envCount: Int) -> [String] {
        var marks: [String] = []
        if confirm { marks.append(hx("agent.binding.confirm")) }
        if envCount > 0 { marks.append(hx("env.count", envCount)) }
        return marks
    }
}

/// C1 drill-down — the four binding dimensions and the sessions, all of which the list row already carries.
public struct AgentBindingsSheet: View {
    private let agent: AgentSummary
    /// Held so switching language inside the app re-resolves the tool name column, which is picked per row.
    @ObservedObject private var catalog = HarnaxCatalog.shared

    public init(agent: AgentSummary) {
        self.agent = agent
    }

    public var body: some View {
        HXBindingSheet(title: hxPresented(agent.name) ?? "") {
            HXBindingListView(sections: sections)
        }
    }

    private var sections: [HXBindingSection] {
        AgentBindingsPresenter.sections(for: agent, chinese: catalog.language.prefersChinese)
    }
}

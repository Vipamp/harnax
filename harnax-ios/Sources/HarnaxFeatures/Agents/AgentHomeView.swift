import SwiftUI
import HarnaxCore
import HarnaxKit

/// C1/C2 — the 智能体 tab is a three-way switch between agents, teams and scheduled tasks, the shape the
/// mockup's `.seg` row draws.
///
/// The scheduled column is the task domain (M4) and shows the placeholder until it lands; the tab bar keeps
/// its three segments so the navigation shape does not move between milestones.
public struct AgentHomeView: View {
    public enum Section: Int, CaseIterable, Identifiable, Sendable {
        case agents
        case teams
        case tasks

        public var id: Int { rawValue }

        public var titleKey: String {
            switch self {
            case .agents: return "agent.segment.agents"
            case .teams: return "agent.segment.teams"
            case .tasks: return "agent.segment.tasks"
            }
        }
    }

    @State private var section: Section = .agents
    private let agents: any AgentCataloging
    private let teams: any TeamCataloging
    private let refresher: any SessionRefreshing
    private let account: AccountSnapshot?

    public init(
        agents: any AgentCataloging,
        teams: any TeamCataloging,
        sessionRefresher: any SessionRefreshing,
        account: AccountSnapshot?
    ) {
        self.agents = agents
        self.teams = teams
        self.refresher = sessionRefresher
        self.account = account
    }

    public var body: some View {
        VStack(spacing: 0) {
            HXSegmented(
                Section.allCases.map { HXSegmentOption(id: $0.rawValue, $0.titleKey) },
                selection: Binding(get: { section.rawValue }, set: { section = Section(rawValue: $0) ?? .agents })
            )
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 4)

            // Each column keeps its own view state, so switching away and back does not lose a search term.
            Group {
                switch section {
                case .agents:
                    AgentListView(agents: agents, sessionRefresher: refresher, account: account)
                case .teams:
                    TeamListView(teams: teams, sessionRefresher: refresher, account: account)
                case .tasks:
                    SoonView(titleKey: Section.tasks.titleKey)
                }
            }
        }
    }
}

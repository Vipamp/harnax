import SwiftUI
import HarnaxCore
import HarnaxKit

/// C1/C2 — the 智能体 tab is a three-way switch between agents, teams and scheduled tasks, the shape the
/// mockup's `.seg` row draws.
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
    private let tasks: any AgentTaskCataloging
    private let refresher: any SessionRefreshing
    private let account: AccountSnapshot?

    public init(
        agents: any AgentCataloging,
        teams: any TeamCataloging,
        tasks: any AgentTaskCataloging,
        sessionRefresher: any SessionRefreshing,
        account: AccountSnapshot?
    ) {
        self.agents = agents
        self.teams = teams
        self.tasks = tasks
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

            // One column on screen at a time: leaving a segment discards its view model, so a search term
            // does not survive the round trip. The console keeps these three as separate routes, which
            // lose the same state on a menu click, so this is the behaviour rather than a difference.
            Group {
                switch section {
                case .agents:
                    AgentListView(agents: agents, sessionRefresher: refresher, account: account)
                case .teams:
                    TeamListView(teams: teams, sessionRefresher: refresher, account: account)
                case .tasks:
                    TaskListView(catalog: tasks, account: account)
                }
            }
        }
    }
}

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
    private let dependencies: HarnaxDependencies
    private let account: AccountSnapshot?

    public init(dependencies: HarnaxDependencies, account: AccountSnapshot?) {
        self.dependencies = dependencies
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
                    AgentListView(dependencies: dependencies, account: account)
                case .teams:
                    TeamListView(dependencies: dependencies, account: account)
                case .tasks:
                    TaskListView(catalog: dependencies.tasks, account: account)
                }
            }
        }
    }
}

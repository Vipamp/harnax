import SwiftUI
import HarnaxCore
import HarnaxKit

/// The fourth segment of the 智能体 tab: the three queues an owner owes the agents' own improvement loop,
/// listed in one column.
///
/// This is a hub rather than a list of its own, and it is a column rather than a page because the app has no
/// first-level menu to add — the tab bar is fixed at four (`Root/HarnaxRootView.swift:52`, one stack per tab).
/// The console's 「优化治理」 group is therefore carried on the tab whose columns are already one row per agent,
/// and the fourth segment costs no width: `HXSegmented` divides the row by option
/// (`HarnaxKit/Components/HXSegmented.swift:42`) and the 上下文 tab already shows five.
///
/// The skill queue keeps its second entry in the conversation list (`Chat/ChatTab.swift:47-51`) on purpose:
/// that row is where a proposal is most likely to be noticed, since it is the conversation that produced it.
/// Two ways into one queue is the console's own shape too — the menu row and the in-page link to
/// `/optimization/skill-drafts` both exist.
public struct GovernanceHomeView: View {
    private let dependencies: HarnaxDependencies

    public init(dependencies: HarnaxDependencies) {
        self.dependencies = dependencies
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("governance.title")))
            .navigationDestination(for: GovernanceRoute.self) { route in
                switch route {
                case .memoryDrafts:
                    if let drafts = dependencies.memoryDrafts {
                        MemoryDraftListView(catalog: drafts)
                    }
                case .memory:
                    if let memory = dependencies.memory {
                        MemoryListView(catalog: memory)
                    }
                case .skillDrafts:
                    if let drafts = dependencies.drafts {
                        SkillDraftListView(drafts: drafts, skills: dependencies.skills)
                    }
                }
            }
    }

    /// One scroll for the whole column: the host's switch row first, then the three entries.
    ///
    /// The bar rides inside rather than above because pinned outside it held still while the rows and the
    /// navigation bar moved (`SegmentBarGateTests` keeps that report shut).
    private var content: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                HXSegmentBarRow()
                HXGroupCard {
                    ForEach(Array(entries.enumerated()), id: \.element) { offset, route in
                        NavigationLink(value: route) {
                            HXRow(
                                route.titleKey,
                                subtitle: hx(route.subtitleKey),
                                systemImage: route.systemImage,
                                divider: offset < entries.count - 1,
                                trailing: { HXChevron() }
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    /// A queue this host has no client for is a row that can only fail, so it is not offered — the same rule
    /// the conversation list's draft entry follows (`ChatTab.swift:48`). On the assembled app all three are
    /// wired, since one `AdminClient` answers for every `/api/admin/**` facade
    /// (`Support/HarnaxDependencies.swift`).
    private var entries: [GovernanceRoute] {
        GovernanceRoute.allCases.filter { route in
            switch route {
            case .memoryDrafts: dependencies.memoryDrafts != nil
            case .memory: dependencies.memory != nil
            case .skillDrafts: dependencies.drafts != nil
            }
        }
    }
}

import SwiftUI
import HarnaxCore
import HarnaxKit

/// C1 — the record cards. One page at a time, and every field the card shows comes from the list row,
/// because the backend already fills the four binding counts there.
public struct AgentListView: View {
    @StateObject private var vm: AgentListViewModel

    public init(agents: any AgentCataloging) {
        _vm = StateObject(wrappedValue: AgentListViewModel(agents: agents))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(.empty, retry: { Task { await vm.refresh() } })
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.refresh() } })
        case .content:
            list
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                if let inline = vm.inlineError {
                    HXBanner("state.error.title", message: inline, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                // Row identity is the array index: a page may carry rows whose `id` the backend left null,
                // and two nil ids under one `ForEach` identity would drop a card.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, agent in
                    AgentRecordCard(agent: agent)
                        .onAppear {
                            if index == vm.items.count - 1 { Task { await vm.loadMore() } }
                        }
                }
                if vm.isAppending {
                    HXStateView(.loading)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }
}

struct AgentRecordCard: View {
    let agent: AgentSummary

    var body: some View {
        HXCard {
            HStack(alignment: .top, spacing: 12) {
                HXAvatar(name: agent.name ?? "")
                VStack(alignment: .leading, spacing: 7) {
                    titleRow
                    if let description = agent.description, !description.isEmpty {
                        Text(verbatim: description)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if hasChips {
                        HXFlow(spacing: 6) { chips }
                            .padding(.top, 2)
                    }
                    let byline = AgentRowMeta.byline(for: agent)
                    if !byline.isEmpty {
                        Text(verbatim: byline)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                }
            }
        }
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            Text(verbatim: agent.name ?? "")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(
                agent.isEnabled ? "agent.badge.enabled" : "agent.badge.disabled",
                tone: agent.isEnabled ? .success : .textTertiary
            )
            if agent.isShared {
                HXChip(hx("agent.badge.public"), tone: .indigo)
            }
            Spacer(minLength: 0)
        }
    }

    private var hasChips: Bool {
        agent.modelName?.isEmpty == false
            || agent.toolCount > 0 || agent.skillCount > 0 || agent.mcpCount > 0 || agent.cliCount > 0
    }

    @ViewBuilder
    private var chips: some View {
        if let model = agent.modelName, !model.isEmpty {
            HXChip(hx("agent.chip.model", model), tone: .brand)
        }
        if agent.toolCount > 0 {
            HXChip(hx("agent.chip.tools", agent.toolCount))
        }
        if agent.skillCount > 0 {
            HXChip(hx("agent.chip.skills", agent.skillCount), tone: .purple)
        }
        if agent.mcpCount > 0 {
            HXChip(hx("agent.chip.mcp", agent.mcpCount))
        }
        if agent.cliCount > 0 {
            HXChip(hx("agent.chip.cli", agent.cliCount), tone: .warning)
        }
    }
}

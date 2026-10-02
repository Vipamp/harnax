import SwiftUI
import HarnaxCore
import HarnaxKit

/// C1 — the record cards, the console's two list filters, and the three writes a card can do.
///
/// Every field the card and its drill-down show comes off the list row, because
/// `GET /api/admin/agents/page` already fills the four binding lists and the session list per row; there
/// is no detail endpoint in this screen's path.
public struct AgentListView: View {
    @StateObject private var vm: AgentListViewModel
    private let dependencies: HarnaxDependencies
    private let agents: any AgentCataloging
    private let refresher: any SessionRefreshing
    private let account: AccountSnapshot?

    /// Which card the drill-down is open for. Held as a row rather than a flag because the row is what the
    /// sheet reads.
    @State private var drillDown: AgentSummary?
    @State private var refreshTarget: SessionRefreshTarget?
    @State private var pendingDelete: AgentSummary?
    /// The wizard, in whichever mode opened it. A struct rather than the mode itself because `.edit` carries
    /// no identity a `@State` flag could key on.
    @State private var editor: Editor?

    public init(dependencies: HarnaxDependencies, account: AccountSnapshot?) {
        _vm = StateObject(wrappedValue: AgentListViewModel(agents: dependencies.agents))
        self.dependencies = dependencies
        self.agents = dependencies.agents
        self.refresher = dependencies.sessionRefresher
        self.account = account
    }

    struct Editor: Identifiable {
        let id = UUID()
        let mode: AgentFormViewModel.Mode
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("agent.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { createButton }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $drillDown) { AgentBindingsSheet(agent: $0) }
            .sheet(item: $editor) { editor in
                AgentFormView(dependencies: dependencies, mode: editor.mode, account: account) {
                    Task { await vm.refresh() }
                }
            }
            .sheet(item: $refreshTarget) { target in
                HXSessionRefreshSheet(target: target, refresher: refresher)
            }
            .confirmationDialog(
                Text(verbatim: hx("agent.delete.title")),
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { if !$0 { pendingDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { agent in
                Button(role: .destructive) {
                    Task { await vm.delete(agent) }
                } label: {
                    HXText("state.delete.confirm", hxPresented(agent.name) ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                Text(verbatim: hx("agent.delete.note"))
            }
    }

    private var createButton: some View {
        HXPlusButton(titleKey: "agent.create") { editor = Editor(mode: .create) }
    }

    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: vm.filter != .all,
            accessibilityLabel: hx("state.filter.status"),
            choices: statusFilterChoices(vm.filter) { vm.filter = $0 }
        )
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            // A filter that matches nothing is not the same sentence as an account with no agents.
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("agent.empty.filtered") : hx("agent.empty"),
                retry: { Task { await vm.refresh() } }
            )
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
                    AgentRecordCard(
                        agent: agent,
                        enabled: vm.status(of: agent),
                        isPending: agent.id.flatMap(vm.pendingIDs.contains) ?? false,
                        canManage: (account?.canManage(creator: agent.creator)) ?? false,
                        onToggle: { value in Task { await vm.setStatus(value, for: agent) } },
                        onEdit: { editor = Editor(mode: .edit(agent)) },
                        onDrillDown: { drillDown = agent },
                        onRefresh: { refreshTarget = refreshTarget(for: agent) },
                        onDelete: { pendingDelete = agent }
                    )
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

    /// The panel reads its own list, so the card only names the subject.
    private func refreshTarget(for agent: AgentSummary) -> SessionRefreshTarget? {
        guard let id = agent.id else { return nil }
        let catalog = agents
        return SessionRefreshTarget(
            id: id,
            name: hxPresented(agent.name) ?? "",
            source: .agent
        ) {
            await catalog.relatedSessions(id: id)
        }
    }
}

struct AgentRecordCard: View {
    let agent: AgentSummary
    let enabled: Bool
    let isPending: Bool
    let canManage: Bool
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
    let onDrillDown: () -> Void
    let onRefresh: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HXCard {
            HStack(alignment: .top, spacing: 12) {
                HXAvatar(name: agent.name ?? "")
                VStack(alignment: .leading, spacing: 7) {
                    titleRow
                    if let description = hxPresented(agent.description) {
                        Text(verbatim: description)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if hasChips {
                        drillButton
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
        .overlay(alignment: .topTrailing) { menu.padding(10) }
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            Text(verbatim: hxPresented(agent.name) ?? "")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            if agent.isShared {
                HXChip(hx("state.badge.shared"), tone: .indigo)
            }
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    private var hasChips: Bool {
        agent.modelName?.isEmpty == false
            || agent.toolCount > 0 || agent.skillCount > 0 || agent.mcpCount > 0 || agent.cliCount > 0
    }

    /// The counts are the entry point to the detail the web console shows in a hover popover.
    private var drillButton: some View {
        Button(action: onDrillDown) {
            HXFlow(spacing: 6) { chips }
                .padding(.top, 2)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var chips: some View {
        if let model = hxPresented(agent.modelName), !model.isEmpty {
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

    private var menu: some View {
        Menu {
            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            // The console's card switch carries no permission check, so neither does this one.
            .disabled(isPending)

            if canManage {
                Button(action: onEdit) { HXText("state.action.edit") }
            }

            Button(action: onRefresh) { HXText("state.action.refresh") }

            if canManage {
                Button(role: .destructive, action: onDelete) { HXText("state.action.delete") }
            }
        } label: {
            Image(systemName: isPending ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
        .accessibilityLabel(hx("state.action.more"))
    }
}

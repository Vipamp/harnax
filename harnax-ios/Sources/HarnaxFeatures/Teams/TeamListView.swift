import SwiftUI
import HarnaxCore
import HarnaxKit

/// C2 — the team list. Same card shape as an agent, same three writes, plus the pre-flight that names how
/// many sessions would block a delete.
///
/// `GET /api/admin/teams/page` fills the lead's skills and the members per row, so the drill-down costs
/// nothing extra.
public struct TeamListView: View {
    @StateObject private var vm: TeamListViewModel
    private let refresher: any SessionRefreshing
    private let account: AccountSnapshot?

    @State private var drillDown: TeamSummary?
    @State private var refreshTarget: SessionRefreshTarget?

    public init(teams: any TeamCataloging, sessionRefresher: any SessionRefreshing, account: AccountSnapshot?) {
        _vm = StateObject(wrappedValue: TeamListViewModel(teams: teams))
        self.refresher = sessionRefresher
        self.account = account
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("team.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $drillDown) { TeamBindingsSheet(team: $0) }
            .sheet(item: $refreshTarget) { target in
                HXSessionRefreshSheet(target: target, refresher: refresher)
            }
            .confirmationDialog(
                Text(verbatim: hx("team.delete.title")),
                isPresented: Binding(
                    get: { vm.deleteTarget != nil },
                    set: { if !$0 { vm.cancelDelete() } }
                ),
                titleVisibility: .visible,
                presenting: vm.deleteTarget
            ) { target in
                Button(role: .destructive) {
                    Task { await vm.confirmDelete() }
                } label: {
                    HXText("state.delete.confirm", target.team.title ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { target in
                // The count is the whole point of asking first: a blocked delete needs a different sentence
                // from a free one.
                Text(verbatim: target.boundSessions > 0
                    ? hx("team.delete.blocked", target.boundSessions)
                    : hx("team.delete.clear"))
            }
    }

    private var filterMenu: some View {
        Menu {
            ForEach(StatusFilter.allCases) { option in
                Button {
                    vm.filter = option
                } label: {
                    HStack {
                        HXText(option.titleKey)
                        if vm.filter == option { Image(systemName: "checkmark") }
                    }
                }
            }
        } label: {
            Image(systemName: vm.filter == .all ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            // A filter that matches nothing is not the same sentence as an account with no teams.
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("team.empty.filtered") : hx("team.empty"),
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
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, team in
                    TeamRecordCard(
                        team: team,
                        enabled: vm.status(of: team),
                        isPending: team.id.flatMap(vm.pendingIDs.contains) ?? false,
                        isChecking: team.id == vm.deleteTarget?.team.id && vm.isCheckingDelete,
                        canManage: (account?.canManage(creator: team.creator)) ?? false,
                        onToggle: { value in Task { await vm.setStatus(value, for: team) } },
                        onDrillDown: { drillDown = team },
                        onRefresh: { refreshTarget = vm.refreshTarget(for: team) },
                        onDelete: { Task { await vm.requestDelete(team) } }
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
}

struct TeamRecordCard: View {
    let team: TeamSummary
    let enabled: Bool
    let isPending: Bool
    /// The pre-flight read is on the wire, so the row shows progress instead of looking inert.
    let isChecking: Bool
    let canManage: Bool
    let onToggle: (Bool) -> Void
    let onDrillDown: () -> Void
    let onRefresh: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HXCard {
            HStack(alignment: .top, spacing: 12) {
                HXAvatar(name: team.title ?? "")
                VStack(alignment: .leading, spacing: 7) {
                    titleRow
                    if let description = hxPresented(team.description) {
                        Text(verbatim: description)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if hasChips {
                        drillButton
                    }
                    let byline = RowMeta.byline(creator: team.creator, createTime: team.createTime)
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
            Text(verbatim: team.title ?? "")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            if team.isShared {
                HXChip(hx("state.badge.shared"), tone: .indigo)
            }
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    private var unavailableCount: Int {
        team.memberList.filter(\.isUnavailable).count + team.skillList.filter(\.isUnavailable).count
    }

    private var hasChips: Bool {
        team.leadModelName != nil || !team.memberList.isEmpty || !team.skillList.isEmpty
    }

    /// The counts are the entry point to the members and lead skills the console shows in its expanded row.
    private var drillButton: some View {
        Button(action: onDrillDown) {
            HXFlow(spacing: 6) { chips }
                .padding(.top, 2)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var chips: some View {
        if let model = team.leadModelName {
            HXChip(hx("team.chip.leadModel", model), tone: .brand)
        }
        if !team.memberList.isEmpty {
            HXChip(hx("team.chip.members", team.memberList.count), tone: .purple)
        }
        if !team.skillList.isEmpty {
            HXChip(hx("team.chip.skills", team.skillList.count))
        }
        if unavailableCount > 0 {
            HXChip(hx("team.stale.count", unavailableCount), tone: .danger)
        }
    }

    private var menu: some View {
        Menu {
            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            .disabled(!canManage || isPending)

            Button(action: onRefresh) { HXText("state.action.refresh") }

            if canManage {
                Button(role: .destructive, action: onDelete) {
                    HXText(isChecking ? "state.delete.checking" : "state.action.delete")
                }
                .disabled(isChecking)
            }
        } label: {
            Image(systemName: isPending || isChecking ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
    }
}

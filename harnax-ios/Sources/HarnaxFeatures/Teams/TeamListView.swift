import SwiftUI
import HarnaxCore
import HarnaxKit

/// C2 — the team list. Same card shape as an agent, same three writes, plus the pre-flight that names how
/// many sessions would block a delete.
///
/// `GET /api/admin/teams/page` fills the lead's skills and the members per row, so the drill-down costs
/// nothing extra.
///
/// The menu's Edit row and the toolbar's create button both open `TeamFormView`, in `.edit(row)` and `.create`.
/// No row action here asks the account's permission first, because the console's team row asks none
/// (`harnax-webui/src/pages/team/index.tsx:250`-`:295`).
public struct TeamListView: View {
    @StateObject private var vm: TeamListViewModel
    /// The wizard reads the model page, the skill repositories and the agent pool as well as teams, so the
    /// screen takes the whole bag rather than the team slice.
    private let dependencies: HarnaxDependencies
    private let refresher: any SessionRefreshing
    private let account: AccountSnapshot?

    @State private var drillDown: TeamSummary?
    @State private var refreshTarget: SessionRefreshTarget?
    /// The wizard, in whichever mode opened it. A struct rather than the mode itself because `.edit` carries
    /// no identity a `@State` flag could key on.
    @State private var editor: Editor?

    public init(dependencies: HarnaxDependencies, account: AccountSnapshot?) {
        _vm = StateObject(wrappedValue: TeamListViewModel(teams: dependencies.teams))
        self.dependencies = dependencies
        self.refresher = dependencies.sessionRefresher
        self.account = account
    }

    struct Editor: Identifiable {
        let id = UUID()
        let mode: TeamFormViewModel.Mode
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("team.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { createButton }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $drillDown) { TeamBindingsSheet(team: $0) }
            .sheet(item: $editor) { editor in
                TeamFormView(dependencies: dependencies, mode: editor.mode, account: account) {
                    Task { await vm.refresh() }
                }
            }
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

    /// The console's team page keeps a create button beside the search box
    /// (`harnax-webui/src/pages/team/index.tsx:318`-`:320`); this app puts it in the toolbar as a `+`, because
    /// every entity list here opens the same way and a text button would be the one place that does not.
    private var createButton: some View {
        HXPlusButton(titleKey: "team.create") { editor = Editor(mode: .create) }
    }

    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: vm.filter != .all,
            accessibilityLabel: hx("state.filter.status"),
            choices: statusFilterChoices(vm.filter) { vm.filter = $0 }
        )
    }

    /// One scroll for the whole column: the host's switch row first, then whichever phase this screen is in.
    ///
    /// The bar rides inside rather than above because pinned outside it held still while the rows and the
    /// navigation bar moved. A state card is only a shorter column, so it scrolls here too — the bar stays
    /// reachable and pulling it is still this list's refresh.
    private var content: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                HXSegmentBarRow()
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
                    rows
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    @ViewBuilder
    private var rows: some View {
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
                onToggle: { value in Task { await vm.setStatus(value, for: team) } },
                onEdit: { editor = Editor(mode: .edit(team)) },
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
}

struct TeamRecordCard: View {
    let team: TeamSummary
    let enabled: Bool
    let isPending: Bool
    /// The pre-flight read is on the wire, so the row shows progress instead of looking inert.
    let isChecking: Bool
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
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
            // The console's row carries no permission check on any of these three (`harnax-webui/src/pages/
            // team/index.tsx:250`-`:295` renders a bare `StatusSwitch` and always both Edit and Delete), so the
            // only thing that may hold a row inert here is the row's own write being on the wire.
            .disabled(isPending)

            Button(action: onEdit) { HXText("state.action.edit") }

            Button(action: onRefresh) { HXText("state.action.refresh") }

            Button(role: .destructive, action: onDelete) {
                HXText(isChecking ? "state.delete.checking" : "state.action.delete")
            }
            .disabled(isChecking)
        } label: {
            Image(systemName: isPending || isChecking ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
        .accessibilityLabel(hx("state.action.more"))
    }
}

import SwiftUI
import HarnaxCore
import HarnaxKit

/// D4 — the CLI page. Eight columns of the console table become one card per package: name and switch
/// state up top, then the four values an operator actually compares (version, shipped skill, parameter
/// count, package digest), then the health check and the registration date.
///
/// The page is read-only apart from the kill switch: a package is registered by dropping its archive into
/// admin's package directory, so there is no create or edit entry here to draw
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:18-25`). Every
/// signed-in account reaches both the reads and the switch — the DTO carries no creator to gate on.
public struct CliListView: View {
    @StateObject private var vm: CliListViewModel
    private let clis: any CliCataloging
    private let refresher: any SessionRefreshing

    @State private var detailTarget: CliSummary?
    @State private var refreshTarget: SessionRefreshTarget?

    public init(clis: any CliCataloging, sessionRefresher: any SessionRefreshing) {
        _vm = StateObject(wrappedValue: CliListViewModel(clis: clis))
        self.clis = clis
        self.refresher = sessionRefresher
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("cli.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $detailTarget) { cli in
                CliDetailSheet(clis: clis, cli: cli)
            }
            .sheet(item: $refreshTarget) { target in
                HXSessionRefreshSheet(target: target, refresher: refresher)
            }
            .onChange(of: vm.refreshOffer) { cli in
                // The switch just landed and agents still hold the old package, so the panel opens straight
                // away — the same hand-off the console makes with `setRefreshTarget(record)`.
                guard let cli else { return }
                refreshTarget = vm.refreshTarget(for: cli)
                vm.refreshOffer = nil
            }
            .confirmationDialog(
                Text(verbatim: hx("cli.disable.title")),
                isPresented: Binding(
                    get: { vm.disableTarget != nil },
                    set: { if !$0 { vm.cancelDisable() } }
                ),
                titleVisibility: .visible,
                presenting: vm.disableTarget
            ) { target in
                Button(role: .destructive) {
                    Task { await vm.confirmDisable() }
                } label: {
                    HXText("cli.disable.confirm", target.cli.title ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { target in
                // Who loses the command is the whole point of asking first; a bare "are you sure" would not
                // be worth the interruption.
                Text(verbatim: hx("cli.disable.body", target.count, target.names))
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
            // A filter that matches nothing is not the same sentence as a platform with no registered
            // packages.
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("cli.empty.filtered") : hx("cli.empty"),
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
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, cli in
                    CliRecordCard(
                        cli: cli,
                        enabled: vm.status(of: cli),
                        isPending: cli.id.flatMap(vm.pendingIDs.contains) ?? false,
                        isChecking: vm.isCheckingStatus,
                        onToggle: { value in Task { await vm.requestStatus(value, for: cli) } },
                        onDetail: { detailTarget = cli },
                        onRefresh: { refreshTarget = vm.refreshTarget(for: cli) }
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

struct CliRecordCard: View {
    let cli: CliSummary
    let enabled: Bool
    let isPending: Bool
    /// The related-agents read is on the wire, so the row shows progress rather than appearing inert.
    let isChecking: Bool
    let onToggle: (Bool) -> Void
    let onDetail: () -> Void
    let onRefresh: () -> Void

    var body: some View {
        HXCard {
            HStack(alignment: .top, spacing: 12) {
                HXAvatar(name: cli.title ?? "")
                VStack(alignment: .leading, spacing: 7) {
                    titleRow
                    if let description = hxPresented(cli.description) {
                        Text(verbatim: description)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    drillButton
                    if let checkCommand = hxPresented(cli.checkCommand) {
                        Text(verbatim: checkCommand)
                            .font(.caption.monospaced())
                            .foregroundStyle(Color.hx(.textTertiary))
                            .lineLimit(1)
                    }
                    let byline = RowMeta.byline(creator: nil, createTime: cli.createTime)
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
            Text(verbatim: cli.title ?? "")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    /// The chips are the entry point to the drawer that holds the rest of the declaration.
    private var drillButton: some View {
        Button(action: onDetail) {
            HXFlow(spacing: 6) { chips }
                .padding(.top, 2)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var chips: some View {
        if let version = hxPresented(cli.version) {
            HXChip(version, tone: .brand)
        }
        if let skill = cli.shippedSkillName {
            HXChip(hx("cli.chip.skill", skill), tone: .indigo)
        }
        if let digest = cli.packageDigestAbbrev {
            HXChip(hx("cli.chip.digest", digest))
        }
        HXEnvParamsChip(entries: cli.envParamEntries)
    }

    private var menu: some View {
        Menu {
            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            .disabled(isPending || isChecking)

            Button(action: onDetail) { HXText("cli.action.detail") }

            Button(action: onRefresh) { HXText("state.action.refresh") }
        } label: {
            Image(systemName: isPending || isChecking ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
    }
}

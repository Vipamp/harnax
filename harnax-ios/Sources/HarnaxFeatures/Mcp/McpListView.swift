import SwiftUI
import HarnaxCore
import HarnaxKit

/// M1 — the MCP segment of the context tab: one card per server, with the transport, the endpoint the
/// runtime will actually use, and the four writes this domain has.
///
/// The card is the whole read path: `GET /api/admin/mcp/page` answers the config entries already masked, so
/// nothing here needs a second call to describe a row (`McpServerResponse.kt:14-44`).
public struct McpListView: View {
    @StateObject private var vm: McpListViewModel
    private let mcp: any McpCataloging
    private let authorizer: any McpAuthorizing
    private let account: AccountSnapshot?

    @State private var editor: McpFormTarget?
    @State private var openDetail: Int64?

    public init(
        mcp: any McpCataloging,
        authorizer: any McpAuthorizing = SystemBrowserAuthorizer(),
        account: AccountSnapshot? = nil
    ) {
        _vm = StateObject(wrappedValue: McpListViewModel(mcp: mcp))
        self.mcp = mcp
        self.authorizer = authorizer
        self.account = account
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("mcp.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { createButton }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .navigationDestination(item: $openDetail) { id in
                McpDetailView(mcp: mcp, authorizer: authorizer, id: id)
            }
            .sheet(item: $editor) { target in
                McpFormView(mcp: mcp, mode: target.mode, account: account) {
                    await vm.refresh()
                }
            }
            .deleteConfirmation(vm: vm)
    }

    private var createButton: some View {
        HXPlusButton(titleKey: "mcp.create") { editor = McpFormTarget(mode: .create) }
    }

    /// Status and transport are two menus in one because they narrow different columns, and a single menu
    /// of six entries would not show which of the two is set.
    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: isFiltering,
            accessibilityLabel: hx("state.filter.status") + ", " + hx("mcp.type.filter")
        ) {
            Picker(selection: $vm.filter) {
                ForEach(StatusFilter.allCases) { option in
                    HXText(option.titleKey).tag(option)
                }
            } label: {
                HXText("state.filter.status")
            }
            Picker(selection: $vm.typeFilter) {
                ForEach(McpTypeFilter.allCases) { option in
                    HXText(option.titleKey).tag(option)
                }
            } label: {
                HXText("mcp.type.filter")
            }
        }
    }

    private var isFiltering: Bool { vm.filter != .all || vm.typeFilter != .all }

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
                    // A filter that matches nothing is not the same sentence as an account with no servers.
                    HXStateView(
                        .empty,
                        message: vm.isFiltered ? hx("mcp.empty.filtered") : hx("mcp.empty"),
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
        ForEach(Array(vm.items.enumerated()), id: \.offset) { index, server in
            McpRecordCard(
                server: server,
                enabled: vm.status(of: server),
                canManage: account?.canManage(creator: server.creator) ?? false,
                isPending: server.id.flatMap(vm.pendingIDs.contains) ?? false,
                isTesting: server.id.flatMap(vm.testingIDs.contains) ?? false,
                isCheckingDelete: server.id == vm.deleteTarget?.server.id && vm.isCheckingDelete,
                outcome: server.id.flatMap { vm.testOutcomes[$0] },
                onOpen: { openDetail = server.id },
                onToggle: { value in Task { await vm.setStatus(value, for: server) } },
                onTest: { Task { await vm.runTest(for: server) } },
                onDismissTest: { vm.dismissTest(for: server) },
                onEdit: { editor = McpFormTarget(mode: .edit(server)) },
                onDelete: { Task { await vm.requestDelete(server) } }
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

/// The sheet's target: a wrapper around the form mode, because `sheet(item:)` needs `Identifiable` and
/// "create" has no row to identify by.
struct McpFormTarget: Identifiable {
    let mode: McpFormViewModel.Mode
    var id: String {
        switch mode {
        case .create: "create"
        case let .edit(row): "edit-\(row.id ?? -1)"
        }
    }
}

/// One server: what it is called, how it is reached, and whether it needs this user's own authorization.
///
/// The endpoint line follows the console's rule — a stdio row shows its command, anything else its URL
/// (`harnax-webui/src/pages/mcp/index.tsx:44`). An OAUTH2 row says so on the card because the server it
/// points at cannot be reached by anyone until they have authorized
/// (`index.tsx:99-102`).
struct McpRecordCard: View {
    let server: McpServerRow
    let enabled: Bool
    let canManage: Bool
    let isPending: Bool
    let isTesting: Bool
    let isCheckingDelete: Bool
    let outcome: McpTestOutcome?
    let onOpen: () -> Void
    let onToggle: (Bool) -> Void
    let onTest: () -> Void
    let onDismissTest: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                titleRow
                if let description = hxPresented(server.description) {
                    Text(verbatim: description)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let endpoint = server.endpoint {
                    HXValueText(endpoint)
                }
                if !chips.isEmpty {
                    HXFlow(spacing: 6) { chipsContent }
                }
                let byline = RowMeta.byline(creator: server.creator, createTime: server.createTime)
                if !byline.isEmpty {
                    Text(verbatim: byline)
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                if let outcome {
                    outcomeBanner(outcome)
                }
            }
        }
        .hxCardTap(onOpen)
        .overlay(alignment: .topTrailing) { menu.padding(10) }
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            Text(verbatim: server.title ?? hx("mcp.unnamed"))
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    private var chips: [AnyView] {
        var rows: [AnyView] = [AnyView(HXChip(McpLabels.transport(server.transport), tone: .brand))]
        if server.isShared {
            rows.append(AnyView(HXChip(hx("state.badge.shared"), tone: .indigo)))
        }
        // The auth chip is the card's only hint that this server is not usable yet: the OAuth block that
        // can say more lives on the detail screen, behind the per-user status read.
        if server.requiresPerUserOAuth {
            rows.append(AnyView(HXChip(McpLabels.auth(server.auth), tone: .warning)))
        }
        if !server.headerEntries.isEmpty {
            rows.append(AnyView(HXChip(hx("mcp.chip.headers", server.headerEntries.count))))
        }
        if !server.envEntries.isEmpty {
            rows.append(AnyView(HXEnvParamsChip(entries: server.envEntries)))
        }
        return rows
    }

    private var chipsContent: some View {
        ForEach(Array(chips.enumerated()), id: \.offset) { _, chip in
            chip
        }
    }

    private func outcomeBanner(_ outcome: McpTestOutcome) -> some View {
        HXBanner(
            "mcp.test.title",
            message: outcome.copy,
            systemImage: outcome.isPassed ? "checkmark.circle" : "exclamationmark.triangle",
            tone: outcome.tone
        )
        .overlay(alignment: .topTrailing) {
            Button(action: onDismissTest) {
                Image(systemName: "xmark")
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .padding(6)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: hx("common.close")))
        }
    }

    /// The console gates this menu's edit and delete on the row's creator (`pages/mcp/index.tsx:110-111`) and
    /// leaves the card's status switch open to anyone (`EntityCard/index.tsx:294-296`), so the probe and the
    /// switch stay here for every row while the two writes appear only on a row this account owns.
    private var menu: some View {
        Menu {
            Button(action: onTest) {
                HXText(isTesting ? "mcp.testing" : "mcp.action.test")
            }
            .disabled(isTesting)

            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            .disabled(isPending)

            if canManage {
                Button(action: onEdit) { HXText("mcp.edit") }

                Button(role: .destructive, action: onDelete) {
                    HXText(isCheckingDelete ? "state.delete.checking" : "state.action.delete")
                }
                .disabled(isCheckingDelete)
            }
        } label: {
            Image(systemName: isPending || isTesting || isCheckingDelete ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
        .accessibilityLabel(hx("state.action.more"))
    }
}

/// The delete dialog, opened only after the related-agent read answered.
///
/// The count changes the sentence and not the outcome: the backend cascades the bindings away
/// (`McpServerServiceImpl.kt:320-337`), so the words here name what is about to be unbound rather than
/// promising a refusal — and they name it, in the sense of listing the dependents by name, which is what
/// `McpDeleteCopy` is for.
extension View {
    fileprivate func deleteConfirmation(vm: McpListViewModel) -> some View {
        confirmationDialog(
            Text(verbatim: hx("mcp.delete.title")),
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
                HXText("mcp.delete.confirm", hxPresented(target.server.name) ?? "")
            }
            Button(role: .cancel) {} label: { HXText("common.cancel") }
        } message: { target in
            Text(verbatim: McpDeleteCopy.message(for: target.boundAgents))
        }
    }
}

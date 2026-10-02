import SwiftUI
import HarnaxCore
import HarnaxKit

/// S4 — the API Key list.
///
/// Same shape as the variable list, with the two things this domain adds: the status segment, which really
/// does drive a server column (`ApiKeyController.kt:39` takes `enabled`), and the one-time key sheet that
/// create and regenerate hand back.
public struct ApiKeyListView: View {
    private enum Target: Identifiable, Equatable {
        case create
        case edit(ApiKeySummary)

        var id: String {
            switch self {
            case .create: return "create"
            case let .edit(row): return "edit-\(row.id ?? 0)"
            }
        }

        var row: ApiKeySummary? {
            switch self {
            case .create: return nil
            case let .edit(row): return row
            }
        }
    }

    @StateObject private var vm: ApiKeyListViewModel
    private let catalog: any ApiKeyCataloging
    private let account: AccountSnapshot?

    @State private var target: Target?
    /// Which row the rotate dialog is open on. The list model deliberately holds no such flag: rotating
    /// publishes a key, so the pending intent belongs to the screen that shows it.
    @State private var rotateTarget: ApiKeySummary?

    public init(catalog: any ApiKeyCataloging, account: AccountSnapshot?) {
        _vm = StateObject(wrappedValue: ApiKeyListViewModel(catalog: catalog))
        self.catalog = catalog
        self.account = account
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("apikey.title")))
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("apikey.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { addButton }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $target) { target in
                ApiKeyFormView(row: target.row, account: account, catalog: catalog) {
                    self.target = nil
                    Task { await vm.refresh() }
                }
            }
            // The rotate path. A create shows its key inside its own sheet, so this is the only route here
            // and `publishedKey` is the only value the raw secret ever takes on this screen.
            .sheet(isPresented: Binding(
                get: { vm.publishedKey != nil },
                set: { if !$0 { vm.dismissPublishedKey() } }
            )) {
                if let created = vm.publishedKey {
                    ApiKeyRawKeySheet(created: created) { vm.dismissPublishedKey() }
                }
            }
            .confirmationDialog(
                Text(verbatim: hx("apikey.delete.title")),
                isPresented: Binding(
                    get: { vm.deleteTarget != nil },
                    set: { if !$0 { vm.cancelDelete() } }
                ),
                titleVisibility: .visible,
                presenting: vm.deleteTarget
            ) { row in
                Button(role: .destructive) {
                    Task { await vm.confirmDelete() }
                } label: {
                    HXText("state.delete.confirm", row.title ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                Text(verbatim: hx("apikey.delete.note"))
            }
            .confirmationDialog(
                Text(verbatim: hx("apikey.regenerate.title")),
                isPresented: Binding(
                    get: { rotateTarget != nil },
                    set: { if !$0 { rotateTarget = nil } }
                ),
                titleVisibility: .visible,
                presenting: rotateTarget
            ) { row in
                Button(role: .destructive) {
                    rotateTarget = nil
                    Task { await vm.regenerate(row) }
                } label: {
                    HXText("apikey.regenerate.confirm", row.title ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                Text(verbatim: hx("apikey.regenerate.note"))
            }
    }

    private var addButton: some View {
        HXPlusButton(titleKey: "apikey.create") { target = .create }
    }

    /// The status filter used to be a segment row at the head of the list, which only exists while the list has
    /// rows: filter down to nothing and the one control that could undo it was gone with them.
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
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("apikey.empty.filtered") : hx("apikey.empty"),
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
                // Row identity is the array index: a page may carry rows whose `id` the backend left null.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, row in
                    ApiKeyRecordCard(
                        row: row,
                        enabled: vm.status(of: row),
                        isPending: row.id.flatMap(vm.pendingIDs.contains) ?? false,
                        canManage: row.manageable(by: account),
                        onToggle: { value in Task { await vm.setStatus(value, for: row) } },
                        onEdit: { target = .edit(row) },
                        onRegenerate: { rotateTarget = row },
                        onDelete: { vm.beginDelete(row) }
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

/// The row: the name, the prefix that is all that survives of the secret, the scope marks, and the expiry.
public struct ApiKeyRecordCard: View {
    private let row: ApiKeySummary
    private let enabled: Bool
    private let isPending: Bool
    private let canManage: Bool
    private let onToggle: (Bool) -> Void
    private let onEdit: () -> Void
    private let onRegenerate: () -> Void
    private let onDelete: () -> Void

    init(
        row: ApiKeySummary,
        enabled: Bool,
        isPending: Bool,
        canManage: Bool,
        onToggle: @escaping (Bool) -> Void,
        onEdit: @escaping () -> Void,
        onRegenerate: @escaping () -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.row = row
        self.enabled = enabled
        self.isPending = isPending
        self.canManage = canManage
        self.onToggle = onToggle
        self.onEdit = onEdit
        self.onRegenerate = onRegenerate
        self.onDelete = onDelete
    }

    public var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                titleRow
                keyRow
                if !row.scopeTokens.isEmpty {
                    HXFlow(spacing: 6) {
                        ForEach(Array(row.scopeTokens.enumerated()), id: \.offset) { _, token in
                            HXChip(ApiKeyScopeLabel.label(for: token))
                        }
                    }
                }
                let byline = RowMeta.byline(creator: row.creator, createTime: row.createTime)
                if !byline.isEmpty {
                    Text(verbatim: byline)
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
            }
        }
        .overlay(alignment: .topTrailing) { menu.padding(10) }
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            Text(verbatim: row.title ?? "")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            if row.isExpired() {
                HXBadge("apikey.badge.expired", tone: .danger)
            } else if let day = hxMonthDay(row.expiresAt) {
                HXChip(hx("apikey.chip.expires", day), tone: .warning)
            }
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    /// The stored prefix — `hnx_sk_live_ab...9f3c` — which is the only part of the secret the backend keeps
    /// in readable form (`ApiKeyServiceImpl.kt:82`).
    private var keyRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "key")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
            if let key = row.displayKey {
                HXValueText(key, lines: 1)
            } else {
                HXText("apikey.noKey")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
            Spacer(minLength: 0)
        }
    }

    private var menu: some View {
        Menu {
            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            .disabled(!canManage || isPending)

            if canManage {
                Button(action: onRegenerate) { HXText("apikey.action.regenerate") }
                    .disabled(isPending)
                Button(action: onEdit) { HXText("apikey.action.edit") }
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

/// One scope token as this screen words it.
///
/// The two labels the console offers get their catalogue entries; anything else is printed as it arrived,
/// because the server checks no value set (`ApiKeyCreateRequest.kt:14-16`) and a token iOS has never seen is
/// still a fact about the row.
enum ApiKeyScopeLabel {
    static func label(for token: String) -> String {
        guard let scope = ApiKeyScope(rawValue: token) else { return token }
        return hx(scope.titleKey)
    }
}

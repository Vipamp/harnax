import SwiftUI
import HarnaxCore
import HarnaxKit

/// S3 — the environment-variable list.
///
/// A row is denser than an agent card and simpler than it: a key, the value column the server chose to
/// mask for us, and the two flags. Copying is on the row because the console makes the key column copyable
/// (`harnax-webui/src/pages/env-variable/index.tsx:140-248`).
public struct EnvVarListView: View {
    /// Which form the sheet shows. A rename is an edit of the same row, so the two cases differ only by
    /// whether there is a row to seed the fields with.
    private enum Target: Identifiable, Equatable {
        case create
        case edit(EnvVarSummary)

        var id: String {
            switch self {
            case .create: return "create"
            case let .edit(row): return "edit-\(row.id ?? 0)"
            }
        }

        var row: EnvVarSummary? {
            switch self {
            case .create: return nil
            case let .edit(row): return row
            }
        }
    }

    @StateObject private var vm: EnvVarListViewModel
    private let catalog: any EnvVarCataloging
    private let account: AccountSnapshot?

    @State private var target: Target?

    public init(catalog: any EnvVarCataloging, account: AccountSnapshot?) {
        _vm = StateObject(wrappedValue: EnvVarListViewModel(catalog: catalog))
        self.catalog = catalog
        self.account = account
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("env.title")))
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("env.var.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { addButton }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $target) { target in
                EnvVarFormView(row: target.row, catalog: catalog) {
                    self.target = nil
                    Task { await vm.refresh() }
                }
            }
            .confirmationDialog(
                Text(verbatim: hx("env.var.delete.title")),
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
                    HXText("state.delete.confirm", row.key ?? "")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                // There is no pre-flight to run here, so the dialog can only promise what the server
                // decides: a variable an agent still binds to is refused, by name
                // (`EnvVariableServiceImpl.kt:199-208`).
                Text(verbatim: hx("env.var.delete.note"))
            }
    }

    private var addButton: some View {
        Button {
            target = .create
        } label: {
            Image(systemName: "plus")
        }
        .accessibilityLabel(hx("env.var.create"))
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("env.var.empty.filtered") : hx("env.var.empty"),
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
                HXBanner("env.var.effect", systemImage: "clock", tone: .warning)
                // The count is the server's total, not the length of the array: the page below it may hold
                // twenty of a hundred rows.
                Text(verbatim: hxCount("env.count", vm.total))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Row identity is the array index: a page may carry rows whose `id` the backend left null,
                // and two nil ids under one `ForEach` identity would drop a card.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, row in
                    EnvVarRecordCard(
                        row: row,
                        enabled: vm.status(of: row),
                        isPending: row.id.flatMap(vm.pendingIDs.contains) ?? false,
                        canManage: row.manageable(by: account),
                        onToggle: { value in Task { await vm.setStatus(value, for: row) } },
                        onEdit: { target = .edit(row) },
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

/// The row itself. Public because the system tab shell mounts it directly, and because a card this
/// specific is easier to read in the preview harness than an inline `some View` chain.
public struct EnvVarRecordCard: View {
    private let row: EnvVarSummary
    private let enabled: Bool
    private let isPending: Bool
    private let canManage: Bool
    private let onToggle: (Bool) -> Void
    private let onEdit: () -> Void
    private let onDelete: () -> Void

    init(
        row: EnvVarSummary,
        enabled: Bool,
        isPending: Bool,
        canManage: Bool,
        onToggle: @escaping (Bool) -> Void,
        onEdit: @escaping () -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.row = row
        self.enabled = enabled
        self.isPending = isPending
        self.canManage = canManage
        self.onToggle = onToggle
        self.onEdit = onEdit
        self.onDelete = onDelete
    }

    public var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                titleRow
                valueRow
                if let note = row.note {
                    Text(verbatim: note)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
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
            Text(verbatim: row.key ?? "")
                .font(.headline.monospaced())
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            if row.isSensitive {
                HXBadge("env.sensitive", tone: .warning)
            }
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    /// The value column, exactly as the stack sent it — a mask whenever the row is sensitive
    /// (`EnvVariableServiceImpl.kt:221-243`). Long-press copies what is shown, which is all this device
    /// ever has.
    private var valueRow: some View {
        HStack(spacing: 8) {
            Image(systemName: row.isSensitive ? "eye.slash" : "textformat")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
            if let value = row.displayValue {
                HXValueText(value, lines: 2)
            } else {
                HXText("env.var.noValue")
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
                Button(action: onEdit) { HXText("env.var.edit") }
                Button(role: .destructive, action: onDelete) { HXText("state.action.delete") }
            }
        } label: {
            Image(systemName: isPending ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
    }
}

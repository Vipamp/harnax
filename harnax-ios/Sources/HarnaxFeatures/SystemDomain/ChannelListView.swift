import SwiftUI
import HarnaxCore
import HarnaxKit

/// E1 — the channel list.
///
/// Three filters rather than two: the page really does take `type` alongside `keyword` and `status`
/// (`ChannelController.kt:34-46`), and the type menu is the one that cannot be a segment — five types plus
/// "all" is six options, so status and type share the toolbar menu the way the MCP list does it.
public struct ChannelListView: View {
    private enum Target: Identifiable, Equatable {
        case create
        case edit(ChannelSummary)

        var id: String {
            switch self {
            case .create: return "create"
            case let .edit(row): return "edit-\(row.id ?? 0)"
            }
        }

        var row: ChannelSummary? {
            switch self {
            case .create: return nil
            case let .edit(row): return row
            }
        }
    }

    @StateObject private var vm: ChannelListViewModel
    private let catalog: any ChannelCataloging
    private let agents: any AgentCataloging
    private let account: AccountSnapshot?

    @State private var target: Target?

    public init(catalog: any ChannelCataloging, agents: any AgentCataloging, account: AccountSnapshot?) {
        _vm = StateObject(wrappedValue: ChannelListViewModel(catalog: catalog))
        self.catalog = catalog
        self.agents = agents
        self.account = account
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("channel.title")))
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("channel.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    HXPlusButton(titleKey: "channel.create") { target = .create }
                }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(item: $target) { target in
                ChannelFormView(row: target.row, catalog: catalog, agents: agents) {
                    self.target = nil
                    Task { await vm.refresh() }
                }
            }
            .sheet(item: $vm.scanTarget) { row in
                if let id = row.id {
                    WechatLoginSheet(catalog: catalog, channelID: id) {
                        vm.scanTarget = nil
                        Task { await vm.refresh() }
                    }
                }
            }
            .confirmationDialog(
                Text(verbatim: hx("channel.delete.title")),
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
                // The console says the same thing (`harnax-webui/src/pages/channel/index.tsx:197-219`): the
                // runtime is released as part of the delete, and a refusal there is what keeps the row.
                Text(verbatim: hx("channel.delete.note"))
            }
    }

    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: isFiltering,
            accessibilityLabel: hx("state.filter.status") + ", " + hx("channel.type.filter")
        ) {
            Picker(selection: $vm.filter) {
                ForEach(StatusFilter.allCases) { option in
                    HXText(option.titleKey).tag(option)
                }
            } label: {
                HXText("state.filter.status")
            }
            Picker(selection: $vm.typeFilter) {
                ForEach(ChannelTypeFilter.allCases) { option in
                    HXText(option.titleKey).tag(option)
                }
            } label: {
                HXText("channel.type.filter")
            }
        }
    }

    private var isFiltering: Bool { vm.filter != .all || vm.typeFilter != .all }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("channel.empty.filtered") : hx("channel.empty"),
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
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, row in
                    ChannelRecordCard(
                        row: row,
                        running: vm.status(of: row),
                        sandbox: vm.sandboxLight(of: row),
                        isPending: row.id.flatMap(vm.pendingIDs.contains) ?? false,
                        canManage: row.manageable(by: account),
                        onToggle: { value in Task { await vm.setStatus(value, for: row) } },
                        onScan: { vm.scanTarget = row },
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

/// The row: name, the two codes that say what this channel actually is, the agent it answers to, and the
/// sandbox light that came from the runtime rather than from this API.
public struct ChannelRecordCard: View {
    private let row: ChannelSummary
    private let running: Bool
    private let sandbox: ChannelSandboxLight
    private let isPending: Bool
    private let canManage: Bool
    private let onToggle: (Bool) -> Void
    private let onScan: () -> Void
    private let onEdit: () -> Void
    private let onDelete: () -> Void

    public init(
        row: ChannelSummary,
        running: Bool,
        sandbox: ChannelSandboxLight,
        isPending: Bool,
        canManage: Bool,
        onToggle: @escaping (Bool) -> Void,
        onScan: @escaping () -> Void,
        onEdit: @escaping () -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.row = row
        self.running = running
        self.sandbox = sandbox
        self.isPending = isPending
        self.canManage = canManage
        self.onToggle = onToggle
        self.onScan = onScan
        self.onEdit = onEdit
        self.onDelete = onDelete
    }

    public var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                titleRow
                chips
                if let agent = hxPresented(row.agentName) {
                    Text(verbatim: hx("channel.row.agent", agent))
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
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
            HXBadge(running ? "state.badge.enabled" : "state.badge.disabled", tone: running ? .success : .textTertiary)
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    private var chips: some View {
        HXFlow(spacing: 6) {
            // A row with neither a known type nor a code to fall back to gets no pill: an empty capsule
            // reads as a rendering bug rather than as "this column is blank". The sandbox column obeys the
            // same rule for the same reason — a channel with no session has no status to wear.
            if let typeLabel { HXChip(typeLabel) }
            if let mode = row.mode { HXChip(hx(mode.titleKey)) }
            if let key = sandbox.labelKey { HXChip(hx(key), tone: sandbox.tone) }
            // Only a WeChat row can be unbound, and the difference is worth a word: the channel exists but
            // has no credentials until somebody scans it (`WechatLoginService.kt:162-186`).
            if row.channelType == .wechat {
                HXChip(
                    hx(row.isWechatBound ? "channel.wechat.bound" : "channel.wechat.unbound"),
                    tone: row.isWechatBound ? .success : .warning
                )
            }
        }
    }

    /// A type this side does not know still shows its code, because dropping it would hide a fact about the
    /// row the server just sent. No code at all is the one case with nothing to say.
    private var typeLabel: String? {
        if let type = row.channelType { return hx(type.titleKey) }
        return row.rawTypeCode
    }

    private var menu: some View {
        Menu {
            Button { onToggle(!running) } label: {
                HXText(running ? "state.action.disable" : "state.action.enable")
            }
            .disabled(!canManage || isPending)

            if canManage {
                // The scan entry only means something on a personal-WeChat row — no other type has a
                // credential this flow writes (`WechatLoginService.kt:162-186`).
                if row.channelType == .wechat {
                    Button(action: onScan) { HXText("channel.wechat.scan") }
                }
                Button(action: onEdit) { HXText("channel.action.edit") }
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

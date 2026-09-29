import SwiftUI
import HarnaxCore
import HarnaxKit

/// D1, first level — the provider cards.
///
/// The screen is a list of providers rather than the console's card/table switch
/// (`harnax-webui/src/pages/model/index.tsx:31`): one column of cards is what fits a phone, and the table
/// mode is the second level here.
///
/// Nothing about a provider is editable from this file's own state: a save answers with no id, so the sheet
/// hands back "it worked" and the list refetches.
public struct ModelProviderListView: View {
    @StateObject private var vm: ModelProviderListViewModel
    private let catalog: any ModelCataloging
    private let account: AccountSnapshot?

    @State private var pendingDelete: ModelProviderSummary?
    @State private var editing: ModelProviderSummary?
    @State private var isCreating = false

    public init(catalog: any ModelCataloging, account: AccountSnapshot?) {
        self.catalog = catalog
        self.account = account
        _vm = StateObject(wrappedValue: ModelProviderListViewModel(catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("model.provider.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { createButton }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .navigationDestination(for: ModelProviderSummary.self) { provider in
                ModelListView(provider: provider, catalog: catalog, account: account)
            }
            .sheet(isPresented: $isCreating) {
                ModelProviderFormSheet(catalog: catalog, editing: nil, account: account) {
                    Task { await vm.saved() }
                }
            }
            .sheet(item: $editing) { target in
                ModelProviderFormSheet(catalog: catalog, editing: target, account: account) {
                    Task { await vm.saved() }
                }
            }
            .confirmationDialog(
                Text(verbatim: hx("model.provider.delete.title")),
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { if !$0 { pendingDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { provider in
                Button(role: .destructive) {
                    Task { await vm.delete(provider) }
                } label: {
                    HXText("state.delete.confirm", ModelProviderPresenter.title(for: provider))
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                Text(verbatim: hx("model.provider.delete.note"))
            }
    }

    private var createButton: some View {
        Button { isCreating = true } label: {
            Image(systemName: "plus")
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
            HXStateView(
                .empty,
                message: vm.isFiltered ? hx("model.provider.empty.filtered") : hx("model.provider.empty"),
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
                    HXBanner(
                        "state.error.title",
                        message: inline,
                        systemImage: "exclamationmark.triangle",
                        tone: .danger
                    )
                }
                // The provider's own identity: the counts a card shows are keyed the same way, so a row that
                // moved must not keep the read of whoever held that slot before. This DTO declares `id`
                // non-null (`ModelProviderResponse.kt:13-43`), so no row is unkeyable.
                ForEach(vm.items, id: \.providerID) { provider in
                    ModelProviderCard(
                        provider: provider,
                        enabled: vm.status(of: provider),
                        isPending: vm.pendingIDs.contains(provider.providerID),
                        stats: vm.statsState(for: provider),
                        test: vm.testOutcome(for: provider),
                        canManage: account?.canManage(creator: provider.creator) ?? false,
                        onToggle: { value in Task { await vm.setStatus(value, for: provider) } },
                        onAppear: { Task { await vm.loadStats(for: provider) } },
                        onTest: { Task { await vm.test(provider) } },
                        onEdit: { editing = provider },
                        onDelete: { pendingDelete = provider }
                    )
                    .onAppear {
                        if provider.providerID == vm.items.last?.providerID {
                            Task { await vm.loadMore() }
                        }
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

/// One provider card: the counts the card asks for itself, the three writes, and the connectivity answer.
struct ModelProviderCard: View {
    let provider: ModelProviderSummary
    let enabled: Bool
    let isPending: Bool
    let stats: ModelProviderListViewModel.StatsState
    let test: ModelProviderListViewModel.TestOutcome?
    let canManage: Bool
    let onToggle: (Bool) -> Void
    let onAppear: () -> Void
    let onTest: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        NavigationLink(value: provider) {
            HXCard {
                HStack(alignment: .top, spacing: 12) {
                    HXAvatar(name: provider.name, slot: ModelProviderPresenter.tone(for: provider.type))
                    VStack(alignment: .leading, spacing: 7) {
                        header
                        detailText
                        chips
                        statsNote
                        if let test { testLine(test) }
                        byline
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .overlay(alignment: .topTrailing) { menu.padding(10) }
        .onAppear(perform: onAppear)
    }

    private var header: some View {
        HStack(spacing: 6) {
            Text(verbatim: ModelProviderPresenter.title(for: provider))
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    @ViewBuilder
    private var detailText: some View {
        if let text = hxPresented(provider.description) {
            Text(verbatim: text)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
        } else {
            HXText("model.provider.noDescription")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }

    /// One wrapping row for everything that qualifies the provider: what it is, whether it is borrowed,
    /// and how many models it carries. The type and visibility chips sat on the title line until the
    /// English screenshots showed what that costs — three wide pills leave the name one glyph.
    private var chips: some View {
        HXFlow(spacing: 6) {
            let typeLabel = ModelProviderPresenter.typeLabel(for: provider)
            // A single-vendor tenant usually names its provider after the vendor, and then the pill would
            // repeat the headline word for word.
            if typeLabel != ModelProviderPresenter.title(for: provider) {
                HXChip(typeLabel)
            }
            if provider.isShared {
                HXChip(hx("state.badge.shared"), tone: .indigo)
            }
            if case let .loaded(counts) = stats {
                countsChips(counts)
            }
        }
    }

    private func countsChips(_ counts: ModelProviderStats) -> some View {
        Group {
            HXChip(hxCount("model.provider.stats.total", counts.totalModels), tone: .brand)
            HXChip(
                hx("model.provider.stats.enabled", counts.enabledModels),
                tone: counts.enabledModels > 0 ? .success : .textTertiary
            )
            HXChip(hx("model.provider.stats.disabled", counts.disabledModels), tone: .textTertiary)
        }
    }

    /// The two answers that replace the counts. An empty provider and an unreadable one are different
    /// answers, and the second is the common one for a provider borrowed from another tenant.
    @ViewBuilder
    private var statsNote: some View {
        switch stats {
        case .pending:
            HXText("model.provider.stats.loading")
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
        case .loaded:
            EmptyView()
        case .unavailable:
            HXText("model.provider.stats.unavailable")
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }

    private var byline: some View {
        let text = ModelProviderPresenter.byline(for: provider)
        return Group {
            if text.isEmpty {
                EmptyView()
            } else {
                Text(verbatim: text)
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        }
    }

    /// The answer is the server's, and it is labelled as such: `connectivityTest` returns `true` for every
    /// provider without opening a socket today
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ModelProviderServiceImpl.kt:163-169`),
    /// so calling it "连通" would be a claim the stack has not earned.
    @ViewBuilder
    private func testLine(_ outcome: ModelProviderListViewModel.TestOutcome) -> some View {
        switch outcome {
        case .running:
            HXText("model.provider.testing")
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
        case .passed:
            report(tone: .success, key: "model.provider.test.passed")
        case .failed:
            report(tone: .warning, key: "model.provider.test.failed")
        case let .refused(message):
            HXBanner("model.provider.test.title", message: message, systemImage: "exclamationmark.triangle", tone: .danger)
        }
    }

    private func report(tone: PaletteSlot, key: String) -> some View {
        HStack(spacing: 6) {
            HXBadge(key, tone: tone)
            HXText("model.provider.test.note")
                .font(.caption2)
                .foregroundStyle(Color.hx(.textTertiary))
            Spacer(minLength: 0)
        }
    }

    private var menu: some View {
        Menu {
            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            .disabled(isPending)

            Button(action: onTest) { HXText("model.provider.test") }

            if canManage {
                Button(action: onEdit) { HXText("model.provider.edit") }
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

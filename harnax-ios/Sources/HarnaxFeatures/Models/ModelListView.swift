import SwiftUI
import HarnaxCore
import HarnaxKit

/// D1, second level — the models under one provider.
///
/// The console shows these rows in a table with no pager; iOS pages them instead
/// (`harnax-ios/specs/04-context-domains.md:62`), and the capability tags that the table renders as coloured
/// tags become filter chips here because they are the only interesting way to cut a long provider list.
///
/// There is no detail endpoint for a model, so the row is the whole story and the edit sheet refills itself
/// from the row the list already has.
public struct ModelListView: View {
    @StateObject private var vm: ModelListViewModel
    private let provider: ModelProviderSummary
    private let catalog: any ModelCataloging
    private let account: AccountSnapshot?

    @State private var pendingDelete: ModelSummary?
    @State private var editing: ModelSummary?
    @State private var isCreating = false

    public init(provider: ModelProviderSummary, catalog: any ModelCataloging, account: AccountSnapshot?) {
        self.provider = provider
        self.catalog = catalog
        self.account = account
        _vm = StateObject(wrappedValue: ModelListViewModel(providerID: provider.providerID, catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("model.search")))
            .navigationTitle(Text(verbatim: ModelProviderPresenter.title(for: provider)))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { createButton }
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .sheet(isPresented: $isCreating) {
                ModelFormSheet(catalog: catalog, providerID: provider.providerID, editing: nil, account: account) {
                    Task { await vm.saved() }
                }
            }
            .sheet(item: $editing) { model in
                ModelFormSheet(
                    catalog: catalog,
                    providerID: provider.providerID,
                    editing: model,
                    account: account
                ) {
                    Task { await vm.saved() }
                }
            }
            .confirmationDialog(
                Text(verbatim: hx("model.delete.title")),
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { if !$0 { pendingDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { model in
                Button(role: .destructive) {
                    Task { await vm.delete(model) }
                } label: {
                    HXText("state.delete.confirm", ModelPresenter.title(for: model))
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { _ in
                Text(verbatim: hx("model.delete.note"))
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
                message: vm.isFiltered ? hx("model.empty.filtered") : hx("model.empty"),
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
                tagFilter
                // Index identity, as on every other list here: the row's own `id` may be null, and two null
                // ids under one identity would drop a row.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, model in
                    ModelRow(
                        model: model,
                        enabled: vm.status(of: model),
                        isPending: model.id.flatMap(vm.pendingIDs.contains) ?? false,
                        canManage: account?.canManage(creator: model.creator) ?? false,
                        onToggle: { value in Task { await vm.setStatus(value, for: model) } },
                        onEdit: { editing = model },
                        onDelete: { pendingDelete = model }
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

    /// The five capability tags double as the filter, because that is what the page endpoint's `tags`
    /// parameter accepts — one comma-joined string it splits again
    /// (`ModelServiceImpl.kt:47-52`).
    private var tagFilter: some View {
        HXFlow(spacing: 8) {
            ForEach(ModelCapability.allCases) { tag in
                let selected = vm.selectedTags.contains(tag)
                Button {
                    Task { await vm.toggle(tag) }
                } label: {
                    HXChip(hx(tag.titleKey), tone: selected ? .brand : nil)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 3)
                        .background(
                            selected ? Color.hxFill(.brand, alpha: 0.10) : Color.hx(.surface),
                            in: Capsule()
                        )
                        .overlay(
                            Capsule().strokeBorder(
                                selected ? Color.hx(.brand) : Color.hx(.separator),
                                lineWidth: 1
                            )
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// One model row.
///
/// `thinkingMode == 2` is the one chip with a colour of its own: a forced-thinking model changes what a
/// session can be asked to do, and the console marks it the same way
/// (`harnax-webui/src/pages/model/components/ModelListTable.tsx:137-176`).
struct ModelRow: View {
    let model: ModelSummary
    let enabled: Bool
    let isPending: Bool
    let canManage: Bool
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HXCard {
            HStack(alignment: .top, spacing: 12) {
                HXAvatar(name: ModelPresenter.title(for: model), size: .small)
                VStack(alignment: .leading, spacing: 7) {
                    header
                    technicalName
                    detailText
                    chips
                    byline
                }
            }
        }
        .overlay(alignment: .topTrailing) { menu.padding(10) }
    }

    private var header: some View {
        HStack(spacing: 6) {
            Text(verbatim: ModelPresenter.title(for: model))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            HXBadge(enabled ? "state.badge.enabled" : "state.badge.disabled", tone: enabled ? .success : .textTertiary)
            if model.isShared {
                HXChip(hx("state.badge.shared"), tone: .indigo)
            }
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    /// The name actually sent to the provider, whenever it differs from the display name — an operator
    /// picking a model out of a long list reads this one first.
    @ViewBuilder
    private var technicalName: some View {
        if let technical = ModelPresenter.technicalName(for: model) {
            Text(verbatim: technical)
                .font(.caption.monospaced())
                .foregroundStyle(Color.hx(.textSecondary))
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var detailText: some View {
        if let text = hxPresented(model.description) {
            Text(verbatim: text)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
        } else {
            HXText("model.noDescription")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }

    private var chips: some View {
        HXFlow(spacing: 6) {
            if let kind = hxPresented(model.modelType) {
                HXChip(kind, tone: .purple)
            }
            ForEach(ModelPresenter.chips(for: model), id: \.text) { chip in
                HXChip(chip.text, tone: chip.tone)
            }
        }
    }

    private var byline: some View {
        let text = ModelPresenter.byline(for: model)
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

    private var menu: some View {
        Menu {
            Button { onToggle(!enabled) } label: {
                HXText(enabled ? "state.action.disable" : "state.action.enable")
            }
            .disabled(!canManage || isPending)

            if canManage {
                Button(action: onEdit) { HXText("model.edit") }
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

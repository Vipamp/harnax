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
        screen
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

    /// The price boxes sit above the phase switch rather than inside the list: a filter that removed every row
    /// has to leave them on screen, or the very boxes that caused the empty screen would vanish with the rows.
    /// The choices that used to ride along here are in the toolbar menu now, and that one cannot scroll away.
    @ViewBuilder
    private var screen: some View {
        VStack(spacing: 0) {
            if vm.phase == .content || (vm.phase == .empty && vm.isFiltered) {
                priceFilter
            }
            content
        }
    }

    private var createButton: some View {
        HXPlusButton(titleKey: "model.create") { isCreating = true }
    }

    /// The three choice-shaped filters on the page endpoint: status and model type
    /// (`ModelController.kt:37,43-44`) and the capability tags, which the endpoint ANDs into one comma-joined
    /// `tags` parameter (`ModelServiceImpl.kt:47-52`). The price bounds stay below as fields, because a menu can
    /// hold a choice but not a number the user types.
    private var filterMenu: some View {
        HXFilterMenu(
            isFiltering: vm.filter != .all || vm.typeFilter != nil || !vm.selectedTags.isEmpty,
            accessibilityLabel: hx("state.filter")
        ) {
            HXFilterChoices(statusFilterChoices(vm.filter) { vm.filter = $0 })
            Divider()
            HXFilterChoices(typeChoices)
            Divider()
            HXFilterChoices(tagChoices)
        }
    }

    private var typeChoices: [HXFilterChoice] {
        [HXFilterChoice(
            id: "state.filter.all",
            titleKey: "state.filter.all",
            isSelected: vm.typeFilter == nil
        ) { vm.typeFilter = nil }] + ModelType.allCases.map { option in
            HXFilterChoice(
                id: option.titleKey,
                titleKey: option.titleKey,
                isSelected: vm.typeFilter == option
            ) { vm.typeFilter = option }
        }
    }

    /// The one group that stacks: a tick means the tag is in the set, and tapping it again takes it out.
    private var tagChoices: [HXFilterChoice] {
        ModelCapability.allCases.map { tag in
            HXFilterChoice(
                id: tag.titleKey,
                titleKey: tag.titleKey,
                isSelected: vm.selectedTags.contains(tag)
            ) { Task { await vm.toggle(tag) } }
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

    /// The price bounds — the same two optional numbers the console puts after the status in its filter bar
    /// (`harnax-webui/src/pages/model/index.tsx:559-576`). They need no clear button: a box that is empty, or
    /// holds text that is not a number, sends no bound at all, so emptying it lifts the filter. The comparison
    /// itself is server-side and inclusive at both ends (`ModelMapper.xml:150-155`).
    private var priceFilter: some View {
        HStack(spacing: 8) {
            HXField("model.filter.minPrice", text: $vm.minPriceText, systemImage: "yensign", kind: .number)
                .frame(maxWidth: 150)
            Text(verbatim: "–")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
            HXField("model.filter.maxPrice", text: $vm.maxPriceText, systemImage: "yensign", kind: .number)
                .frame(maxWidth: 150)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 2)
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
        .accessibilityLabel(hx("state.action.more"))
    }
}

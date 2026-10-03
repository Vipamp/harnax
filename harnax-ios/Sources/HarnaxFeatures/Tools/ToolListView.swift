import SwiftUI
import HarnaxCore
import HarnaxKit

/// D-tools — the read-only tool table, on the phone.
///
/// The whole screen is a read: no switch, no delete, no create button, because the stack registers tools
/// from annotations in the running code and exposes no write route at all
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:13-15`). The
/// web console reads the rows from the unpaged `/builtin` route and filters its loaded array in memory
/// (`harnax-webui/src/pages/tool/index.tsx:39-68`), and iOS now does the same: one read of that route, then
/// the search box and the status menu narrow what is already held — which is what makes a Chinese display
/// name findable at all. Nothing here paginates, because the answer is a whole table.
public struct ToolListView: View {
    @StateObject private var vm: ToolListViewModel
    private let tools: any ToolCataloging

    /// Which row the drill-down is open for. Held as a row rather than a flag because the sheet reads it.
    @State private var drillDown: ToolSummary?
    @ObservedObject private var catalog = HarnaxCatalog.shared

    public init(tools: any ToolCataloging) {
        _vm = StateObject(wrappedValue: ToolListViewModel(tools: tools))
        self.tools = tools
    }

    public var body: some View {
        content
            .harnaxScreen()
            .searchable(text: $vm.keyword, prompt: Text(verbatim: hx("tool.search")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) { filterMenu }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            // Keyed by row so a second tool gets a fresh sheet state rather than the first one's re-read row.
            .sheet(item: $drillDown) { tool in
                ToolDetailSheet(tools: tools, tool: tool).id(tool.id)
            }
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
                    // A filter that matches nothing is not the same sentence as an installation that registers no
                    // tools.
                    HXStateView(
                        .empty,
                        message: vm.isFiltered ? hx("tool.empty.filtered") : hx("tool.empty"),
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
        Text(verbatim: hx("tool.readonly.note"))
            .font(.caption)
            .foregroundStyle(Color.hx(.textTertiary))
            .frame(maxWidth: .infinity, alignment: .leading)
        // Row identity is the array index: a page may carry rows whose `id` the backend left null,
        // and two nil ids under one `ForEach` identity would drop a card.
        ForEach(Array(vm.items.enumerated()), id: \.offset) { _, tool in
            ToolRecordCard(tool: tool, chinese: catalog.language.prefersChinese) {
                drillDown = tool
            }
        }
    }
}

/// One tool, with nothing to tap but the row itself.
struct ToolRecordCard: View {
    let tool: ToolSummary
    let chinese: Bool
    let onDrillDown: () -> Void

    var body: some View {
        HXCard {
            HStack(alignment: .top, spacing: 12) {
                HXAvatar(name: ToolDetailPresenter.title(for: tool, chinese: chinese))
                VStack(alignment: .leading, spacing: 7) {
                    titleRow
                    if let codeName = ToolDetailPresenter.codeNameLine(for: tool, chinese: chinese) {
                        HXValueText(codeName)
                    }
                    if let description = tool.details {
                        Text(verbatim: description)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    HXFlow(spacing: 6) { chips }
                        .padding(.top, 2)
                    let byline = RowMeta.byline(creator: tool.creator, createTime: tool.createTime)
                    if !byline.isEmpty {
                        Text(verbatim: byline)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onDrillDown)
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            Text(verbatim: ToolDetailPresenter.title(for: tool, chinese: chinese))
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            Spacer(minLength: 0)
            HXChevron()
        }
    }

    /// The parameter count opens the shared environment sheet; the two marks are the two flags that change
    /// how a call runs. A row with neither shows neither — an explicit "no" on every card would say nothing.
    @ViewBuilder
    private var chips: some View {
        if tool.requiresConfirmation {
            HXBadge("agent.binding.confirm", tone: .warning)
        }
        if tool.isMandatory {
            HXBadge("tool.badge.mandatory", tone: .danger)
        }
        if !tool.isEnabled {
            HXBadge("state.badge.disabled", tone: .textTertiary)
        }
        HXEnvParamsChip(entries: tool.entries, fallbackCount: tool.entryCountFallback)
    }
}

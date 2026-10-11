import SwiftUI
import HarnaxCore
import HarnaxKit

/// What a list row pushes: the agent's **runtime name**, which is the only address these routes take
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:70`, `:89`).
///
/// Not the database id of an agent row — `MemoryAgentRow.agentId` is named `agentId` by the server while carrying
/// a name (`MemoryAgentResponse.kt:19-20`), and handing a number to `GET /api/admin/memory/{agentId}` reads back
///「没有这台」.
public struct MemoryAgentRef: Hashable, Sendable {
    public let agentName: String

    public init(agentName: String) {
        self.agentName = agentName
    }
}

/// The owner's own long-term agent memory: which of their agents remember anything, and a way to have one
/// agent's memory removed.
///
/// Every row is the long-term layer and nothing else. The list groups both layers before subtracting the
/// conversation ones (`MemoryStoreGateway.kt:94`, `:95`), so a row that has only ever written the session layer
/// arrives with a blank body, no dates and a count of what is still owing a merge — the count is the only thing
/// that layer is ever represented by here, because no route addresses a conversation's own text
/// (`prod_doc/ios-memory-domain.zh-CN.md` §2).
public struct MemoryListView: View {
    @StateObject private var vm: MemoryListViewModel
    private let catalog: any MemoryCataloging

    public init(catalog: any MemoryCataloging) {
        self.catalog = catalog
        _vm = StateObject(wrappedValue: MemoryListViewModel(catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("memory.list.title")))
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
            .navigationDestination(for: MemoryAgentRef.self) { ref in
                MemoryDetailView(agentName: ref.agentName, catalog: catalog)
            }
            .confirmationDialog(
                Text(verbatim: hx("memory.purge.title")),
                isPresented: Binding(
                    get: { vm.purgeTarget != nil },
                    set: { if !$0 { vm.cancelPurge() } }
                ),
                titleVisibility: .visible,
                presenting: vm.purgeTarget
            ) { row in
                Button(role: .destructive) {
                    Task { await vm.confirmPurge() }
                } label: {
                    HXText("state.delete.confirm", row.agentId)
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: { row in
                // One sentence, three things: the consolidated `MEMORY.md`, every daily record, and the
                // conversation memory that has not been merged away yet — the prefix sweep the DELETE runs takes
                // all three (`MemoryStoreGateway.kt:153`, `:162`), so naming two of them would under-report what
                // just left.
                Text(verbatim: MemoryCopy.purgeWarning(row.agentId))
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
                message: hx(MemoryListViewModel.emptyKey),
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
                if let report = vm.report {
                    HXBanner(
                        "memory.purge.banner",
                        message: MemoryCopy.report(report),
                        systemImage: report.tone.systemImage,
                        tone: report.tone.slot
                    )
                }
                Text(verbatim: hxCount("memory.count", vm.agentCount))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Row identity is the array index: two agents whose names the backend left blank
                // (`MemoryAgentResponse.kt:20` defaults `agentId` to `""`) would otherwise collide under one
                // `ForEach` identity and drop a card.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { _, row in
                    MemoryAgentCard(
                        row: row,
                        isPurging: vm.purgingName == row.agentId,
                        canPurge: vm.canPurge(row),
                        onPurge: { vm.beginPurge(row) }
                    )
                }
                scopeNote
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    /// Whose memory this is, and why the list can hold an agent with nothing to show for it
    /// (`harnax-webui/src/pages/memory/index.tsx:332-339`).
    private var scopeNote: some View {
        Text(verbatim: hx("memory.list.note"))
            .font(.caption)
            .foregroundStyle(Color.hx(.textTertiary))
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 4)
    }
}

/// One agent's row.
///
/// The whole card is the read — a `NavigationLink` whose label is the card, as `ModelProviderListView.swift:169-185`
/// draws it, so the title, the chips and the card's own padding all open the detail. The purge is a corner menu
/// beside it, never on the card: one gesture should not both read somebody's memory and erase it.
struct MemoryAgentCard: View {
    let row: MemoryAgentRow
    let isPurging: Bool
    let canPurge: Bool
    let onPurge: () -> Void

    var body: some View {
        NavigationLink(value: MemoryAgentRef(agentName: row.agentId)) {
            HXCard {
                VStack(alignment: .leading, spacing: 7) {
                    titleRow
                    preview
                    chips
                    byline
                }
            }
        }
        .buttonStyle(.plain)
        .overlay(alignment: .topTrailing) { menu.padding(10) }
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            Text(verbatim: hxPresented(row.agentId) ?? "")
                .font(.headline.monospaced())
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.trailing, 34)
    }

    /// The curated layer's own text, or the sentence for there not being one yet: an empty body is a real answer
    /// here (`MemoryAgentResponse.kt:22-23` ships `""`, not null), and a card that printed nothing would read as
    /// a row that failed to load.
    @ViewBuilder
    private var preview: some View {
        if let content = hxPresented(row.content) {
            Text(verbatim: content)
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            HXText("memory.row.noCurated")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }

    @ViewBuilder
    private var chips: some View {
        let pending = row.pendingSessionLayers
        if row.dateCount > 0 || pending > 0 {
            HStack(spacing: 6) {
                if row.dateCount > 0 {
                    HXChip(hxCount("memory.row.days", row.dateCount))
                        .accessibilityLabel(hx("memory.column.days"))
                }
                if pending > 0 {
                    HXChip(MemoryCopy.pending(pending), tone: .warning)
                        .help(hx("memory.pending.why"))
                        .accessibilityLabel(hx("memory.column.pending"))
                }
                Spacer(minLength: 0)
            }
        }
    }

    @ViewBuilder
    private var byline: some View {
        if let day = MemoryCopy.day(row.lastModified) {
            Text(verbatim: hx("memory.row.updated") + " " + day)
                .font(.caption2)
                .foregroundStyle(Color.hx(.textTertiary))
        }
    }

    /// Purge only, and only for a row the screen can address. No permission gate: this is the caller's own
    /// memory, scoped by the tenant header alone, and the console runs no gate on this column either
    /// (`harnax-webui/src/pages/memory/index.tsx:283-309`).
    private var menu: some View {
        Menu {
            Button(role: .destructive, action: onPurge) {
                HXText("memory.purge.action")
            }
            .disabled(!canPurge)
        } label: {
            Image(systemName: isPurging ? "hourglass" : "ellipsis")
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 30, height: 30)
                .background(Color.hx(.surfaceAlt), in: Circle())
        }
        .accessibilityLabel(hx("state.action.more"))
    }
}

/// The memory screens' shared read of a row: the two counted sentences and the stamp.
///
/// Slicing rather than re-formatting, as `SkillDraftPresentation.swift:141-148` establishes for the review
/// queue — but the value being sliced is not the same shape: `lastModified` here is an **instant**
/// (`MemoryStoreGateway.kt:787-790` renders `Instant.toString()`, so `2026-10-05T12:00:00Z`), while every other
/// stamp in this app is a zoneless server wall clock. A time of day printed without its zone would be the
/// handset's offset away from the truth, so the row and the detail show the instant's **date**, which is the day
/// the memory was written and the same granularity the daily ledger is keyed on.
enum MemoryCopy {
    /// The console's empty cell (`harnax-webui/src/pages/memory/index.tsx:281`).
    static let dash = "-"

    /// `2026-10-05T12:00:00Z` → `2026-10-05`. Anything that is not a date-shaped head is `nil`, which drops the
    /// byline rather than guessing one — `hxMonthDay` (`Support/RowMeta.swift:21-27`) applies the same rule.
    static func day(_ raw: String?) -> String? {
        guard let text = hxPresented(raw) else { return nil }
        let head = text.split(whereSeparator: { $0 == "T" || $0 == " " }).first ?? Substring(text)
        let parts = head.split(separator: "-")
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2 else { return nil }
        return String(head)
    }

    /// 「%d 个会话的记忆还没并入长期层」— a count of conversations that still **owe** a merge, not of candidates
    /// waiting on a decision: a rejection leaves the layer where it is (`MemoryDraftServiceImpl.kt:314`) and the
    /// same material is proposed again next window, so「n 条待你审批」would be a different claim than the number
    /// supports (`MemoryStoreGateway.kt:108`, counted from `:468`).
    static func pending(_ count: Int) -> String {
        hxCount("memory.row.pending", count)
    }

    /// The one sentence the purge dialog is allowed to say, naming all three things the sweep takes.
    static func purgeWarning(_ agentName: String) -> String {
        hx("memory.purge.warning", hxPresented(agentName) ?? dash)
    }

    static func report(_ report: MemoryListViewModel.PurgeReport) -> String {
        switch report {
        case let .removed(agentName, objects):
            return hx("memory.purge.removed", hxPresented(agentName) ?? dash, objects)
        case let .nothingFound(agentName):
            return hx("memory.purge.nothing", hxPresented(agentName) ?? dash)
        }
    }

    /// The banner's colour and glyph, decided from the report rather than at the call site, so a zero-object
    /// purge cannot be painted as the success it refused to claim.
    static func tone(_ report: MemoryListViewModel.PurgeReport) -> (slot: PaletteSlot, systemImage: String) {
        switch report {
        case .removed: (slot: .success, systemImage: "checkmark.circle")
        case .nothingFound: (slot: .warning, systemImage: "questionmark.circle")
        }
    }
}

private extension MemoryListViewModel.PurgeReport {
    var tone: (slot: PaletteSlot, systemImage: String) { MemoryCopy.tone(self) }
}

import SwiftUI
import HarnaxCore
import HarnaxKit

/// The address of one memory candidate, as the queue hands it to the stack.
///
/// `MemoryDraftRow.id` is not nullable (`MemoryDraftResponse.kt:17` answers `val id: Long = 0`), so unlike the
/// skill queue's ref there is no addressless row to guard: the route is the id, and every row is tappable.
public struct MemoryDraftRef: Hashable, Sendable {
    public let id: Int64

    public init(id: Int64) { self.id = id }
}

/// The memory review queue: every merge a conversation has proposed for the owner's long-term layer.
///
/// The console's nine columns fold into a card the way the skill queue's do
/// (`HarnaxFeatures/SkillDrafts/SkillDraftListView.swift:5-12`): the agent whose layer the merge rewrites is the
/// title, the conversation it came out of and the two stamps are bylines, and the verdict and the state are
/// chips. What does *not* fold is the difference between the two counts a row carries: `sourceCount` is material
/// taken *out* and an approval clears those files, `targetCount` is the owner's own day files written *in*. One
/// number under one heading would tell the reviewer both halves of a merge are the same kind of touch.
public struct MemoryDraftListView: View {
    @StateObject private var vm: MemoryDraftListViewModel
    private let catalog: any MemoryDraftCataloging

    public init(catalog: any MemoryDraftCataloging) {
        self.catalog = catalog
        _vm = StateObject(wrappedValue: MemoryDraftListViewModel(catalog: catalog))
    }

    public var body: some View {
        VStack(spacing: 0) {
            filterBar
            // The same rule as the skill queue: a state view has no height of its own, so the switch takes what
            // the pinned bar leaves, or `harnaxScreen` centres the two and the arms land mid-screen.
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .harnaxScreen()
        .harnaxTitle(Text(verbatim: hx("memory.draft.title")))
        .task {
            if vm.phase == .loading { await vm.refresh() }
        }
        .refreshable { await vm.refresh() }
        .navigationDestination(for: MemoryDraftRef.self) { ref in
            MemoryDraftDetailView(id: ref.id, catalog: catalog, onDecided: { Task { await vm.refresh() } })
        }
    }

    /// The three arms and the search box, both pinned: an arm switch is the more important control and it must
    /// not scroll away while a long queue is being read (`drafts.tsx:310-356`).
    private var filterBar: some View {
        VStack(spacing: 10) {
            HXSegmented(segmentOptions, selection: statusBinding)
            searchBar
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    private var segmentOptions: [HXSegmentOption] {
        MemoryDraftStatus.allCases.enumerated().map { index, arm in
            HXSegmentOption(id: index, arm.titleKey, count: vm.countForSegment(arm))
        }
    }

    private var statusBinding: Binding<Int> {
        Binding(
            get: { MemoryDraftStatus.allCases.firstIndex(of: vm.status) ?? 0 },
            set: { vm.status = MemoryDraftStatus.allCases[safe: $0] ?? .pending }
        )
    }

    /// Submit-style, no debounce (`drafts.tsx:343-355`): the request goes out on Enter, on the search button and
    /// on the clear button, and the field's own text is not a query until one of those happens. The placeholder
    /// says what the term matches, because the route filters on exactly one column (`MemoryDraftController.kt:69`).
    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.body)
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 22)
            TextField(
                hx("memory.draft.search"),
                text: $vm.keyword
            )
            .font(.body)
            .foregroundStyle(Color.hx(.textPrimary))
            .tint(Color.hx(.brand))
            .onSubmit {
                Task { await vm.search() }
            }
            if !vm.keyword.isEmpty {
                Button {
                    Task { await vm.clearSearch() }
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.body)
                        .foregroundStyle(Color.hx(.textTertiary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(hx("common.cancel"))
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 12)
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .strokeBorder(Color.hx(.separator), lineWidth: 1)
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
                message: hx(vm.emptyKey),
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
                // Row identity is the index, as in the skill queue: a page that a concurrent merge shifted would
                // otherwise render two cards under one id.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, row in
                    MemoryDraftRowCard(row: row)
                        .onAppear {
                            if index == vm.items.count - 1 { Task { await vm.loadMore() } }
                        }
                }
                if vm.isAppending {
                    HXStateView(.loading)
                }
                Text(verbatim: hx("memory.draft.queue.hint"))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }
}

/// One queue card.
struct MemoryDraftRowCard: View {
    let row: MemoryDraftRow

    var body: some View {
        NavigationLink(value: MemoryDraftRef(id: row.id)) { card }
            .buttonStyle(.plain)
    }

    private var card: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .top, spacing: 8) {
                    Text(verbatim: row.agentName.isEmpty ? hx("memory.draft.unnamed") : row.agentName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.hx(.textPrimary))
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .accessibilityLabel(hx("memory.draft.column.agent"))
                    Spacer(minLength: 0)
                    MemoryDraftStatusBadge(status: row.status)
                }
                HStack(spacing: 6) {
                    Image(systemName: "bubble.left")
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                    HXValueText(row.sessionId)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(hx("memory.draft.column.session"))
                badges
                stamps
            }
        }
        .overlay(alignment: .topTrailing) {
            Image(systemName: "chevron.forward")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.hx(.textTertiary))
                .padding(14)
        }
        .accessibilityElement(children: .combine)
    }

    /// What the merge reads and what it touches, as chips: the version it was merged against (`0` is「this agent
    /// has no long-term layer yet」, which is a different fact from「v0」— `drafts.tsx:140-151`), how much text it
    /// produced, and the two counts that say how many files the one approval moves.
    private var badges: some View {
        HXFlow(spacing: 6) {
            if row.baseVersion > 0 {
                HXChip("v\(row.baseVersion)")
            } else {
                HXChip(hx("memory.draft.firstLayer"), tone: .brand)
            }
            HXChip(hx("memory.draft.row.merged", row.mergedChars))
            HXChip(hx("memory.draft.row.sources", row.sourceCount))
            HXChip(hx("memory.draft.row.targets", row.targetCount))
                .help(hx("memory.draft.hint.targets"))
            if row.patched {
                HXChip(hx("memory.draft.patched"), tone: .indigo)
                    .help(hx("memory.draft.hint.patched"))
            }
        }
    }

    /// The two stamps, and the decided-by pair when the arm is a decided one (`drafts.tsx:186-258`). Absent
    /// values are dropped rather than printed as `-`: a card byline with three dashes reads as data loss.
    private var stamps: some View {
        Text(verbatim: stampLine)
            .font(.caption2)
            .foregroundStyle(Color.hx(.textTertiary))
            .fixedSize(horizontal: false, vertical: true)
    }

    private var stampLine: String {
        var pieces: [String] = []
        if let created = hxPresented(row.createTime) {
            pieces.append(hx("memory.draft.column.proposed") + " " + MemoryDraftCopy.stamp(created))
        }
        if let updated = hxPresented(row.updateTime) {
            pieces.append(hx("memory.draft.column.patched") + " " + MemoryDraftCopy.stamp(updated))
        }
        if let by = hxPresented(row.reviewedBy), let at = hxPresented(row.reviewedAt) {
            pieces.append(hx("memory.draft.column.decidedBy") + " " + by + " · " + MemoryDraftCopy.shortStamp(at))
        } else if let by = hxPresented(row.reviewedBy) {
            pieces.append(hx("memory.draft.column.decidedBy") + " " + by)
        }
        return pieces.isEmpty ? MemoryDraftCopy.dash : pieces.joined(separator: " · ")
    }
}

/// The queue's and the detail screen's shared read of a row: stamps, labels, colours.
///
/// Slicing rather than re-formatting, for the reason `SkillDraftCopy` gives
/// (`HarnaxFeatures/SkillDrafts/SkillDraftPresentation.swift:141-147`): a `DateFormatter` round trip would drag
/// the device's locale into a queue that has to read identically in both languages, and both serialisations the
/// stack answers with — the space form and the `T` form — are accepted. Unreadable text keeps itself rather than
/// being guessed at: a wrong timestamp on an approval screen is worse than a raw one.
public enum MemoryDraftCopy {
    /// The console's empty cell (`drafts.tsx:192,212`).
    public static let dash = "-"

    /// The empty cell for a value the server left out. `ALREADY_REVIEWED` still has to answer「who decided」with
    /// no `reviewedBy` to print (`MemoryDraftDecisionResponse.kt:56-57`), because a blank there reads as no
    /// decision at all.
    public static func dashOr(_ raw: String?) -> String { hxPresented(raw) ?? dash }

    /// `2026-10-08 09:12:33` → `2026-10-08 09:12`, in either serialisation the stack answers with.
    public static func stamp(_ raw: String?) -> String {
        guard let text = hxPresented(raw) else { return dash }
        guard let (date, time) = split(text) else { return text }
        guard let hourMinute = hhmm(time) else { return text }
        return "\(date) \(hourMinute)"
    }

    /// `2026-10-08 09:12:33` → `10-08 09:12`: the decided-by pair drops the year, as the console's column does
    /// (`drafts.tsx:252-256`).
    public static func shortStamp(_ raw: String?) -> String {
        guard let text = hxPresented(raw), let (_, time) = split(text), let hourMinute = hhmm(time) else {
            return dash
        }
        let parts = text.split(whereSeparator: { $0 == " " || $0 == "T" || $0 == "." })
        let day = parts[0].split(separator: "-")
        guard day.count == 3, day[1].count == 2, day[2].count == 2 else { return dash }
        return "\(day[1])-\(day[2]) \(hourMinute)"
    }

    /// The catalogue key of a status the contract has, or `nil` for anything else ever written in that column
    /// (`MemoryDraft.kt:42-48` defines three; the server refuses a fourth on the way in —
    /// `MemoryDraftServiceImpl.kt:173-175`).
    public static func statusKey(_ status: String?) -> String? {
        guard let status, let arm = MemoryDraftStatus(rawValue: status) else { return nil }
        return arm.titleKey
    }

    /// `PENDING` orange, `APPROVED` green, `REJECTED` red (`drafts.tsx:21-25`); anything else reads neutral
    /// rather than borrowing one of those three meanings.
    public static func statusTone(_ status: String?) -> PaletteSlot {
        switch status {
        case MemoryDraftStatus.pending.rawValue: .warning
        case MemoryDraftStatus.approved.rawValue: .success
        case MemoryDraftStatus.rejected.rawValue: .danger
        default: .textTertiary
        }
    }

    /// Characters as the queue and the console count them: Kotlin's `String.length`, which is UTF-16 units
    /// (`MemoryDraftServiceImpl.kt:517` computes `mergedChars` exactly that way). Swift's `count` is a grapheme
    /// count, so measuring with it would print a different number than the row the same merge produced.
    public static func charCount(_ text: String?) -> Int { text?.utf16.count ?? 0 }

    private static func split(_ text: String) -> (String, String)? {
        let parts = text.split(whereSeparator: { $0 == " " || $0 == "T" || $0 == "." })
        guard parts.count >= 2 else { return nil }
        return (String(parts[0]), String(parts[1]))
    }

    private static func hhmm(_ time: String) -> String? {
        let parts = time.split(separator: ":")
        guard parts.count >= 2, parts[0].count <= 2, parts[1].count == 2 else { return nil }
        return "\(parts[0]):\(parts[1])"
    }
}

/// The status pill.
///
/// Here rather than in each screen because the queue row, the detail header and the decided banner all have to
/// say the same word in the same colour — and none of them may echo a catalogue key when the column holds
/// something other than the three arms.
public struct MemoryDraftStatusBadge: View {
    private let status: String?

    public init(status: String?) {
        self.status = status
    }

    public var body: some View {
        if let key = MemoryDraftCopy.statusKey(status) {
            HXBadge(key, tone: MemoryDraftCopy.statusTone(status))
        } else if let text = hxPresented(status) {
            HXChip(text, tone: .textTertiary)
        } else {
            HXChip(MemoryDraftCopy.dash)
        }
    }
}

/// A machine-owned value with the whole text one tap away.
///
/// The console renders a session id and a replacement digest as `Typography.Text code copyable`
/// (`draftDetail.tsx:533`, `:204`); `HXValueText` truncates by width and keeps the value in its context menu, so
/// this adds the visible button a reviewer on a phone would otherwise have to discover by long press.
struct MemoryDraftHash: View {
    private let label: String
    private let full: String

    init(label: String, full: String) {
        self.label = label
        self.full = full
    }

    var body: some View {
        HStack(spacing: 8) {
            HXValueText(label)
            Button {
                HXPasteboard.copy(full)
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.brand))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: hx("common.copy")))
            Spacer(minLength: 0)
        }
    }
}

private extension Array {
    /// The arm index comes straight off a segmented control, and a segment that no longer maps to an arm must
    /// not be able to trap the queue in a `fatalError`.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

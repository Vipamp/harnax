import SwiftUI
import HarnaxCore
import HarnaxKit

/// The review queue screen: every draft the agents have proposed, filtered by what has been done with it.
///
/// Six Web columns fold into a card the same way `SkillTableView` folds its six
/// (`HarnaxFeatures/Skills/SkillTableView.swift:5-9`) — name as the title, the two stamps and the decided-by
/// line as bylines, the verdict and the state as chips. What does *not* fold is the distinction between the
/// two finding counts: the row carries the sandbox's number only (`SkillDraftRow` has no `localFindings` at
/// all), and the queue therefore never claims to show what the content scan found. That claim belongs to the
/// detail screen (§5.3), where the two lists sit in separate cards.
public struct SkillDraftListView: View {
    @StateObject private var vm: SkillDraftListViewModel
    private let drafts: any SkillDraftCataloging
    private let skills: (any SkillCataloging)?

    /// `skills` is only ever used by the detail's「打开技能」offer; a host without it still has a working queue.
    public init(drafts: any SkillDraftCataloging, skills: (any SkillCataloging)? = nil) {
        self.drafts = drafts
        self.skills = skills
        _vm = StateObject(wrappedValue: SkillDraftListViewModel(drafts: drafts))
    }

    public var body: some View {
        VStack(spacing: 0) {
            filterBar
            content
        }
        .harnaxScreen()
        .navigationTitle(Text(verbatim: hx("skill.draft.title")))
        .task {
            if vm.phase == .loading { await vm.refresh() }
        }
        .refreshable { await vm.refresh() }
        .navigationDestination(for: SkillDraftRef.self) { ref in
            SkillDraftDetailView(id: ref.id, drafts: drafts, skills: skills)
        }
    }

    /// The three arms and the search box, both pinned: an arm switch is the more important control and it
    /// must not scroll away while a long queue is being read (`drafts.tsx:193-205`).
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
        SkillDraftStatus.allCases.enumerated().map { index, arm in
            HXSegmentOption(
                id: index,
                arm.titleKey,
                count: arm == vm.status ? vm.pendingCountForSegment : nil
            )
        }
    }

    private var statusBinding: Binding<Int> {
        Binding(
            get: { SkillDraftStatus.allCases.firstIndex(of: vm.status) ?? 0 },
            set: { vm.status = SkillDraftStatus.allCases[safe: $0] ?? .pending }
        )
    }

    /// Submit-style, no debounce (`drafts.tsx:206-215`): the request goes out on Enter, on the search button
    /// and on the clear button, and the field's own text is not a query until one of those happens.
    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.body)
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 22)
            TextField(
                hx("skill.draft.search"),
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
                // Row identity is the index: a page may carry rows whose `id` the backend left null, and two
                // nil ids under one `ForEach` identity would drop a card.
                ForEach(Array(vm.items.enumerated()), id: \.offset) { index, row in
                    SkillDraftRowCard(row: row)
                        .onAppear {
                            if index == vm.items.count - 1 { Task { await vm.loadMore() } }
                        }
                }
                if vm.isAppending {
                    HXStateView(.loading)
                }
                // The queue's own footnote (`drafts.tsx:250-255`): where these rows come from, and that
                // approving one publishes it for every agent that can see it.
                queueNote
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    private var queueNote: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: hx("skill.draft.queue.hint"))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 4)
    }
}

/// One queue card.
///
/// The card carries its own push, and an id-less row renders the same as every other one with the link simply
/// not there — `SkillDraftRef` unwraps a nullable id (`SkillDraftResponse.kt:16-20`).
struct SkillDraftRowCard: View {
    let row: SkillDraftRow

    var body: some View {
        if let ref = SkillDraftRef.forRow(row) {
            NavigationLink(value: ref) { card }
                .buttonStyle(.plain)
        } else {
            card
        }
    }

    private var card: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                Text(verbatim: row.title ?? hx("skill.table.unnamed"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .accessibilityLabel(hx("skill.draft.column.name"))
                badges
                if let detail = row.detail {
                    Text(verbatim: detail)
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                stamps
            }
        }
        .overlay(alignment: .topTrailing) {
            Image(systemName: "chevron.forward")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(row.id == nil ? Color.hx(.separator) : Color.hx(.textTertiary))
                .padding(14)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var badges: some View {
        HStack(spacing: 6) {
            SkillDraftStatusBadge(status: row.status)
            if let verdict = hxPresented(row.scanVerdict) {
                HXChip(
                    verdict,
                    tone: SkillDraftCopy.verdictTone(row.scanVerdict)
                )
                .help(hx("skill.draft.hint.upstream"))
            }
            if row.upstreamFindingCount > 0 {
                HXChip(
                    hx("skill.draft.row.findings", row.upstreamFindingCount),
                    tone: .warning
                )
                .accessibilityLabel(hx("skill.draft.column.findings"))
                .help(hx("skill.draft.hint.upstream"))
            }
            if row.isPatched {
                HXChip(hx("skill.draft.patched"), tone: .indigo)
                    .help(hx("skill.draft.hint.patched"))
            }
        }
    }

    /// The two stamps the row carries, and the decided-by pair when the arm is a decided one
    /// (`drafts.tsx:148-156`). Absent values are dropped rather than printed as `-`: unlike a table cell, a
    /// card byline with three dashes reads as data loss.
    private var stamps: some View {
        Text(verbatim: stampLine)
            .font(.caption2)
            .foregroundStyle(Color.hx(.textTertiary))
            .fixedSize(horizontal: false, vertical: true)
    }

    private var stampLine: String {
        var pieces: [String] = []
        if let created = hxPresented(row.createTime) {
            pieces.append(hx("skill.draft.column.proposed") + " " + SkillDraftCopy.stamp(created))
        }
        if let updated = hxPresented(row.updateTime) {
            pieces.append(hx("skill.draft.column.patched") + " " + SkillDraftCopy.stamp(updated))
        }
        if let by = hxPresented(row.reviewedBy), let at = hxPresented(row.reviewedAt) {
            pieces.append(hx("skill.draft.column.decidedBy") + " " + by + " · " + SkillDraftCopy.shortStamp(at))
        } else if let by = hxPresented(row.reviewedBy) {
            pieces.append(hx("skill.draft.column.decidedBy") + " " + by)
        }
        return pieces.joined(separator: " · ")
    }
}

private extension Array {
    /// The arm index comes straight off a segmented control, and a segment that no longer maps to an arm must
    /// not be able to trap the queue in a `fatalError`.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

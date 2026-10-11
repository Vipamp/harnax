import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The drill-down: the calls one subject made inside the window the row was counted over.
///
/// This is the console's records drawer (`harnax-webui/src/pages/call-metrics/index.tsx:514-561`) as a pushed
/// screen. The drawer's other panel — `SubjectDrawer.tsx`, the subject's *profile* — is not portable here: it
/// reads the agent, MCP, CLI and session registries, and `ToolMetricsReading` answers three calls-metrics routes
/// and nothing else (`Sources/HarnaxCore/Contract/ToolMetrics.swift:296-315`). The facts it would show are
/// another domain's reads, so this screen shows what the row itself is a count of, and the row's own numbers stay
/// on the card that pushed.
///
/// The query is the pushed value's, not a fresh one: the drawer "may not outlive the query it came from"
/// (`index.tsx:105-112`), and a `CallMetricsSubjectRef` carrying the window and the dimension is what makes that
/// a structural fact rather than a cleanup step.
///
/// Read side only, as the whole domain is: one GET, paged.
@MainActor
public final class CallInvocationsViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [CallInvocationDisplayRow] = []
    @Published public private(set) var total = 0
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    /// A tail or a refresh that failed beside rows already on screen: the list stays, and says what did not
    /// arrive (`SkillDraftListViewModel.inlineError`).
    @Published public private(set) var inlineError: String?

    /// The terminal-state filter, `nil` for every outcome.
    ///
    /// The route takes one `outcome` and no predicate at all when it is blank (`ToolMetricsController.kt:101`),
    /// so "all" is the absence of the parameter rather than a fifth code the server would have to recognise.
    @Published public var outcome: CallInvocationOutcome? {
        didSet {
            guard outcome != oldValue else { return }
            // A different filter is a different result set: page 3 of every outcome is not a continuation of the
            // ERROR list, and `append` would key its de-duplication off the wrong total.
            resetPages()
            Task { await refresh() }
        }
    }

    /// What this screen is a page of, and the interval it was asked over.
    public let subject: CallMetricsSubjectRef

    private let catalog: any ToolMetricsReading
    private var pages: PagedState<CallInvocationRow>

    /// Refresh identity and the one-in-flight rule, as `SkillDraftListViewModel` documents them: the last answer
    /// to arrive must not be the one that wins the rows, the page counter and the total.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped.
    private var appendRequested = false

    /// `size` is the console's own page size, and the contract's (`index.tsx:135`, `ToolMetrics.swift:264`).
    public init(subject: CallMetricsSubjectRef, catalog: any ToolMetricsReading, pageSize: Int = 20) {
        self.subject = subject
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    /// The header: which subject, over which window. Both are the server's or the pushing row's own words, so the
    /// line is one key with two arguments (`CallMetricsLabels.group(_:)`, `CallMetricsWindows.title(_:)`).
    public var subjectNote: String {
        hx("monitor.calls.detail.subject", CallMetricsLabels.group(subject.group), CallMetricsWindows.title(subject.window))
    }

    /// Why a record list can be empty beside a table that is not: the aggregate dimensions keep their counts
    /// longer than the detail table keeps rows, so a trend with a bar in it can page to nothing
    /// (`ToolMetrics.swift:108-111`, `ToolMetricsController.kt:79-83`). The console notes the retention window on
    /// the two detail dimensions of its table (`index.tsx:478-485`); here the note belongs wherever the rows the
    /// card counted came from the aggregate, because that is the pairing that can disagree.
    public var showsRetentionNote: Bool { subject.group.readsAggregate }

    /// The empty sentence of this read, which is not the table's: no recorded call answers here while the subject
    /// may still have calls counted in the aggregate above.
    public var emptyKey: String { "monitor.calls.detail.empty" }

    /// How many calls answered in total, in the app's counted copy.
    public var totalNote: String { hxCount("monitor.calls.detail.total", total) }

    /// The origin this page was filtered by, resolved from the row's own bucket with the list screen's filter
    /// behind it — the predicate `CallMetricsSubjectRef.invocationQuery(num:size:outcome:)` builds, stated back
    /// so the operator can see which calls the list is (`index.tsx:126-128`).
    public var originNote: String? {
        guard let kind = currentQuery.kind else { return nil }
        return hx("monitor.calls.detail.origin", CallMetricsLabels.kind(kind.rawValue) ?? "")
    }

    // MARK: - reads

    /// The query in force: the pushed subject, the pushed window, the outcome arm and the page size.
    public var currentQuery: CallInvocationQuery {
        subject.invocationQuery(num: 1, size: pages.pageSize, outcome: outcome)
    }

    public func refresh() async {
        refreshGeneration += 1
        guard !isRefreshing else {
            rerunRequested = true
            return
        }
        await runRefresh(generation: refreshGeneration)
        while rerunRequested {
            rerunRequested = false
            await runRefresh(generation: refreshGeneration)
        }
    }

    private func runRefresh(generation: Int) async {
        isRefreshing = true
        defer { isRefreshing = false }
        inlineError = nil
        // Page 1 every time, and the accumulated rows are replaced rather than topped up: the console opens the
        // drawer empty for the same reason — a failed request would otherwise leave the previous subject's calls
        // listed under this one's title (`index.tsx:117-120`).
        switch await catalog.invocations(currentQuery) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            pages.replace(with: page)
            apply()
            await reissueAppend()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            guard ErrorMessage.carriesNews(error) else { return }
            let text = ErrorMessage.text(for: error)
            if items.isEmpty {
                phase = .failed(text)
            } else {
                inlineError = text
            }
        }
    }

    /// The list's last row asking for the next page.
    public func loadMore() async {
        guard canLoadMore, !isAppending else { return }
        guard !isRefreshing else {
            appendRequested = true
            return
        }
        await runAppend()
    }

    /// The tail read under the identity of the query that was on screen when the scroll happened, so a page that
    /// answers after an outcome switch is discarded rather than appended to the wrong list.
    private func runAppend() async {
        let generation = refreshGeneration
        isAppending = true
        defer { isAppending = false }
        let query = subject.invocationQuery(
            num: pages.pageNum + 1, size: pages.pageSize, outcome: outcome
        )
        switch await catalog.invocations(query) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func reissueAppend() async {
        guard appendRequested, canLoadMore, !isAppending else { return }
        appendRequested = false
        await runAppend()
    }

    /// Drop the accumulated rows without touching the outcome arm — that is the query, and a caller changing it
    /// wants the tail of the *new* one.
    private func resetPages() {
        refreshGeneration += 1
        items = []
        total = 0
        inlineError = nil
        pages = PagedState(pageSize: pages.pageSize)
        phase = .loading
    }

    private func apply() {
        // Server order, kept: the route answers `ORDER BY l.ts DESC, l.id DESC`
        // (`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:182-184`), and a newest-first
        // record list is the reading the console gives its operator. Nothing is re-sorted or filtered out here.
        items = pages.elements.map(CallInvocationDisplayRow.init(row:))
        total = pages.total
        canLoadMore = pages.hasMore
        phase = pages.isEmpty ? .empty : .content
    }
}

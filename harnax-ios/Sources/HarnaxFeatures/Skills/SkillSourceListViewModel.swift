import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The left half of the skill screen: the sources, and the writes a source row owns.
///
/// Two decisions that only look small:
/// - the list reads `/skill-sources/page` rather than `/skill-sources/active`, because only the paged
///   endpoint fills `enabledSkillCount`, and that count is the only thing standing between an operator and a
///   delete (`SkillSourceResponse.kt:58-62`);
/// - a delete is blocked client-side on that count instead of letting the backend refuse it, because the row
///   already knows and the refusal message would arrive too late to explain the greyed-out button
///   (`RepositoryList.tsx:304-314`).
@MainActor
public final class SkillSourceListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [SkillSourceSummary] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public private(set) var deleteTarget: SkillSourceSummary?
    /// The sentence shown instead of a confirm dialog when the source still has enabled skills under it.
    @Published public private(set) var blockedMessage: String?
    /// What the last create or upload did — graded, because a `200` there does not mean the skills landed.
    @Published public private(set) var lastReport: SkillInstallReport?
    /// The open repository form, `nil` when no sheet is up. Settable because it is what `.sheet(item:)`
    /// presents: a write that landed clears it here, and a refused one leaves it — and its own `errorText` —
    /// on screen for the operator to fix.
    @Published public var repositoryForm: SkillRepositoryFormModel?
    /// What an edit leaves behind instead of a success: a PUT stores configuration and re-reads nothing, so
    /// saying "updated" would tell the operator the skills had already moved
    /// (`RepositoryForm.tsx:115-124`).
    @Published public private(set) var configurationHint: String?
    /// The picker hands this back, and the sheet keeps it until the upload has been answered. It is a plain
    /// `let` because it is an observable object in its own right — the sheet binds into it directly.
    public let upload: SkillUploadModel

    /// Auto-selection follows the console (`harnax-webui/src/pages/skill/index.tsx:58-62`): the first row is
    /// picked so the list is never reading as unselected, and a selection that has paged or been deleted out of
    /// the list falls back to the new head. Opening a table is not this state's job: the source row carries its
    /// own link value, so only a press can push one.
    @Published public var selection: Int64?
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }

    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let skills: any SkillCataloging
    /// The row's writes already gate on it in the view, and the repository form needs the same account for
    /// one control of its own: `isPublic` is disabled rather than hidden
    /// (`harnax-webui/src/utils/permissionUtil.ts:57-75`).
    private let account: AccountSnapshot?
    private var pages: PagedState<SkillSourceSummary>
    private var searchTask: Task<Void, Never>?
    /// Refresh identity and the one-in-flight rule, exactly as `AgentListViewModel` documents them: the
    /// last answer to arrive must not be the one that wins the rows, the page counter and the total.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped — see
    /// `AgentListViewModel`.
    private var appendRequested = false

    public init(skills: any SkillCataloging, account: AccountSnapshot? = nil, pageSize: Int = 20) {
        self.skills = skills
        self.account = account
        pages = PagedState(pageSize: pageSize)
        upload = SkillUploadModel(catalog: skills)
    }

    public var selectedSource: SkillSourceSummary? {
        guard let selection else { return nil }
        return items.first { $0.id == selection }
    }

    public func status(of source: SkillSourceSummary) -> Bool {
        statusOverrides[source.id] ?? source.isEnabled
    }

    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
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
        switch await skills.sourcePage(
            name: keyword,
            sourceType: nil,
            status: filter.queryValue,
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            pages.replace(with: page)
            statusOverrides = statusOverrides.filter { pendingIDs.contains($0.key) }
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

    public func loadMore() async {
        guard canLoadMore, !isAppending else { return }
        guard !isRefreshing else {
            appendRequested = true
            return
        }
        await runAppend()
    }

    /// The tail read, under the identity of the query that was on screen when the scroll happened, as
    /// `AgentListViewModel` documents it: a retired answer may take none of the rows, the total or the page
    /// counter with it.
    private func runAppend() async {
        let generation = refreshGeneration
        isAppending = true
        defer { isAppending = false }
        switch await skills.sourcePage(
            name: keyword,
            sourceType: nil,
            status: filter.queryValue,
            num: pages.pageNum + 1,
            size: pages.pageSize
        ) {
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

    public func setStatus(_ enabled: Bool, for source: SkillSourceSummary) async {
        guard !pendingIDs.contains(source.id) else { return }
        statusOverrides[source.id] = enabled
        pendingIDs.insert(source.id)
        defer { pendingIDs.remove(source.id) }
        if case let .failure(error) = await skills.setSourceStatus(id: source.id, enabled: enabled) {
            statusOverrides[source.id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// Reinstall without a picker: `names` stays off the request, which is the backend's "the whole source"
    /// (`SkillSourceInstallRequest.kt:13-15`). The report still has to be read, because a `200` here can
    /// carry per-skill failures — and the previous run's report goes first, since the sheet opens on a
    /// *change* of it and two identical answers would otherwise be one published value.
    public func installAll(_ source: SkillSourceSummary) async {
        guard !pendingIDs.contains(source.id) else { return }
        inlineError = nil
        lastReport = nil
        pendingIDs.insert(source.id)
        defer { pendingIDs.remove(source.id) }
        switch await skills.install(sourceID: source.id, names: nil) {
        case let .success(outcome):
            lastReport = SkillInstallReport.describe(outcome)
            await refresh()
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// `nil` means the list never got a count for this row (the picker endpoint), which is not the same news
    /// as `0` and therefore does not block anything: the backend still has the final word on the delete.
    public func requestDelete(_ source: SkillSourceSummary) {
        inlineError = nil
        blockedMessage = nil
        if let count = source.enabledSkills, count > 0 {
            deleteTarget = nil
            blockedMessage = hx("skill.source.delete.blocked", count)
            return
        }
        deleteTarget = source
    }

    public func cancelDelete() {
        deleteTarget = nil
    }

    /// A refusal is a normal answer here: the backend's sentence names whatever still binds the source, so
    /// it goes up as it arrived.
    public func confirmDelete() async {
        guard let source = deleteTarget else { return }
        deleteTarget = nil
        if case let .failure(error) = await skills.deleteSource(id: source.id) {
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[source.id] = nil
        if selection == source.id { selection = nil }
        await refresh()
    }

    /// Upload is create-and-install in one call, so the report is read off the answer rather than assumed
    /// (`SkillSourceInstallResponse.kt:12-17`).
    public func finishUpload() async {
        lastReport = nil
        lastReport = await upload.submit()
        if upload.succeeded { await refresh() }
    }

    /// SKILL-1 — opens the create form. The console has one Create button on the repository card
    /// (`harnax-webui/src/pages/skill/index.tsx:191-195,289-294`); the ZIP half of that button's form is this
    /// screen's own upload entry, because the create route refuses a ZIP
    /// (`SkillSourceServiceImpl.kt:119-123`).
    public func beginRepositoryCreate() {
        configurationHint = nil
        repositoryForm = SkillRepositoryFormModel(mode: .create, catalog: skills, account: account)
    }

    /// SKILL-2 — opens the same form seeded from one row. The console selects the row on the way into the
    /// edit (`RepositoryList.tsx:459-464`), and so does this: the highlight follows what is being changed.
    public func beginRepositoryEdit(_ source: SkillSourceSummary) {
        configurationHint = nil
        selection = source.id
        repositoryForm = SkillRepositoryFormModel(mode: .edit(source), catalog: skills, account: account)
    }

    /// The sheet's submit, and the only place either write reloads the list. The reload is the same
    /// generation-tokened `refresh()` the pull-to-refresh and the reinstall use, because a create installs
    /// rows the moment it stores the source and an edit changes a row the next sync will read
    /// (`harnax-webui/src/pages/skill/index.tsx:101-104`).
    ///
    /// The answer goes up *after* that reload and the sheet comes down before it: the report sheet opens on a
    /// change of `lastReport`, and presenting it while the form is still on screen would stack two sheets on
    /// one view. A write that was refused leaves the sheet — and its `errorText` — exactly where they were,
    /// which is what the console does by not calling `onSuccess()` at all (`RepositoryForm.tsx:133-134`).
    public func submitRepositoryForm() async {
        guard let form = repositoryForm else { return }
        lastReport = nil
        configurationHint = nil
        guard let outcome = await form.save() else { return }
        repositoryForm = nil
        await refresh()
        switch outcome {
        case let .created(report):
            lastReport = report
        case .updated:
            configurationHint = hx("skill.repository.updateHint")
        }
    }

    /// The list calls this when the operator has read the hint.
    public func dismissConfigurationHint() {
        configurationHint = nil
    }

    /// The sheet calls this as it closes. A report nobody has read left published would make "nothing yet" and
    /// "already dismissed" the same value to the list.
    public func dismissReport() {
        lastReport = nil
    }

    public func resetUpload() {
        upload.reset()
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            await self?.refresh()
        }
    }

    private func apply() {
        items = pages.elements
        total = pages.total
        canLoadMore = pages.hasMore
        phase = pages.isEmpty ? .empty : .content
        if let selection, items.contains(where: { $0.id == selection }) { return }
        selection = items.first?.id
    }
}

/// The ZIP sheet's state, kept out of the view so the "picked but not yet uploaded" rule is testable
/// (`RepositoryForm.tsx:258-277` — select, then submit explicitly; never upload on pick).
@MainActor
public final class SkillUploadModel: ObservableObject {
    @Published public var name = ""
    @Published public private(set) var pickedFile: String?
    @Published public private(set) var payload: Data?
    @Published public private(set) var isWorking = false
    @Published public private(set) var error: String?
    /// Set once the archive name or the body is missing, so the primary button can say why it is inert.
    @Published public private(set) var needsInput: String?

    private let catalog: (any SkillCataloging)?
    private(set) var succeeded = false

    public init(catalog: (any SkillCataloging)? = nil) {
        self.catalog = catalog
    }

    /// Back to a blank sheet: the next upload must not inherit the previous archive or its verdict.
    public func reset() {
        name = ""
        pickedFile = nil
        payload = nil
        isWorking = false
        error = nil
        needsInput = nil
        succeeded = false
    }

    public var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && payload != nil && !isWorking
    }

    /// The view reads the security-scoped URL into `Data` and hands both the name and the bytes here: that
    /// keeps every sandbox API on the UIKit side of the boundary.
    public func select(fileName: String, payload: Data) {
        pickedFile = fileName
        self.payload = payload
        error = nil
        needsInput = nil
    }

    public func clear() {
        pickedFile = nil
        payload = nil
    }

    /// Returns the graded report, or `nil` when the call never got an answer at all.
    public func submit() async -> SkillInstallReport? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            needsInput = hx("skill.source.upload.needName")
            return nil
        }
        guard let payload, let pickedFile, let catalog else {
            needsInput = hx("skill.source.upload.needFile")
            return nil
        }
        isWorking = true
        error = nil
        needsInput = nil
        defer { isWorking = false }
        switch await catalog.uploadSource(name: trimmed, fileName: pickedFile, payload: payload) {
        case let .success(result):
            succeeded = true
            return SkillInstallReport.describe(result.install)
        case let .failure(apiError):
            self.error = ErrorMessage.text(for: apiError)
            return nil
        }
    }
}

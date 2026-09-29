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
    /// The picker hands this back, and the sheet keeps it until the upload has been answered. It is a plain
    /// `let` because it is an observable object in its own right — the sheet binds into it directly.
    public let upload: SkillUploadModel

    /// Auto-selection follows the console (`harnax-webui/src/pages/skill/index.tsx:58-62`): the first row is
    /// picked so the right-hand table always has a subject. An explicit tap wins over it, and a selection
    /// that has paged or been deleted out of the list falls back to the new head.
    @Published public var selection: Int64?
    /// The source whose table the operator just asked for, and the only thing that pushes one.
    ///
    /// `selection` cannot carry this: `apply()` picks the head row on its own after every load, so a screen
    /// that pushed on that change opened a table the moment the tab appeared. There the automatic selection
    /// only decides which source the right-hand panel shows on the same screen
    /// (`harnax-webui/src/pages/skill/index.tsx:58-62`); here that panel is a pushed screen, so it follows the
    /// tap alone.
    @Published public private(set) var pendingOpen: SkillSourceSummary?
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }

    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let skills: any SkillCataloging
    private var pages: PagedState<SkillSourceSummary>
    private var searchTask: Task<Void, Never>?

    public init(skills: any SkillCataloging, pageSize: Int = 20) {
        self.skills = skills
        pages = PagedState(pageSize: pageSize)
        upload = SkillUploadModel(catalog: skills)
    }

    public var selectedSource: SkillSourceSummary? {
        guard let selection else { return nil }
        return items.first { $0.id == selection }
    }

    /// The row's gesture. It highlights as it goes, so the card that was pressed reads as the one whose table
    /// is on screen.
    public func open(_ source: SkillSourceSummary) {
        selection = source.id
        pendingOpen = source
    }

    /// The screen calls this once it has pushed, so a second press of the same row is a change again.
    public func didOpen() {
        pendingOpen = nil
    }

    public func status(of source: SkillSourceSummary) -> Bool {
        statusOverrides[source.id] ?? source.isEnabled
    }

    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await skills.sourcePage(
            name: keyword,
            sourceType: nil,
            status: filter.queryValue,
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            pages.replace(with: page)
            statusOverrides = statusOverrides.filter { pendingIDs.contains($0.key) }
            apply()
        case let .failure(error):
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
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
    }

    public func setStatus(_ enabled: Bool, for source: SkillSourceSummary) async {
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

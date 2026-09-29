import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The right half of the skill screen: the skills a source has stored, read through
/// `GET /api/admin/skills/page?repositoryId=` (`SkillController.kt:35-58`).
///
/// This table has no delete. The console's own skill list has no delete column either
/// (`SkillList.tsx:27-138`), and the delete that does exist lives on the source row — removing a single
/// stored skill would leave the source's sync report describing rows that are gone.
@MainActor
public final class SkillTableViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [SkillItem] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    /// The row whose switch is inert because something still binds the skill, with the reason to say.
    @Published public private(set) var boundNotice: String?

    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }

    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    /// The source this table is showing. Changing it has to drop every piece of accumulated state, which is
    /// why it is a stored property the view sets through `show(_:)` rather than a init-only value: a
    /// leftover page-12 tail or a stale keyword under a new source reads as data corruption.
    private var sourceID: Int64?
    private let skills: any SkillCataloging
    private var pages: PagedState<SkillItem>
    private var searchTask: Task<Void, Never>?

    public init(skills: any SkillCataloging, pageSize: Int = 20) {
        self.skills = skills
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of skill: SkillItem) -> Bool {
        guard let id = skill.id else { return skill.isEnabled }
        return statusOverrides[id] ?? skill.isEnabled
    }

    /// Points the table at a source, clearing what the previous one left behind.
    public func show(id: Int64?) async {
        guard let id else {
            guard sourceID != nil else { return }
            sourceID = nil
            reset()
            phase = .empty
            return
        }
        guard id != sourceID else { return }
        sourceID = id
        reset()
        await refresh()
    }

    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        guard let sourceID else {
            items = []
            phase = .empty
            return
        }
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await skills.skillPage(
            name: keyword,
            repositoryID: sourceID,
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
        guard canLoadMore, !isAppending, let sourceID else { return }
        isAppending = true
        defer { isAppending = false }
        switch await skills.skillPage(
            name: keyword,
            repositoryID: sourceID,
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

    /// The binding counts on the row are the gate, so the switch never sends a write the stack will refuse:
    /// "A bound skill can be neither disabled nor deleted from here" (`SkillResponse.kt:33-36`). The reason
    /// is spelled out per side because an operator with only teams bound has to hear about teams
    /// (`SkillList.tsx:94-134`).
    public func setStatus(_ enabled: Bool, for skill: SkillItem) async {
        boundNotice = nil
        guard let id = skill.id else { return }
        // Only the disable direction is gated: a row that has since been bound still has to be switchable
        // back on, and the server would refuse the disable anyway.
        if !enabled, skill.isBound {
            boundNotice = Self.bindingNotice(for: skill)
            return
        }
        await write(enabled, id: id)
    }

    public static func bindingNotice(for skill: SkillItem) -> String {
        switch (skill.boundAgentCount > 0, skill.boundTeamCount > 0) {
        case (true, true): hx("skill.bound.both", skill.boundAgentCount, skill.boundTeamCount)
        case (true, false): hx("skill.bound.agent", skill.boundAgentCount)
        case (false, true): hx("skill.bound.team", skill.boundTeamCount)
        case (false, false): hx("skill.bound.none")
        }
    }

    private func write(_ enabled: Bool, id: Int64) async {
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await skills.setSkillStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func reset() {
        keyword = ""
        filter = .all
        items = []
        statusOverrides = [:]
        boundNotice = nil
        inlineError = nil
        pages = PagedState(pageSize: pages.pageSize)
        phase = .loading
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
    }
}

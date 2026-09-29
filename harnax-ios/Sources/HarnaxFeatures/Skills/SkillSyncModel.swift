import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The sync flow, moved out of the view the same way `SessionRefreshModel` moves a refresh out of its sheet.
///
/// A sync is two calls and not one: `GET /skill-sources/{id}/fetch` reads the source without storing
/// anything, the operator prunes the list, and `POST /skill-sources/{id}/install` writes the selection
/// (`harnax-webui/src/pages/skill/index.tsx:127-152`). The modal exists because those two steps must not be
/// collapsed into one button.
///
/// The rule that shapes the whole state machine: the install may answer `200` while half of it failed, so a
/// report is a state the sheet sits in — not something to toast and dismiss.
@MainActor
public final class SkillSyncModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        /// The fetch answered with an empty list: the source opened fine and simply holds nothing. Kept
        /// apart from `failed`, which says the opposite.
        case empty
        case ready
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var rows: [SkillPreviewItem] = []
    @Published public private(set) var selected: Set<String> = []
    @Published public private(set) var report: SkillInstallReport?
    @Published public private(set) var isSubmitting = false
    /// The install call itself was refused — a non-200 envelope — as opposed to rows inside a `200`.
    @Published public private(set) var submitError: String?
    /// The sheet dismisses on it, and only on a run with nothing left to read.
    @Published public private(set) var isFinished = false
    /// Set as soon as an install was attempted with an answer, good or bad: the right-hand table has to be
    /// reloaded either way, because the source may have stored part of the selection
    /// (`SyncSkillModal.tsx:66-89`).
    @Published public private(set) var didChangeSkills = false

    public let source: SkillSourceSummary
    private let skills: any SkillCataloging

    public init(source: SkillSourceSummary, skills: any SkillCataloging) {
        self.source = source
        self.skills = skills
    }

    public var title: String { source.title }
    public var selectedCount: Int { selected.count }
    public var isNewCount: Int { rows.filter { !$0.exists && selected.contains($0.id) }.count }

    /// Step one. Everything arrives checked: an operator who means "sync it all" should not have to tick a
    /// long list first, and unchecking is the rarer gesture (`SyncSkillModal.tsx:38-52`).
    public func load() async {
        phase = .loading
        report = nil
        submitError = nil
        switch await skills.preview(sourceID: source.id) {
        case let .success(items):
            rows = items
            selected = Set(items.compactMap(\.name))
            phase = items.isEmpty ? .empty : .ready
        case let .failure(error):
            rows = []
            selected = []
            phase = .failed(ErrorMessage.text(for: error))
        }
    }

    /// Ticking is over once the answer is on screen: the report describes what was sent, not what the
    /// checkboxes now say.
    public func toggle(_ item: SkillPreviewItem) {
        guard report == nil, let id = item.name else { return }
        if selected.contains(id) {
            selected.remove(id)
        } else {
            selected.insert(id)
        }
    }

    public func selectAll() {
        guard report == nil else { return }
        selected = Set(rows.compactMap(\.name))
    }

    public func selectNone() {
        guard report == nil else { return }
        selected = []
    }

    /// In preview order rather than sorted by name, so what goes over the wire matches what was on screen.
    public var selectedNames: [String] {
        rows.compactMap { $0.name }.filter { selected.contains($0) }
    }

    /// Step two. An empty selection never reaches the backend: `names: []` is a real answer there — "the
    /// caller had nothing to store" — and sending it would overwrite the source's sync report with a lie
    /// about a run that never happened (`SkillSourceInstallRequest.kt:6-11`).
    public func submit() async {
        guard !isSubmitting else { return }
        guard !selectedNames.isEmpty else {
            submitError = hx("skill.sync.pickOne")
            return
        }
        isSubmitting = true
        submitError = nil
        defer { isSubmitting = false }
        switch await skills.install(sourceID: source.id, names: selectedNames) {
        case let .success(outcome):
            didChangeSkills = true
            let built = SkillInstallReport.describe(outcome)
            report = built
            if built.isClean { isFinished = true }
        case let .failure(error):
            didChangeSkills = true
            submitError = ErrorMessage.text(for: error)
        }
    }

    public var primaryTitle: String { hx("skill.sync.submit", selectedCount) }

    /// After an answer the footer stops offering "install" and offers "done": the run is over, and a second
    /// one needs a fresh preview.
    public func closeAfterReport() {
        isFinished = true
    }

    public func bucketRows(_ bucket: SkillInstallBucket) -> [SkillInstallLine] { bucket.lines }
}

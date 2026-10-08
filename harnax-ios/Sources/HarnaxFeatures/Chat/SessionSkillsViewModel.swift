import Foundation
import Combine
import HarnaxCore

/// The session page's own skill panel: what this conversation's agent wrote, which of it the conversation may
/// already use, and the one action that changes the second answer.
///
/// The panel's data lives here rather than on `ChatViewModel`, because the two have different lifetimes: the
/// transcript, the composer flags and the occupancy tag are re-taken when the conversation opens
/// (`ChatView.swift:115-126`), while these two reads are asked only once a finger opens the drawer. Nothing on
/// this screen is drawn from the stream either, so a conversation that is mid-turn needs no re-read here.
///
/// `SessionSkillReading` merges both legs behind one call (`SessionSkillRules.merged`), so this model holds no
/// merge rule of its own — the console's drawer and this panel are meant to list the same rows for the same
/// session.
@MainActor
public final class SessionSkillsViewModel: ObservableObject {
    @Published public private(set) var rows: [SessionSkillRow] = []
    /// Opens on the spinner, the way every other list in the app does (`TeamArtifactsViewModel`'s `phase` starts
    /// at `.loading`, and `SkillDraftListViewModelTests` names the rule: 「the screen opens on a spinner, not on an
    /// empty promise」). A panel that had not asked yet would otherwise draw an empty list for one frame, and an
    /// empty list on this screen is a sentence about the conversation.
    @Published public private(set) var isLoading = true
    /// Either read failed, or no host wired the dependency at all.
    ///
    /// Kept apart from an empty `rows`, because the two say opposite things: `chat.skills.empty` claims this
    /// agent has not written a skill, and a queue that would not load cannot support that claim. The rows from
    /// an earlier successful read stay on screen when a later re-read fails — the same rule every other list in
    /// the app follows (`TeamArtifactsViewModel.load()`).
    @Published public private(set) var unavailable = false
    /// Why the last enable was refused, already reduced to the one cause it names. `notice` rather than a toast:
    /// this app has no toast, and a sentence that times out is no use to someone still looking at the row that
    /// produced it (`SkillDraftDetailViewModel.notice`).
    @Published public private(set) var notice: SessionSkillRefusal?
    /// The row whose enable is in flight, or `nil` while the panel is not writing. The name rather than a bare
    /// flag, because the sheet has one button per row and only the row that is being copied may claim to be busy
    /// (`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx`'s `busyName`).
    @Published public private(set) var actingName: String?
    /// Whether the panel's one mutator is already running — the guard every other write in the app carries
    /// (`SkillDraftDetailViewModel.isActing`, `SkillSyncModel.isSubmitting`, `LoginViewModel.isSubmitting`), and
    /// the reason the row's button can be disabled honestly rather than optimistically.
    public var isActing: Bool { actingName != nil }

    /// `nil` for a host that carries no session-skill surface. The entry row is not offered then, and a panel
    /// opened some other way says it cannot read rather than that there is nothing to read.
    private let reading: (any SessionSkillReading)?
    private let sessionId: String
    /// Re-reads come from the drawer opening, the refresh button, pull-to-refresh and the re-read that follows an
    /// enable, and none of them is awaited by the others; the last answer wins
    /// (`ChatViewModel.refreshContextUsage()`'s generation guard, `:1471-1485`).
    private var refreshGeneration = 0

    public init(reading: (any SessionSkillReading)?, sessionId: String) {
        self.reading = reading
        self.sessionId = sessionId
    }

    /// Both reads, merged.
    public func refresh() async {
        guard let reading else {
            // Nothing is ever coming to answer, so the spinner has to stop here: `isLoading` starts true precisely
            // so an unanswered panel cannot read as an empty one, and this is the one case with no answer ahead.
            isLoading = false
            unavailable = true
            return
        }
        refreshGeneration += 1
        let generation = refreshGeneration
        isLoading = true
        let read = await reading.read(sessionId: sessionId)
        // The generation is the order: an older ask that lands after a newer one would put the rows from before
        // that newer one back on screen, and a failed re-read would erase a list the user could still act on.
        guard generation == refreshGeneration else { return }
        isLoading = false
        if read.unavailable, read.rows.isEmpty {
            // Not one leg answered. The list on screen becomes a statement about an older answer, and the only
            // honest move is to keep it and say the read is missing — an unanswered re-read is not news that a
            // skill went away, and rows that answered a moment ago are still rows the operator can act on
            // (`TeamArtifactsViewModel.load()` keeps its rows for the same reason).
            unavailable = true
        } else {
            rows = read.rows
            unavailable = read.unavailable
        }
    }

    /// Enable one of this session's own drafts, then re-read: the row's answer to「is it enabled」comes from the
    /// directory, so the panel has to ask again rather than assume the tap worked
    /// (`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx` re-pulls both reads for the same
    /// reason).
    ///
    /// One write at a time. This call copies `SKILL.md` into the session's enabled zone, so a second tap while
    /// the first is outstanding copies it twice and can then be refused with 409 by the ceiling the first tap
    /// filled — a sentence about a limit the user never reached. The flag covers the re-read too: until the
    /// directory has answered, the row still does not know whether it is enabled.
    public func enable(name: String) async {
        guard !isActing else { return }
        guard let reading else {
            notice = SessionSkillRefusal(code: -1)
            return
        }
        notice = nil
        actingName = name
        defer { actingName = nil }
        do {
            try await reading.enable(sessionId: sessionId, name: name)
        } catch let refusal as SessionSkillRefusal {
            notice = refusal
            return
        } catch {
            notice = SessionSkillRefusal(code: -1)
            return
        }
        await refresh()
    }
}

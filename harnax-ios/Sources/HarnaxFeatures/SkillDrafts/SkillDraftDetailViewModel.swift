import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// One draft, its two scans, and the only two writes the review surface has.
///
/// The shape of this view model is dictated by §5.4 of `specs/07-skill-draft-review.md`: a refusal the
/// reviewer can act on does **not** arrive as an error. It arrives with `code: 200` and a
/// `SkillDraftDecision` whose `outcome` says what actually happened, and the six answers need six different
/// follow-ups. Three rules are load-bearing and easy to water down:
///
/// - `NAME_TAKEN` returns **without** re-reading. The conflict dialog has to keep the name this attempt tried
///   to land, and a reload would overwrite the draft the reviewer is mid-decision on
///   (`draftDetail.tsx:179-184`). Every other outcome branch reloads, because the row it describes moved.
/// - An error envelope does not reload. It is the server's sentence about a row that has not changed; a
///   re-read would replace a refusal still being read with a row that says nothing. The one exception is the
///   promote-name race — an error envelope too, `code: 409` inside HTTP 200
///   (`SkillDraftController.kt:113-117`) — where the write rolled back and the pending row has to be re-read
///   before it can be decided again.
/// - `expectedDigest` is read off the freshly loaded detail rather than mirrored into its own property, so
///   "re-read refreshes the digest" (`draftDetail.tsx:70-91`) cannot be broken by a forgotten assignment.
@MainActor
public final class SkillDraftDetailViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case ready
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var draft: SkillDraftDetail?
    @Published public private(set) var isActing = false
    /// What the last decision actually did, once it stopped being a button.
    @Published public private(set) var notice: SkillDraftNotice?
    /// Non-nil only while the name-conflict dialog is open — the one state the approve flow reaches without
    /// the draft having moved.
    @Published public private(set) var conflict: SkillDraftConflict?
    @Published public var resolution: SkillDraftResolution = .rename
    @Published public var newName = ""
    @Published public private(set) var conflictRefusal: String?
    @Published public var rejectReason = ""
    @Published public private(set) var rejectRefusal: String?
    /// Approval is a publish, so it is confirmed before it is sent (§5.4).
    ///
    /// Both dialog flags are settable rather than `private(set)`: `sheet(isPresented:)` and
    /// `confirmationDialog(isPresented:)` own the dismissal gesture (swipe down, tap outside), and a binding the
    /// view cannot write would leave the flag true after the sheet is gone. `start…`/`close…` remain the way
    /// to open them with the form prepared.
    @Published public var isRejectOpen = false
    @Published public var isConfirmingApprove = false
    @Published public var tab: SkillDraftTab = .body

    public let id: Int64
    private let drafts: any SkillDraftCataloging
    /// `nil` when the host carries no skill surface: the promoted outcomes then say the same sentence
    /// **without** the「打开技能」offer (§5.4 末行).
    private let skills: (any SkillCataloging)?
    /// Re-reads come from several branches and are not awaited by their callers; the last answer wins.
    private var loadGeneration = 0

    public init(id: Int64, drafts: any SkillDraftCataloging, skills: (any SkillCataloging)? = nil) {
        self.id = id
        self.drafts = drafts
        self.skills = skills
    }

    // MARK: - Reads

    public func load() async {
        loadGeneration += 1
        let generation = loadGeneration
        if draft == nil { phase = .loading }
        switch await drafts.detail(id: id) {
        case let .success(detail):
            guard generation == loadGeneration else { return }
            draft = detail
            phase = .ready
        case let .failure(error):
            guard generation == loadGeneration else { return }
            guard ErrorMessage.carriesNews(error) else { return }
            let text = ErrorMessage.text(for: error)
            if draft == nil {
                phase = .failed(text)
            } else {
                notice = .refused(message: text)
            }
        }
    }

    /// The digest the next approval will send, straight off the row on screen. `nil` means this screen has
    /// nothing to approve with, which is a local refusal rather than a request the server would reject.
    public var expectedDigest: String? { hxPresented(draft?.contentDigest) }

    /// The pending banner's twelve characters (`draftDetail.tsx:399`), and the sixteens a replacement digest
    /// or a script hash gets (§5.7).
    public var digestPreview: String? { expectedDigest.map { SkillDraftRules.truncated($0, digits: 12) } }

    /// Whether the two decision buttons belong on screen: `PENDING` only (§5.3, `draftDetail.tsx:353-363`).
    public var isDecidable: Bool { draft?.isPending ?? false }

    /// The decided draft's report, `nil` while it is still pending.
    public var decidedSummary: SkillDraftDecided? {
        guard let draft, !draft.isPending else { return nil }
        return SkillDraftDecided(
            statusKey: SkillDraftCopy.statusKey(draft.status),
            by: SkillDraftCopy.dashOr(draft.reviewedBy),
            at: SkillDraftCopy.stamp(draft.reviewedAt),
            // The console prints the reason for a rejection and for nothing else (`draftDetail.tsx:413`).
            // Approval does blank the column server-side, but the row is what it is once it arrives here:
            // a stale reason must not turn an approved draft's banner into a rejection's.
            reason: draft.status == SkillDraftStatus.rejected.rawValue ? hxPresented(draft.rejectReason) : nil
        )
    }

    /// The skill the promoted outcomes offer to open: `nil` when no outcome wrote a row, and `nil` when the
    /// host has no skill facade to open it with.
    public var openableSkillID: Int64? {
        guard skills != nil else { return nil }
        return notice?.openableSkillID
    }

    // MARK: - Approval

    /// Opens the confirmation. Nothing is sent here: an approval writes a skill every agent can then load.
    ///
    /// The digest is checked **before** the confirmation opens, not after it (`draftDetail.tsx:222-232`): a
    /// draft with no digest cannot be approved at all, and asking the reviewer to confirm one and then
    /// refusing them the answer to their yes is worse than refusing them the question.
    public func approve() async {
        notice = nil
        guard expectedDigest != nil else {
            notice = .refused(message: hx("skill.draft.digest.missing"))
            await load()
            return
        }
        isConfirmingApprove = true
    }

    public func cancelApproval() {
        isConfirmingApprove = false
    }

    /// The confirmed first attempt — exactly one key on the wire, no `conflictResolution`
    /// (`SkillDraftApproveRequest.kt:31-35`).
    public func confirmApprove() async {
        isConfirmingApprove = false
        guard !isActing else { return }
        await sendApprove(resolution: nil, newName: nil)
    }

    // MARK: - Rejection

    public func startRejection() {
        notice = nil
        rejectRefusal = nil
        rejectReason = ""
        isRejectOpen = true
    }

    public func closeRejection() {
        isRejectOpen = false
        rejectReason = ""
        rejectRefusal = nil
    }

    /// Validated on the **trimmed** length and submitted trimmed (§5.6, `draftDetail.tsx:245-262`). The
    /// predicate is Core's, so this screen and the service cannot drift apart on where 512 sits: exactly 512
    /// is legal because `SkillDraftRules.isRejectReasonAdmissible` compares with `>`.
    public func submitRejection() async {
        guard !isActing else { return }
        let trimmed = rejectReason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard SkillDraftRules.isRejectReasonAdmissible(rejectReason) else {
            // The two halves of the same predicate get their own sentences:「write something」and「that is too
            // long」are different things to fix.
            rejectRefusal = trimmed.isEmpty
                ? hx("skill.draft.reject.reasonRequired")
                : hx("skill.draft.reject.tooLong", SkillDraftRules.maxRejectReasonLength)
            return
        }
        rejectRefusal = nil
        isActing = true
        defer { isActing = false }
        switch await drafts.reject(id: id, SkillDraftRejectPayload(reason: trimmed)) {
        case let .success(decision):
            isRejectOpen = false
            await apply(decision, askedName: draft?.name ?? "")
        case let .failure(error):
            // The sheet stays open holding what the reviewer typed, and the server's sentence goes inside it
            // rather than on the screen behind (`draftDetail.tsx:253-259` leaves the modal up on a refusal).
            rejectRefusal = ErrorMessage.text(for: error)
        }
    }

    // MARK: - Conflict

    /// The dialog's submit. `newName` is validated untrimmed and sent trimmed (§5.5); a `replace` answer has
    /// no name to validate, since it lands under the draft's own.
    public func resolveConflict() async {
        guard !isActing else { return }
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        if resolution == .rename {
            guard SkillDraftRules.isRenameAdmissible(newName) else {
                conflictRefusal = trimmed.isEmpty
                    ? hx("skill.draft.conflict.nameRequired")
                    : hx("skill.draft.conflict.nameTooLong", SkillDraftRules.maxSkillNameLength)
                return
            }
        }
        conflictRefusal = nil
        await sendApprove(resolution: resolution, newName: resolution == .rename ? trimmed : nil)
    }

    /// Switching arm retires the sentence that explained why the last one was refused — it describes an
    /// attempt that no longer matches what the dialog is about to send.
    public func choose(_ arm: SkillDraftResolution) {
        resolution = arm
        conflictRefusal = nil
    }

    /// Cancel closes, clears **and re-reads** (`draftDetail.tsx:485-489`): the digest this dialog was about to
    /// send may no longer be the digest on the row.
    public func cancelConflict() async {
        conflict = nil
        conflictRefusal = nil
        newName = ""
        resolution = .rename
        await load()
    }

    // MARK: - Wire

    /// The one approve call, shared by the first attempt (`resolution == nil`) and both conflict answers.
    private func sendApprove(resolution: SkillDraftResolution?, newName: String?) async {
        guard let digest = expectedDigest else {
            // No digest means nothing to approve against, so this refuses locally, re-reads, and never puts a
            // request on the wire (`draftDetail.tsx:222-232`).
            notice = .refused(message: hx("skill.draft.digest.missing"))
            await load()
            return
        }
        let askedName = askedName(for: resolution, newName: newName)
        isActing = true
        defer { isActing = false }
        let payload = SkillDraftApprovePayload(
            expectedDigest: digest,
            conflictResolution: resolution?.rawValue,
            newName: resolution == .rename ? newName : nil
        )
        switch await drafts.approve(id: id, payload) {
        case let .success(decision):
            await apply(decision, askedName: askedName)
        case let .failure(error):
            await handleFailure(error)
        }
    }

    /// The name this attempt asked to land, which is what a `NAME_TAKEN` has to show back rather than the
    /// draft's title (`draftDetail.tsx:179-184`).
    private func askedName(for resolution: SkillDraftResolution?, newName: String?) -> String {
        if resolution == .rename {
            return newName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        }
        return draft?.name ?? ""
    }

    private func handleFailure(_ error: APIError) async {
        if case .business(code: 409, _) = error {
            conflict = nil
            conflictRefusal = nil
            newName = ""
            // The write rolled back and the draft is still pending, so a re-read is the whole remedy.
            notice = .nameRace
            await load()
            return
        }
        // Anything else keeps the dialog and the form exactly as they are: the reviewer has a server sentence
        // to read and a name they may want to change and retry. Where that sentence lands depends on what is
        // on top — the conflict sheet covers the notice banner, so writing to the banner alone would report a
        // refusal the reviewer never sees.
        let text = ErrorMessage.text(for: error)
        if conflict != nil {
            conflictRefusal = text
        } else {
            notice = .refused(message: text)
        }
    }

    /// §5.4's table, applied to both decision calls: a reject can answer `REJECTED`, and an approve can answer
    /// it too when another reviewer got there first.
    private func apply(_ decision: SkillDraftDecision, askedName: String) async {
        switch decision.kind {
        case .promoted:
            let name = hxPresented(decision.promotedName) ?? hxPresented(draft?.name) ?? SkillDraftCopy.dash
            if SkillDraftRules.isPromotedEnabled(decision.skillStatus) {
                notice = .promotedEnabled(name: name, skillID: decision.skillId, canOpenSkill: skills != nil)
            } else {
                notice = .promotedHeldBack(
                    name: name,
                    findings: decision.findings.count,
                    skillID: decision.skillId,
                    canOpenSkill: skills != nil
                )
            }
        case .draftChanged:
            notice = .draftChanged(digest: decision.currentDigest)
        case .alreadyReviewed:
            notice = .alreadyReviewed(
                by: SkillDraftCopy.dashOr(decision.reviewedBy),
                at: SkillDraftCopy.stamp(decision.reviewedAt),
                reason: hxPresented(decision.rejectReason)
            )
        case .nameTaken:
            // The early exit. Preselect `rename`, clear the field rather than leaving the refused name in it,
            // and **do not re-read** (`draftDetail.tsx:511,179-184`).
            isConfirmingApprove = false
            conflict = SkillDraftConflict(name: askedName, skillID: decision.skillId)
            resolution = .rename
            newName = ""
            conflictRefusal = nil
            return
        case .rejected:
            notice = .rejectionRecorded
        case .unknown:
            // `reason` is not a contract (`SkillDraftDecisionResponse.kt:35-36`), so it is the fallback rather
            // than the copy — and a sixth outcome must never be read as one of the five.
            notice = .refused(message: hxPresented(decision.reason) ?? hx("skill.draft.decision.failed"))
        }
        conflict = nil
        conflictRefusal = nil
        newName = ""
        await load()
    }
}

/// A decided draft's one-line report, kept as a value so the banner and a test read the same thing.
public struct SkillDraftDecided: Equatable, Sendable {
    /// `nil` for a status the contract has never heard of, which the badge then prints verbatim.
    public let statusKey: String?
    public let by: String
    public let at: String
    /// Only a rejection has one; a `PENDING` draft never reaches this type.
    public let reason: String?

    public init(statusKey: String?, by: String, at: String, reason: String?) {
        self.statusKey = statusKey
        self.by = by
        self.at = at
        self.reason = reason
    }
}

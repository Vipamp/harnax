import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// One memory candidate, its four parts, and the only two writes this surface has.
///
/// The shape of this view model is dictated by what a memory decision actually returns. A refusal the reviewer
/// can act on does **not** arrive as an error: it arrives with `code: 200` and a `MemoryDraftDecision` whose
/// `outcome` says what was done, because the five answers are five different events and each needs a different
/// extra field to be actionable (`MemoryDraftDecisionResponse.kt:9-19`). Three rules are load-bearing:
///
/// - Every outcome branch re-reads (`draftDetail.tsx:176-260` calls `load()` once, after the switch). A memory
///   candidate that was refused by rule is still pending and may hold different text; one that was decided has
///   left the pending arm entirely. Nothing here splices a row by hand — there is no row to splice, and the
///   queue screen re-reads its own page.
/// - The digest travels from the text on screen (`draftDetail.tsx:338`), read off the freshly loaded detail
///   rather than mirrored into its own property, so a re-read cannot leave a stale one behind. There is no
///   second attempt field: a memory approval has no name or target to choose
///   (`MemoryDraftApproveRequest.kt:8-15`), so a raced approval re-reads and shows the new digest.
/// - An error envelope does not reload, except the one keyed on `409` (`MemoryDraftServiceImpl.kt:278`, `:285`),
///   where the store write lost the race, the candidate is still `PENDING` and re-reading it is the whole fix.
@MainActor
public final class MemoryDraftDetailViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case ready
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var draft: MemoryDraftDetail?
    @Published public private(set) var isActing = false
    /// What the last decision actually did, once it stopped being a button.
    @Published public private(set) var notice: MemoryDraftNotice?
    /// Announced once a decision has been made on this candidate, so the queue can re-read it.
    ///
    /// The queue is a *pushed* screen, and popping back to one does not re-mount it: its only automatic read is
    /// the `.task` guarded on `.loading`, which a return does not re-run. Without this announcement the reviewer
    /// decides a candidate, pops back, and the row is still sitting in the arm it left (the skill queue's defect
    /// of 2026-10-11, which this screen shares wiring with).
    @Published public private(set) var queueNeedsReload = false

    @Published public var rejectReason = ""
    @Published public private(set) var rejectRefusal: String?
    /// Settable rather than `private(set)`: `sheet(isPresented:)` owns the dismissal gesture (swipe down, tap
    /// outside), and a binding the view cannot write would leave the flag true after the sheet is gone.
    @Published public var isRejectOpen = false
    @Published public var isConfirmingApprove = false

    public let id: Int64
    private let catalog: any MemoryDraftCataloging
    /// Re-reads come from several branches and are not awaited by their callers; the last answer wins.
    private var loadGeneration = 0

    public init(id: Int64, catalog: any MemoryDraftCataloging) {
        self.id = id
        self.catalog = catalog
    }

    // MARK: - Reads

    public func load() async {
        loadGeneration += 1
        let generation = loadGeneration
        if draft == nil { phase = .loading }
        switch await catalog.detail(id: id) {
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
                // The screen keeps showing what was readable and says out loud that the re-read failed; a
                // reviewer must not approve against text they can still see with a digest nobody has checked.
                notice = .refused(message: text)
            }
        }
    }

    /// The conclusion layer this merge produced, or `nil` when there is nothing to render.
    public var mergedMarkdown: String? { hxPresented(draft?.mergedMd) }

    /// The text the owner's layer holds now. `nil` means there is none yet — `baseMd` is nullable
    /// (`MemoryDraftResponse.kt:87-88`) and the console's empty branch tests the *trimmed* value
    /// (`draftDetail.tsx:424-450`), so blank and absent are the same pane here too.
    public var baseMarkdown: String? { hxPresented(draft?.baseMd) }

    /// The digest the next approval will send, straight off the row on screen. `nil` means this screen has
    /// nothing to approve against, which is a local refusal rather than a request the server would reject
    /// (`draftDetail.tsx:294-301`, `MemoryDraftServiceImpl.kt:228`).
    public var expectedDigest: String? { hxPresented(draft?.contentDigest) }

    /// The twelve characters the pending banner shows (`draftDetail.tsx:560-565`).
    public var digestPreview: String? { expectedDigest.map { SkillDraftRules.truncated($0, digits: 12) } }

    /// Both counts as the queue counts them — UTF-16 units, the same measure `mergedChars` arrives with
    /// (`MemoryDraftServiceImpl.kt:517`), so the detail's number and the row's number cannot disagree.
    public var mergedCharCount: Int { MemoryDraftCopy.charCount(draft?.mergedMd) }
    /// Not the raw column: the console's base hint sits inside its non-blank branch (`draftDetail.tsx:424-450`),
    /// so a whitespace-only layer shows no number there at all. Counting the raw text here would promise three
    /// characters for a pane that says「no long-term memory yet」, which is the one case the two diverge.
    public var baseCharCount: Int { MemoryDraftCopy.charCount(baseMarkdown) }

    /// Whether the two decision buttons belong on screen: `PENDING` only (`draftDetail.tsx:486-499`).
    public var isDecidable: Bool { draft?.isPending ?? false }

    /// The day files the same approval writes. The count rides the confirmation, because one button decides
    /// 1 + K objects and a reviewer who does not know that will be surprised by the receipt
    /// (`draftDetail.tsx:322-331`).
    public var dailyTargetCount: Int { draft?.targets.count ?? 0 }

    /// A decided candidate's report, `nil` while it is still pending.
    public var decidedSummary: MemoryDraftDecided? {
        guard let draft, !draft.isPending else { return nil }
        return MemoryDraftDecided(
            statusKey: MemoryDraftCopy.statusKey(draft.status),
            by: MemoryDraftCopy.dashOr(draft.reviewedBy),
            at: MemoryDraftCopy.stamp(draft.reviewedAt),
            // The console prints the reason for a rejection and for nothing else
            // (`draftDetail.tsx:582`). Approval blanks the column server-side, but the row is what it is once
            // it arrives here: a stale reason must not turn an approved candidate's banner into a refusal's.
            reason: draft.status == MemoryDraftStatus.rejected.rawValue ? hxPresented(draft.rejectReason) : nil
        )
    }

    // MARK: - Approval

    /// Opens the confirmation. Nothing is sent here: an approval rewrites the text every later conversation of
    /// this agent is told.
    ///
    /// The digest is checked **before** the confirmation opens, not after (`draftDetail.tsx:294-301`): a
    /// candidate with no digest cannot be approved at all, and asking the reviewer to confirm one and then
    /// refusing them the answer to their yes is worse than refusing them the question.
    public func approve() async {
        notice = nil
        guard expectedDigest != nil else {
            notice = .refused(message: hx("memory.draft.digest.missing"))
            await load()
            return
        }
        isConfirmingApprove = true
    }

    public func cancelApproval() {
        isConfirmingApprove = false
    }

    /// The confirmed approval: exactly one key on the wire, the digest of the text this screen is showing
    /// (`MemoryDraftApproveRequest.kt:18-24`).
    public func confirmApprove() async {
        isConfirmingApprove = false
        guard !isActing else { return }
        await sendApprove()
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

    /// Validated on the **trimmed** length and submitted trimmed (`draftDetail.tsx:620-656`). The predicate is
    /// Core's, so this screen and the service cannot drift apart on where 512 sits: exactly 512 is legal because
    /// `MemoryDraftRules.isRejectReasonAdmissible` compares with `>` (`MemoryDraftServiceImpl.kt:321`), and the
    /// count is UTF-16 units because that is what Kotlin's `String.length` counts (`:322`). A blank or
    /// whitespace-only reason stops here and costs no round trip.
    public func submitRejection() async {
        guard !isActing else { return }
        let trimmed = rejectReason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard MemoryDraftRules.isRejectReasonAdmissible(rejectReason) else {
            // The two halves of the same predicate get their own sentences:「write something」and「that is too
            // long」are different things to fix (`MemoryDraftServiceImpl.kt:320`, `:322`).
            rejectRefusal = trimmed.isEmpty
                ? hx("memory.draft.reject.reasonRequired")
                : hx("memory.draft.reject.tooLong", MemoryDraftRules.maxRejectReasonLength)
            return
        }
        rejectRefusal = nil
        isActing = true
        defer { isActing = false }
        switch await catalog.reject(id: id, MemoryDraftRejectPayload(reason: trimmed)) {
        case let .success(decision):
            // Closing the sheet and emptying it are one act here: the console does both
            // (`draftDetail.tsx:351-353`), and a form that keeps the words already filed offers them
            // as a second rejection the moment the reviewer returns.
            isRejectOpen = false
            rejectReason = ""
            rejectRefusal = nil
            await apply(decision)
        case let .failure(error):
            // The sheet stays open holding what the reviewer typed, and the server's sentence goes inside it
            // rather than on the screen behind (`draftDetail.tsx:604-663` leaves the modal up on a refusal).
            rejectRefusal = ErrorMessage.text(for: error)
        }
    }

    // MARK: - Wire

    private func sendApprove() async {
        guard let digest = expectedDigest else {
            // No digest means nothing to approve against, so this refuses locally, re-reads, and never puts a
            // request on the wire.
            notice = .refused(message: hx("memory.draft.digest.missing"))
            await load()
            return
        }
        isActing = true
        defer { isActing = false }
        switch await catalog.approve(id: id, MemoryDraftApprovePayload(expectedDigest: digest)) {
        case let .success(decision):
            await apply(decision)
        case let .failure(error):
            await handleFailure(error)
        }
    }

    private func handleFailure(_ error: APIError) async {
        if case .business(code: 409, _) = error {
            // The write rolled back and the candidate is still pending, so a re-read — which also replaces the
            // digest this approval just failed to send — is the whole remedy
            // (`MemoryDraftServiceImpl.kt:278`, `:285`).
            notice = .refused(message: ErrorMessage.text(for: error))
            await load()
            return
        }
        // Anything else keeps the form exactly as it is: the reviewer has a server sentence to read and a
        // reason they may want to change and retry. Where it lands depends on what is on top — the reject sheet
        // covers the banner, so writing to the banner alone would report a refusal the reviewer never sees.
        let text = ErrorMessage.text(for: error)
        if isRejectOpen {
            rejectRefusal = text
        } else {
            notice = .refused(message: text)
        }
    }

    /// The five contract outcomes plus a fall-through, applied to both decision calls: a reject can answer
    /// `REJECTED`, and an approve can answer it too when another reviewer got there first.
    private func apply(_ decision: MemoryDraftDecision) async {
        switch decision.kind {
        case .approved:
            // The receipt's numbers are the decision's own fields, not part of the outcome token.
            notice = Self.approvalNotice(from: decision)
        case .rejected:
            notice = .rejectionRecorded
        case .draftChanged:
            notice = .draftChanged(digest: decision.currentDigest)
        case .alreadyReviewed:
            notice = .alreadyReviewed(
                by: MemoryDraftCopy.dashOr(decision.reviewedBy),
                at: MemoryDraftCopy.stamp(decision.reviewedAt),
                reason: hxPresented(decision.rejectReason)
            )
        case .staleBase:
            notice = .staleBase(
                currentBaseVersion: decision.currentBaseVersion,
                staleTarget: hxPresented(decision.staleTarget)
            )
        case let .unknown(raw):
            // `reason` is server-composed prose and explicitly not a contract
            // (`MemoryDraftDecisionResponse.kt:44-45`), so a sixth outcome never borrows one of the five
            // sentences: it says the decision was refused and carries the token verbatim.
            notice = .unrecognised(raw: raw)
        }
        // Every outcome re-reads the row, so every one of them owes the queue the same re-read: a moved row is
        // gone from the arm on screen, and one that stayed still changed its bylines.
        queueNeedsReload = true
        await load()
    }

    /// The approval receipt: the version the owner's layer reached, how many conversation files were cleared,
    /// kept for the next candidate or already gone, and how many of the owner's own days were folded in
    /// (`MemoryDraftDecisionResponse.kt:26-42`).
    static func approvalNotice(from decision: MemoryDraftDecision) -> MemoryDraftNotice {
        .approved(
            longTermVersion: decision.longTermVersion,
            cleared: decision.clearedSources,
            kept: decision.keptSources,
            absent: decision.absentSources,
            dailyApplied: decision.dailyTargetsApplied
        )
    }
}

/// A decided candidate's one-line report, kept as a value so the banner and a test read the same thing.
public struct MemoryDraftDecided: Equatable, Sendable {
    /// `nil` for a status the contract has never heard of, which the badge then prints verbatim.
    public let statusKey: String?
    public let by: String
    public let at: String
    /// Only a rejection has one; a `PENDING` candidate never reaches this type.
    public let reason: String?

    public init(statusKey: String?, by: String, at: String, reason: String?) {
        self.statusKey = statusKey
        self.by = by
        self.at = at
        self.reason = reason
    }
}

/// What a memory decision ended in, reduced to the one thing each outcome owes the screen.
///
/// Carries values rather than finished sentences: `APPROVED` needs the version and the three source counts,
/// `DRAFT_CHANGED` needs the digest the next attempt has to send, `STALE_BASE` needs the version the layer is at
/// now and the name of the object that moved. That keeps the six rows of the decision table assertable without a
/// view, and it keeps every sentence keyed on `kind` rather than on the server's `reason`.
public enum MemoryDraftNotice: Equatable, Sendable {
    /// `APPROVED`: the long-term layer holds the candidate. `longTermVersion` is `nil` only if the server
    /// answered without it (`MemoryDraftDecisionResponse.kt:26-27` makes it nullable), and the receipt then
    /// prints the console's `-`.
    case approved(longTermVersion: Int64?, cleared: Int, kept: Int, absent: Int, dailyApplied: Int)
    /// `REJECTED` — the success the reject button asks for: nothing written, both layers as they were.
    case rejectionRecorded
    /// `DRAFT_CHANGED`: the conversation merged again after this screen loaded the candidate. `digest` is the
    /// new one, offered for copying rather than retyping.
    case draftChanged(digest: String?)
    /// `ALREADY_REVIEWED`: somebody else closed it first. Both halves default to `-`.
    case alreadyReviewed(by: String, at: String, reason: String?)
    /// `STALE_BASE`: an object this candidate writes moved since the merge read it, so nothing was written.
    case staleBase(currentBaseVersion: Int64?, staleTarget: String?)
    /// An `outcome` the contract does not have. The token is kept, and nothing about it is interpreted.
    case unrecognised(raw: String)
    /// A refusal the screen cannot branch on, already rendered: an error envelope, or a local guard.
    case refused(message: String)

    /// The colour of the banner that reports it.
    ///
    /// `staleBase` is danger rather than warning because the decision can no longer be made on this row at all
    /// — the console answers it with `Modal.error` (`draftDetail.tsx:212-247`).
    public var tone: MemoryDraftNoticeTone {
        switch self {
        case .approved, .rejectionRecorded: .success
        case .draftChanged: .warning
        case .alreadyReviewed: .info
        case .staleBase, .unrecognised, .refused: .error
        }
    }

    /// The version an approval landed at, in the console's own dash-for-absent form (`draftDetail.tsx:112`).
    public var longTermVersionText: String? {
        if case let .approved(version, _, _, _, _) = self {
            return version.map { String($0) } ?? MemoryDraftCopy.dash
        }
        return nil
    }
}

/// The tone of a finished decision, so the banner can be coloured without the view re-deriving which outcome
/// means what.
public enum MemoryDraftNoticeTone: Equatable, Sendable {
    case success
    case warning
    case info
    case error

    var slot: PaletteSlot {
        switch self {
        case .success: .success
        case .warning: .warning
        case .info: .brand
        case .error: .danger
        }
    }
}

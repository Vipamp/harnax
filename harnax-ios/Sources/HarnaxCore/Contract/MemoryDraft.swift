import Foundation

/// One row of the memory merge queue: `GET /api/admin/memory-drafts`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftResponse.kt:15-54`.
///
/// Neither text is on a row on purpose (`:8-13`): a candidate is a whole rewrite of one file, so its body is
/// the thing to read rather than a cell to scan. What tells two rows of one agent apart is `mergedChars`,
/// `sourceCount` and `targetCount` — how much material the merge took and how many of the owner's own daily
/// files the same approval would rewrite.
public struct MemoryDraftRow: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64
    /// Agent whose long-term layer the merge rewrites — the runtime name, as in `MemoryAgentRow`.
    public let agentName: String
    /// Conversation whose own layer was merged.
    public let sessionId: String
    /// `PENDING` / `APPROVED` / `REJECTED`
    /// (`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/MemoryDraft.kt:42-48`), the only three the
    /// queue's filter accepts.
    public let status: String
    /// Store version of the owner's layer the merge read. `0` means they had none yet (`:28-29`).
    public let baseVersion: Int64
    public let mergedChars: Int
    public let sourceCount: Int
    public let targetCount: Int
    public let createTime: String?
    /// Later than `createTime` means the merge rewrote the candidate since it was first proposed (`:43-44`).
    public let updateTime: String?
    public let reviewedBy: String?
    public let reviewedAt: String?
    public let rejectReason: String?

    public var isPending: Bool { status == MemoryDraftStatus.pending.rawValue }

    /// `updateTime` later than `createTime` means the merge rewrote the candidate after it was first offered,
    /// which is exactly what the digest check at approval exists to catch.
    public var patched: Bool {
        SkillDraftRules.isPatched(createTime: createTime, updateTime: updateTime)
    }
}

/// The three arms the queue can be filtered by. No「show me everything」: the console's filter offers these
/// three and the screen starts on `PENDING` (`harnax-webui/src/pages/memory/drafts.tsx`), which is also the
/// server's own default (`MemoryDraftController.kt:74`). An unrecognised value is refused outright rather than
/// answering an empty queue
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt:170-175`).
public enum MemoryDraftStatus: String, CaseIterable, Identifiable, Sendable {
    case pending = "PENDING"
    case approved = "APPROVED"
    case rejected = "REJECTED"

    public var id: String { rawValue }
    public var titleKey: String { "memory.draft.status.\(rawValue)" }
}

/// The candidate with both texts, the files it merged out of and the days it rewrites:
/// `GET /api/admin/memory-drafts/{id}` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftResponse.kt:71-116`).
///
/// Four parts decide the click, and a screen that shows only the first one hides what the approval touches:
/// `mergedMd` is the conclusion layer it produces, `baseMd` the owner's text it read, `sources` the
/// conversation files it drained (an approval clears each only while it still holds the bytes recorded there),
/// and `targets` the owner's own daily ledgers the same write replaces.
public struct MemoryDraftDetail: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64
    public let agentName: String
    public let sessionId: String
    public let status: String
    /// The complete `MEMORY.md` the merge produced.
    public let mergedMd: String
    /// The owner's text it was merged against; nil when they had none yet (`:87-88`).
    public let baseMd: String?
    /// Version that text was read at. `0` means the object did not exist.
    public let baseVersion: Int64
    public let sources: [MemoryDraftSource]
    public let targets: [MemoryDraftTarget]
    /// SHA-256 over agent, conversation, both texts, the base version, the sources and the targets as stored;
    /// an approval sends it back (`:99-100`). Without that round trip「approve the text I read」would be a
    /// claim about two documents rather than a check.
    public let contentDigest: String
    public let createTime: String?
    public let updateTime: String?
    public let reviewedBy: String?
    public let reviewedAt: String?
    public let rejectReason: String?

    public var isPending: Bool { status == MemoryDraftStatus.pending.rawValue }
    public var hasBaseText: Bool { baseMd?.isEmpty == false }
}

/// One conversation-layer file the merge read (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftSource.kt:19-25`).
/// `path` resolves the object an approval clears, `content` is the precondition that clear has to find.
public struct MemoryDraftSource: Decodable, Equatable, Sendable {
    public let path: String?
    public let content: String?

    public var id: String { path ?? content?.hashValue.description ?? "" }
}

/// One day of the owner's own ledger this candidate rewrites (`MemoryDraftSource.kt:40-53`). Carries its own
/// version precondition, so a day another conversation wrote in the meantime is refused **by name** rather
/// than overwritten — which is why `MemoryDraftOutcome.staleBase` has a `staleTarget` to show.
public struct MemoryDraftTarget: Decodable, Equatable, Sendable {
    public let path: String?
    /// Version that day's file was read at; `0` when it did not exist.
    public let expectedVersion: Int64
    /// The text before the merge, empty when the file did not exist.
    public let baseText: String?
    /// The complete text the merge produced for that day.
    public let mergedText: String?

    public var id: String { path ?? "" }
    /// The day's name as a reader recognises it: `memory/2026-10-08.md` → `2026-10-08`.
    public var dayLabel: String {
        guard let path else { return "" }
        let name = path.split(separator: "/").last.map(String.init) ?? path
        return String(name.split(separator: ".").first ?? Substring(name))
    }
}

/// What a memory decision actually did: `POST /api/admin/memory-drafts/{id}/approve` and `/reject`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftDecisionResponse.kt:22-64`).
///
/// A refusal the screen must respond to travels **in the payload with a `code: 200` envelope**, not in the
/// envelope code — the same deliberate break the skill review makes, and each refusal needs its own extra
/// field to be actionable: `DRAFT_CHANGED` carries the new digest, `ALREADY_REVIEWED` carries who decided and
/// why, `STALE_BASE` carries the version the layer is at now and which day moved (`:9-19`).
///
/// The one refusal keyed on an envelope **code** is `409`: an approval whose store write lost the race, where
/// the candidate is still `PENDING` and re-reading it is the fix (`MemoryDraftController.kt:37-40`).
public struct MemoryDraftDecision: Decodable, Equatable, Sendable {
    public let outcome: String
    /// Version the owner's long-term layer is at after an approval.
    public let longTermVersion: Int64?
    public let clearedSources: Int
    /// Files left alone because their bytes moved after the merge read them; they re-enter the next candidate.
    public let keptSources: Int
    public let absentSources: Int
    /// Daily files of the owner's own ledger this approval wrote or found already holding its text.
    public let dailyTargetsApplied: Int
    /// A sentence the server composed; explicitly not a contract (`:44-45`), so copy keys off `kind`.
    public let reason: String?
    public let currentDigest: String?
    public let currentBaseVersion: Int64?
    public let staleTarget: String?
    public let reviewedBy: String?
    public let reviewedAt: String?
    public let rejectReason: String?

    /// Unknown raw values keep their text rather than collapsing into a known case: a sixth outcome has to read
    /// as「the decision was refused」, not as one of the four the screen knows how to undo.
    public var kind: MemoryDraftOutcome {
        switch outcome {
        case MemoryDraftOutcome.approvedName: .approved
        case MemoryDraftOutcome.rejectedName: .rejected
        case MemoryDraftOutcome.draftChangedName: .draftChanged
        case MemoryDraftOutcome.alreadyReviewedName: .alreadyReviewed
        case MemoryDraftOutcome.staleBaseName: .staleBase
        default: .unknown(raw: outcome)
        }
    }
}

/// The four contract outcomes, plus a fall-through case for whatever the server invents next.
public enum MemoryDraftOutcome: Equatable, Sendable {
    /// The long-term layer now holds the candidate, and the sources it merged out of are cleared.
    case approved
    /// The candidate is closed with a reason; both layers untouched.
    case rejected
    /// The conversation merged again after this screen loaded the candidate; nothing was written.
    case draftChanged
    /// The owner, or a second tab of theirs, decided this one first.
    case alreadyReviewed
    /// One object this candidate writes moved since the merge read it, so the whole decision is refused.
    case staleBase
    case unknown(raw: String)

    static let approvedName = "APPROVED"
    static let rejectedName = "REJECTED"
    static let draftChangedName = "DRAFT_CHANGED"
    static let alreadyReviewedName = "ALREADY_REVIEWED"
    static let staleBaseName = "STALE_BASE"
}

/// Body of `POST /api/admin/memory-drafts/{id}/approve`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftApproveRequest.kt:18-25`).
///
/// One key, and it is required: unlike a skill approval there is no name or target to choose, because the
/// candidate already says whose layer it rewrites and which conversation it came out of. What an owner decides
/// is the text, so the digest of the text they read is the whole request.
public struct MemoryDraftApprovePayload: Encodable, Equatable, Sendable {
    public let expectedDigest: String

    public init(expectedDigest: String) { self.expectedDigest = expectedDigest }
}

/// Body of `POST /api/admin/memory-drafts/{id}/reject`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftRejectRequest.kt:14-21`).
///
/// The reason is load-bearing in a way it is not for a skill: a rejected memory candidate leaves the
/// conversation's layer untouched, so the next window proposes the same material again and the owner reads
/// this sentence in the queue when it comes back (`:8-12`).
public struct MemoryDraftRejectPayload: Encodable, Equatable, Sendable {
    public let reason: String

    public init(reason: String) { self.reason = reason }
}

/// The rules a review screen has to share with the service rather than re-derive.
public enum MemoryDraftRules {
    /// Width of `memory_draft.reject_reason` (`MemoryDraftServiceImpl.kt:591-592`).
    public static let maxRejectReasonLength = 512

    /// Counted in **UTF-16 code units**, because that is what the server counts (`String.length` in Kotlin)
    /// and what the console counts. Same rule `SkillDraftRules.wireLength` documents.
    public static func wireLength(_ text: String) -> Int { text.utf16.count }

    /// Trim, then measure: the service does (`MemoryDraftServiceImpl.kt:319`), and a reason that is only
    /// whitespace is the「a rejection needs a reason」refusal at `:320`, not a stored blank. Exactly 512 is
    /// legal — the service compares with `>` (`:321`).
    public static func isRejectReasonAdmissible(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let length = wireLength(trimmed)
        return length > 0 && length <= maxRejectReasonLength
    }
}

import Foundation

/// One row of the review queue: `GET /api/admin/skill-drafts`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillDraftResponse.kt:16-55`.
/// A queue row carries neither the body nor the support files — those exist only on the detail read
/// (`SkillDraftResponse.kt:8-14`), which is why nothing here can settle a review decision.
///
/// `upstreamFindingCount` is non-optional: the DTO declares it with a `0` default
/// (`SkillDraftResponse.kt:33`), so it is in the payload even when the sandbox reported nothing, and an
/// absent key is a contract break rather than something to decode around.
public struct SkillDraftRow: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    /// `PENDING` / `APPROVED` / `REJECTED`. `EXPIRED` is written on the column's documentation but by no code,
    /// and filtering by it is refused outright (`SkillDraftServiceImpl.kt:578`), so it is not one of the
    /// statuses this app can ask for.
    public let status: String?
    /// Upstream `SkillSecurityScanner.Verdict`, and only `SAFE` / `CAUTION` / `DANGEROUS` are echoed back
    /// (`SkillDraftServiceImpl.kt:569`). Kept as text: an unrecognised verdict still has to be readable.
    public let scanVerdict: String?
    public let upstreamFindingCount: Int
    /// The conversation the agent proposed this skill in. Display and copy only — the console never links it
    /// (`harnax-webui/src/pages/skill/draftDetail.tsx:709-713`), and the queue cannot be filtered by it.
    public let sourceSessionId: String?
    public let agentId: Int64?
    public let createTime: String?
    public let updateTime: String?
    public let reviewedBy: String?
    public let reviewedAt: String?
    public let rejectReason: String?

    public var title: String? { hxPresented(name) }
    public var detail: String? { hxPresented(description) }
    public var isPending: Bool { status == SkillDraftStatus.pending.rawValue }

    /// `updateTime` later than `createTime` means the agent patched the proposal after it was first offered,
    /// which is exactly what the digest check at approval exists to catch.
    public var isPatched: Bool {
        SkillDraftRules.isPatched(createTime: createTime, updateTime: updateTime)
    }
}

/// The three statuses the queue can be filtered by. There is no「show me everything」arm: the console's own
/// filter has these three only (`harnax-webui/src/pages/skill/drafts.tsx:193-205`), and the screen starts on
/// `PENDING`, which is the list a reviewer has a reason to ask for.
public enum SkillDraftStatus: String, CaseIterable, Identifiable, Sendable {
    case pending = "PENDING"
    case approved = "APPROVED"
    case rejected = "REJECTED"

    public var id: String { rawValue }
    public var titleKey: String { "skill.draft.status.\(rawValue)" }
}

/// The two answers to a name clash, spelled as the service matches them
/// (`SkillDraftServiceImpl.kt:583-585`). These raw values go on the wire as `conflictResolution`.
///
/// `allCases` is the dialog's display order: `rename` first and `replace` second, preselected
/// (`harnax-webui/src/pages/skill/draftDetail.tsx:511-520`) — both overwrite or duplicate something somebody
/// else published, and the console decided the reader should start on the arm that leaves the existing row alone.
public enum SkillDraftResolution: String, CaseIterable, Identifiable, Sendable {
    case rename
    case replace

    public var id: String { rawValue }
    public var titleKey: String {
        switch self {
        case .rename: return "skill.draft.conflict.rename"
        case .replace: return "skill.draft.conflict.replace"
        }
    }
}

/// The full content of one proposal, with the trail of how it got here:
/// `GET /api/admin/skill-drafts/{id}` (`SkillDraftResponse.kt:71-128`).
///
/// Two traps on this shape:
/// - `resources` is a real JSON object here, unlike `SkillItem.resources`, which is a *string* of one
///   (`:87-88` versus `harnax-ios/Sources/HarnaxCore/Contract/SkillItem.swift:26-28`).
/// - `localFindings` and `scanFindings` decide different things. The first is harnax's own rules run over the
///   stored bytes at read time, and non-empty means an approval stores the skill *disabled* (`:99-100`); the
///   second is what the sandbox reported when the proposal arrived and is display only (`:96-97`).
public struct SkillDraftDetail: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    public let status: String?
    public let skillmd: String
    public let resources: [String: String]
    public let scripts: [SkillDraftScript]
    public let scanVerdict: String?
    public let scanFindings: [String]
    public let localFindings: [String]
    /// SHA-256 over name, description, body and files as stored. An approval sends it back, which is the only
    /// thing that makes「approve what I am looking at」a check rather than a claim (`:60-62`).
    public let contentDigest: String
    public let sourceSessionId: String?
    public let agentId: Int64?
    public let createTime: String?
    public let updateTime: String?
    public let reviewedBy: String?
    public let reviewedAt: String?
    public let rejectReason: String?
    /// Proposals and decisions, newest first (`:126-127`).
    public let history: [SkillDraftHistoryItem]

    public var title: String? { hxPresented(name) }
    public var detail: String? { hxPresented(description) }
    public var isPending: Bool { status == SkillDraftStatus.pending.rawValue }
    public var hasLocalFindings: Bool { !localFindings.isEmpty }
    public var resourceFiles: [(path: String, content: String)] {
        resources.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }
}

/// `SkillDraftCodec.ScriptPreview` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillDraftCodec.kt:108-109`).
/// Computed by harnax over the stored bytes, not reported by the sandbox — which is the sentence the scripts
/// tab has to say (`harnax-webui/src/pages/skill/draftDetail.tsx:559-605`).
public struct SkillDraftScript: Decodable, Identifiable, Equatable, Sendable {
    public let relPath: String
    public let headPreview: String
    public let totalLines: Int
    public let sha256: String

    public var id: String { relPath }
}

/// One recorded state change of a draft (`SkillDraftResponse.kt:134-147`). The same shape serves a skill's
/// trail, so `action` names either a draft decision or a skill change.
public struct SkillDraftHistoryItem: Decodable, Equatable, Sendable {
    /// `PROPOSE` / `APPROVE` / `REJECT` for a draft.
    public let action: String
    /// A username, or the sentinel `agent` / `system`.
    public let actor: String
    public let detail: String?
    public let createTime: String?

    public var id: String { "\(action)-\(createTime ?? "")" }
}

/// What a review action actually did (`SkillDraftDecisionResponse.kt:19-49`).
///
/// A refusal a screen can act on travels in the payload with `code: 200`, not in the envelope code, and the
/// three kinds need different follow-ups: `DRAFT_CHANGED` carries the new digest, `ALREADY_REVIEWED` carries
/// who decided and why, `NAME_TAKEN` opens a replace-or-rename choice. `reason` is a sentence the server
/// composed and is explicitly not a contract (`:35-36`), so copy keys off `kind`.
public struct SkillDraftDecision: Decodable, Equatable, Sendable {
    public let outcome: String
    public let skillId: Int64?
    /// `1` enabled, `0` held back by the content scan. Anything but exactly 1 is the held-back branch
    /// (`harnax-webui/src/pages/skill/draftDetail.tsx:99-127`).
    public let skillStatus: Int?
    public let promotedName: String?
    public let findings: [String]
    public let reason: String?
    public let currentDigest: String?
    public let reviewedBy: String?
    public let reviewedAt: String?
    public let rejectReason: String?

    /// Unknown raw values keep their text instead of collapsing into a known case: a sixth outcome from the
    /// server must read as「the decision was refused」, not as one of the five the screen knows how to undo.
    public var kind: SkillDraftOutcome {
        switch outcome {
        case SkillDraftOutcome.promotedName: .promoted
        case SkillDraftOutcome.rejectedName: .rejected
        case SkillDraftOutcome.draftChangedName: .draftChanged
        case SkillDraftOutcome.alreadyReviewedName: .alreadyReviewed
        case SkillDraftOutcome.nameTakenName: .nameTaken
        default: .unknown(raw: outcome)
        }
    }
}

/// The five contract outcomes, plus a fall-through case for whatever the server invents next.
public enum SkillDraftOutcome: Equatable, Sendable {
    /// The draft became a skill row — check `skillStatus` before calling it live.
    case promoted
    /// The proposal is closed with a reason; nothing was written.
    case rejected
    /// The agent patched the draft after this screen loaded it.
    case draftChanged
    /// Another reviewer decided this one first.
    case alreadyReviewed
    /// The landing repository already holds that name and no resolution was sent.
    case nameTaken
    case unknown(raw: String)

    static let promotedName = "PROMOTED"
    static let rejectedName = "REJECTED"
    static let draftChangedName = "DRAFT_CHANGED"
    static let alreadyReviewedName = "ALREADY_REVIEWED"
    static let nameTakenName = "NAME_TAKEN"
}

/// Body of `POST /api/admin/skill-drafts/{id}/approve` (`SkillDraftApproveRequest.kt:23-36`).
///
/// Three keys, and the first attempt sends one of them: `conflictResolution` has **no default**, because both
/// answers overwrite or duplicate something somebody else published, so the service refuses to guess
/// (`:12-20`). It only goes out after a `NAME_TAKEN`.
public struct SkillDraftApprovePayload: Encodable, Equatable, Sendable {
    public let expectedDigest: String
    /// `replace` or `rename` — the raw value of a `SkillDraftResolution`.
    public var conflictResolution: String?
    public var newName: String?

    public init(expectedDigest: String, conflictResolution: String? = nil, newName: String? = nil) {
        self.expectedDigest = expectedDigest
        self.conflictResolution = conflictResolution
        self.newName = newName
    }

    public func encode(to encoder: Encoder) throws {
        var box = encoder.container(keyedBy: CodingKeys.self)
        try box.encode(expectedDigest, forKey: .expectedDigest)
        try box.encodeIfPresent(conflictResolution, forKey: .conflictResolution)
        try box.encodeIfPresent(newName, forKey: .newName)
    }

    enum CodingKeys: String, CodingKey {
        case expectedDigest, conflictResolution, newName
    }
}

/// Body of `POST /api/admin/skill-drafts/{id}/reject` (`SkillDraftRejectRequest.kt:13-20`).
///
/// `reason` is required server-side and a blank one is refused (`SkillDraftServiceImpl.kt:370-371`), so the
/// screen validates before sending rather than paying for a round trip that can only say「write something」.
public struct SkillDraftRejectPayload: Encodable, Equatable, Sendable {
    public let reason: String

    public init(reason: String) { self.reason = reason }
}

/// The rules a reviewer's screen has to share with the service rather than re-derive.
public enum SkillDraftRules {
    /// Width of `skill_draft.reject_reason` (`SkillDraftServiceImpl.kt:587-588`).
    public static let maxRejectReasonLength = 512

    /// `SkillInstaller.MAX_SKILL_NAME_LENGTH` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillInstaller.kt:330`):
    /// a rename has to fit the column the promoted row grows into.
    public static let maxSkillNameLength = 100

    /// Both ceilings are counted in **UTF-16 code units**, because that is what the server counts
    /// (`String.length` in Kotlin) and what the console counts (JS `.length`). Swift's `count` is a grapheme
    /// cluster count, and one emoji is 2 there but 1 here — measuring with `count` would wave through input
    /// the service then refuses.
    public static func wireLength(_ text: String) -> Int { text.utf16.count }

    /// `trim` first, then measure: the server does (`SkillDraftServiceImpl.kt:370`), and a reason that is only
    /// whitespace is the「a rejection needs a reason」refusal, not a stored blank.
    public static func trimmedWireLength(_ text: String) -> Int { wireLength(text.trimmingCharacters(in: .whitespacesAndNewlines)) }

    /// A rejection reason is admissible when it is not blank and its trimmed length is at most 512. Exactly
    /// 512 is legal: the service compares with `>` (`SkillDraftServiceImpl.kt:372`).
    public static func isRejectReasonAdmissible(_ raw: String) -> Bool {
        let length = trimmedWireLength(raw)
        return length > 0 && length <= maxRejectReasonLength
    }

    /// A rename target is admissible when it is not blank and its trimmed length is at most 100
    /// (`SkillDraftServiceImpl.kt:415-424`). Validated untrimmed, submitted trimmed — the console's order.
    public static func isRenameAdmissible(_ raw: String) -> Bool {
        let length = trimmedWireLength(raw)
        return length > 0 && length <= maxSkillNameLength
    }

    /// Strictly after. Two equal stamps mean nothing moved, and a missing `createTime` means nothing can be
    /// said at all (`harnax-webui/src/pages/skill/drafts.tsx:102`).
    public static func isPatched(createTime: String?, updateTime: String?) -> Bool {
        guard let created = hxServerDateTime(createTime), let updated = hxServerDateTime(updateTime) else {
            return false
        }
        return updated > created
    }

    /// Digest display forms: 12 characters in the pending banner, 16 for a replacement digest and for a script
    /// hash (`draftDetail.tsx:399` / `:170` / `:582`). Truncation is by character, and these are hex strings,
    /// so no grapheme question arises.
    public static func truncated(_ value: String, digits: Int) -> String {
        guard value.count > digits else { return value }
        return String(value.prefix(digits)) + "…"
    }

    /// The one origin value that marks a skill as agent-written: `Skill.ORIGIN_AGENT_PROMOTED`
    /// (`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt:13`). Everything else is a human's
    /// skill (`harnax-webui/src/pages/skill/components/SkillOriginTag.tsx:5-6`).
    public static let agentPromotedOrigin = "agent_promoted"

    /// `PROMOTED` says a row was written; `skillStatus == 1` says it is live. The test is on exactly 1 — 0 and
    /// absent both mean the content scan held the skill back.
    public static func isPromotedEnabled(_ skillStatus: Int?) -> Bool { skillStatus == 1 }
}

import Foundation

/// The reviewer's half of the self-evolution queue: list, one draft, the two decisions.
///
/// Kept out of `SkillCataloging`, which is the *published* skill surface — a proposal is not a skill yet, and
/// every read here answers about a row that may never become one
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillDraftController.kt:28-40`).
/// Backend: the same controller; console: `harnax-webui/src/services/ant-design-pro/skillDraft.ts`.
public protocol SkillDraftCataloging: Sendable {
    /// One page of the queue. `status` is always sent rather than left to the server's default — the console
    /// does the same, and it is the only way a caller can ask for decided rows
    /// (`skillDraft.ts:5-8`, `SkillDraftController.kt:70-74`). `name` is a partial match, trimmed, and an
    /// empty one is left off the request entirely (`harnax-webui/src/pages/skill/drafts.tsx:38,206-215`).
    /// `sessionId` scopes the queue to one conversation and is the parameter name the route binds
    /// (`SkillDraftController.kt`'s `sessionId`); the reviewer's queue never sends it, and only the session's own
    /// skill panel does — without it that screen would list the tenant's nominations rather than this
    /// conversation's. A caller with no scope passes `nil`, which the client leaves off the request entirely.
    func page(
        status: SkillDraftStatus,
        name: String?,
        sessionId: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillDraftRow>, APIError>

    /// Full content, both scans, the per-script hashes and the digest an approval has to send back.
    func detail(id: Int64) async -> Result<SkillDraftDetail, APIError>

    /// Refusals the screen can act on come back inside a successful envelope as `outcome`, so a `.success`
    /// here does **not** mean the draft was promoted (`SkillDraftDecisionResponse.kt:9-16`).
    /// The one refusal keyed on an envelope **code** rather than on `data.outcome` is `409` — the race where
    /// another publisher won the name (`SkillDraftController.kt:113-117` answers `ResultVo.error(409, …)`,
    /// which is HTTP 200 with the code inside the envelope), and where the draft is still pending and
    /// re-reading is the fix.
    func approve(id: Int64, _ payload: SkillDraftApprovePayload) async -> Result<SkillDraftDecision, APIError>

    /// A rejection with no reason is refused by the service (`SkillDraftServiceImpl.kt:370-371`), so callers
    /// validate first rather than paying for a round trip that can only say「write something」.
    func reject(id: Int64, _ payload: SkillDraftRejectPayload) async -> Result<SkillDraftDecision, APIError>
}

public extension SkillDraftCataloging {
    /// The badge the chat tab's entry row carries. One row of the pending queue is enough to read `total`, and
    /// this read sends no `sessionId` on purpose — the badge counts the tenant, not the conversation the row
    /// sits in. The screen that wants the conversation's own nominations is the session skill panel, and it goes
    /// through `SessionSkillReading`, which does scope by session.
    func pendingCount() async -> Int? {
        guard let page = try? await page(status: .pending, name: nil, sessionId: nil, num: 1, size: 1).get() else {
            return nil
        }
        return page.total
    }
}

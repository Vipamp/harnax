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
    func page(
        status: SkillDraftStatus,
        name: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillDraftRow>, APIError>

    /// Full content, both scans, the per-script hashes and the digest an approval has to send back.
    func detail(id: Int64) async -> Result<SkillDraftDetail, APIError>

    /// Refusals the screen can act on come back inside a successful envelope as `outcome`, so a `.success`
    /// here does **not** mean the draft was promoted (`SkillDraftDecisionResponse.kt:9-16`). The one
    /// non-2xx branch worth naming is `code: 409` — the race where another publisher won the name
    /// (`SkillDraftController.kt:115-117`), where the draft is still pending and re-reading is the fix.
    func approve(id: Int64, _ payload: SkillDraftApprovePayload) async -> Result<SkillDraftDecision, APIError>

    /// A rejection with no reason is refused by the service (`SkillDraftServiceImpl.kt:370-371`), so callers
    /// validate first rather than paying for a round trip that can only say「write something」.
    func reject(id: Int64, _ payload: SkillDraftRejectPayload) async -> Result<SkillDraftDecision, APIError>
}

public extension SkillDraftCataloging {
    /// The badge the chat tab's entry row carries. One row of the pending queue is enough to read `total`,
    /// and the queue has no per-session filter at all (`SkillDraftController.kt:55-68`) — which is why the
    /// badge counts the tenant, not the conversation.
    func pendingCount() async -> Int? {
        guard let page = try? await page(status: .pending, name: nil, num: 1, size: 1).get() else {
            return nil
        }
        return page.total
    }
}

import Foundation

/// The owner's half of the memory merge queue: their candidates, one candidate, the two decisions.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryDraftController.kt:42-146`.
/// It sits next to `/api/admin/memory` rather than under it because the two read different stores: that one
/// shows what the memory bucket holds now, this one shows rows that may never be written anywhere — a
/// candidate is not a memory yet (`:27-33`).
///
/// Nothing here takes a user id. Every read and write is scoped to the person on the token, so the queue one
/// account sees is their own recollections and no other account's.
public protocol MemoryDraftCataloging: Sendable {
    /// One page of the caller's own candidates, newest touched first.
    ///
    /// `status` always goes out rather than being left to the server's default, because it is the only way to
    /// ask for a decided arm (`MemoryDraftController.kt:65-68`, default `PENDING` at `:74`). `agentName` is a
    /// partial match, `sessionId` an exact one (`:69-70`); both are dropped from the request when blank.
    func page(
        status: MemoryDraftStatus,
        agentName: String?,
        sessionId: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<MemoryDraftRow>, APIError>

    /// Both texts, the source files it merged out of, the daily targets it rewrites, and the digest an
    /// approval has to send back.
    func detail(id: Int64) async -> Result<MemoryDraftDetail, APIError>

    /// Approve a merge and write it into the long-term layer, version-checked against the store; the
    /// conversation files it merged out of are cleared only after the write lands
    /// (`MemoryDraftController.kt:106-122`).
    ///
    /// A `.success` here does **not** mean the layer changed: four of the refusals ride in a `code: 200`
    /// envelope as `outcome`, and `MemoryDraftDecision.kind` is what the screen branches on. The one refusal
    /// keyed on an envelope code is `409` — the write lost its race, the candidate is still pending, and
    /// re-reading it is the fix.
    func approve(id: Int64, _ payload: MemoryDraftApprovePayload) async -> Result<MemoryDraftDecision, APIError>

    /// Close a candidate with a reason, leaving both layers alone; the conversation proposes again next window
    /// (`MemoryDraftController.kt:124-140`). A blank reason is refused by the service
    /// (`MemoryDraftServiceImpl.kt:319-323`), so callers validate first with `MemoryDraftRules` rather than
    /// paying for a round trip that can only say「write something」.
    func reject(id: Int64, _ payload: MemoryDraftRejectPayload) async -> Result<MemoryDraftDecision, APIError>
}

public extension MemoryDraftCataloging {
    /// The badge the governance hub puts on the queue row. One row of the pending queue is enough to read
    /// `total`, the same trick `SkillDraftCataloging.pendingCount` uses.
    func pendingCount() async -> Int? {
        guard let page = try? await page(status: .pending, agentName: nil, sessionId: nil, num: 1, size: 1).get()
        else {
            return nil
        }
        return page.total
    }
}

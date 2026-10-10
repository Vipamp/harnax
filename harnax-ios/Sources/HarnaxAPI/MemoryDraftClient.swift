import Foundation
import HarnaxCore

/// The memory review half of the admin surface (`MemoryDraftEndpoint.swift`).
///
/// Nothing on `approve` / `reject` is a `.success` for the merge having landed: the decision comes back as a
/// `MemoryDraftDecision` whose `outcome` says what was actually done, and four of the refusals ride in a
/// `code: 200` envelope on purpose (`MemoryDraftDecisionResponse.kt:9-19`). The one refusal keyed on an
/// envelope code is `409`, where the store write lost its race and the candidate is still pending.
extension AdminClient: MemoryDraftCataloging {
    public func page(
        status: MemoryDraftStatus,
        agentName: String?,
        sessionId: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<MemoryDraftRow>, APIError> {
        await client.send(
            Page<MemoryDraftRow>.self,
            MemoryDraftEndpoint.page(status: status, agentName: agentName, sessionId: sessionId, num: num, size: size)
        )
    }

    public func detail(id: Int64) async -> Result<MemoryDraftDetail, APIError> {
        await client.send(MemoryDraftDetail.self, MemoryDraftEndpoint.detail(id: id))
    }

    public func approve(id: Int64, _ payload: MemoryDraftApprovePayload) async -> Result<MemoryDraftDecision, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try MemoryDraftEndpoint.approve(id: id, payload)
        } catch {
            return .failure(.decoding)
        }
        return await client.send(MemoryDraftDecision.self, endpoint)
    }

    public func reject(id: Int64, _ payload: MemoryDraftRejectPayload) async -> Result<MemoryDraftDecision, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try MemoryDraftEndpoint.reject(id: id, payload)
        } catch {
            return .failure(.decoding)
        }
        return await client.send(MemoryDraftDecision.self, endpoint)
    }
}

import Foundation
import HarnaxCore

/// The draft-review half of the admin surface. Lives on `AdminClient` through an extension so it shares the
/// bearer injection, the 401 replay and the tenant header with every other domain
/// (`AdminClient.swift:4-6`).
///
/// Nothing here is a `.success` for the review having happened: `approve` and `reject` answer with a
/// `SkillDraftDecision` whose `outcome` says what was actually done, and three of the five refusals ride in a
/// `code: 200` envelope on purpose (`SkillDraftDecisionResponse.kt:9-16`).
extension AdminClient: SkillDraftCataloging {
    public func page(
        status: SkillDraftStatus,
        name: String?,
        sessionId: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillDraftRow>, APIError> {
        await client.send(
            Page<SkillDraftRow>.self,
            SkillDraftEndpoint.page(status: status, name: name, sessionId: sessionId, num: num, size: size)
        )
    }

    public func detail(id: Int64) async -> Result<SkillDraftDetail, APIError> {
        await client.send(SkillDraftDetail.self, SkillDraftEndpoint.detail(id: id))
    }

    public func approve(id: Int64, _ payload: SkillDraftApprovePayload) async -> Result<SkillDraftDecision, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try SkillDraftEndpoint.approve(id: id, payload)
        } catch {
            return .failure(.decoding)
        }
        return await client.send(SkillDraftDecision.self, endpoint)
    }

    public func reject(id: Int64, _ payload: SkillDraftRejectPayload) async -> Result<SkillDraftDecision, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try SkillDraftEndpoint.reject(id: id, payload)
        } catch {
            return .failure(.decoding)
        }
        return await client.send(SkillDraftDecision.self, endpoint)
    }
}

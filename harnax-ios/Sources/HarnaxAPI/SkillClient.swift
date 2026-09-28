import Foundation
import HarnaxCore

/// The skill domain's half of the admin surface: source CRUD, the two-step sync, the skill table and its
/// detail read. Lives on `AdminClient` through an extension so it shares the token injection, the 401
/// replay and the tenant header with every other domain.
extension AdminClient: SkillCataloging {
    public func sourcePage(
        name: String?,
        sourceType: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillSourceSummary>, APIError> {
        await client.send(
            Page<SkillSourceSummary>.self,
            SkillEndpoint.sourcePage(name: name, sourceType: sourceType, status: status, num: num, size: size)
        )
    }

    public func source(id: Int64) async -> Result<SkillSourceSummary, APIError> {
        await client.send(SkillSourceSummary.self, SkillEndpoint.source(id: id))
    }

    public func createSource(_ payload: SkillSourceCreatePayload) async -> Result<SkillSourceInstallResult, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try SkillEndpoint.create(payload)
        } catch {
            return .failure(.decoding)
        }
        return await client.send(SkillSourceInstallResult.self, endpoint)
    }

    public func updateSource(id: Int64, _ payload: SkillSourceUpdatePayload) async -> Result<EmptyResponse, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try SkillEndpoint.update(id: id, payload)
        } catch {
            return .failure(.decoding)
        }
        return await client.send(EmptyResponse.self, endpoint)
    }

    public func install(sourceID: Int64, names: [String]?) async -> Result<SkillInstallOutcome, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try SkillEndpoint.install(id: sourceID, names: names)
        } catch {
            return .failure(.decoding)
        }
        return await client.send(SkillInstallOutcome.self, endpoint)
    }

    public func deleteSource(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, SkillEndpoint.delete(id: id))
    }

    public func setSourceStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, SkillEndpoint.toggleSource(id: id, status: enabled.hxInt))
    }

    public func preview(sourceID: Int64) async -> Result<[SkillPreviewItem], APIError> {
        await client.send([SkillPreviewItem].self, SkillEndpoint.fetch(id: sourceID))
    }

    public func uploadSource(
        name: String,
        fileName: String,
        payload: Data
    ) async -> Result<SkillSourceInstallResult, APIError> {
        await client.send(SkillSourceInstallResult.self, SkillEndpoint.upload(name: name, fileName: fileName, payload: payload))
    }

    public func skillPage(
        name: String?,
        repositoryID: Int64?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillItem>, APIError> {
        await client.send(
            Page<SkillItem>.self,
            SkillEndpoint.skillPage(name: name, repositoryID: repositoryID, status: status, num: num, size: size)
        )
    }

    public func skill(id: Int64) async -> Result<SkillItem, APIError> {
        await client.send(SkillItem.self, SkillEndpoint.skill(id: id))
    }

    public func setSkillStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, SkillEndpoint.toggleSkill(id: id, status: enabled.hxInt))
    }
}

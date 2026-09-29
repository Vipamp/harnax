import Foundation
import HarnaxCore

extension AdminClient: EnvVarCataloging {
    public func envVarPage(keyword: String?, num: Int, size: Int) async -> Result<Page<EnvVarSummary>, APIError> {
        await client.send(
            Page<EnvVarSummary>.self,
            EnvVarEndpoint.page(keyword: keyword, num: num, size: size)
        )
    }

    /// Unpaged, and the `data` is a bare array rather than a `Page`, so it decodes as `[EnvVarCandidate]`.
    public func envVarCandidates() async -> Result<[EnvVarCandidate], APIError> {
        await client.send([EnvVarCandidate].self, EnvVarEndpoint.list)
    }

    public func createEnvVar(_ draft: EnvVarDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? EnvVarEndpoint.create(draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    public func updateEnvVar(id: Int64, _ change: EnvVarChange) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? EnvVarEndpoint.update(id: id, change) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    public func setEnvVarStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, EnvVarEndpoint.toggle(id: id, enabled: enabled.hxInt))
    }

    public func deleteEnvVar(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, EnvVarEndpoint.delete(id: id))
    }
}

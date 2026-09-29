import Foundation
import HarnaxCore

/// Saving a team: the same two submissions, one agent-shaped payload with a membership on the end.
extension AdminClient: TeamWriting {
    /// Refused outright when the draft carries no member (`TeamCreateRequest.kt:41-44`), and the lead's own
    /// name collides inside the tenant the same way an agent's does.
    public func createTeam(_ draft: TeamSaveDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? TeamEndpoint.create(draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    /// `members` and `skillIds` replace their whole sets when present, so this is the one place in the app
    /// where an empty list and a missing list mean opposite things
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamUpdateRequest.kt:8-13`).
    public func updateTeam(id: Int64, _ draft: TeamSaveDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? TeamEndpoint.update(id: id, draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }
}

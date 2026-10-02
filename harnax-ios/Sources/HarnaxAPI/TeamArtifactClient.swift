import Foundation
import HarnaxCore

/// The two reads of `TeamArtifactReading`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:40`,
/// a class mapped at `/api/admin/team-artifacts` that is only registered when the server runs with
/// `minio.enabled=true` (`:42`). A deployment without object storage therefore answers both paths as unknown
/// routes, and `DESIGN.md` O8 asks the list leg to name that as `objectStoreDisabled` rather than to report it
/// as a mystery about the files.
///
/// The ownership rule the controller applies to both (`:108-127`) is why the drawer is a team-only control: a
/// conversation with no `teamId`, a stopped conversation, or one the caller did not create all answer the same
/// refusal, and the list route says so in the envelope's own sentence (`:64`).
extension AdminClient: TeamArtifactReading {
    public func teamArtifacts(sessionId: String) async -> Result<[TeamArtifact], APIError> {
        await client
            .send([TeamArtifact].self, TeamArtifactEndpoint.list(sessionId: sessionId))
            .mapError { $0.isUnregisteredRoute ? .objectStoreDisabled : $0 }
    }

    /// The bytes, not an envelope. `sendRaw` is the only way this leaves the app: the shared mapper would read
    /// a body of file bytes as an unreadable reply, and the console sends this one without a tenant header
    /// (`harnax-webui/src/services/ant-design-pro/team.ts:91-113`), which `TeamArtifactEndpoint.download`
    /// turns off for this route alone.
    ///
    /// A name is only ever what the response's `Content-Disposition` says (`:96`); when the header names no
    /// file this returns an empty one and the caller's own row is the fallback, because the server sanitises
    /// that value rather than inventing it.
    public func downloadTeamArtifact(fileId: String, sessionId: String) async -> Result<TeamArtifactFile, APIError> {
        let result = await client.sendRaw(TeamArtifactEndpoint.download(fileId: fileId, sessionId: sessionId))
        return result.map {
            TeamArtifactFile(
                data: $0.data,
                fileName: $0.suggestedFilename ?? "",
                mimeType: $0.mimeType
            )
        }
    }
}

enum TeamArtifactEndpoint {
    static let base = "/api/admin/team-artifacts"

    /// `sessionId` goes in the query. The controller re-checks it against `^[a-zA-Z0-9_-]{1,128}$` (`:56`) and
    /// refuses a value outside it, so nothing here tries to repair one first.
    static func list(sessionId: String) -> Endpoint {
        Endpoint(
            .get,
            path: base,
            query: [URLQueryItem(name: "sessionId", value: sessionId)]
        )
    }

    /// The `fileId` is a path segment and the `sessionId` stays in the query (`:72-76`) — both are encoded the
    /// way every other opaque id in this app is.
    static func download(fileId: String, sessionId: String) -> Endpoint {
        Endpoint(
            .get,
            path: "\(base)/\(Endpoint.segment(fileId))",
            query: [URLQueryItem(name: "sessionId", value: sessionId)],
            sendsTenantHeader: false
        )
    }
}

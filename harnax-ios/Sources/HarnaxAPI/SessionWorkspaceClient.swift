import Foundation
import HarnaxCore

/// The sandbox behind a conversation: its status, its directory, its files.
///
/// Four of the five routes are runtime ones (`base: .router`) and the fifth is the artifact store on the admin
/// host. Both ride the shared transport, which already carries the bearer token; the console sends these its
/// permanent key instead (`harnax-webui/src/services/ant-design-pro/workspace.ts:23-33` with
/// `skipAuthorization`), and the runtime's filter accepts either credential
/// (`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt:73`, `:102`), so a device never
/// needs the key at all.
extension AdminClient: SessionWorkspaceReading {
    /// The batch route with one id in it, which is how the console reads a single conversation
    /// (`workspace.ts:98-110` against `AgentProxyController.kt:213-228`). Reusing it keeps the answer shape the
    /// channel list already decodes rather than inventing a second one.
    public func sandboxStatus(sessionId: String) async -> Result<SandboxStatus, APIError> {
        let result = await client.send(
            SandboxStatusMap.self,
            SessionWorkspaceEndpoint.status(sessionId: sessionId)
        )
        return result.map { $0.status(for: sessionId) }
    }

    public func workspaceFiles(sessionId: String, path: String) async -> Result<[WorkspaceFile], APIError> {
        await client.send(
            [WorkspaceFile].self,
            SessionWorkspaceEndpoint.files(sessionId: sessionId, path: path)
        )
    }

    public func readWorkspaceFile(
        sessionId: String,
        path: String
    ) async -> Result<WorkspaceFileContent, APIError> {
        await client.send(
            WorkspaceFileContent.self,
            SessionWorkspaceEndpoint.read(sessionId: sessionId, path: path)
        )
    }

    /// A body this side cannot assemble is a local failure, not a refused upload: the multipart boundary and
    /// the two parts are built before anything touches the socket.
    public func uploadWorkspaceFile(
        sessionId: String,
        path: String,
        fileName: String,
        mimeType: String,
        payload: Data
    ) async -> Result<WorkspaceUpload, APIError> {
        guard let endpoint = try? SessionWorkspaceEndpoint.upload(
            sessionId: sessionId,
            path: path,
            fileName: fileName,
            mimeType: mimeType,
            payload: payload
        ) else {
            return .failure(.decoding)
        }
        return await client.send(WorkspaceUpload.self, endpoint)
    }

    /// The bytes, not an envelope (`AgentProxyController.kt:278-302`).
    ///
    /// The name is the response's `Content-Disposition` when it has one, and otherwise the same
    /// `safeFileName` rule the server applies to the path's last component (`:268-273`, `:293-296`) — the
    /// router sanitises the name it writes into that header, so a local fallback built any other way would
    /// save a file under a name the server would never have produced.
    public func downloadWorkspaceFile(
        sessionId: String,
        path: String
    ) async -> Result<WorkspaceDownload, APIError> {
        let result = await client.sendRaw(
            SessionWorkspaceEndpoint.download(sessionId: sessionId, path: path)
        )
        return result.map {
            WorkspaceDownload(
                name: $0.suggestedFilename ?? WorkspacePath.safeFileName(path),
                data: $0.data,
                mimeType: $0.mimeType
            )
        }
    }

    /// The artifact store is optional in the deployment: `OutputFileController.kt:38` only registers the class
    /// with `minio.enabled=true`, so with it off this route answers as an unknown path. `DESIGN.md` O8 asks for a
    /// named degradation there rather than a page error, and the route-missing sentence is what separates that
    /// from a 404 about the file itself (`:95`, `:105`), which stays shown as it arrived.
    public func downloadAttachment(
        _ attachment: ChatFileAttachment,
        sessionId: String
    ) async -> Result<WorkspaceDownload, APIError> {
        let result = await client.sendRaw(
            SessionWorkspaceEndpoint.attachment(attachment, sessionId: sessionId)
        ).mapError { $0.isUnregisteredRoute ? .objectStoreDisabled : $0 }
        return result.map {
            WorkspaceDownload(
                name: $0.suggestedFilename ?? hxPresented(attachment.fileName)
                    ?? WorkspacePath.safeFileName(attachment.fileId),
                data: $0.data,
                mimeType: $0.mimeType
            )
        }
    }
}

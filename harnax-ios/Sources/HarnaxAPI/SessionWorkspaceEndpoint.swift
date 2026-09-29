import Foundation
import HarnaxCore

/// The sandbox workspace routes, on the router base, plus the artifact download on the admin base.
///
/// The five share one file because they share one credential story (`SessionWorkspaceClient`) rather than one
/// prefix: four address `/api/router/agent/workspace/**` (`AgentProxyController.kt:180-302`) and the fifth
/// answers `/api/output-files/**` (`OutputFileController.kt:36`), which is served by admin but sits outside
/// the `/api/admin` class.
enum SessionWorkspaceEndpoint {
    static let base = "/api/router/agent/workspace"

    /// `GET /status?sessionIds=`. The console reads one conversation through the batch route with a single id
    /// in it (`workspace.ts:74-92`), so iOS does the same and gets the answer shape the channel list already
    /// decodes (`ChannelEndpoint.sandboxStatuses`).
    static func status(sessionId: String) -> Endpoint {
        Endpoint(
            .get,
            path: "\(base)/status",
            query: [URLQueryItem(name: "sessionIds", value: sessionId)],
            base: .router
        )
    }

    /// The `path` parameter is a **directory** here, and the agent's own default is the drawer's root
    /// (`SandboxWorkspaceController.kt:94`). It travels as a plain query value: the route decodes it twice
    /// (`:97`, on purpose, "%2F is not auto-decoded"), so a value that never needed decoding is the one that
    /// cannot be mangled on the way in.
    static func files(sessionId: String, path: String) -> Endpoint {
        Endpoint(
            .get,
            path: "\(base)/\(Endpoint.segment(sessionId))/files",
            query: [URLQueryItem(name: "path", value: path)],
            base: .router
        )
    }

    /// Same parameter, now a **file** (`SandboxWorkspaceController.kt:144`).
    static func read(sessionId: String, path: String) -> Endpoint {
        Endpoint(
            .get,
            path: "\(base)/\(Endpoint.segment(sessionId))/read",
            query: [URLQueryItem(name: "path", value: path)],
            base: .router
        )
    }

    /// `POST /{sessionId}/upload`. `path` is a form field and the archive part is named `file`
    /// (`AgentProxyController.kt:248-252`, console shape at `workspace.ts:121-123`); the session id is the only
    /// thing in the URL.
    static func upload(
        sessionId: String,
        path: String,
        fileName: String,
        mimeType: String,
        payload: Data
    ) throws -> Endpoint {
        let multipart = HarnaxMultipart.makeResult(
            parts: [
                .file("file", fileName: fileName, payload: payload, contentType: mimeType),
                .field("path", path),
            ]
        )
        return Endpoint(
            .post,
            path: "\(base)/\(Endpoint.segment(sessionId))/upload",
            body: multipart.body,
            base: .router,
            contentType: multipart.contentType
        )
    }

    /// `GET /{sessionId}/download?path=` — a bare body, not an envelope (`AgentProxyController.kt:278-302`).
    static func download(sessionId: String, path: String) -> Endpoint {
        Endpoint(
            .get,
            path: "\(base)/\(Endpoint.segment(sessionId))/download",
            query: [URLQueryItem(name: "path", value: path)],
            base: .router
        )
    }

    /// The artifact download. The `name` is not a parameter the route reads for its own purposes but the file
    /// name it echoes back in `Content-Disposition` (`OutputFileController.kt:72`, `:110-112`), and the whole
    /// tail — type segment, id segments, encoded name — is `OutputFileDownload.path`, which is what makes it
    /// byte-identical to the URL the harness put in the attachment frame.
    ///
    /// The encoded name therefore travels inside `path` rather than as a `URLQueryItem`: handing it to
    /// `URLComponents` again would escape the `%` signs that are already there.
    static func attachment(_ attachment: ChatFileAttachment, sessionId: String) -> Endpoint {
        Endpoint(
            .get,
            path: OutputFileDownload.path(
                sessionId: sessionId, fileId: attachment.fileId, fileName: attachment.fileName
            ),
            base: .admin
        )
    }
}

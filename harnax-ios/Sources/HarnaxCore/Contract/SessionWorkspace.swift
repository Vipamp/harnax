import Foundation

/// What one entry of a sandbox workspace directory is.
///
/// The agent's `find -printf '%y'` maps its single-letter type codes onto these four words
/// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SandboxWorkspaceController.kt:113-119`),
/// and the router passes the map through untouched, so anything the runtime of a later day adds lands in
/// `unknown` rather than failing the listing.
public enum WorkspaceFileKind: String, Equatable, Sendable {
    case file
    case directory
    case symlink
    case unknown

    public init(wireValue: String?) {
        self = WorkspaceFileKind(rawValue: wireValue?.lowercased() ?? "") ?? .unknown
    }

    public var isDirectory: Bool { self == .directory }
}

/// One row of `GET /api/router/agent/workspace/{sessionId}/files` — the four keys the agent puts in the map
/// (`SandboxWorkspaceController.kt:112-121`).
///
/// `name` is the entry's own name, never a path: the listing runs `find` with `-maxdepth 1` and takes `%f`,
/// so navigating into a directory means asking again with a longer `path` rather than with a different
/// answer. `modified` is `%T+`, i.e. an ISO-8601 timestamp with the container's offset on the end, which is
/// not the `2026-09-12 10:20:30` shape `hxMonthDay` parses — so it is displayed verbatim or not at all.
public struct WorkspaceFile: Decodable, Identifiable, Equatable, Sendable {
    public let type: WorkspaceFileKind
    public let name: String
    public let size: Int64?
    public let modified: String?

    public init(
        type: WorkspaceFileKind,
        name: String,
        size: Int64? = nil,
        modified: String? = nil
    ) {
        self.type = type
        self.name = name
        self.size = size
        self.modified = modified
    }

    /// Names are unique inside one directory, which is the only scope the listing covers.
    public var id: String { name }

    /// Read key by key rather than by the synthesised conformance: the synthesised one decodes `type` as a
    /// raw-value enum, so a type word this build has never seen would fail the whole listing instead of
    /// landing in `unknown`, and it would require a `name` the agent answers as `""` for a nameless entry
    /// (`SandboxWorkspaceController.kt:120`).
    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        type = WorkspaceFileKind(wireValue: try? box.decodeIfPresent(String.self, forKey: .type))
        name = (try? box.decodeIfPresent(String.self, forKey: .name)) ?? ""
        size = try? box.decodeIfPresent(Int64.self, forKey: .size)
        modified = try? box.decodeIfPresent(String.self, forKey: .modified)
    }

    private enum Key: String, CodingKey {
        case type, name, size, modified
    }

    /// The console's rule (`harnax-webui/src/pages/session/components/WorkspaceDrawer.tsx:60-64`): directories
    /// first, then `name.localeCompare`, which is what `localizedStandardCompare` stands in for — a plain `<`
    /// on `String` would sort `Zebra` before `apple`.
    public static func sortedForListing(_ files: [WorkspaceFile]) -> [WorkspaceFile] {
        files.sorted { left, right in
            if left.type.isDirectory != right.type.isDirectory { return left.type.isDirectory }
            let byName = left.name.localizedStandardCompare(right.name)
            if byName != .orderedSame { return byName == .orderedAscending }
            return left.id < right.id
        }
    }
}

/// `GET /api/router/agent/workspace/{sessionId}/read` — `content`, `truncated`, `size`
/// (`SandboxWorkspaceController.kt:133-176`).
///
/// `truncated` is true in two different cases and neither is an error: the file is over the reader's own cap,
/// in which case `content` is the sentence the server wrote instead of the file
/// (`:148-156`), or the sandbox cut the read. Either way the preview has to say so rather than present a
/// partial file as the whole thing.
public struct WorkspaceFileContent: Decodable, Equatable, Sendable {
    public let content: String
    public let truncated: Bool
    public let size: Int64?

    public init(content: String, truncated: Bool = false, size: Int64? = nil) {
        self.content = content
        self.truncated = truncated
        self.size = size
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        content = (try? box.decodeIfPresent(String.self, forKey: .content)) ?? ""
        truncated = (try? box.decodeIfPresent(Bool.self, forKey: .truncated)) ?? false
        size = try? box.decodeIfPresent(Int64.self, forKey: .size)
    }

    private enum Key: String, CodingKey {
        case content, truncated, size
    }
}

/// What the upload route confirms: the name it stored the file under, the directory it went into, and the
/// byte count. `ResultVo<Map<String, Any>>` on the router (`AgentProxyController.kt:257`) whose three keys the
/// console types for us (`workspace.ts:125`).
///
/// All three are read tolerantly because the map is untyped: the reply names a file the sandbox already took,
/// so a missing `fileName` costs the confirmation line, not the upload.
public struct WorkspaceUpload: Decodable, Equatable, Sendable {
    public let fileName: String
    public let path: String
    public let size: Int64?

    public init(fileName: String, path: String, size: Int64? = nil) {
        self.fileName = fileName
        self.path = path
        self.size = size
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        fileName = (try? box.decodeIfPresent(String.self, forKey: .fileName)) ?? ""
        path = (try? box.decodeIfPresent(String.self, forKey: .path)) ?? ""
        size = Self.readSize(in: box)
    }

    /// A count the route wrote as a number or as a numeric string both read, since the map is untyped
    /// (`AgentProxyController.kt:257` answers `ResultVo<Map<String, Any>>`).
    private static func readSize(in box: KeyedDecodingContainer<Key>) -> Int64? {
        if let exact = (try? box.decodeIfPresent(Int64.self, forKey: .size)) ?? nil { return exact }
        guard let text = (try? box.decodeIfPresent(String.self, forKey: .size)) ?? nil else { return nil }
        return Int64(text)
    }

    private enum Key: String, CodingKey {
        case fileName, path, size
    }
}

/// A file the drawer pulled out of the sandbox.
///
/// The bytes and the name arrive together because the name is not in the body: the router answers a bare
/// `Content-Disposition` (`AgentProxyController.kt:293-296`), so a download either carries the header or falls
/// back to the local `safeFileName` rule the server itself applies.
public struct WorkspaceDownload: Equatable, Sendable {
    public let name: String
    public let data: Data
    public let mimeType: String?

    public init(name: String, data: Data, mimeType: String? = nil) {
        self.name = name
        self.data = data
        self.mimeType = mimeType
    }
}

/// The two path rules the workspace drawer cannot get wrong.
public enum WorkspacePath {
    /// The drawer's fixed root, and the router's own default for the `path` parameter
    /// (`WorkspaceDrawer.tsx:39`, `AgentProxyController.kt:186`).
    public static let root = "/workspace"

    /// A directory plus one entry name. `find` returns bare names, so the caller has to rebuild the full path
    /// before it can read or download the file it tapped.
    public static func joined(_ directory: String, _ name: String) -> String {
        if directory.isEmpty { return "/\(name)" }
        if directory.hasSuffix("/") { return "\(directory)\(name)" }
        return "\(directory)/\(name)"
    }

    /// The parent of a workspace path, stopping at the root: `/workspace/a/b.md` → `/workspace/a`,
    /// `/workspace` → `/workspace`. The drawer's breadcrumb cannot climb above the root it was opened at
    /// (`WorkspaceDrawer.tsx:118-133`).
    public static func parent(_ path: String) -> String {
        guard let cut = path.lastIndex(of: "/") else { return root }
        let head = path[..<cut]
        return head.isEmpty ? root : String(head)
    }

    /// Port of the router's `safeFileName` (`AgentProxyController.kt:268-273`), used here only to name the
    /// file this side saved when the response carried no `Content-Disposition`.
    ///
    /// The server's reason for the rule is the log line and the header it repeats a client-supplied name in:
    /// the directory part goes, a quote and the control characters go, the name is cut at 128, and an empty
    /// result becomes `unnamed`.
    public static func safeFileName(_ raw: String) -> String {
        var base = raw
        for separator: Character in ["/", "\\"] {
            guard let cut = base.lastIndex(of: separator) else { continue }
            base = String(base[base.index(after: cut)...])
        }
        let kept = base.unicodeScalars.filter { $0 != "\"" && $0.value >= 0x20 }.prefix(128)
        let cleaned = String(kept)
        // `ifBlank` rather than `isEmpty`: the server's own check, so a name of only spaces also collapses.
        return cleaned.allSatisfy(\.isWhitespace) ? "unnamed" : cleaned
    }
}

/// Builds the address `GET /api/output-files/{sessionType}/{sessionId}/{fileId}?name=` — the download the
/// attachment list points at.
///
/// The shape is the agent's own: the harness composes the very string it hands to the chat stream as the
/// attachment's `url` (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/output/OutputFileStore.kt:104-106`).
/// iOS rebuilds it from the four fields against *its own* configured admin address rather than reusing that
/// string, because `adminBaseUrl` is a property of the agent's deployment
/// and a device pointed at a different host would then fetch from a server the operator never configured.
///
/// The encoding is `java.net.URLEncoder` with one patch: every byte of the UTF-8 form that is not
/// `A-Za-z0-9`, `.`, `-`, `*` or `_` goes out as `%XX` with upper-case hex, and a space — which `URLEncoder`
/// writes as `+` and the file then replaces with `%20` (`OutputFileStore.kt:104`). `*` surviving is surprising
/// but it is what the server's own names are built with, so a name built any other way is not the URL the
/// attachment list would have produced.
public enum OutputFileDownload {
    public static let routePrefix = "/api/output-files"

    /// `resolveSessionType`: a `task-` conversation's files live under `task`, everything else under `web`
    /// (`OutputFileStore.kt:131-134`). The third type the route accepts, `channel`, is never produced here —
    /// a channel's files come from a channel run, whose id the chat side does not carry.
    public static func sessionType(for sessionId: String) -> String {
        sessionId.hasPrefix("task-") ? "task" : "web"
    }

    public static func encodedName(_ fileName: String) -> String {
        var text = ""
        for scalar in fileName.unicodeScalars {
            if Self.unreserved.contains(scalar) {
                text.unicodeScalars.append(scalar)
            } else if scalar == " " {
                text += "%20"
            } else {
                for byte in String(scalar).utf8 { text += String(format: "%%%02X", byte) }
            }
        }
        return text
    }

    /// The `path` segment plus the `name` query, ready to hang off a base URL. A name that is blank goes out
    /// as the route's own default, `download` (`OutputFileController.kt:72`).
    public static func path(sessionId: String, fileId: String, fileName: String) -> String {
        let name = hxPresented(fileName) ?? "download"
        return "\(routePrefix)/\(sessionType(for: sessionId))/\(sessionId)/\(fileId)?name=\(encodedName(name))"
    }

    public static func url(adminBaseURL: String, sessionId: String, fileId: String, fileName: String) -> URL? {
        URL(string: adminBaseURL + path(sessionId: sessionId, fileId: fileId, fileName: fileName))
    }

    /// `URLEncoder`'s unreserved set — note the `*`, which RFC 3986 would have percent-encoded.
    private static let unreserved: Set<UnicodeScalar> = {
        let letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._*-"
        return Set(letters.unicodeScalars)
    }()
}

/// The sandbox behind a conversation: whether it is up, what is in it, and how to get a file out.
///
/// Backend: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:181-302`.
/// These are runtime routes on the router base, and the console sends them its permanent key rather than the
/// bearer (`workspace.ts:23-33` with `skipAuthorization`), which is why this surface has its own client instead
/// of another `AdminClient` extension. The `status` route is the one exception the package already carries:
/// the channel list reads the same URL through the shared admin transport
/// (`ChannelEndpoint.sandboxStatuses`), and the runtime's filter accepts either credential
/// (`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt:73`, `:102`).
///
/// All five address the conversation by its **string** business key.
public protocol SessionWorkspaceReading: Sendable {
    /// Whether the sandbox is up, from `GET /api/router/agent/workspace/status?sessionIds=<one id>` — the
    /// console's single-session read is the batch route with one id in it
    /// (`workspace.ts:98-110`, `AgentProxyController.kt:213-228`).
    ///
    /// Reused rather than restated: the answer shape is `SandboxStatusMap`, already modelled for the channel
    /// list. A conversation this reply says nothing about is `.unknown`, which is deliberately not `.idle`.
    func sandboxStatus(sessionId: String) async -> Result<SandboxStatus, APIError>

    /// The directory listing. `path` defaults to `/workspace` (`AgentProxyController.kt:186`).
    func workspaceFiles(sessionId: String, path: String) async -> Result<[WorkspaceFile], APIError>

    /// A file's text, for the preview. The console writes a failed read into the preview area rather than
    /// raising a dialog (`WorkspaceDrawer.tsx:88-107`), and the caller keeps that behaviour by handling this
    /// result without leaving the list.
    func readWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceFileContent, APIError>

    /// Puts a file into the sandbox: `POST /api/router/agent/workspace/{sessionId}/upload`
    /// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:248-260`).
    ///
    /// `path` travels as a **form field** here, not a query item like the three reads above — that is the
    /// console's own shape (`harnax-webui/src/services/ant-design-pro/workspace.ts:121-123`) and the route's
    /// `@RequestParam` binds from either, so the two differ only in where a name containing `%` gets decoded.
    ///
    /// The name the sandbox ends up with is the server's, not the caller's: the route runs the original file
    /// name through `safeFileName` (`:254`) before handing it to the agent, and echoes that back in the reply.
    func uploadWorkspaceFile(
        sessionId: String,
        path: String,
        fileName: String,
        mimeType: String,
        payload: Data
    ) async -> Result<WorkspaceUpload, APIError>

    /// `GET /api/router/agent/workspace/{sessionId}/download?path=`.
    ///
    /// Two non-200 answers are ordinary here and say different things: 404 for a path that is not in the
    /// sandbox, 413 for one over the 50 MB cap (`AgentProxyController.kt:41`, `:288-292`). Neither carries an
    /// envelope, so the status code is the whole message.
    func downloadWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceDownload, APIError>

    /// A file a run produced, fetched from the artifact store through the admin host.
    ///
    /// The `sessionId` is a parameter and not read off the attachment because it is not on it: the route
    /// addresses `{sessionType}/{sessionId}/{fileId}` (`OutputFileController.kt:66`) and `FileAttachment`
    /// carries neither the conversation nor its type (`FileAttachment.kt:14-22`). The caller has it — it is
    /// the conversation whose stream the frame arrived on.
    ///
    /// The store is optional in the deployment: the whole controller is
    /// `@ConditionalOnProperty(minio.enabled = true)` (`OutputFileController.kt:38`), so with MinIO off this
    /// answers 404 and the caller has to treat "no artifact store" as a missing file rather than a network
    /// fault. The attachment list itself only ever arrives on the stream's end frame
    /// (`ChatEvent.StreamEnd.attachments`), which is why this takes the attachment rather than looking it up.
    func downloadAttachment(
        _ attachment: ChatFileAttachment,
        sessionId: String
    ) async -> Result<WorkspaceDownload, APIError>
}

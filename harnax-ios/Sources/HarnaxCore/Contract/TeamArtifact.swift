import Foundation

/// One file a team member published into the conversation, as `GET /api/admin/team-artifacts?sessionId=`
/// answers it.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamArtifactResponse.kt:14-32`. Two
/// facts about that DTO decide what a client can do with a row:
/// - there is **no `objectKey`**, deliberately — the key is where the bytes live in the bucket and a client
///   that has it can try to use it elsewhere (`:9-12`). `fileId` is the only handle, and only the download
///   route resolves the key from the row (`controller/TeamArtifactController.kt:72-100`).
/// - every column is a non-null Kotlin type with a default (`:16-31`), so under the stack's `non_null`
///   inclusion the keys always arrive. They are still read tolerantly here: a row that half-decodes should
///   cost one line of the drawer, not the whole list.
///
/// The controller behind the route is registered only when the server runs with `minio.enabled=true`
/// (`TeamArtifactController.kt:42`), so a deployment without object storage answers this path as unknown —
/// an ordinary failure the drawer reports rather than a state it invents.
public struct TeamArtifact: Decodable, Equatable, Sendable, Identifiable {
    /// The artifact reference a member was handed and the only thing the download route takes. A UUID
    /// (`TeamArtifactController.kt:54`); anything else is refused 400 server-side.
    public let fileId: String
    public let fileName: String
    public let mimeType: String
    public let sizeBytes: Int64
    /// Which member produced it (`TeamArtifactResponse.kt:27-28`). Read, never used to address anything.
    public let memberAgentId: Int64
    /// Publication time. A string, as the DTO's own `createTime.toString()` writes it (`:41`).
    public let createTime: String

    public init(
        fileId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Int64,
        memberAgentId: Int64 = 0,
        createTime: String
    ) {
        self.fileId = fileId
        self.fileName = fileName
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.memberAgentId = memberAgentId
        self.createTime = createTime
    }

    private enum Field: String, CodingKey {
        case fileId
        case fileName
        case mimeType
        case sizeBytes
        case memberAgentId
        case createTime
    }

    public init(from decoder: Decoder) throws {
        let row = try decoder.container(keyedBy: Field.self)
        fileId = row.hxString(.fileId) ?? ""
        fileName = row.hxString(.fileName) ?? ""
        mimeType = row.hxString(.mimeType) ?? ""
        sizeBytes = row.hxInt64(.sizeBytes) ?? 0
        memberAgentId = row.hxInt64(.memberAgentId) ?? 0
        createTime = row.hxString(.createTime) ?? ""
    }

    public var id: String { fileId }

    /// The row's own `fileId.slice(0,8)…` tag (`harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx:83-104`).
    /// Eight characters is enough to tell two artifacts apart on one screen, and the whole value stays
    /// reachable because the tag is what the user copies.
    public var shortFileId: String {
        fileId.count > 8 ? "\(fileId.prefix(8))…" : fileId
    }

    /// The subtitle the console spells as `${mimeType} · ${formatSize(sizeBytes)}`
    /// (`TeamArtifactsDrawer.tsx:68-82`).
    public var subtitle: String { "\(mimeType) · \(hxFileSize(sizeBytes))" }

    public var displayName: String? { hxPresented(fileName) }
}

/// The bytes of one artifact, as `GET /api/admin/team-artifacts/{fileId}?sessionId=` returns them — a plain
/// `ResponseEntity<ByteArray>` with a `Content-Disposition` naming the file
/// (`TeamArtifactController.kt:95-100`), not an envelope, which is why this is its own read rather than a
/// decoded `data`.
public struct TeamArtifactFile: Equatable, Sendable {
    public let data: Data
    public let fileName: String
    /// The media type the server set from the row, falling back to what the request asked with.
    public let mimeType: String

    public init(data: Data, fileName: String, mimeType: String) {
        self.data = data
        self.fileName = fileName
        self.mimeType = mimeType
    }

    public var sizeBytes: Int64 { Int64(data.count) }
}

/// The console's `formatSize`, spelled the same way: bytes below a kibibyte are a whole number, above it one
/// decimal and the unit letter. `ByteCountFormatter` is not used because it switches between decimal and
/// binary units by count style, and the drawer's own rule is 1024 all the way up.
public func hxFileSize(_ bytes: Int64) -> String {
    guard bytes > 0 else { return "0 B" }
    let units = ["B", "KB", "MB", "GB", "TB"]
    var value = Double(bytes)
    var unit = 0
    while value >= 1024, unit < units.count - 1 {
        value /= 1024
        unit += 1
    }
    if unit == 0 { return "\(Int(value)) B" }
    return String(format: "%.1f %@", value, units[unit])
}

private extension KeyedDecodingContainer {
    func hxString(_ key: K) -> String? {
        (try? decodeIfPresent(String.self, forKey: key)) ?? nil
    }

    /// A count the server wrote as a number or as a numeric string both read; neither is worth failing a
    /// row over.
    func hxInt64(_ key: K) -> Int64? {
        if let exact = try? decodeIfPresent(Int64.self, forKey: key) { return exact }
        guard let text = (try? decodeIfPresent(String.self, forKey: key)) ?? nil else { return nil }
        return Int64(text.trimmingCharacters(in: .whitespaces))
    }
}

/// The published files of one team conversation: the list and one file's bytes.
///
/// Its own protocol rather than members of `SessionCataloging`, because the two reads answer a different
/// controller class (`TeamArtifactController.kt:40`) and only ever exist for a conversation with a `teamId`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:59-100`,
/// a class only registered when the server runs with `minio.enabled=true` (`:42`). A deployment without object
/// storage therefore answers both paths as unknown, which is an ordinary failure the drawer reports rather than
/// a state it invents.
///
/// The ownership rule both routes apply (`:108-127`) is why the drawer is a team-only control: a conversation
/// with no `teamId`, a switched-off one, or one the caller did not create all answer the same refusal, and the
/// list route says so in the envelope's own sentence (`:64`).
public protocol TeamArtifactReading: Sendable {
    /// `GET /api/admin/team-artifacts?sessionId=` (`:59-70`), newest publication first. Three refusals are
    /// ordinary answers rather than bugs (`:64`, `:108-127`).
    func teamArtifacts(sessionId: String) async -> Result<[TeamArtifact], APIError>

    /// One artifact's bytes: `GET /api/admin/team-artifacts/{fileId}?sessionId=` (`:72-100`).
    ///
    /// Two departures from the rest of the admin surface. The answer is a `ResponseEntity<ByteArray>` with a
    /// `Content-Disposition`, not an envelope, so there is nothing for the shared mapper to unpack. And the
    /// console sends it on the bearer token alone — no `X-Tenant-ID`
    /// (`harnax-webui/src/services/ant-design-pro/team.ts:91-113`) — because the controller reads the tenant
    /// off the session row rather than off a header (`:108-127`).
    ///
    /// A `fileId` that is not a UUID is refused 400 before anything is read (`:78`, pattern `:54`), another
    /// session's artifact is a 404 rather than a 403 so the route cannot confirm that a reference exists
    /// (`:81-86`), and a caller who does not own the session is a 403 (`:79`).
    func downloadTeamArtifact(fileId: String, sessionId: String) async -> Result<TeamArtifactFile, APIError>
}

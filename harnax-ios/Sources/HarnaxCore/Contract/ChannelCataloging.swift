import Foundation

/// Everything the channel screens do: the paged read, the four writes, the sandbox lookup that colours the
/// rows, and the three WeChat scan calls.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ChannelController.kt:22` on
/// `/api/admin/channels`, plus `WechatLoginController.kt:28` on `/api/admin/channels/{id}/wechat`.
///
/// The route shapes that are easy to copy wrong from another domain: the update is `PUT /update/{id}`
/// (`:73`), the toggle puts the id *before* the verb and takes the flag as `status` rather than `enabled`
/// (`:83-87`), and create/update/delete all answer `ResultVo<Void>` — so a saved row is re-read from the
/// list, never taken from the reply.
public protocol ChannelCataloging: Sendable {
    /// `keyword` is a server-side `LIKE`, `type` the exact lower-case code and `status` the raw 0/1 column
    /// (`ChannelController.kt:34-46`). Rows are tenant-scoped server-side
    /// (`ChannelMapper.xml:143/147`), so an empty page means this tenant has no channels.
    func channelPage(
        keyword: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ChannelSummary>, APIError>
    func createChannel(_ draft: ChannelDraft) async -> Result<EmptyResponse, APIError>
    func updateChannel(id: Int64, _ change: ChannelChange) async -> Result<EmptyResponse, APIError>
    /// No pre-flight of any kind: this only writes `status` and validates 0/1
    /// (`ChannelServiceImpl.kt:133-146`). It does not stop a live listener.
    func setChannelStatus(id: Int64, running: Bool) async -> Result<EmptyResponse, APIError>
    /// Releases the channel's runtime *before* the row goes (`ChannelServiceImpl.kt:163-169`), and a refused
    /// release aborts the delete. That refusal is an ordinary outcome of this call, not a bug
    /// (`SessionRuntimeReleaser.kt:42-48`).
    func deleteChannel(id: Int64) async -> Result<EmptyResponse, APIError>
    /// Second-order read for the list: `GET /api/router/agent/workspace/status` on the *runtime* base,
    /// keyed by `sessionId`. A failure here must render as "unknown", never as "stopped" and never as an
    /// error that hides the rows (`harnax-webui/src/pages/channel/index.tsx:111-128` swallows it outright).
    func sandboxStatuses(sessionIds: [String]) async -> Result<SandboxStatusMap, APIError>
    /// Starts a QR login and hands back the PNG as a data URL. Only meaningful on a `wechat` row.
    func startWechatLogin(id: Int64) async -> Result<WechatQrCode, APIError>
    /// The poll. Reads `message` as the server's sentence about where the scan stands; a successful poll is
    /// what writes the credentials into `configJson` (`WechatLoginService.kt:162-186`), so the caller has to
    /// re-read the row afterwards.
    func wechatLoginStatus(id: Int64) async -> Result<WechatLoginUpdate, APIError>
    /// Fire-and-forget on the server's side (`WechatLoginController.kt:57-63`). Only worth calling when the
    /// sheet closes on a login that has not resolved.
    func cancelWechatLogin(id: Int64) async -> Result<EmptyResponse, APIError>
}

/// `GET /api/router/agent/workspace/status?sessionIds=a,b` — the runtime answers a JSON object keyed by
/// session id, and `AgentProxyController.kt:217` types each entry `Map<String, Any>`.
public struct SandboxStatusMap: Decodable, Equatable, Sendable {
    public let states: [String: SandboxState]

    public init(states: [String: SandboxState]) {
        self.states = states
    }

    /// A session this reply says nothing about is `unknown`, which is also what a whole failed lookup maps
    /// to at the caller.
    public func status(for sessionId: String?) -> SandboxStatus {
        guard let sessionId = hxPresented(sessionId) else { return .unknown }
        return SandboxStatus(from: states[sessionId])
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.singleValueContainer()
        states = try box.decode([String: SandboxState].self)
    }
}

public struct SandboxState: Decodable, Equatable, Sendable {
    public let active: Bool?

    public init(active: Bool?) {
        self.active = active
    }

    /// `active` is untyped on the server side, so a non-boolean answer decodes as "no answer" rather than
    /// failing the whole map.
    public init(from decoder: any Decoder) throws {
        guard let box = try? decoder.container(keyedBy: Key.self) else {
            active = nil
            return
        }
        if let flag = try? box.decodeIfPresent(Bool.self, forKey: .active) {
            active = flag
        } else if let number = try? box.decodeIfPresent(Int.self, forKey: .active) {
            active = number != 0
        } else {
            active = nil
        }
    }

    private enum Key: String, CodingKey {
        case active
    }
}

public enum SandboxStatus: Equatable, Sendable {
    case running
    case idle
    /// Not answered yet, or answered in a shape this side cannot read. Deliberately distinct from `idle`:
    /// the two mean opposite things and the row must not claim a sandbox is down because a lookup failed.
    case unknown

    public init(from state: SandboxState?) {
        switch state?.active {
        case .some(true): self = .running
        case .some(false): self = .idle
        case .none: self = .unknown
        }
    }
}

/// `POST /api/admin/channels/{id}/wechat/login` — `data` is the whole `<img src>` value the web console
/// uses verbatim: `data:image/png;base64,...` (`WechatLoginService.kt:191-208`).
public struct WechatQrCode: Decodable, Equatable, Sendable {
    public let dataUrl: String

    public init(dataUrl: String) {
        self.dataUrl = dataUrl
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.singleValueContainer()
        dataUrl = try box.decode(String.self)
    }

    /// The base64 body, with the data-URL prefix stripped. Kept next to the value so no caller has to know
    /// the prefix's exact shape — and a payload with no prefix still yields its own text.
    public var base64Body: String? {
        guard let range = dataUrl.range(of: "base64,") else {
            return hxPresented(dataUrl)
        }
        return hxPresented(String(dataUrl[range.upperBound...]))
    }

    public var pngData: Data? {
        guard let body = base64Body else { return nil }
        return Data(base64Encoded: body)
    }
}

/// `GET /api/admin/channels/{id}/wechat/login/status`
/// (`WechatLoginService.kt:214-217` — `status` and `message` are both plain strings).
public struct WechatLoginUpdate: Decodable, Equatable, Sendable {
    public let phase: WechatLoginPhase
    public let message: String

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        let code = (try? box.decodeIfPresent(String.self, forKey: .status)) ?? nil
        phase = WechatLoginPhase(code: code) ?? .waiting
        message = (try? box.decodeIfPresent(String.self, forKey: .message)) ?? nil ?? ""
    }

    private enum Key: String, CodingKey {
        case status, message
    }
}

/// The six states the poll can answer. `waiting` is the fall-back for a code this side has not seen, which
/// matches the console: an unknown code keeps polling rather than ending the flow
/// (`WechatLoginModal.tsx:99-101`).
public enum WechatLoginPhase: String, CaseIterable, Sendable {
    case notLoggedIn = "NOT_LOGIN"
    case waiting = "WAITING"
    case scanned = "SCANNED"
    case loggedIn = "LOGGED_IN"
    case expired = "EXPIRED"
    case error = "ERROR"

    public init?(code: String?) {
        guard let code = hxPresented(code) else { return nil }
        self.init(rawValue: code.uppercased())
    }
}

/// The credential the scan writes, read back out of `configJson` to tell "bound" from "not scanned yet".
/// Not a form field: `botToken` is in the server's secret set but outside the console's managed keys
/// (`ChannelServiceImpl.kt:386` against `UpdateForm.tsx:12`), so iOS reads it and never sends it.
public enum WechatLoginCredential {
    public static let botTokenKey = "botToken"
}

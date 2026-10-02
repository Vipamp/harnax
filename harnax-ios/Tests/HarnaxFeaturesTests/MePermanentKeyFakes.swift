import Foundation
import HarnaxCore
import HarnaxAPI
@testable import HarnaxFeatures

/// The account screen's own double for `ApiKeyCataloging` (KEY-1).
///
/// Its own file because the API Key list's double belongs to that domain's tests: this one only speaks the
/// two per-user routes F1 uses, and answers the six list routes by failing — the discipline every unwired
/// catalog in this suite keeps, so a screen that reaches a route it has no business reaching shows up as a
/// wrong call rather than as a list that quietly rendered nothing.
///
/// Replies are queued one per call, and an exhausted queue answers `.decoding` rather than a success: an
/// extra round trip then reads as a wrong reply, not as a silent pass.
final class MePermanentKeyDouble: ApiKeyCataloging, @unchecked Sendable {
    private(set) var readCalls = 0
    var readReplies: [Result<ApiKeySummary?, APIError>] = []

    private(set) var rotateCalls = 0
    var rotateReplies: [Result<ApiKeyCreatedSummary, APIError>] = []

    /// The six management routes, counted: the account screen must never call one, not even to "look up" its
    /// own key.
    private(set) var listRouteCalls = 0

    /// Holds the rotate open, so a test can act while a new secret is still on the wire.
    var gateRotate = false
    private var parked: [() -> Void] = []

    func myPermanentKey() async -> Result<ApiKeySummary?, APIError> {
        readCalls += 1
        return readReplies.isEmpty ? .failure(.decoding) : readReplies.removeFirst()
    }

    func regenerateMyPermanentKey() async -> Result<ApiKeyCreatedSummary, APIError> {
        rotateCalls += 1
        guard gateRotate else { return nextRotate() }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.nextRotate()) }
        }
    }

    /// Replays every parked rotate in the order it went out.
    func releaseRotations() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func nextRotate() -> Result<ApiKeyCreatedSummary, APIError> {
        rotateReplies.isEmpty ? .failure(.decoding) : rotateReplies.removeFirst()
    }

    // MARK: - the management list, which this screen does not open

    private func unwired<T>() -> Result<T, APIError> {
        .failure(.business(code: -1, message: "the account screen never calls the API Key list routes"))
    }

    private func touchedListRoute() {
        listRouteCalls += 1
    }

    func apiKeyPage(
        keyword: String?,
        enabled: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ApiKeySummary>, APIError> {
        touchedListRoute()
        return unwired()
    }

    func createApiKey(_ draft: ApiKeyDraft) async -> Result<ApiKeyCreatedSummary, APIError> {
        touchedListRoute()
        return unwired()
    }

    func updateApiKey(id: Int64, _ change: ApiKeyChange) async -> Result<EmptyResponse, APIError> {
        touchedListRoute()
        return unwired()
    }

    func setApiKeyStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        touchedListRoute()
        return unwired()
    }

    func deleteApiKey(id: Int64) async -> Result<EmptyResponse, APIError> {
        touchedListRoute()
        return unwired()
    }

    func regenerateApiKey(id: Int64) async -> Result<ApiKeyCreatedSummary, APIError> {
        touchedListRoute()
        return unwired()
    }
}

/// Fixtures for the account's own key.
///
/// Rows are decoded rather than constructed: every field on `ApiKeyResponse` is nullable
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyResponse.kt:8-41`) and the struct has no
/// public memberwise init, so the wire is the only honest way to make one — and it keeps a test's row a row the
/// backend could actually have answered with.
enum MeKeyFixture {
    /// `"hnx_sk_live_"` plus 43 url-safe base64 characters — what `generateRawKey()` writes
    /// (`ApiKeyServiceImpl.kt:330-334`). It exists in exactly one reply of one route.
    static let raw = "hnx_sk_live_" + String(repeating: "A", count: 39) + "9f3c"
    /// The prefix the server cuts from it: first 12 characters, `...`, last 4 (`ApiKeyServiceImpl.kt:82`),
    /// derived here rather than typed so a test cannot enshrine a mask the backend never produces.
    static let mask = String(raw.prefix(12)) + "..." + String(raw.suffix(4))

    /// The answer `GET /my-permanent-key` writes into its envelope: the eleven nullable columns of
    /// `ApiKeyResponse.kt:8-41`, nulls dropped the way Jackson drops them.
    ///
    /// One builder for both the in-memory row and the fake transport's body, because `ApiKeySummary` is
    /// `Decodable` only — a test cannot re-encode one, so the only way to say "the read hands back the same DTO
    /// the list uses" is to decode the same bytes twice.
    static func rowJSON(
        name: String? = "permanent_admin",
        keyPrefix: String? = mask,
        scopes: String? = "chat",
        enabled: Int? = 1,
        rateLimit: Int? = 300,
        expiresAt: String? = nil
    ) -> String {
        var fields: [String] = ["\"id\":41"]
        for (key, value) in [
            ("name", name), ("keyPrefix", keyPrefix), ("scopes", scopes), ("expiresAt", expiresAt),
        ] {
            if let value { fields.append("\"\(key)\":\"\(value)\"") }
        }
        if let enabled { fields.append("\"enabled\":\(enabled)") }
        if let rateLimit { fields.append("\"rateLimit\":\(rateLimit)") }
        return "{\(fields.joined(separator: ","))}"
    }

    static func row(
        name: String? = "permanent_admin",
        keyPrefix: String? = mask,
        scopes: String? = "chat",
        enabled: Int? = 1,
        rateLimit: Int? = 300,
        expiresAt: String? = nil
    ) throws -> ApiKeySummary {
        try decode(rowJSON(
            name: name,
            keyPrefix: keyPrefix,
            scopes: scopes,
            enabled: enabled,
            rateLimit: rateLimit,
            expiresAt: expiresAt
        ))
    }

    /// A row whose prefix column is blank — the shape that makes the screen say it has nothing to show.
    static func rowWithoutMask() throws -> ApiKeySummary {
        try row(keyPrefix: nil)
    }

    static func created(
        id: Int64 = 41,
        name: String = "permanent_admin",
        rawKey: String = raw
    ) -> ApiKeyCreatedSummary {
        ApiKeyCreatedSummary(id: id, name: name, rawKey: rawKey, keyPrefix: mask)
    }

    static func decode(_ json: String) throws -> ApiKeySummary {
        try JSONDecoder().decode(ApiKeySummary.self, from: Data(json.utf8))
    }
}

/// The real transport path for the two account routes, built out of the public seams of `HarnaxAPI`.
///
/// The feature-side double can only say what the client *asked for*; this says what went on the wire and what
/// came back through `ResponseMapper` — which is where a null `data` would turn into `.unpackable` if the
/// client read it wrong.
final class MeKeyTransport: HTTPRequesting, @unchecked Sendable {
    private(set) var requests: [URLRequest] = []
    private var replies: [(status: Int, body: String)] = []

    func enqueue(_ status: Int, _ body: String) {
        replies.append((status, body))
    }

    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        // An exhausted queue answers 599 so a missing expectation fails loudly instead of decoding `{}`.
        let reply = replies.isEmpty ? (599, "{}") : replies.removeFirst()
        guard let url = request.url,
              let http = HTTPURLResponse(url: url, statusCode: reply.0, httpVersion: nil, headerFields: nil)
        else {
            throw URLError(.badURL)
        }
        return (Data(reply.1.utf8), http)
    }
}

/// A refresher that never succeeds: these two routes take a fresh session and a 401 is the test's to assert,
/// not something to replay around.
private struct MeKeyNoRefresh: TokenRefreshing {
    func refresh(current accessToken: String) async throws -> RefreshedToken {
        throw APIError.unauthorized
    }
}

enum MeKeyHarness {
    /// The envelope shape the running stack answers with — nulls dropped, `isSuccess` derived
    /// (`Tests/HarnaxAPITests/Wire.swift:5-21` is the reference).
    static func envelope(code: Int, message: String, data: String?) -> String {
        let payload = data.map { ",\"data\":\($0)" } ?? ""
        return """
        {"code":\(code),"message":"\(message)"\(payload),"timestamp":1790592457109,"isSuccess":\(code == 200 ? "true" : "false")}
        """
    }

    static func client(transport: MeKeyTransport) -> AdminClient {
        let store = MemorySecretStore()
        return AdminClient(client: APIClient(
            transport: transport,
            session: AuthSession(store: store),
            configs: ServerConfigStore(store: store),
            refresher: MeKeyNoRefresh(),
            language: { "en-US" }
        ))
    }
}

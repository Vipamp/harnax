import Foundation
import HarnaxCore

/// The session panel's two legs, on the same client that already carries the queue's.
///
/// Router and admin are one surface on iOS: `HarnaxDependencies.live()` hangs `contextUsage`, `workspace` and
/// the rest on the same `AdminClient` instance, and `Endpoint.base` is what picks the host
/// (`HarnaxDependencies.swift:152-165`). So this extension adds the two `/api/router/agent/session-skills/**`
/// routes to the client that already answers `/api/admin/skill-drafts` (`SkillDraftClient.swift:11-22`), and the
/// merge gets both reads without a second transport.
///
/// What lives here is transport and reduction only. The rule for which read wins a field is
/// `SessionSkillRules.merged` and it exists once — the console's drawer merges the same two replies
/// (`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx`), so a second copy here would be a
/// second chance to disagree with it.
extension AdminClient: SessionSkillReading {
    /// Both reads, merged.
    ///
    /// The nominations come from the reviewer's queue scoped to this conversation
    /// (`GET /api/admin/skill-drafts?status=PENDING&sessionId=` — `SkillDraftEndpoint.page`, and `pageSize` 50 is
    /// the console's own ceiling for this drawer, one screen with no pagination on it), and the enabled set from
    /// `GET /api/router/agent/session-skills/{sessionId}`, which lists only what the session may already use
    /// (`SessionSkillController.list`) — hence `enabled: true` for every row that read supplies.
    ///
    /// A leg that fails is thrown, never answered with an empty half: a queue that would not load otherwise
    /// reads as a conversation whose agent proposed nothing, and that is the one thing this panel must not say
    /// about a failure. `SessionSkillsViewModel` turns the throw into `unavailable`.
    public func rows(sessionId: String) async throws -> [SessionSkillRow] {
        let nominations: [SessionSkillRules.Draft]
        switch await page(status: .pending, name: nil, sessionId: sessionId, num: 1, size: Self.nominatedPageSize) {
        case let .success(page):
            // A queue row with no name cannot be enabled — the name is the enable route's path segment — so it
            // drops out rather than becoming a row the panel offers a dead button for.
            nominations = page.records.compactMap { row in
                guard let name = hxPresented(row.name) else { return nil }
                return SessionSkillRules.Draft(name: name, description: hxPresented(row.description))
            }
        case let .failure(error):
            throw error
        }

        let directory: [SessionSkillRow]
        switch await client.send([SessionSkillViewRow].self, SessionSkillEndpoint.rows(sessionId: sessionId)) {
        case let .success(enabled):
            // `SessionSkillView` has no `enabled` key: presence in this list *is* the flag.
            directory = enabled.compactMap { row in
                guard let name = hxPresented(row.name) else { return nil }
                return SessionSkillRow(
                    name: name,
                    description: hxPresented(row.description),
                    enabled: true,
                    enabledAt: hxPresented(row.enabledAt)
                )
            }
        case let .failure(error):
            throw error
        }

        return SessionSkillRules.merged(drafts: nominations, enabled: directory)
    }

    /// `POST /api/router/agent/session-skills/{sessionId}/{name}/enable`, with no body: the router names the
    /// operator off its own auth context, so anything this side sent in a body would be a claim rather than a
    /// fact.
    ///
    /// A refusal the panel can explain arrives as `APIError.business(code:message:)` — HTTP 200 with the code
    /// inside the envelope (`APIError.swift:24-25`), which is how the whole stack reports a business failure —
    /// and becomes the `SessionSkillRefusal` whose `messageKey` says which of the five it was. Everything else,
    /// offline included, says only that the enable did not happen.
    public func enable(sessionId: String, name: String) async throws {
        let reply = await client.send(EmptyResponse.self, SessionSkillEndpoint.enable(sessionId: sessionId, name: name))
        guard case let .failure(error) = reply else { return }
        if case let .business(code, _) = error {
            throw SessionSkillRefusal(code: code)
        }
        throw SessionSkillRefusal(code: -1)
    }

    /// How many of this conversation's nominations the panel reads. The same 50 the console asks for, and enough
    /// that the ten-skill ceiling is reached long before the list is cut off.
    static let nominatedPageSize = 50
}

/// `SessionSkillView(name, description, enabledAt)`
/// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SessionSkillController.kt`),
/// as the router proxies it. `name` is a non-null Kotlin `String`, so a row arriving without one is a contract
/// break — and it drops out rather than becoming a row nobody can address, exactly as a nameless nomination does:
/// the name is the enable route's path segment, so there is no request left to make for it.
private struct SessionSkillViewRow: Decodable, Sendable {
    let name: String?
    let description: String?
    let enabledAt: String?
}

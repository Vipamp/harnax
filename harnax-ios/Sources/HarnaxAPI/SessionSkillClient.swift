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
    /// Each leg carries its own failure. The queue going down costs the nominations and nothing else, the
    /// directory going down costs the enabled answers and nothing else: a leg that did not answer hands the merge an
    /// empty list of its own and flips `unavailable`, because the rows the other leg did answer are still rows the
    /// operator can act on. What no leg may do is answer "this conversation's agent proposed nothing" on behalf of
    /// a failure — that is the one sentence this panel must not say about something that broke, and `unavailable`
    /// is what keeps an empty list from being read as it. Both legs failing is the single case where the empty list
    /// is the honest answer.
    ///
    /// Both go out before either is awaited: the two hosts are unrelated, and waiting on one to start the other
    /// makes the panel's open as slow as the sum of them.
    public func read(sessionId: String) async -> SessionSkillRead {
        async let nominations = nominationLeg(sessionId: sessionId)
        async let enabled = enabledLeg(sessionId: sessionId)
        let (drafts, directory) = await (nominations, enabled)
        return SessionSkillRead(
            rows: SessionSkillRules.merged(drafts: drafts.rows, enabled: directory.rows),
            unavailable: drafts.failed || directory.failed
        )
    }

    /// `GET /api/admin/skill-drafts?status=PENDING&sessionId=`, reduced to what the merge needs.
    ///
    /// A conversation id that trims to nothing is not asked about at all. `SkillDraftEndpoint.page` drops the
    /// `sessionId` key for a blank value, and a queue query without it is the reviewer's whole tenant — somebody
    /// else's nominations drawn under this conversation's title. Refusing to send is the only answer that cannot do
    /// that, and it hands the merge the same `(empty, failed)` pair any other leg failure does.
    private func nominationLeg(sessionId: String) async -> (rows: [SessionSkillRules.Draft], failed: Bool) {
        let scope = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !scope.isEmpty else { return ([], true) }
        switch await page(status: .pending, name: nil, sessionId: scope, num: 1, size: Self.nominatedPageSize) {
        case let .success(page):
            // A queue row with no name cannot be enabled — the name is the enable route's path segment — so it
            // drops out rather than becoming a row the panel offers a dead button for.
            let rows: [SessionSkillRules.Draft] = page.records.compactMap { row in
                guard let name = hxPresented(row.name) else { return nil }
                return SessionSkillRules.Draft(name: name, description: hxPresented(row.description))
            }
            return (rows, false)
        case .failure:
            return ([], true)
        }
    }

    /// The directory leg: `GET /api/router/agent/session-skills/{sessionId}`.
    private func enabledLeg(sessionId: String) async -> (rows: [SessionSkillRow], failed: Bool) {
        switch await client.send([SessionSkillViewRow].self, SessionSkillEndpoint.rows(sessionId: sessionId)) {
        case let .success(enabled):
            // `SessionSkillView` has no `enabled` key: presence in this list *is* the flag.
            let rows: [SessionSkillRow] = enabled.compactMap { row in
                guard let name = hxPresented(row.name) else { return nil }
                return SessionSkillRow(
                    name: name,
                    description: hxPresented(row.description),
                    enabled: true,
                    enabledAt: hxPresented(row.enabledAt)
                )
            }
            return (rows, false)
        case .failure:
            return ([], true)
        }
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

import Foundation

/// The conversation list's surface: one read and the four writes a row can take.
///
/// This is the *list* half of the chat tab. What a conversation says — the message stream, its history,
/// its plans and its workspace — belongs to the chat side and is reached over the runtime's own routes, so
/// nothing about those lives here.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:20`,
/// which maps the whole class at `/api/admin/sessions`. Four of the five methods below are therefore admin
/// calls on the bearer token plus tenant header, like every other catalog on `AdminClient`. The fifth,
/// `clearMessages`, is the odd one out and is documented at its own declaration.
///
/// The method names carry the domain because one `AdminClient` conforms to this and to `AgentCataloging`
/// and `TeamCataloging` at once, and a bare `page(status:)` with a different element type cannot sit beside
/// the other two.
///
/// Two identities are in play and each method names the one its route wants: `id` is the numeric primary key
/// the admin routes take (`SessionController.kt:94`, `:146`, `:166`), `sessionId` is the string business key
/// the runtime takes. Swapping them is not a compile error in either direction — a numeric id in a runtime
/// path simply addresses a conversation that does not exist.
public protocol SessionCataloging: Sendable {
    /// `keyword` is a `LIKE` over `title` and `status` the raw 0/1 column (`SessionController.kt:40-41`,
    /// applied at `mapper/SessionMapper.xml:108-116`). Both are optional on the wire, and the web console
    /// sends neither — it asks for one page of 100 and lets the server's `ORDER BY create_time DESC` stand
    /// (`harnax-webui/src/pages/session/index.tsx:40`, `SessionMapper.xml:117`) — but the filters are real,
    /// so this screen is allowed to use them. `size` is clamped to 1000 server-side
    /// (`service/impl/SessionServiceImpl.kt:66`).
    ///
    /// The result is already visibility-filtered to `active = 1 AND (is_public = 1 OR creator = <me>)`
    /// within the caller's tenant (`SessionMapper.xml:104-116`), so a row this returns is a row the caller
    /// may read; it does not follow that they may write it.
    func sessionPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SessionSummary>, APIError>

    /// Renames a conversation. Named for what the screen does rather than for the route, because the route
    /// is a whole-row update: `PUT /api/admin/sessions/update/{id}` takes the create DTO
    /// (`SessionController.kt:91-109`), loads the row, and writes the columns `updateById` names
    /// (`SessionMapper.xml:86-101`) — of which `title` is the only display column that changes
    /// (`SessionServiceImpl.kt:252`). See `SessionRenameRequest` for what the other body keys do not reach.
    ///
    /// Unlike the create route, this one does not police duplicate titles
    /// (`SessionServiceImpl.kt:164-168` guards creation only), so two conversations can end up sharing a
    /// name and the screen has to live with that.
    func renameSession(_ session: SessionSummary, to title: String) async -> Result<EmptyResponse, APIError>

    /// The enable/disable switch. `status` travels as a `0`/`1` query parameter with no body
    /// (`SessionController.kt:143-161`), and it is not cosmetic: a conversation switched off here is
    /// refused by the runtime's config read with its own 403 sentence
    /// (`SessionServiceImpl.kt:275-279`).
    func setSessionStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>

    /// Deletes a conversation. The server releases the runtime, reclaims the team artifacts and only then
    /// drops the row, and a failure in the first two steps refuses the whole delete
    /// (`SessionServiceImpl.kt:326-346`), so the envelope's message is the reason the row is still there.
    func deleteSession(id: Int64) async -> Result<EmptyResponse, APIError>

    /// Throws away a conversation's message history, leaving the row itself in place.
    ///
    /// This is the one method here that is not an admin call: it goes to `POST /api/router/agent/command`
    /// with `CLEAR` on the router base, which is what the web console does
    /// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:2693-2697`). Two routes can clear a
    /// session — the runtime also exposes `DELETE /api/router/agent/session/{sessionId}`
    /// (`AgentProxyController.kt:131-139`) — and the console never calls that one, so neither does this
    /// screen. The command's reply is a loose map rather than an envelope body (`AgentCommandReply`), and
    /// `CLEAR` answers success even when the session holds no history
    /// (`DefaultAgentRunner.kt:241-244`), so this method reports what the server said instead of inferring
    /// anything from it.
    func clearMessages(sessionId: String) async -> Result<AgentCommandReply, APIError>
}

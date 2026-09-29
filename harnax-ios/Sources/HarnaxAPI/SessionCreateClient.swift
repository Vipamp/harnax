import Foundation
import HarnaxCore

/// Creating a conversation: the duplicate-name check, the executor picker and the write.
///
/// All three are ordinary admin calls, so they ride the shared transport. The order the check and the write
/// have to keep is the sheet's business (`SessionCreateViewModel`), not this file's.
extension AdminClient: SessionCreating {
    /// Answers the server's own verdict rather than a local guess, because the lookup behind it is
    /// `WHERE title = ? AND active = 1` with no tenant and no creator condition (`harnax-entity/src/main/resources/mapper/SessionMapper.xml:44-46`): a
    /// name taken by another tenant's conversation is `true` here even though the list can never show it, and
    /// only this route says so.
    public func sessionTitleTaken(_ title: String) async -> Result<Bool, APIError> {
        await client.send(Bool.self, SessionEndpoint.checkTitle(title: title))
    }

    /// The two page routes the console loads when the form opens (`SettingsModal.tsx:64-86`), folded group by
    /// group rather than collapsed into one verdict: a broken team route has to leave the agents the account
    /// has, which is what `unavailableKinds` says. Only the case where neither group answered is reported as a
    /// failure, and it reports the agent one, since that is the group the picker leads with.
    public func executorChoices() async -> Result<SessionExecutorChoices, APIError> {
        let agents = await executorPage(kind: .agent)
        let teams = await executorPage(kind: .team)
        switch (agents, teams) {
        case (.success(let agentRows), .success(let teamRows)):
            return .success(SessionExecutorChoices(agents: agentRows, teams: teamRows))
        case (.success(let agentRows), .failure):
            return .success(SessionExecutorChoices(agents: agentRows, unavailableKinds: [.team]))
        case (.failure, .success(let teamRows)):
            return .success(SessionExecutorChoices(teams: teamRows, unavailableKinds: [.agent]))
        case (.failure(let error), .failure):
            return .failure(error)
        }
    }

    /// `ResultVo<Void>` (`SessionController.kt:80-89`): the reply names no new row, so the list has to be
    /// re-read to find what was created.
    public func createSession(_ draft: SessionCreateDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? SessionEndpoint.create(draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    /// One page of one group, the console's own size (`SettingsModal.tsx:70`, `:83`). A picker longer than that
    /// is a list the user scrolls off, and the create route re-resolves the id server-side anyway
    /// (`SessionServiceImpl.kt:197-215`).
    ///
    /// The agent branch keeps the console's local second pass over the records (`:74`). It looks redundant next
    /// to the `status: 1` query, and it is — for the page route. It is what makes the picker agree with
    /// `status == 1` rather than with `AgentSummary.isEnabled`, which reads any non-zero as enabled, and the
    /// team branch deliberately does not copy it: the console filters only the agent list.
    private func executorPage(kind: SessionExecutorKind) async -> Result<[SessionExecutorOption], APIError> {
        switch kind {
        case .agent:
            let page = await client.send(
                Page<AgentSummary>.self,
                AgentEndpoint.page(name: nil, status: 1, num: 1, size: Self.pickerLimit)
            )
            return page.map { rows in
                rows.records.compactMap { row in
                    guard row.status == 1, let id = row.id, let name = hxPresented(row.name) else { return nil }
                    return SessionExecutorOption(
                        kind: .agent, id: id, name: name, detail: hxPresented(row.description)
                    )
                }
            }
        case .team:
            let page = await client.send(
                Page<TeamSummary>.self,
                TeamEndpoint.page(name: nil, status: 1, num: 1, size: Self.pickerLimit)
            )
            return page.map { rows in
                rows.records.compactMap { row in
                    guard let id = row.id, let name = hxPresented(row.name) else { return nil }
                    return SessionExecutorOption(
                        kind: .team, id: id, name: name, detail: hxPresented(row.description)
                    )
                }
            }
        }
    }

    static let pickerLimit = 100
}

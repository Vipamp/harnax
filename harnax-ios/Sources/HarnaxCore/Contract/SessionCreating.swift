import Foundation

/// Who a conversation runs on. The two kinds share one picker in the web console and one column on the row
/// (`name`), and only this kind — later `teamId` — tells them apart (`SessionServiceImpl.kt:194-214`).
public enum SessionExecutorKind: String, Equatable, Sendable {
    case agent
    case team
}

/// One executor the create form may pick, as the two page routes answer it.
///
/// The web console keys its `executor` select with `"agent:<id>"` / `"team:<id>"`
/// (`harnax-webui/src/pages/session/components/SettingsModal.tsx:92-94`) because an agent and a team can share
/// a numeric id. iOS keeps the two parts apart instead, and `selectionKey` is only what the picker needs to
/// identify a row with — the body is built from `kind` and `id`, never by parsing that string back.
public struct SessionExecutorOption: Identifiable, Hashable, Sendable {
    public let kind: SessionExecutorKind
    public let id: Int64
    public let name: String
    /// The second half of the console's `${name} - ${description}` label
    /// (`SettingsModal.tsx:196-208`), absent when the row has no description.
    public let detail: String?

    public init(kind: SessionExecutorKind, id: Int64, name: String, detail: String? = nil) {
        self.kind = kind
        self.id = id
        self.name = name
        self.detail = detail
    }

    public var selectionKey: String { "\(kind.rawValue):\(id)" }
}

/// The two executor groups, kept apart because one of them can fail alone.
///
/// The console loads agents and teams in two independent `try` blocks and logs whichever fails
/// (`SettingsModal.tsx:64-86`), so a broken team route still leaves a usable agent list. Collapsing that into
/// one failure would hide a group the user has; `unavailableKinds` says which half is missing while the form
/// stays open. Both halves failing is the case the caller reports as an error, because there is then nothing
/// to pick.
public struct SessionExecutorChoices: Equatable, Sendable {
    public let agents: [SessionExecutorOption]
    public let teams: [SessionExecutorOption]
    public let unavailableKinds: [SessionExecutorKind]

    public init(
        agents: [SessionExecutorOption] = [],
        teams: [SessionExecutorOption] = [],
        unavailableKinds: [SessionExecutorKind] = []
    ) {
        self.agents = agents
        self.teams = teams
        self.unavailableKinds = unavailableKinds
    }

    public var options: [SessionExecutorOption] { agents + teams }
    public var isEmpty: Bool { options.isEmpty }

    /// Nothing can be created until at least one group answered.
    public var isUsable: Bool { !isEmpty }
}

/// The body of `POST /api/admin/sessions` — `SessionCreateRequest`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionCreateRequest.kt:11-31`).
///
/// Four keys and no more. `isPublic` is the web console's dead switch: it renders a `Switch`
/// (`SettingsModal.tsx:215-225`) that never reaches `createData` (`:95-101`), and the DTO has no such field
/// either, so iOS does not send one. `teamId` and `agentId` are mutually exclusive by the same submit code,
/// and for a team conversation the service leaves `agent_id` NULL on purpose (`SessionServiceImpl.kt:197-215`).
///
/// `sessionDescription` is `String = ""` on the DTO, so an empty string is a real answer rather than an
/// omission — the console sends the form value as-is, which is `undefined` for an untouched field and so
/// leaves the key off the JSON. This mirrors that: the key is only present with text.
public struct SessionCreateDraft: Encodable, Equatable, Sendable {
    public let title: String
    public let sessionDescription: String?
    public let agentId: Int64?
    public let teamId: Int64?

    public init(title: String, sessionDescription: String?, executor: SessionExecutorOption) {
        self.title = title
        self.sessionDescription = hxPresented(sessionDescription)
        agentId = executor.kind == .agent ? executor.id : nil
        teamId = executor.kind == .team ? executor.id : nil
    }

    /// The two rules the route's own `@Validated` enforces (`SessionController.kt:83` against
    /// `SessionCreateRequest.kt:13-15`): not blank, at most 100 characters. Answers `nil` for a draft that
    /// breaks either, since this is the one session write whose refusal the server *does* phrase readably and
    /// there is no reason to spend a round trip on it.
    public init?(validating title: String, sessionDescription: String?, executor: SessionExecutorOption) {
        guard let trimmed = hxPresented(title), trimmed.count <= Self.titleLimit else { return nil }
        self.init(title: trimmed, sessionDescription: sessionDescription, executor: executor)
    }

    public static let titleLimit = 100
    public static let descriptionLimit = 500
}

/// Creating a conversation: the duplicate check, the executor picker it needs, the write.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:68-89`.
/// This sits beside `SessionCataloging` rather than inside it because that protocol is the *list* surface —
/// five methods the row menu already answers — and adding members to it would break every stand-in of it
/// before this screen has a sheet to open.
///
/// The order of the two calls is the contract, not an optimisation: the console validates the title through
/// `check-title` before it will submit (`SettingsModal.tsx:33-51`, `:88`), and the create route re-checks the
/// same thing server-side (`SessionServiceImpl.kt:165-168`). So a taken name never reaches the POST here
/// either, and a check that itself failed blocks the submit rather than guessing.
public protocol SessionCreating: Sendable {
    /// `GET /api/admin/sessions/check-title?title=`, whose `ResultVo<Boolean>` is "already taken"
    /// (`SessionController.kt:68-78`). `true` means a caller may not use the name.
    ///
    /// Worth knowing before debugging a surprising answer: the count behind it is
    /// `WHERE title = ? AND active = 1` with no tenant and no creator condition (`harnax-entity/src/main/resources/mapper/SessionMapper.xml:44-46`),
    /// so a name that belongs to another tenant's conversation is claimed here even though the list can never
    /// show it.
    func sessionTitleTaken(_ title: String) async -> Result<Bool, APIError>

    /// The two page routes behind the executor picker, on the console's own terms: `status = 1`, one page of
    /// 100 each (`SettingsModal.tsx:69-71`, `:82`), plus the second local pass over the agent records
    /// (`filter(item => item.status === 1)`) that the console keeps even though it asked for the same filter.
    func executorChoices() async -> Result<SessionExecutorChoices, APIError>

    /// `POST /api/admin/sessions`. Answers `ResultVo<Void>` (`SessionController.kt:80-89`), so there is no new
    /// row id in the reply and the caller has to re-read the list to find what it created.
    func createSession(_ draft: SessionCreateDraft) async -> Result<EmptyResponse, APIError>
}

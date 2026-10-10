import Foundation

/// One conversation — the row of `GET /api/admin/sessions/page`, and the same shape
/// `GET /api/admin/sessions/{id}` and `GET /api/admin/sessions/{sessionId}/config` answer.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionResponse.kt:10-66` declares
/// one DTO for all three routes, and every route serialises it through the same
/// `SessionServiceImpl.convertToResponse` (`service/impl/SessionServiceImpl.kt:86-160`), so a list row
/// really does carry the model capabilities and the two binding lists — this is not a page shape that
/// happens to be reused for a detail read, it is one shape sent three times.
///
/// Like `AgentResponse`, every scalar here is declared `Long?`/`String?`/`Int?` with a `null` default, and
/// the stack serialises with `default-property-inclusion: non_null`
/// (`harnax-admin/src/main/resources/application.yml:25`), so a key may arrive as `null` or not at all.
/// `mcpList` and `skillList` are the two exceptions: they default to an empty collection
/// (`SessionResponse.kt:52-54`), so their keys always arrive and are modelled non-optional — the same rule
/// that makes `TeamSummary.skillList` non-optional. `Identifiable.ID` is `Int64?`, so a row the server
/// answered without an `id` still decodes.
///
/// The two identity columns are separate and not interchangeable: `id` is the numeric primary key the admin
/// routes address (`SessionController.kt:58`, `:94`, `:146`, `:166`), while `sessionId` is the string
/// business key (`web-<uuid>`, minted at `SessionServiceImpl.kt:193`) that the runtime and the chat side
/// address (`AgentProxyController.kt:131`, `:144`).
public struct SessionSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let title: String?
    public let sessionDescription: String?
    /// The string business key. Never a display fallback for a missing `title` on this screen's own rows —
    /// `title` is `NOT NULL` in the table (`db/migration/V1__init_schema.sql:457`).
    public let sessionId: String?
    public let agentId: Int64?
    /// Set only at creation and never rewritten by the update route (`SessionServiceImpl.kt:197`, `:244-260`).
    public let teamId: Int64?
    /// The executor's name, copied onto the row when the conversation was started so a later edit to the
    /// agent or team cannot rewrite what this chat already ran on (`SessionServiceImpl.kt:194-214`).
    public let name: String?
    public let description: String?
    public let systemPrompt: String?
    public let modelId: Int64?
    public let modelName: String?
    public let modelPrice: Double?
    public let modelSupportReasoning: Int?
    public let modelThinkingMode: Int?
    public let modelSupportInternet: Int?
    public let modelSupportVision: Int?
    public let enableThink: Int?
    public let enableSearch: Int?
    public let enablePlan: Int?
    public let permissionMode: String?
    public let mcpList: [SessionMcpItem]
    public let skillList: [SessionSkillItem]
    public let owner: String?
    public let status: Int?
    public let isPublic: Int?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?

    public init(
        id: Int64? = nil,
        title: String? = nil,
        sessionDescription: String? = nil,
        sessionId: String? = nil,
        agentId: Int64? = nil,
        teamId: Int64? = nil,
        name: String? = nil,
        description: String? = nil,
        systemPrompt: String? = nil,
        modelId: Int64? = nil,
        modelName: String? = nil,
        modelPrice: Double? = nil,
        modelSupportReasoning: Int? = nil,
        modelThinkingMode: Int? = nil,
        modelSupportInternet: Int? = nil,
        modelSupportVision: Int? = nil,
        enableThink: Int? = nil,
        enableSearch: Int? = nil,
        enablePlan: Int? = nil,
        permissionMode: String? = nil,
        mcpList: [SessionMcpItem] = [],
        skillList: [SessionSkillItem] = [],
        owner: String? = nil,
        status: Int? = nil,
        isPublic: Int? = nil,
        creator: String? = nil,
        createTime: String? = nil,
        updateTime: String? = nil
    ) {
        self.id = id
        self.title = title
        self.sessionDescription = sessionDescription
        self.sessionId = sessionId
        self.agentId = agentId
        self.teamId = teamId
        self.name = name
        self.description = description
        self.systemPrompt = systemPrompt
        self.modelId = modelId
        self.modelName = modelName
        self.modelPrice = modelPrice
        self.modelSupportReasoning = modelSupportReasoning
        self.modelThinkingMode = modelThinkingMode
        self.modelSupportInternet = modelSupportInternet
        self.modelSupportVision = modelSupportVision
        self.enableThink = enableThink
        self.enableSearch = enableSearch
        self.enablePlan = enablePlan
        self.permissionMode = permissionMode
        self.mcpList = mcpList
        self.skillList = skillList
        self.owner = owner
        self.status = status
        self.isPublic = isPublic
        self.creator = creator
        self.createTime = createTime
        self.updateTime = updateTime
    }

    /// A row with no status column reads as enabled: the column defaults to `1`
    /// (`db/migration/V1__init_schema.sql:470`).
    public var isEnabled: Bool { status != 0 }

    /// The only true meaning of this flag is the list query's own visibility rule —
    /// `active = 1 AND (is_public = 1 OR creator = <me>)` (`mapper/SessionMapper.xml:104-118`) — so it says
    /// "other accounts can see this conversation", not "shared by design": every row created from the web
    /// console is written with `is_public = 0` (`SessionServiceImpl.kt:234`).
    public var isShared: Bool { isPublic == 1 }

    /// A team conversation runs on the team's lead and has no agent row behind it
    /// (`SessionServiceImpl.kt:199-206`).
    public var isTeamConversation: Bool { teamId != nil }

    public var displayName: String? { hxPresented(title) }

    /// What the row shows under its title. The console's list row carries only a team tag and the title
    /// (`harnax-webui/src/pages/session/index.tsx:204-214`) and never renders this column, so showing it is
    /// this screen's own choice — the value is real, it is just not a port of a webui decision.
    public var detail: String? { hxPresented(sessionDescription) }

    /// The executor this conversation was started on. Both a team name and an agent name land in the same
    /// column, which is why the row needs `teamId` to tell them apart.
    public var executorName: String? { hxPresented(name) }

    /// Skill names only: the row's `skillList` carries no `skillAvailable` flag at all
    /// (`SessionResponse.kt:78-90`), so a list row cannot tell a live skill from a deleted one — only the
    /// agent or team detail read can, which is why nothing here claims to.
    public var skillNames: [String] { skillList.compactMap(\.displayName) }
    public var mcpNames: [String] { mcpList.compactMap(\.displayName) }
}

/// `mcpList[]` of `SessionResponse` (`SessionResponse.kt:68-76`) — resolved from the bound agent, so a team
/// conversation always answers an empty list rather than a missing one (`SessionServiceImpl.kt:128-138`).
public struct SessionMcpItem: Decodable, Equatable, Sendable {
    public let mcpId: Int64?
    public let mcpName: String?
    public let mcpDescription: String?

    public init(mcpId: Int64? = nil, mcpName: String? = nil, mcpDescription: String? = nil) {
        self.mcpId = mcpId
        self.mcpName = mcpName
        self.mcpDescription = mcpDescription
    }

    public var displayName: String? { hxPresented(mcpName) }
    public var detail: String? { hxPresented(mcpDescription) }
}

/// `skillList[]` of `SessionResponse` (`SessionResponse.kt:78-90`). A skill whose repository row is gone
/// answers without the two repository keys, and a skill that has since been deleted is dropped from the
/// list entirely rather than flagged (`SessionServiceImpl.kt:144-156`).
public struct SessionSkillItem: Decodable, Equatable, Sendable {
    public let repositoryId: Int64?
    public let repositoryName: String?
    public let skillId: Int64?
    public let skillName: String?
    public let skillDescription: String?

    public init(
        repositoryId: Int64? = nil,
        repositoryName: String? = nil,
        skillId: Int64? = nil,
        skillName: String? = nil,
        skillDescription: String? = nil
    ) {
        self.repositoryId = repositoryId
        self.repositoryName = repositoryName
        self.skillId = skillId
        self.skillName = skillName
        self.skillDescription = skillDescription
    }

    public var displayName: String? { hxPresented(skillName) }
    public var detail: String? { hxPresented(skillDescription) }
    public var repository: String? { hxPresented(repositoryName) }
}

/// The body of `PUT /api/admin/sessions/update/{id}`, which the backend types as `SessionCreateRequest`
/// (`SessionController.kt:91-109`, `dto/SessionCreateRequest.kt:11-31`).
///
/// Two things about this body are not guessable from the route name:
/// - `sessionDescription` has no effect. The service writes it to `session.description` — the copy of the
///   *executor's* description that creation put on the row (`SessionServiceImpl.kt:253`) — and `description`
///   is not one of the columns `updateById` writes (`harnax-entity/src/main/resources/mapper/SessionMapper.xml:86-101`),
///   while `session_description` is re-stored from the value the row was loaded with. So the conversation's
///   own description cannot be edited here at all: a sheet that offered the field would silently do nothing.
///   It is still sent, carrying whatever the row already says, so the body is a valid `SessionCreateRequest`
///   rather than a partial one.
/// - `teamId` is deliberately absent. The update route never writes that column
///   (`SessionServiceImpl.kt:244-260`, and `:197` is its only assignment), and naming an agent on a team
///   conversation is refused outright (`:249-251`), so sending it could only break the write.
public struct SessionRenameRequest: Encodable, Sendable {
    /// `title` is the only display column this route changes (`SessionServiceImpl.kt:252`). Its
    /// `@NotBlank`/`@Size` (`dto/SessionCreateRequest.kt:13-15`) do **not** run here — the update route's
    /// body is a plain `@RequestBody` (`controller/SessionController.kt:95`), unlike creation
    /// (`:83`), so a blank or over-long title reaches the database and fails as a column error.
    public let title: String
    public let sessionDescription: String
    /// Sent only when the row already names an agent; the server re-resolves it and refuses a rename whose
    /// agent no longer exists (`SessionServiceImpl.kt:257`).
    public let agentId: Int64?

    public init(title: String, sessionDescription: String, agentId: Int64?) {
        self.title = title
        self.sessionDescription = sessionDescription
        self.agentId = agentId
    }

    /// Builds the body from the row being renamed so the columns this route re-stores keep their values, and
    /// so the agent the row runs on is named rather than defaulted. Answers `nil` for a title that is blank
    /// once trimmed: nothing on the server would refuse it readably, so the screen refuses it first.
    public init?(renaming session: SessionSummary, to title: String) {
        guard let title = hxPresented(title) else { return nil }
        self.title = title
        self.sessionDescription = session.sessionDescription ?? ""
        self.agentId = session.agentId
    }
}

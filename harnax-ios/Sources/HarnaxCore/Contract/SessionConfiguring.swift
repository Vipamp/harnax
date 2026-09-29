import Foundation

/// What `GET /api/admin/sessions/{sessionId}/config` means, read off the same `SessionResponse` the list and
/// the detail read answer (`SessionResponse.kt:35-50`, serialised by the shared
/// `convertToResponse` at `SessionServiceImpl.kt:86-160`).
///
/// The mapping below is the console's own (`harnax-webui/src/pages/session/components/ChatWindow.tsx:718-737`)
/// and each default is a decision the server does not make for it:
///
/// - the three `enable*` columns are `Int?` 0/1, and `|| false` means an absent column reads as *off* rather
///   than as unknown.
/// - `permissionMode` is free text on the wire (`String?`); the console turns only null and blank into
///   `DEFAULT`.
/// - `modelSupport*` are compared with `!== 0`, which is true of a *missing* column: an absent capability flag
///   means "not disallowed", so the switch is enabled. That is the opposite polarity to `enable*` and the one
///   easiest to invert by accident.
/// - `modelThinkingMode` falls back off the **raw** `modelSupportReasoning` column, not off the derived
///   boolean: `config.modelSupportReasoning !== 0 ? 1 : 0` (`:726-727`).
/// - a `modelThinkingMode` of 2 forces thinking on whatever the `enableThink` column says (`:732-734`),
///   because the service refuses to store the opposite
///   (`SessionServiceImpl.kt:291-295`, `DefaultAgentRunner.kt:961-970`).
public struct SessionChatConfig: Equatable, Sendable {
    public var enableThink: Bool
    public var enableSearch: Bool
    public var enablePlan: Bool
    /// The one vocabulary shared with the chat tab: `VALID_PERMISSION_MODES`
    /// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:1052`)
    /// is both what the `/permission` command accepts and what this column stores, and the command's handler
    /// delegates to the same admin write (`:1029`).
    public var permissionMode: ChatPermissionMode
    public var modelSupportReasoning: Bool
    public var modelThinkingMode: Int
    public var modelSupportInternet: Bool
    public var modelSupportVision: Bool
    /// A team conversation labels its answers with the team's name, which is the row's `name` column
    /// (`ChatWindow.tsx:737`).
    public var leadLabel: String?

    public init(
        enableThink: Bool = false,
        enableSearch: Bool = false,
        enablePlan: Bool = false,
        permissionMode: ChatPermissionMode = .defaultMode,
        modelSupportReasoning: Bool = true,
        modelThinkingMode: Int = 1,
        modelSupportInternet: Bool = true,
        modelSupportVision: Bool = true,
        leadLabel: String? = nil
    ) {
        self.enableThink = enableThink
        self.enableSearch = enableSearch
        self.enablePlan = enablePlan
        self.permissionMode = permissionMode
        self.modelSupportReasoning = modelSupportReasoning
        self.modelThinkingMode = modelThinkingMode
        self.modelSupportInternet = modelSupportInternet
        self.modelSupportVision = modelSupportVision
        self.leadLabel = leadLabel
    }

    public init(from row: SessionSummary) {
        let supportsReasoning = row.modelSupportReasoning != 0
        let thinkingMode = row.modelThinkingMode ?? (supportsReasoning ? 1 : 0)
        self.init(
            // Mode 2 is the model's "thinking is required" setting (`ModelThinkingMode` on the model table),
            // and the console overrides the stored column with it rather than trusting the row.
            enableThink: row.enableThink == 1 || thinkingMode == 2,
            enableSearch: row.enableSearch == 1,
            enablePlan: row.enablePlan == 1,
            permissionMode: ChatPermissionMode(wireValue: row.permissionMode),
            modelSupportReasoning: supportsReasoning,
            modelThinkingMode: thinkingMode,
            modelSupportInternet: row.modelSupportInternet != 0,
            modelSupportVision: row.modelSupportVision != 0,
            leadLabel: row.isTeamConversation ? hxPresented(row.name) : nil
        )
    }

    /// Thinking cannot be offered as a switch at all: `!modelSupportReasoning` disables it, and mode 2 forbids
    /// turning it off (`ChatWindow.tsx:3506-3572`, `SessionServiceImpl.kt:291-295`).
    public var canToggleThink: Bool { modelSupportReasoning && modelThinkingMode != 2 }
    public var canToggleSearch: Bool { modelSupportInternet }
    public var thinkLockedOn: Bool { modelThinkingMode == 2 }
}

/// The body of `PUT /api/admin/sessions/{sessionId}/config` — `SessionChatUpdateRequest`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SessionChatUpdateRequest.kt:5-17`).
///
/// The service applies only the fields it finds non-null (`SessionServiceImpl.kt:288-300`), so a null here
/// means "leave that column alone". What makes this type worth writing by hand is the interaction with
/// Kotlin: the three flags are declared `Boolean? = false`, and the stack registers
/// `jackson-module-kotlin` (`harnax-admin/pom.xml:233`), whose value instantiator fills an *absent* property
/// with its default — so omitting `enableThink` from the JSON would arrive as `false`, not `null`, and would
/// silently switch thinking off.
///
/// The encoder therefore always writes all four keys, with explicit `null` for the ones that did not change:
/// explicit null is null under both readings (default-filling and plain), and an unchanged field is never
/// mistaken for a write.
public struct SessionChatChange: Encodable, Equatable, Sendable {
    public let enableThink: Bool?
    public let enableSearch: Bool?
    public let enablePlan: Bool?
    public let permissionMode: String?

    public init(
        enableThink: Bool?,
        enableSearch: Bool?,
        enablePlan: Bool?,
        permissionMode: String?
    ) {
        self.enableThink = enableThink
        self.enableSearch = enableSearch
        self.enablePlan = enablePlan
        self.permissionMode = permissionMode
    }

    /// Nothing to write. The screen keeps its save button off for this rather than sending a body that would
    /// change no column.
    public var isEmpty: Bool {
        enableThink == nil && enableSearch == nil && enablePlan == nil && permissionMode == nil
    }

    /// The diff of the draft against what was read. Answers `nil` when none of the four writable fields moved,
    /// so a call that would have written nothing never reaches the socket. Compared field by field rather than
    /// by `stored != draft`, because the two configs also carry read-only capabilities that a draft cannot
    /// change and that must not look like a pending write.
    public init?(stored: SessionChatConfig, draft: SessionChatConfig) {
        let think: Bool? = stored.enableThink == draft.enableThink ? nil : draft.enableThink
        let search: Bool? = stored.enableSearch == draft.enableSearch ? nil : draft.enableSearch
        let plan: Bool? = stored.enablePlan == draft.enablePlan ? nil : draft.enablePlan
        let mode: String? = stored.permissionMode == draft.permissionMode ? nil : draft.permissionMode.wireValue
        guard think != nil || search != nil || plan != nil || mode != nil else { return nil }
        self.init(enableThink: think, enableSearch: search, enablePlan: plan, permissionMode: mode)
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        switch enableThink {
        case let .some(flag): try box.encode(flag, forKey: .enableThink)
        case .none: try box.encodeNil(forKey: .enableThink)
        }
        switch enableSearch {
        case let .some(flag): try box.encode(flag, forKey: .enableSearch)
        case .none: try box.encodeNil(forKey: .enableSearch)
        }
        switch enablePlan {
        case let .some(flag): try box.encode(flag, forKey: .enablePlan)
        case .none: try box.encodeNil(forKey: .enablePlan)
        }
        switch permissionMode {
        case let .some(text): try box.encode(text, forKey: .permissionMode)
        case .none: try box.encodeNil(forKey: .permissionMode)
        }
    }

    private enum Key: String, CodingKey {
        case enableThink, enableSearch, enablePlan, permissionMode
    }
}

/// The one conversation write that is not the row's title or status: its chat configuration.
///
/// Backend: `SessionController.kt:112-141`, both routes addressed by the **string** business key
/// (`sessionId`), unlike the rename, the toggle and the delete, which take the numeric id.
///
/// Three answers have to stay three states on screen, because the remedies differ:
///
/// - a conversation that is not there, or not in the caller's tenant, is an envelope `code = 404`
///   (`SessionServiceImpl.kt:270-275` returns null and the controller turns that into
///   `ResultVo.error(404, …)` at `SessionController.kt:114-116`);
/// - one that exists but is switched off is an envelope `code = 403` with the server's own sentence
///   (`SessionServiceImpl.kt:276-279`). Note this is HTTP 200 carrying a business 403, so it does **not**
///   reach the transport's `403 → unauthorized` rule (`Transport/ResponseMapper.swift:10-12`);
/// - `code = 200` with no `data` at all is `unpackable`, which is neither of the above and says the stack
///   answered something this side cannot read.
///
/// The write has no body in the reply (`ResultVo<Void>`, spec 未确认 #4), so a saved change has to be re-read
/// before the screen can show it.
public protocol SessionConfiguring: Sendable {
    func sessionConfig(sessionId: String) async -> Result<SessionSummary, APIError>

    /// Sends only what changed. See `SessionChatChange` for why the unchanged keys go out as explicit nulls
    /// instead of being left off.
    ///
    /// This route is the one the web console never calls: it sets the same four columns through
    /// `POST /api/router/agent/command` (`ChatWindow.tsx:2408-2430`), which additionally refuses a permission
    /// change on a `task-` conversation and invalidates the runtime's cached agent copy
    /// (`DefaultAgentRunner.kt:1014-1040`). Neither happens here. The chat tab's own switches keep going
    /// through the command channel; this sheet is the admin-side editor the route exists for.
    func updateSessionConfig(sessionId: String, _ change: SessionChatChange) async -> Result<EmptyResponse, APIError>
}

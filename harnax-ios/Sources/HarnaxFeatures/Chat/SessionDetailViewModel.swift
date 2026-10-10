import Foundation
import HarnaxCore
import HarnaxKit

/// A pill on a detail row.
///
/// The two shapes are the two the app already draws: catalogue copy in a tone (`HXBadge`) and a word that
/// came from the server (`HXChip`). A mark carries either a key or resolved text precisely so the copy stays
/// in the catalogue while a name does not get a key it cannot have.
public enum SessionDetailMark: Equatable, Sendable {
    case badge(key: String, tone: PaletteSlot)
    case chip(text: String, tone: PaletteSlot?)
}

/// How a row's value sits next to its label.
public enum SessionDetailLayout: Equatable, Sendable {
    /// Label left, value right — the ordinary description item.
    case inlineValue
    /// Label above the value, for copy that would be truncated in a trailing slot.
    case stackedValue
    /// The string business key: monospaced and handed to the pasteboard.
    case code
    /// The system prompt, with the console's four-line ellipsis and its expand control.
    case paragraph(expandable: Bool)
}

/// One line of the detail sheet.
///
/// A field names itself with catalogue copy (`labelKey`); an entry in the skill or MCP list names itself with
/// the word the server sent (`labelKey == nil`) and puts its description under it. Both shapes fit one row
/// because the sheet renders the same group card either way.
public struct SessionDetailRow: Equatable, Sendable {
    public let labelKey: String?
    public let value: String?
    public let detail: String?
    public let marks: [SessionDetailMark]
    public let layout: SessionDetailLayout

    public init(
        labelKey: String?,
        value: String?,
        detail: String? = nil,
        marks: [SessionDetailMark] = [],
        layout: SessionDetailLayout = .inlineValue
    ) {
        self.labelKey = labelKey
        self.value = value
        self.detail = detail
        self.marks = marks
        self.layout = layout
    }
}

public struct SessionDetailSection: Equatable, Sendable {
    public let titleKey: String
    public let rows: [SessionDetailRow]

    public init(titleKey: String, rows: [SessionDetailRow]) {
        self.titleKey = titleKey
        self.rows = rows
    }
}

/// What the executor's own row says the conversation can use, from `GET /api/admin/agents/{id}` or
/// `GET /api/admin/teams/{id}`.
///
/// The two cases are mutually exclusive the way the backend makes them: a team conversation leaves
/// `agent_id` NULL because there is no lead agent row to point at (`SessionServiceImpl.kt:199-207`), so a
/// row either names an agent or names a team.
///
/// `nil` means this sheet has not got that row — nothing was read because the host wired no leg, the read is
/// still on the wire, or it came back refused. The panels that only the executor's row can fill stay absent
/// rather than appearing empty: a conversation with no tools and a conversation whose tools could not be
/// read are two different claims, and this app refuses to make the second one look like the first
/// (`AgentCataloging.relatedSessions` carries the same rule).
public enum SessionExecutorBindings: Equatable, Sendable {
    case agent(AgentSummary)
    case team(TeamSummary)
}

/// Turns a `SessionSummary` row, plus the executor's own row when there is one, into the panels the console's
/// DetailModal shows — nothing more.
///
/// The two rows are two different reads, and the console keeps them apart for the same reason this presenter
/// does: the conversation row carries a snapshot of who it runs on (name, model, prompt, and the MCP and
/// skill bindings it can reach through `agentId`, `SessionServiceImpl.kt:126-158`), while the executor's own
/// row is the only thing that has tools, CLI packages and members at all. The console fires the by-id request
/// the moment the modal opens and shows a spinner in each of those panels until it answers
/// (`DetailModal.tsx:112-148`); here the snapshot panels never wait for it — the three that live only on the
/// executor's row appear when it answers, and the skill and MCP lists re-source from it.
///
/// The by-id row is also the only place either app can find 失效: a deleted skill drops out of the
/// conversation's `skillList` (`SessionServiceImpl.kt:145`) but stays in the team's own list flagged
/// `skillAvailable = false`, and a deleted member stays flagged `agentAvailable = false`
/// (`TeamServiceImpl.kt:181-203`). So a team conversation shows both badges and an agent conversation shows
/// neither — `AgentResponse.SkillItem` has no availability column to read
/// (`AgentResponse.kt:106-121`), which is why the web draws none there either.
///
/// A value neither row carries produces no row: the console prints `None`/`Unknown` placeholders
/// (`DetailModal.tsx:206-219`), and this screen hides the line instead.
public enum SessionDetailPresenter {
    /// The console's `ellipsis={{ rows: 4 }}` (`DetailModal.tsx:271`).
    public static let promptLineLimit = 4

    public static func sections(
        for session: SessionSummary,
        bindings: SessionExecutorBindings?,
        isPromptExpanded: Bool = false,
        chinese: Bool
    ) -> [SessionDetailSection] {
        var panels: [SessionDetailSection] = []
        if let basic = basicSection(session) { panels.append(basic) }
        if let executor = executorSection(session, expanded: isPromptExpanded) { panels.append(executor) }
        if let tools = toolSection(bindings, chinese: chinese) { panels.append(tools) }
        if let mcp = mcpSection(session, bindings: bindings) { panels.append(mcp) }
        if let skills = skillSection(session, bindings: bindings) { panels.append(skills) }
        if let packages = cliSection(bindings) { panels.append(packages) }
        if let members = memberSection(bindings) { panels.append(members) }
        return panels
    }

    // MARK: - basic

    private static func basicSection(_ session: SessionSummary) -> SessionDetailSection? {
        var rows: [SessionDetailRow] = []
        if let key = hxPresented(session.sessionId) {
            rows.append(SessionDetailRow(labelKey: "chat.detail.field.sessionId", value: key, layout: .code))
        }
        append("chat.detail.field.title", session.displayName, to: &rows)
        append("chat.detail.field.sessionDescription", session.detail, layout: .stackedValue, to: &rows)
        // The row carries the flag, not the word: only a public conversation gets a line, because a chip
        // reading "Private" says nothing the sheet does not already show (`SessionListView`'s badge row).
        if session.isShared {
            rows.append(SessionDetailRow(
                labelKey: "chat.detail.field.visibility",
                value: nil,
                marks: [.badge(key: "state.badge.shared", tone: .teal)]
            ))
        }
        append("chat.detail.field.owner", session.owner, to: &rows)
        append("chat.detail.field.creator", session.creator, to: &rows)
        append("chat.detail.field.createTime", session.createTime, to: &rows)
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.basic", rows: rows)
    }

    // MARK: - executor

    /// A team conversation runs on the team's lead, so the panel is named for the lead
    /// (`DetailModal.tsx:224-229`); both kinds read the same three columns, which is why the labels do not
    /// switch with it.
    private static func executorSection(_ session: SessionSummary, expanded: Bool) -> SessionDetailSection? {
        var rows: [SessionDetailRow] = []
        append(session.isTeamConversation ? "chat.detail.field.leadName" : "chat.detail.field.agentName",
               session.executorName, to: &rows)
        append("chat.detail.field.executorDescription", session.description, layout: .stackedValue, to: &rows)
        append("chat.detail.field.model", hxPresented(session.modelName), to: &rows)
        if let price = priceText(session.modelPrice) {
            append("chat.detail.field.modelPrice", price, to: &rows)
        }
        if let prompt = promptText(session.systemPrompt, expanded: expanded) {
            rows.append(SessionDetailRow(
                labelKey: "chat.detail.field.systemPrompt",
                value: prompt,
                layout: .paragraph(expandable: promptNeedsExpansion(session.systemPrompt))
            ))
        }
        let titleKey = session.isTeamConversation ? "chat.detail.section.teamLead" : "chat.detail.section.executor"
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: titleKey, rows: rows)
    }

    // MARK: - tools

    /// Only the agent's row has tools at all — `SessionResponse` carries no such list, and the console's
    /// panel is fed from `getAgentById` (`DetailModal.tsx:297-336`). A team conversation gets no panel because
    /// a team binds no tools: the console hides the panel outright (`:282`), which is the same reason the
    /// read is never fired for one.
    ///
    /// The naming and mark rules are the card drill-down's (`AgentBindingsPresenter.sections`): the same three
    /// name columns in the same order, the same `Tool #<id>` fallback and the same catalogue keys, the same
    /// "a mark only appears when the binding carries that attribute". Mirrored rather than called, because that
    /// presenter emits untitled string badges and the card's own section names, while this sheet's rows carry a
    /// tone per mark — so a rule changed there has to be changed here too.
    private static func toolSection(_ bindings: SessionExecutorBindings?, chinese: Bool) -> SessionDetailSection? {
        guard case let .agent(agent) = bindings else { return nil }
        let rows = (agent.toolList ?? []).compactMap { tool -> SessionDetailRow? in
            var marks: [SessionDetailMark] = []
            if tool.requiresConfirmation {
                marks.append(.badge(key: "agent.binding.confirm", tone: .warning))
            }
            if tool.envCount > 0 {
                marks.append(.chip(text: hxCount("env.count", tool.envCount), tone: nil))
            }
            let name = tool.name(chinese: chinese) ?? fallbackName("agent.binding.toolFallback", tool.toolId)
            return SessionDetailRow(labelKey: nil, value: name, detail: tool.description, marks: marks)
        }
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.tools", rows: rows)
    }

    // MARK: - cli

    /// Same shape as the tools panel: only an agent binds CLI packages, and only its own row lists them
    /// (`DetailModal.tsx:449-502`). The one skill the package ships rides as a chip, the way the card's
    /// drill-down shows it (`AgentServiceImpl.kt:376-390`).
    private static func cliSection(_ bindings: SessionExecutorBindings?) -> SessionDetailSection? {
        guard case let .agent(agent) = bindings else { return nil }
        let rows = (agent.cliList ?? []).compactMap { cli -> SessionDetailRow? in
            var marks: [SessionDetailMark] = []
            if let version = cli.packageVersion {
                marks.append(.chip(text: version, tone: nil))
            }
            if cli.envCount > 0 {
                marks.append(.chip(text: hxCount("env.count", cli.envCount), tone: nil))
            }
            if !cli.skillNames.isEmpty {
                marks.append(.chip(text: cli.skillNames.joined(separator: " · "), tone: nil))
            }
            let name = cli.name ?? fallbackName("agent.binding.unnamed", cli.cliId)
            return SessionDetailRow(labelKey: nil, value: name, detail: cli.description, marks: marks)
        }
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.cli", rows: rows)
    }

    // MARK: - members

    /// The people the lead can delegate to, in the order it sees them. This panel is the one thing a team
    /// conversation has and an agent conversation cannot: a member only exists on the team's own row
    /// (`DetailModal.tsx:505-561`).
    ///
    /// A member whose agent row has since gone stays in the list, named `#<id>` by the backend and flagged
    /// `agentAvailable = false` (`TeamServiceImpl.kt:193-203`). Hiding it would be the lie the console warns
    /// about, so the row is drawn with the 失效 badge instead.
    private static func memberSection(_ bindings: SessionExecutorBindings?) -> SessionDetailSection? {
        guard case let .team(team) = bindings else { return nil }
        let rows = team.memberList.compactMap { member -> SessionDetailRow? in
            var marks: [SessionDetailMark] = []
            if member.isUnavailable {
                marks.append(.badge(key: "chat.detail.badge.unavailable", tone: .danger))
            }
            // A member's delegation note wins over its own description, the rule the team list already
            // applies (`TeamSummary.swift:54-56`).
            if let name = member.displayName {
                return SessionDetailRow(labelKey: nil, value: name, detail: member.detail, marks: marks)
            }
            guard let detail = member.detail else { return nil }
            return SessionDetailRow(labelKey: nil, value: detail, marks: marks)
        }
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.members", rows: rows)
    }

    // MARK: - skills

    /// Skills come from whichever row is authoritative. The team's own list is the only one that can say a
    /// binding has gone dead, so a team conversation reads it from there and picks up the失效 badges; an
    /// agent conversation reads its agent's list, where the DTO has no availability column to badge
    /// (`AgentResponse.kt:106-121`); before either answer arrives — and for a row that names no executor,
    /// which is the console's own third branch (`DetailModal.tsx:146-147`) — the conversation snapshot stands.
    private static func skillSection(
        _ session: SessionSummary,
        bindings: SessionExecutorBindings?
    ) -> SessionDetailSection? {
        let rows: [SessionDetailRow]
        switch bindings {
        case let .team(team):
            rows = team.skillList.compactMap {
                skillRow(name: $0.displayName, detail: $0.detail, repository: $0.repository, dead: $0.isUnavailable)
            }
        case let .agent(agent):
            rows = (agent.skillList ?? []).compactMap {
                skillRow(name: $0.name, detail: $0.description, repository: $0.repository, dead: nil)
            }
        case nil:
            // The snapshot has no availability column: a skill whose row was deleted never reached this list
            // at all (`SessionServiceImpl.kt:145`), so `dead` stays nil and says no more than the row knows.
            rows = session.skillList.compactMap {
                skillRow(name: $0.displayName, detail: $0.detail, repository: $0.repository, dead: nil)
            }
        }
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.skills", rows: rows)
    }

    /// One skill line, whichever row it came from.
    ///
    /// A skill the row never names keeps its description as its title rather than disappearing: the binding is
    /// real even when the name column is blank. `dead: nil` means "this row does not say", which renders no
    /// badge.
    private static func skillRow(
        name: String?,
        detail: String?,
        repository: String?,
        dead: Bool?
    ) -> SessionDetailRow? {
        var marks: [SessionDetailMark] = []
        if dead == true {
            marks.append(.badge(key: "chat.detail.badge.unavailable", tone: .danger))
        }
        if let repository {
            marks.append(.chip(text: repository, tone: nil))
        }
        if let name {
            return SessionDetailRow(labelKey: nil, value: name, detail: detail, marks: marks)
        }
        guard let detail else { return nil }
        return SessionDetailRow(labelKey: nil, value: detail, marks: marks)
    }

    // MARK: - mcp

    /// An agent's own list wins as soon as it is in: it says the same thing the snapshot does and adds each
    /// server's parameter count, which is the console's expandable environment table
    /// (`DetailModal.tsx:37-90`, `:381`). A team conversation reads none — a team binds no MCP server, and the
    /// backend leaves the snapshot empty for exactly that reason (`SessionServiceImpl.kt:129-138`).
    private static func mcpSection(
        _ session: SessionSummary,
        bindings: SessionExecutorBindings?
    ) -> SessionDetailSection? {
        let rows: [SessionDetailRow]
        switch bindings {
        case let .agent(agent):
            rows = (agent.mcpList ?? []).compactMap { server -> SessionDetailRow? in
                var marks: [SessionDetailMark] = []
                if server.envCount > 0 {
                    marks.append(.chip(text: hxCount("env.count", server.envCount), tone: nil))
                }
                let name = server.name ?? hx("agent.binding.unnamed")
                return SessionDetailRow(labelKey: nil, value: name, detail: server.description, marks: marks)
            }
        case .team:
            rows = []
        case nil:
            rows = session.mcpList.compactMap { server -> SessionDetailRow? in
                if let name = server.displayName {
                    return SessionDetailRow(labelKey: nil, value: name, detail: server.detail)
                }
                guard let detail = server.detail else { return nil }
                return SessionDetailRow(labelKey: nil, value: detail)
            }
        }
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.mcp", rows: rows)
    }

    // MARK: - formatting

    /// The console falls back to `Tool #<id>` when every name column is blank
    /// (`harnax-webui/src/pages/agent/index.tsx:141`), and to a placeholder when even the id is missing. The
    /// agent card's drill-down already words this, so both screens read the same two keys rather than saying
    /// it twice.
    private static func fallbackName(_ key: String, _ id: Int64?) -> String {
        guard let id else { return hx("agent.binding.unnamed") }
        return hx(key, Int(id))
    }

    /// `¥{n}/M`, the console's shape for `modelPrice` (`DetailModal.tsx:262`). The number keeps whole units
    /// whole — a `2.0` price reads `¥2/M`, the way the web prints the JSON number — and a price the row does
    /// not carry renders no line at all rather than the console's "Not configured".
    public static func priceText(_ price: Double?) -> String? {
        guard let number = priceNumber(price) else { return nil }
        return hx("chat.detail.price", number)
    }

    /// The number as the web prints a JSON number: no trailing zeros on a whole price, no invented
    /// decimals on a fractional one. Split out of `priceText` because a catalogue that has not merged yet
    /// echoes its own key, which would let the trimming rule pass without being checked.
    public static func priceNumber(_ price: Double?) -> String? {
        guard let price, price.isFinite else { return nil }
        return numberText(price)
    }

    private static func numberText(_ value: Double) -> String {
        var text = Substring(String(format: "%.6f", value))
        while text.last == "0" { text = text.dropLast() }
        if text.last == "." { text = text.dropLast() }
        return String(text)
    }

    /// The first four lines, with an ellipsis, until the row is expanded.
    public static func promptText(_ raw: String?, expanded: Bool) -> String? {
        guard let prompt = hxPresented(raw) else { return nil }
        if expanded { return prompt }
        let lines = prompt.components(separatedBy: .newlines)
        guard lines.count > promptLineLimit else { return prompt }
        return lines.prefix(promptLineLimit).joined(separator: "\n") + "…"
    }

    /// Whether the prompt has more lines than the collapsed block shows.
    ///
    /// The console measures *wrapped* lines; a sheet with no layout cannot, so a single unbroken paragraph
    /// that reflows past four lines keeps its expand control to the web's absence of one. Logical lines are
    /// the only measure available here and saying so is better than pretending to wrap.
    public static func promptNeedsExpansion(_ raw: String?) -> Bool {
        guard let prompt = hxPresented(raw) else { return false }
        return prompt.components(separatedBy: .newlines).count > promptLineLimit
    }

    private static func append(
        _ labelKey: String,
        _ value: String?,
        layout: SessionDetailLayout = .inlineValue,
        to rows: inout [SessionDetailRow]
    ) {
        guard let value = hxPresented(value) else { return }
        rows.append(SessionDetailRow(labelKey: labelKey, value: value, layout: layout))
    }
}

/// The detail sheet's whole state: the row it presents, the executor's own row it reads for the three panels
/// the snapshot cannot fill, whether the prompt is open, and the one write this screen can make.
///
/// What the row already carries needs no fetch: every field of the basic and executor panels, the four
/// configuration columns and the four model capability columns are on it, because all three session routes
/// serialise one DTO through the same `convertToResponse`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt:86-160`,
/// `Sources/HarnaxCore/Contract/SessionSummary.swift:3-10`).
///
/// What it does not carry is the reason this screen has a load at all. Tools, CLI packages and members have no
/// columns on a session row, and the conversation's own two lists are a copy that loses a deleted binding
/// instead of flagging it (`ExecutorReading.swift:8-27`). The console reaches the same conclusion the same
/// way — one by-id request as the modal opens, a spinner in those panels until it answers
/// (`DetailModal.tsx:112-148`) — and so does this sheet, through the `ExecutorReading` leg.
///
/// Two reads, then, and neither blocks the other: the snapshot panels draw at once, the three that need the
/// executor's row appear when it lands, and a refusal says so on one line rather than leaving the sheet to
/// imply the conversation has no tools. The configuration editor is the only part that writes, and it reads
/// again only to confirm a write it just made.
@MainActor
public final class SessionDetailViewModel: ObservableObject {
    /// Where the executor read stands.
    public enum ExecutorPhase: Equatable {
        /// Nothing to read and nothing to show: the row names no executor, or this host wired no leg. The
        /// snapshot panels are the whole sheet, and no line says more than that.
        case none
        case loading
        case loaded
        /// The read came back refused. `nil` is not an option here: an answer that did not arrive is not the
        /// same claim as an executor with no tools.
        case failed(APIError)
    }

    public let session: SessionSummary

    /// The admin config leg. Absent means this host cannot write a conversation's configuration, which leaves
    /// the panel read-only rather than offering a save that cannot land — the same optional-dependency shape
    /// `SessionListView` uses for the create, workspace and artifact legs.
    private let configWriter: (any SessionConfiguring)?

    /// The executor read. Absent means the three panels only it can fill stay off the sheet, which is what the
    /// same optional-dependency shape is for: a host that lists conversations need not stand in for an agent row.
    private let executorReader: (any ExecutorReading)?

    @Published public var isPromptExpanded = false

    /// The executor's own row, once it is in. `nil` while it is not, which is what keeps the snapshot panels
    /// authoritative for the skills and MCP lists rather than an empty answer wiping them.
    @Published public private(set) var bindings: SessionExecutorBindings?
    @Published public private(set) var executorPhase: ExecutorPhase = .none

    /// What the server last said about the four writable fields. Starts as the row's own columns and is
    /// replaced only by a read-back that confirms a write.
    @Published public private(set) var stored: SessionChatConfig
    /// The draft being edited, and `nil` while the panel is in its reading state.
    @Published public private(set) var draft: SessionChatConfig?
    @Published public private(set) var isSavingConfig = false
    /// The panel's own line: the server's refusal, or the sentence that says what a landed write left behind.
    @Published public private(set) var configNotice: String?

    /// Says the word to the host once a write has landed. The row this sheet was built from is a snapshot of a
    /// page read, and the server has just replaced four of its columns, so a list still holding that row shows
    /// a conversation the sheet has moved on from. What the host refreshes is its own business.
    private let onWritten: (() -> Void)?

    /// The tool panel picks a name column per language, the way the agent card's drill-down does
    /// (`AgentBindingsSheet`). Read at call time so a language picked inside the app reaches the next render.
    private var prefersChinese: Bool { HarnaxCatalog.shared.language.prefersChinese }

    public init(
        session: SessionSummary,
        config: (any SessionConfiguring)? = nil,
        executor: (any ExecutorReading)? = nil,
        onWritten: (() -> Void)? = nil
    ) {
        self.session = session
        self.configWriter = config
        self.executorReader = executor
        self.onWritten = onWritten
        self.stored = SessionChatConfig(from: session)
    }

    /// The panels in the console's order, with the empty ones dropped.
    public var sections: [SessionDetailSection] {
        SessionDetailPresenter.sections(
            for: session,
            bindings: bindings,
            isPromptExpanded: isPromptExpanded,
            chinese: prefersChinese
        )
    }

    /// A row with nothing to say. Reachable: every column of `SessionSummary` is optional, so a row the
    /// server answered with only an `id` has no panel to open.
    public var hasNothingToShow: Bool { sections.isEmpty }

    /// The string business key the runtime routes address — the one value on this sheet worth copying, and the
    /// only address the two config routes take (`SessionController.kt:111-141`).
    public var businessKey: String? { hxPresented(session.sessionId) }

    /// The footer the console keeps outside the collapse (`DetailModal.tsx:564-567`). It is not a section, so
    /// `updateTime` reaches the sheet exactly once.
    public var updatedLine: String? {
        guard let time = hxPresented(session.updateTime) else { return nil }
        return hx("chat.detail.updated", time)
    }

    public func togglePrompt() {
        isPromptExpanded.toggle()
    }

    // MARK: - the executor's own row

    /// Which row to read, in the console's own order: a team first, then an agent, then nothing
    /// (`DetailModal.tsx:114-147`). Both would never be set — a team conversation leaves `agent_id` NULL
    /// (`SessionServiceImpl.kt:199-207`) — so the order only decides what a hand-built row says.
    private enum ExecutorTarget {
        case agent(Int64)
        case team(Int64)
    }

    private var executorTarget: ExecutorTarget? {
        if let teamId = session.teamId { return .team(teamId) }
        if let agentId = session.agentId { return .agent(agentId) }
        return nil
    }

    /// Whether the sheet has a read of its own to make. False covers both honest silences — no leg wired, and
    /// a row that names no executor — and the view draws no line for either.
    public var canReadExecutor: Bool { executorReader != nil && executorTarget != nil }

    /// Fires the by-id read the console fires when its modal opens. Safe to call twice: a sheet that reappears
    /// after a background stint must not put a second read on the wire, and one that already has the row has
    /// nothing left to ask.
    ///
    /// A refusal keeps the panels off rather than showing them empty. That is the difference the console
    /// collapses by clearing its lists in its `catch` (`DetailModal.tsx:126`, `:141`) and printing "No tool
    /// configuration" — on this screen that sentence would read as "this agent binds no tools", which is a
    /// claim about the conversation the sheet has no business making.
    public func loadExecutor() async {
        guard canReadExecutor, executorPhase != .loading, executorPhase != .loaded else { return }
        guard let target = executorTarget, let reader = executorReader else { return }
        executorPhase = .loading
        let read: Result<SessionExecutorBindings, APIError>
        switch target {
        case let .agent(id):
            read = await reader.agent(id: id).map { .agent($0) }
        case let .team(id):
            read = await reader.team(id: id).map { .team($0) }
        }
        switch read {
        case let .success(row):
            bindings = row
            executorPhase = .loaded
        case let .failure(error):
            bindings = nil
            executorPhase = .failed(error)
        }
    }

    /// The line under the panels when the read was refused, in the server's own words where it has them.
    public var executorNotice: String? {
        guard case let .failed(error) = executorPhase else { return nil }
        return ErrorMessage.text(for: error)
    }

    /// Whether the sheet is waiting on the read, which is when it draws a quiet progress line where the three
    /// panels will appear.
    public var isLoadingExecutor: Bool { executorPhase == .loading }

    /// Puts the read back on the wire after a refusal.
    public func retryExecutorLoad() async {
        guard case .failed = executorPhase else { return }
        executorPhase = .none
        await loadExecutor()
    }

    // MARK: - chat configuration

    /// The one of the two the panel draws: the draft while it is being edited, the confirmed values otherwise.
    public var displayedConfig: SessionChatConfig { draft ?? stored }

    public var isEditingConfig: Bool { draft != nil }

    /// Whether this conversation's configuration may be edited at all.
    ///
    /// Three refusals, each with its own reason:
    ///
    /// - no leg: this host does not own the admin config route;
    /// - no business key: both config routes address the **string** `sessionId`, and a row without one
    ///   addresses nothing (`SessionConfiguring.swift:161-178`);
    /// - a switched-off conversation: the server's own read refuses one with a business `403`
    ///   (`SessionServiceImpl.kt:276-279`), and it is the route this write goes through first, so an editor
    ///   here could only ever come back with that sentence. The row's status is the flag, and re-enabling it is
    ///   the card menu's job, not this panel's.
    public var canEditConfig: Bool {
        configWriter != nil && businessKey != nil && session.isEnabled
    }

    public func beginConfigEdit() {
        guard canEditConfig else { return }
        configNotice = nil
        draft = stored
    }

    /// Hands back the confirmed values. The draft is thrown away rather than reverted field by field: nothing
    /// was written, so the server's answer is still the whole truth.
    public func cancelConfigEdit() {
        draft = nil
        configNotice = nil
    }

    /// Thinking, and the two model columns that decide whether the switch is live at all. The service refuses
    /// the same move (`SessionServiceImpl.kt:291-296`), so the rule lives here rather than only on the control:
    /// a caller that never renders the row still cannot put a forbidden value into the draft.
    public func setConfigThink(_ value: Bool) {
        mutateDraft(allowed: displayedConfig.canToggleThink) { row in row.enableThink = value }
    }

    public func setConfigSearch(_ value: Bool) {
        mutateDraft(allowed: displayedConfig.canToggleSearch) { row in row.enableSearch = value }
    }

    /// Plan has no capability behind it: neither the console nor the service gates it on a model column
    /// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:3606-3614`, `SessionServiceImpl.kt:298`), so
    /// the switch is always live while the draft is open.
    public func setConfigPlan(_ value: Bool) {
        mutateDraft { row in row.enablePlan = value }
    }

    public func setConfigPermissionMode(_ mode: ChatPermissionMode) {
        mutateDraft { row in row.permissionMode = mode }
    }

    /// Why the thinking switch is dead, in the two cases that differ. `nil` when it is live.
    public var configThinkHint: String? {
        let row = displayedConfig
        if !row.modelSupportReasoning { return hx("chat.detail.config.think.unsupported") }
        if row.thinkLockedOn { return hx("chat.detail.config.think.required") }
        return nil
    }

    /// Why the search switch is dead, which is one case only.
    public var configSearchHint: String? {
        displayedConfig.canToggleSearch ? nil : hx("chat.detail.config.search.unsupported")
    }

    private func mutateDraft(allowed: Bool = true, _ change: (inout SessionChatConfig) -> Void) {
        guard allowed, var row = draft else { return }
        change(&row)
        guard row != draft else { return }
        draft = row
        configNotice = nil
    }

    /// Whether the draft differs from what the server holds, in the four fields the write can carry. The
    /// read-only capability columns are excluded by `SessionChatChange.init?(stored:draft:)` itself, which is
    /// why this is a diff rather than `stored != draft`.
    public var hasConfigChanges: Bool {
        guard let draft else { return false }
        return SessionChatChange(stored: stored, draft: draft) != nil
    }

    public var canSaveConfig: Bool { hasConfigChanges && !isSavingConfig }

    /// The draft's switches and menu, each live only while the panel is being edited and the model allows it.
    public var canToggleConfigThink: Bool { isEditingConfig && displayedConfig.canToggleThink }
    public var canToggleConfigSearch: Bool { isEditingConfig && displayedConfig.canToggleSearch }
    public var canToggleConfigPlan: Bool { isEditingConfig }
    public var canChooseConfigPermissionMode: Bool { isEditingConfig }

    /// Writes the draft and, once the write has landed, reads the row back.
    ///
    /// The read is not decoration: `PUT /{sessionId}/config` answers `ResultVo<Void>` with no body
    /// (`SessionConfiguring.swift:176-178`), so the only confirmation this screen can show is the row the
    /// server holds afterwards. A landed write whose read-back fails therefore says both things at once — the
    /// columns are applied locally, and the line under the panel names the read-back as what did not arrive —
    /// rather than reporting a save the screen cannot show or pretending the write never happened.
    ///
    /// One write at a time: a second tap while the first is on the wire is ignored, because the server's update
    /// is a read-modify-write on the row and the second body would be built against values already being
    /// replaced (`RowWriteReentryTests` charges the same rule to the row-level writes).
    public func saveConfig() async {
        guard let draft, let configWriter, let key = businessKey, !isSavingConfig else { return }
        guard let change = SessionChatChange(stored: stored, draft: draft) else {
            // Unreachable from the panel, whose save stays off; kept as the refusal a caller that ignores
            // `canSaveConfig` gets instead of a silent no-op.
            configNotice = hx("chat.detail.config.nothing")
            return
        }
        isSavingConfig = true
        defer { isSavingConfig = false }

        if case let .failure(error) = await configWriter.updateSessionConfig(sessionId: key, change) {
            // The draft stays on screen: what the user typed is worth more than a clean form, and the columns
            // did not move.
            configNotice = ErrorMessage.text(for: error)
            return
        }
        // The row this sheet came from is a page snapshot whose four columns the server has just replaced.
        onWritten?()

        guard case let .success(row) = await configWriter.sessionConfig(sessionId: key) else {
            stored = draft
            self.draft = nil
            configNotice = hx("chat.detail.config.readBackFailed")
            return
        }
        let confirmed = SessionChatConfig(from: row)
        stored = confirmed
        self.draft = nil
        configNotice = hx("chat.detail.config.saved")
    }
}

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

/// Turns a `SessionSummary` row into the panels the console's DetailModal shows — nothing more.
///
/// Everything here reads the row the list already has. The console fires a second, by-id request per row
/// (`getAgentById` / `getTeamById`, `harnax-webui/src/pages/session/components/DetailModal.tsx:114-144`) to
/// fill its tools, MCP, CLI and members panels; this app makes no by-id agent or team read at all
/// (`Sources/HarnaxCore/Contract/Facades.swift:148-151`), so those panels are not part of this surface.
///
/// A value the row does not carry produces no row: the console prints `None`/`Unknown` placeholders
/// (`DetailModal.tsx:206-219`), and this screen hides the line instead.
public enum SessionDetailPresenter {
    /// The console's `ellipsis={{ rows: 4 }}` (`DetailModal.tsx:271`).
    public static let promptLineLimit = 4

    public static func sections(
        for session: SessionSummary,
        isPromptExpanded: Bool = false,
        availability: (SessionSkillItem) -> Bool? = { _ in nil }
    ) -> [SessionDetailSection] {
        var panels: [SessionDetailSection] = []
        if let basic = basicSection(session) { panels.append(basic) }
        if let executor = executorSection(session, expanded: isPromptExpanded) { panels.append(executor) }
        if let skills = skillSection(session, availability: availability) { panels.append(skills) }
        if let mcp = mcpSection(session) { panels.append(mcp) }
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

    // MARK: - skills

    /// The console's 失效 badge needs `skillAvailable`, and this row has no such column: `skillList` on a
    /// session answers with the five fields of `SessionResponse.kt:78-90` only
    /// (`Sources/HarnaxCore/Contract/SessionSummary.swift:144-147`), so the flag is a caller's input rather
    /// than something read off the DTO. A `nil` means "this row does not say", which renders no badge.
    private static func skillSection(
        _ session: SessionSummary,
        availability: (SessionSkillItem) -> Bool?
    ) -> SessionDetailSection? {
        let rows = session.skillList.compactMap { skill -> SessionDetailRow? in
            var marks: [SessionDetailMark] = []
            if availability(skill) == false {
                marks.append(.badge(key: "chat.detail.badge.unavailable", tone: .danger))
            }
            if let repository = skill.repository {
                marks.append(.chip(text: repository, tone: nil))
            }
            // A skill the row never names keeps its description as its title rather than disappearing: the
            // binding is real even when the name column is blank.
            if let name = skill.displayName {
                return SessionDetailRow(labelKey: nil, value: name, detail: skill.detail, marks: marks)
            }
            guard let detail = skill.detail else { return nil }
            return SessionDetailRow(labelKey: nil, value: detail, marks: marks)
        }
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.skills", rows: rows)
    }

    // MARK: - mcp

    private static func mcpSection(_ session: SessionSummary) -> SessionDetailSection? {
        let rows = session.mcpList.compactMap { server -> SessionDetailRow? in
            if let name = server.displayName {
                return SessionDetailRow(labelKey: nil, value: name, detail: server.detail)
            }
            guard let detail = server.detail else { return nil }
            return SessionDetailRow(labelKey: nil, value: detail)
        }
        return rows.isEmpty ? nil : SessionDetailSection(titleKey: "chat.detail.section.mcp", rows: rows)
    }

    // MARK: - formatting

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

/// The detail sheet's whole state: the row it presents and whether its prompt is open.
///
/// There is no load. The list's row already carries every field these panels show
/// (`Sources/HarnaxCore/Contract/SessionSummary.swift:3-18`), so this screen has nothing to fetch, nothing to
/// retry, and no phase — which is why it is a presenter plus one piece of view state rather than the
/// `Phase` machine the writable screens carry.
@MainActor
public final class SessionDetailViewModel: ObservableObject {
    public let session: SessionSummary

    @Published public var isPromptExpanded = false

    public init(session: SessionSummary) {
        self.session = session
    }

    /// The panels in the console's order, with the empty ones dropped.
    public var sections: [SessionDetailSection] {
        SessionDetailPresenter.sections(for: session, isPromptExpanded: isPromptExpanded)
    }

    /// A row with nothing to say. Reachable: every column of `SessionSummary` is optional, so a row the
    /// server answered with only an `id` has no panel to open.
    public var hasNothingToShow: Bool { sections.isEmpty }

    /// The string business key the runtime routes address — the one value on this sheet worth copying.
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
}

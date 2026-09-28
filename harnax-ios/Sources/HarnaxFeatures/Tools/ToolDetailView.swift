import SwiftUI
import Combine
import HarnaxCore
import HarnaxKit

/// The drill-down's rows, resolved to the words the current language shows.
///
/// The presenter exists so the two rules of this screen are testable without a view: which name column wins
/// per language, and what a row says when the tool declares no parameters at all.
enum ToolDetailPresenter {
    /// Parameters first — the one thing an operator has to fill in before a tool runs — then where the call
    /// lands in the running code. A dimension with no rows is dropped rather than shown empty.
    static func sections(for tool: ToolSummary, chinese: Bool) -> [HXBindingSection] {
        var sections: [HXBindingSection] = []
        if let params = paramsSection(for: tool) { sections.append(params) }
        if let implementation = implementationSection(for: tool) { sections.append(implementation) }
        return sections
    }

    /// The name the row is titled by. All three columns blank still leaves the id, and no id at all leaves
    /// the same placeholder the agent card's tool section uses — the two screens must not name one tool two
    /// different ways.
    static func title(for tool: ToolSummary, chinese: Bool) -> String {
        tool.title(chinese: chinese)
            ?? tool.id.map { hx("agent.binding.toolFallback", Int($0)) }
            ?? hx("agent.binding.unnamed")
    }

    /// The technical identifier only earns its own line when it differs from the name being shown
    /// (`harnax-webui/src/pages/tool/index.tsx:79-86`).
    static func codeNameLine(for tool: ToolSummary, chinese: Bool) -> String? {
        guard let code = tool.codeName, code != title(for: tool, chinese: chinese) else { return nil }
        return code
    }

    private static func paramsSection(for tool: ToolSummary) -> HXBindingSection? {
        let rows = tool.entries.map { entry in
            HXBindingRow(
                title: entry.envParamName,
                subtitle: detail(of: entry),
                badges: flags(of: entry)
            )
        }
        // A row can carry the required key names without the entry table — the two come from different
        // columns (`AgentToolServiceImpl.kt:39-40`), and the key list is the only word on what is expected.
        let fallbacks = tool.entries.isEmpty
            ? tool.declaredRequiredKeys.map { HXBindingRow(title: $0, subtitle: nil, badges: [hx("env.required")]) }
            : []
        let all = rows + fallbacks
        return all.isEmpty ? nil : HXBindingSection(titleKey: "env.title", rows: all)
    }

    private static func detail(of entry: EnvParamEntry) -> String? {
        [hxPresented(entry.description), hxPresented(entry.defaultValue).map { "\(hx("env.default")) \($0)" }]
            .compactMap { $0 }
            .joined(separator: " · ")
            .nilIfEmpty()
    }

    /// The two flags the wire carries as booleans. A parameter that is neither is a plain line — a "no" mark
    /// on every row would say nothing.
    private static func flags(of entry: EnvParamEntry) -> [String] {
        var marks: [String] = []
        if entry.required { marks.append(hx("env.required")) }
        if entry.secret { marks.append(hx("env.sensitive")) }
        return marks
    }

    /// Bean and method only. The code name is a line on the card itself, and repeating it here would give a
    /// tool with no implementation any section at all.
    private static func implementationSection(for tool: ToolSummary) -> HXBindingSection? {
        var rows: [HXBindingRow] = []
        if let bean = tool.bean {
            rows.append(HXBindingRow(title: hx("tool.row.bean"), subtitle: bean, badges: []))
        }
        if let method = tool.method {
            rows.append(HXBindingRow(title: hx("tool.row.method"), subtitle: method, badges: []))
        }
        return rows.isEmpty ? nil : HXBindingSection(titleKey: "tool.section.implementation", rows: rows)
    }
}

private extension String {
    func nilIfEmpty() -> String? { isEmpty ? nil : self }
}

/// The state behind one drill-down sheet.
///
/// The list row already carries the parameter table, so opening a sheet costs no request. The one call it
/// can make is the row re-read by id: the code sync rewrites `agent_tool` and its parameter rows at every
/// boot, so a sheet left open across a server restart is reading a stale row, and `GET /tools/{id}` is the
/// cheapest way to put a current one on screen.
@MainActor
final class ToolDetailModel: ObservableObject {
    @Published private(set) var tool: ToolSummary
    @Published private(set) var isReloading = false
    /// A failed re-read keeps the row that was on screen — losing the text the operator is reading would be
    /// a worse answer than a stale one.
    @Published private(set) var inlineError: String?

    private let tools: any ToolCataloging

    init(tools: any ToolCataloging, tool: ToolSummary) {
        self.tools = tools
        self.tool = tool
    }

    var canReload: Bool { tool.id != nil }

    func reload() async {
        guard let id = tool.id, !isReloading else { return }
        isReloading = true
        defer { isReloading = false }
        switch await tools.toolDetail(id: id) {
        case let .success(fresh):
            tool = fresh
            inlineError = nil
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
    }
}

/// The tool drill-down: parameters with their flags and defaults, then the bean and method that serve the
/// call. Read-only in both directions — there is nothing here to edit, and the stack offers no route for it.
struct ToolDetailSheet: View {
    @StateObject private var model: ToolDetailModel
    /// Held so switching language inside the app re-resolves the name column, which is picked per row.
    @ObservedObject private var catalog = HarnaxCatalog.shared

    init(tools: any ToolCataloging, tool: ToolSummary) {
        _model = StateObject(wrappedValue: ToolDetailModel(tools: tools, tool: tool))
    }

    var body: some View {
        HXBindingSheet(title: ToolDetailPresenter.title(for: model.tool, chinese: chinese)) {
            VStack(alignment: .leading, spacing: 0) {
                if let inline = model.inlineError {
                    HXBanner(
                        "state.error.title",
                        message: inline,
                        systemImage: "exclamationmark.triangle",
                        tone: .danger
                    )
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
                if sections.isEmpty {
                    HXStateView(.empty, message: hx("tool.detail.empty"))
                } else {
                    HXBindingListView(sections: sections)
                }
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) { reloadButton }
            }
        }
    }

    private var chinese: Bool { catalog.language.prefersChinese }

    private var sections: [HXBindingSection] {
        ToolDetailPresenter.sections(for: model.tool, chinese: chinese)
    }

    /// Icon-only, so the sheet keeps one title. A row with no id has nothing to re-read by.
    private var reloadButton: some View {
        Button {
            Task { await model.reload() }
        } label: {
            Image(systemName: model.isReloading ? "hourglass" : "arrow.clockwise")
                .foregroundStyle(Color.hx(.textSecondary))
                .accessibilityLabel(hx("tool.detail.reload"))
        }
        .disabled(!model.canReload || model.isReloading)
    }
}

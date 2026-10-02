import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The view model of the read-only tool table.
///
/// It has no writes at all — no switch, no delete, no form — because the stack has no write route for tools
/// (`ToolCataloging`). What it does have is one read and no traffic after that: `/builtin` answers the whole
/// code-registered set in one array (`AgentToolController.kt:67-78`), so the search and the status choice
/// are applied to the rows already held, exactly as the console applies them to its own loaded array
/// (`harnax-webui/src/pages/tool/index.tsx:57-68`). Typing filters; only a refresh costs a request.
///
/// That is also why the Chinese display name became searchable. Under the paged route the keyword went to
/// MySQL, whose pattern covers `name`, `display_name` and `description` but not `display_name_zh`
/// (`AgentToolMapper.xml:45-57`), and the row shows the Chinese column first on a Chinese device.
@MainActor
public final class ToolListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// Nothing to show — either the code registers no tool, or the filter in effect keeps none of them.
        /// Kept apart from `loading` so an empty table never looks like a spinner that never finishes; the
        /// screen tells the two apart with `isFiltered`.
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    /// The rows on screen: what the last read answered, under the filter in effect.
    @Published public private(set) var items: [ToolSummary] = []
    /// How wide the table is before the filter, so an empty screen under a search can say that rows exist
    /// behind it. There is no page counter on this route to report.
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen: a failed refresh must not erase the rows the
    /// user was reading.
    @Published public private(set) var inlineError: String?
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { apply() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { apply() } }
    }

    private let tools: any ToolCataloging
    /// The last answer, unfiltered. The filter is re-applied to this rather than re-read, which is the whole
    /// point of the route change.
    private var held: [ToolSummary] = []
    /// Bumped by every refresh so the last answer to arrive is not the one that wins the rows: a pull that
    /// is still on the wire while a second one lands must leave the newer set on screen.
    private var refreshGeneration = 0

    public init(tools: any ToolCataloging) {
        self.tools = tools
    }

    /// An empty result under a filter is not the same news as an installation with no tools at all. A
    /// keyword of only spaces is no filter — the console tests the trimmed word for emptiness too
    /// (`tool/index.tsx:59`).
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        refreshGeneration += 1
        let generation = refreshGeneration
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await tools.builtinTools() {
        case let .success(rows):
            guard generation == refreshGeneration else { return }
            // Replaces rather than appends: this is the table as it stands now, and a tool the last boot
            // removed has to leave the screen with it.
            held = rows
            apply()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            let text = ErrorMessage.text(for: error)
            if items.isEmpty {
                phase = .failed(text)
            } else {
                inlineError = text
            }
        }
    }

    // MARK: - the two judgements the console makes in memory

    /// The console's own predicate, copied field for field: `name`, `displayName`, `displayNameZh` and
    /// `description`, each lowercased and tested for the lowercased word
    /// (`harnax-webui/src/pages/tool/index.tsx:57-68`).
    ///
    /// Two details of that code are deliberately kept rather than improved, so the two screens agree about
    /// what a search word is. The word is trimmed only to decide whether there *is* one — the needle itself
    /// is the typed string lowercased, so ` 读取 ` keeps its spaces and legitimately matches nothing. And a
    /// column that is absent or empty cannot match, which is what `item.name &&` does in JavaScript.
    nonisolated static func matches(_ tool: ToolSummary, keyword: String) -> Bool {
        guard !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return true }
        let needle = keyword.lowercased()
        return contains(tool.name, needle: needle)
            || contains(tool.displayName, needle: needle)
            || contains(tool.displayNameZh, needle: needle)
            || contains(tool.description, needle: needle)
    }

    private nonisolated static func contains(_ value: String?, needle: String) -> Bool {
        guard let value, !value.isEmpty else { return false }
        return value.lowercased().contains(needle)
    }

    /// Equality with the raw 0/1 column, which is what the paged route's SQL did (`status = #{status}`,
    /// `AgentToolMapper.xml:53-55`) and what stays true now that the comparison happens here: a row whose
    /// `status` never arrived is neither enabled nor disabled, so it shows under `all` and under nothing
    /// else. Every row the code sync writes is enabled (`AgentToolMapper.xml:64`), which is why the stopped
    /// half of this filter is a rarity rather than a daily tool.
    nonisolated static func matches(_ tool: ToolSummary, status filter: StatusFilter) -> Bool {
        guard let wanted = filter.queryValue else { return true }
        return tool.status == wanted
    }

    private func apply() {
        let kept = held.filter { Self.matches($0, keyword: keyword) && Self.matches($0, status: filter) }
        items = kept
        total = held.count
        phase = kept.isEmpty ? .empty : .content
    }
}

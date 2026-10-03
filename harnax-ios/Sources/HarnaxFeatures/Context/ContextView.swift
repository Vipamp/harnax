import SwiftUI
import HarnaxCore
import HarnaxKit

/// D1 — the 上下文 tab is a five-way switch over the domains an agent binds to.
///
/// The segment row is the whole navigation shape of this tab: one column on screen at a time, each column
/// the domain's own list screen with its forms and detail pushes riding on the tab's stack.
public struct ContextView: View {
    @State private var domain: ContextDomain = .model
    private let dependencies: HarnaxDependencies
    private let account: AccountSnapshot?

    public init(dependencies: HarnaxDependencies, account: AccountSnapshot?) {
        self.dependencies = dependencies
        self.account = account
    }

    public var body: some View {
        // The bar goes into the column instead of sitting above it: outside the scroll it held still while
        // the title and the search field moved with the navigation bar, and two surfaces that move
        // differently read as a broken screen. `HXSegmentBarRow` is where the column draws it.
        column.harnaxSegmentBar {
            HXSegmented(ContextDomain.segments, selection: Binding(
                get: { domain.rawValue },
                set: { domain = ContextDomain(rawValue: $0) ?? .model }
            ))
        }
    }

    @ViewBuilder
    private var column: some View {
        // A column is built when its segment is picked and thrown away when it is left, so a search term
        // does not survive the round trip and the first page reloads on every visit.
        switch domain {
        case .model:
            ModelProviderListView(catalog: dependencies.models, account: account)
        case .tool:
            ToolListView(tools: dependencies.tools)
        case .mcp:
            McpListView(mcp: dependencies.mcp, account: account)
        case .skill:
            SkillHomeView(skills: dependencies.skills, account: account)
        case .cli:
            CliListView(clis: dependencies.clis, sessionRefresher: dependencies.sessionRefresher)
        }
    }
}

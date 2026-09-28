import SwiftUI
import HarnaxKit

/// D1 — the 上下文 tab is a five-way switch over the domains an agent binds to.
///
/// The segment row is the whole navigation shape of this tab, so it lands before the domains do: a
/// placeholder per column keeps the tab from rearranging between milestones.
public struct ContextView: View {
    @State private var domain: ContextDomain = .model

    public init() {}

    public var body: some View {
        VStack(spacing: 0) {
            HXSegmented(ContextDomain.segments, selection: Binding(
                get: { domain.rawValue },
                set: { domain = ContextDomain(rawValue: $0) ?? .model }
            ))
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 4)

            // Each column holds its own state, so switching away and back does not lose a search term.
            Group {
                switch domain {
                case .model, .tool, .mcp, .skill, .cli:
                    SoonView(titleKey: domain.titleKey)
                }
            }
        }
    }
}

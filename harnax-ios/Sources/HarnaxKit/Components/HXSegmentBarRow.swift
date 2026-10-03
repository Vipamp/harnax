import SwiftUI

/// The row a host tab uses to switch between the columns it hosts.
///
/// `ContextView` and `AgentHomeView` own the switch, but the row has to travel with the column it switches
/// to. Pinned above the column it sits still while the title and the search field move with the navigation
/// bar, and two surfaces that move differently read as a broken screen rather than as chrome — the report
/// this shape answers, 2026-10-03. So the bar is handed down here and each column draws it as the first item
/// of its own scroll: it leaves with the rows, and pulling it down is the column's own refresh.
///
/// It arrives through the environment rather than an initializer because the column is picked at runtime —
/// a host would otherwise repeat the bar once per `case`, and the skill domain would have to relay it through
/// `SkillHomeView` to the list it hosts.
public struct HXSegmentBarRow: View {
    @Environment(\.harnaxSegmentBar) private var bar: AnyView?

    public init() {}

    public var body: some View {
        if let bar { bar }
    }
}

public extension View {
    /// Hands the switch row to whichever column this screen is showing. Call it on the column, not on a
    /// stack that holds the column and the bar: a bar outside the column's scroll cannot ride away with it.
    func harnaxSegmentBar<Bar: View>(@ViewBuilder _ bar: () -> Bar) -> some View {
        environment(\.harnaxSegmentBar, AnyView(bar()))
    }
}

private struct HarnaxSegmentBarKey: EnvironmentKey {
    static let defaultValue: AnyView? = nil
}

extension EnvironmentValues {
    /// The host's switch row, or nil where a screen is mounted bare — which is how the DEBUG walkthrough
    /// frames one column at a time.
    public var harnaxSegmentBar: AnyView? {
        get { self[HarnaxSegmentBarKey.self] }
        set { self[HarnaxSegmentBarKey.self] = newValue }
    }
}

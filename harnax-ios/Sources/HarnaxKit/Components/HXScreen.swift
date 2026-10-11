import SwiftUI

/// Page chrome every screen shares.
public enum HXLayout {
    /// The tab bar floats over the content, so a scroll view needs this much room after its last row for
    /// that row to be scrollable clear of the pill.
    public static let tabBarClearance: CGFloat = 88
}

public extension View {
    /// The semantic page background, edge to edge. Without `ignoresSafeArea` the strip under the status
    /// bar and behind the tab pill falls back to the system's own black or white, which reads as a band.
    /// Applied to the screen root, so it also stretches a state view to the full height.
    func harnaxScreen() -> some View {
        frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.hx(.background).ignoresSafeArea())
    }

    /// The house page title: centred at the top and at the bar's own smaller size. A bare `navigationTitle`
    /// leaves iOS's default large title, which starts a left-aligned block at the top of every screen — one
    /// title style for the whole app is this modifier's whole point, so screens call it rather than
    /// `navigationTitle` directly.
    func harnaxTitle(_ title: Text) -> some View {
        #if canImport(UIKit)
        return navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
        #else
        return navigationTitle(title)
        #endif
    }

    /// The `String` overload, verbatim: callers hand over an already-localized `hx(...)` string, so a key
    /// never reaches the bar as itself.
    func harnaxTitle(_ title: String) -> some View {
        harnaxTitle(Text(verbatim: title))
    }
}

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
}

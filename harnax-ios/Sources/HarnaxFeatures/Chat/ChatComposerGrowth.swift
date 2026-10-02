import CoreGraphics
import Foundation

/// How tall the composer is allowed to get before it stops growing and starts scrolling.
///
/// Half of the screen is the line the answer still has to stay readable above it; the field's own vertical
/// padding is taken off, or the card lands a line or two past that half.
enum ChatComposerGrowth {
    static let fraction: CGFloat = 0.5
    /// One line would hide the caret's own context; two is where a wrapped sentence becomes readable.
    static let minimumLines = 2
    /// What the field gets before the screen has been measured — the height the composer had all along.
    static let unmeasuredLines = 6
    /// The text field's own `padding(.vertical, 10)` on both edges.
    static let verticalPadding: CGFloat = 20

    static func lineCap(screenHeight: CGFloat, lineHeight: CGFloat) -> Int {
        guard screenHeight > 0, lineHeight > 0 else { return unmeasuredLines }
        let usable = screenHeight * fraction - verticalPadding
        return max(minimumLines, Int(usable / lineHeight))
    }
}

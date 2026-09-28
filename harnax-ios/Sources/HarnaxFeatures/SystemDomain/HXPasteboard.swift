import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// The one way anything on these two screens puts text on the system pasteboard.
///
/// Guarded rather than imported: the tests run on macOS, where `UIPasteboard` does not exist, and a
/// copy that silently does nothing there is better than a target that will not build. `HXValueText` has
/// kept the same rule since the agent screens landed
/// (`Sources/HarnaxKit/Components/HXValue.swift:35-39`).
enum HXPasteboard {
    static func copy(_ text: String) {
        #if canImport(UIKit) && os(iOS)
        UIPasteboard.general.string = text
        #endif
    }
}

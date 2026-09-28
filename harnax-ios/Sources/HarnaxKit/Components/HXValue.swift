import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A machine-owned identifier — a digest, a session id, a package path. Monospaced, truncated in the
/// middle so both ends stay readable, and long-press copies the whole value.
///
/// The web console truncates by character count (`AgentRefreshModal.tsx:193-217`); on iOS the width
/// decides, and the copy hands back what was never visible.
public struct HXValueText: View {
    private let value: String
    private let lines: Int

    public init(_ value: String, lines: Int = 1) {
        self.value = value
        self.lines = lines
    }

    public var body: some View {
        Text(verbatim: value)
            .font(.caption.monospaced())
            .foregroundStyle(Color.hx(.textSecondary))
            .lineLimit(lines)
            .truncationMode(.middle)
            .contextMenu {
                Button {
                    HXValueText.copy(value)
                } label: {
                    HXText("common.copy")
                }
            }
    }

    private static func copy(_ value: String) {
        #if canImport(UIKit) && os(iOS)
        UIPasteboard.general.string = value
        #endif
    }
}

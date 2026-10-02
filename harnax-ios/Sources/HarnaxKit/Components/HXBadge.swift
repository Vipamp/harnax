import SwiftUI

/// Status pill — the mockup's `.badge`. Colour carries meaning, so the slot is always explicit.
public struct HXBadge: View {
    private let titleKey: String
    private let slot: PaletteSlot

    public init(_ titleKey: String, tone: PaletteSlot) {
        self.titleKey = titleKey
        self.slot = tone
    }

    public var body: some View {
        HXText(titleKey)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(Color.hx(slot))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.hxFill(slot, alpha: 0.16), in: Capsule())
            .fixedSize()
    }
}

/// Neutral metadata pill — the mockup's `.chip`, used for the model/skill/mcp/cli counts.
/// Takes resolved text, because the label usually comes from the server.
public struct HXChip: View {
    private let label: String
    private let slot: PaletteSlot?

    public init(_ label: String, tone: PaletteSlot? = nil) {
        self.label = label
        self.slot = tone
    }

    public var body: some View {
        Text(verbatim: label)
            .font(.caption2)
            .foregroundStyle(slot.map { Color.hx($0) } ?? Color.hx(.textSecondary))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(
                slot.map { Color.hxFill($0, alpha: 0.14) } ?? Color.hx(.surfaceAlt),
                in: Capsule()
            )
            .fixedSize()
    }
}

/// A dot on a list row that marks a state. Hue alone is invisible to a screen reader, so a caller that means
/// something by the colour passes the word for it; a dot that only echoes text already on the row stays
/// decorative and is spoken past.
public struct HXStatusDot: View {
    private let slot: PaletteSlot
    private let label: String?

    public init(tone: PaletteSlot, label: String? = nil) {
        self.slot = tone
        self.label = label
    }

    @ViewBuilder
    public var body: some View {
        let dot = Circle()
            .fill(Color.hx(slot))
            .frame(width: 8, height: 8)
        if let label {
            dot.accessibilityLabel(Text(verbatim: label))
        } else {
            dot.accessibilityHidden(true)
        }
    }
}

import SwiftUI

/// The corner every card surface is drawn with — and therefore the corner a card-wide tap has to answer for.
/// One value on purpose: if the tap region and the visible card ever disagreed, a tap would land on nothing.
let hxCardCornerRadius: CGFloat = 14

/// Elevated container for a single record — the mockup's `.card`.
public struct HXCard<Content: View>: View {
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.hx(.surface), in: shape)
            .overlay(shape.strokeBorder(Color.hx(.separator), lineWidth: 1))
    }

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: hxCardCornerRadius, style: .continuous) }
}

/// Grouped settings block — the mockup's `.group`. Rows sit on one surface with hairlines between them.
public struct HXGroupCard<Content: View>: View {
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        VStack(spacing: 0) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surface), in: shape)
        .overlay(shape.strokeBorder(Color.hx(.separator), lineWidth: 1))
        .clipShape(shape)
    }

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: hxCardCornerRadius, style: .continuous) }
}

/// The whole card as one tap target, for a card whose body opens something.
///
/// A card built out of text rows answers only for the glyphs drawn inside it, so a tap landing beside a short
/// title, on a badge, in the gap between rows, or on the card's own padding goes nowhere — which reads as the
/// control failing "sometimes". This shapes the hit region to the card surface and takes the tap wherever it
/// lands. A `nil` action leaves the card untouched: a row with nothing to open must not answer like a control.
///
/// Apply it before the card's `.overlay(alignment: .topTrailing)`, so a menu the card carries in its corner
/// stays in the layer above the region this covers.
public struct HXCardTap: ViewModifier {
    private let action: (() -> Void)?

    public init(action: (() -> Void)?) {
        self.action = action
    }

    public func body(content: Content) -> some View {
        if let action {
            content
                .contentShape(RoundedRectangle(cornerRadius: hxCardCornerRadius, style: .continuous))
                .onTapGesture(perform: action)
        } else {
            content
        }
    }
}

extension View {
    public func hxCardTap(_ action: (() -> Void)?) -> some View {
        modifier(HXCardTap(action: action))
    }
}

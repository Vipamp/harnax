import SwiftUI

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

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: 14, style: .continuous) }
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

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: 14, style: .continuous) }
}

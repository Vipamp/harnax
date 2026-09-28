import SwiftUI

public struct HXPrimaryButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Color.hx(.onBrand))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.hx(.brand), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .opacity(configuration.isPressed ? 0.72 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

public struct HXSecondaryButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.medium))
            .foregroundStyle(Color.hx(.brand))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .strokeBorder(Color.hx(.separator), lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.72 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

public struct HXDestructiveButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Color.hx(.danger))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.hxFill(.danger, alpha: 0.14), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .opacity(configuration.isPressed ? 0.72 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

/// Small tap target used for the inline 对话/编辑/⋯ actions on a record card.
public struct HXInlineButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.footnote.weight(.medium))
            .foregroundStyle(Color.hx(.brand))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.hxFill(.brand, alpha: 0.12), in: Capsule())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

public extension ButtonStyle where Self == HXPrimaryButtonStyle {
    static var hxPrimary: HXPrimaryButtonStyle { HXPrimaryButtonStyle() }
}

public extension ButtonStyle where Self == HXSecondaryButtonStyle {
    static var hxSecondary: HXSecondaryButtonStyle { HXSecondaryButtonStyle() }
}

public extension ButtonStyle where Self == HXDestructiveButtonStyle {
    static var hxDestructive: HXDestructiveButtonStyle { HXDestructiveButtonStyle() }
}

public extension ButtonStyle where Self == HXInlineButtonStyle {
    static var hxInline: HXInlineButtonStyle { HXInlineButtonStyle() }
}

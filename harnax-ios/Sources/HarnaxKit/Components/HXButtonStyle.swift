import SwiftUI

/// `.disabled()` alone leaves a filled button at full brand colour, so a form whose required fields are still
/// empty offers a 确定 that reads as tappable and answers nothing. Press keeps its own depth; disabled goes
/// visibly quieter.
private func hxButtonOpacity(isPressed: Bool, isEnabled: Bool, pressed: CGFloat = 0.72) -> CGFloat {
    if isPressed { return pressed }
    return isEnabled ? 1 : 0.4
}

public struct HXPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Color.hx(.onBrand))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.hx(.brand), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .opacity(hxButtonOpacity(isPressed: configuration.isPressed, isEnabled: isEnabled))
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

public struct HXSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

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
            .opacity(hxButtonOpacity(isPressed: configuration.isPressed, isEnabled: isEnabled))
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

public struct HXDestructiveButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Color.hx(.danger))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.hxFill(.danger, alpha: 0.14), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .opacity(hxButtonOpacity(isPressed: configuration.isPressed, isEnabled: isEnabled))
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

/// Small tap target used for the inline 对话/编辑/⋯ actions on a record card.
public struct HXInlineButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.footnote.weight(.medium))
            .foregroundStyle(Color.hx(.brand))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.hxFill(.brand, alpha: 0.12), in: Capsule())
            .opacity(hxButtonOpacity(isPressed: configuration.isPressed, isEnabled: isEnabled, pressed: 0.6))
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

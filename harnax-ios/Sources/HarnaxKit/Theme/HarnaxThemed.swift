import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Root theme modifier. Every screen hangs off it, so an appearance preference picked in `F1 Me`
/// reaches the whole tree without each view having to know about it.
public struct HarnaxThemed: ViewModifier {
    @AppStorage(ThemeMode.storageKey) private var storedMode: String = ThemeMode.system.rawValue

    public init() {}

    public func body(content: Content) -> some View {
        content
            .tint(.hx(.brand))
            .background(Color.hx(.background))
            .modifier(AppearanceModifier(forcedDark: ThemeMode.stored(storedMode).forcedDark))
    }
}

public extension View {
    func harnaxThemed() -> some View {
        modifier(HarnaxThemed())
    }
}

struct AppearanceModifier: ViewModifier {
    let forcedDark: Bool?

    func body(content: Content) -> some View {
        #if canImport(UIKit)
        content.background(AppearanceBridge(forcedDark: forcedDark))
        #else
        content.environment(\.colorScheme, forcedDark == true ? .dark : .light)
        #endif
    }
}

#if canImport(UIKit)
/// Writes `overrideUserInterfaceStyle` onto the hosting window. Doing it at the window level means the
/// app-level preference drives UIKit trait resolution for every bridged colour, not just SwiftUI's own.
private struct AppearanceBridge: UIViewRepresentable {
    let forcedDark: Bool?

    func makeUIView(context: Context) -> AppearanceUIView {
        AppearanceUIView(forcedDark: forcedDark)
    }

    func updateUIView(_ uiView: AppearanceUIView, context: Context) {
        uiView.forcedDark = forcedDark
        uiView.apply()
    }
}

final class AppearanceUIView: UIView {
    var forcedDark: Bool?

    init(forcedDark: Bool?) {
        self.forcedDark = forcedDark
        super.init(frame: .zero)
        isHidden = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        apply()
    }

    func apply() {
        guard let window else { return }
        switch forcedDark {
        case .none: window.overrideUserInterfaceStyle = .unspecified
        case .some(false): window.overrideUserInterfaceStyle = .light
        case .some(true): window.overrideUserInterfaceStyle = .dark
        }
    }
}
#endif

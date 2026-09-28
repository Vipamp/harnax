import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Which physical keyboard a field wants. Kept as our own enum so the iOS-only modifiers stay in one
/// guarded place instead of every screen that owns a field.
public enum HXInputKind: Sendable {
    case text
    case email
    case number
    case URL
}

public struct HXField: View {
    private let placeholderKey: String
    private let text: Binding<String>
    private let systemImage: String
    private let secure: Bool
    private let kind: HXInputKind

    public init(
        _ placeholderKey: String,
        text: Binding<String>,
        systemImage: String = "textformat",
        secure: Bool = false,
        kind: HXInputKind = .text
    ) {
        self.placeholderKey = placeholderKey
        self.text = text
        self.systemImage = systemImage
        self.secure = secure
        self.kind = kind
    }

    public var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.body)
                .foregroundStyle(Color.hx(.textTertiary))
                .frame(width: 22)
            field
                .font(.body)
                .foregroundStyle(Color.hx(.textPrimary))
                .tint(Color.hx(.brand))
                .hxInput(kind)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 13)
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .strokeBorder(Color.hx(.separator), lineWidth: 1)
        )
    }

    @ViewBuilder
    private var field: some View {
        if secure {
            SecureField(hx(placeholderKey), text: text)
        } else {
            TextField(hx(placeholderKey), text: text)
        }
    }
}

extension View {
    @ViewBuilder
    func hxInput(_ kind: HXInputKind) -> some View {
        #if os(iOS)
        self
            .keyboardType(kind.uiKeyboardType)
            .textInputAutocapitalization(kind == .email || kind == .text ? .never : .sentences)
            .autocorrectionDisabled()
        #else
        self
        #endif
    }
}

#if os(iOS)
private extension HXInputKind {
    var uiKeyboardType: UIKeyboardType {
        switch self {
        case .text: return .default
        case .email: return .emailAddress
        case .number: return .numberPad
        case .URL: return .URL
        }
    }
}
#endif

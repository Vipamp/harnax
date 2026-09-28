import SwiftUI

/// The three non-happy paths every list screen needs, so they look the same everywhere.
public struct HXStateView: View {
    public enum Kind {
        case loading
        case empty
        case error
    }

    private let kind: Kind
    private let message: String?
    private let retry: (() -> Void)?

    public init(_ kind: Kind, message: String? = nil, retry: (() -> Void)? = nil) {
        self.kind = kind
        self.message = message
        self.retry = retry
    }

    public var body: some View {
        VStack(spacing: 10) {
            glyph
            HXText(titleKey)
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
            subtitle
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .multilineTextAlignment(.center)
            if let retry {
                Button {
                    retry()
                } label: {
                    HXText("common.retry")
                }
                .buttonStyle(.hxInline)
                .padding(.top, 4)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var glyph: some View {
        if let symbol = glyphSpec.symbol {
            Image(systemName: symbol)
                .font(.system(size: 34))
                .foregroundStyle(Color.hx(glyphSpec.tone))
        } else {
            ProgressView()
                .tint(Color.hx(.brand))
        }
    }

    /// Loading has no glyph, which is why the symbol is optional rather than an empty string.
    private var glyphSpec: (symbol: String?, tone: PaletteSlot) {
        switch kind {
        case .loading: (nil, .textTertiary)
        case .empty: ("tray", .textTertiary)
        case .error: ("exclamationmark.triangle", .danger)
        }
    }

    private var titleKey: String {
        switch kind {
        case .loading: return "state.loading"
        case .empty: return "state.empty.title"
        case .error: return "state.error.title"
        }
    }

    /// Either the caller's own sentence or the kind's generic hint — never both, so a screen that has said
    /// why it failed does not then get told to check its network. Loading has nothing to add to its title.
    @ViewBuilder
    private var subtitle: some View {
        if let message {
            Text(verbatim: message)
        } else {
            switch kind {
            case .loading: EmptyView()
            case .empty: HXText("state.empty.hint")
            case .error: HXText("state.error.hint")
            }
        }
    }
}

/// Section heading inside a grouped screen — the mockup's `.sec` label.
public struct HXSectionHeader: View {
    private let titleKey: String

    public init(_ titleKey: String) {
        self.titleKey = titleKey
    }

    public var body: some View {
        HXText(titleKey)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.hx(.textTertiary))
            .textCase(.uppercase)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }
}

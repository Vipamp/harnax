import SwiftUI

/// Settings / list row inside an `HXGroupCard`: optional tinted glyph, localised title, optional value
/// under that title, trailing accessory.
public struct HXRow<Trailing: View>: View {
    /// Either a catalogue key or copy that is already final — a language name belongs in its own language.
    private enum RowTitle {
        case key(String)
        case verbatim(String)
    }

    private let title: RowTitle
    private let subtitle: String?
    private let systemImage: String?
    private let tone: PaletteSlot
    private let divider: Bool
    private let trailing: Trailing

    public init(
        _ titleKey: String,
        subtitle: String? = nil,
        systemImage: String? = nil,
        tone: PaletteSlot = .brand,
        divider: Bool = true,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) {
        self.title = .key(titleKey)
        self.subtitle = subtitle
        self.systemImage = systemImage
        self.tone = tone
        self.divider = divider
        self.trailing = trailing()
    }

    public init(
        text: String,
        subtitle: String? = nil,
        systemImage: String? = nil,
        tone: PaletteSlot = .brand,
        divider: Bool = true,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) {
        self.title = .verbatim(text)
        self.subtitle = subtitle
        self.systemImage = systemImage
        self.tone = tone
        self.divider = divider
        self.trailing = trailing()
    }

    @ViewBuilder
    private var titleView: some View {
        switch title {
        case let .key(key):
            HXText(key)
        case let .verbatim(text):
            Text(verbatim: text)
        }
    }

    /// A value that would only fit a full row width — two base URLs, say — rides under the title instead of
    /// being truncated in the trailing slot.
    @ViewBuilder
    private var label: some View {
        VStack(alignment: .leading, spacing: 3) {
            titleView
                .font(.body)
                .foregroundStyle(Color.hx(.textPrimary))
            if let subtitle {
                Text(verbatim: subtitle)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    public var body: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.hx(tone))
                    .frame(width: 26)
            }
            label
            Spacer(minLength: 8)
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .bottom) {
            if divider {
                Color.hx(.separator)
                    .frame(height: 1)
                    .padding(.leading, systemImage == nil ? 14 : 52)
            }
        }
    }
}

/// Trailing affordance on a row that pushes another screen.
public struct HXChevron: View {
    public init() {}

    public var body: some View {
        Image(systemName: "chevron.forward")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.hx(.textTertiary))
    }
}

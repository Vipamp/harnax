import SwiftUI

/// Settings / list row inside an `HXGroupCard`: optional tinted glyph, localised title, trailing accessory.
public struct HXRow<Trailing: View>: View {
    private let titleKey: String
    private let systemImage: String?
    private let tone: PaletteSlot
    private let divider: Bool
    private let trailing: Trailing

    public init(
        _ titleKey: String,
        systemImage: String? = nil,
        tone: PaletteSlot = .brand,
        divider: Bool = true,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) {
        self.titleKey = titleKey
        self.systemImage = systemImage
        self.tone = tone
        self.divider = divider
        self.trailing = trailing()
    }

    public var body: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.hx(tone))
                    .frame(width: 26)
            }
            HXText(titleKey)
                .font(.body)
                .foregroundStyle(Color.hx(.textPrimary))
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

/// Right-hand value text, e.g. the tenant name on the switch-tenant row.
public struct HXValueText: View {
    private let value: String

    public init(_ value: String) {
        self.value = value
    }

    public var body: some View {
        Text(verbatim: value)
            .font(.subheadline)
            .foregroundStyle(Color.hx(.textSecondary))
            .lineLimit(1)
    }
}

public struct HXChevron: View {
    public init() {}

    public var body: some View {
        Image(systemName: "chevron.forward")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.hx(.textTertiary))
    }
}

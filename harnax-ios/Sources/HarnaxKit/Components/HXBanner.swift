import SwiftUI

/// Inline notice — the mockup's server-address `.banner` on the login screen.
public struct HXBanner: View {
    private let titleKey: String
    private let message: String?
    private let systemImage: String
    private let tone: PaletteSlot

    public init(_ titleKey: String, message: String? = nil, systemImage: String = "info.circle", tone: PaletteSlot = .brand) {
        self.titleKey = titleKey
        self.message = message
        self.systemImage = systemImage
        self.tone = tone
    }

    public var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.hx(tone))
            VStack(alignment: .leading, spacing: 3) {
                HXText(titleKey)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.hx(tone))
                if let message {
                    Text(verbatim: message)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hxFill(tone, alpha: 0.13), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

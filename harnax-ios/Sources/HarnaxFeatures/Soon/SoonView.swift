import SwiftUI
import HarnaxKit

/// The tab bar keeps all five slots so the navigation shape does not move between milestones; this is
/// what a tab whose screens have not landed yet shows instead of an empty list that looks broken.
public struct SoonView: View {
    private let titleKey: String

    public init(titleKey: String) {
        self.titleKey = titleKey
    }

    public var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "hammer")
                .font(.system(size: 34))
                .foregroundStyle(Color.hx(.textTertiary))
            HXText(titleKey)
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
            HXText("soon.title")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.hx(.textSecondary))
            HXText("soon.hint")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .harnaxScreen()
    }
}

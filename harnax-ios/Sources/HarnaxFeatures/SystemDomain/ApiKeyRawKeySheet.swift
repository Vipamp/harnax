import SwiftUI
import HarnaxCore
import HarnaxKit

/// S4 — the one-time key screen.
///
/// `POST /api/admin/api-keys` and `POST /{id}/regenerate` are the only routes in the whole stack that ever
/// carry a usable secret (`ApiKeyCreatedResponse.kt`, cited at `harnax-ios/specs/03-system-domain.md:124`);
/// the database holds a SHA-256 digest (`ApiKeyServiceImpl.kt:80-82`). So the value is shown here, on its
/// own surface, with the notice that says it will not be shown again — and closing this surface destroys
/// the only copy this device ever had.
///
/// Nothing here persists: no `@AppStorage`, no cache, no write into the row model. The row keeps the prefix
/// the server computed from the key, and that is all any later screen can show.
public struct ApiKeyRawKeySheet: View {
    private let created: ApiKeyCreatedSummary
    private let onClose: () -> Void

    @State private var copied = false

    public init(created: ApiKeyCreatedSummary, onClose: @escaping () -> Void) {
        self.created = created
        self.onClose = onClose
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HXBanner("apikey.published.once", systemImage: "exclamationmark.triangle", tone: .warning)

                    VStack(alignment: .leading, spacing: 8) {
                        HXText("apikey.published.label")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.hx(.textPrimary))
                        Text(verbatim: created.rawKey)
                            .font(.callout.monospaced())
                            .foregroundStyle(Color.hx(.textPrimary))
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                        HXText("apikey.published.prefix", created.keyPrefix)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 13, style: .continuous))

                    Button(action: copy) {
                        HXText(copied ? "apikey.published.copied" : "common.copy")
                    }
                    .buttonStyle(.hxPrimary)

                    HXText("apikey.published.hint")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textTertiary))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 28)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: created.name))
            #if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: onClose) { HXText("common.confirm") }
                }
            }
        }
    }

    /// Copy is the point of this screen — nobody retypes a 44-character secret into a config file.
    private func copy() {
        HXPasteboard.copy(created.rawKey)
        copied = true
    }
}

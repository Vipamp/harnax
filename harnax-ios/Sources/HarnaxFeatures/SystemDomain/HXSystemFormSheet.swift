import SwiftUI
import HarnaxKit

/// The chrome both system forms share: a title, a cancel item, and a save item whose readiness the view
/// model decides.
///
/// This lives in the domain rather than in `HarnaxKit` because it is the shape a *write* sheet needs — the
/// kit's `HXBindingSheet` is a read-only drill-down with a single Close item and no submit semantics. The
/// error line is part of the frame on purpose: every failure this screen can hit is a sentence the server
/// wrote, so it belongs above the fields, not in a transient toast.
struct HXSystemFormSheet<Content: View>: View {
    private let titleKey: String
    private let canSubmit: Bool
    private let isSaving: Bool
    private let errorText: String?
    private let onSave: () -> Void
    private let content: Content

    @Environment(\.dismiss) private var dismiss

    init(
        titleKey: String,
        canSubmit: Bool,
        isSaving: Bool,
        errorText: String?,
        onSave: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.titleKey = titleKey
        self.canSubmit = canSubmit
        self.isSaving = isSaving
        self.errorText = errorText
        self.onSave = onSave
        self.content = content()
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let errorText {
                        HXBanner(
                            "state.error.title",
                            message: errorText,
                            systemImage: "exclamationmark.triangle",
                            tone: .danger
                        )
                    }
                    content
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 28)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx(titleKey)))
            #if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { HXText("common.cancel") }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: onSave) {
                        if isSaving {
                            ProgressView()
                        } else {
                            HXText("common.save")
                        }
                    }
                    .disabled(!canSubmit)
                }
            }
        }
        // Sliding the sheet away mid-write would lose a request the server has already taken.
        .interactiveDismissDisabled(isSaving)
    }
}

/// One labelled field block: a caption and the helper line that explains the server's rule.
///
/// The hint is optional because most fields need none, and a card that repeats itself on every row reads as
/// a form built by four people. It arrives resolved rather than as a key because some hints quote a value
/// back — the mask of a sensitive row, say — and that substitution belongs to the caller.
struct HXSystemFieldLabel: View {
    private let titleKey: String
    private let hint: String?

    init(_ titleKey: String, hint: String? = nil) {
        self.titleKey = titleKey
        self.hint = hint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HXText(titleKey)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.hx(.textPrimary))
            if let hint {
                Text(verbatim: hint)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

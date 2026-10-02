import SwiftUI
import HarnaxCore
import HarnaxKit

/// KEY-1's row on F1 — the account's own permanent key, in the shape this app already uses for a key.
///
/// Three conventions, all lifted from the API Key domain rather than invented here:
///
/// - what sits on screen is the server's mask (`HXValueText` over `keyPrefix`, the same pair
///   `ApiKeyRecordCard.keyRow` uses at `ApiKeyListView.swift:252-266`), and a row with no prefix at all says so
///   with that screen's own `apikey.noKey` line instead of a blank;
/// - a raw value only ever appears inside `ApiKeyRawKeySheet`, the one-time surface the create and rotate paths
///   already hand its key to — and copying is that sheet's `HXPasteboard.copy`, not a second copy control here.
///   This screen has no raw value to copy: `GET /my-permanent-key` answers the row DTO, which holds no secret
///   (`ApiKeyResponse.kt:8-41`);
/// - no key is a state, not an error: the `absent` phase prints `me.key.none`, because the backend answers
///   `ResultVo<ApiKeyResponse?>` (`ApiKeyController.kt:128`) and the login path mints the row when it is missing
///   (`AuthServiceImpl.kt:107-114`).
///
/// Not administrator-gated on purpose: O5 (`harnax-ios/DESIGN.md:360`) keeps the management list behind the
/// web console's admin route while the account's own key stays visible, because that read is per user
/// (`ApiKeyController.kt:129`).
public struct MePermanentKeyCard: View {
    @ObservedObject private var vm: MePermanentKeyViewModel
    @State private var showsRegeneratePrompt = false

    public init(vm: MePermanentKeyViewModel) {
        self.vm = vm
    }

    public var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 9) {
                titleRow
                state
                if let error = vm.errorText {
                    HXBanner("me.key.readFailed", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                if vm.canRegenerate { regenerateAction }
            }
        }
        .task { await vm.load() }
        // The same surface the create and rotate paths use, and the only place this screen shows a key that
        // cannot be seen twice. The view model's key is read-only from here, so the sheet is driven by the same
        // derived binding `ApiKeyListView.swift:65-72` uses and the only way it closes is the model's own
        // destroy. `interactiveDismissDisabled` lives inside the sheet, so the drag that would throw the value
        // away unread is refused there, not re-decided here.
        .sheet(isPresented: Binding(
            get: { vm.publishedKey != nil },
            set: { if !$0 { vm.dismissPublishedKey() } }
        )) {
            if let created = vm.publishedKey {
                ApiKeyRawKeySheet(created: created) { vm.dismissPublishedKey() }
            }
        }
        .confirmationDialog(
            Text(verbatim: hx("me.key.regenerate.title")),
            isPresented: $showsRegeneratePrompt,
            titleVisibility: .visible
        ) {
            Button(role: .destructive) {
                showsRegeneratePrompt = false
                Task { await vm.regenerate() }
            } label: {
                HXText("apikey.action.regenerate")
            }
            Button(role: .cancel) {
                showsRegeneratePrompt = false
            } label: {
                HXText("common.cancel")
            }
        } message: {
            Text(verbatim: hx("me.key.regenerate.note"))
        }
    }

    private var titleRow: some View {
        HStack(spacing: 6) {
            HXText("me.key.title")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            if let row = phaseRow, !row.isEnabled {
                HXBadge("state.badge.disabled", tone: .textTertiary)
            }
            Spacer(minLength: 0)
            if vm.isRegenerating {
                ProgressView()
                    .tint(Color.hx(.brand))
            }
        }
    }

    /// The row the screen is showing, if the phase carries one. `isEnabled` alone decides the badge: the
    /// protected row cannot be switched off through the API (`ApiKeyServiceImpl.kt:151-155`), so a `0` here came
    /// from an administrator's hand and is worth marking.
    private var phaseRow: ApiKeySummary? {
        if case let .present(row) = vm.phase { return row }
        return nil
    }

    @ViewBuilder
    private var state: some View {
        switch vm.phase {
        case .loading:
            HXText("state.loading")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
        case .absent:
            HXText("me.key.none")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
        case let .present(row):
            if let mask = row.displayKey {
                HXValueText(mask, lines: 2)
            } else {
                HXText("apikey.noKey")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        case let .failed(message):
            VStack(alignment: .leading, spacing: 6) {
                HXBanner("state.error.title", message: message, systemImage: "exclamationmark.triangle", tone: .danger)
                Button {
                    Task { await vm.load() }
                } label: {
                    HXText("common.retry")
                }
                .buttonStyle(.hxInline)
            }
        }
    }

    private var regenerateAction: some View {
        Button {
            showsRegeneratePrompt = true
        } label: {
            HXText("apikey.action.regenerate")
        }
        .buttonStyle(.hxSecondary)
        .disabled(vm.isRegenerating)
    }
}

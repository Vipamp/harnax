import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// KEY-1 — the signed-in account's own permanent key, as `harnax-ios/DESIGN.md:360` (O5) promises it: the
/// management list stays administrator-gated, but the account's own row is always readable.
///
/// Two routes and three states. `GET /api/admin/api-keys/my-permanent-key` answers the row or nothing
/// (`ApiKeyController.kt:126-136`), and `POST /api/admin/api-keys/regenerate-permanent` replaces it
/// (`:138-148`). What the read hands back is the list row's own DTO (`ApiKeyResponse.kt:8-41`), so the secret
/// is *not* among its fields — only the `keyPrefix` the server cut from it (`ApiKeyServiceImpl.kt:82`). iOS
/// therefore cannot reveal a permanent key it has already lost: the raw value exists on exactly one leg, the
/// rotate, and `ApiKeyRawKeySheet` is the only surface that shows it.
///
/// This is the screen's own model rather than a field list on `MeViewModel`, because what it holds is a secret
/// with a lifetime: one published value that must be destroyable on its own terms, and a write that invalidates
/// credentials other screens are still using.
@MainActor
public final class MePermanentKeyViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        /// The call succeeded and the account has no permanent row. `ApiKeyController.kt:128` types that as
        /// `ResultVo<ApiKeyResponse?>`, and the login path mints one on the spot when it is missing
        /// (`AuthServiceImpl.kt:107-114`) — so this is a state to say out loud, not an error to recover from.
        case absent
        case present(ApiKeySummary)
        case failed(message: String)

        /// Whether a row is on screen: while it is, a later read that fails says so beside the row instead of
        /// replacing the row with a sentence.
        var holdsRow: Bool {
            if case .present = self { return true }
            return false
        }
    }

    @Published public private(set) var phase: Phase = .loading
    /// A failure beside a row that is still on screen — the shape `ApiKeyListViewModel.inlineError` uses.
    @Published public private(set) var errorText: String?
    @Published public private(set) var isRegenerating = false
    /// The new key, published by the rotate alone and by nothing else on this type. `nil` means no secret is
    /// on screen, and `dismissPublishedKey` is the only other writer — the same discipline as
    /// `ApiKeyListViewModel.publishedKey`.
    @Published public private(set) var publishedKey: ApiKeyCreatedSummary?

    private let catalog: any ApiKeyCataloging
    /// One read in flight: the screen re-loads on `.task`, and a rotate re-reads right after it publishes, so
    /// two answers can otherwise race for the same phase.
    private var isLoading = false

    public init(catalog: any ApiKeyCataloging) {
        self.catalog = catalog
    }

    /// The only form of the key this screen can show: the server's own mask, never reassembled
    /// (`ApiKeyServiceImpl.kt:82` — first 12 characters, `...`, last 4). `nil` when the row carries no prefix
    /// at all, which the row renders as its own "nothing to show" line rather than as a blank.
    public var maskedKey: String? {
        if case let .present(row) = phase { return row.displayKey }
        return nil
    }

    /// The rotate is offered only on a row: `regeneratePermanentKey` starts by loading the caller's permanent
    /// row and throws when there is none (`ApiKeyServiceImpl.kt:237-238`), so a button on the `absent` state
    /// could only ever fail.
    public var canRegenerate: Bool {
        if case .present = phase { return true }
        return false
    }

    public func load() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        // A row already on screen stays there while the next answer is on the wire; only the no-row states
        // go back to loading, so a re-read does not blink the card away.
        if !phase.holdsRow { phase = .loading }
        switch await catalog.myPermanentKey() {
        case let .success(row):
            errorText = nil
            phase = row.map(Phase.present) ?? .absent
        case let .failure(error):
            let text = ErrorMessage.text(for: error)
            if phase.holdsRow { errorText = text } else { phase = .failed(message: text) }
        }
    }

    /// Replaces the account's key and publishes the new value once.
    ///
    /// Refused rotations publish nothing: the backend's own sentences — "Permanent API Key not found for user"
    /// (`ApiKeyServiceImpl.kt:238`) above all — come back as a business error and land in `errorText`. A second
    /// call while the first is on the wire is dropped, because two new secrets cannot share one
    /// shown-only-once surface.
    public func regenerate() async {
        guard canRegenerate, !isRegenerating else { return }
        isRegenerating = true
        defer { isRegenerating = false }
        switch await catalog.regenerateMyPermanentKey() {
        case let .success(created):
            errorText = nil
            publishedKey = created
            // The prefix on the row changed with the key, so the card re-reads rather than keeping the old mask
            // — the same reason `ApiKeyListViewModel.regenerate` refreshes.
            await load()
        case let .failure(error):
            errorText = ErrorMessage.text(for: error)
        }
    }

    /// Closing the one-time surface destroys the value. Nothing on this type keeps a copy: `maskedKey` reads
    /// the server's prefix off the row, and no field, cache or `UserDefaults` entry is ever written — the
    /// digest is all the backend itself can offer afterwards (`ApiKeyServiceImpl.kt:80-82`).
    public func dismissPublishedKey() {
        publishedKey = nil
    }
}

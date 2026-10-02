import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The team artifacts drawer: the files this conversation's members published, and one file's bytes.
///
/// The console's `TeamArtifactsDrawer.tsx` is a four-column `Table` with no filter, no sort and no pagination
/// — the route answers the whole list at once (`:29-51`), newest publication first
/// (`TeamArtifactController.kt:60`), and `§iOS 适配注意点 14` says the first iOS build keeps that shape instead
/// of inventing controls the backend does not have. So this view model has exactly two jobs: read the list,
/// and hand one row's bytes to the share sheet.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:59-100`.
/// Both routes live behind `@ConditionalOnProperty(minio = enabled)` (`:42`), and a deployment that leaves the
/// switch off answers the list as an unknown route. `DESIGN.md` O8 gives that its own phase rather than an error:
/// the remedy is a server setting, and neither a red banner nor "no member has published anything" says so.
@MainActor
public final class TeamArtifactsViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        /// No member has published anything yet — the console's own `Empty` (`:143-147`), not a failure.
        case empty
        /// `minio.enabled=false` leaves the whole controller unregistered (`:42`), so the list is not a refusal
        /// but a route that does not exist. A named state, per `DESIGN.md` O8: neither an error nor an empty list.
        case objectStoreDisabled
        case content
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var artifacts: [TeamArtifact] = []
    /// A refused list re-read or a refused download, above rows that stay on screen.
    @Published public private(set) var inlineError: String?
    /// File ids whose bytes are in flight.
    @Published public private(set) var pendingDownloads: Set<String> = []

    private let reading: any TeamArtifactReading
    private let sessionId: String
    private let share: @Sendable (HXSharedFile) -> Void

    public init(
        reading: any TeamArtifactReading,
        sessionId: String,
        share: @escaping @Sendable (HXSharedFile) -> Void = HXFileShare.share
    ) {
        self.reading = reading
        self.sessionId = sessionId
        self.share = share
    }

    /// The list, asked again.
    ///
    /// A failure with rows already on screen is a banner, not a blank drawer — the console clears its table in
    /// that case (`setArtifacts([])`, `:39`, `:43`), which loses information the user could still act on, and
    /// the app's own convention everywhere else is to keep the rows.
    public func load() async {
        inlineError = nil
        if artifacts.isEmpty { phase = .loading }
        switch await reading.teamArtifacts(sessionId: sessionId) {
        case let .success(rows):
            artifacts = rows
            phase = rows.isEmpty ? .empty : .content
        case let .failure(error):
            if artifacts.isEmpty, error == .objectStoreDisabled {
                phase = .objectStoreDisabled
                return
            }
            let text = ErrorMessage.text(for: error)
            if artifacts.isEmpty {
                phase = .failed(text)
            } else {
                inlineError = text
            }
        }
    }

    /// One row's bytes, straight to the share sheet.
    ///
    /// The name is the response's own `Content-Disposition` suggestion when it has one — the controller writes
    /// the row's `fileName` through `sanitizeFileName` before it puts it there
    /// (`TeamArtifactController.kt:96`, `:137-145`) — and the row's `fileName` otherwise, since a download
    /// that never reached a body has no header to read.
    public func download(_ artifact: TeamArtifact) async {
        guard !artifact.fileId.isEmpty, !pendingDownloads.contains(artifact.fileId) else { return }
        pendingDownloads.insert(artifact.fileId)
        defer { pendingDownloads.remove(artifact.fileId) }
        switch await reading.downloadTeamArtifact(fileId: artifact.fileId, sessionId: sessionId) {
        case let .success(file):
            let name = sharedName(for: artifact, suggested: file.fileName)
            share(HXSharedFile(name: name, data: file.data, mimeType: file.mimeType))
            inlineError = nil
        case let .failure(error):
            // 400 for a reference that is not a UUID, 403 for a session this account does not own, and 404
            // both for "no such artifact" and for "not yours" (`:78-86`) — the status is the whole message, so
            // it is shown as it arrived.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// The name the shared file gets, never empty: a row with no `fileName` falls back to the reference, which
    /// is the one identifier this route has.
    public func sharedName(for artifact: TeamArtifact, suggested: String?) -> String {
        WorkspacePath.safeFileName(hxPresented(suggested) ?? artifact.displayName ?? artifact.fileId)
    }

    public func isDownloading(_ artifact: TeamArtifact) -> Bool {
        pendingDownloads.contains(artifact.fileId)
    }
}

import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The sandbox workspace drawer: what is in the container right now, one file's preview, and the two ways a
/// byte crosses the boundary.
///
/// The console's `WorkspaceDrawer.tsx` is the contract here, including the parts of it that look like
/// omissions:
///
/// - the drawer opens on the sandbox's own state, not on the list (`:76-86`, `:186-190`). A conversation whose
///   sandbox is not running shows an empty state and asks for no file listing at all, and a status request
///   that *fails* lands in the same place — the web code swallows the error outright
///   (`catch { // ignore }`, `:83-85`) and then reads `!sandboxStatus?.active`. So "we could not ask" is not
///   an error banner either; claiming a sandbox is up because the question did not answer would be the worse
///   invention.
/// - a failed **read** writes its sentence into the preview area rather than raising a dialog
///   (`:98-101`), because the list next to it is still perfectly good.
/// - the path the drawer believes it is on is committed only when a listing succeeded
///   (`setCurrentPath(path)` sits inside the success branch, `:59-60`), so a refused navigation leaves the
///   rows and the breadcrumb where they were.
///
/// Backend: `AgentProxyController.kt:181-302`, which proxies to the agent's own
/// `SandboxWorkspaceController.kt:90-244`.
@MainActor
public final class WorkspaceViewModel: ObservableObject {
    /// The drawer's four non-happy paths, kept apart because they say three different things.
    public enum Phase: Equatable {
        case loading
        /// The sandbox is not running, or its state could not be read. An ordinary state — the console shows
        /// an `Empty` for it (`:186-190`), never an error.
        case sandboxClosed
        case emptyDirectory
        case content
        /// The listing itself was refused with rows already on screen: `failed` is only for a drawer that has
        /// nothing left to show.
        case failed(String)
    }

    /// One line of the breadcrumb. The root is a name, not a path segment, because `/workspace` is the
    /// drawer's whole world (`WorkspaceDrawer.tsx:39`) and the console's own breadcrumb starts at `workspace`.
    public struct Crumb: Identifiable, Equatable, Sendable {
        public let path: String
        public let label: String
        public var id: String { path }

        public init(path: String, label: String) {
            self.path = path
            self.label = label
        }
    }

    /// The preview area, which is one text block plus the two flags that change what it says.
    public struct Preview: Equatable, Sendable {
        public let path: String
        public let name: String
        public var text: String
        public var isLoading: Bool
        /// The server's own `truncated`, true both when the reader hit its cap and when the sandbox cut the
        /// read (`SandboxWorkspaceController.kt:158-166`) — in the first case `text` is the sentence the
        /// server wrote instead of the file.
        public var isTruncated: Bool
        /// The `Error: …` case (`WorkspaceDrawer.tsx:98-101`). Kept on the preview rather than as a banner so
        /// the list beside it stays the focus.
        public var isFailure: Bool

        public init(
            path: String,
            name: String,
            text: String = "",
            isLoading: Bool = false,
            isTruncated: Bool = false,
            isFailure: Bool = false
        ) {
            self.path = path
            self.name = name
            self.text = text
            self.isLoading = isLoading
            self.isTruncated = isTruncated
            self.isFailure = isFailure
        }
    }

    @Published public private(set) var phase: Phase = .loading
    /// Sorted the way the drawer sorts (`WorkspaceFile.sortedForListing`): directories first, then by name.
    @Published public private(set) var files: [WorkspaceFile] = []
    @Published public private(set) var currentPath: String = WorkspacePath.root
    @Published public private(set) var preview: Preview?
    /// A refused listing, upload or download, above rows that stay on screen.
    @Published public private(set) var inlineError: String?
    /// An upload or a download that went through. Kept apart from `inlineError` because a red banner under a
    /// write that succeeded teaches the user to distrust the banner.
    @Published public private(set) var notice: String?
    @Published public private(set) var sandboxStatus: SandboxStatus = .unknown
    @Published public private(set) var isListing = false
    @Published public private(set) var isUploading = false
    /// Paths whose download is in flight — the row's own spinner, since a 50 MB file is the normal case here.
    @Published public private(set) var pendingDownloads: Set<String> = []

    private let workspace: any SessionWorkspaceReading
    private let sessionId: String
    private let share: @Sendable (HXSharedFile) -> Void

    public init(
        workspace: any SessionWorkspaceReading,
        sessionId: String,
        share: @escaping @Sendable (HXSharedFile) -> Void = HXFileShare.share
    ) {
        self.workspace = workspace
        self.sessionId = sessionId
        self.share = share
    }

    // MARK: - opening

    /// The status gate, then the root listing.
    ///
    /// Only `.running` continues. `.idle` is the console's `active: false`, and `.unknown` — the conversation
    /// the batch reply said nothing about (`ChannelCataloging.swift:101-103`) — cannot be claimed as running
    /// either, so both land in the empty state the drawer shows for a stopped sandbox.
    public func load() async {
        inlineError = nil
        notice = nil
        phase = .loading
        switch await workspace.sandboxStatus(sessionId: sessionId) {
        case let .success(status):
            sandboxStatus = status
        case .failure:
            // The web code does not even keep this; the drawer's answer is the same one it gives for
            // `active: false`.
            sandboxStatus = .unknown
        }
        guard sandboxStatus == .running else {
            files = []
            preview = nil
            phase = .sandboxClosed
            return
        }
        await list(WorkspacePath.root)
    }

    /// The refresh button, and the re-read an upload ends with: the directory on screen, asked again.
    public func reload() async {
        guard sandboxStatus == .running else {
            await load()
            return
        }
        await list(currentPath)
    }

    // MARK: - navigation

    /// The breadcrumb: the root, then one crumb per segment below it.
    ///
    /// `WorkspacePath.parent` is what makes the trail climbable without walking back through every listing,
    /// and it stops at the root by itself (`SessionWorkspace.swift:180-184`).
    public var breadcrumbs: [Crumb] {
        var crumbs = [Crumb(path: WorkspacePath.root, label: hx("chat.workspace.root"))]
        let segments = currentPath
            .split(separator: "/")
            .dropFirst()
            .map(String.init)
        var walked = WorkspacePath.root
        for segment in segments {
            walked = WorkspacePath.joined(walked, segment)
            crumbs.append(Crumb(path: walked, label: segment))
        }
        return crumbs
    }

    public var canGoUp: Bool { currentPath != WorkspacePath.root }

    /// Tapping a directory row.
    public func enter(_ file: WorkspaceFile) async {
        guard file.type.isDirectory else { return }
        await list(WorkspacePath.joined(currentPath, file.name))
    }

    /// Tapping a breadcrumb. The console builds the same accumulation (`:210-212`).
    public func goTo(path: String) async {
        guard path != currentPath else { return }
        await list(path)
    }

    /// The "up" affordance, which does nothing at the root rather than asking for `/`.
    public func goUp() async {
        guard canGoUp else { return }
        await list(WorkspacePath.parent(currentPath))
    }

    // MARK: - preview

    /// Tapping a file. A refusal is written into the preview area and changes nothing else
    /// (`WorkspaceDrawer.tsx:98-101`).
    public func read(_ file: WorkspaceFile) async {
        let path = WorkspacePath.joined(currentPath, file.name)
        preview = Preview(path: path, name: file.name, isLoading: true)
        switch await workspace.readWorkspaceFile(sessionId: sessionId, path: path) {
        case let .success(content):
            preview = Preview(
                path: path,
                name: file.name,
                text: content.content,
                isTruncated: content.truncated
            )
        case let .failure(error):
            preview = Preview(
                path: path,
                name: file.name,
                text: Self.failureText(for: error),
                isFailure: true
            )
        }
    }

    public func closePreview() {
        preview = nil
    }

    // MARK: - out

    /// Hands the bytes to the share sheet. Nothing is written to the app's own storage: the console's blob +
    /// `<a download>` (`:291-297`) has no iOS equivalent that keeps a file, and a silent write to the sandbox
    /// would be news the user never asked for.
    public func download(_ file: WorkspaceFile) async {
        let path = WorkspacePath.joined(currentPath, file.name)
        pendingDownloads.insert(path)
        defer { pendingDownloads.remove(path) }
        switch await workspace.downloadWorkspaceFile(sessionId: sessionId, path: path) {
        case let .success(payload):
            let name = sharedName(forPath: path, suggested: payload.name)
            share(HXSharedFile(name: name, data: payload.data, mimeType: payload.mimeType))
            notice = hx("chat.workspace.download.done", name)
        case let .failure(error):
            // 404 for a path that is not there and 413 for one over the 50 MB cap
            // (`AgentProxyController.kt:41`, `:288-292`) are both ordinary and both say so in the banner.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// The name the shared file gets: the response's own suggestion, run through the server's rule so a header
    /// cannot smuggle a directory, and otherwise the name `safeFileName` builds from the path
    /// (`AgentProxyController.kt:268-273`).
    public func sharedName(forPath path: String, suggested: String?) -> String {
        guard let suggested, let trimmed = hxPresented(suggested) else {
            return WorkspacePath.safeFileName(path)
        }
        return WorkspacePath.safeFileName(trimmed)
    }

    // MARK: - in

    /// Uploads into the directory on screen, then re-reads it, because the listing is the only proof the file
    /// arrived: the reply names it (`SandboxWorkspaceController.kt:230-236`) but says nothing about the rest
    /// of the directory.
    ///
    /// A refusal — the sandbox gone mid-flight (404), a path the agent rejects (400), a `docker cp` that
    /// failed (500, whose message is the container's own sentence, `:224-228`) — keeps every row on screen.
    public func upload(fileName: String, mimeType: String, payload: Data) async {
        guard sandboxStatus == .running, !isUploading else { return }
        isUploading = true
        defer { isUploading = false }
        switch await workspace.uploadWorkspaceFile(
            sessionId: sessionId,
            path: currentPath,
            fileName: fileName,
            mimeType: mimeType,
            payload: payload
        ) {
        case let .success(answer):
            inlineError = nil
            notice = hx("chat.workspace.upload.done", hxPresented(answer.fileName) ?? fileName)
            await list(currentPath)
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
    }

    // MARK: - listing

    /// One listing. `currentPath` is committed only on success, the way the console commits it inside its own
    /// success branch (`WorkspaceDrawer.tsx:59-60`): a directory the agent refused leaves both the rows and
    /// the breadcrumb where the user was, with the reason in the banner.
    private func list(_ target: String) async {
        isListing = true
        defer { isListing = false }
        switch await workspace.workspaceFiles(sessionId: sessionId, path: target) {
        case let .success(rows):
            inlineError = nil
            currentPath = target
            files = WorkspaceFile.sortedForListing(rows)
            // A new directory has no preview in it; the console drops the selection for exactly this reason.
            preview = nil
            phase = files.isEmpty ? .emptyDirectory : .content
        case let .failure(error):
            guard ErrorMessage.carriesNews(error) else { return }
            let text = ErrorMessage.text(for: error)
            inlineError = text
            if files.isEmpty {
                phase = .failed(text)
            }
        }
    }

    /// The preview area's own wording of a failure. `Error: …` verbatim, as the console writes it
    /// (`WorkspaceDrawer.tsx:98`), because the sentence is the content here — a banner would take the focus
    /// off the list the user is still reading.
    static func failureText(for error: APIError) -> String {
        "Error: \(ErrorMessage.text(for: error))"
    }
}

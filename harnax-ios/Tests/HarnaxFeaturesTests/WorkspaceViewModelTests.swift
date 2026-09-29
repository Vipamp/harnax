import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The sandbox workspace drawer, in the order its four routes interleave: status first, then the listing the
/// status gates, then a read that must not leave the list, then the two ways a byte crosses the boundary.
///
/// Three behaviours here are the console's, not the app's taste, so each gets its own test: a sandbox that is
/// not running is an empty state and *no* listing request (`WorkspaceDrawer.tsx:76-86`, `:186-190`), a failed
/// read is written into the preview area rather than raised as a dialog (`:98-101`), and a refused upload
/// keeps every row on screen (`:161-163`).
@MainActor
final class WorkspaceViewModelTests: XCTestCase {
    private let root = WorkspacePath.root

    // MARK: - fixtures

    private func entry(_ name: String, _ type: WorkspaceFileKind, size: Int64? = 12) -> WorkspaceFile {
        WorkspaceFile(type: type, name: name, size: size, modified: "2026-09-28T10:20:30+08:00")
    }

    private func box(_ files: WorkspaceFile...) -> [WorkspaceFile] { files }

    /// A sandbox the drawer believes is running, with one listing already committed.
    private func open(
        _ workspace: FakeWorkspaceSandbox,
        rows: [WorkspaceFile],
        shares: ShareBox = ShareBox()
    ) async -> WorkspaceViewModel {
        workspace.statusReplies = [.success(.running)]
        workspace.listReplies = [.success(rows)]
        let vm = WorkspaceViewModel(workspace: workspace, sessionId: "sess-1", share: shares.receiver)
        await vm.load()
        return vm
    }

    private func running(_ rows: [WorkspaceFile] = []) async -> (WorkspaceViewModel, FakeWorkspaceSandbox, ShareBox) {
        let workspace = FakeWorkspaceSandbox()
        let shares = ShareBox()
        let vm = await open(workspace, rows: rows, shares: shares)
        return (vm, workspace, shares)
    }

    // MARK: - the status gate

    /// The drawer is opened by its parent, so a stopped sandbox has to be said *inside* it — and the file
    /// route is not asked, because the agent would answer 404 `No active sandbox for session=…`
    /// (`SandboxWorkspaceController.kt:99`) for something the drawer already knows.
    func testASandboxThatIsNotRunningIsAnEmptyStateWithNoListing() async {
        let workspace = FakeWorkspaceSandbox()
        workspace.statusReplies = [.success(.idle)]
        let shares = ShareBox()
        let vm = WorkspaceViewModel(workspace: workspace, sessionId: "sess-1", share: shares.receiver)
        await vm.load()

        XCTAssertEqual(vm.phase, .sandboxClosed)
        XCTAssertTrue(vm.files.isEmpty)
        XCTAssertEqual(workspace.statusRequests, ["sess-1"], "one status read, addressed by the string key")
        XCTAssertTrue(workspace.listRequests.isEmpty, "a stopped sandbox must not reach the file route")
    }

    /// The console swallows a failed status outright and falls into the same `Empty`
    /// (`:83-85`, then `!sandboxStatus?.active`), so "we could not ask" is not an error either — claiming a
    /// sandbox is up because the question did not answer would be the worse invention.
    func testAStatusThatCouldNotBeReadLandsInTheSameStateNotInAnError() async {
        let workspace = FakeWorkspaceSandbox()
        workspace.statusReplies = [.failure(.offline)]
        let vm = WorkspaceViewModel(workspace: workspace, sessionId: "sess-1")
        await vm.load()

        XCTAssertEqual(vm.phase, .sandboxClosed)
        XCTAssertNil(vm.inlineError)
        XCTAssertTrue(workspace.listRequests.isEmpty)
    }

    /// `.unknown` is a reply that said nothing about this conversation, which is deliberately not `.idle`
    /// (`ChannelCataloging.swift:101-103`) — but it is equally not `running`, so the gate stays shut.
    func testAConversationTheStatusReplySaidNothingAboutStaysBehindTheGate() async {
        let workspace = FakeWorkspaceSandbox()
        workspace.statusReplies = [.success(.unknown)]
        let vm = WorkspaceViewModel(workspace: workspace, sessionId: "sess-1")
        await vm.load()
        XCTAssertEqual(vm.phase, .sandboxClosed)
        XCTAssertTrue(workspace.listRequests.isEmpty)
    }

    func testARunningSandboxAsksForTheRootFirst() async {
        let (vm, workspace, _) = await running()
        XCTAssertEqual(vm.phase, .emptyDirectory, "the console's own second empty sentence (`:224-225`)")
        XCTAssertEqual(workspace.listRequests, [root])
        XCTAssertEqual(vm.currentPath, root)
        XCTAssertNil(vm.inlineError)
    }

    func testARefreshReReadsTheDirectoryWithoutReAskingTheStatus() async {
        let (vm, workspace, _) = await running([entry("a.md", .file)])
        workspace.listReplies = [.success([entry("b.md", .file)])]
        await vm.reload()
        XCTAssertEqual(workspace.statusRequests.count, 1, "a refresh does not re-ask the status it already has")
        XCTAssertEqual(workspace.listRequests, [root, root], "and the refresh button re-reads where we are")
        XCTAssertEqual(vm.files.map(\.name), ["b.md"])
    }

    // MARK: - the listing and its shape

    /// Directories first, then `name.localeCompare` — which is `WorkspaceFile.sortedForListing`, and the
    /// drawer's job is to actually use it (`WorkspaceDrawer.tsx:53-57`).
    func testDirectoriesComeFirstAndNamesSortTheWayTheConsoleSorts() async {
        let rows = box(
            entry("Zebra.md", .file, size: 1),
            entry("apple.md", .file, size: 2),
            entry("reports", .directory, size: 4096),
            entry("link", .symlink, size: 0)
        )
        let (vm, _, _) = await running(rows)
        XCTAssertEqual(vm.files.map(\.name), ["reports", "apple.md", "link", "Zebra.md"])
    }

    func testTappingADirectoryDescendsAndTheBreadcrumbFollows() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.listReplies = [.success([entry("q3.md", .file)])]
        await vm.enter(vm.files[0])

        XCTAssertEqual(workspace.listRequests, [root, "\(root)/reports"])
        XCTAssertEqual(vm.currentPath, "/workspace/reports")
        XCTAssertEqual(vm.files.map(\.name), ["q3.md"])
        XCTAssertEqual(
            vm.breadcrumbs.map(\.path),
            [root, "/workspace/reports"],
            "the trail is the path, rebuilt without another listing"
        )
        XCTAssertEqual(vm.breadcrumbs.last?.label, "reports")
        XCTAssertFalse(vm.breadcrumbs.first!.label.isEmpty)
        XCTAssertTrue(vm.canGoUp)
    }

    /// `WorkspacePath.parent` stops at the root by itself, and the affordance does not send a request when it
    /// has nowhere to go — asking for `/` would be a listing of the container's filesystem root.
    func testGoingUpClimbsOnceAndStopsAtTheRoot() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.listReplies = [.success([entry("q3.md", .file)])]
        await vm.enter(vm.files[0])
        XCTAssertEqual(vm.currentPath, "/workspace/reports")

        workspace.listReplies = [.success([entry("reports", .directory)])]
        await vm.goUp()
        XCTAssertEqual(vm.currentPath, root)
        XCTAssertFalse(vm.canGoUp)

        let requests = workspace.listRequests.count
        await vm.goUp()
        XCTAssertEqual(workspace.listRequests.count, requests, "up at the root sends nothing at all")
    }

    func testTappingABreadcrumbJumpsStraightToThatDirectory() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.listReplies = [.success([entry("2026", .directory)])]
        await vm.enter(vm.files[0])
        workspace.listReplies = [.success([entry("q3.md", .file)])]
        await vm.enter(vm.files[0])
        XCTAssertEqual(vm.currentPath, "/workspace/reports/2026")

        workspace.listReplies = [.success([entry("q3.md", .file)])]
        await vm.goTo(path: "/workspace/reports")
        XCTAssertEqual(workspace.listRequests.last, "/workspace/reports")
        XCTAssertEqual(vm.breadcrumbs.map(\.path), [root, "/workspace/reports"])

        let requests = workspace.listRequests.count
        await vm.goTo(path: vm.currentPath)
        XCTAssertEqual(workspace.listRequests.count, requests, "the crumb already on is not a navigation")
    }

    /// The path is committed inside the success branch only (`:59-60`), so a refused descent leaves the user
    /// exactly where they were with the reason above the rows.
    func testARefusedNavigationKeepsTheRowsAndThePathItAlreadyHad() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.listReplies = [
            .failure(.business(code: 404, message: "Path not found or inaccessible: /workspace/reports"))
        ]
        await vm.enter(vm.files[0])

        XCTAssertEqual(vm.currentPath, root, "the drawer never arrived")
        XCTAssertEqual(vm.files.map(\.name), ["reports"])
        XCTAssertEqual(vm.inlineError, "Path not found or inaccessible: /workspace/reports")
        XCTAssertEqual(vm.phase, .content)
    }

    // MARK: - the preview

    func testTappingAFileReadsItAtTheJoinedPathAndShowsTheText() async {
        let (vm, workspace, _) = await running([entry("notes.md", .file)])
        workspace.readReplies = [.success(WorkspaceFileContent(content: "# hi", truncated: false, size: 4))]
        await vm.read(vm.files[0])

        XCTAssertEqual(workspace.readRequests, ["\(root)/notes.md"])
        XCTAssertEqual(vm.preview?.text, "# hi")
        XCTAssertEqual(vm.preview?.name, "notes.md")
        XCTAssertEqual(vm.preview?.path, "/workspace/notes.md")
        XCTAssertEqual(vm.preview?.isTruncated, false)
        XCTAssertEqual(vm.preview?.isFailure, false)
        XCTAssertNil(vm.inlineError)
    }

    /// The server's `truncated` is true both when the reader hit its cap — in which case `content` is the
    /// sentence it wrote instead (`SandboxWorkspaceController.kt:158-166`) — and when the sandbox cut the
    /// read, so the preview has to say so instead of presenting a part as the whole.
    func testATruncatedReadIsMarkedAsTruncated() async {
        let (vm, workspace, _) = await running([entry("big.log", .file)])
        workspace.readReplies = [
            .success(
                WorkspaceFileContent(
                    content: "[File too large: 9000000 bytes, limit: 5242880 bytes]",
                    truncated: true,
                    size: 9_000_000
                )
            )
        ]
        await vm.read(vm.files[0])
        XCTAssertEqual(vm.preview?.isTruncated, true)
        XCTAssertEqual(vm.preview?.isFailure, false)
        XCTAssertNil(vm.inlineError)
    }

    /// The rule the drawer must not break: a read failure goes *into the preview area*, as the console's own
    /// `Error: …` line (`:98-101`). No dialog, no banner, and the listing untouched.
    func testAFailedReadWritesItsSentenceIntoThePreviewAndRaisesNoDialog() async {
        let (vm, workspace, _) = await running([entry("gone.md", .file)])
        let refusal = APIError.business(code: 404, message: "File not found or inaccessible: /workspace/gone.md")
        workspace.readReplies = [.failure(refusal)]
        await vm.read(vm.files[0])

        let message = ErrorMessage.text(for: refusal)
        XCTAssertEqual(vm.preview?.text, "Error: \(message)", "the preview block is where the news belongs")
        XCTAssertEqual(vm.preview?.isFailure, true)
        XCTAssertEqual(vm.preview?.isLoading, false)
        XCTAssertNil(vm.inlineError, "a banner would pull the eye off the list the user is still reading")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.files.map(\.name), ["gone.md"])
    }

    /// The console writes `Error: Failed to read file` for a transport failure it never names
    /// (`:101`), so the client's own copy key stands in for that sentence rather than a bare `Error:`.
    func testATransportFailureStillLandsInThePreview() async {
        let (vm, workspace, _) = await running([entry("notes.md", .file)])
        workspace.readReplies = [.failure(.timeout)]
        await vm.read(vm.files[0])
        XCTAssertEqual(vm.preview?.text, "Error: \(hx("error.timeout"))")
        XCTAssertEqual(vm.preview?.isFailure, true)
    }

    func testANewDirectoryDropsThePreviewOfTheOldOne() async {
        let (vm, workspace, _) = await running([entry("notes.md", .file), entry("reports", .directory)])
        // Sorted directories first, so `reports` is row 0 and the file is row 1.
        workspace.readReplies = [.success(WorkspaceFileContent(content: "text"))]
        await vm.read(vm.files[1])
        XCTAssertNotNil(vm.preview)

        workspace.listReplies = [.success([])]
        await vm.enter(vm.files[0])
        XCTAssertNil(vm.preview, "the console clears the selection on every new listing (`:60`)")
    }

    // MARK: - out: download

    func testADownloadHandsTheBytesToTheShareSheetUnderTheResolvedName() async {
        let (vm, workspace, shares) = await running([entry("report.pdf", .file)])
        workspace.downloadReplies = [
            .success(WorkspaceDownload(name: "report.pdf", data: Data([1, 2, 3]), mimeType: "application/pdf"))
        ]
        await vm.download(vm.files[0])

        XCTAssertEqual(workspace.downloadRequests, ["\(root)/report.pdf"])
        XCTAssertEqual(shares.shared.count, 1)
        XCTAssertEqual(shares.last?.name, "report.pdf")
        XCTAssertEqual(shares.last?.data, Data([1, 2, 3]))
        XCTAssertEqual(shares.last?.mimeType, "application/pdf")
        XCTAssertTrue(vm.pendingDownloads.isEmpty, "the row's spinner ends with the request")
    }

    /// A `Content-Disposition` is client-influenced text; the router itself sanitises the name it writes there
    /// (`AgentProxyController.kt:268-273`), and the share step has to keep that promise before it puts the
    /// name in a path of its own.
    func testASuggestedNameIsSanitisedAndAnAbsentOneFallsBackToThePath() async {
        let (vm, _, _) = await running([entry("notes.md", .file)])
        XCTAssertEqual(vm.sharedName(forPath: "/workspace/notes.md", suggested: "季度 报告.pdf"), "季度 报告.pdf")
        XCTAssertEqual(vm.sharedName(forPath: "/workspace/a/b.md", suggested: "/etc/passwd"), "passwd")
        XCTAssertEqual(vm.sharedName(forPath: "/workspace/a/b.md", suggested: "  "), "b.md")
        XCTAssertEqual(vm.sharedName(forPath: "/workspace/a/b.md", suggested: nil), "b.md")
        XCTAssertEqual(vm.sharedName(forPath: "/workspace/", suggested: ""), "unnamed")
    }

    func testARefusedDownloadSaysWhyAndSharesNothing() async {
        let (vm, workspace, shares) = await running([entry("huge.bin", .file)])
        workspace.downloadReplies = [.failure(.business(code: 413, message: "File too large to download"))]
        await vm.download(vm.files[0])

        XCTAssertTrue(shares.shared.isEmpty)
        XCTAssertEqual(vm.inlineError, "File too large to download")
        XCTAssertEqual(vm.phase, .content, "the 50 MB cap is news about one row, not about the listing")
        XCTAssertEqual(vm.files.map(\.name), ["huge.bin"])
    }

    // MARK: - in: upload

    /// The upload goes into the directory on screen, and the listing is re-read afterwards because the reply
    /// names only the file that arrived (`SandboxWorkspaceController.kt:230-236`).
    func testAnUploadGoesIntoTheDirectoryOnScreenAndReReadsIt() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.uploadReplies = [
            .success(WorkspaceUpload(fileName: "fresh.txt", path: "/workspace/fresh.txt", size: 2))
        ]
        workspace.listReplies = [.success([entry("reports", .directory), entry("fresh.txt", .file)])]
        await vm.upload(fileName: "fresh.txt", mimeType: "text/plain", payload: Data("hi".utf8))

        XCTAssertEqual(workspace.uploadRequests.count, 1)
        XCTAssertEqual(workspace.uploadRequests.last?.path, root)
        XCTAssertEqual(workspace.uploadRequests.last?.fileName, "fresh.txt")
        XCTAssertEqual(workspace.uploadRequests.last?.mimeType, "text/plain")
        XCTAssertEqual(workspace.uploadRequests.last?.bytes, 2)
        XCTAssertEqual(workspace.listRequests, [root, root], "the directory is asked again")
        XCTAssertEqual(vm.files.map(\.name), ["reports", "fresh.txt"])
        XCTAssertEqual(vm.notice, hx("chat.workspace.upload.done", "fresh.txt"))
        XCTAssertNil(vm.inlineError)
        XCTAssertFalse(vm.isUploading)
    }

    func testAnUploadIntoAChildDirectoryUsesThatChildsPath() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.listReplies = [.success([entry("q3.md", .file)])]
        await vm.enter(vm.files[0])
        workspace.uploadReplies = [.success(WorkspaceUpload(fileName: "q4.md", path: "/workspace/reports/q4.md", size: 1))]
        workspace.listReplies = [.success([entry("q3.md", .file), entry("q4.md", .file)])]
        await vm.upload(fileName: "q4.md", mimeType: "text/markdown", payload: Data())

        XCTAssertEqual(workspace.uploadRequests.last?.path, "/workspace/reports")
        XCTAssertEqual(vm.files.map(\.name), ["q3.md", "q4.md"])
    }

    /// The agent's refusal arrives as its own sentence — `Invalid path: …`, a `docker cp` error, a 504 timeout
    /// (`:203`, `:220`, `:227`) — and the console shows it as it came (`:162`). The rows never go.
    func testARefusedUploadShowsTheServersSentenceAndKeepsEveryRow() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.uploadReplies = [
            .failure(.business(code: 500, message: "Failed to upload file: No such container"))
        ]
        await vm.upload(fileName: "fresh.txt", mimeType: "text/plain", payload: Data("hi".utf8))

        XCTAssertEqual(vm.inlineError, "Failed to upload file: No such container")
        XCTAssertEqual(vm.files.map(\.name), ["reports"])
        XCTAssertEqual(vm.phase, .content)
        XCTAssertNil(vm.notice)
        XCTAssertEqual(workspace.listRequests.count, 1, "a write that failed is not news the listing needs")
    }

    /// The name the sandbox ends up with is the server's, not the picker's (`AgentProxyController.kt:256`), so
    /// the confirmation quotes the reply back.
    func testTheConfirmationQuotesTheNameTheServerChose() async {
        let (vm, workspace, _) = await running([entry("reports", .directory)])
        workspace.uploadReplies = [.success(WorkspaceUpload(fileName: "unnamed", path: "/workspace/unnamed"))]
        workspace.listReplies = [.success([entry("unnamed", .file)])]
        await vm.upload(fileName: "../../etc/passwd", mimeType: "text/plain", payload: Data())
        XCTAssertEqual(vm.notice, hx("chat.workspace.upload.done", "unnamed"))
    }

    func testAnUploadIsRefusedBeforeTheSandboxIsRunning() async {
        let workspace = FakeWorkspaceSandbox()
        workspace.statusReplies = [.success(.idle)]
        let vm = WorkspaceViewModel(workspace: workspace, sessionId: "sess-1")
        await vm.load()
        await vm.upload(fileName: "a.txt", mimeType: "text/plain", payload: Data())
        XCTAssertTrue(workspace.uploadRequests.isEmpty)
    }
}

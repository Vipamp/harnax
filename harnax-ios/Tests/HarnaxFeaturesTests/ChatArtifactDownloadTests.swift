import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The file row a run's end frame leaves behind, taken from the tap to the share sheet.
///
/// The row itself is the app's own addition — the console draws `EndEvent.attachments` nowhere
/// (`specs/02-session-chat.md` “未确认” 1) — so the whole path is this screen's to get right: the conversation
/// the bytes are addressed with, the name they arrive under, and the one request a twice-tapped row owes.
@MainActor
final class ChatArtifactDownloadTests: XCTestCase {
    private let conversation = ChatConversation(id: "s-1", title: "跑一份报表")

    /// One `EndEvent.attachments` entry, read back through the only door a test target has for it
    /// (`ChatFakes.swift:272-278`): the payload structs keep internal memberwise inits.
    private func attachment(id: String = "f-1", name: String = "report.md") throws -> ChatFileAttachment {
        let json = "[\(ChatFrames.file(id: id, name: name))]"
        return try JSONDecoder().decode([ChatFileAttachment].self, from: Data(json.utf8)).first!
    }

    /// `wired: false` is the deployment with the artifact store switched off, which is the case the row has to
    /// notice rather than attempt.
    private func makeModel(
        conversation: ChatConversation? = nil,
        wired: Bool = true
    ) -> (ChatViewModel, FakeWorkspaceSandbox, ShareBox) {
        let store = FakeWorkspaceSandbox()
        let shares = ShareBox()
        let vm = ChatViewModel(
            streaming: ScriptedChatStream(),
            workspace: wired ? store : nil,
            conversation: conversation ?? self.conversation,
            share: shares.receiver
        )
        return (vm, store, shares)
    }

    /// A download that answers hands the bytes over under the name the response carried, and says so.
    func testARowHandsItsBytesToTheShareSheet() async throws {
        let (vm, store, shares) = makeModel()
        store.attachmentReplies = [
            .success(WorkspaceDownload(name: "report.md", data: Data("one".utf8), mimeType: "text/markdown"))
        ]

        await vm.download(try attachment())

        XCTAssertEqual(shares.shared.count, 1)
        let file = try XCTUnwrap(shares.last)
        XCTAssertEqual(file.name, "report.md")
        XCTAssertEqual(file.data, Data("one".utf8))
        XCTAssertEqual(file.mimeType, "text/markdown")
        XCTAssertEqual(vm.composerNotice, .info(hx("chat.workspace.download.done", "report.md")))
        XCTAssertTrue(vm.pendingDownloads.isEmpty, "the row's spinner comes back down with its bytes")
    }

    /// The frame carries no conversation and no session type — the route is addressed with the one the stream
    /// arrived on (`SessionWorkspace.swift:315-323`), so this is the only place that id can come from.
    func testTheDownloadIsAddressedWithTheConversationOnScreen() async throws {
        let (vm, store, _) = makeModel(conversation: ChatConversation(id: "sess-77", title: "另一场"))
        store.attachmentReplies = [.success(WorkspaceDownload(name: "report.md", data: Data()))]

        await vm.download(try attachment(id: "f-9"))

        XCTAssertEqual(store.attachmentRequests.count, 1)
        XCTAssertEqual(store.attachmentRequests.first?.fileId, "f-9")
        XCTAssertEqual(store.attachmentRequests.first?.sessionId, "sess-77")
    }

    /// A refusal is a banner over a transcript that stays; the row keeps its size and offers another tap.
    func testARefusedDownloadSaysSoAndSharesNothing() async throws {
        let (vm, store, shares) = makeModel()
        store.attachmentReplies = [.failure(.business(code: 404, message: "文件不存在"))]

        await vm.download(try attachment())

        XCTAssertEqual(vm.composerNotice, .error("文件不存在"))
        XCTAssertTrue(shares.shared.isEmpty)
        XCTAssertTrue(vm.pendingDownloads.isEmpty)
    }

    /// The gate is in the model, not on the control: a second tap arrives before the first has had a render to
    /// disable itself with.
    func testARowTappedTwiceWhileItsBytesAreOutSendsOneRequest() async throws {
        let (vm, store, _) = makeModel()
        store.gateAttachments = true
        store.attachmentReplies = [.success(WorkspaceDownload(name: "report.md", data: Data("one".utf8)))]
        let row = try attachment()

        let first = Task { await vm.download(row) }
        for _ in 0..<400 where store.attachmentRequests.isEmpty {
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTAssertTrue(vm.isDownloading(row), "the row shows its own spinner while the bytes are out")

        await vm.download(row)
        XCTAssertEqual(
            store.attachmentRequests.count,
            1,
            "the second tap is dropped, not queued behind the first"
        )

        store.releaseAttachments()
        _ = await first.value
        XCTAssertTrue(vm.pendingDownloads.isEmpty)
    }

    /// The artifact store is optional in the deployment (`OutputFileController.kt:38`), and the read is optional
    /// here for the same reason — so with it unwired the row offers no control at all.
    func testAnUnwiredStoreOffersNoControl() async throws {
        let (vm, _, shares) = makeModel(wired: false)
        XCTAssertFalse(vm.canTakeArtifacts)

        await vm.download(try attachment())

        XCTAssertEqual(vm.composerNotice?.tone, .error)
        XCTAssertTrue(shares.shared.isEmpty)
    }

    /// Bytes still coming back for a conversation the user has left are not news about the one they opened.
    func testLeavingTheConversationDropsAnArtifactStillComing() async throws {
        let (vm, store, shares) = makeModel()
        store.gateAttachments = true
        store.attachmentReplies = [.success(WorkspaceDownload(name: "report.md", data: Data("one".utf8)))]
        let row = try attachment()

        let download = Task { await vm.download(row) }
        for _ in 0..<400 where store.attachmentRequests.isEmpty {
            try await Task.sleep(for: .milliseconds(5))
        }
        vm.bind(ChatConversation(id: "s-2", title: "下一场"))
        store.releaseAttachments()
        _ = await download.value

        XCTAssertTrue(shares.shared.isEmpty, "the share sheet does not open over another conversation")
        XCTAssertNil(vm.composerNotice)
        XCTAssertTrue(vm.pendingDownloads.isEmpty, "the next conversation starts with no row spinning")
    }

    /// `Content-Disposition` repeats whatever the sandbox had, so the name goes through the router's own rule
    /// before it reaches the sheet — the same step the two drawers take (`WorkspaceViewModel.swift:235-243`).
    func testANameCarryingAPathSharesAPlainFile() async throws {
        let (vm, store, shares) = makeModel()
        store.attachmentReplies = [
            .success(WorkspaceDownload(name: "../../evil.txt", data: Data("one".utf8)))
        ]

        await vm.download(try attachment())

        XCTAssertEqual(shares.last?.name, "evil.txt")
    }
}

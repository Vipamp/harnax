import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// E3 — the WeChat QR sheet's flow, against the four behaviours the console shows
/// (`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx`).
///
/// The two that are easy to get wrong and are the reason this file exists: a poll that fails is noise rather
/// than a verdict, because the login state lives in a server-side memory map (`:99-101`), and `SCANNED` must
/// keep polling, because the server only flips to `LOGGED_IN` after the phone confirms — stopping at the scan
/// would leave a finished login unseen.
///
/// Both timings are injected, so these assert on the answers rather than on elapsed time.
@MainActor
final class WechatLoginViewModelTests: XCTestCase {
    private final class Hit {
        var count = 0
    }

    /// The real flow is one read per two seconds; a test only needs the turns to happen.
    private func makeFlow(
        _ catalog: FakeChannels,
        pollInterval: Duration = .milliseconds(5),
        successHold: Duration = .milliseconds(5)
    ) -> WechatLoginViewModel {
        WechatLoginViewModel(catalog: catalog, channelID: 7, pollInterval: pollInterval, successHold: successHold)
    }

    private func catalogWithCode(_ catalog: FakeChannels = FakeChannels()) -> FakeChannels {
        catalog.scanStartReplies = [.success(QR.code())]
        return catalog
    }

    private func update(_ status: String, message: String? = nil) throws -> WechatLoginUpdate {
        try WechatLoginUpdate.stub(status: status, message: message)
    }

    // MARK: - the code itself

    func testAGeneratedCodeShowsTheImageAndStartsPolling() async throws {
        let catalog = catalogWithCode()
        let vm = makeFlow(catalog)
        await vm.begin()
        XCTAssertEqual(vm.phase, .waiting)
        XCTAssertEqual(vm.pngData, QR.png)
        XCTAssertEqual(catalog.scanStarts, [7], "the sheet is addressed by the channel row's id")
        try await waitUntil { catalog.scanPolls >= 1 }
    }

    func testARefusedGenerateShowsTheReasonAndNeverPolls() async throws {
        let catalog = FakeChannels()
        catalog.scanStartReplies = [.failure(.offline)]
        let vm = makeFlow(catalog)
        await vm.begin()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertNil(vm.pngData)
        XCTAssertEqual(catalog.scanPolls, 0, "there is no code to wait on")
    }

    /// A reply that carries no readable image is a failed generate, not an empty QR — an empty frame would
    /// invite the operator to scan nothing.
    func testAReplyWithNoDecodableImageIsAFailure() async throws {
        let catalog = FakeChannels()
        catalog.scanStartReplies = [.success(QR.code(""))]
        let vm = makeFlow(catalog)
        await vm.begin()
        XCTAssertEqual(vm.phase, .failed(hx("error.unpackable")))
        XCTAssertNil(vm.pngData)
        XCTAssertEqual(catalog.scanPolls, 0)
    }

    func testReStartingReplacesTheCodeInsteadOfStackingTwoPolls() async throws {
        let catalog = catalogWithCode(FakeChannels())
        catalog.scanStartReplies = [.success(QR.code()), .success(QR.code())]
        let vm = makeFlow(catalog, pollInterval: .milliseconds(60))
        await vm.begin()
        let first = vm.pngData
        await vm.begin()
        XCTAssertEqual(catalog.scanStarts, [7, 7])
        XCTAssertEqual(vm.pngData, first)
        XCTAssertEqual(vm.phase, .waiting)
    }

    // MARK: - the poll's answers

    func testTheScanKeepsPollingUntilThePhoneConfirms() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("SCANNED")), .success(try update("LOGGED_IN"))]
        let vm = makeFlow(catalog)
        let hit = Hit()
        vm.onSucceeded = { hit.count += 1 }
        await vm.begin()

        try await waitUntil { vm.phase == .scanned }
        try await waitUntil { vm.phase == .success }
        XCTAssertEqual(catalog.scanPolls, 2, "a scan is a half-done login, so the poll has to keep running")
        try await waitUntil { hit.count == 1 }
    }

    /// Success resolves on a short hold so the state is actually seen before the sheet goes away.
    func testTheSuccessHoldIsWhatClosesTheSheet() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("LOGGED_IN"))]
        let vm = makeFlow(catalog, successHold: .milliseconds(120))
        let hit = Hit()
        vm.onSucceeded = { hit.count += 1 }
        await vm.begin()
        try await waitUntil { vm.phase == .success }
        XCTAssertEqual(hit.count, 0, "the sheet stays up long enough to show it worked")
        try await waitUntil { hit.count == 1 }
    }

    func testAnExpiredCodeStopsThePolling() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("EXPIRED", message: "二维码已过期"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { vm.phase == .expired }
        let polls = catalog.scanPolls
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertEqual(catalog.scanPolls, polls, "an expired code cannot be waited out")
    }

    /// `NOT_LOGIN` means the server holds no login in progress for this channel, which is what an already
    /// scanned-out code has become (`:91-94`).
    func testANoLoginAnswerReadsAsExpiredRatherThanWaitingForever() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("NOT_LOGIN"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { vm.phase == .expired }
    }

    /// The server's own sentence when it writes one.
    func testAnErrorAnswerShowsWhatTheServerSaid() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("ERROR", message: "微信侧返回失败"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { vm.phase == .failed("微信侧返回失败") }
        XCTAssertEqual(catalog.scanPolls, 1)
    }

    /// …and the catalogue's when it does not, so the sheet never renders an empty label.
    func testAnErrorAnswerWithNoMessageFallsBackToTheLocalCopy() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("ERROR"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { vm.phase == .failed(hx("channel.wechat.loginError")) }
    }

    func testAWaitingAnswerChangesNothingAndKeepsTheLoop() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("WAITING")), .success(try update("WAITING"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { catalog.scanPolls >= 2 }
        XCTAssertEqual(vm.phase, .waiting)
        XCTAssertEqual(vm.pngData, QR.png)
    }

    /// A dropped poll is noise: the state lives server-side, so the QR stays up and the loop keeps turning
    /// (`WechatLoginModal.tsx:99-101`).
    func testATransientPollFailureLeavesTheCodeOnScreen() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.failure(.timeout), .success(try update("SCANNED"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { vm.phase == .scanned }
        XCTAssertGreaterThanOrEqual(catalog.scanPolls, 2, "the failure is retried on the next tick, not surfaced")
        XCTAssertEqual(vm.pngData, QR.png)
    }

    func testAnUnknownStatusCodeKeepsPollingRatherThanEndingTheFlow() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("SOMETHING_NEW"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { catalog.scanPolls >= 2 }
        XCTAssertEqual(vm.phase, .waiting)
    }

    // MARK: - closing the sheet

    /// An unresolved login owes the server a cancel, so the next open starts a fresh one.
    func testClosingBeforeTheLoginResolvesCancelsIt() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("WAITING"))]
        let vm = makeFlow(catalog, pollInterval: .milliseconds(60))
        await vm.begin()
        await vm.dismiss()
        XCTAssertEqual(catalog.scanCancels, [7])
        XCTAssertFalse(vm.phase.isResolved, "a cancel is owed exactly because the login never resolved")

        let polls = catalog.scanPolls
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(catalog.scanPolls, polls, "closing stops the loop rather than leaving it running")
    }

    /// A finished login must not be cancelled — the credentials are already written into `configJson`.
    func testClosingAfterASuccessCancelsNothing() async throws {
        let catalog = catalogWithCode()
        catalog.scanPollReplies = [.success(try update("LOGGED_IN"))]
        let vm = makeFlow(catalog)
        await vm.begin()
        try await waitUntil { vm.phase == .success }
        await vm.dismiss()
        XCTAssertTrue(catalog.scanCancels.isEmpty, "a resolved login has nothing left to drop")
    }

    /// A cancel that itself fails is ignored: there is nothing the operator can do about it and the sheet is
    /// already going away.
    func testAFailedCancelIsStillAQuietClose() async throws {
        let catalog = catalogWithCode()
        catalog.scanCancelReplies = [.failure(.offline)]
        let vm = makeFlow(catalog)
        await vm.begin()
        await vm.dismiss()
        XCTAssertEqual(catalog.scanCancels, [7])
        XCTAssertEqual(vm.phase, .waiting, "and the sheet's own state is untouched by it")
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

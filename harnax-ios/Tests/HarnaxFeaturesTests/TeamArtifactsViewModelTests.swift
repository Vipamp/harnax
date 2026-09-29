import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The team artifacts drawer: one list, one byte route, and the three refusals that are ordinary answers
/// rather than bugs.
///
/// The route only exists at all when the server runs with object storage enabled
/// (`TeamArtifactController.kt:42`), so a deployment without it answers as an unknown route — which is a
/// failure the drawer shows (`testAListRefusalBecomesTheFailureStateWithTheServersSentence`), not a state it
/// renames "empty".
@MainActor
final class TeamArtifactsViewModelTests: XCTestCase {
    private let session = "sess-team-1"

    private func artifact(
        _ id: String,
        name: String,
        mime: String = "text/markdown",
        bytes: Int64 = 2048,
        published: String = "2026-09-28T10:20:30+08:00"
    ) -> TeamArtifact {
        TeamArtifact(
            fileId: id,
            fileName: name,
            mimeType: mime,
            sizeBytes: bytes,
            memberAgentId: 7,
            createTime: published
        )
    }

    private func makeVM(
        _ store: FakeTeamArtifactStore,
        shares: ShareBox = ShareBox()
    ) -> (TeamArtifactsViewModel, ShareBox) {
        (TeamArtifactsViewModel(reading: store, sessionId: session, share: shares.receiver), shares)
    }

    private func loaded(
        _ rows: [TeamArtifact]
    ) async -> (TeamArtifactsViewModel, FakeTeamArtifactStore, ShareBox) {
        let store = FakeTeamArtifactStore()
        store.listReplies = [.success(rows)]
        let (vm, shares) = makeVM(store)
        await vm.load()
        return (vm, store, shares)
    }

    // MARK: - the list

    func testTheListIsAddressedByTheStringSessionKeyAndComesBackAsRows() async {
        let rows = [artifact("3f1a2b3c-0000-0000-0000-000000000001", name: "摘要.md")]
        let (vm, store, _) = await loaded(rows)

        XCTAssertEqual(store.listRequests, [session], "no page parameters — the route answers the whole list")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.artifacts.count, 1)
        XCTAssertNil(vm.inlineError)
    }

    /// The row's own `subtitle` is what the console's second line renders, `mimeType · formatSize(sizeBytes)`
    /// (`TeamArtifactsDrawer.tsx:76-78`), and `shortFileId` is the eight-character tag with the ellipsis
    /// (`:99`). Both are asserted here so a change in the DTO shows up as a broken drawer.
    func testTheRowCarriesTheSubtitlesTheTableRenders() async {
        let (vm, _, _) = await loaded([
            artifact("3f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b", name: "report.csv", mime: "text/csv", bytes: 1536)
        ])
        let row = vm.artifacts[0]
        XCTAssertEqual(row.subtitle, "text/csv · 1.5 KB")
        XCTAssertEqual(row.shortFileId, "3f1a2b3c…")
        XCTAssertEqual(row.displayName, "report.csv")
        XCTAssertEqual(row.createTime, "2026-09-28T10:20:30+08:00")
    }

    /// "No member has published one yet" is the console's own `Empty` (`:143-147`).
    func testAnEmptyListIsAnEmptyStateNotAFailure() async {
        let (vm, store, _) = await loaded([])
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.artifacts.isEmpty)
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(store.listRequests.count, 1)
    }

    /// A deployment without `minio.enabled` answers this path as an unknown route; the drawer says so in the
    /// envelope's own sentence instead of inventing a state for it.
    func testAListRefusalBecomesTheFailureStateWithTheServersSentence() async {
        let store = FakeTeamArtifactStore()
        store.listReplies = [.failure(.business(code: 500, message: "No permission to access this session"))]
        let (vm, _) = makeVM(store)
        await vm.load()

        XCTAssertEqual(vm.phase, .failed("No permission to access this session"))
        XCTAssertTrue(vm.artifacts.isEmpty)
        XCTAssertNil(vm.inlineError, "there are no rows for a banner to sit above")
    }

    func testARefreshThatFailedKeepsTheRowsAndRaisesABanner() async {
        let (vm, store, _) = await loaded([artifact("f-id-1", name: "摘要.md")])
        store.listReplies = [.failure(.timeout)]
        await vm.load()

        XCTAssertEqual(vm.phase, .content, "a re-read that failed must not blank the drawer")
        XCTAssertEqual(vm.artifacts.count, 1)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    func testASecondRequestIsTheRefreshTheUserAskedForAndNothingElse() async {
        let (vm, store, _) = await loaded([artifact("f-id-1", name: "a.md")])
        store.listReplies = [.success([artifact("f-id-1", name: "a.md"), artifact("f-id-2", name: "b.md")])]
        await vm.load()
        XCTAssertEqual(store.listRequests.count, 2)
        XCTAssertEqual(vm.artifacts.count, 2)
        XCTAssertEqual(vm.phase, .content)
    }

    // MARK: - the bytes

    func testADownloadAsksForThatReferenceAndSharesTheBytesUnderTheRowName() async {
        let (vm, store, shares) = await loaded([
            artifact("3f1a2b3c-0000-0000-0000-0000000000aa", name: "plan.md", mime: "text/markdown")
        ])
        store.downloadReplies = [
            .success(TeamArtifactFile(data: Data("body".utf8), fileName: "plan.md", mimeType: "text/markdown"))
        ]
        await vm.download(vm.artifacts[0])

        XCTAssertEqual(store.downloadRequests.count, 1)
        XCTAssertEqual(
            store.downloadRequests.first?.fileId,
            "3f1a2b3c-0000-0000-0000-0000000000aa",
            "the reference is the only handle this route takes (`TeamArtifactController.kt:72-77`)"
        )
        XCTAssertEqual(store.downloadRequests.first?.sessionId, session)
        XCTAssertEqual(shares.last?.name, "plan.md")
        XCTAssertEqual(shares.last?.data, Data("body".utf8))
        XCTAssertEqual(shares.last?.mimeType, "text/markdown")
        XCTAssertTrue(vm.pendingDownloads.isEmpty)
        XCTAssertNil(vm.inlineError)
    }

    /// A row that half-decoded — no `fileName` — still has to share something with a usable name, because the
    /// reference is the one field this route guarantees.
    func testNameResolutionPrefersTheServerThenTheRowThenTheReference() {
        let row = artifact("", name: "摘要.md")
        let (vm, _) = makeVM(FakeTeamArtifactStore())
        XCTAssertEqual(vm.sharedName(for: row, suggested: "server-name.txt"), "server-name.txt")
        XCTAssertEqual(vm.sharedName(for: row, suggested: ""), "摘要.md")
        XCTAssertEqual(vm.sharedName(for: row, suggested: nil), "摘要.md")

        let nameless = TeamArtifact(fileId: "abc-123-def", fileName: "", mimeType: "text/plain", sizeBytes: 0, createTime: "")
        XCTAssertEqual(vm.sharedName(for: nameless, suggested: nil), "abc-123-def")
        XCTAssertEqual(vm.sharedName(for: nameless, suggested: "../secrets"), "secrets")
    }

    func testARefusedDownloadSaysWhyAndSharesNothing() async {
        let (vm, store, shares) = await loaded([artifact("f-id-1", name: "a.md")])
        // 404 doubles as "that reference is not yours" so the route cannot confirm it exists (`:81-86`).
        store.downloadReplies = [.failure(.business(code: 404, message: "Artifact not found"))]
        await vm.download(vm.artifacts[0])

        XCTAssertTrue(shares.shared.isEmpty)
        XCTAssertEqual(vm.inlineError, "Artifact not found")
        XCTAssertEqual(vm.phase, .content, "one row's failure is not news about the list")
        XCTAssertTrue(vm.pendingDownloads.isEmpty)
    }

    /// A 400 for a reference that is not a UUID (`:78`) never reaches the bucket, and the drawer has no way to
    /// pre-judge it, so the status is the message.
    func testAMalformedReferenceSendsNoRequestAtAll() async {
        let (vm, store, shares) = await loaded([artifact("", name: "a.md")])
        await vm.download(vm.artifacts[0])
        XCTAssertTrue(store.downloadRequests.isEmpty, "an empty reference addresses nothing")
        XCTAssertTrue(shares.shared.isEmpty)
    }

    func testARowTappedTwiceWhileItsBytesAreOutSendsOneRequest() async throws {
        let (vm, store, _) = await loaded([artifact("f-id-1", name: "a.md")])
        store.gateDownloads = true
        store.downloadReplies = [
            .success(TeamArtifactFile(data: Data("one".utf8), fileName: "a.md", mimeType: "text/plain"))
        ]
        let first = Task { await vm.download(vm.artifacts[0]) }
        for _ in 0..<400 where store.downloadRequests.isEmpty {
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTAssertEqual(vm.pendingDownloads, ["f-id-1"], "the row shows its own spinner while the bytes are out")

        await vm.download(vm.artifacts[0])
        XCTAssertEqual(store.downloadRequests.count, 1, "the second tap is dropped, not queued behind the first")

        store.releaseDownloads()
        await first.value
        XCTAssertTrue(vm.pendingDownloads.isEmpty)
    }
}

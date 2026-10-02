import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// KEY-1 — the account screen's permanent key: what the two per-user routes answer, what the row may show, and
/// what happens to a secret that exists once.
///
/// The three invariants this file exists to pin:
///
/// - a `null` from `GET /my-permanent-key` means the account has no key and is never a failure
///   (`ApiKeyController.kt:128`);
/// - a mask never travels back out as if it were a value — the rotate carries no body at all;
/// - a raw key lives in one property and dies when the one-time surface closes: no `UserDefaults`, no cache
///   file, no copy in the row model.
@MainActor
final class MePermanentKeyTests: XCTestCase {
    // MARK: - the read

    func testTheRowShowsTheServersMaskAndNothingMore() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [.success(try MeKeyFixture.row())]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()

        guard case let .present(row) = vm.phase else { return XCTFail("expected the row, got \(vm.phase)") }
        XCTAssertEqual(vm.maskedKey, MeKeyFixture.mask)
        XCTAssertEqual(row.id, 41)
        XCTAssertEqual(row.displayKey, MeKeyFixture.mask)
        XCTAssertNil(vm.errorText)
        XCTAssertNil(vm.publishedKey, "a read never carries a secret")
    }

    /// The whole point of `ResultVo<ApiKeyResponse?>`: this account has no row, which is a fact to print, not
    /// an error to recover from.
    func testANullAnswerIsNoKeyRatherThanAFailure() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [.success(nil)]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()

        XCTAssertEqual(vm.phase, .absent)
        XCTAssertNil(vm.errorText)
        XCTAssertNil(vm.maskedKey)
        XCTAssertEqual(double.readCalls, 1)
        XCTAssertNotEqual(hx("me.key.none"), "me.key.none", "the no-key line has to resolve")
    }

    /// `regeneratePermanentKey` loads the caller's row first and throws when there is none
    /// (`ApiKeyServiceImpl.kt:237-238`), so the button that would only fail is not offered.
    func testTheNoKeyStateOffersNoRotation() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [.success(nil)]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()

        XCTAssertFalse(vm.canRegenerate)
        await vm.regenerate()
        XCTAssertEqual(double.rotateCalls, 0)
        XCTAssertNil(vm.publishedKey)
    }

    func testAFailedReadKeepsItsOwnSentenceAndCanBeRetried() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [
            .failure(.business(code: 500, message: "service temporarily unavailable")),
            .success(try MeKeyFixture.row()),
        ]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()
        XCTAssertEqual(vm.phase, .failed(message: "service temporarily unavailable"), "the backend's own text wins")

        await vm.load()
        guard case .present = vm.phase else { return XCTFail("the retry never brought the row back") }
        XCTAssertNil(vm.errorText)
    }

    /// A row that exists but whose prefix column is blank is the list screen's own "nothing to show" case; the
    /// mask is never invented on this side.
    func testARowWithoutAPrefixStillHasNoInventedValue() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [.success(try MeKeyFixture.rowWithoutMask())]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()

        XCTAssertNil(vm.maskedKey)
        XCTAssertTrue(vm.canRegenerate, "the row is there, only its display form is missing")
    }

    // MARK: - the rotation

    func testARotationPublishesTheKeyOnceThenReReadsTheMask() async throws {
        let double = MePermanentKeyDouble()
        let before = try MeKeyFixture.row()
        // The row the next read answers with: the server recomputed the prefix from the new key
        // (`ApiKeyServiceImpl.kt:242`).
        let after = try MeKeyFixture.row(keyPrefix: "hnx_sk_live_ne...0001")
        double.readReplies = [.success(before), .success(after)]
        double.rotateReplies = [.success(MeKeyFixture.created())]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()
        XCTAssertEqual(vm.maskedKey, MeKeyFixture.mask)

        await vm.regenerate()

        XCTAssertEqual(vm.publishedKey, MeKeyFixture.created())
        XCTAssertEqual(double.readCalls, 2, "the mask changed with the key, so the row is re-read")
        XCTAssertEqual(vm.maskedKey, "hnx_sk_live_ne...0001")
        XCTAssertFalse(vm.isRegenerating)
    }

    func testClosingTheOneTimeSurfaceDestroysTheKey() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [.success(try MeKeyFixture.row()), .success(try MeKeyFixture.row())]
        double.rotateReplies = [.success(MeKeyFixture.created())]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()
        await vm.regenerate()
        guard let secret = vm.publishedKey?.rawKey else { return XCTFail("the rotation published nothing") }
        XCTAssertEqual(secret, MeKeyFixture.raw)

        vm.dismissPublishedKey()

        XCTAssertNil(vm.publishedKey)
        // The mask is still on screen and it is still the server's mask, not a reassembled value.
        XCTAssertEqual(vm.maskedKey, MeKeyFixture.mask)
        XCTAssertNotEqual(vm.maskedKey, secret)
        XCTAssertFalse(MeKeyStorage.holds(secret), "a dismissed secret must not survive anywhere on the device")
    }

    func testARefusedRotationPublishesNothing() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [.success(try MeKeyFixture.row())]
        double.rotateReplies = [.failure(.business(code: 500, message: "Permanent API Key not found for user: 7"))]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()

        await vm.regenerate()

        XCTAssertNil(vm.publishedKey)
        XCTAssertEqual(vm.errorText, "Permanent API Key not found for user: 7")
        guard case .present = vm.phase else { return XCTFail("a failed write may not throw the row away") }
    }

    /// Two answers, one shown-once surface: the second rotate is refused before it reaches the wire.
    func testASecondRotationWhileTheFirstIsOnTheWireIsDropped() async throws {
        let double = MePermanentKeyDouble()
        double.readReplies = [.success(try MeKeyFixture.row()), .success(try MeKeyFixture.row())]
        double.rotateReplies = [.success(MeKeyFixture.created()), .success(MeKeyFixture.created(id: 42))]
        double.gateRotate = true
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()

        let first = Task { await vm.regenerate() }
        for _ in 0..<50 where double.rotateCalls == 0 { await Task.yield() }
        XCTAssertEqual(double.rotateCalls, 1, "the first rotate never reached the wire")

        await vm.regenerate()
        XCTAssertEqual(double.rotateCalls, 1, "and no second secret goes out behind it")

        double.releaseRotations()
        await first.value
        XCTAssertEqual(vm.publishedKey?.id, 41)
        XCTAssertFalse(vm.isRegenerating)
    }

    /// The account screen reads its own key; it never opens the management list to do it — and a mask that came
    /// off a list row would be the one way this screen could hand a value back that is not a value.
    func testTheAccountReadNeverWalksIntoTheManagementRoutes() async throws {
        let double = MePermanentKeyDouble()
        // Three reads: the screen's own, the rotate's refresh, and a manual reload.
        double.readReplies = [
            .success(try MeKeyFixture.row()),
            .success(try MeKeyFixture.row()),
            .success(try MeKeyFixture.row()),
        ]
        double.rotateReplies = [.success(MeKeyFixture.created())]
        let vm = MePermanentKeyViewModel(catalog: double)
        await vm.load()
        await vm.regenerate()
        await vm.load()

        XCTAssertEqual(double.listRouteCalls, 0)
        XCTAssertEqual(double.readCalls, 3)
        XCTAssertNil(vm.errorText, "every round trip answered, so nothing was left to apologise for")
    }

    /// A catalog that never learned these two routes says "cannot be read". `absent` is reserved for the server
    /// actually answering null, and this is the case that keeps the two apart.
    func testAnUnwiredCatalogSaysCannotReadNotNoKey() async {
        let vm = MePermanentKeyViewModel(catalog: UnwiredSystem())
        await vm.load()

        guard case .failed = vm.phase else { return XCTFail("an unwired route must not read as no key: \(vm.phase)") }
        XCTAssertNotEqual(vm.phase, .absent)
        XCTAssertFalse(vm.canRegenerate)
    }

    // MARK: - the wire

    /// The path `ResponseMapper` would otherwise take: an absent `data` is `.unpackable` for every payload that
    /// does not declare itself `HarnaxVoid` (`Transport/ResponseMapper.swift:25-28`), and this read is the one
    /// place in the domain where "no data" is the answer.
    func testThePerUserReadHitsItsOwnRouteAndTurnsNullIntoNoKey() async throws {
        let transport = MeKeyTransport()
        transport.enqueue(200, MeKeyHarness.envelope(code: 200, message: "success", data: "null"))
        let keys: any ApiKeyCataloging = MeKeyHarness.client(transport: transport)

        let answer = await keys.myPermanentKey()

        XCTAssertEqual(answer, .success(nil))
        let request = try XCTUnwrap(transport.requests.first, "the read never went out")
        XCTAssertEqual(request.url?.path, "/api/admin/api-keys/my-permanent-key")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertNil(request.httpBody)
    }

    func testThePerUserReadDecodesTheSameRowDTOTheListUses() async throws {
        let transport = MeKeyTransport()
        let body = MeKeyFixture.rowJSON()
        let row = try MeKeyFixture.decode(body)
        transport.enqueue(200, MeKeyHarness.envelope(code: 200, message: "success", data: body))
        let keys: any ApiKeyCataloging = MeKeyHarness.client(transport: transport)

        let answer = await keys.myPermanentKey()

        if case let .success(loaded) = answer, let loaded {
            XCTAssertEqual(loaded, row)
            XCTAssertEqual(loaded.displayKey, MeKeyFixture.mask)
            XCTAssertEqual(loaded.scopes, "chat")
            XCTAssertEqual(loaded.rateLimit, 300, "the permanent row's own limit (`ApiKeyServiceImpl.kt:190-224`)")
            XCTAssertTrue(loaded.isEnabled)
            XCTAssertNil(loaded.expiresAt, "a permanent key carries no expiry")
        } else {
            XCTFail("the row vanished: \(String(describing: transport.requests.first?.url))")
        }
    }

    /// A null the server writes as an explicit JSON null behaves like the dropped key: Jackson omits nulls
    /// (`Envelope.swift:6-7`), so both shapes have to land on `nil`.
    func testThePerUserReadSurvivesADroppedDataKey() async {
        let transport = MeKeyTransport()
        transport.enqueue(200, MeKeyHarness.envelope(code: 200, message: "success", data: nil))
        let keys: any ApiKeyCataloging = MeKeyHarness.client(transport: transport)

        let answer = await keys.myPermanentKey()

        XCTAssertEqual(answer, .success(nil))
    }

    /// The invariant in wire form: the rotate sends nothing at all. There is no field on this route for a mask,
    /// so there is no way to send one back as a value.
    func testThePerUserRotationSendsNoBodyAndCarriesTheNewKeyOnce() async throws {
        let transport = MeKeyTransport()
        transport.enqueue(200, MeKeyHarness.envelope(code: 200, message: "success", data: """
        {"id":41,"name":"permanent_admin","rawKey":"\(MeKeyFixture.raw)","keyPrefix":"\(MeKeyFixture.mask)"}
        """))
        let keys: any ApiKeyCataloging = MeKeyHarness.client(transport: transport)

        let reply = await keys.regenerateMyPermanentKey()

        guard case let .success(created) = reply else {
            return XCTFail("the rotate's own reply never reached the one-time surface: \(reply)")
        }
        XCTAssertEqual(created.rawKey, MeKeyFixture.raw)
        XCTAssertEqual(created.keyPrefix, MeKeyFixture.mask)

        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.url?.path, "/api/admin/api-keys/regenerate-permanent")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertNil(request.httpBody, "no body means nothing to send a mask back in")
    }

    func testABusinessRefusalStaysARefusalRatherThanAnEmptyRow() async {
        let transport = MeKeyTransport()
        transport.enqueue(200, MeKeyHarness.envelope(code: 500, message: "Permanent API Key not found", data: nil))
        let keys: any ApiKeyCataloging = MeKeyHarness.client(transport: transport)

        let answer = await keys.myPermanentKey()

        XCTAssertEqual(
            answer,
            .failure(.business(code: 500, message: "Permanent API Key not found"))
        )
    }

    // MARK: - the surface

    /// What the account screen may hold: the row's mask and nothing shaped like a secret. The raw value belongs
    /// to `ApiKeyRawKeySheet`, which this screen only ever hands the whole reply to — so the text of `Me/**`
    /// must not name `rawKey`, must not copy anything itself, and must not write a key anywhere.
    func testTheAccountScreenNeverNamesTheRawValueItCannotKeep() throws {
        let sources = try MeKeySources.files()
        XCTAssertFalse(sources.isEmpty, "the locator found no Me screens — the gate is reading nothing")
        for file in sources {
            let code = MeKeySources.strippingComments(file.contents)
            XCTAssertFalse(code.contains("rawKey"), "\(file.relative) may not read a raw key: it has none")
            XCTAssertFalse(code.contains("HXPasteboard"), "\(file.relative) copies through the sheet, not itself")
            for forbidden in ["UserDefaults", "write(to:", "KeychainStore", "Cache"] {
                XCTAssertFalse(code.contains(forbidden), "\(file.relative) must persist nothing about the key")
            }
        }
        let row = try MeKeySources.contents(of: "HarnaxFeatures/Me/MePermanentKeyRow.swift")
        XCTAssertTrue(
            row.contains("ApiKeyRawKeySheet(created:"),
            "the one-time surface is the domain's own sheet, not a second one invented here"
        )
        XCTAssertTrue(
            row.contains("dismissPublishedKey()"),
            "and closing it is the model's destroy, which is the only way out"
        )
        XCTAssertTrue(
            row.contains("HXValueText("),
            "the masked form rides the same component the API Key row uses"
        )
    }

    /// The row's title, the no-key line and the confirmation all resolve in both catalogues — the gate the
    /// screen is behind, restated where it is used.
    func testTheRowsOwnCopyResolvesInBothCatalogues() {
        for key in [
            "me.section.key", "me.key.title", "me.key.none", "me.key.readFailed",
            "me.key.regenerate.title", "me.key.regenerate.note",
        ] {
            let text = hx(key)
            XCTAssertFalse(text.isEmpty, "\(key) resolved to nothing")
            XCTAssertNotEqual(text, key, "\(key) is missing from a catalogue")
        }
    }
}

// MARK: - helpers

/// Where a secret must not be found once the sheet closed: the defaults, and any file this process could have
/// left in a scratch directory.
enum MeKeyStorage {
    static func holds(_ secret: String) -> Bool {
        for (key, value) in UserDefaults.standard.dictionaryRepresentation() {
            if key.contains(secret) { return true }
            if let text = value as? String, text.contains(secret) { return true }
        }
        for url in scratchFiles() {
            guard let data = try? Data(contentsOf: url), data.contains(Data(secret.utf8)) else { continue }
            return true
        }
        return false
    }

    /// Non-recursive, and only the small ones: a tripwire, not a disk sweep.
    private static func scratchFiles() -> [URL] {
        let manager = FileManager.default
        var roots = [manager.temporaryDirectory]
        for folder in [FileManager.SearchPathDirectory.cachesDirectory, .applicationSupportDirectory] {
            roots += manager.urls(for: folder, in: .userDomainMask)
        }
        return roots.flatMap { root in
            let found = (try? manager.contentsOfDirectory(
                at: root,
                includingPropertiesForKeys: [.fileSizeKey],
                options: [.skipsSubdirectoryDescendants]
            )) ?? []
            return found.filter { url in
                ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) < 1_000_000
            }
        }
    }
}

/// Reads the account screen's own sources. `TestSources` belongs to the kit target and
/// `SystemDomainSources` to that domain's gates, so the locator here stays local to `Me/`.
enum MeKeySources {
    static let sourcesRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Sources")

    struct File {
        let relative: String
        let contents: String
    }

    static func files() throws -> [File] {
        let directory = sourcesRoot.appendingPathComponent("HarnaxFeatures/Me")
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        return try names.filter { $0.hasSuffix(".swift") }.sorted().map { name in
            File(
                relative: "HarnaxFeatures/Me/\(name)",
                contents: try String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
            )
        }
    }

    static func contents(of relative: String) throws -> String {
        try String(contentsOf: sourcesRoot.appendingPathComponent(relative), encoding: .utf8)
    }

    /// Doc comments legitimately say which field the screen is not allowed to read.
    static func strippingComments(_ source: String) -> String {
        source
            .replacingOccurrences(of: #"/\*[\s\S]*?\*/"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"//.*"#, with: "", options: .regularExpression)
    }
}

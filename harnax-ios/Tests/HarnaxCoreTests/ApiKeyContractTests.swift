import XCTest

@testable import HarnaxCore

/// The API Key domain's contract, pinned against the two real reply shapes.
///
/// The field names are the least interesting part — `ApiKeyResponse.kt:8-41` declares all eleven columns
/// nullable with `default-property-inclusion: non_null`
/// (`harnax-admin/src/main/resources/application.yml`), so what a row can actually do is drop keys rather
/// than send nulls. What is worth a test is the four places where the screen reads something the server
/// never sent:
/// - `enabled` is an `Int`, and *absent* means enabled — the switch has no third position
///   (`ApiKeySummary.isEnabled`);
/// - the three stamps arrive in either the space form or the ISO `T` form and both have to parse
///   (`hxServerDateTime`), while an unreadable one must not read back as "never expires";
/// - `scopes` is a free-text comma string the server never validates
///   (`ApiKeyCreateRequest.kt:14-16`), so a token this side does not know still has to survive an edit;
/// - the create/regenerate reply (`ApiKeyCreatedResponse`) is the only shape in the pair whose four keys
///   are non-null, and it is the only place the raw key exists at all.
///
/// Both api-key fixtures carry invented values (`FixtureLoader.swift:7-8` covers the rest): the raw key is
/// a fake, but a shape-faithful one — 12-character scheme plus 43 base64url characters, which is exactly
/// what `generateRawKey` emits (`ApiKeyServiceImpl.kt:330-334`).
final class ApiKeyContractTests: XCTestCase {
    // MARK: - page rows

    func testPageDecodesFullRowAndSparseRow() throws {
        let page = try Fixture.decode(Envelope<Page<ApiKeySummary>>.self, "api-keys-page-two-rows").data!
        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.records.count, 2)

        let full = page.records[0]
        XCTAssertEqual(full.id, 7)
        XCTAssertEqual(full.title, "ci-pipeline")
        XCTAssertEqual(full.displayKey, "hnx_sk_live_...9f3c")
        XCTAssertEqual(full.tenantId, 1)
        XCTAssertEqual(full.rateLimit, 120)
        XCTAssertEqual(full.creator, "admin")
        XCTAssertTrue(full.isEnabled)
        XCTAssertEqual(full.scopeTokens, ["chat", "manager"])
        XCTAssertFalse(full.hasNoExpiry)
        XCTAssertEqual(full.expiryDate, wallClock(2026, 12, 31, 23, 59, 59), "the space form must parse")

        // Row two is what `non_null` actually produces: no `id`, no tenant, no rate limit, no creator, no
        // update time, and no expiry column at all — which the screen words as "never expires".
        let sparse = page.records[1]
        XCTAssertNil(sparse.id, "the row still has to be decodable without the column `Identifiable` wants")
        XCTAssertEqual(sparse.title, "no-id-row")
        XCTAssertNil(sparse.tenantId)
        XCTAssertNil(sparse.rateLimit)
        XCTAssertNil(sparse.creator)
        XCTAssertFalse(sparse.isEnabled, "explicitly 0, not absent")
        XCTAssertEqual(sparse.scopeTokens, ["chat"])
        XCTAssertTrue(sparse.hasNoExpiry)
        XCTAssertNil(sparse.expiryDate)
    }

    /// A row that does not carry `enabled` at all reads as on: the console's switch has no third position
    /// to put it in.
    func testRowWithoutTheEnabledKeyReadsAsEnabled() throws {
        let row = try decode(ApiKeySummary.self, #"{"id": 9, "name": "k"}"#)
        XCTAssertNil(row.enabled)
        XCTAssertTrue(row.isEnabled)
    }

    // MARK: - expiry

    /// "Never expires" is the column being absent or blank. A stamp this side cannot read is a *different*
    /// case — the row still says it has an expiry, and the screen shows the raw text rather than deciding
    /// for the operator (`ApiKeySummary:47-60`).
    func testUnreadableStampIsNotTheSameAsNoExpiry() throws {
        let never = try decode(ApiKeySummary.self, #"{"id": 1, "name": "k", "expiresAt": null}"#)
        XCTAssertTrue(never.hasNoExpiry)
        XCTAssertNil(never.expiryDate)
        XCTAssertFalse(never.isExpired(at: wallClock(2026, 1, 1)))

        let blank = try decode(ApiKeySummary.self, #"{"id": 2, "name": "k", "expiresAt": "  "}"#)
        XCTAssertTrue(blank.hasNoExpiry)

        let unreadable = try decode(ApiKeySummary.self, #"{"id": 3, "name": "k", "expiresAt": "31/12/2026"}"#)
        XCTAssertFalse(unreadable.hasNoExpiry, "the column is there — only our reading of it failed")
        XCTAssertNil(unreadable.expiryDate)
        XCTAssertFalse(unreadable.isExpired(at: wallClock(2026, 1, 1)), "a stamp we cannot parse is not a date in the past")
    }

    func testExpiryComparesAgainstTheClockItIsGiven() throws {
        let row = try decode(ApiKeySummary.self, #"{"id": 4, "name": "k", "expiresAt": "2026-09-30 12:00:00"}"#)
        XCTAssertTrue(row.isExpired(at: wallClock(2026, 10, 1, 0, 0, 0)))
        XCTAssertFalse(row.isExpired(at: wallClock(2026, 9, 1, 0, 0, 0)))
    }

    /// The one column the reply can answer in either shape — the space form on a list (`hxServerDateTime`
    /// reads `yyyy-MM-dd HH:mm:ss`) and the ISO `T` form on the routes that serialise a `LocalDateTime`
    /// directly (`harnax-ios/HARNESS-NOTES.md:84`) — and the write side only ever sends the latter
    /// (`hxServerDateTimeString`). Reading the `T` form as "no expiry" would silently drop an expiry the
    /// operator just typed in.
    func testExpiryReadsEitherServerSerialisation() throws {
        let spaced = try decode(ApiKeySummary.self, #"{"id": 10, "name": "k", "expiresAt": "2027-03-04 05:06:07"}"#)
        let iso = try decode(ApiKeySummary.self, #"{"id": 11, "name": "k", "expiresAt": "2027-03-04T05:06:07"}"#)
        XCTAssertFalse(spaced.hasNoExpiry)
        XCTAssertFalse(iso.hasNoExpiry)
        XCTAssertEqual(iso.expiryDate, spaced.expiryDate)
        XCTAssertEqual(spaced.expiryDate, wallClock(2027, 3, 4, 5, 6, 7))
    }

    // MARK: - scopes

    /// The three token shapes the string can hold: the canonical pair, either one alone, and one this side
    /// has never heard of. The unknown token has to come back out of an edit, because the console does not
    /// own the vocabulary (`ApiKeyCreateRequest.kt:14-16`).
    func testScopeSplitKeepsForeignTokens() throws {
        let both = try decode(ApiKeySummary.self, #"{"id": 5, "name": "k", "scopes": "chat,manager"}"#)
        XCTAssertEqual(both.scopeSelection.joined, "chat,manager")

        // Out of canonical order and padded, and carrying a scope no client knows.
        let messy = try decode(ApiKeySummary.self, #"{"id": 6, "name": "k", "scopes": " billing ,manager,, chat "}"#)
        XCTAssertEqual(messy.scopeTokens, ["billing", "manager", "chat"], "every token, in the stored order")
        XCTAssertEqual(
            messy.scopeSelection.joined, "chat,manager,billing",
            "canonical order first, foreign tokens after, so nothing is dropped"
        )

        let none = try decode(ApiKeySummary.self, #"{"id": 7, "name": "k"}"#)
        XCTAssertEqual(none.scopeTokens, [])
        XCTAssertEqual(none.scopeSelection.joined, "")
    }

    // MARK: - who may edit a row

    func testManageabilityFollowsTheCreatorColumn() throws {
        let row = try decode(ApiKeySummary.self, #"{"id": 8, "name": "k", "creator": "admin"}"#)
        XCTAssertTrue(row.manageable(by: AccountSnapshot(username: "admin")))
        XCTAssertFalse(row.manageable(by: AccountSnapshot(username: "someone-else")))
        XCTAssertTrue(
            row.manageable(by: AccountSnapshot(username: "someone-else", isAdministrator: true)),
            "an administrator manages every row"
        )
    }

    /// A row with no `creator` key belongs to nobody in particular, so no non-administrator may claim it —
    /// and the nil account (profile not loaded yet) is not a yes either.
    func testRowWithoutCreatorIsUnmanageableForNonAdmin() throws {
        let row = try decode(ApiKeySummary.self, #"{"id": 9, "name": "k"}"#)
        XCTAssertNil(row.creator)
        XCTAssertFalse(row.manageable(by: AccountSnapshot(username: "admin")))
        XCTAssertFalse(row.manageable(by: nil))
        XCTAssertTrue(row.manageable(by: AccountSnapshot(username: "admin", isAdministrator: true)))
    }

    // MARK: - the create/regenerate reply

    func testCreatedReplyCarriesTheRawKeyOnce() throws {
        let created = try Fixture.decode(Envelope<ApiKeyCreatedSummary>.self, "api-key-created").data!
        XCTAssertEqual(created.id, 8)
        XCTAssertEqual(created.name, "ci-pipeline")
        XCTAssertEqual(
            created.keyPrefix, String(created.rawKey.prefix(12)) + "..." + String(created.rawKey.suffix(4)),
            "`rawKey.substring(0, 12) + \"...\" + rawKey.takeLast(4)` (`ApiKeyServiceImpl.kt:82`) — the one "
                + "column a later list row can be matched against the key the operator saved"
        )
        XCTAssertEqual(
            created.rawKey.count, 55,
            "the scheme plus 43 base64url characters of a 32-byte key (`ApiKeyServiceImpl.kt:330-334`); a "
                + "shorter read is a truncated key"
        )
        XCTAssertTrue(created.rawKey.hasPrefix("hnx_sk_live_"))
    }

    /// The one place the `non_null` declaration does real work: `ApiKeyCreatedResponse.kt:59-72` has four
    /// non-optional columns over non-null entity columns, so a reply missing any of them is a reply this
    /// side must refuse rather than show as an empty secret.
    func testCreatedReplyRefusesAnyMissingKey() throws {
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try Fixture.data("api-key-created")) as? [String: Any]
        )
        let data = try XCTUnwrap(object["data"] as? [String: Any])
        XCTAssertEqual(Set(data.keys), ["id", "name", "rawKey", "keyPrefix"], "the fixture is the complete shape")
        for key in data.keys {
            var cut = data
            cut.removeValue(forKey: key)
            let json = try JSONSerialization.data(withJSONObject: cut)
            XCTAssertThrowsError(
                try JSONDecoder().decode(ApiKeyCreatedSummary.self, from: json),
                "a reply without `\(key)` is not a created key"
            )
        }
    }

    // MARK: - helpers

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    /// The server stamps are wall-clock strings with no zone in them and `hxServerDateTime` reads them
    /// without one, so the comparison has to be built from the device's own calendar to mean the same
    /// instant on any machine.
    private func wallClock(
        _ year: Int, _ month: Int, _ day: Int, _ hour: Int = 0, _ minute: Int = 0, _ second: Int = 0
    ) -> Date {
        var parts = DateComponents()
        parts.year = year
        parts.month = month
        parts.day = day
        parts.hour = hour
        parts.minute = minute
        parts.second = second
        guard let date = Calendar.current.date(from: parts) else {
            fatalError("not a wall clock instant: \(parts)")
        }
        return date
    }
}

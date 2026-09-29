import XCTest
@testable import HarnaxCore

/// The two write-side rules every management screen reads, and the one read-side exemption the skill
/// sources need. Both live on the contract layer rather than in a view so a screen cannot invent its own
/// version — the console keeps them in one helper too (`permissionUtil.ts:29-75,111-123`).
final class PermissionRulesTests: XCTestCase {

    // MARK: - canManage

    func testAnAdministratorReachesEveryRow() {
        let admin = AccountSnapshot(username: "admin", isAdministrator: true)
        XCTAssertTrue(admin.canManage(creator: "someone-else"))
        XCTAssertTrue(admin.canManage(creator: "admin"))
        XCTAssertTrue(admin.canManage(creator: nil), "an unattributed row is still a row an admin owns")
    }

    func testEveryoneElseReachesOnlyTheRowsTheyCreated() {
        let alice = AccountSnapshot(username: "alice")
        XCTAssertTrue(alice.canManage(creator: "alice"))
        XCTAssertTrue(alice.canManage(creator: " alice "), "the column is compared after the same trim the display uses")
        XCTAssertFalse(alice.canManage(creator: "bob"))
    }

    /// The console's own comparison is `record.creator === username`; with no creator on the row and a
    /// profile that has not loaded, both sides are empty and the row would come back manageable.
    func testARowWithNoCreatorOrAnAccountWithNoNameManagesNothing() {
        let alice = AccountSnapshot(username: "alice")
        XCTAssertFalse(alice.canManage(creator: nil))
        XCTAssertFalse(alice.canManage(creator: ""))
        XCTAssertFalse(alice.canManage(creator: "   "))

        let nobody = AccountSnapshot(username: "")
        XCTAssertFalse(nobody.canManage(creator: nil))
        XCTAssertFalse(nobody.canManage(creator: "alice"), "a blank profile must not match a blank creator")
    }

    // MARK: - canChangeVisibility

    /// An admin and a create are never narrowed; otherwise only a private row of this account's own may
    /// move, which is the one direction `isPublicSwitchDisabled` leaves open.
    func testOnlyAnOwnPrivateRowMayChangeVisibility() {
        let admin = AccountSnapshot(username: "admin", isAdministrator: true)
        XCTAssertTrue(admin.canChangeVisibility(creator: "bob", currentlyPublic: true, isCreate: false))

        let alice = AccountSnapshot(username: "alice")
        XCTAssertTrue(alice.canChangeVisibility(creator: "alice", currentlyPublic: false, isCreate: false))
        XCTAssertFalse(alice.canChangeVisibility(creator: "alice", currentlyPublic: true, isCreate: false),
                       "a public row does not go back to private at non-administrator hands")
        XCTAssertFalse(alice.canChangeVisibility(creator: "bob", currentlyPublic: false, isCreate: false),
                       "somebody else's row is not this account's to publish")

        let create = AccountSnapshot(username: "alice")
        XCTAssertTrue(create.canChangeVisibility(creator: nil, currentlyPublic: true, isCreate: true),
                       "a form that has no row yet decides its own visibility")
    }

    // MARK: - platform-owned skill sources

    /// Two keys, because the console tests the name (`RepositoryList.tsx:384,438`) while the seed writes the
    /// type (`V1__init_schema.sql:744-749`); a row that matches on either is read-only.
    func testAPlatformOwnedSourceMatchesEitherTheBuiltinTypeOrTheSeededName() throws {
        XCTAssertTrue(try source(type: "BUILTIN", name: "some-other-name").isPlatformOwned)
        XCTAssertTrue(try source(type: "GIT", name: SkillSourceSummary.builtinRepositoryName).isPlatformOwned)
        XCTAssertFalse(try source(type: "GIT", name: "qoder-skills").isPlatformOwned)
    }

    // MARK: - helpers

    private func source(type: String, name: String) throws -> SkillSourceSummary {
        try decode(
            SkillSourceSummary.self,
            """
            {"id": 12, "name": "\(name)", "sourceType": "\(type)",
             "version": "1.0.0", "url": "https://example.com/skills.git", "branch": "main",
             "description": "", "status": 1, "isPublic": 1, "creator": "heqingsong",
             "createTime": "2026-05-01T11:10:40", "updateTime": "2026-09-27T08:15:03"}
            """
        )
    }

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }
}

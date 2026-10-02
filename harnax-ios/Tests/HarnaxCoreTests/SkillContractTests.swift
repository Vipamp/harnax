import XCTest

@testable import HarnaxCore

/// The skill domain's contract, read against the two admin surfaces behind it:
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt` and
/// `SkillController.kt`.
///
/// The fixtures are hand-shaped from the response DTOs rather than curl-captured, the way
/// `ToolDecodeTests` does it, and every field traces to a declared property:
/// - `skill-sources-page.json` — `SkillSourceResponse.kt:9-62`, filled by `fromEntity` at
///   `SkillSourceResponse.kt:65-87` and counted by `SkillSourceServiceImpl.kt:71-72`;
/// - `skills-page.json` — `SkillResponse.kt:12-44` through `SkillServiceImpl.kt:375-390`, on rows read by
///   the `SELECT *` at `harnax-entity/src/main/resources/mapper/SkillMapper.xml:84-106`;
/// - `skill-detail.json` — the same DTO through `SkillServiceImpl.kt:365-373` (`SkillController.kt:64-72`).
///
/// What is worth pinning is the same three things the other contract tests pin: which keys
/// `default-property-inclusion: non_null` (`harnax-admin/src/main/resources/application.yml:25`) really
/// drops, which 0/1 columns the screens turn into flags, and where a value the app shows is derived rather
/// than stored.
final class SkillContractTests: XCTestCase {
    // MARK: - source rows

    private func sourcePage() throws -> Page<SkillSourceSummary> {
        try Fixture.decode(Envelope<Page<SkillSourceSummary>>.self, "skill-sources-page").data!
    }

    private func sources() throws -> [SkillSourceSummary] { try sourcePage().records }

    private func skills() throws -> [SkillItem] {
        try Fixture.decode(Envelope<Page<SkillItem>>.self, "skills-page").data!.records
    }

    /// Four rows, four different shapes, and a `total` that does not equal the number of records because
    /// this is the first of two pages. The server ships its own `pages` / `hasPrevious` / `hasNext` getters
    /// beside the four declared keys (`Page.kt:15-31`), and `Page` ignores all three.
    func testSourcePageDecodesEveryRow() throws {
        let page = try sourcePage()
        XCTAssertEqual(page.pageNum, 1)
        XCTAssertEqual(page.pageSize, 4)
        XCTAssertEqual(page.total, 5, "the counter is the whole result, not this page")
        XCTAssertEqual(page.records.count, 4)
    }

    /// The one row where every declared key arrived, so it pins the plain columns. `sourceConfig` wins over
    /// the legacy `url` / `branch` mirrors, which exist only so older readers keep working
    /// (`SkillSourceServiceImpl.kt:144-148`, `SkillSourceConfigs.kt:40-47`).
    func testGitRowReadsEveryColumnTheListScreenShows() throws {
        let git = try XCTUnwrap(sources().first)
        XCTAssertEqual(git.id, 12)
        XCTAssertEqual(git.title, "qoder-skills")
        XCTAssertEqual(git.type, .git)
        XCTAssertTrue(git.isEnabled, "status 1")
        XCTAssertTrue(git.isShared, "isPublic 1")
        XCTAssertEqual(git.creator, "heqingsong")
        XCTAssertEqual(git.endpoint, "https://github.com/example/qoder-skills.git")
        XCTAssertEqual(git.revision, "main")
        XCTAssertEqual(git.enabledSkills, 3)
        XCTAssertNil(git.lastSyncError, "a PARTIAL sync had nothing to fold into error")
    }

    /// `SkillSyncRecorder.detailJson` writes exactly these seven keys and leaves `error` off when there is
    /// nothing to say (`SkillSyncRecorder.kt:52-64`) — with two renames against the live install response:
    /// the counter is `saved`, and `sourceError` / `emptyReason` share one `error`.
    func testStoredSyncReportDecodesInTheRecordersOwnVocabulary() throws {
        let git = try XCTUnwrap(sources().first)
        let detail = try XCTUnwrap(git.lastSyncDetail)
        XCTAssertEqual(git.lastSyncStatus, "PARTIAL")
        XCTAssertEqual(detail.saved, 3, "the recorder writes savedCount under the name saved")
        XCTAssertEqual(detail.installed, ["pdf-report"])
        XCTAssertEqual(detail.updated, ["code-review", "i18n-audit"])
        XCTAssertEqual(detail.failed?.first?.name, "translate")
        XCTAssertEqual(detail.failed?.first?.reason, "SKILL.md front matter has no name")
        XCTAssertEqual(
            detail.flagged?.first?.reasons,
            ["recursively deletes a root-level path", "pipes a remote payload straight into a shell"],
            "one scan flags the same file on several counts, so reasons is a list"
        )
        XCTAssertEqual(detail.stale, ["legacy-docs"])
        XCTAssertNil(detail.error)
    }

    /// A ZIP row: `zipPath` is a server-side scratch path and never leaves `forApi`
    /// (`SkillSourceConfigs.kt:49-61,65`), so the only config key is the name the archive came in under
    /// (`SkillSourceServiceImpl.kt:367`) — and that is the address line this row has to show.
    func testZipRowReadsItsUploadNameAsTheEndpoint() throws {
        let zip = try sources()[2]
        XCTAssertEqual(zip.name, "contract-review-pack")
        XCTAssertEqual(zip.type, .zip)
        XCTAssertFalse(zip.isEnabled, "status 0")
        XCTAssertFalse(zip.isShared)
        XCTAssertNil(zip.sourceConfig?.url, "a ZIP config has no url to show")
        XCTAssertEqual(zip.endpoint, "contract-review.zip")
        XCTAssertNil(zip.revision, "only a GIT row has a branch to name")
        XCTAssertEqual(zip.lastSyncStatus, "EMPTY")
        XCTAssertEqual(zip.lastSyncError, "No SKILL.md found under the archive root")
        XCTAssertEqual(zip.enabledSkills, 2, "a disabled source still owes the same account")
    }

    /// The seeded builtin repository (`V1__init_schema.sql:747-749`) stores a blank `source_config`, and
    /// `forApi` answers null for a blank column (`SkillSourceConfigs.kt:50-52`) — so the key is gone
    /// entirely rather than arriving empty, and the row still has to read.
    func testBuiltinRowArrivesWithNoSourceConfigKeyAtAll() throws {
        let builtin = try XCTUnwrap(sources().last)
        XCTAssertEqual(builtin.name, "builtin-cli-skills")
        XCTAssertEqual(builtin.type, .builtin)
        XCTAssertNil(builtin.sourceConfig)
        XCTAssertNil(builtin.endpoint, "the legacy url column is blank, and blank is not an address")
        XCTAssertNil(builtin.revision, "the seeded main is a leftover, not a branch this row can read")
        XCTAssertNil(builtin.lastSyncTime)
        XCTAssertNil(builtin.lastSyncDetail)
        XCTAssertFalse(builtin.type.isRefreshable, "requireRefreshable refuses this row outright")
    }

    /// Row two was never synced, so all three `lastSync*` keys drop instead of arriving as null
    /// (`SkillSourceResponse.kt:50-56`), while `url` / `branch` / `description` / `version` are non-null
    /// strings (`:23-32`) and therefore arrive as `""`.
    func testNeverSyncedRowLosesTheWholeSyncBlockRatherThanTheRow() throws {
        let npm = try sources()[1]
        XCTAssertEqual(npm.type, .npm)
        XCTAssertNil(npm.lastSyncStatus)
        XCTAssertNil(npm.lastSyncTime)
        XCTAssertNil(npm.lastSyncDetail)
        XCTAssertNil(npm.lastSyncError)
        XCTAssertEqual(npm.url, "", "the DTO defaults to the empty string, which is not a null")
        XCTAssertEqual(npm.endpoint, "@example/npm-skills", "an NPM row addresses its package, not a url")
        XCTAssertEqual(npm.enabledSkills, 0, "an empty source is a zero here, not a missing key")
        XCTAssertTrue(npm.isEnabled)
    }

    /// The same column in two forms, because the two DTOs are two different types. A source row is
    /// `entity.createTime.toString()` (`SkillSourceResponse.kt:80-81`) — ISO with a `T`, and
    /// `LocalDateTime.toString()` cuts the seconds when they are zero. A skill row really is a
    /// `LocalDateTime` (`SkillResponse.kt:42-43`) and gets Jackson's `yyyy-MM-dd HH:mm:ss`
    /// (`application.yml:23`).
    func testSourceAndSkillRowsStampTheirClocksDifferently() throws {
        let rows = try sources()
        XCTAssertEqual(rows[0].createTime, "2026-05-01T11:10:40")
        XCTAssertEqual(rows[1].createTime, "2026-09-20T16:42", "no seconds in the ISO form when they are zero")
        XCTAssertEqual(try skills().first?.createTime, "2026-09-21 10:04:12")
    }

    // MARK: - the delete gate

    /// The rule `SkillSourceListViewModel.requestDelete` applies to the count: the server cascades through
    /// a source's skills and refuses while any of them is still on (`SkillInstaller.kt:303-309`).
    private func deletionBlocked(_ source: SkillSourceSummary) -> Bool {
        (source.enabledSkills ?? 0) > 0
    }

    func testEnabledSkillCountIsWhatBlocksTheDelete() throws {
        let rows = try sources()
        XCTAssertEqual(rows[0].enabledSkills, 3)
        XCTAssertTrue(deletionBlocked(rows[0]))
        XCTAssertTrue(deletionBlocked(rows[2]), "the count is the gate whether or not the source is enabled")
        XCTAssertFalse(deletionBlocked(rows[1]))
        XCTAssertFalse(deletionBlocked(rows[3]))
    }

    /// `GET /skill-sources/active` maps through `fromEntity` without the lookup
    /// (`SkillSourceController.kt:45`, `SkillSourceResponse.kt:58-62`), so those rows carry no count key at
    /// all. An absent count is not a zero: it means nobody asked, and it must not block a delete.
    func testPickerRowWithoutTheCountKeyStillDecodesAndDoesNotBlock() throws {
        let picker = try decode(
            SkillSourceSummary.self,
            """
            {"id": 12, "name": "qoder-skills", "sourceType": "GIT",
             "sourceConfig": {"url": "https://github.com/example/qoder-skills.git", "branch": "main"},
             "version": "1.0.0", "url": "https://github.com/example/qoder-skills.git", "branch": "main",
             "description": "", "status": 1, "isPublic": 1, "creator": "heqingsong",
             "createTime": "2026-05-01T11:10:40", "updateTime": "2026-09-27T08:15:03"}
            """
        )
        XCTAssertNil(picker.enabledSkills)
        XCTAssertFalse(deletionBlocked(picker), "unknown is not the same news as zero")
        XCTAssertEqual(picker.id, 12, "the count can be missing; the id cannot")
    }

    // MARK: - source types

    /// The four types the server knows, as the raw column strings (`SkillSourceResponse.kt:17`,
    /// `BuiltinRepository.kt:25`), and the upper-case tolerance the picker filter needs
    /// (`SkillSourceController.kt:31` takes the column string as it arrives).
    func testSourceTypeRawStringsRoundTrip() {
        let known: [(SkillSourceType, String)] = [(.git, "GIT"), (.npm, "NPM"), (.zip, "ZIP"), (.builtin, "BUILTIN")]
        for (type, raw) in known {
            XCTAssertEqual(type.rawValue, raw)
            XCTAssertEqual(SkillSourceType(raw: raw), type)
        }
        XCTAssertEqual(SkillSourceType(raw: "git"), .git, "the column is compared case-insensitively")
        XCTAssertEqual(SkillSourceType(raw: nil), .unknown(""))
        XCTAssertTrue(SkillSourceType.git.isRefreshable)
        XCTAssertTrue(SkillSourceType.npm.isRefreshable)
        XCTAssertFalse(SkillSourceType.zip.isRefreshable)
        XCTAssertFalse(SkillSourceType.builtin.isRefreshable)
        XCTAssertFalse(SkillSourceType.formOptions.contains(.builtin), "BUILTIN is platform-owned, never a choice")
        XCTAssertEqual(Set(SkillSourceType.formOptions.map(\.rawValue)), ["GIT", "NPM", "ZIP"])
    }

    /// A type this build does not know has to stay a row: `sourceType` is a plain string column on the DTO
    /// (`SkillSourceResponse.kt:17`), so nothing can fail on it. It falls back to the config url and keeps
    /// its branch silent, because only GIT is asked for one.
    func testUnknownSourceTypeStillRendersARow() throws {
        let future = try decode(
            SkillSourceSummary.self,
            """
            {"id": 21, "name": "artifacts", "sourceType": "HTTP",
             "sourceConfig": {"url": "https://example.org/skills", "authToken": "redacted"},
             "version": "", "url": "https://example.org/skills", "branch": "",
             "description": "", "status": 1, "isPublic": 0, "creator": "alice",
             "createTime": "2026-09-22T10:00:00", "updateTime": "2026-09-22T10:00:00", "enabledSkillCount": 0}
            """
        )
        XCTAssertEqual(future.type, .unknown("HTTP"))
        XCTAssertEqual(future.type.rawValue, "HTTP", "a request body has to be able to name it back")
        XCTAssertEqual(future.endpoint, "https://example.org/skills")
        XCTAssertNil(future.revision)
        XCTAssertFalse(future.type.isRefreshable)
    }

    /// `sourceConfig` is a `Map<String, Any>` on the wire (`SkillSourceResponse.kt:20`) and `forApi` ships
    /// the stored object unchanged apart from `zipPath`, while `SkillSourceCreateRequest.kt:19` accepts any
    /// JSON the caller sends. A number under a key this app knows is therefore reachable, and the server's
    /// own loaders read those keys with `as? String` (`GitSkillLoader.kt:64-65`) — the row has to survive it.
    func testNonTextConfigValueReadsAsAbsentRatherThanBlankingThePage() throws {
        let config = try decode(
            SkillSourceConfig.self,
            """
            {"url": 42, "branch": true, "packageName": "@example/npm-skills", "depth": 1}
            """
        )
        XCTAssertNil(config.url, "a number is not an address somebody configured")
        XCTAssertNil(config.branch)
        XCTAssertEqual(config.packageName, "@example/npm-skills")
        XCTAssertNil(config.originalFilename, "an absent key is absent")

        let row = try decode(
            SkillSourceSummary.self,
            """
            {"id": 22, "name": "broken-config", "sourceType": "GIT",
             "sourceConfig": {"url": 42}, "version": "", "url": "https://example.org/kept", "branch": "",
             "description": "", "status": 1, "isPublic": 0, "creator": "alice",
             "createTime": "2026-09-23T09:00:00", "updateTime": "2026-09-23T09:00:00", "enabledSkillCount": 0}
            """
        )
        XCTAssertEqual(
            row.endpoint, "https://example.org/kept",
            "the config value is unreadable, so the legacy mirror is what the row can show"
        )
    }

    // MARK: - skill rows

    func testSkillPageDecodesEveryRow() throws {
        let rows = try skills()
        XCTAssertEqual(rows.count, 3)

        let bound = rows[0]
        XCTAssertEqual(bound.id, 41)
        XCTAssertEqual(bound.title, "pdf-report")
        XCTAssertEqual(bound.detail, "从模板生成 PDF 报表")
        XCTAssertEqual(bound.source, "qoder-skills")
        XCTAssertEqual(bound.repositoryId, 12)
        XCTAssertEqual(bound.repositoryUrl, "https://github.com/example/qoder-skills.git")
        XCTAssertEqual(bound.repositoryBranch, "main")
        XCTAssertTrue(bound.isEnabled)
        XCTAssertTrue(bound.isShared)
        XCTAssertEqual(bound.creator, "heqingsong")
    }

    /// Both counters default to `0` in the DTO (`SkillResponse.kt:34,36`), so they always arrive — and the
    /// team-only binding is the case that used to read "0 agents" here and still be refused server-side
    /// (`SkillServiceImpl.kt:261-265`).
    func testBindingCountersGateTheRowFromEitherSide() throws {
        let rows = try skills()
        XCTAssertEqual(rows[0].boundAgentCount, 2)
        XCTAssertEqual(rows[0].boundTeamCount, 1)
        XCTAssertEqual(rows[0].bindings, 3)
        XCTAssertTrue(rows[0].isBound)

        XCTAssertEqual(rows[2].boundAgentCount, 0)
        XCTAssertEqual(rows[2].boundTeamCount, 2)
        XCTAssertTrue(rows[2].isBound, "a lead binding gates the disable exactly like an agent binding")

        XCTAssertEqual(rows[1].bindings, 1)
        XCTAssertTrue(rows[1].isBound, "one agent binding is enough on its own")
    }

    /// An unbound row is not necessarily a writable one. `requireUnbound` adds a third test the response
    /// carries no column for: a skill a CLI package owns through `cli.skill_id`
    /// (`SkillServiceImpl.kt:266-274`). Nothing on the wire tells iOS about it, so that refusal can only
    /// surface as the server's sentence on the write — which is why the counts are a pre-flight check and
    /// not the guard.
    func testCountsAreTheOnlyBindingSignalTheRowCanGive() throws {
        let packaged = try decode(
            SkillItem.self,
            #"{"id": 51, "name": "packaged", "boundAgentCount": 0, "boundTeamCount": 0}"#
        )
        XCTAssertFalse(packaged.isBound)
        XCTAssertEqual(packaged.bindings, 0)
    }

    /// `""` and `"   "` are what the non-null columns answer for a skill nobody described
    /// (`Skill.kt:20-32` defaults every one of them to the empty string), and neither is a value.
    func testBlankSkillColumnsReadAsAbsent() throws {
        let blank = try XCTUnwrap(skills().last)
        XCTAssertEqual(blank.id, 43)
        XCTAssertNil(blank.title, "a whitespace name is not a name")
        XCTAssertNil(blank.detail)
        XCTAssertNil(blank.skillmdBody)
        XCTAssertNotNil(blank.source, "this one still knows where it came from")
        XCTAssertTrue(blank.isEnabled, "only a status of 0 stops a row")
        XCTAssertFalse(blank.isShared)
    }

    /// `resources` is a text column holding a serialised `path -> content` map
    /// (`SkillInstaller.kt:186-187`), so the caller parses the string it was handed. The keys are relative
    /// paths with `/` separators (`SkillFileParser.kt:91`) — that separator is what the detail screen
    /// splits on to build the folder rows.
    func testResourceBlobParsesIntoTheTreeKeysTheDetailScreenSplitsOn() throws {
        let row = try XCTUnwrap(skills().first)
        let files = row.resourceFiles
        XCTAssertEqual(
            files.keys.sorted(),
            ["references/style-guide.md", "scripts/lib/paper.css", "scripts/render.py"],
            "a nested path stays one key; nothing on the wire says which parts are folders"
        )
        XCTAssertEqual(files["scripts/render.py"], "import sys\n\nprint(sys.argv[1])\n")
        XCTAssertEqual(files["scripts/lib/paper.css"], "@page { size: A4; margin: 18mm; }\n")
    }

    /// An empty blob means "no bundled files", and a blob cut short by a column too small is the same news
    /// on the wire (`SkillSyncRecorder.kt:79-83` names truncation as a real failure mode of these stored
    /// JSON columns). Neither may fail the row.
    func testUnparsableAndEmptyBlobsBothReadAsNoFiles() throws {
        let rows = try skills()
        XCTAssertEqual(rows[1].resources, "{}")
        XCTAssertEqual(rows[1].resourceFiles, [:])

        XCTAssertEqual(rows[2].resources, "{\"scripts/build.sh\":\"echo ")
        XCTAssertEqual(rows[2].resourceFiles, [:], "a truncated blob is not a file list")
    }

    /// The orphan: the repository trio comes off a nullable lookup (`SkillResponse.kt:64-66`), so when the
    /// source row is gone all three keys drop and only `repositoryId` survives — which is why the table
    /// shows a source line only when there is one to show.
    func testOrphanedSkillRowIsMissingTheRepositoryTrio() throws {
        let orphan = try XCTUnwrap(skills().dropFirst().first)
        XCTAssertEqual(orphan.repositoryId, 99)
        XCTAssertNil(orphan.repositoryName)
        XCTAssertNil(orphan.repositoryUrl)
        XCTAssertNil(orphan.repositoryBranch)
        XCTAssertNil(orphan.source)
        XCTAssertFalse(orphan.isEnabled, "status 0")
        XCTAssertNotNil(orphan.skillmdBody)
    }

    /// The claim `SkillItem` used to carry in its doc comment — that `skillmd` is "null on the paged
    /// endpoint, which does not select it" — is wrong. `selectSkillList` is a `SELECT *` whose result map
    /// names both content columns (`SkillMapper.xml:12-13,84-85`), and the page conversion goes through the
    /// same `SkillResponse.fromEntity` as the detail conversion (`SkillServiceImpl.kt:365-390`). The detail
    /// read buys a fresh row, not extra columns.
    func testDetailRowCarriesExactlyWhatItsPageRowCarries() throws {
        let pageRow = try XCTUnwrap(skills().first)
        let detail = try Fixture.decode(Envelope<SkillItem>.self, "skill-detail").data!

        XCTAssertEqual(detail, pageRow, "same row, same DTO, same conversion")
        XCTAssertEqual(detail.skillmdBody, pageRow.skillmdBody)
        XCTAssertEqual(detail.resourceFiles, pageRow.resourceFiles)

        // And the key sets really are equal on the wire, before any projection through a Swift type.
        let pageKeys = try wireKeys(of: "skills-page", at: ["data", "records", 0])
        let detailKeys = try wireKeys(of: "skill-detail", at: ["data"])
        XCTAssertEqual(pageKeys, detailKeys)
        XCTAssertEqual(
            detailKeys,
            [
                "id", "name", "repositoryId", "repositoryName", "repositoryUrl", "repositoryBranch",
                "description", "skillmd", "resources", "status", "boundAgentCount", "boundTeamCount",
                "isPublic", "creator", "createTime", "updateTime",
            ],
            "every declared property of SkillResponse.kt:14-44 arrives once the row has a source"
        )
    }

    /// The counters are the two keys that cannot drop, because the DTO gives them a non-null default
    /// (`SkillResponse.kt:34,36`) while everything else is `T? = null` and so omittable under
    /// `default-property-inclusion: non_null`.
    func testOnlyTheCountersAreGuaranteedOnASkillRow() throws {
        let empty = try decode(SkillItem.self, #"{"boundAgentCount": 0, "boundTeamCount": 0}"#)
        XCTAssertNil(empty.id)
        XCTAssertNil(empty.title)
        XCTAssertNil(empty.source)
        XCTAssertTrue(empty.isEnabled, "an absent status is not a 0")
        XCTAssertFalse(empty.isBound)
    }

    // MARK: - install and preview payloads

    /// `SkillInstallResponse` serialises its derived getters beside the four buckets
    /// (`SkillInstallResponse.kt:34-51`). iOS deliberately keeps `summary` off the model because it is
    /// hard-coded English, and rebuilds the two counters from the lists — so the wire copy and the
    /// recomputation have to agree, key for key.
    func testInstallOutcomeRecomputesWhatTheServerAlreadyDerived() throws {
        let wire = #"""
        {"installed": ["pdf-report"], "updated": ["code-review"],
         "failed": [{"name": "translate", "reason": "SKILL.md front matter has no name"}],
         "flagged": [{"name": "db-migrate", "reasons": ["recursively deletes a root-level path"]}],
         "stale": ["legacy-docs"],
         "savedCount": 2, "failedCount": 1, "complete": false,
         "summary": "2 saved (1 new) (1 updated), 1 disabled pending review, 1 failed"}
        """#
        let outcome = try decode(SkillInstallOutcome.self, wire)
        XCTAssertEqual(outcome.savedCount, 2)
        XCTAssertEqual(outcome.failedCount, 1)
        XCTAssertFalse(outcome.isComplete)
        XCTAssertEqual(outcome.stale, ["legacy-docs"], "reported, never deleted along with the source")
        XCTAssertNil(outcome.sourceError)
        XCTAssertNil(outcome.emptyReason)

        let derived = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(wire.utf8)) as? [String: Any],
            "the install response is an object of four lists plus the getters"
        )
        XCTAssertEqual(derived["savedCount"] as? Int, outcome.savedCount)
        XCTAssertEqual(derived["failedCount"] as? Int, outcome.failedCount)
        XCTAssertEqual(derived["complete"] as? Bool, outcome.isComplete)
        XCTAssertNotNil(derived["summary"])
    }

    /// The three one-field answers the recorder branches on (`SkillSyncRecorder.kt:32-41`), where
    /// `complete` is `failed.isEmpty && sourceError == null` (`SkillInstallResponse.kt:41`): a source that
    /// broke, a source that opened but held nothing, and a clean sync. The empty one is complete — EMPTY is
    /// its own status and a different problem with a different fix.
    func testInstallOutcomeSeparatesBrokenSourceFromEmptySource() throws {
        let broken = try decode(
            SkillInstallOutcome.self,
            #"{"installed": [], "updated": [], "failed": [], "flagged": [], "stale": [], "sourceError": "git clone timed out"}"#
        )
        XCTAssertFalse(broken.isComplete)
        XCTAssertEqual(broken.savedCount, 0)

        let emptySource = try decode(
            SkillInstallOutcome.self,
            #"{"installed": [], "updated": [], "failed": [], "flagged": [], "stale": [], "emptyReason": "No SKILL.md found under the archive root"}"#
        )
        XCTAssertTrue(emptySource.isComplete)
        XCTAssertEqual(emptySource.emptyReason, "No SKILL.md found under the archive root")

        let clean = try decode(
            SkillInstallOutcome.self,
            #"{"installed": ["a"], "updated": [], "failed": [], "flagged": [], "stale": []}"#
        )
        XCTAssertTrue(clean.isComplete)

        // The buckets arrive as empty lists rather than as absent keys — `non_null` only drops nulls, and
        // the DTO defaults each to `emptyList()`. An absent bucket is a contract break, so it throws
        // instead of quietly reading as a sync that stored nothing (`SkillItem.swift:89-91`).
        let untouched = try decode(
            SkillInstallOutcome.self,
            #"{"installed": [], "updated": [], "failed": [], "flagged": [], "stale": []}"#
        )
        XCTAssertEqual(untouched, SkillInstallOutcome.none)
        XCTAssertThrowsError(try decode(SkillInstallOutcome.self, "{}"))
    }

    /// An absent `names` stores the whole source, an empty array stores nothing
    /// (`SkillSourceInstallRequest.kt:8-15`), so the two must not encode the same way.
    func testInstallPayloadKeepsAbsentAndEmptyApart() throws {
        XCTAssertTrue(try XCTUnwrap(JSONObject(SkillInstallPayload(names: nil))).isEmpty)
        XCTAssertEqual(try JSONObject(SkillInstallPayload(names: []))?.keys.sorted(), ["names"])
        XCTAssertEqual(
            try JSONObject(SkillInstallPayload(names: ["pdf-report", "code-review"]))?["names"] as? [String],
            ["pdf-report", "code-review"]
        )
    }

    /// `name`, `sourceType` and `sourceConfig` are the only columns create cannot default away
    /// (`SkillSourceCreateRequest.kt:13-19`); `url` / `branch` are legacy mirrors the caller does not have
    /// to send, because create re-derives them from the normalised config (`SkillSourceServiceImpl.kt:144-148`)
    /// and `buildConfigMap` only falls back to them when `sourceConfig` arrives empty (`:480-483`). The
    /// update DTO has no `sourceType` in it at all: a source's type cannot be switched after the fact
    /// (`SkillSourceUpdateRequest.kt:7-37`).
    func testSourceBodiesSendOnlyWhatTheEndpointAccepts() throws {
        let withMirrors = try XCTUnwrap(
            JSONObject(
                SkillSourceCreatePayload(
                    name: "qoder-skills",
                    sourceType: "GIT",
                    sourceConfig: ["url": "https://example.org/skills.git", "branch": "main"],
                    url: "https://example.org/skills.git",
                    branch: "main"
                )
            )
        )
        XCTAssertEqual(
            Set(withMirrors.keys),
            ["name", "sourceType", "sourceConfig", "url", "branch"],
            "the mirrors ride along only when the caller sets them"
        )
        XCTAssertEqual((withMirrors["sourceConfig"] as? [String: String])?["url"], "https://example.org/skills.git")

        let configOnly = try XCTUnwrap(
            JSONObject(
                SkillSourceCreatePayload(
                    name: "npm-skills",
                    sourceType: "NPM",
                    sourceConfig: ["packageName": "@harnax/skills", "registry": "https://npm.example.org"]
                )
            )
        )
        XCTAssertEqual(
            Set(configOnly.keys),
            ["name", "sourceType", "sourceConfig"],
            "an NPM create has nothing to mirror, and nil optional fields drop out"
        )

        let edited = try XCTUnwrap(JSONObject(SkillSourceUpdatePayload(status: 0)))
        XCTAssertEqual(Set(edited.keys), ["status"], "every other column stays unchanged")
        XCTAssertTrue(try XCTUnwrap(JSONObject(SkillSourceUpdatePayload())).isEmpty)
    }

    /// `SyncSkillResponse.resources` really is an object (`SyncSkillResponse.kt:17`), unlike the stored
    /// skill row's blob: the preview is read straight off the source, before anything is serialised into a
    /// column. `resources` and `exists` default non-null (`:17-19`) so they always arrive; the three text
    /// fields are nullable (`:11-15`) and can drop.
    func testPreviewItemTakesResourcesAsAnObjectNotABlob() throws {
        let known = try decode(
            SkillPreviewItem.self,
            """
            {"name": "pdf-report", "description": "从模板生成 PDF 报表", "skillmd": "# PDF 报表",
             "resources": {"SKILL.md": "# PDF 报表", "scripts/render.py": "import sys"}, "exists": true}
            """
        )
        XCTAssertEqual(known.id, "pdf-report", "the name is the only key a candidate has")
        XCTAssertEqual(known.resourceCount, 2)
        XCTAssertEqual(known.resources["scripts/render.py"], "import sys")
        XCTAssertTrue(known.exists, "an overwrite candidate, not a new one")

        let fresh = try decode(SkillPreviewItem.self, #"{"name": "newcomer", "resources": {}, "exists": false}"#)
        XCTAssertNil(fresh.skillmd)
        XCTAssertNil(fresh.detail)
        XCTAssertEqual(fresh.resourceCount, 0)
        XCTAssertFalse(fresh.exists)
    }

    // MARK: - helpers

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    private func JSONObject<T: Encodable>(_ value: T) throws -> [String: Any]? {
        try JSONSerialization.jsonObject(with: try JSONEncoder().encode(value)) as? [String: Any]
    }

    /// The keys the server really sent, read before any projection through a Swift type. A set: `Dictionary`
    /// hands its keys out in an order that is not stable across processes, and this gate asks only which keys
    /// arrived.
    private func wireKeys(of fixture: String, at path: [Any]) throws -> Set<String> {
        var cursor: Any = try JSONSerialization.jsonObject(with: try Fixture.data(fixture))
        for step in path {
            switch step {
            case let index as Int: cursor = try XCTUnwrap((cursor as? [Any])?[index])
            case let key as String: cursor = try XCTUnwrap((cursor as? [String: Any])?[key])
            default: throw FixtureError.missing("\(step)")
            }
        }
        return Set(try XCTUnwrap((cursor as? [String: Any])?.keys))
    }
}

private extension SkillItem {
    /// The body the detail screen renders: the stored column with the blank rule applied, so a test can
    /// tell "no key" from "a key that says nothing".
    var skillmdBody: String? { hxPresented(skillmd) }
}

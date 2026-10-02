import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// SKILL-1 and SKILL-2 — the create and edit halves of the skill source list, and the body each one sends.
///
/// Everything here is asserted off the fake client: which fields a type makes *required*, what the body that
/// actually went out contains, and what a refused write left behind. The console's own rules are the
/// specification —
/// `harnax-webui/src/pages/skill/components/RepositoryForm.tsx` for the form,
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceCreateRequest.kt` and
/// `SkillSourceUpdateRequest.kt` for the body.
@MainActor
final class SkillRepositoryFormTests: XCTestCase {
    private typealias Form = SkillRepositoryFormModel
    private typealias Field = Form.Field

    /// The whole row shape `SkillSourceSummary` decodes, so a seeded row never differs from a read one.
    private static func sourceRow(
        id: Int = 7,
        name: String = "技能仓库",
        type: String = "GIT",
        url: String? = nil,
        branch: String? = nil,
        version: String = "1.0.0",
        description: String = "示例来源",
        status: Int = 1,
        isPublic: Int = 0,
        creator: String = "heqingsong",
        config: [String: String]? = nil
    ) -> [String: Any] {
        var row: [String: Any] = [
            "id": id,
            "name": name,
            "sourceType": type,
            "version": version,
            "url": url ?? "",
            "branch": branch ?? "",
            "description": description,
            "status": status,
            "isPublic": isPublic,
            "creator": creator,
            "createTime": "2026-09-01 10:00:00",
            "updateTime": "2026-09-01 10:00:00",
        ]
        if let config { row["sourceConfig"] = config }
        return row
    }

    /// One row, straight off the wire shape the list would have handed the form. Same knobs as `sourceRow`,
    /// so a seeded row and a decoded one can never drift apart.
    private static func row(
        id: Int = 7,
        name: String = "技能仓库",
        type: String = "GIT",
        url: String? = nil,
        branch: String? = nil,
        version: String = "1.0.0",
        description: String = "示例来源",
        status: Int = 1,
        isPublic: Int = 0,
        creator: String = "heqingsong",
        config: [String: String]? = nil
    ) throws -> SkillSourceSummary {
        try SkillSourceSummary.stub(
            sourceRow(
                id: id,
                name: name,
                type: type,
                url: url,
                branch: branch,
                version: version,
                description: description,
                status: status,
                isPublic: isPublic,
                creator: creator,
                config: config
            )
        )
    }

    private func makeForm(
        row: SkillSourceSummary? = nil,
        skills: FakeSkills = FakeSkills(),
        account: AccountSnapshot? = AccountSnapshot(username: "heqingsong", isAdministrator: true)
    ) -> Form {
        Form(mode: row.map { Form.Mode.edit($0) } ?? .create, catalog: skills, account: account)
    }

    // MARK: - the field set, per type

    /// A new form opens on the console's defaults: GIT, enabled, private, branch already `main`
    /// (`RepositoryForm.tsx:42-52`).
    func testANewFormOpensOnTheConsolesDefaults() {
        let form = makeForm()
        XCTAssertEqual(form.typeChoice, .git)
        XCTAssertEqual(form.text(for: .name), "")
        XCTAssertEqual(form.text(for: .branch), "main")
        XCTAssertTrue(form.enabled)
        XCTAssertFalse(form.shared)
        XCTAssertEqual(form.credentialFields, [.url, .branch])
    }

    /// Picking NPM moves the requirement, it does not merely repaint the screen: `url` stops being required
    /// and `packageName` starts (`RepositoryForm.tsx:213-256`), and a form that was ready as a GIT source is
    /// not ready as an NPM one.
    func testPickingNpmChangesWhichFieldIsRequired() throws {
        let form = makeForm()
        form.setValue("技能仓库", for: .name)
        form.setValue("https://github.com/example/skills", for: .url)
        XCTAssertTrue(form.isRequired(.url))
        XCTAssertFalse(form.isRequired(.packageName))
        XCTAssertTrue(form.canSubmit)

        form.setType(.npm)
        XCTAssertEqual(form.credentialFields, [.packageName, .registry])
        XCTAssertFalse(form.isRequired(.url), "an NPM source has no address to be missing")
        XCTAssertTrue(form.isRequired(.packageName))
        XCTAssertFalse(form.isRequired(.registry), "the registry is optional (`:247-254`)")
        XCTAssertEqual(form.missingRequired, [.packageName])
        XCTAssertFalse(form.canSubmit)
    }

    /// ZIP is the console's third option (`RepositoryForm.tsx:207-209`) but not this route's: the create
    /// endpoint refuses it and points at the upload (`SkillSourceServiceImpl.kt:119-123`), which is the list's
    /// own entry. A form that could name a type the server rejects would only produce a failure to explain.
    func testZipIsNotAnOptionTheFormCanName() throws {
        let form = makeForm()
        XCTAssertEqual(Form.typeOptions, [.git, .npm])
        form.setType(.zip)
        XCTAssertEqual(form.typeChoice, .git, "a type outside `typeOptions` cannot be chosen")
    }

    // MARK: - the create body

    /// GIT puts `url` and `branch` in `sourceConfig` and nowhere else, and leaves the branch out of nothing:
    /// an empty one is sent as `main` (`RepositoryForm.tsx:87`). The legacy `url` / `branch` body keys stay
    /// absent because the service mirrors them from the config itself (`SkillSourceServiceImpl.kt:144-148`).
    func testTheGitBodyCarriesUrlAndBranchInsideTheConfigOnly() async throws {
        let skills = FakeSkills()
        skills.createReplies = [.success(try SkillSourceInstallResult.stub(name: "技能仓库", sourceConfig: [
            "url": "https://github.com/example/skills", "branch": "main",
        ]))]
        let form = makeForm(skills: skills)
        form.setValue("  技能仓库  ", for: .name)
        form.setValue("  https://github.com/example/skills  ", for: .url)
        form.setValue("   ", for: .branch)

        let outcome = await form.save()
        guard case .created = outcome else { return XCTFail("a create answers with the install report") }
        let sent = try XCTUnwrap(skills.createRequests.first)
        XCTAssertEqual(sent.name, "技能仓库", "padded text is trimmed on the way in (`SkillSourceConfigs.normalized:77-79`)")
        XCTAssertEqual(sent.sourceType, "GIT")
        XCTAssertEqual(sent.sourceConfig, ["url": "https://github.com/example/skills", "branch": "main"])
        XCTAssertNil(sent.url)
        XCTAssertNil(sent.branch)
        XCTAssertEqual(sent.status, 1)
        XCTAssertEqual(sent.isPublic, 0)
        XCTAssertEqual(
            try keys(of: sent),
            ["description", "isPublic", "name", "sourceConfig", "sourceType", "status", "version"],
            "no `url` and no `branch`: the config is the only carrier (`SkillSourceServiceImpl.kt:144-148`)"
        )
    }

    /// NPM sends `packageName` plus a `registry` key that is present even when empty
    /// (`RepositoryForm.tsx:89`) — an absent registry and an empty one are different answers to the server.
    func testTheNpmBodyCarriesPackageNameAndAnEmptyRegistry() async throws {
        let skills = FakeSkills()
        skills.createReplies = [.success(try SkillSourceInstallResult.stub(name: "技能包", type: "NPM"))]
        let form = makeForm(skills: skills)
        form.setValue("技能包", for: .name)
        form.setType(.npm)
        form.setValue("@harnax/skill-pack", for: .packageName)

        _ = await form.save()
        let sent = try XCTUnwrap(skills.createRequests.first)
        XCTAssertEqual(sent.sourceType, "NPM")
        XCTAssertEqual(sent.sourceConfig, ["packageName": "@harnax/skill-pack", "registry": ""])

        let withRegistry = makeForm(skills: skills)
        withRegistry.setType(.npm)
        withRegistry.setValue("技能包", for: .name)
        withRegistry.setValue("@harnax/skill-pack", for: .packageName)
        withRegistry.setValue("https://registry.npmmirror.com", for: .registry)
        skills.createReplies = [.success(try SkillSourceInstallResult.stub(name: "技能包", type: "NPM"))]
        _ = await withRegistry.save()
        XCTAssertEqual(
            skills.createRequests.last?.sourceConfig,
            ["packageName": "@harnax/skill-pack", "registry": "https://registry.npmmirror.com"]
        )
    }

    /// `@field:Size` on the two columns the DTO declares (`SkillSourceCreateRequest.kt:12,24`) — the request's
    /// own contract, so it is checked before the wire. Shape rules the *loaders* enforce are deliberately not
    /// duplicated here; the server's sentence about a Git URL beats any paraphrase.
    func testTextLongerThanTheColumnNeverGoesOut() async throws {
        let skills = FakeSkills()
        let form = makeForm(skills: skills)
        form.setValue(String(repeating: "长", count: Form.nameLimit + 1), for: .name)
        form.setValue("https://github.com/example/skills", for: .url)
        XCTAssertFalse(form.canSubmit)
        let refused = await form.save()
        XCTAssertNil(refused)
        XCTAssertTrue(skills.createRequests.isEmpty, "a validation failure sends nothing at all")
        XCTAssertTrue(form.invalid.contains(.name))
        XCTAssertEqual(form.problem(for: .name), hx("skill.repository.nameTooLong"))

        form.setValue("技能仓库", for: .name)
        form.setValue(String(repeating: "1", count: Form.versionLimit + 1), for: .version)
        let secondRefusal = await form.save()
        XCTAssertNil(secondRefusal)
        XCTAssertTrue(skills.createRequests.isEmpty)
        XCTAssertEqual(form.problem(for: .version), hx("skill.repository.versionTooLong"))
    }

    // MARK: - the edit round trip

    /// Before a single key is typed, the form holds the row's own stored values — config first, legacy columns
    /// as the fallback the row itself uses, and the NPM pair out of the config only
    /// (`RepositoryForm.tsx:29-41`).
    func testAnEditRoundTripsTheRowsStoredValuesBeforeTyping() throws {
        let stored = try Self.row(
            url: "https://old.example/skills.git",
            config: ["url": "https://live.example/skills.git", "branch": "develop"]
        )
        let fromConfig = makeForm(row: stored)
        XCTAssertEqual(fromConfig.text(for: .url), "https://live.example/skills.git")
        XCTAssertEqual(fromConfig.text(for: .branch), "develop")
        XCTAssertEqual(fromConfig.text(for: .name), "技能仓库")
        XCTAssertEqual(fromConfig.text(for: .version), "1.0.0")
        XCTAssertEqual(fromConfig.text(for: .description), "示例来源")
        XCTAssertTrue(fromConfig.enabled)
        XCTAssertFalse(fromConfig.shared)

        // A row with no config at all still reads its legacy columns, and no branch means `main` (`:37`).
        let legacy = makeForm(row: try Self.row(url: "https://old.example/skills.git", branch: ""))
        XCTAssertEqual(legacy.text(for: .url), "https://old.example/skills.git")
        XCTAssertEqual(legacy.text(for: .branch), "main")

        let npm = makeForm(row: try Self.row(type: "NPM", config: ["packageName": "@harnax/skills", "registry": ""]))
        XCTAssertEqual(npm.text(for: .packageName), "@harnax/skills")
        XCTAssertEqual(npm.typeChoice, .npm)
    }

    /// An edit sends no type and no legacy `url` / `branch`: the update DTO has no `sourceType` at all
    /// (`SkillSourceUpdateRequest.kt:6-37`), and the console drops the two columns because under NPM they
    /// would be empty strings asking to overwrite a real value (`RepositoryForm.tsx:104-107`).
    func testAnEditSendsNoTypeNoUrlAndNoBranch() async throws {
        let stored = try Self.row(config: ["url": "https://live.example/skills.git", "branch": "develop"])
        let skills = FakeSkills()
        skills.updateReplies = [.success(EmptyResponse())]
        let form = makeForm(row: stored, skills: skills)
        form.setValue("改名后的仓库", for: .name)
        form.setValue("main", for: .branch)

        let outcome = await form.save()
        XCTAssertEqual(outcome, .updated)
        let (id, patch) = try XCTUnwrap(skills.updateRequests.first)
        XCTAssertEqual(id, stored.id)
        XCTAssertEqual(patch.name, "改名后的仓库")
        XCTAssertEqual(patch.sourceConfig, ["url": "https://live.example/skills.git", "branch": "main"])
        XCTAssertEqual(patch.version, "1.0.0", "a field nobody touched travels as the row stored it")
        XCTAssertEqual(patch.status, 1)
        XCTAssertEqual(patch.isPublic, 0)
        XCTAssertNil(patch.url)
        XCTAssertNil(patch.branch)
        XCTAssertEqual(
            try keys(of: patch),
            ["description", "isPublic", "name", "sourceConfig", "status", "version"],
            "the update DTO has no `sourceType`, and the legacy pair stays off it too"
        )
    }

    /// The type of an existing row is not a choice: the `Select` is disabled (`RepositoryForm.tsx:205`) because
    /// the body could carry no switch.
    func testTheTypeOfAnExistingRowCannotBeSwitched() throws {
        let form = makeForm(row: try Self.row(type: "GIT", config: ["url": "https://a.example/x.git", "branch": "main"]))
        form.setType(.npm)
        XCTAssertEqual(form.typeChoice, .git)
        XCTAssertEqual(form.credentialFields, [.url, .branch])
    }

    /// A ZIP row is still editable, but its configuration is fixed at upload time and the server throws a
    /// config change for it away (`SkillSourceServiceImpl.kt:236-241`) — so the form sends none, and shows the
    /// archive the row was built from instead (`RepositoryList.tsx:422-426`).
    func testEditingAZipRowSendsNoConfigAtAll() async throws {
        let stored = try Self.row(type: "ZIP", config: ["originalFilename": "skills.zip"])
        let skills = FakeSkills()
        skills.updateReplies = [.success(EmptyResponse())]
        let form = makeForm(row: stored, skills: skills)
        XCTAssertTrue(form.showsStoredArchive)
        XCTAssertEqual(form.storedArchive, "skills.zip")
        XCTAssertTrue(form.credentialFields.isEmpty)

        _ = await form.save()
        let patch = try XCTUnwrap(skills.updateRequests.first).1
        XCTAssertNil(patch.sourceConfig)
        XCTAssertEqual(try keys(of: patch), ["description", "isPublic", "name", "status", "version"])
    }

    /// `isPublicSwitchDisabled` (`harnax-webui/src/utils/permissionUtil.ts:57-75`): a non-administrator may not
    /// pull somebody else's public row back to private, and the switch is disabled rather than hidden.
    func testThePublicSwitchIsLockedForARowTheAccountMayNotRepublish() throws {
        let foreign = try Self.row(
            id: 8,
            isPublic: 1,
            creator: "liufang",
            config: ["url": "https://a.example/x.git", "branch": "main"]
        )
        let locked = makeForm(row: foreign, account: AccountSnapshot(username: "heqingsong"))
        XCTAssertFalse(locked.canChangeVisibility)
        locked.setIsPublic(false)
        XCTAssertTrue(locked.shared, "a locked switch keeps the stored answer")
        XCTAssertTrue(locked.canSubmit, "everything else about that row is still editable")

        let own = makeForm(row: try Self.row(id: 9, isPublic: 0), account: AccountSnapshot(username: "heqingsong"))
        XCTAssertTrue(own.canChangeVisibility)
        own.setIsPublic(true)
        XCTAssertTrue(own.shared)

        let create = makeForm(account: AccountSnapshot(username: "heqingsong"))
        XCTAssertTrue(create.canChangeVisibility, "a create is never locked")
    }

    // MARK: - the list's half

    /// A create installs on the way in, so its answer is the graded report
    /// (`SkillSourceInstallResponse.kt:12-17`), and the list reloads
    /// (`harnax-webui/src/pages/skill/index.tsx:101-104`).
    func testACreatePublishesTheInstallReportAndReloadsTheList() async throws {
        let skills = FakeSkills()
        try skills.seedSources([Self.sourceRow(id: 11)])
        let vm = SkillSourceListViewModel(skills: skills)
        await vm.refresh()
        XCTAssertEqual(skills.sourceRequests.count, 1)

        vm.beginRepositoryCreate()
        let form = try XCTUnwrap(vm.repositoryForm)
        form.setValue("技能仓库", for: .name)
        form.setValue("https://github.com/example/skills", for: .url)
        skills.createReplies = [.success(try SkillSourceInstallResult.stub(
            name: "技能仓库",
            installed: ["weekly-report"]
        ))]
        try skills.seedSources([Self.sourceRow(id: 11), Self.sourceRow(id: 12, name: "第二个")])
        await vm.submitRepositoryForm()

        XCTAssertEqual(skills.createRequests.count, 1)
        let report = try XCTUnwrap(vm.lastReport, "a 200 says nothing about the skills unless the report is read")
        XCTAssertTrue(report.isClean)
        XCTAssertEqual(report.savedCount, 1)
        XCTAssertEqual(report.bucket(.installed)?.lines.map(\.name), ["weekly-report"])
        XCTAssertNil(vm.configurationHint)
        XCTAssertNil(vm.repositoryForm, "the sheet goes with the write that landed")
        XCTAssertEqual(skills.sourceRequests.count, 2, "the reload is the list's own generation-tokened refresh")
        XCTAssertEqual(vm.items.count, 2, "and it is the refresh that puts the new row on screen")
    }

    /// An edit stores configuration and re-reads nothing, so the console warns instead of congratulating
    /// (`RepositoryForm.tsx:115-124`). The iOS equivalent is a banner that survives the reload it causes.
    func testAnEditLeavesTheReinstallHintInsteadOfASuccess() async throws {
        let stored = try Self.row(id: 21, config: ["url": "https://live.example/skills.git", "branch": "main"])
        let skills = FakeSkills()
        try skills.seedSources([Self.sourceRow(id: 21)])
        let vm = SkillSourceListViewModel(skills: skills)
        await vm.refresh()

        vm.beginRepositoryEdit(stored)
        let form = try XCTUnwrap(vm.repositoryForm)
        XCTAssertEqual(vm.selection, stored.id, "the console selects the row on the way into the edit")
        XCTAssertEqual(form.text(for: .url), "https://live.example/skills.git")
        form.setValue("改个名", for: .name)
        skills.updateReplies = [.success(EmptyResponse())]
        try skills.seedSources([Self.sourceRow(id: 21, name: "改个名")])
        await vm.submitRepositoryForm()

        XCTAssertEqual(skills.updateRequests.first?.0, 21)
        XCTAssertNil(vm.lastReport, "nothing was installed, so there is no report to read")
        XCTAssertEqual(vm.configurationHint, hx("skill.repository.updateHint"))
        XCTAssertNil(vm.repositoryForm)
        XCTAssertEqual(skills.sourceRequests.count, 2)
        vm.dismissConfigurationHint()
        XCTAssertNil(vm.configurationHint)
    }

    /// A refused write keeps the sheet — and the server's own sentence — on screen, and reloads nothing, the
    /// way the console leaves `onSuccess()` uncalled (`RepositoryForm.tsx:133-134`).
    func testARefusedWriteKeepsTheSheetOpenAndReloadsNothing() async throws {
        let skills = FakeSkills()
        try skills.seedSources([Self.sourceRow(id: 31)])
        let vm = SkillSourceListViewModel(skills: skills)
        await vm.refresh()

        vm.beginRepositoryCreate()
        let form = try XCTUnwrap(vm.repositoryForm)
        form.setValue("技能仓库", for: .name)
        form.setValue("https://github.com/example/skills", for: .url)
        skills.createReplies = [.failure(.business(code: 500, message: "Source name already exists"))]
        await vm.submitRepositoryForm()

        XCTAssertEqual(form.errorText, "Source name already exists")
        XCTAssertNotNil(vm.repositoryForm, "the operator has something to fix in that sheet")
        XCTAssertNil(vm.lastReport)
        XCTAssertNil(vm.configurationHint)
        XCTAssertEqual(skills.sourceRequests.count, 1)
    }

    /// One write per sheet while a request is on the wire — the same re-entry rule the row writes keep
    /// (`RowWriteReentryTests`).
    func testASecondTapWhileTheCreateIsOnTheWireSendsOneRequest() async throws {
        let skills = FakeSkills()
        try skills.seedSources([Self.sourceRow(id: 41)])
        let vm = SkillSourceListViewModel(skills: skills)
        await vm.refresh()

        vm.beginRepositoryCreate()
        let form = try XCTUnwrap(vm.repositoryForm)
        form.setValue("技能仓库", for: .name)
        form.setValue("https://github.com/example/skills", for: .url)
        skills.gateRepositoryWrites = true
        skills.createReplies = [.success(try SkillSourceInstallResult.stub(name: "技能仓库"))]
        let first = Task { await vm.submitRepositoryForm() }
        try await waitUntil { skills.createRequests.count == 1 }

        await vm.submitRepositoryForm()
        XCTAssertEqual(skills.createRequests.count, 1, "a save was already running for that sheet")

        try skills.seedSources([Self.sourceRow(id: 41), Self.sourceRow(id: 42, name: "第二个")])
        skills.releaseWrites()
        await first.value
        XCTAssertEqual(skills.createRequests.count, 1)
        XCTAssertNil(vm.repositoryForm)
        XCTAssertEqual(vm.items.count, 2)
    }

    /// Both entries are the console's: one Create button on the repository card
    /// (`harnax-webui/src/pages/skill/index.tsx:191-195,289-294`) and the row's own edit action
    /// (`RepositoryList.tsx:459-463`), the latter inside the permission cluster the whole menu already lives
    /// in (`SkillSourceListView.swift:157-159`). `swift test` cannot drive a SwiftUI menu, so the wiring is
    /// asserted off the source the same way the other view gates in this target do.
    func testTheListWiresBothEntriesIntoTheConsoleSlots() throws {
        let text = try FeatureSources.contents(of: "HarnaxFeatures/Skills/SkillSourceListView.swift")
        XCTAssertTrue(text.contains("vm.beginRepositoryCreate()"), "the create entry has to open the form")
        XCTAssertTrue(text.contains("vm.beginRepositoryEdit(source)"), "and the row edit hands it its own row")
        XCTAssertTrue(text.contains("SkillRepositoryFormSheet"), "presented as a sheet, as every other write here is")
        XCTAssertTrue(
            text.contains("await vm.submitRepositoryForm()"),
            "the sheet goes through the list view model, which owns the reload and the report"
        )
        XCTAssertTrue(text.contains("account: account"), "the form needs the account for the visibility gate")
        let edit = text.range(of: "vm.beginRepositoryEdit(source)")
        let delete = text.range(of: "vm.requestDelete(source)")
        XCTAssertTrue(
            unwrap(edit).lowerBound < unwrap(delete).lowerBound,
            "the console puts edit immediately before delete (`RepositoryList.tsx:459-465`)"
        )
    }

    // MARK: - helpers

    /// The keys a body actually encodes with. `SkillSourceUpdatePayload` has eight properties, so asserting
    /// the values alone would not catch a `url` that went out as nil-but-present.
    private func keys<T: Encodable>(of value: T) throws -> [String] {
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(value))
        return ((object as? [String: Any])?.keys ?? [:].keys).sorted()
    }

    private func unwrap<T>(_ value: T?) -> T {
        guard let value else { XCTFail("the wiring this asserts is not in the file"); fatalError("unreachable") }
        return value
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

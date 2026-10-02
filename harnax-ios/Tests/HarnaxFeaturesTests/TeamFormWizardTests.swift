import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// C4 — the three-step team wizard's behaviour, one rule at a time.
///
/// Four things hold this screen together and each is asserted rather than left to the layout. Steps have
/// different rights: step 1's four fields are what 「下一步」 demands, step 2 may be walked out of with nothing
/// chosen, and step 3 is where the save lives and re-checks *everything* before writing
/// (`TeamWizard.tsx:225`-`:240`, `:173`-`:198`). Two nulls are load-bearing rather than incidental: `skillIds`
/// is 集合语义 and goes out only when the sorted id set really changed
/// (`TeamWizard.tsx:199`-`:210`; `TeamServiceImpl.kt:124`-`:132`), while `members` is always a whole
/// replacement (`TeamServiceImpl.kt:127`-`:130`). References may be broken and must not be hidden
/// (`TeamServiceImpl.kt:182`-`:202`, `MembersField.tsx:62`-`:81`). And the two refusal families belong to
/// different steps, which is what decides where a save leaves the operator.
@MainActor
final class TeamFormWizardTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HarnaxCatalog.shared.language = .en
    }

    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }

    // MARK: - the walk, and who reads when

    /// Opening the wizard sends no read at all: the list row an edit arrived on already carries the lead's
    /// prompt, model, skills and members, and there is no detail endpoint on this path
    /// (`specs/01-agent-team.md:253`-`:257`).
    func testOpeningTheWizardSendsNoReadAtAll() throws {
        let rig = try rig()
        XCTAssertTrue(rig.models.choiceRequests.isEmpty)
        XCTAssertTrue(rig.skills.sourceRequests.isEmpty)
        XCTAssertTrue(rig.agents.requests.isEmpty)
        XCTAssertTrue(rig.teams.requests.isEmpty)
        XCTAssertEqual(rig.vm.step, .basic)
    }

    /// A create starts the way the console's `Form` does: one blank skill row and one blank member row
    /// (`TeamWizard.tsx:78` `useState<SkillConfigState[]>([{}])`, `:279` `initialValues={{ members: [{}] }}`).
    func testACreateStartsWithOneBlankRowOfEachKind() throws {
        let rig = try rig()
        XCTAssertEqual(rig.vm.skillRows.count, 1)
        XCTAssertTrue(rig.vm.skillRows[0].isEmpty)
        XCTAssertEqual(rig.vm.memberRows.count, 1)
        XCTAssertNil(rig.vm.memberRows[0].agentID)
    }

    /// Entering a step reads that step's candidates and nobody else's.
    func testEnteringAStepReadsOnlyThatStepsCandidates() async throws {
        let rig = try rig()
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])

        await rig.vm.load(.basic)

        XCTAssertEqual(rig.models.choiceRequests.count, 1)
        XCTAssertTrue(rig.skills.sourceRequests.isEmpty, "step 1 has no business asking the skill domain")
        XCTAssertTrue(rig.agents.requests.isEmpty, "step 1 has no business asking the agent domain")
    }

    /// The seed page the member dropdown opens with: one page of 200 enabled agents
    /// (`harnax-webui/src/pages/team/index.tsx:76`-`:83`).
    func testTheMemberSeedIsTwoHundredEnabledAgents() async throws {
        let rig = try rig()
        try seed(rig, agents: [["id": 11, "name": "研究员"]])

        await rig.vm.load(.member)

        XCTAssertEqual(rig.agents.requests.last?.num, 1)
        XCTAssertEqual(rig.agents.requests.last?.size, 200)
        XCTAssertNil(rig.agents.filters.last?.name)
        XCTAssertEqual(rig.agents.filters.last?.status, 1, "a stopped agent cannot be a member")
        XCTAssertEqual(rig.vm.memberCandidates.count, 1)
    }

    /// A step is read once, however many times the operator walks over it; `reload` is the retry hatch.
    func testAStepIsReadOnceUntilItIsReloaded() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])

        await rig.vm.load(.skill)
        await rig.vm.load(.skill)
        XCTAssertEqual(rig.skills.sourceRequests.count, 1)

        rig.skills.sourceReplies = [.success(try sources([["id": 7, "name": "团队技能源"]]))]
        await rig.vm.reload(.skill)
        XCTAssertEqual(rig.skills.sourceRequests.count, 2, "the retry affordance has to actually retry")
    }

    func testAFailedCandidateReadSaysSoAndTheRetryClearsIt() async throws {
        let rig = try rig()
        rig.models.choiceReplies = [.failure(.offline)]

        await rig.vm.load(.basic)
        XCTAssertNotNil(rig.vm.candidateError)
        XCTAssertTrue(rig.vm.modelOptions.isEmpty)

        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])
        await rig.vm.reload(.basic)
        XCTAssertNil(rig.vm.candidateError)
        XCTAssertEqual(rig.vm.modelOptions.count, 1)
    }

    // MARK: - stepping, and what each step may refuse

    func testTheFirstStepRefusesAndNamesAllFourFields() throws {
        let rig = try rig()
        let issues = rig.vm.next()

        XCTAssertEqual(issues.map(\.field), [.name, .detail, .prompt, .model])
        XCTAssertEqual(rig.vm.step, .basic, "a refused step stays on screen")
        XCTAssertTrue(issues.allSatisfy { !$0.message.isEmpty })
    }

    /// Step 1 holds the fields the save route demands; there is no way around them
    /// (`TeamWizard.tsx:230`-`:235`).
    func testTheFirstStepCannotBeSkipped() throws {
        let rig = try rig()
        rig.vm.skip()
        XCTAssertEqual(rig.vm.step, .basic)
        XCTAssertFalse(TeamWizardStep.basic.isSkippable)
    }

    /// The last step is where the save lives, so it has no way out either: 「至少需要一个成员」 is refused there
    /// (`TeamWizard.tsx:192`-`:198`, and `@NotEmpty` on `TeamCreateRequest.kt:37`-`:40` covers update too).
    func testTheMemberStepCannotBeSkipped() throws {
        let rig = try rig()
        rig.vm.step = .member
        rig.vm.skip()
        XCTAssertEqual(rig.vm.step, .member)
        XCTAssertFalse(TeamWizardStep.member.isSkippable)
    }

    /// 「下一步」 on step 2 lets an untouched skill step through: a blank row is not an issue
    /// (`configValidation.ts:138`-`:148`), which is how the console gets past an optional step at all — it has
    /// no 跳过 button (`TeamWizard.tsx:437`-`:447`).
    func testTheSkillStepWalksOnWithNothingChosen() throws {
        let rig = try rig()
        rig.vm.step = .skill

        XCTAssertTrue(rig.vm.validate(.skill).isEmpty)
        XCTAssertTrue(rig.vm.next().isEmpty)
        XCTAssertEqual(rig.vm.step, .member)
    }

    /// A half-made skill row holds step 2 shut, and a step that refuses cannot be walked *into* anything:
    /// `skip()` is the only way out, and the save brings the operator straight back.
    func testAHalfFilledSkillRowHoldsTheStepButSaveBringsItBack() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        await rig.vm.load(.skill)
        rig.vm.step = .skill
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[0].id)
        fillIn(rig.vm)

        XCTAssertEqual(rig.vm.validate(.skill).map(\.field), [.skill])
        XCTAssertEqual(rig.vm.next().map(\.field), [.skill])
        XCTAssertEqual(rig.vm.step, .skill, "下一步 validates")

        rig.vm.skip()
        XCTAssertEqual(rig.vm.step, .member)
        let result = await rig.vm.save()
        guard case let .invalid(_, step) = result else { return XCTFail("expected the skill refusal, got \(result)") }
        XCTAssertEqual(step, .skill, "the skill rules run first, so the row is where the operator lands")
        XCTAssertTrue(rig.teams.saveCalls.isEmpty)
    }

    func testBackFromTheFirstStepDoesNothingAndFromTheSecondGoesBack() throws {
        let rig = try rig()
        rig.vm.back()
        XCTAssertEqual(rig.vm.step, .basic)

        rig.vm.step = .skill
        rig.vm.back()
        XCTAssertEqual(rig.vm.step, .basic)
    }

    // MARK: - step 1's own rules

    /// `getModelList` then `status===1 && modelType==='chat'` (`TeamWizard.tsx:139`-`:150`) — a stopped model
    /// and an embedding model are both unusable as a lead.
    func testOnlyEnabledChatModelsAreOffered() async throws {
        let rig = try rig()
        try seed(
            rig,
            models: [
                ["id": 1, "name": "对话", "status": 1, "modelType": "chat"],
                ["id": 2, "name": "向量", "status": 1, "modelType": "embedding"],
                ["id": 3, "name": "停用的对话", "status": 0, "modelType": "chat"],
            ]
        )

        await rig.vm.load(.basic)

        XCTAssertEqual(rig.vm.modelChoices.map(\.id), [1])
        XCTAssertEqual(rig.vm.modelOptions.map(\.id), [1])
    }

    /// The one piece of copy the console prints per model, kept whole
    /// (`TeamWizard.tsx:50`-`:56`: `{modelName} - {provider||未知供应商} ¥{price||0}/M`).
    func testAModelOptionLineCarriesNameProviderAndPrice() async throws {
        let rig = try rig()
        try seed(rig, models: [["id": 1, "name": "展示名", "modelName": "qwen-max", "providerName": "阿里云", "price": 2.5, "status": 1, "modelType": "chat"]])

        await rig.vm.load(.basic)

        XCTAssertEqual(rig.vm.modelOptions.map(\.title), ["qwen-max - 阿里云 ¥2.5 per million tokens"])
    }

    /// A stored model that has fallen out of the candidate page stays listed, refuses a tap, and is named with
    /// the row's own model name plus the unavailability suffix (`TeamWizard.tsx:49`-`:68`, where the console
    /// `unshift`s a `disabled: true` option).
    func testAStoredModelThatFellOutOfThePageStaysListedAndRefusesATap() async throws {
        let row = try team(id: 3, name: "报表团队", extra: [
            "modelId": 88,
            "modelName": "gpt-4",
            "systemPrompt": "旧提示词",
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])
        await rig.vm.load(.basic)

        XCTAssertNil(rig.vm.selectedModel)
        XCTAssertTrue(rig.vm.isStoredModelUnavailable)
        XCTAssertEqual(rig.vm.modelName, "gpt-4 · " + hx("team.wizard.model.unavailable"))
        let choices = rig.vm.modelChoices
        XCTAssertEqual(choices.count, 2, "the stale row is listed too, or the form cannot say what it holds")
        XCTAssertEqual(choices.first?.id, 88, "and first, the way the console unshifts it")
        XCTAssertFalse(try XCTUnwrap(choices.first).isSelectable)
        XCTAssertEqual(rig.vm.modelOptions.map(\.id), [1], "a tap can only land on a live model")

        let live = try XCTUnwrap(rig.vm.modelOptions.first)
        rig.vm.pickModel(live)
        XCTAssertEqual(rig.vm.modelID, 1, "a tap on a live row swaps the dead reference out")
        XCTAssertFalse(rig.vm.isStoredModelUnavailable)
        rig.vm.clearModel()
        XCTAssertNil(rig.vm.modelID)
        XCTAssertEqual(rig.vm.modelName, hx("team.wizard.model.none"))
        XCTAssertFalse(rig.vm.isStoredModelUnavailable, "no selection is not a broken selection")
    }

    /// A lead model has no `…Available` flag in the team DTO, unlike a skill or a member, so the candidate page
    /// is the only thing that can prove the stored id is gone. Until it answers — and if it never does — the row
    /// has to name the model it holds without accusing anybody of deleting it.
    func testAnUnansweredModelReadMakesNoClaimAboutTheStoredModel() async throws {
        let row = try team(id: 3, name: "报表团队", extra: [
            "modelId": 88,
            "modelName": "gpt-4",
            "systemPrompt": "旧提示词",
        ])
        let rig = try rig(mode: .edit(row))

        XCTAssertNil(rig.vm.selectedModel)
        XCTAssertFalse(rig.vm.isStoredModelUnavailable, "nothing has been read yet")
        XCTAssertEqual(rig.vm.modelName, "gpt-4")
        XCTAssertEqual(rig.vm.modelChoices.map(\.id), [], "and no row is offered as dead either")

        rig.models.choiceReplies = [.failure(.offline)]
        await rig.vm.loadModels()
        XCTAssertNotNil(rig.vm.candidateError)
        XCTAssertFalse(rig.vm.isStoredModelUnavailable, "a failed read proves nothing")
        XCTAssertEqual(rig.vm.modelName, "gpt-4")

        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])
        await rig.vm.loadModels()
        XCTAssertTrue(rig.vm.isStoredModelUnavailable, "an answered page that lacks the id is the evidence")
        XCTAssertEqual(rig.vm.modelName, "gpt-4 · " + hx("team.wizard.model.unavailable"))
    }

    /// 是否公开 has no permission gate on a team at all, unlike the agent form: the console's `Switch` carries
    /// no `disabled` (`TeamWizard.tsx:388`-`:401`) and `updateTeam` takes `isPublic` straight from the body
    /// (`TeamServiceImpl.kt:112`).
    func testTheVisibilitySwitchIsFreeEvenOnAnotherUsersTeam() throws {
        let row = try team(id: 3, name: "报表团队", extra: [
            "creator": "someoneelse",
            "isPublic": 1,
            "systemPrompt": "提示词",
            "modelId": 1,
        ])
        let rig = try rig(mode: .edit(row), account: AccountSnapshot(username: "liufang"))

        XCTAssertTrue(rig.vm.canChangeVisibility)
        XCTAssertTrue(rig.vm.isPublic, "the row was public")
        rig.vm.setIsPublic(false)
        XCTAssertFalse(rig.vm.isPublic)
        XCTAssertEqual(rig.vm.buildDraft().isPublic, 0, "and the switch reaches the body")
    }

    /// `@Size(min = 1, max = 100)` counts UTF-16 code units (`TeamCreateRequest.kt:17`-`:20`), which a grapheme
    /// count understates for anything outside the basic plane.
    func testTheNameLimitIsCountedTheWayTheServerCountsIt() throws {
        let rig = try rig()
        rig.vm.detail = "描述"
        rig.vm.systemPrompt = "提示词"
        rig.vm.modelID = 1

        rig.vm.name = String(repeating: "字", count: 100)
        XCTAssertTrue(rig.vm.validate(.basic).isEmpty, "100 of these are 100 units, which the server accepts")

        rig.vm.name = String(repeating: "字", count: 101)
        XCTAssertEqual(rig.vm.validate(.basic).map(\.field), [.name])

        let emoji = String(repeating: "😀", count: 51)
        XCTAssertEqual(emoji.count, 51)
        XCTAssertEqual(emoji.utf16.count, 102)
        rig.vm.name = emoji
        XCTAssertEqual(
            rig.vm.validate(.basic).map(\.field),
            [.name],
            "51 of these are 102 units, and the server refuses them even though a grapheme count does not"
        )
    }

    /// `description` and `systemPrompt` are `@NotBlank`, not merely `@NotNull`
    /// (`TeamCreateRequest.kt:22`-`:28`), so whitespace is nothing typed.
    func testWhitespaceIsNothingForTheThreeNotBlankFields() throws {
        let rig = try rig()
        rig.vm.name = "   "
        rig.vm.detail = "\n\t "
        rig.vm.systemPrompt = " "
        rig.vm.modelID = 1

        XCTAssertEqual(rig.vm.validate(.basic).map(\.field), [.name, .detail, .prompt])
    }

    func testTheDraftTrimsNameAndDescriptionButNotThePrompt() throws {
        let rig = try rig()
        rig.vm.name = "  报表团队  "
        rig.vm.detail = "  抓取并汇总  "
        rig.vm.systemPrompt = "  你负责拆解  \n"
        let draft = rig.vm.buildDraft()

        XCTAssertEqual(draft.name, "报表团队")
        XCTAssertEqual(draft.description, "抓取并汇总")
        XCTAssertEqual(draft.systemPrompt, "  你负责拆解  \n", "the prompt goes out as written, markdown and all")
    }

    /// A create body fixes `status: 1`; the update leg sends no status, so an edit of a stopped team cannot
    /// switch it back on (`harnax-webui/src/pages/team/index.tsx:368` against `:393`).
    func testACreateCarriesAStatusAndAnEditDoesNot() throws {
        let created = try rig()
        XCTAssertEqual(created.vm.buildDraft().status, 1)

        let row = try team(id: 3, name: "报表团队", extra: ["status": 0])
        let edited = try rig(mode: .edit(row))
        XCTAssertNil(edited.vm.buildDraft().status, "an edit must not switch a stopped team back on")
    }

    /// There is no `owner`/`creator` on this form at all, and the body has no key for one: `createTeam` stamps
    /// the calling account itself (`TeamServiceImpl.kt:88`).
    func testNothingInTheBodyNamesAnOwner() throws {
        let rig = try rig()
        fillIn(rig.vm)
        let body = try json(rig.vm.buildDraft())

        XCTAssertFalse(body.keys.contains("owner"))
        XCTAssertFalse(body.keys.contains("creator"))
        XCTAssertEqual(
            Set(body.keys),
            ["name", "description", "systemPrompt", "modelId", "isPublic", "status", "skillIds", "members"]
        )
    }

    // MARK: - the lead's skills

    /// A row that names a repository and no skill is half-made and refused, not dropped
    /// (`configValidation.ts:141`).
    func testASkillRowWithARepositoryButNoSkillIsRefused() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        await rig.vm.load(.skill)
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[0].id)

        XCTAssertEqual(rig.vm.validate(.skill).map(\.field), [.skill])
        XCTAssertTrue(rig.vm.buildDraft().skillIDs?.isEmpty ?? false, "and the half row reaches the wire as nothing")
    }

    /// The built-in CLI repository is not offered: a team has no CLI leg, so its skills can only arrive through
    /// a CLI binding (`TeamWizard.tsx:129`-`:137`; `SkillBindingResolver.kt:53`-`:60` refuses them again).
    func testTheBuiltinSkillRepositoryIsNotOffered() async throws {
        let rig = try rig()
        try seed(rig, sources: [
            ["id": 7, "name": "团队技能源"],
            ["id": 9, "name": "builtin-cli-skills", "sourceType": "BUILTIN_CLI"],
        ])

        await rig.vm.load(.skill)

        XCTAssertEqual(rig.vm.repositoryOptions.map(\.id), [7])
    }

    /// The skill page is asked per repository, once, with `status=1`: a disabled skill cannot be bound
    /// (`TeamWizard.tsx:152`-`:164`).
    func testTheSkillPageIsScopedToTheRepositoryAndAskedOnce() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        try seed(rig, skills: ["id": 51, "name": "PDF"])
        await rig.vm.load(.skill)
        rig.vm.addSkillRow()

        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[0].id)
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[1].id)

        let requests = rig.skills.skillRequests.filter { $0.repositoryID == 7 }
        XCTAssertEqual(requests.count, 1, "two rows on one repository is one read")
        XCTAssertEqual(requests.first?.status, 1)
    }

    /// The duplicate that a picker cannot produce but an edit can: the backend's `distinct()` keeps the first
    /// row and silently loses the second's value, so the form refuses the pair
    /// (`configValidation.ts:142`-`:145`).
    func testTwoRowsForTheSameSkillFromAnEditAreRefused() throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
        ])
        let rig = try rig(mode: .edit(row))

        let issues = rig.vm.validate(.skill)
        XCTAssertEqual(issues.count, 1)
        XCTAssertTrue(issues[0].message.contains("twice"), "\(issues[0].message)")
    }

    /// Same name, different id: the resolver refuses the pair outright (`SkillBindingResolver.kt:63`-`:70`).
    func testTwoSkillRowsWithTheSameNameButDifferentIdsAreRefused() throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
            ["skillId": 52, "skillName": "pdf", "repositoryId": 7],
        ])
        let rig = try rig(mode: .edit(row))

        let issues = rig.vm.validate(.skill)
        XCTAssertEqual(issues.count, 1)
        XCTAssertTrue(issues[0].message.contains("pdf"), "\(issues[0].message)")
    }

    /// The last skill row is cleared rather than deleted, so the step always has a place to pick into
    /// (`SkillConfigPanel.tsx:63`-`:84`).
    func testTheLastSkillRowIsClearedInPlace() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        try seed(rig, skills: ["id": 51, "name": "PDF"])
        await rig.vm.load(.skill)
        let rowID = rig.vm.skillRows[0].id
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rowID)
        rig.vm.pickSkill(HXEntityPickerOption(id: 51, title: "PDF"), into: rowID)

        rig.vm.removeSkillRow(rowID)
        XCTAssertEqual(rig.vm.skillRows.count, 1)
        XCTAssertEqual(rig.vm.skillRows[0].id, rowID, "the same slot, emptied")
        XCTAssertTrue(rig.vm.skillRows[0].isEmpty)

        rig.vm.addSkillRow()
        rig.vm.removeSkillRow(rig.vm.skillRows[1].id)
        XCTAssertEqual(rig.vm.skillRows.count, 1, "a second row makes the delete a real delete")
    }

    // MARK: - 集合语义: when `skillIds` goes out at all

    /// An edit that never touched the skill set leaves the field off: sending it would hand a stored broken
    /// skill to the whole-replacement guard, which refuses it, so the team could not even be renamed
    /// (`TeamWizard.tsx:199`-`:210`; `TeamServiceImpl.kt:124`-`:132`).
    func testAnUntouchedSkillSetIsLeftOffTheBody() throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
        ])
        let rig = try rig(mode: .edit(row))
        fillIn(rig.vm)

        XCTAssertNil(rig.vm.buildDraft().skillIDs, "no change, no field")
        XCTAssertFalse(try json(rig.vm.buildDraft()).keys.contains("skillIds"))
    }

    /// The comparison is a sorted *set*: bindings are not ordered, so shuffling the rows is not a change.
    func testReorderingTheSkillRowsIsNotAChange() throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
            ["skillId": 52, "skillName": "DOCX", "repositoryId": 7],
        ])
        let rig = try rig(mode: .edit(row))
        rig.vm.skillRows.reverse()
        fillIn(rig.vm)

        XCTAssertNil(rig.vm.buildDraft().skillIDs, "a row shuffle is not a change")
    }

    /// An edit that really did empty the set sends `[]`, which means 「主管不要技能」 — the one thing `nil` is not
    /// (`TeamUpdateRequest.kt:10`-`:13`, `TeamServiceImpl.kt:124`).
    func testAnEmptiedSkillSetSendsAnEmptyArray() throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
        ])
        let rig = try rig(mode: .edit(row))
        rig.vm.skillRows = [TeamSkillRow()]
        fillIn(rig.vm)

        XCTAssertEqual(rig.vm.buildDraft().skillIDs, [], "empty is a decision; absent is not")
    }

    /// A changed set goes out whole, in row order.
    func testAChangedSkillSetGoesOutWhole() async throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        try seed(rig, skills: ["id": 52, "name": "DOCX"])
        await rig.vm.load(.skill)
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[0].id)
        rig.vm.pickSkill(HXEntityPickerOption(id: 52, title: "DOCX"), into: rig.vm.skillRows[0].id)
        fillIn(rig.vm)

        XCTAssertEqual(rig.vm.buildDraft().skillIDs, [52])
    }

    /// A create always sends, nothing chosen included: there is no stored set to leave alone.
    func testACreateAlwaysSendsTheSkillSet() throws {
        let rig = try rig()
        fillIn(rig.vm)

        XCTAssertEqual(rig.vm.buildDraft().skillIDs, [], "and an empty create is still a field on the wire")
    }

    // MARK: - members

    /// A row without an agent is refused rather than dropped: `agentId` is `@NotNull` per item
    /// (`TeamCreateRequest.kt:53`-`:55`), and `validateMembers` counts `Member agent ID cannot be empty`
    /// before anything else (`TeamServiceImpl.kt:280`-`:283`).
    func testABlankMemberRowIsRefusedRatherThanDropped() throws {
        let rig = try rig()
        fillInBasics(rig.vm)

        XCTAssertEqual(rig.vm.validate(.member).map(\.field), [.member])
        XCTAssertTrue(rig.vm.validate(.member)[0].message.contains("member agent"))
    }

    /// Zero rows is the console's own refusal, and it belongs to step 3 and not to step 1
    /// (`TeamWizard.tsx:192`-`:198`): the wizard jumps *forward* to the member step, never back to basics.
    func testATeamWithoutMembersIsRefusedOnStepThree() throws {
        let rig = try rig()
        fillIn(rig.vm)
        rig.vm.memberRows = []
        rig.vm.step = .member

        let issues = rig.vm.validate(.member)
        XCTAssertEqual(issues.count, 1)
        XCTAssertEqual(issues[0].field, .member)
        XCTAssertEqual(issues[0].step, .member, "the refusal owns step 3, not step 1")
        XCTAssertTrue(issues[0].message.contains("At least one member"), "\(issues[0].message)")
    }

    /// The dropdown lets a duplicate through on purpose — `MembersField` leaves every agent selectable and
    /// refuses the pair with a row-level validator (`MembersField.tsx:114`-`:130`, 注意点 「下拉可重复选，靠行级
    /// validator 拦」), and the backend agrees (`TeamServiceImpl.kt:284`-`:286`).
    func testTheSameAgentTwiceIsSelectableButRefused() async throws {
        let rig = try rig()
        try seed(rig, agents: [["id": 11, "name": "研究员"], ["id": 12, "name": "撰写员"]])
        await rig.vm.load(.member)
        fillInBasics(rig.vm)

        rig.vm.addMemberRow()
        rig.vm.pickMember(HXEntityPickerOption(id: 11, title: "研究员"), into: rig.vm.memberRows[0].id)
        XCTAssertTrue(
            rig.vm.memberOptions(excluding: []).contains { $0.id == 11 },
            "an option another row holds is still offered — the refusal is the de-duplication"
        )
        rig.vm.pickMember(HXEntityPickerOption(id: 11, title: "研究员"), into: rig.vm.memberRows[1].id)

        let issues = rig.vm.validate(.member)
        XCTAssertEqual(issues.count, 1)
        XCTAssertTrue(issues[0].message.contains("twice"), "\(issues[0].message)")
    }

    /// The cap on delegation text is 500 in both the form and the DTO
    /// (`MembersField.tsx:150`-`:157`; `TeamCreateRequest.kt:57`-`:59`), again in UTF-16 units.
    func testTheDelegationCapIsCountedInUTF16Units() throws {
        let rig = try rig()
        fillIn(rig.vm)
        let rowID = rig.vm.memberRows[0].id

        rig.vm.setDelegation(String(repeating: "字", count: 500), of: rowID)
        XCTAssertTrue(rig.vm.validate(.member).isEmpty)

        rig.vm.setDelegation(String(repeating: "😀", count: 251), of: rowID)
        XCTAssertEqual(rig.vm.memberRows[0].delegationDescription.utf16.count, 502)
        XCTAssertEqual(rig.vm.validate(.member).map(\.field), [.delegation])
    }

    /// A blank delegation is sent as *absent*, so the backend falls back to the member's own description
    /// (`TeamServiceImpl.kt:301`-`:304`); a typed one goes out trimmed but otherwise whole.
    func testABlankDelegationIsAbsentAndATypedOneIsTrimmed() throws {
        let rig = try rig()
        fillIn(rig.vm)
        rig.vm.addMemberRow()
        rig.vm.pickMember(HXEntityPickerOption(id: 12, title: "撰写员"), into: rig.vm.memberRows[1].id)
        rig.vm.setDelegation("  负责成文  ", of: rig.vm.memberRows[1].id)

        let members = try XCTUnwrap(rig.vm.buildDraft().members)
        XCTAssertEqual(members.count, 2)
        XCTAssertNil(members[0].delegationDescription, "留空＝不发，后端按成员自身描述兜底")
        XCTAssertEqual(members[1].delegationDescription, "负责成文")

        let body = try json(members[0])
        XCTAssertFalse(body.keys.contains("delegationDescription"), "and the key is off the wire, not empty")
    }

    /// Rows are kept in order, because the lead reads its delegates in that order
    /// (`MembersField.tsx:192`-`:197`, `TeamServiceImpl.kt:296`-`:307`).
    func testMemberOrderSurvivesIntoTheBody() async throws {
        let rig = try rig()
        try seed(rig, agents: [["id": 11, "name": "研究员"], ["id": 12, "name": "撰写员"]])
        await rig.vm.load(.member)
        fillInBasics(rig.vm)

        rig.vm.addMemberRow()
        rig.vm.pickMember(HXEntityPickerOption(id: 11, title: "研究员"), into: rig.vm.memberRows[0].id)
        rig.vm.pickMember(HXEntityPickerOption(id: 12, title: "撰写员"), into: rig.vm.memberRows[1].id)

        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().members).map(\.agentId), [11, 12])
        rig.vm.removeMemberRow(rig.vm.memberRows[0].id)
        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().members).map(\.agentId), [12], "删掉的行就是要消失")
    }

    /// One row always stays, so the step never runs out of a place to pick into
    /// (`MembersField.tsx:170`-`:176`).
    func testTheLastMemberRowCannotBeRemoved() throws {
        let rig = try rig()
        let rowID = rig.vm.memberRows[0].id
        XCTAssertFalse(rig.vm.canRemoveMemberRow)
        rig.vm.removeMemberRow(rowID)
        XCTAssertEqual(rig.vm.memberRows.count, 1)

        rig.vm.addMemberRow()
        XCTAssertTrue(rig.vm.canRemoveMemberRow)
        rig.vm.removeMemberRow(rowID)
        XCTAssertEqual(rig.vm.memberRows.count, 1)
    }

    // MARK: - member search

    /// The console's own shape: `pageSize=50&status=1&name=<keyword>` (`MembersField.tsx:41`-`:44`).
    func testAMemberSearchIsTheConsoleShape() async throws {
        let rig = try rig()
        try seed(rig, agents: [["id": 11, "name": "研究员"]], [["id": 21, "name": "报表员"]])
        await rig.vm.load(.member)

        try await search(rig, for: "报表")

        XCTAssertEqual(rig.agents.requests.count, 2, "the seed page plus one search")
        XCTAssertEqual(rig.agents.filters.last?.name, "报表")
        XCTAssertEqual(rig.agents.filters.last?.status, 1)
        XCTAssertEqual(rig.agents.requests.last?.size, 50)
    }

    /// Clearing the keyword is not a search: the pool returns to the seed page without a request
    /// (`MembersField.tsx:37`-`:40`).
    func testAClearedKeywordReturnsToTheSeedWithoutARequest() async throws {
        let rig = try rig()
        try seed(rig, agents: [["id": 11, "name": "研究员"]], [["id": 21, "name": "报表员"]])
        await rig.vm.load(.member)
        try await search(rig, for: "报表")

        await rig.vm.searchMembers("   ")

        XCTAssertEqual(rig.agents.requests.count, 2, "no keyword, no call")
        XCTAssertNil(rig.vm.memberSearchResults)
        XCTAssertEqual(rig.vm.memberPool.compactMap(\.id), [11], "the seed page is the pool again")
    }

    /// A failed search falls back to the seed pool rather than claiming the catalogue is empty
    /// (`MembersField.tsx:45`-`:49`) — the claim this screen is not entitled to make.
    func testAFailedSearchFallsBackToTheSeedPage() async throws {
        let rig = try rig()
        rig.agents.replies = [
            .success(try PageStub.page(AgentSummary.self, [["id": 11, "name": "研究员"]])),
            .failure(.offline),
        ]
        await rig.vm.load(.member)

        await rig.vm.searchMembers("报表")

        XCTAssertNil(rig.vm.memberSearchResults, "a failure is 「no search in effect」, not 「nothing matched」")
        XCTAssertEqual(rig.vm.memberPool.compactMap(\.id), [11])
    }

    /// An honest empty answer stays empty: the server did say there is no such agent.
    func testAnEmptySearchResultStaysEmpty() async throws {
        let rig = try rig()
        try seed(rig, agents: [["id": 11, "name": "研究员"]], [])
        await rig.vm.load(.member)

        try await search(rig, for: "不存在")

        XCTAssertEqual(rig.vm.memberSearchResults?.count, 0)
        XCTAssertTrue(rig.vm.memberPool.isEmpty, "「没有结果」 is a claim the server made, so it stands")
    }

    // MARK: - references that no longer resolve

    /// A stored member whose agent is gone keeps its own row in the dropdown, gets flagged, and still goes out
    /// in the body (`MembersField.tsx:62`-`:81`; `TeamServiceImpl.kt:194`-`:202`) — hiding it would let the
    /// operator believe the team is still intact.
    func testAStaleMemberStaysNamedFlaggedAndSendable() async throws {
        let row = try team(id: 3, name: "报表团队", members: [
            ["agentId": 99, "agentName": "旧研究员", "agentAvailable": false, "agentStatus": 0],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, agents: [["id": 11, "name": "研究员"]])
        await rig.vm.load(.member)
        fillInBasics(rig.vm)

        let rowID = rig.vm.memberRows[0].id
        XCTAssertTrue(rig.vm.isMemberUnavailable(for: rowID), "flagged, per row")
        let option = try XCTUnwrap(rig.vm.memberOptions.first { $0.id == 99 })
        XCTAssertEqual(option.title, "旧研究员 · " + hx("team.binding.unavailable"))
        XCTAssertEqual(rig.vm.unavailableSuffix(for: .member), hx("team.binding.unavailable"))
        XCTAssertEqual(rig.vm.memberName(for: rowID), "旧研究员", "and never a bare id")
        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().members).map(\.agentId), [99], "still sent, never dropped")
    }

    /// A member that is merely off this page is not a broken reference: admin's per-member flags decide, and a
    /// search that brings the agent back clears the row's flag.
    func testAMemberThatOnlyMissedTheSeedPageIsNotFlagged() async throws {
        let row = try team(id: 3, name: "报表团队", members: [
            ["agentId": 99, "agentName": "旧研究员", "agentAvailable": true, "agentStatus": 1],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, agents: [["id": 11, "name": "研究员"]], [["id": 99, "name": "旧研究员"]])
        await rig.vm.load(.member)

        XCTAssertFalse(rig.vm.isMemberUnavailable(for: rig.vm.memberRows[0].id))
        XCTAssertEqual(rig.vm.memberOptions.first { $0.id == 99 }?.title, "旧研究员", "one row, no suffix")

        try await search(rig, for: "旧")
        XCTAssertTrue(rig.vm.memberPool.contains { $0.id == 99 })
        XCTAssertFalse(rig.vm.isMemberUnavailable(for: rig.vm.memberRows[0].id))
    }

    /// A stored skill whose row no longer resolves stays in the list with the flag on
    /// (`TeamWizard.tsx:106`-`:113`; `TeamServiceImpl.kt:182`-`:192`) and its id still rides in the set.
    func testAStaleSkillStaysFlaggedAndInheritedIntoTheSet() async throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7, "skillAvailable": false],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        try seed(rig, skills: ["id": 999, "name": "别的技能"])
        await rig.vm.load(.skill)

        let rowID = rig.vm.skillRows[0].id
        XCTAssertTrue(rig.vm.isSkillUnavailable(for: rowID))
        XCTAssertEqual(rig.vm.unavailableSuffix(for: .skill), hx("team.binding.unavailable"))
        XCTAssertEqual(rig.vm.skillRows[0].skillName, "PDF")

        rig.vm.skillRows[0].storedReferenceBroken = false
        XCTAssertFalse(rig.vm.isSkillUnavailable(for: rowID), "the flag is the row's evidence, not a guess")
    }

    /// The flag clears where the candidate page proves the skill is live: the page is asked with `status=1`, so
    /// resolving there is evidence of enablement.
    func testASkillThatTheRepositoryPageStillListsIsNotFlagged() async throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7, "skillAvailable": false],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        try seed(rig, skills: ["id": 51, "name": "PDF"])
        await rig.vm.load(.skill)

        XCTAssertFalse(rig.vm.isSkillUnavailable(for: rig.vm.skillRows[0].id))
    }

    /// Repicking proves a live reference, on both dimensions.
    func testRepickingAClearsTheStoredFlag() async throws {
        let row = try team(id: 3, name: "报表团队", skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7, "skillAvailable": false],
        ], members: [["agentId": 99, "agentName": "旧研究员", "agentAvailable": false]])
        let rig = try rig(mode: .edit(row))
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        try seed(rig, skills: ["id": 52, "name": "DOCX"])
        try seed(rig, agents: [["id": 11, "name": "研究员"]])
        await rig.vm.load(.skill)
        await rig.vm.load(.member)

        rig.vm.pickSkill(HXEntityPickerOption(id: 52, title: "DOCX"), into: rig.vm.skillRows[0].id)
        rig.vm.pickMember(HXEntityPickerOption(id: 11, title: "研究员"), into: rig.vm.memberRows[0].id)

        XCTAssertFalse(rig.vm.isSkillUnavailable(for: rig.vm.skillRows[0].id))
        XCTAssertFalse(rig.vm.isMemberUnavailable(for: rig.vm.memberRows[0].id))
        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().members).map(\.agentId), [11])
    }

    // MARK: - an edit seeds from the row it arrived on

    func testAnEditSeedsEveryFieldWithoutARead() throws {
        let row = try team(id: 3, name: "报表团队", extra: [
            "description": "抓取并汇总",
            "systemPrompt": "你负责拆解",
            "modelId": 1,
            "modelName": "qwen-max",
            "isPublic": 1,
        ], skills: [["skillId": 51, "skillName": "PDF", "repositoryId": 7, "repositoryName": "团队技能源"]], members: [
            ["agentId": 11, "agentName": "研究员", "delegationDescription": "负责抓取"],
            ["agentId": 12, "agentName": "撰写员"],
        ])
        let rig = try rig(mode: .edit(row))

        XCTAssertEqual(rig.vm.name, "报表团队")
        XCTAssertEqual(rig.vm.detail, "抓取并汇总")
        XCTAssertEqual(rig.vm.systemPrompt, "你负责拆解")
        XCTAssertEqual(rig.vm.modelID, 1)
        XCTAssertTrue(rig.vm.isPublic)
        XCTAssertEqual(rig.vm.skillRows.count, 1)
        XCTAssertEqual(rig.vm.skillRows[0].skillID, 51)
        XCTAssertEqual(rig.vm.skillRows[0].repositoryName, "团队技能源")
        XCTAssertEqual(rig.vm.memberRows.map(\.agentID), [11, 12])
        XCTAssertEqual(rig.vm.memberRows[0].delegationDescription, "负责抓取")
        XCTAssertEqual(rig.vm.memberRows[1].delegationDescription, "", "a member with no delegation starts blank")
        XCTAssertTrue(rig.agents.requests.isEmpty, "there is no detail endpoint on this path")
        XCTAssertTrue(rig.skills.sourceRequests.isEmpty)
    }

    /// `modelId` is a non-null `Long` defaulting to `0`, which is this DTO's 「没有主管模型」 rather than a model
    /// called zero (`TeamResponse.kt:24`-`:25`).
    func testAModelIdOfZeroSeedsNoSelection() throws {
        let row = try team(id: 3, name: "报表团队")
        let rig = try rig(mode: .edit(row))

        XCTAssertNil(rig.vm.modelID)
        XCTAssertEqual(rig.vm.modelName, hx("team.wizard.model.none"))
        XCTAssertFalse(rig.vm.isStoredModelUnavailable)
    }

    /// A lead with no stored skill still gets a slot to pick into, and its baseline is the empty set — which is
    /// why adding one skill is a change and deleting it again is not a no-op.
    func testAnEditWithNoSkillOrMemberStillSeedsOneRowEach() throws {
        let row = try team(id: 3, name: "报表团队")
        let rig = try rig(mode: .edit(row))

        XCTAssertEqual(rig.vm.skillRows.count, 1)
        XCTAssertTrue(rig.vm.skillRows[0].isEmpty)
        XCTAssertEqual(rig.vm.memberRows.count, 1)
        XCTAssertNil(rig.vm.buildDraft().skillIDs, "empty baseline, empty now: the set did not change")
    }

    func testTheTitlesFollowTheMode() throws {
        let created = try rig()
        XCTAssertTrue(created.vm.isCreate)
        XCTAssertEqual(created.vm.titleKey, "team.wizard.title.create")
        XCTAssertEqual(created.vm.subtitleKey, "team.wizard.subtitle.create")

        let row = try team(id: 3, name: "报表团队")
        let edited = try rig(mode: .edit(row))
        XCTAssertFalse(edited.vm.isCreate)
        XCTAssertEqual(edited.vm.titleKey, "team.wizard.title.edit")
        XCTAssertEqual(edited.vm.subtitleKey, "team.wizard.subtitle.edit")
    }

    // MARK: - submitting

    /// The save on the last step re-checks everything (`TeamWizard.tsx:225`-`:240`), and the refusal walks the
    /// operator to the step that owns the first problem — skills first, then basics, then members, which is the
    /// order `handleFinish` reaches them in (`:175`-`:198`).
    func testASaveRefusesAndJumpsToTheStepThatHoldsTheFirstProblem() async throws {
        let rig = try rig()
        rig.vm.step = .member

        let result = await rig.vm.save()

        XCTAssertTrue(rig.teams.saveCalls.isEmpty, "a wizard that knows the body is short cannot send it")
        guard case let .invalid(issues, step) = result else { return XCTFail("expected a refusal, got \(result)") }
        XCTAssertEqual(step, .basic, "the basics are the first family handleFinish fails on once skills pass")
        XCTAssertEqual(rig.vm.step, .basic, "the operator has to land where the sentence points")
        XCTAssertEqual(issues.map(\.field), [.name, .detail, .prompt, .model, .member])
    }

    /// A broken skill outranks a broken basic: `showSkillIssue()` runs first and `setCurrentStep(1)` with it.
    func testABrokenSkillOutranksABrokenBasic() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源"]])
        await rig.vm.load(.skill)
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[0].id)
        rig.vm.step = .member

        let result = await rig.vm.save()

        guard case let .invalid(issues, step) = result else { return XCTFail("expected a refusal, got \(result)") }
        XCTAssertEqual(step, .skill, "even with step 1 empty, the skill row is the first problem")
        XCTAssertEqual(issues.first?.field, .skill)
    }

    func testACreateSendsOnePostAndReportsCreated() async throws {
        let rig = try rig()
        fillIn(rig.vm)

        let result = await rig.vm.save()

        XCTAssertEqual(result, .created)
        XCTAssertEqual(rig.teams.saveCalls.count, 1)
        XCTAssertNil(rig.teams.saveCalls[0].id, "the create route carries no id")
        XCTAssertEqual(rig.teams.saveCalls[0].draft.name, "报表团队")
        XCTAssertEqual(rig.teams.saveCalls[0].draft.members?.map(\.agentId), [11])
    }

    func testAnUpdateIsAddressedByIDAndGivesTheRefreshPanelItsRow() async throws {
        let row = try team(id: 3, name: "报表团队", members: [["agentId": 11, "agentName": "研究员"]])
        let rig = try rig(mode: .edit(row))
        fillIn(rig.vm)
        rig.teams.relatedReply = .success([])

        let result = await rig.vm.save()

        XCTAssertEqual(result, .updated(id: 3, name: "报表团队"))
        XCTAssertEqual(rig.teams.saveCalls.map(\.id), [3], "PUT /teams/{id}, addressed by the row it came from")
        XCTAssertNil(rig.teams.saveCalls[0].draft.status, "an update body carries no status")

        let target = rig.vm.makeRefreshTarget(id: 3, name: "报表团队")
        XCTAssertEqual(target.id, 3)
        XCTAssertEqual(target.name, "报表团队")
        _ = await target.load()
        XCTAssertEqual(rig.teams.relatedRequests, [3], "the panel reads through the team domain, not a second client")
    }

    /// A refused write is not a validation failure: the server's own sentence is shown where the operator is
    /// and nothing moves (`TeamWizard.tsx:217`-`:222` leaves `currentStep` alone).
    func testARefusedWriteKeepsTheWizardOpenInTheServersOwnSentence() async throws {
        let rig = try rig()
        fillIn(rig.vm)
        rig.vm.step = .member
        rig.teams.saveReplies = [.failure(.business(code: -1, message: "Team name already exists"))]

        let result = await rig.vm.save()

        guard case let .failed(message) = result else { return XCTFail("expected the server's sentence, got \(result)") }
        XCTAssertEqual(message, "Team name already exists")
        XCTAssertEqual(message, ErrorMessage.text(for: APIError.business(code: -1, message: "Team name already exists")))
        XCTAssertEqual(rig.vm.step, .member, "a refused write is not a validation failure; nothing moves")
    }

    /// A double tap reaches the model before the button redraws, so the guard is the model's job
    /// (`TeamWizard.tsx:173`-`:174`, `if (submitting) return`).
    func testASecondSaveWhileOneIsInFlightSendsNothingTwice() async throws {
        let writer = ParkingTeamWriter()
        let rig = try rig(writer: writer)
        fillIn(rig.vm)

        writer.gate = true
        let first = Task { await rig.vm.save() }
        try await waitUntil { writer.calls == 1 }
        XCTAssertTrue(rig.vm.isSaving)
        let second = Task { await rig.vm.save() }
        try await settle()
        XCTAssertEqual(writer.calls, 1, "the same team cannot be created twice by one tap")

        writer.release()
        _ = await first.value
        _ = await second.value
        XCTAssertFalse(rig.vm.isSaving, "and the window closes for the next attempt")
    }

    // MARK: - plumbing

    private struct Rig {
        let vm: TeamFormViewModel
        let teams: FakeTeams
        let models: FakeModelCatalog
        let skills: FakeSkills
        let agents: FakeAgents
    }

    private func rig(
        mode: TeamFormViewModel.Mode = .create,
        account: AccountSnapshot? = AccountSnapshot(username: "admin", isAdministrator: true),
        writer: (any TeamWriting)? = nil
    ) throws -> Rig {
        let teams = FakeTeams()
        let models = FakeModelCatalog()
        let skills = FakeSkills()
        let agents = FakeAgents()
        let vm = TeamFormViewModel(
            mode: mode,
            account: account,
            teams: teams,
            writer: writer ?? teams,
            models: models,
            skills: skills,
            agents: agents
        )
        return Rig(vm: vm, teams: teams, models: models, skills: skills, agents: agents)
    }

    /// Step 1's four fields, filled: enough for `validateBasics()` to pass.
    private func fillInBasics(_ vm: TeamFormViewModel) {
        vm.name = "报表团队"
        vm.detail = "抓取并汇总"
        vm.systemPrompt = "你负责拆解目标"
        vm.modelID = 1
    }

    /// A whole wizard that would pass `validateAll()`: basics filled, no skill bound, one member picked.
    private func fillIn(_ vm: TeamFormViewModel) {
        fillInBasics(vm)
        if vm.memberRows.isEmpty { vm.addMemberRow() }
        vm.pickMember(HXEntityPickerOption(id: 11, title: "研究员"), into: vm.memberRows[0].id)
    }

    /// `TeamResponse.kt` gives `systemPrompt`, `modelId`, `skillList` and `memberList` non-null defaults, and
    /// every member/skill item carries a non-null name and availability flag, so the fixtures spell them out.
    /// A row that names no availability means the healthy case: the flag is always on the wire, so "the fixture
    /// did not care" and "the key is missing from the reply" cannot be the same thing here.
    private func team(
        id: Int64?,
        name: String,
        extra: [String: Any] = [:],
        skills: [[String: Any]] = [],
        members: [[String: Any]] = []
    ) throws -> TeamSummary {
        var row: [String: Any] = [
            "name": name,
            "systemPrompt": "",
            "modelId": 0,
            "skillList": skills.map { withFlag("skillAvailable", in: $0) },
            "memberList": members.map { withFlag("agentAvailable", in: $0) },
        ]
        if let id { row["id"] = id }
        for (key, value) in extra { row[key] = value }
        return try TeamSummary.stub(row)
    }

    private func withFlag(_ key: String, in row: [String: Any]) -> [String: Any] {
        guard row[key] == nil else { return row }
        var filled = row
        filled[key] = true
        return filled
    }

    private func seed(_ rig: Rig, models rows: [[String: Any]]) throws {
        rig.models.choiceReplies = [.success(try PageStub.page(ModelSummary.self, rows))]
    }

    private func seed(_ rig: Rig, sources rows: [[String: Any]]) throws {
        rig.skills.sourceReplies = [.success(try sources(rows))]
    }

    private func seed(_ rig: Rig, skills row: [String: Any]) throws {
        var counted: [String: Any] = ["boundAgentCount": 0, "boundTeamCount": 0]
        for (key, value) in row { counted[key] = value }
        rig.skills.skillPageReplies = [.success(try PageStub.page(SkillItem.self, [counted]))]
    }

    /// Two queues: the seed page answers the first call, the search page the next.
    private func seed(_ rig: Rig, agents first: [[String: Any]], _ second: [[String: Any]]? = nil) throws {
        var replies = [try PageStub.page(AgentSummary.self, first)]
        if let second { replies.append(try PageStub.page(AgentSummary.self, second)) }
        rig.agents.replies = replies.map { .success($0) }
    }

    /// `SkillSourceSummary` has ten non-optional columns; a fixture about one repository still has to carry the
    /// other nine.
    private func sources(_ rows: [[String: Any]]) throws -> Page<SkillSourceSummary> {
        let columns: [String: Any] = [
            "sourceType": "GIT",
            "version": "1.0.0",
            "url": "https://example.com/skills.git",
            "branch": "main",
            "description": "示例来源",
            "status": 1,
            "isPublic": 1,
            "creator": "heqingsong",
            "createTime": "2026-09-01 10:00:00",
            "updateTime": "2026-09-01 10:00:00",
        ]
        let answered = rows.map { row -> [String: Any] in
            var merged = columns
            for (key, value) in row { merged[key] = value }
            return merged
        }
        return try PageStub.page(SkillSourceSummary.self, answered)
    }

    /// The debounce belongs to the view (`MembersField.tsx:41`-`:50`), so a test drives the model's own call and
    /// waits for the reply it consumed.
    private func search(_ rig: Rig, for keyword: String) async throws {
        await rig.vm.searchMembers(keyword)
    }

    private func json<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(50))
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0 ..< 400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

/// A team writer that holds a save open, so the window between the tap and the redraw can be looked at.
private final class ParkingTeamWriter: TeamWriting, @unchecked Sendable {
    var gate = false
    private(set) var calls = 0
    var reply: Result<EmptyResponse, APIError> = .success(EmptyResponse())
    private var parked: [() -> Void] = []

    func createTeam(_ draft: TeamSaveDraft) async -> Result<EmptyResponse, APIError> {
        await park()
    }

    func updateTeam(id: Int64, _ draft: TeamSaveDraft) async -> Result<EmptyResponse, APIError> {
        await park()
    }

    private func park() async -> Result<EmptyResponse, APIError> {
        calls += 1
        guard gate else { return reply }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.reply) }
        }
    }

    func release() {
        let waiting = parked
        parked = []
        waiting.forEach { $0() }
    }
}

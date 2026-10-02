import XCTest
import SwiftUI
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The five-step agent wizard's behaviour, one rule at a time.
///
/// Four things hold this screen together and each is asserted rather than left to the layout. Steps 2–5 are
/// optional, so a step may be walked past but never walked *into* with the previous one broken, and its
/// candidates are read only when the operator gets there (`CreateForm.tsx:81`-`:127`). The three parameter
/// tables are *not* symmetric: a tool's declared default does not count as filled while an MCP's and a CLI's
/// do (`configValidation.ts:120`, `:130`, `:156`). Rows are slots rather than bindings, so an empty row is
/// dropped from the body while a half-filled one is refused. And the body has to keep the server's null
/// semantics — a group the operator never touched is left off, a group they emptied is sent empty
/// (`AgentServiceImpl.kt:185`-`:197`).
@MainActor
final class AgentFormWizardTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HarnaxCatalog.shared.language = .en
    }

    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }

    // MARK: - the walk, and who reads when

    /// Opening the wizard is a read-free act: the row an edit came in on already carries every stored binding,
    /// and the five candidate lists belong to the endpoints of five different domains.
    func testOpeningTheWizardSendsNoReadAtAll() throws {
        let rig = try rig()
        XCTAssertTrue(rig.models.choiceRequests.isEmpty)
        XCTAssertEqual(rig.tools.availableCalls, 0)
        XCTAssertTrue(rig.mcp.pageRequests.isEmpty)
        XCTAssertTrue(rig.skills.sourceRequests.isEmpty)
        XCTAssertTrue(rig.clis.requests.isEmpty)
        XCTAssertEqual(rig.vm.step, .basic)
    }

    func testEnteringAStepReadsOnlyThatStepsCandidates() async throws {
        let rig = try rig()
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])

        await rig.vm.load(.basic)

        XCTAssertEqual(rig.models.choiceRequests.count, 1)
        XCTAssertEqual(rig.tools.availableCalls, 0, "step 1 has no business asking the tool domain")
        XCTAssertTrue(rig.mcp.pageRequests.isEmpty)
        XCTAssertTrue(rig.skills.sourceRequests.isEmpty)
        XCTAssertTrue(rig.clis.requests.isEmpty)
    }

    /// The console asks for a page of 100 per list; that is what truncates the candidate set, and iOS does not
    /// get to pretend otherwise by asking for something else.
    func testEachReadIsThePagesTheConsoleAsksFor() async throws {
        let rig = try rig()

        await rig.vm.load(.basic)
        XCTAssertEqual(rig.models.choiceRequests.last?.num, 1)
        XCTAssertEqual(rig.models.choiceRequests.last?.size, 100)
        await rig.vm.load(.mcp)
        XCTAssertEqual(rig.mcp.pageRequests.last?.status, 1, "a stopped server cannot be bound")
        XCTAssertEqual(rig.mcp.pageRequests.last?.size, 100)
        await rig.vm.load(.skill)
        XCTAssertEqual(rig.skills.sourceRequests.last?.status, 1)
        await rig.vm.load(.cli)
        XCTAssertEqual(rig.clis.filters.last?.status, 1)
    }

    /// Walking back and forth is a single read per step; `reload` is the only way to ask again.
    func testAStepIsReadOnceUntilItIsReloaded() async throws {
        let rig = try rig()

        await rig.vm.load(.tool)
        await rig.vm.load(.tool)
        XCTAssertEqual(rig.tools.availableCalls, 1)

        await rig.vm.reload(.tool)
        XCTAssertEqual(rig.tools.availableCalls, 2, "the retry affordance has to actually retry")
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

    /// The console filters the model page down to `status===1 && modelType==='chat'` after fetching it
    /// (`CreateForm.tsx:101`-`:107`); the tool list arrives already filtered and must not be filtered twice.
    func testOnlyChatAndEnabledModelsAreOffered() async throws {
        let rig = try rig()
        try seed(
            rig,
            models: [
                ["id": 1, "name": "对话", "status": 1, "modelType": "chat"],
                ["id": 2, "name": "向量", "status": 1, "modelType": "embedding"],
                ["id": 3, "name": "停用的", "status": 0, "modelType": "chat"],
            ]
        )

        await rig.vm.load(.basic)

        XCTAssertEqual(rig.vm.modelOptions.map(\.id), [1])
    }

    func testTheToolListIsTakenAsItArrives() async throws {
        let rig = try rig()
        try seed(rig, tools: [
            ["id": 11, "name": "fetch_url", "needConfirm": 1],
            ["id": 12, "name": "read_file", "readOnly": 1],
        ])

        await rig.vm.load(.tool)

        XCTAssertEqual(rig.vm.toolOptions(excluding: []).map(\.id), [11, 12], "the server already dropped the mandatory tools")
    }

    /// Two keys make a repository platform-owned, and neither is offered to an agent (`SkillBindingResolver.kt:55`-`:60`).
    func testTheBuiltinSkillRepositoryIsNotOffered() async throws {
        let rig = try rig()
        try seed(rig, sources: [
            ["id": 7, "name": "团队技能源", "sourceType": "GIT"],
            ["id": 9, "name": "builtin-cli-skills", "sourceType": "BUILTIN_CLI"],
        ])

        await rig.vm.load(.skill)

        XCTAssertEqual(rig.vm.repositoryOptions.map(\.id), [7])
    }

    // MARK: - stepping

    func testTheFirstStepRefusesAndNamesAllFourFields() throws {
        let rig = try rig()
        let issues = rig.vm.next()
        XCTAssertEqual(issues.map(\.field), [.name, .detail, .prompt, .model])
        XCTAssertEqual(rig.vm.step, .basic, "a refused step stays on screen")
        XCTAssertTrue(issues.allSatisfy { !$0.message.isEmpty })
    }

    func testTheFirstStepCannotBeSkipped() throws {
        let rig = try rig()
        rig.vm.skip()
        XCTAssertEqual(rig.vm.step, .basic)
    }

    func testASkippableStepSkipsPastWhatItWouldRefuse() async throws {
        let rig = try rig()
        rig.vm.step = .tool
        try seed(rig, tools: [["id": 11, "name": "fetch_url", "envParams": [["envParamName": "API_KEY", "required": true]]]])
        await rig.vm.load(.tool)
        rig.vm.addToolRow()
        // Values without a tool: the one half-made state the console refuses (`configValidation.ts:114`-`:118`),
        // and the one a pick cannot produce because picking fills the hole.
        rig.vm.toolRows[0].env = [HXEnvBindingRow(entry: EnvParamEntry(envParamName: "API_KEY"))]
        XCTAssertFalse(rig.vm.validate(.tool).isEmpty)

        XCTAssertEqual(rig.vm.next().map(\.field), [.tool])
        XCTAssertEqual(rig.vm.step, .tool, "下一步 validates")

        rig.vm.skip()
        XCTAssertEqual(rig.vm.step, .mcp, "跳过 is the way out of a step that will not validate")
    }

    func testTheLastStepStillRefusesABrokenBasic() throws {
        let rig = try rig()
        rig.vm.step = .cli
        let issues = rig.vm.next()
        XCTAssertEqual(issues.map(\.step), [.basic, .basic, .basic, .basic])
        XCTAssertEqual(rig.vm.step, .cli, "there is nowhere further to go, so the wizard stays and says why")
    }

    func testBackFromTheFirstStepDoesNothing() throws {
        let rig = try rig()
        rig.vm.back()
        XCTAssertEqual(rig.vm.step, .basic)
    }

    // MARK: - where a refusal stays

    /// `AgentFormView`'s own `issues` state was wiped by `move(to:)`, so the sentence that explained why a
    /// save had jumped back was gone the moment the operator tapped anything. The view model now keeps each
    /// step's refusal until that step is validated again.
    func testASaveRefusalStaysOnTheStepItBelongsTo() async throws {
        let rig = try rig()
        try seed(rig, mcps: [
            ["id": 21, "name": "报表服务", "envParams": [["envParamName": "TOKEN", "required": true]]],
        ])
        await rig.vm.load(.mcp)
        rig.vm.detail = "抓取并汇总"
        rig.vm.systemPrompt = "你负责抓取"
        rig.vm.modelID = 1
        rig.vm.step = .cli
        rig.vm.addMcpRow()
        rig.vm.pickMCP(HXEntityPickerOption(id: 21, title: "报表服务"), into: rig.vm.mcpRows[0].id)

        let result = await rig.vm.save()
        guard case let .invalid(issues, step) = result else { return XCTFail("expected a refusal, got \(result)") }
        XCTAssertEqual(step, .basic, "the missing name is the first thing the whole-form check found")
        XCTAssertEqual(issues.count, 2)
        XCTAssertEqual(rig.vm.issues(for: .basic).map(\.field), [.name], "step 1 has to be able to say what it refused")
        XCTAssertEqual(rig.vm.issues(for: .mcp).map(\.field), [.mcp], "the other half belongs to step 3")

        rig.vm.step = .mcp
        XCTAssertEqual(rig.vm.issues(for: .basic).map(\.field), [.name], "walking away is not a re-validation")
        XCTAssertEqual(rig.vm.issues(for: .mcp).count, 1, "and the step walked into keeps its own message")
    }

    /// The recording is per step, so a chip never reads as finished while its own refusal still stands
    /// (`AgentFormView.chipTone`).
    func testARefusedNextIsRememberedForItsOwnStep() throws {
        let rig = try rig()

        XCTAssertEqual(rig.vm.next().count, 4)
        XCTAssertEqual(rig.vm.issues(for: .basic).count, 4)

        rig.vm.step = .tool
        XCTAssertEqual(
            rig.vm.issues(for: .basic).count,
            4,
            "step 1 still has four things wrong with it; painting it as a finished step is the bug this fixes"
        )
    }

    /// Re-validation is what retires a refusal — the pass that finds the step clean replaces the old message,
    /// including on the step the operator lands on after `save()`.
    func testReValidatingAStepIsWhatRetiresItsOutstandingIssue() async throws {
        let rig = try rig()
        rig.vm.step = .cli

        _ = await rig.vm.save()

        XCTAssertEqual(rig.vm.issues(for: .basic).count, 4, "the save found all four of step 1's fields empty")
        fillIn(rig.vm)
        XCTAssertEqual(rig.vm.issues(for: .basic).count, 4, "typing into the fields is not yet a validation pass")

        rig.vm.step = .basic
        XCTAssertTrue(rig.vm.next().isEmpty)
        XCTAssertTrue(rig.vm.issues(for: .basic).isEmpty, "now it is, and the message has to go with it")
        XCTAssertEqual(rig.vm.step, .tool)
    }

    /// Only the steps that refused are marked: a red chip on a step that passed would send the operator to fix
    /// something that is not broken.
    func testASaveRefusalOnlyMarksTheStepsThatActuallyRefused() async throws {
        let rig = try rig()
        try seed(rig, mcps: [
            ["id": 21, "name": "报表服务", "envParams": [["envParamName": "TOKEN", "required": true]]],
        ])
        await rig.vm.load(.mcp)
        fillIn(rig.vm)
        rig.vm.step = .cli
        rig.vm.addMcpRow()
        rig.vm.pickMCP(HXEntityPickerOption(id: 21, title: "报表服务"), into: rig.vm.mcpRows[0].id)

        let result = await rig.vm.save()
        guard case let .invalid(_, step) = result else { return XCTFail("expected a refusal, got \(result)") }
        XCTAssertEqual(step, .mcp)
        XCTAssertEqual(rig.vm.step, .mcp, "the jump lands on the step that refused, not the last one")
        XCTAssertTrue(rig.vm.issues(for: .basic).isEmpty, "step 1 passed")
        XCTAssertEqual(rig.vm.issues(for: .mcp).map(\.field), [.mcp])
        XCTAssertTrue(rig.vm.issues(for: .cli).isEmpty)
    }

    /// 跳过 never validates, so the refusal it walks past stays standing — the save will meet it again.
    func testSkippingAStepLeavesItsRefusalStanding() async throws {
        let rig = try rig()
        try seed(rig, tools: [["id": 11, "name": "fetch_url"]])
        await rig.vm.load(.tool)
        rig.vm.step = .tool
        rig.vm.addToolRow()
        rig.vm.toolRows[0].env = [HXEnvBindingRow(entry: EnvParamEntry(envParamName: "API_KEY"))]

        XCTAssertEqual(rig.vm.next().map(\.field), [.tool])
        rig.vm.skip()

        XCTAssertEqual(rig.vm.step, .mcp)
        XCTAssertEqual(rig.vm.issues(for: .tool).map(\.field), [.tool], "a skipped step is an unvalidated step")
    }

    // MARK: - step 1

    /// The owner column is written by the backend from the calling account; the form shows it and never sends
    /// it (`AgentServiceImpl.kt:124`).
    func testTheOwnerLineNamesTheAccountAndNeverReachesTheBody() throws {
        let rig = try rig(account: AccountSnapshot(username: "liufang", nickname: "刘芳"))
        let draft = rig.vm.buildDraft()
        XCTAssertEqual(rig.vm.ownerName, "刘芳")
        XCTAssertNil(draft.owner, "an owner the server derives is not a field to submit")
    }

    func testAnEditShowsTheRowsCreator() throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "creator": "liufang", "isPublic": 0])
        let rig = try rig(mode: .edit(row))
        XCTAssertEqual(rig.vm.ownerName, "liufang")
    }

    /// Create carries `status: 1`; the update request has no such field at all.
    func testCreateCarriesAStatusAndEditCarriesNone() throws {
        let created = try rig()
        XCTAssertEqual(created.vm.buildDraft().status, 1)

        let row = try AgentSummary.stub(["id": 3, "name": "报表", "status": 0])
        let edited = try rig(mode: .edit(row))
        XCTAssertNil(edited.vm.buildDraft().status, "an edit must not switch a stopped agent back on")
    }

    /// A public row cannot be made private by anyone but an administrator, and a foreign row cannot be
    /// touched at all (`permissionUtil.ts:57`-`:75`).
    func testTheVisibilitySwitchRefusesAnAccountThatMayNotChangeIt() throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "creator": "someoneelse", "isPublic": 1])
        let rig = try rig(
            mode: .edit(row),
            account: AccountSnapshot(username: "liufang")
        )
        XCTAssertFalse(rig.vm.canChangeVisibility)
        XCTAssertTrue(rig.vm.isPublic, "the row was public")

        rig.vm.setIsPublic(false)
        XCTAssertTrue(rig.vm.isPublic, "a refused switch must not even change the draft")
    }

    func testAnAdministratorMayPutAPublicRowBackBehindTheWall() throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "creator": "someoneelse", "isPublic": 1])
        let rig = try rig(
            mode: .edit(row),
            account: AccountSnapshot(username: "admin", isAdministrator: true)
        )
        XCTAssertTrue(rig.vm.canChangeVisibility)
        rig.vm.setIsPublic(false)
        XCTAssertFalse(rig.vm.isPublic)
    }

    /// The list page answers 100 models and an edit can hold one that fell out of that page. The id stays,
    /// the name comes from the row, and no capability is claimed about a model this build cannot see.
    func testAModelThatFellOutOfThePageKeepsItsStoredName() async throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "modelId": 88, "modelName": "gpt-4"])
        let rig = try rig(mode: .edit(row))
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat", "supportTool": 0, "supportMcp": 0]])
        await rig.vm.load(.basic)

        XCTAssertNil(rig.vm.selectedModel)
        XCTAssertEqual(rig.vm.modelName, "gpt-4")
        XCTAssertTrue(rig.vm.supportsTools, "no evidence either way is not evidence of absence")
        XCTAssertTrue(rig.vm.supportsMCP)
    }

    func testASelectedModelWithoutToolSupportLocksStepTwo() async throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "modelId": 1])
        let rig = try rig(mode: .edit(row))
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat", "supportTool": 0, "supportMcp": 1]])
        await rig.vm.load(.basic)

        XCTAssertFalse(rig.vm.supportsTools)
        XCTAssertTrue(rig.vm.supportsMCP)
    }

    /// The web's model `Select` is `allowClear` in both forms (`CreateForm.tsx:249`,
    /// `UpdateForm.tsx:354`; `specs/01-agent-team.md:73`), so taking the model off the row is a legal act the
    /// operator must be able to undo their way into. It is only worth having because the field is required:
    /// a cleared id has to send step 1 back to refusing, rather than leave a stale pick on screen.
    func testClearingTheModelSendsStepOneBackToRefusingIt() throws {
        let rig = try rig()
        fillIn(rig.vm)
        XCTAssertTrue(rig.vm.validate(.basic).isEmpty, "four fields and a model: step 1 lets this through")

        rig.vm.clearModel()

        XCTAssertNil(rig.vm.modelID)
        XCTAssertEqual(rig.vm.validate(.basic).map(\.field), [.model], "a row with no model cannot be saved")
        XCTAssertNil(rig.vm.buildDraft().modelId, "and the cleared id must not ride along into the body")
    }

    /// 注意点 12 (`specs/01-agent-team.md:338`) forbids hiding a reference that has gone stale: the operator
    /// has to be able to see what the row currently holds. `AgentFormViewModel.modelOptions` rendered only
    /// the candidate page, so the retained model vanished from the sheet entirely.
    func testAStaleModelReferenceIsStillListedInThePicker() async throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "modelId": 88, "modelName": "gpt-4"])
        let rig = try rig(mode: .edit(row))
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])
        await rig.vm.load(.basic)

        XCTAssertEqual(rig.vm.modelOptions.map(\.id), [88, 1], "the web puts the retained row on top (`TeamWizard.tsx:60`-`:66`)")
    }

    /// The degraded style is the whole point of keeping the row listed: 名称 plus the web's own sentence
    /// 「该模型已停用、已删除或不再是对话模型」 (`harnax-webui/src/locales/zh-CN/pages.ts:1094`, the shape
    /// `TeamWizard.tsx:49`-`:68`). A bare name would read as a live candidate.
    func testAStaleModelRowCarriesTheWebDegradationSuffix() async throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "modelId": 88, "modelName": "gpt-4"])
        let rig = try rig(mode: .edit(row))
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])
        await rig.vm.load(.basic)

        XCTAssertTrue(rig.vm.isModelStale)
        XCTAssertTrue(rig.vm.modelDisplayName.hasPrefix("gpt-4"), "\(rig.vm.modelDisplayName)")
        XCTAssertTrue(
            rig.vm.modelDisplayName.contains(hx("agent.wizard.model.unavailable")),
            "without the suffix the row is indistinguishable from a model that still exists"
        )
        let option = try XCTUnwrap(rig.vm.modelOptions.first { $0.id == 88 })
        XCTAssertEqual(option.title, rig.vm.modelDisplayName, "the sheet says what the line says")
    }

    /// A model that resolves in the page is not stale, and the label stays the plain name — the suffix is a
    /// claim about the server's data and only the arrived page entitles the wizard to make it.
    func testALiveModelRowIsNeverMarkedStale() async throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "modelId": 1, "modelName": "qwen-max"])
        let rig = try rig(mode: .edit(row))
        try seed(rig, models: [["id": 1, "name": "qwen-max", "title": "通义千问", "status": 1, "modelType": "chat"]])
        await rig.vm.load(.basic)

        XCTAssertFalse(rig.vm.isModelStale)
        XCTAssertFalse(rig.vm.modelDisplayName.contains(hx("agent.wizard.model.unavailable")))
        XCTAssertEqual(rig.vm.modelOptions.count, 1, "a resolvable reference is listed once, not twice")
    }

    /// Before the page arrives nothing is known: an edit that has not opened step 1 yet must not be told its
    /// model is gone.
    func testAStoredModelIsNotCalledStaleBeforeItsPageHasArrived() throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "modelId": 88, "modelName": "gpt-4"])
        let rig = try rig(mode: .edit(row))

        XCTAssertFalse(rig.vm.isModelStale, "no read has happened, so no absence has been observed")
        XCTAssertTrue(rig.vm.modelOptions.isEmpty)
        XCTAssertFalse(rig.vm.modelDisplayName.contains(hx("agent.wizard.model.unavailable")))
    }

    /// The web marks its retained row `disabled` (`TeamWizard.tsx:65`); iOS keeps it selectable, because an
    /// operator who came to look at what the agent holds must be able to leave it exactly as it was.
    func testTheRetainedModelRowCanBePickedBack() async throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表", "modelId": 88, "modelName": "gpt-4"])
        let rig = try rig(mode: .edit(row))
        try seed(rig, models: [["id": 1, "name": "qwen-max", "status": 1, "modelType": "chat"]])
        await rig.vm.load(.basic)

        let retained = try XCTUnwrap(rig.vm.modelOptions.first { $0.id == 88 })
        rig.vm.pickModel(retained)
        XCTAssertEqual(rig.vm.modelID, 88, "the retained row is a choice the wizard can hand back untouched")
    }

    /// `AgentCreateRequest.kt:13`-`:16` caps the name at 100 UTF-16 code units, which grapheme counting
    /// understates for anything outside the basic plane.
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

    func testTheDraftTrimsTheNameAndDescriptionButNotThePrompt() throws {
        let rig = try rig()
        rig.vm.name = "  报表助手  "
        rig.vm.detail = "  抓取并汇总  "
        rig.vm.systemPrompt = "  你负责抓取  \n"
        let draft = rig.vm.buildDraft()
        XCTAssertEqual(draft.name, "报表助手")
        XCTAssertEqual(draft.description, "抓取并汇总")
        XCTAssertEqual(draft.systemPrompt, "  你负责抓取  \n", "the prompt goes out as written, markdown and all")
    }

    // MARK: - rows are slots

    /// An added row that was never filled in is not a binding; it must not be sent, and must not be refused.
    func testAnEmptyRowIsNeitherSentNorRefused() async throws {
        let rig = try rig()
        try seed(rig, tools: [["id": 11, "name": "fetch_url"]])
        await rig.vm.load(.tool)
        rig.vm.addToolRow()

        XCTAssertTrue(rig.vm.validate(.tool).isEmpty)
        XCTAssertTrue(rig.vm.buildDraft().tools?.isEmpty ?? false, "the body carries no half-made row")
    }

    func testPickingAToolTakesItsDeclarationsItsDefaultAndItsLock() async throws {
        let rig = try rig()
        try seed(rig, tools: [[
            "id": 11,
            "name": "fetch_url",
            "needConfirm": 1,
            "envParams": [
                ["envParamName": "API_KEY", "required": true, "secret": true],
                ["envParamName": "TIMEOUT", "required": false, "defaultValue": "30"],
            ],
        ]])
        await rig.vm.load(.tool)
        rig.vm.addToolRow()
        let rowID = rig.vm.toolRows[0].id
        rig.vm.pickTool(HXEntityPickerOption(id: 11, title: "抓取网页"), into: rowID)

        let row = try XCTUnwrap(rig.vm.toolRows.first)
        XCTAssertEqual(row.shownName, "抓取网页")
        XCTAssertTrue(row.needConfirm, "the package declares confirmation, so the row starts on")
        XCTAssertTrue(rig.vm.confirmIsLocked(for: rowID))
        rig.vm.setToolConfirm(false, of: rowID)
        XCTAssertTrue(rig.vm.toolRows[0].needConfirm, "the switch may be tightened but never loosened")

        XCTAssertEqual(row.env.map(\.envKey), ["API_KEY", "TIMEOUT"])
        XCTAssertEqual(row.env[0].customValue, "", "a secret parameter never takes the package default onto the screen")
        XCTAssertEqual(row.env[1].customValue, "30")
        XCTAssertEqual(row.env[1].source, .custom)
    }

    /// The mirror rule: an MCP pick never backfills, because the frozen value would outlive the server's own
    /// default (`McpConfigPanel.tsx:43`-`:48`).
    func testAnMCPPickLeavesEveryParameterBlank() async throws {
        let rig = try rig()
        try seed(rig, mcps: [
            ["id": 21, "name": "报表服务", "envParams": [["envParamName": "TOKEN", "required": true, "defaultValue": "abc"]]],
        ])
        await rig.vm.load(.mcp)
        rig.vm.addMcpRow()
        let rowID = rig.vm.mcpRows[0].id
        rig.vm.pickMCP(HXEntityPickerOption(id: 21, title: "报表服务"), into: rowID)

        XCTAssertEqual(rig.vm.mcpRows[0].env[0].customValue, "")
        XCTAssertEqual(rig.vm.mcpRows[0].env[0].source, .reference)
        XCTAssertTrue(rig.vm.validate(.mcp).isEmpty, "an unfilled parameter the server will default is not a refusal")
    }

    func testTheLastMCPRowCannotBeDeletedButAToolRowCan() async throws {
        let rig = try rig()
        await rig.vm.load(.tool)
        await rig.vm.load(.mcp)
        XCTAssertTrue(rig.vm.mcpRows.isEmpty, "step 3 seeds nothing until the operator arrives")

        rig.vm.addMcpRow()
        let rowID = rig.vm.mcpRows[0].id
        XCTAssertFalse(rig.vm.canRemoveMcpRow, "one row has to stay so the step always has a place to pick into")
        rig.vm.removeMcpRow(rowID)
        XCTAssertEqual(rig.vm.mcpRows.count, 1)

        rig.vm.addMcpRow()
        XCTAssertTrue(rig.vm.canRemoveMcpRow)
        rig.vm.removeMcpRow(rowID)
        XCTAssertEqual(rig.vm.mcpRows.count, 1)

        XCTAssertTrue(rig.vm.toolRows.isEmpty, "a create seeds no rows of any kind")
        rig.vm.addToolRow()
        rig.vm.removeToolRow(rig.vm.toolRows[0].id)
        XCTAssertTrue(rig.vm.toolRows.isEmpty, "tools are the dimension that can go down to none")
    }

    /// The way out of a lone MCP row is the web's `allowClear` on the server picker
    /// (`McpConfigPanel.tsx:107`), not the trash it also refuses to render (`:98`-`:102`). A row that holds a
    /// server whose required parameter is still blank is refused, and with one row left the trash is not
    /// offered either — so the refusal would have no escape at all if the pick could not be dropped.
    func testClearingTheLoneMCPPickEmptiesTheRowAndStopsTheRefusal() async throws {
        let rig = try rig()
        try seed(rig, mcps: [
            ["id": 21, "name": "报表服务", "envParams": [["envParamName": "TOKEN", "required": true]]],
        ])
        await rig.vm.load(.mcp)
        rig.vm.addMcpRow()
        let rowID = rig.vm.mcpRows[0].id
        rig.vm.pickMCP(HXEntityPickerOption(id: 21, title: "报表服务"), into: rowID)
        XCTAssertFalse(rig.vm.validate(.mcp).isEmpty)

        rig.vm.clearMCP(into: rowID)

        XCTAssertTrue(rig.vm.mcpRows[0].isEmpty)
        XCTAssertNil(rig.vm.mcpRows[0].mcpID)
        XCTAssertNil(rig.vm.mcpRows[0].shownName)
        XCTAssertTrue(rig.vm.validate(.mcp).isEmpty, "an empty row is a slot, not a half-made binding")
        XCTAssertTrue(rig.vm.buildDraft().mcps?.isEmpty ?? false, "and nothing about it reaches the body")
    }

    /// Clearing zeroes the parameter table with the pick. The web leaves `envEntries` behind after a clear
    /// (`McpConfigPanel.tsx:107` only drops `mcpId`), which strands a table whose typed values belong to no
    /// server; iOS drops them, matching `pickRepository(nil)` for skills (`AgentFormViewModel.swift:743`-`:748`).
    func testClearingAnMCPPickDropsTheParametersItBroughtIn() async throws {
        let rig = try rig()
        try seed(rig, mcps: [
            ["id": 21, "name": "报表服务", "envParams": [["envParamName": "TOKEN", "required": true]]],
            ["id": 22, "name": "搜索服务", "envParams": [["envParamName": "KEY", "required": true]]],
        ])
        await rig.vm.load(.mcp)
        rig.vm.addMcpRow()
        let rowID = rig.vm.mcpRows[0].id
        rig.vm.pickMCP(HXEntityPickerOption(id: 21, title: "报表服务"), into: rowID)
        try fill(rig.vm.mcpEnvBinding(for: rowID), at: 0, with: "typed-for-21")

        rig.vm.clearMCP(into: rowID)

        XCTAssertTrue(rig.vm.mcpRows[0].env.isEmpty, "a value held for a server nobody chose cannot be shown again")
        // Re-picking the same server starts from its declarations, not from what the clear threw away.
        rig.vm.pickMCP(HXEntityPickerOption(id: 21, title: "报表服务"), into: rowID)
        XCTAssertEqual(rig.vm.mcpRows[0].env.map(\.envKey), ["TOKEN"])
        XCTAssertEqual(rig.vm.mcpRows[0].env[0].customValue, "", "the row comes back blank rather than half-filled")
    }

    /// The refused delete stays refused: the step must always keep a place to pick into
    /// (`McpConfigPanel.tsx:64`-`:69`), which is exactly why a clear is the affordance to add rather than a
    /// way to delete.
    func testTheLoneMCPRowStaysUndeletableSoAClearIsTheWayOut() async throws {
        let rig = try rig()
        await rig.vm.load(.mcp)
        rig.vm.addMcpRow()
        let rowID = rig.vm.mcpRows[0].id

        rig.vm.clearMCP(into: rowID)

        XCTAssertEqual(rig.vm.mcpRows.count, 1, "the slot survives its own clear")
        XCTAssertEqual(rig.vm.mcpRows[0].id, rowID, "the same identity, so the screen's binding still addresses it")
        XCTAssertFalse(rig.vm.canRemoveMcpRow, "clearing does not unlock the delete")
    }

    /// Skill rows are cleared rather than deleted only when one is left, so the last one keeps its slot
    /// (`SkillConfigPanel.tsx:76`-`:84`).
    func testTheLastSkillRowIsClearedInPlace() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源", "sourceType": "GIT"]])
        await rig.vm.load(.skill)
        rig.vm.addSkillRow()
        let rowID = rig.vm.skillRows[0].id
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rowID)
        XCTAssertEqual(rig.vm.skillRows[0].repositoryID, 7)

        rig.vm.removeSkillRow(rowID)
        XCTAssertEqual(rig.vm.skillRows.count, 1)
        XCTAssertEqual(rig.vm.skillRows[0].id, rowID, "the same slot, emptied")
        XCTAssertNil(rig.vm.skillRows[0].repositoryID)

        rig.vm.addSkillRow()
        let secondID = rig.vm.skillRows[1].id
        rig.vm.removeSkillRow(secondID)
        XCTAssertEqual(rig.vm.skillRows.count, 1, "a second row makes the delete a real delete")
        XCTAssertEqual(rig.vm.skillRows[0].id, rowID, "the row that was cleared is the one still there")
    }

    func testClearingTheRepositoryZeroesTheRow() async throws {
        let rig = try rig()
        try seed(rig, sources: [["id": 7, "name": "团队技能源", "sourceType": "GIT"]])
        await rig.vm.load(.skill)
        rig.vm.addSkillRow()
        let rowID = rig.vm.skillRows[0].id
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rowID)

        await rig.vm.pickRepository(nil, into: rowID)
        XCTAssertTrue(rig.vm.skillRows[0].isEmpty)
    }

    /// One read per repository, however many rows point at it.
    func testARepositoryIsReadOnceAcrossRows() async throws {
        let rig = try rig()
        try seed(rig, sources: [
            ["id": 7, "name": "团队技能源", "sourceType": "GIT"],
            ["id": 8, "name": "外部技能源", "sourceType": "GIT"],
        ])
        try seed(rig, skills: ["id": 51, "name": "PDF", "boundAgentCount": 0, "boundTeamCount": 0])
        await rig.vm.load(.skill)
        rig.vm.addSkillRow()
        rig.vm.addSkillRow()
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[0].id)
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[1].id)

        XCTAssertEqual(rig.skills.skillRequests.filter { $0.repositoryID == 7 }.count, 1)
    }

    func testASkillOptionListIsScopedToTheRowsOwnRepository() async throws {
        let rig = try rig()
        try seed(rig, sources: [
            ["id": 7, "name": "团队技能源", "sourceType": "GIT"],
            ["id": 8, "name": "外部技能源", "sourceType": "GIT"],
        ])
        try seed(rig, skills: ["id": 51, "name": "PDF", "boundAgentCount": 0, "boundTeamCount": 0])
        await rig.vm.load(.skill)
        rig.vm.addSkillRow()
        await rig.vm.pickRepository(HXEntityPickerOption(id: 7, title: "团队技能源"), into: rig.vm.skillRows[0].id)

        XCTAssertEqual(rig.vm.skillOptions(in: 7, excluding: []).map(\.id), [51])
        XCTAssertEqual(rig.vm.skillOptions(in: 8, excluding: []).count, 0)
        XCTAssertEqual(rig.vm.skillOptions(in: nil, excluding: []).count, 0)
    }

    // MARK: - CLI cards

    func testAddingCardsTakesOnePackageAtATimeAndStopsWhenEveryOneHasACard() async throws {
        let rig = try rig()
        try seed(rig, clis: [
            ["id": 31, "name": "harnax-cli", "version": "1.2.0"],
            ["id": 32, "name": "curl", "version": "8.0"],
        ])
        await rig.vm.load(.cli)

        XCTAssertTrue(rig.vm.canAddCLI)
        rig.vm.addCLI()
        rig.vm.addCLI()
        XCTAssertEqual(rig.vm.selectedCLIIDs, [31, 32])
        XCTAssertFalse(rig.vm.canAddCLI)
        rig.vm.addCLI()
        XCTAssertEqual(rig.vm.selectedCLIIDs.count, 2, "the button is off, and the call still cannot duplicate a card")
    }

    /// The values live in a dictionary keyed by package id, which is the whole reason delete-then-re-add
    /// restores them (`CliConfigPanel.tsx:11`).
    func testDeletingAndReAddingACardRestoresWhatWasTyped() async throws {
        let rig = try rig()
        try seed(rig, clis: [
            ["id": 31, "name": "harnax-cli", "envParams": [["envParamName": "TOKEN", "required": true]]],
            ["id": 32, "name": "curl"],
        ])
        await rig.vm.load(.cli)
        rig.vm.addCLI()
        let first = try XCTUnwrap(rig.vm.selectedCLIIDs.first)
        try fill(rig.vm.cliEnvBinding(for: first), at: 0, with: "typed-value")

        rig.vm.removeCLI(at: 0)
        XCTAssertTrue(rig.vm.selectedCLIIDs.isEmpty)
        rig.vm.addCLI()

        XCTAssertEqual(rig.vm.selectedCLIIDs.first, first)
        XCTAssertEqual(rig.vm.cliEnv[first]?.first?.customValue, "typed-value")
    }

    func testRePickingACardLeavesTheOthersValuesAlone() async throws {
        let rig = try rig()
        try seed(rig, clis: [
            ["id": 31, "name": "harnax-cli", "envParams": [["envParamName": "TOKEN", "required": true]]],
            ["id": 32, "name": "curl", "envParams": [["envParamName": "PROXY"]]],
        ])
        await rig.vm.load(.cli)
        rig.vm.addCLI()
        rig.vm.addCLI()
        try fill(rig.vm.cliEnvBinding(for: 31), at: 0, with: "typed-value")

        rig.vm.pickCLI(HXEntityPickerOption(id: 33, title: "jq"), at: 1)

        XCTAssertEqual(rig.vm.selectedCLIIDs, [31, 33])
        XCTAssertEqual(rig.vm.cliName(for: 33), "jq")
        XCTAssertEqual(rig.vm.cliEnv[31]?.first?.customValue, "typed-value")
    }

    func testAPackageThatShipsASkillNamesItOnTheCard() async throws {
        let rig = try rig()
        try seed(rig, clis: [["id": 31, "name": "harnax-cli", "skill": ["skillId": 44, "skillName": "harnax 技能"]]])
        await rig.vm.load(.cli)
        XCTAssertEqual(rig.vm.shippedSkillName(for: 31), "harnax 技能")
        XCTAssertNil(rig.vm.shippedSkillName(for: 99))
    }

    // MARK: - the three asymmetric required-parameter rules

    /// The asymmetry is only visible on a secret: a plain default gets copied into the value when the tool is
    /// picked (`ToolConfigPanel.tsx:60`-`:63` blanks the secret because the server's default is a mask
    /// (`AgentToolServiceImpl.maskValue`), so a filled-in non-secret row is already filled for the validator.
    func testAToolsRequiredParameterIsRefusedEvenWhenThePackageNamesADefault() async throws {
        let rig = try rig()
        try seed(rig, tools: [[
            "id": 11,
            "name": "fetch_url",
            "envParams": [["envParamName": "API_KEY", "required": true, "secret": true, "defaultValue": "sk-****"]],
        ]])
        await rig.vm.load(.tool)
        rig.vm.addToolRow()
        rig.vm.pickTool(HXEntityPickerOption(id: 11, title: "抓取网页"), into: rig.vm.toolRows[0].id)

        let issues = rig.vm.validate(.tool)
        XCTAssertEqual(issues.map(\.field), [.tool])
        XCTAssertTrue(issues[0].message.contains("API_KEY"), "\(issues[0].message)")
        XCTAssertTrue(issues[0].message.contains("抓取网页"), "the sentence has to say which row")
    }

    func testACLIsRequiredParameterIsRefusedAtTheLastStep() async throws {
        let rig = try rig()
        try seed(rig, clis: [["id": 31, "name": "harnax-cli", "envParams": [["envParamName": "TOKEN", "required": true]]]])
        await rig.vm.load(.cli)
        rig.vm.addCLI()
        fillIn(rig.vm)

        let issues = rig.vm.validate(.cli)
        XCTAssertEqual(issues.map(\.field), [.cli], "the console refuses this at 完成 (`configValidation.ts:150`-`:160`)")
        XCTAssertTrue(issues[0].message.contains("TOKEN"))
        XCTAssertTrue(issues[0].message.contains("harnax-cli"))
    }

    /// The reason the CLI table is checked at all: an unfilled required parameter with no default reaches the
    /// sandbox as nothing, so refusing on the last step is the only place the operator hears about it.
    func testARefusedCLIParameterHoldsTheSaveShut() async throws {
        let rig = try rig()
        try seed(rig, clis: [["id": 31, "name": "harnax-cli", "envParams": [["envParamName": "TOKEN", "required": true]]]])
        await rig.vm.load(.cli)
        rig.vm.addCLI()
        rig.vm.name = "报表"
        rig.vm.detail = "抓取"
        rig.vm.systemPrompt = "提示词"
        rig.vm.modelID = 1

        let result = await rig.vm.save()
        XCTAssertTrue(rig.agents.saveCalls.isEmpty, "a wizard that knows the body is short cannot send it")
        guard case let .invalid(issues, step) = result else {
            return XCTFail("expected the refusal, got \(result)")
        }
        XCTAssertEqual(step, .cli)
        XCTAssertEqual(issues.map(\.field), [.cli])
    }

    func testACLIsRequiredParameterWithADefaultIsAccepted() async throws {
        let rig = try rig()
        try seed(rig, clis: [[
            "id": 31,
            "name": "harnax-cli",
            "envParams": [["envParamName": "TOKEN", "required": true, "defaultValue": "abc"]],
        ]])
        await rig.vm.load(.cli)
        rig.vm.addCLI()
        fillIn(rig.vm)
        XCTAssertTrue(rig.vm.validate(.cli).isEmpty, "the package default really does reach the sandbox")
    }

    func testAnMCPSRequiredParameterWithoutADefaultIsRefused() async throws {
        let rig = try rig()
        try seed(rig, mcps: [
            ["id": 21, "name": "报表服务", "envParams": [["envParamName": "TOKEN", "required": true]]],
        ])
        await rig.vm.load(.mcp)
        rig.vm.addMcpRow()
        rig.vm.pickMCP(HXEntityPickerOption(id: 21, title: "报表服务"), into: rig.vm.mcpRows[0].id)

        let issues = rig.vm.validate(.mcp)
        XCTAssertEqual(issues.map(\.field), [.mcp])
        XCTAssertTrue(issues[0].message.contains("TOKEN"))
    }

    // MARK: - duplicates

    /// The picker hides what another row has taken, so a duplicate can only have come back from storage — and
    /// the backend de-duplicates by id after ordering, which silently loses the second row's values.
    func testTwoRowsForTheSameToolFromAnEditAreRefused() throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "toolList": [
                ["toolId": 11, "toolName": "fetch_url"],
                ["toolId": 11, "toolName": "fetch_url"],
            ],
        ])
        let rig = try rig(mode: .edit(row))
        XCTAssertEqual(rig.vm.validate(.tool).map(\.field), [.tool])
        XCTAssertTrue(rig.vm.validate(.tool).contains { $0.message.contains("twice") }, "\(rig.vm.validate(.tool))")
    }

    /// Same name, different id: the resolver refuses the pair (`SkillBindingResolver.kt:63`-`:68`).
    func testTwoRowsWithTheSameSkillNameButDifferentIdsAreRefused() throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "skillList": [
                ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
                ["skillId": 52, "skillName": "pdf", "repositoryId": 7],
            ],
        ])
        let rig = try rig(mode: .edit(row))
        let issues = rig.vm.validate(.skill)
        XCTAssertEqual(issues.count, 1)
        XCTAssertTrue(issues[0].message.contains("pdf"), "\(issues[0].message)")
    }

    func testAHalfFilledSkillRowIsRefusedRatherThanDropped() throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "skillList": [["repositoryId": 7, "repositoryName": "团队技能源"]],
        ])
        let rig = try rig(mode: .edit(row))
        XCTAssertEqual(rig.vm.validate(.skill).map(\.field), [.skill])
    }

    // MARK: - what goes on the wire

    func testSkillsAlwaysGoOutAsAListSoAnEmptiedStepClearsThem() throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "skillList": [["skillId": 51, "skillName": "PDF", "repositoryId": 7]],
        ])
        let rig = try rig(mode: .edit(row))
        rig.vm.skillRows = []

        XCTAssertEqual(rig.vm.buildDraft().skillIDs, [], "an emptied step 4 has to send the empty list, not nothing")
        let body = try json(rig.vm.buildDraft())
        XCTAssertEqual(body["skillList"] as? String, "", "and the wire shape is the comma-joined string")
    }

    func testAToolRowSendsEveryDeclaredParameterEvenTheBlankOnes() async throws {
        let rig = try rig()
        try seed(rig, tools: [[
            "id": 11,
            "name": "fetch_url",
            "envParams": [
                ["envParamName": "API_KEY", "required": true, "secret": true],
                ["envParamName": "TIMEOUT", "defaultValue": "30"],
            ],
        ]])
        await rig.vm.load(.tool)
        rig.vm.addToolRow()
        rig.vm.pickTool(HXEntityPickerOption(id: 11, title: "抓取网页"), into: rig.vm.toolRows[0].id)

        let tools = try XCTUnwrap(rig.vm.buildDraft().tools)
        XCTAssertEqual(tools[0].envBindings, [
            .custom(envKey: "API_KEY", value: ""),
            .custom(envKey: "TIMEOUT", value: "30"),
        ], "the web sends the blank rows too; only CLI drops them")
    }

    func testOnlyFilledCLIRowsGoOut() async throws {
        let rig = try rig()
        try seed(rig, clis: [[
            "id": 31,
            "name": "harnax-cli",
            "envParams": [
                ["envParamName": "TOKEN", "required": true, "defaultValue": "abc"],
                ["envParamName": "PROXY"],
            ],
        ]])
        await rig.vm.load(.cli)
        rig.vm.addCLI()
        try fill(rig.vm.cliEnvBinding(for: 31), at: 0, with: "typed")

        let clis = try XCTUnwrap(rig.vm.buildDraft().clis)
        XCTAssertEqual(clis[0].envBindings, [.custom(envKey: "TOKEN", value: "typed")], "a blank CLI row would freeze the wrong key into the snapshot")
    }

    /// The mask is the whole reason the reference row keeps an id and not a string.
    func testAReferenceBindingSendsTheIdAndNeverTheMask() async throws {
        let rig = try rig()
        try seed(rig, tools: [["id": 11, "name": "fetch_url", "envParams": [["envParamName": "API_KEY", "secret": true]]]])
        await rig.vm.load(.tool)
        rig.vm.addToolRow()
        rig.vm.pickTool(HXEntityPickerOption(id: 11, title: "抓取网页"), into: rig.vm.toolRows[0].id)

        let rowID = rig.vm.toolRows[0].id
        var rows = rig.vm.toolEnvBinding(for: rowID).wrappedValue
        rows[0].source = .reference
        rows[0].envVarID = 42
        rows[0].customValue = "******"
        rig.vm.toolEnvBinding(for: rowID).wrappedValue = rows

        let bindings = try XCTUnwrap(rig.vm.buildDraft().tools?.first?.envBindings)
        XCTAssertEqual(bindings, [.referenced(envKey: "API_KEY", envVarID: 42)])
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(bindings), as: UTF8.self).contains("******"))
    }

    // MARK: - an edit seeds from the row it arrived on

    func testAnEditSeedsEveryBindingWithoutARead() throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "description": "抓取并汇总",
            "systemPrompt": "你负责抓取",
            "modelId": 1,
            "isPublic": 1,
            "toolList": [["toolId": 11, "toolName": "fetch_url", "needConfirm": true, "envBindings": [["envKey": "API_KEY", "customValue": "abc"]]]],
            "mcpList": [["mcpId": 21, "mcpName": "报表服务"]],
            "skillList": [["skillId": 51, "skillName": "PDF", "repositoryId": 7, "repositoryName": "团队技能源"]],
            "cliList": [["cliId": 31, "cliName": "harnax-cli", "envBindings": [["envKey": "TOKEN", "customValue": "xyz"]]]],
        ])
        let rig = try rig(mode: .edit(row))

        XCTAssertEqual(rig.vm.name, "报表")
        XCTAssertEqual(rig.vm.detail, "抓取并汇总")
        XCTAssertEqual(rig.vm.systemPrompt, "你负责抓取")
        XCTAssertEqual(rig.vm.modelID, 1)
        XCTAssertTrue(rig.vm.isPublic)
        XCTAssertEqual(rig.vm.toolRows.count, 1)
        XCTAssertEqual(rig.vm.mcpRows.count, 1)
        XCTAssertEqual(rig.vm.skillRows.count, 1)
        XCTAssertEqual(rig.vm.selectedCLIIDs, [31])
        XCTAssertEqual(rig.vm.cliNames[31], "harnax-cli")
        XCTAssertTrue(rig.agents.requests.isEmpty, "there is no detail endpoint on this path")
    }

    /// The stored value is the operator's; the declaration list only adds what the entity has gained since.
    func testMergingDeclarationsKeepsStoredValuesAndAddsTheNewParameter() async throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "toolList": [["toolId": 11, "toolName": "fetch_url", "envBindings": [["envKey": "API_KEY", "customValue": "abc"]]]],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, tools: [[
            "id": 11,
            "name": "fetch_url",
            "envParams": [
                ["envParamName": "API_KEY", "required": true],
                ["envParamName": "TIMEOUT", "defaultValue": "30"],
            ],
        ]])

        await rig.vm.load(.tool)

        let env = rig.vm.toolRows[0].env
        XCTAssertEqual(env.map(\.envKey), ["API_KEY", "TIMEOUT"])
        XCTAssertEqual(env[0].customValue, "abc", "a stored value is never overwritten by the declaration")
        XCTAssertEqual(env[1].customValue, "", "the merge attaches declarations only; a default reaches the row at pick time, not here (`UpdateForm.tsx:154`-`:165`)")
    }

    func testAStoredParameterThePackageStoppedDeclaringIsKept() async throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "toolList": [["toolId": 11, "toolName": "fetch_url", "envBindings": [["envKey": "LEGACY", "customValue": "abc"]]]],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, tools: [["id": 11, "name": "fetch_url", "envParams": [["envParamName": "API_KEY"]]]])

        await rig.vm.load(.tool)

        XCTAssertEqual(rig.vm.toolRows[0].env.map(\.envKey), ["LEGACY", "API_KEY"])
        XCTAssertEqual(rig.vm.buildDraft().tools?[0].envBindings?.count, 2)
    }

    /// A row that names a tool the candidate page no longer holds still has to be sendable — deleting it would
    /// silently unbind something the operator never touched.
    func testAToolThatFellOutOfTheCandidatePageSurvivesTheRoundTrip() async throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "toolList": [["toolId": 99, "toolName": "gone_tool"]],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, tools: [["id": 11, "name": "fetch_url"]])

        await rig.vm.load(.tool)

        XCTAssertEqual(rig.vm.toolRows[0].toolID, 99)
        XCTAssertEqual(rig.vm.buildDraft().tools?.map(\.id), [99])
    }

    func testAStepFourSkillReadFollowsTheSeededRepository() async throws {
        let row = try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "skillList": [["skillId": 51, "skillName": "PDF", "repositoryId": 7]],
        ])
        let rig = try rig(mode: .edit(row))
        try seed(rig, sources: [["id": 7, "name": "团队技能源", "sourceType": "GIT"]])

        await rig.vm.load(.skill)

        XCTAssertEqual(rig.skills.skillRequests.map(\.repositoryID), [7])
        XCTAssertTrue(rig.vm.skillOptions(in: 7, excluding: []).isEmpty, "nothing queued, nothing on screen yet")
    }

    // MARK: - submitting

    func testASaveRefusesAndJumpsToTheStepThatHoldsTheFirstProblem() async throws {
        let rig = try rig()
        rig.vm.step = .cli

        let result = await rig.vm.save()

        XCTAssertTrue(rig.agents.saveCalls.isEmpty)
        guard case let .invalid(issues, step) = result else { return XCTFail("expected a refusal, got \(result)") }
        XCTAssertEqual(step, .basic)
        XCTAssertEqual(rig.vm.step, .basic, "the operator has to land where the sentence points")
        XCTAssertEqual(issues.count, 4)
    }

    func testACreateSendsOnePostAndReportsCreated() async throws {
        let rig = try rig()
        fillIn(rig.vm)

        let result = await rig.vm.save()

        XCTAssertEqual(result, .created)
        XCTAssertEqual(rig.agents.saveCalls.count, 1)
        XCTAssertNil(rig.agents.saveCalls[0].id, "the create route carries no id")
        XCTAssertEqual(rig.agents.saveCalls[0].draft.name, "报表")
    }

    func testAnUpdateIsAddressedByIDAndGivesTheRefreshPanelItsRow() async throws {
        let row = try AgentSummary.stub(["id": 3, "name": "报表"])
        let rig = try rig(mode: .edit(row))
        fillIn(rig.vm)
        rig.agents.relatedReply = .success([])

        let result = await rig.vm.save()

        XCTAssertEqual(result, .updated(id: 3, name: "报表"))
        XCTAssertEqual(rig.agents.saveCalls.map(\.id), [3])

        let target = rig.vm.makeRefreshTarget(id: 3, name: "报表")
        XCTAssertEqual(target.id, 3)
        XCTAssertEqual(target.name, "报表")
        _ = await target.load()
        XCTAssertEqual(rig.agents.relatedRequests, [3], "the panel reads through the agent domain, not a second client")
    }

    func testAFailedWriteKeepsTheWizardOpenInTheServersWords() async throws {
        let rig = try rig()
        fillIn(rig.vm)
        rig.vm.step = .cli
        rig.agents.saveReplies = [.failure(.business(code: -1, message: "该名称已存在"))]

        let result = await rig.vm.save()

        guard case let .failed(message) = result else { return XCTFail("expected the server's sentence, got \(result)") }
        XCTAssertEqual(message, "该名称已存在")
        XCTAssertEqual(rig.vm.step, .cli, "a refused write is not a validation failure; nothing moves")
    }

    /// A double tap reaches the model before the button redraws, so the guard is the model's job.
    func testASecondSaveWhileOneIsInFlightSendsNothingTwice() async throws {
        let writer = ParkingWriter()
        let rig = try rig(writer: writer)
        fillIn(rig.vm)

        writer.gate = true
        let first = Task { await rig.vm.save() }
        try await waitUntil { writer.calls == 1 }
        XCTAssertTrue(rig.vm.isSaving)
        let second = Task { await rig.vm.save() }
        try await settle()
        XCTAssertEqual(writer.calls, 1, "the same agent cannot be created twice by one tap")

        writer.release()
        _ = await first.value
        _ = await second.value
        XCTAssertFalse(rig.vm.isSaving)
    }

    // MARK: - plumbing

    private struct Rig {
        let vm: AgentFormViewModel
        let agents: FakeAgents
        let models: FakeModelCatalog
        let tools: FakeToolCatalog
        let mcp: FakeMcpServers
        let skills: FakeSkills
        let clis: FakeClis
        let envVars: FakeEnvVars
    }

    private func rig(
        mode: AgentFormViewModel.Mode = .create,
        account: AccountSnapshot? = AccountSnapshot(username: "admin", isAdministrator: true),
        writer: (any AgentWriting)? = nil
    ) throws -> Rig {
        let agents = FakeAgents()
        let models = FakeModelCatalog()
        let tools = FakeToolCatalog()
        let mcp = FakeMcpServers()
        let skills = FakeSkills()
        let clis = FakeClis()
        let envVars = FakeEnvVars()
        let vm = AgentFormViewModel(
            mode: mode,
            account: account,
            agents: agents,
            writer: writer ?? agents,
            models: models,
            tools: tools,
            mcp: mcp,
            skills: skills,
            clis: clis,
            envVars: envVars
        )
        return Rig(vm: vm, agents: agents, models: models, tools: tools, mcp: mcp, skills: skills, clis: clis, envVars: envVars)
    }

    /// The four fields step 1 insists on, plus the model id — enough for `validateAll()` to pass.
    private func fillIn(_ vm: AgentFormViewModel) {
        vm.name = "报表"
        vm.detail = "抓取并汇总"
        vm.systemPrompt = "你负责抓取"
        vm.modelID = 1
    }

    private func seed(_ rig: Rig, models rows: [[String: Any]]) throws {
        rig.models.choiceReplies = [.success(try PageStub.page(ModelSummary.self, rows))]
    }

    private func seed(_ rig: Rig, tools rows: [[String: Any]]) throws {
        rig.tools.availableReplies = [.success(try wire(rows).map { try ToolSummary.stub($0) })]
    }

    private func seed(_ rig: Rig, mcps rows: [[String: Any]]) throws {
        rig.mcp.pageReplies = [.success(try PageStub.page(McpServerRow.self, wire(rows)))]
    }

    private func seed(_ rig: Rig, sources rows: [[String: Any]]) throws {
        let columns: [String: Any] = [
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
            var merged: [String: Any] = ["sourceType": "GIT"]
            for (key, value) in columns where row[key] == nil { merged[key] = value }
            for (key, value) in row { merged[key] = value }
            return merged
        }
        rig.skills.sourceReplies = [.success(try PageStub.page(SkillSourceSummary.self, answered))]
    }

    private func seed(_ rig: Rig, skills row: [String: Any]) throws {
        var counted: [String: Any] = ["boundAgentCount": 0, "boundTeamCount": 0]
        for (key, value) in row { counted[key] = value }
        rig.skills.skillPageReplies = [.success(try PageStub.page(SkillItem.self, [counted]))]
    }

    private func seed(_ rig: Rig, clis rows: [[String: Any]]) throws {
        rig.clis.replies = [.success(try PageStub.page(CliSummary.self, wire(rows)))]
    }

    /// A declared parameter always carries its two flags: `required` and `secret` are non-null booleans with a
    /// default in `ToolEnvParamEntry.kt:25`,`:28` so Jackson writes them even when the fixture is about
    /// something else entirely.
    private func wire(_ rows: [[String: Any]]) -> [[String: Any]] {
        rows.map { row in
            guard let params = row["envParams"] as? [[String: Any]] else { return row }
            var updated = row
            updated["envParams"] = params.map { param in
                var filled = param
                if filled["required"] == nil { filled["required"] = false }
                if filled["secret"] == nil { filled["secret"] = false }
                return filled
            }
            return updated
        }
    }

    /// Writes through the editor's own binding, which is how a screen changes a parameter row.
    private func fill(_ binding: Binding<[HXEnvBindingRow]>, at index: Int, with value: String) throws {
        var rows = binding.wrappedValue
        guard rows.indices.contains(index) else {
            XCTFail("the picked package declared no parameter row at index \(index)")
            return
        }
        rows[index].source = .custom
        rows[index].customValue = value
        binding.wrappedValue = rows
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

/// A writer that holds a save open, so the window between the tap and the redraw can be looked at.
private final class ParkingWriter: AgentWriting, @unchecked Sendable {
    var gate = false
    private(set) var calls = 0
    var reply: Result<EmptyResponse, APIError> = .success(EmptyResponse())
    private var parked: [() -> Void] = []

    func createAgent(_ draft: AgentSaveDraft) async -> Result<EmptyResponse, APIError> {
        await park()
    }

    func updateAgent(id: Int64, _ draft: AgentSaveDraft) async -> Result<EmptyResponse, APIError> {
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

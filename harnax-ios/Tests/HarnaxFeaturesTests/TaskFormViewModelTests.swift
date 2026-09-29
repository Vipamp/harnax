import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// `/agent/task`'s create and edit sheet: what it refuses to post, what it puts on the wire, and what it says
/// when the write comes back.
///
/// The two rules that outrank the rest are the backend's, so they are asserted here rather than designed around:
/// a successful edit pauses the task whatever the body said (`AgentTaskCrudServiceImpl.kt:156-157`, `§3.2`), and
/// a 40902 is a saved row whose Quartz entry never converged, which closes the sheet with the server's sentence
/// as a warning (`:264-293`, `§4.3`).
///
/// The partial body is the other half of this file. `AgentTaskUpdateRequest` declares every property nullable
/// with no validator (`:16-45`), so a field iOS does not send is a field the service keeps — and a field iOS
/// sends as an empty string is one it **stores**.
@MainActor
final class TaskFormViewModelTests: XCTestCase {
    private func summaryRow(_ fields: [String: Any] = [:]) throws -> AgentTaskSummary {
        var json: [String: Any] = [
            "id": 41,
            "name": "每日晨报",
            "agentId": 7,
            "agentName": "翻译助手",
            "prompt": "汇总昨天的构建失败",
            "cronExpression": "0 0 9 * * ?",
            "taskStatus": 1,
            "concurrent": 0,
            "timeoutSeconds": 300,
            "description": "工作日每天早上九点跑一次",
            "isPublic": 1,
            "creator": "admin",
            "active": 1,
            "createTime": "2026-09-20 09:14:02",
            "updateTime": "2026-09-25 18:40:11",
        ]
        for (key, value) in fields { json[key] = value }
        return try XCTUnwrap(
            PageStub.page(AgentTaskSummary.self, [json], total: 1).records.first
        )
    }

    private func form(
        _ row: AgentTaskSummary? = nil,
        catalog: FakeAgentTasks = FakeAgentTasks()
    ) -> TaskFormViewModel {
        TaskFormViewModel(row: row, catalog: catalog)
    }

    /// A create sheet with everything the two DTOs ask for, so one field can be broken at a time.
    private func filled(_ vm: TaskFormViewModel, agentID: Int64 = 7) {
        vm.name = "夜间汇总"
        vm.agentId = agentID
        vm.prompt = "总结今天的告警"
        vm.cronExpression = "0 0 2 * * ?"
        vm.timeoutText = "300"
        vm.note = ""
    }

    // MARK: - what the form refuses to post

    func testAnEmptySheetIsRefusedAtItsNameFirst() {
        let vm = form()
        XCTAssertEqual(vm.validationErrorKey, "task.form.name.required")
        XCTAssertFalse(vm.canSubmit)
        XCTAssertNil(vm.draft, "there is no body to build out of an empty form")
    }

    func testTheNameCeilingIsTheDTOSAndNotTheColumns() {
        let vm = form()
        vm.name = String(repeating: "名", count: TaskFormViewModel.nameLimit + 1)
        XCTAssertEqual(vm.validationErrorKey, "task.form.name.long")
        vm.name = String(repeating: "名", count: TaskFormViewModel.nameLimit)
        XCTAssertEqual(vm.validationErrorKey, "task.form.agent.required", "128 characters fit")
    }

    func testTheTargetAndThePromptAreBothRequiredEvenThoughTheEditDTOHasNoValidator() {
        let vm = form()
        vm.name = "夜间汇总"
        XCTAssertEqual(vm.validationErrorKey, "task.form.agent.required")
        vm.agentId = 7
        XCTAssertEqual(vm.validationErrorKey, "task.form.prompt.required")
        vm.prompt = "   "
        XCTAssertEqual(vm.validationErrorKey, "task.form.prompt.required", "a blank prompt reads as none")
    }

    /// The backend's only cron check is the field count (`AgentTaskCrudServiceImpl.kt:212-225`), so a five-field
    /// Unix expression is accepted by the write and refused at `start` — this client refuses it up front instead.
    func testACronIsJudgedByTheArityTheServerCounts() {
        let vm = form()
        filled(vm)
        vm.cronExpression = "0 0 9 * *"
        XCTAssertEqual(vm.validationErrorKey, "task.form.cron.arity")
        vm.cronExpression = "0 0 9 * * ? 2026 extra"
        XCTAssertEqual(vm.validationErrorKey, "task.form.cron.arity")
        vm.cronExpression = "   "
        XCTAssertEqual(vm.validationErrorKey, "task.form.cron.required")
    }

    /// `0 0 0 * * *` passes the server's count and builds no trigger: Quartz wants exactly one of the two day
    /// slots marked `?` (`§3.3`).
    func testBothDaySlotsCannotNameDaysAtOnce() {
        let vm = form()
        filled(vm)
        vm.cronExpression = "0 0 0 * * *"
        XCTAssertTrue(
            QuartzCron.hasServerAcceptedArity("0 0 0 * * *"),
            "the backend's own check lets this one through, so the refusal has to come from here"
        )
        XCTAssertEqual(vm.validationErrorKey, "task.form.cron.dayFields")
        vm.cronExpression = "0 0 9 1 * MON"
        XCTAssertEqual(vm.validationErrorKey, "task.form.cron.dayFields")
        vm.cronExpression = "0 0 9 ? * MON"
        XCTAssertNil(vm.validationErrorKey)
    }

    func testTheFivePresetsAllPassTheTwoCronRules() {
        let vm = form()
        filled(vm)
        for preset in CronPreset.allCases {
            vm.choose(preset)
            XCTAssertNil(vm.validationErrorKey, "\(preset.expression) would be refused by its own form")
        }
    }

    /// Nothing on the server bounds this field — the DTO has no annotation on it at all — so the `InputNumber`
    /// limits are the only place 30..3600 exists.
    func testTheTimeoutLimitsExistOnlyOnThisClient() {
        let vm = form()
        filled(vm)
        vm.timeoutText = "29"
        XCTAssertEqual(vm.validationErrorKey, "task.form.timeout.range")
        vm.timeoutText = "3601"
        XCTAssertEqual(vm.validationErrorKey, "task.form.timeout.range")
        vm.timeoutText = "abc"
        XCTAssertEqual(vm.validationErrorKey, "task.form.timeout.range")
        for allowed in ["30", "300", "3600"] {
            vm.timeoutText = allowed
            XCTAssertNil(vm.validationErrorKey, "\(allowed) is inside the console's own picker")
        }
        vm.timeoutText = ""
        XCTAssertNil(vm.validationErrorKey, "an empty box lets the DTO's default stand")
        XCTAssertNil(vm.parsedTimeout)
    }

    func testANoteOverTheDTOCeilingIsRefusedHere() {
        let vm = form()
        filled(vm)
        vm.note = String(repeating: "说", count: TaskFormViewModel.noteLimit + 1)
        XCTAssertEqual(vm.validationErrorKey, "task.form.note.long")
    }

    // MARK: - the presets are a fill helper, not a mode

    func testAPresetWritesIntoTheSameFieldAndLightsItself() {
        let vm = form()
        vm.cronExpression = "0 30 * * * ?"
        vm.choose(.everyDay9)
        XCTAssertEqual(vm.cronExpression, "0 0 9 * * ?", "the chip overwrites whatever was typed")
        XCTAssertEqual(vm.selectedPreset, .everyDay9)
    }

    /// The server stores the string as it arrived, so an extra space makes a hand-typed preset read as custom —
    /// and the chip must go dark rather than light on a near match.
    func testAPresetTypedWithAnExtraSpaceReadsAsCustom() {
        let vm = form()
        vm.cronExpression = "0  0 9 * * ?"
        XCTAssertNil(vm.selectedPreset)
        XCTAssertNil(CronPreset.matching("0 0 9 * * ? "))
    }

    // MARK: - the create body

    func testACreateSendsEveryFieldItHas() throws {
        let vm = form()
        filled(vm)
        vm.concurrent = true
        vm.note = "夜里跑"
        vm.isPublic = false
        let draft = try XCTUnwrap(vm.draft)
        XCTAssertEqual(draft.name, "夜间汇总")
        XCTAssertEqual(draft.agentId, 7)
        XCTAssertEqual(draft.prompt, "总结今天的告警")
        XCTAssertEqual(draft.cronExpression, "0 0 2 * * ?")
        XCTAssertEqual(draft.concurrent, 1, "the switch goes out as 0/1, never as a JSON boolean")
        XCTAssertEqual(draft.timeoutSeconds, 300)
        XCTAssertEqual(draft.description, "夜里跑")
        XCTAssertEqual(draft.isPublic, 0)
    }

    func testACreateTrimsTheNameAndTheCronButNotThePrompt() throws {
        let vm = form()
        filled(vm)
        vm.name = "  夜间汇总  "
        vm.cronExpression = "  0 0 2 * * ?  "
        vm.prompt = "总结今天的告警  "
        let draft = try XCTUnwrap(vm.draft)
        XCTAssertEqual(draft.name, "夜间汇总")
        XCTAssertEqual(draft.cronExpression, "0 0 2 * * ?")
        XCTAssertEqual(draft.prompt, "总结今天的告警  ", "the prompt is the agent's instruction text, kept as typed")
    }

    func testABlankTimeoutBoxSendsNothingSoTheServerDefaultApplies() throws {
        let vm = form()
        filled(vm)
        vm.timeoutText = ""
        XCTAssertNil(try XCTUnwrap(vm.draft).timeoutSeconds)
    }

    func testADraftDoesNotExistWhileTheFormIsInvalid() {
        let vm = form()
        filled(vm)
        XCTAssertNotNil(vm.draft)
        vm.cronExpression = "0 0 2 * *"
        XCTAssertNil(vm.draft)
    }

    // MARK: - the edit body, which is partial by construction

    /// An untouched sheet has nothing to say, and the service reads a body of no keys as "keep everything".
    func testAnUntouchedEditSendsNoKeysAtAll() throws {
        let vm = form(try summaryRow())
        XCTAssertFalse(vm.hasChanges)
        XCTAssertEqual(try XCTUnwrap(vm.change), AgentTaskChange())
        XCTAssertEqual(vm.timeoutText, "300", "the row seeds every box")
        XCTAssertEqual(vm.note, "工作日每天早上九点跑一次")
        XCTAssertFalse(vm.concurrent)
        XCTAssertTrue(vm.isPublic)
    }

    /// `agentName` is resolved from `agentId` by admin on every body that names one
    /// (`AgentTaskController.kt:100,230-239`), so an unchanged id would re-stamp a name the row already carries.
    func testSendingAnUnchangedTargetWouldRestampItsName() throws {
        let vm = form(try summaryRow())
        XCTAssertNil(vm.submittedAgentId)
        vm.agentId = 9
        XCTAssertEqual(vm.submittedAgentId, 9)
        XCTAssertEqual(vm.change?.agentId, 9)
        XCTAssertEqual(vm.change?.name, nil, "one change is one key")
    }

    func testEachChangedFieldIsTheOnlyOneThatGoesOut() throws {
        let vm = form(try summaryRow())
        vm.name = "改名的任务"
        XCTAssertEqual(vm.change, AgentTaskChange(name: "改名的任务"))

        vm.prompt = "新的提示词"
        XCTAssertEqual(vm.change, AgentTaskChange(name: "改名的任务", prompt: "新的提示词"))

        vm.cronExpression = "0 30 9 * * ?"
        vm.concurrent = true
        vm.timeoutText = "60"
        vm.isPublic = false
        XCTAssertEqual(
            vm.change,
            AgentTaskChange(
                name: "改名的任务", prompt: "新的提示词", cronExpression: "0 30 9 * * ?",
                concurrent: 1, timeoutSeconds: 60, isPublic: 0
            )
        )
    }

    func testATimeoutBoxLeftAtTheStoredValueIsNotAChange() throws {
        let vm = form(try summaryRow())
        vm.timeoutText = "300"
        XCTAssertNil(vm.submittedTimeout)
        vm.timeoutText = " 300 "
        XCTAssertNil(vm.submittedTimeout, "the same number with spaces is still the same number")
        vm.timeoutText = "60"
        XCTAssertEqual(vm.submittedTimeout, 60)
    }

    /// The one asymmetry worth its own test: an empty note on create sends nothing, while on edit it must send
    /// the empty string — otherwise the stored description can never be cleared.
    func testClearingTheNoteSendsAnEmptyStringOnEditAndNothingOnCreate() throws {
        let edit = form(try summaryRow())
        edit.note = ""
        XCTAssertEqual(edit.submittedNote, "")
        XCTAssertEqual(try XCTUnwrap(edit.change).description, "")

        let create = form()
        filled(create)
        create.note = ""
        XCTAssertNil(create.submittedNote)
        XCTAssertNil(try XCTUnwrap(create.draft).description)
    }

    // MARK: - the two writes

    func testACreateSucceedsAndLeavesNoPauseNoticeBecauseTheServerStartedItPaused() async throws {
        let catalog = FakeAgentTasks()
        let vm = form(catalog: catalog)
        filled(vm)
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(catalog.createRequests.count, 1)
        XCTAssertTrue(catalog.updateRequests.isEmpty)
        XCTAssertEqual(vm.outcome, TaskFormSaveOutcome(didPauseScheduledTask: false))
        XCTAssertTrue(vm.saved)
        XCTAssertNil(vm.errorText)
    }

    func testAnEditThatSucceedsReportsThatItPausedTheTask() async throws {
        let catalog = FakeAgentTasks()
        let vm = form(try summaryRow(), catalog: catalog)
        vm.prompt = "新的提示词"
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(catalog.updateRequests.count, 1)
        XCTAssertEqual(catalog.updateRequests.first?.id, 41)
        XCTAssertEqual(catalog.updateRequests.first?.change, AgentTaskChange(prompt: "新的提示词"))
        XCTAssertEqual(vm.outcome, TaskFormSaveOutcome(didPauseScheduledTask: true))
    }

    func testABusinessRefusalStaysInTheSheetWithTheServersSentence() async throws {
        let catalog = FakeAgentTasks()
        let vm = form(catalog: catalog)
        filled(vm)
        catalog.createReplies = [
            .failure(.business(code: 500, message: "Task name already exists"))
        ]
        await vm.save()
        XCTAssertEqual(vm.errorText, "Task name already exists")
        XCTAssertNil(vm.outcome, "a refusal is not a save, so the sheet stays open")
        XCTAssertFalse(vm.saved)
    }

    /// 40902 on an edit is a written row whose Quartz entry never converged. The console closes the form and
    /// shows the sentence as a warning (`TaskForm.tsx:85-91`, `§4.3`), so iOS does the same.
    func testASchedulerSyncFailureOnAnEditClosesTheSheetWithTheSentenceAsAWarning() async throws {
        let catalog = FakeAgentTasks()
        let vm = form(try summaryRow(), catalog: catalog)
        vm.cronExpression = "0 15 9 * * ?"
        catalog.updateReplies = [
            .failure(.business(code: 40902, message: "Task updated but scheduler sync failed"))
        ]
        await vm.save()
        XCTAssertEqual(
            vm.outcome,
            TaskFormSaveOutcome(
                didPauseScheduledTask: true,
                schedulerSyncWarning: "Task updated but scheduler sync failed"
            )
        )
        XCTAssertNil(vm.errorText, "the write did land, so it is not a failure the sheet can retry")
    }

    /// The code means "written but unsynced" only where a write precedes the reconcile — and `create` never
    /// reconciles (`§4.2`). On a create sheet it stays a refusal.
    func testASchedulerSyncCodeOnACreateIsRefusedLikeAnyOtherFailure() async throws {
        let catalog = FakeAgentTasks()
        let vm = form(catalog: catalog)
        filled(vm)
        catalog.createReplies = [
            .failure(.business(code: 40902, message: "Task updated but scheduler sync failed"))
        ]
        await vm.save()
        XCTAssertNil(vm.outcome)
        XCTAssertEqual(vm.errorText, "Task updated but scheduler sync failed")
    }

    func testARowTheWireGaveNoIdForCannotBeWritten() async throws {
        let catalog = FakeAgentTasks()
        let keyless = try XCTUnwrap(
            PageStub.page(
                AgentTaskSummary.self,
                [[
                    "name": "无 id", "agentId": 7, "agentName": "", "prompt": "汇总",
                    "cronExpression": "0 0 9 * * ?",
                    "taskStatus": 1, "concurrent": 0, "timeoutSeconds": 300, "description": "",
                    "isPublic": 0, "creator": "admin", "active": 1,
                    "createTime": "2026-09-20 09:14:02", "updateTime": "2026-09-25 18:40:11",
                ]],
                total: 1
            ).records.first
        )
        XCTAssertNil(keyless.id, "the route's own key is optional on the wire")
        let vm = form(keyless, catalog: catalog)
        vm.prompt = "改一下"
        await vm.save()
        XCTAssertTrue(catalog.updateRequests.isEmpty, "the id is this route's only address")
        XCTAssertEqual(vm.errorText, ErrorMessage.text(for: .unpackable))
        XCTAssertNil(vm.outcome)
    }

    func testASaveAlreadyOutstandingIsNotSentTwice() async throws {
        let catalog = FakeAgentTasks()
        let vm = form(catalog: catalog)
        filled(vm)
        catalog.gateWrites = true
        catalog.createReplies = [.success(EmptyResponse())]
        let first = Task { await vm.save() }
        try await waitUntil { catalog.createRequests.count == 1 }
        await vm.save()
        XCTAssertEqual(catalog.createRequests.count, 1, "the sheet is mid-write, and a second tap is nothing")
        catalog.releaseWrites()
        await first.value
        XCTAssertTrue(vm.saved)
    }

    func testASaveThatCannotBeValidatedSaysSoWithoutReachingTheStack() async throws {
        let catalog = FakeAgentTasks()
        let vm = form(catalog: catalog)
        filled(vm)
        vm.cronExpression = "0 0 9 * *"
        await vm.save()
        XCTAssertTrue(catalog.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("task.form.cron.arity"))
    }

    // MARK: - the picker's source

    func testThePickerListsTheLiveAgentsAndDropsRowsWithNoId() async throws {
        let catalog = FakeAgentTasks()
        catalog.agentsReplies = .success(
            try PageStub.list(
                [AgentTaskAgentOption].self,
                [["id": 7, "name": "翻译助手"], ["name": "残缺行"], ["id": 9, "name": "代码审查"]]
            )
        )
        let vm = form(catalog: catalog)
        await vm.loadAgents()
        XCTAssertEqual(vm.agents.map(\.id), [7, 9], "a picker entry with no id cannot be selected")
        XCTAssertEqual(vm.agents.map(\.displayName), ["翻译助手", "代码审查"])
        XCTAssertNil(vm.agentErrorText)
    }

    /// The route returns only the *live* agents, so a stored task bound to an agent that has since been stopped
    /// would otherwise open with an empty target and post a change nobody asked for.
    func testAStoredTaskWhoseAgentHasStoppedKeepsItsTargetOnScreen() async throws {
        let catalog = FakeAgentTasks()
        catalog.agentsReplies = .success(try PageStub.list([AgentTaskAgentOption].self, [["id": 7, "name": "翻译助手"]]))
        let vm = form(try summaryRow(["agentId": 9, "agentName": "旧助手"]), catalog: catalog)
        await vm.loadAgents()
        XCTAssertEqual(vm.agents.map(\.id), [7, 9])
        XCTAssertEqual(vm.agentTitle, "旧助手", "the row's own name snapshot is what the picker shows")
        XCTAssertNil(vm.submittedAgentId, "and keeping it is not a change")
    }

    func testAStoredAgentThatTheRouteStillListsIsNotAddedTwice() async throws {
        let catalog = FakeAgentTasks()
        catalog.agentsReplies = .success(try PageStub.list([AgentTaskAgentOption].self, [["id": 7, "name": "翻译助手"]]))
        let vm = form(try summaryRow(), catalog: catalog)
        await vm.loadAgents()
        XCTAssertEqual(vm.agents.count, 1)
    }

    func testAStoredAgentWithNoNameSnapshotReadsAsItsId() async throws {
        let catalog = FakeAgentTasks()
        catalog.agentsReplies = .success(try PageStub.list([AgentTaskAgentOption].self, []))
        let vm = form(try summaryRow(["agentId": 9, "agentName": ""]), catalog: catalog)
        await vm.loadAgents()
        XCTAssertEqual(vm.agentTitle, "#9")
    }

    func testAPickerThatCannotListAgentsFailsOnTheFieldNotOnTheSheet() async throws {
        let catalog = FakeAgentTasks()
        catalog.agentsReplies = .failure(.offline)
        let vm = form(catalog: catalog)
        await vm.loadAgents()
        XCTAssertEqual(vm.agentErrorText, hx("error.offline"))
        XCTAssertNil(vm.errorText, "a form that cannot list agents has not failed to save")
    }

    /// The retry has to be possible: a failed read leaves the list empty, so the next tap asks the route again
    /// instead of sitting on an empty picker forever.
    func testAFailedPickerReadIsAskedAgainOnTheNextLoad() async throws {
        let catalog = FakeAgentTasks()
        catalog.agentsReplies = .failure(.offline)
        let vm = form(catalog: catalog)
        await vm.loadAgents()
        catalog.agentsReplies = .success(try PageStub.list([AgentTaskAgentOption].self, [["id": 7, "name": "翻译助手"]]))
        await vm.loadAgents()
        XCTAssertEqual(catalog.agentsRequests, 2)
        XCTAssertNil(vm.agentErrorText)
        XCTAssertEqual(vm.agents.count, 1)
    }

    /// The picker is a one-read field: once it holds rows, reopening the sheet must not spend a second request
    /// on the same list.
    func testAPickerThatAlreadyHoldsAgentsIsNotReadAgain() async throws {
        let catalog = FakeAgentTasks()
        catalog.agentsReplies = .success(try PageStub.list([AgentTaskAgentOption].self, [["id": 7, "name": "翻译助手"]]))
        let vm = form(catalog: catalog)
        await vm.loadAgents()
        await vm.loadAgents()
        XCTAssertEqual(catalog.agentsRequests, 1)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

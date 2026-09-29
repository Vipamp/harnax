import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The new-conversation sheet: what it refuses to post, what it asks the duplicate route about, and what one
/// create costs.
///
/// Two of this screen's rules are not design choices. The duplicate check is platform-wide —
/// `SELECT COUNT(*) FROM session WHERE title = ? AND active = 1` with no tenant and no creator condition
/// (`harnax-entity/src/main/resources/mapper/SessionMapper.xml:44-46`) — so a taken name can belong to a
/// conversation in another tenant that this account will never list, and the copy has to say the name is
/// taken rather than claim the user already owns it. And the create answers `ResultVo<Void>`
/// (`SessionController.kt:80-89`), so success is only ever a callback that says "re-read", never an id to
/// navigate to.
@MainActor
final class SessionCreateViewModelTests: XCTestCase {
    /// Short enough to keep the suite quick; the production default is the spec's 300 ms and is asserted
    /// on its own.
    private static let checkDelay = Duration.milliseconds(20)

    private func makeVM(
        _ creating: FakeSessionCreating = FakeSessionCreating(),
        onCreated: @escaping () -> Void = {}
    ) -> SessionCreateViewModel {
        SessionCreateViewModel(creating: creating, titleCheckDelay: Self.checkDelay, onCreated: onCreated)
    }

    /// A form with both executor groups answered and the name free, so one gate can be broken at a time.
    /// Loads the picker the way the sheet's `.task` does: the save gates read `choices`, and a test that
    /// skipped the read would be asserting the picker's absence rather than a rule.
    private func filled(
        _ creating: FakeSessionCreating,
        title: String = "夜间汇总",
        choices: SessionExecutorChoices = CreateFixtures.bothGroups(),
        taken: Bool = false,
        onCreated: @escaping () -> Void = {}
    ) async -> SessionCreateViewModel {
        creating.choicesReplies = [.success(choices)]
        creating.titleCheckReplies = Array(repeating: .success(taken), count: 5)
        let vm = makeVM(creating, onCreated: onCreated)
        await vm.loadChoices()
        vm.title = title
        vm.selectionKey = CreateFixtures.agent(7).selectionKey
        return vm
    }

    private func waitUntilAvailable(_ vm: SessionCreateViewModel) async throws {
        try await waitUntil { vm.titleVerdict != .checking && vm.titleVerdict != .unchecked }
    }

    // MARK: - the two title rules the route enforces

    func testAnEmptySheetIsRefusedAtItsTitleFirst() {
        let vm = makeVM()
        XCTAssertEqual(vm.validationErrorKey, "session.create.title.required")
        XCTAssertFalse(vm.canSubmit)
        XCTAssertNil(vm.draft, "there is no body to build out of an empty form")
    }

    /// `@NotBlank` is a blank check, not an empty check (`SessionCreateRequest.kt:13`).
    func testATitleOfPureSpacesIsStillNoTitleAtAll() {
        let vm = makeVM()
        vm.title = "   \n "
        XCTAssertEqual(vm.validationErrorKey, "session.create.title.required")
        XCTAssertFalse(vm.title.isEmpty, "the box still shows what was typed")
    }

    func testTheTitleCeilingIsTheDTOSAndTrimmingHappensBeforeTheCount() {
        let vm = makeVM()
        XCTAssertEqual(SessionCreateViewModel.titleLimit, SessionCreateDraft.titleLimit)
        vm.title = String(repeating: "名", count: SessionCreateViewModel.titleLimit + 1)
        XCTAssertEqual(vm.validationErrorKey, "session.create.title.long")
        vm.title = " \(String(repeating: "名", count: SessionCreateViewModel.titleLimit)) "
        XCTAssertEqual(vm.validationErrorKey, "session.create.executor.required", "100 characters fit once trimmed")
    }

    /// A blank or over-long name is already refused by the fields, so the route is not asked about it: that
    /// `COUNT(*)` scans the whole table for a name that cannot be posted either way.
    func testANameTheFieldsAlreadyRefusedNeverReachesTheDuplicateRoute() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating)
        try await waitUntil { creating.titleCheckRequests.count == 1 }
        vm.title = "   "
        vm.title = String(repeating: "名", count: 101)
        try await Task.sleep(for: .milliseconds(120))
        XCTAssertEqual(creating.titleCheckRequests, ["夜间汇总"], "only the one name that was ever submittable")
    }

    // MARK: - the debounced check

    /// The console defines `debouncedValidateTitle` at `SettingsModal.tsx:54` and then hangs the undebounced
    /// validator on the `Form`, so iOS follows the intent: a burst of keystrokes costs one request.
    func testAStoppedTypingTitleIsOneCheckNotOnePerKeystroke() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating)
        try await waitUntil { creating.titleCheckRequests.count == 1 }
        vm.title = "白天"
        vm.title = "白天汇"
        vm.title = "白天汇总"
        try await waitUntil { creating.titleCheckRequests.count == 2 }
        try await Task.sleep(for: .milliseconds(120))
        XCTAssertEqual(creating.titleCheckRequests.count, 2, "the two superseded keystrokes never went out")
        XCTAssertEqual(creating.titleCheckRequests.last, "白天汇总")
    }

    func testTheProductionDebounceIsTheSpecsThreeHundredMilliseconds() {
        XCTAssertEqual(SessionCreateViewModel.defaultTitleCheckDelay, .milliseconds(300))
    }

    func testEditingTheTitleDropsTheVerdictItWasAbout() async throws {
        let vm = await filled(FakeSessionCreating())
        try await waitUntilAvailable(vm)
        XCTAssertEqual(vm.titleVerdict, .available)
        vm.title = "换一个名字"
        XCTAssertEqual(vm.titleVerdict, .checking, "the old verdict spoke for a name that is gone")
        XCTAssertFalse(vm.canSubmit)
    }

    /// Cancellation stops the sleep, not a request already in flight, so the stale-reply guard is what keeps a
    /// slow answer about a deleted name from clearing the one now on screen.
    func testASlowReplyAboutAnOldNameIsNotStampedOntoTheNewOne() async throws {
        let creating = FakeSessionCreating()
        creating.gateChecks = true
        let vm = await filled(creating, title: "旧名字")
        try await waitUntil { creating.titleCheckRequests.count == 1 }
        vm.title = "新名字"
        try await waitUntil { creating.titleCheckRequests.count == 2 }
        // The taken answer belongs to the first name, the free one to the second.
        creating.titleCheckReplies = [.success(true), .success(false)]
        creating.releaseChecks()
        try await waitUntil { vm.titleVerdict != .checking }
        XCTAssertEqual(vm.titleVerdict, .available, "the reply about 旧名字 was dropped on the floor")
    }

    // MARK: - a known-taken title

    func testATakenTitleKeepsTheCreateItemOff() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating, taken: true)
        try await waitUntil { vm.titleVerdict == .taken }
        XCTAssertFalse(vm.canSubmit)
        XCTAssertNil(vm.draft, "a draft that cannot be posted is not a draft")
        await vm.save()
        XCTAssertTrue(creating.createRequests.isEmpty, "the taken name never reaches the POST")
        XCTAssertEqual(vm.errorText, hx("session.create.title.taken"))
        XCTAssertFalse(vm.created)
    }

    /// A check that failed clears nothing: the contract blocks rather than guesses (`SessionCreating`).
    func testADuplicateCheckThatFailedBlocksSubmitAndSaysWhy() async throws {
        let creating = FakeSessionCreating()
        creating.choicesReplies = [.success(CreateFixtures.bothGroups())]
        creating.titleCheckReplies = [.failure(.offline)]
        let vm = makeVM(creating)
        await vm.loadChoices()
        vm.title = "夜间汇总"
        vm.selectionKey = CreateFixtures.agent(7).selectionKey
        try await waitUntil { vm.titleVerdict != .checking }
        XCTAssertEqual(vm.titleVerdict, .checkFailed(hx("error.offline")))
        XCTAssertFalse(vm.canSubmit)
        await vm.save()
        XCTAssertTrue(creating.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("error.offline"))
    }

    func testASaveBeforeTheNameIsClearedSaysSoWithoutReachingTheStack() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating)
        XCTAssertFalse(vm.canSubmit, "the check has not even landed yet")
        await vm.save()
        XCTAssertTrue(creating.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("session.create.title.pending"))
    }

    // MARK: - the description

    func testTheDescriptionCeilingIsAClientRuleTheDTODoesNotHave() {
        let vm = makeVM()
        vm.title = "夜间汇总"
        vm.selectionKey = CreateFixtures.agent(7).selectionKey
        XCTAssertEqual(SessionCreateViewModel.descriptionLimit, SessionCreateDraft.descriptionLimit)
        vm.note = String(repeating: "说", count: SessionCreateViewModel.descriptionLimit)
        XCTAssertNil(vm.validationErrorKey)
        vm.note = String(repeating: "说", count: SessionCreateViewModel.descriptionLimit + 1)
        XCTAssertEqual(vm.validationErrorKey, "session.create.note.long")
    }

    func testAnUntouchedDescriptionIsSentAsNothingAtAll() async throws {
        let vm = await filled(FakeSessionCreating())
        try await waitUntilAvailable(vm)
        let draft = try XCTUnwrap(vm.draft)
        XCTAssertNil(draft.sessionDescription, "an empty box is an omission, not an empty string")
        vm.note = "夜里跑一次"
        XCTAssertEqual(vm.draft?.sessionDescription, "夜里跑一次")
        vm.note = "   "
        XCTAssertNil(vm.draft?.sessionDescription)
    }

    // MARK: - the executor picker

    func testNoExecutorMeansNoSubmit() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating)
        vm.selectionKey = nil
        XCTAssertEqual(vm.validationErrorKey, "session.create.executor.required")
        XCTAssertFalse(vm.canSubmit)
        await vm.save()
        XCTAssertTrue(creating.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("session.create.executor.required"))
    }

    func testTheGroupsAreKeptApartSoOneFailureIsNotBoth() async throws {
        let vm = await filled(FakeSessionCreating(), choices: CreateFixtures.teamsMissing())
        XCTAssertEqual(vm.agents.count, 1)
        XCTAssertTrue(vm.teams.isEmpty)
        XCTAssertEqual(vm.choices?.unavailableKinds, [.team])
        XCTAssertTrue(vm.executorSourceIsUsable, "the group that answered is still pickable")
        XCTAssertEqual(
            vm.unavailableNotice,
            hx("session.create.unavailable", hx("session.create.kind.team")),
            "the form says which half is missing instead of showing an empty group"
        )
    }

    func testASubmitFromAPartialPickerStillGoesOut() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating, choices: CreateFixtures.teamsMissing())
        try await waitUntilAvailable(vm)
        XCTAssertTrue(vm.canSubmit)
        creating.createReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(creating.createRequests.count, 1)
        XCTAssertNil(vm.errorText)
        XCTAssertTrue(vm.created)
    }

    func testBothGroupsFailingRefusesWithTheReasonOnScreen() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating, choices: CreateFixtures.bothMissing())
        XCTAssertFalse(vm.executorSourceIsUsable)
        XCTAssertFalse(vm.canSubmit)
        await vm.save()
        XCTAssertTrue(creating.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, vm.unavailableNotice)
        XCTAssertNotNil(vm.errorText, "a form with nothing to pick has to name the groups that are down")
    }

    func testAnEmptyPickerThatLoadedFineStillRefuses() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating, choices: SessionExecutorChoices())
        XCTAssertNil(vm.unavailableNotice, "nothing failed, there simply is no executor yet")
        await vm.save()
        XCTAssertTrue(creating.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("session.create.executor.none"))
    }

    func testAPickerThatCannotLoadFailsOnTheFieldNotOnTheSheet() async throws {
        let creating = FakeSessionCreating()
        creating.choicesReplies = [.failure(.offline)]
        let vm = makeVM(creating)
        await vm.loadChoices()
        XCTAssertEqual(vm.choicesErrorText, hx("error.offline"))
        XCTAssertNil(vm.errorText, "a form that cannot list executors has not failed to save")
        XCTAssertFalse(vm.executorSourceIsUsable)
    }

    /// The console loads its two lists only after the modal opens (`SettingsModal.tsx:64-86`) and the title box
    /// is usable the whole time; a picker read that gated typing would be a slower version of the same screen.
    func testTheExecutorReadDoesNotHoldUpTheTitleBox() async throws {
        let creating = FakeSessionCreating()
        creating.gateChoices = true
        creating.titleCheckReplies = [.success(false)]
        let vm = makeVM(creating)
        vm.title = "夜间汇总"
        let loader = Task { await vm.loadChoices() }
        try await waitUntil { creating.choicesRequests == 1 }
        try await waitUntil { vm.titleVerdict == .available }
        XCTAssertEqual(creating.titleCheckRequests, ["夜间汇总"], "the check went out and answered meanwhile")
        XCTAssertNil(vm.choices, "the picker is still out")
        creating.choicesReplies = [.success(CreateFixtures.bothGroups())]
        creating.releaseChoices()
        await loader.value
        XCTAssertEqual(vm.agents.count, 1)
    }

    func testThePickerIsReadOnceAndTheSelectionMapsBackToItsOption() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating)
        vm.selectionKey = CreateFixtures.team(4).selectionKey
        await vm.loadChoices()
        XCTAssertEqual(creating.choicesRequests, 1, "a picker that already answered is not asked again")
        XCTAssertEqual(vm.executor?.kind, .team)
        XCTAssertEqual(vm.executor?.id, 4)
    }

    /// A selection whose group failed to load cannot leave a phantom executor in the body.
    func testASelectionTheLoadedGroupsDoNotContainIsNoExecutor() async throws {
        let creating = FakeSessionCreating()
        creating.choicesReplies = [.success(CreateFixtures.teamsMissing())]
        let vm = makeVM(creating)
        vm.selectionKey = CreateFixtures.team(4).selectionKey
        await vm.loadChoices()
        XCTAssertNil(vm.executor)
        XCTAssertNil(vm.draft)
    }

    // MARK: - agent XOR team

    func testAnAgentSendsAgentIdAndLeavesTeamIdOff() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating)
        try await waitUntilAvailable(vm)
        let draft = try XCTUnwrap(vm.draft)
        XCTAssertEqual(draft.agentId, 7)
        XCTAssertNil(draft.teamId)
        await vm.save()
        let sent = try XCTUnwrap(creating.createRequests.first)
        XCTAssertEqual(sent.agentId, 7)
        XCTAssertNil(sent.teamId)
        XCTAssertEqual(sent.title, "夜间汇总")
    }

    func testATeamSendsTeamIdAndLeavesAgentIdOff() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating)
        vm.selectionKey = CreateFixtures.team(4).selectionKey
        try await waitUntilAvailable(vm)
        await vm.save()
        let sent = try XCTUnwrap(creating.createRequests.first)
        XCTAssertNil(sent.agentId, "the service leaves agent_id NULL for a team conversation on purpose")
        XCTAssertEqual(sent.teamId, 4)
    }

    /// The reason the selection key carries the kind at all: the two groups come from two routes and an agent
    /// and a team can be numbered the same.
    func testAnAgentAndATeamWithTheSameNumberAreStillTwoChoices() async throws {
        let creating = FakeSessionCreating()
        let clashes = SessionExecutorChoices(
            agents: [CreateFixtures.agent(7, "同一个号")],
            teams: [CreateFixtures.team(7, "也是这个号")]
        )
        let vm = await filled(creating, choices: clashes)
        try await waitUntilAvailable(vm)
        vm.selectionKey = "team:7"
        await vm.save()
        XCTAssertEqual(creating.createRequests.first?.teamId, 7)
        XCTAssertNil(creating.createRequests.first?.agentId)
    }

    func testATypedTitleIsTrimmedBeforeItGoesOut() async throws {
        let creating = FakeSessionCreating()
        let vm = await filled(creating, title: "  夜间汇总  ")
        try await waitUntilAvailable(vm)
        await vm.save()
        XCTAssertEqual(creating.createRequests.first?.title, "夜间汇总")
    }

    // MARK: - success, and the one thing it can report

    func testASuccessCallsOnCreatedExactlyOnceAndReportsNoId() async throws {
        let creating = FakeSessionCreating()
        var calls = 0
        let vm = await filled(creating, onCreated: { calls += 1 })
        creating.createReplies = [.success(EmptyResponse())]
        try await waitUntilAvailable(vm)
        XCTAssertTrue(vm.canSubmit)
        await vm.save()
        XCTAssertTrue(vm.created)
        XCTAssertNil(vm.errorText)
        XCTAssertEqual(calls, 1)
        await vm.save()
        XCTAssertEqual(calls, 1, "a sheet that has already created does not create twice")
        XCTAssertEqual(creating.createRequests.count, 1)
    }

    func testATapWhileTheCreateIsOutstandingIsNotASecondCreate() async throws {
        let creating = FakeSessionCreating()
        var calls = 0
        let vm = await filled(creating, onCreated: { calls += 1 })
        try await waitUntilAvailable(vm)
        creating.gateWrites = true
        creating.createReplies = [.success(EmptyResponse())]
        let first = Task { await vm.save() }
        try await waitUntil { creating.createRequests.count == 1 }
        await vm.save()
        XCTAssertEqual(creating.createRequests.count, 1, "the sheet is mid-write")
        creating.releaseWrites()
        await first.value
        XCTAssertEqual(calls, 1)
        XCTAssertTrue(vm.created)
    }

    // MARK: - failure keeps what was typed

    func testARefusedCreateKeepsTheDraftAndInvokesNothing() async throws {
        let creating = FakeSessionCreating()
        var calls = 0
        let vm = await filled(creating, onCreated: { calls += 1 })
        try await waitUntilAvailable(vm)
        vm.note = "夜里跑一次"
        creating.createReplies = [.failure(.business(code: 500, message: "Failed to create session"))]
        await vm.save()
        XCTAssertFalse(vm.created)
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(vm.errorText, "Failed to create session")
        XCTAssertEqual(vm.title, "夜间汇总", "a banner must not cost the user their typing")
        XCTAssertEqual(vm.note, "夜里跑一次")
        XCTAssertEqual(vm.selectionKey, CreateFixtures.agent(7).selectionKey)
        XCTAssertNotNil(vm.draft, "and the same form can be sent again")
        XCTAssertFalse(vm.isSaving)
    }

    // MARK: - helpers

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<600 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

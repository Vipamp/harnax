import XCTest
import HarnaxAPI
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

@MainActor
final class AppModelTests: XCTestCase {
    private func makeModel(
        _ auth: FakeAuth = FakeAuth(),
        biometrics: any BiometricUnlocking = NoBiometricUnlock(),
        gateEnabled: Bool = false
    ) -> (AppModel, FakeAuth) {
        // Nothing here opens the context tab or the scheduled-task column, so those catalogs share one
        // failing double; the chat and system tabs have their own.
        let unwired = UnwiredCatalogs()
        let chat = UnwiredChat()
        let system = UnwiredSystem()
        let saving = UnwiredSaving()
        let model = AppModel(dependencies: HarnaxDependencies(
            auth: auth,
            agents: FakeAgents(),
            teams: FakeTeams(),
            executor: chat,
            agentWrite: saving,
            teamWrite: saving,
            tasks: unwired,
            sessionRefresher: FakeRefresher(),
            models: unwired,
            tools: unwired,
            mcp: unwired,
            skills: unwired,
            clis: unwired,
            envVars: system,
            apiKeys: system,
            channels: system,
            tokenStats: system,
            sessions: chat,
            sessionCreate: chat,
            sessionConfig: chat,
            workspace: chat,
            teamArtifacts: chat,
            chatHistory: chat,
            plan: chat,
            contextUsage: chat,
            commands: chat,
            streaming: chat
        ), biometrics: biometrics, gateEnabled: gateEnabled)
        return (model, auth)
    }

    func testLaunchDoesNotClaimAnyoneIsSignedInBeforeTheKeychainIsRead() {
        let (model, _) = makeModel()
        XCTAssertTrue(model.isRestoring)
        XCTAssertFalse(model.isSignedIn)
    }

    func testRestoreLandsOnTheAccountTheKeychainHolds() async {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin", tenantID: 1))
        await model.restore()
        XCTAssertEqual(model.account?.username, "admin")
        XCTAssertFalse(model.isRestoring)
    }

    func testRestoreRunsOnceAndALaterSessionChangeComesThroughSync() async {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        auth.authState = .signedOut
        await model.restore()
        XCTAssertTrue(model.isSignedIn, "restore only fills the unknown state")
        await model.sync()
        XCTAssertFalse(model.isSignedIn, "sync is how an ended session reaches the root")
    }

    func testSyncPicksUpASessionEndedElsewhere() async {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.sync()
        XCTAssertTrue(model.isSignedIn)
        auth.authState = .signedOut
        await model.sync()
        XCTAssertFalse(model.isSignedIn)
    }

    func testSigningOutClearsTheSessionAndReturnsToTheList() async {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.sync()
        model.tab = .me
        await model.signOut()
        XCTAssertEqual(auth.logoutCalls, 1)
        XCTAssertFalse(model.isSignedIn)
        XCTAssertEqual(model.tab, .agents)
    }

    func testARejectedSignInLeavesTheRootSignedOut() async {
        let (model, auth) = makeModel()
        auth.loginResult = .failure(.offline)
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "x"
        await vm.submit()
        await model.sync()
        XCTAssertFalse(model.isSignedIn)
        XCTAssertNotNil(vm.errorText)
    }

    func testASuccessfulSignInFlipsTheRootThroughTheSameSync() async {
        let (model, auth) = makeModel()
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "admin123"
        await vm.submit()
        await model.sync()
        XCTAssertTrue(model.isSignedIn)
    }

    // MARK: - the launch guard

    func testTheGuardSitsInFrontOfASessionTheKeychainAlreadyHolds() async {
        let (model, auth) = makeModel(biometrics: ScriptedBiometrics(), gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertTrue(model.isAwaitingBiometric)
        XCTAssertFalse(model.isSignedIn, "the gate is what the root shows first")
        XCTAssertEqual(auth.logoutCalls, 0, "a guard is not a sign-out — the stored token stays put")
    }

    func testNoGuardWhenTheDeviceHasNoBiometry() async {
        let (model, auth) = makeModel(biometrics: NoBiometricUnlock(), gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertFalse(model.isAwaitingBiometric)
        XCTAssertTrue(model.isSignedIn)
        XCTAssertFalse(model.biometricsAvailable, "and the settings switch has nothing to promise")
    }

    func testNoGuardWhenTheSwitchIsOff() async {
        let (model, auth) = makeModel(biometrics: ScriptedBiometrics(), gateEnabled: false)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertFalse(model.isAwaitingBiometric)
        XCTAssertTrue(model.isSignedIn)
        XCTAssertTrue(model.biometricsAvailable, "off is a choice, not a missing capability")
    }

    func testASuccessfulUnlockOpensTheSessionAndCarriesTheScreensCopy() async {
        let biometrics = ScriptedBiometrics()
        let (model, auth) = makeModel(biometrics: biometrics, gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        await model.unlockWithBiometrics(reason: "解锁 Harnax")
        XCTAssertEqual(biometrics.reasons, ["解锁 Harnax"], "the prompt text belongs to the screen")
        XCTAssertTrue(model.isSignedIn)
        XCTAssertFalse(model.isAwaitingBiometric)
        XCTAssertNil(model.biometricFailure)
    }

    func testACancelKeepsTheGateAndSaysNothing() async {
        let biometrics = ScriptedBiometrics(result: .failure(.cancelled))
        let (model, auth) = makeModel(biometrics: biometrics, gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        await model.unlockWithBiometrics(reason: "r")
        XCTAssertTrue(model.isAwaitingBiometric, "tapping away is not an answer, just a pause")
        XCTAssertNil(model.biometricFailure)
    }

    func testAPasswordFallbackKeepsTheGateAndSaysNothing() async {
        let biometrics = ScriptedBiometrics(result: .failure(.passwordFallback))
        let (model, auth) = makeModel(biometrics: biometrics, gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        await model.unlockWithBiometrics(reason: "r")
        XCTAssertNil(model.biometricFailure, "the form below the gate is exactly what that tap means")
        XCTAssertTrue(model.isAwaitingBiometric)
    }

    func testAFailedUnlockKeepsTheGateAndSaysSo() async {
        let biometrics = ScriptedBiometrics(result: .failure(.failed))
        let (model, auth) = makeModel(biometrics: biometrics, gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        await model.unlockWithBiometrics(reason: "r")
        XCTAssertEqual(model.biometricFailure, .failed)
        XCTAssertTrue(model.isAwaitingBiometric)
        XCTAssertFalse(model.isSignedIn)
    }

    func testBiometryLostMidFlightWithdrawsTheGate() async {
        let biometrics = ScriptedBiometrics(result: .failure(.unavailable))
        let (model, auth) = makeModel(biometrics: biometrics, gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        await model.unlockWithBiometrics(reason: "r")
        XCTAssertEqual(model.biometricFailure, .unavailable)
        XCTAssertFalse(model.isAwaitingBiometric, "a control that cannot deliver has to make way for the form")
        XCTAssertFalse(model.isSignedIn)
    }

    func testAPasswordSignInAnswersTheGate() async {
        let (model, auth) = makeModel(biometrics: ScriptedBiometrics(), gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertTrue(model.isAwaitingBiometric)
        await model.answerGate()
        XCTAssertFalse(model.isAwaitingBiometric)
        XCTAssertTrue(model.isSignedIn)
    }

    func testAReSignInWithoutCredentialsDoesNotOpenTheGate() async {
        let (model, auth) = makeModel(biometrics: ScriptedBiometrics(), gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        // What the screen does after a submit that never exchanged — blank fields, or a refused
        // password: the keychain still holds the old session, and re-reading it is not an answer.
        await model.sync()
        XCTAssertTrue(model.isAwaitingBiometric, "the gate has two answers, and a failed attempt is neither")
        XCTAssertFalse(model.isSignedIn)
    }

    func testSigningOutWithdrawsTheGate() async {
        let (model, auth) = makeModel(biometrics: ScriptedBiometrics(), gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        await model.signOut()
        XCTAssertFalse(model.isAwaitingBiometric)
        XCTAssertNil(model.biometricFailure)
    }

    // MARK: - a session that ended underneath a screen

    /// The address sheet is pushed from a tab the root has no reason to re-read, so a session ended by
    /// moving the stack has to announce itself — otherwise the shell keeps showing an account the keychain
    /// no longer holds, and every screen below it fails on its own schedule.
    func testTheRootDropsToSignInWhenTheAddressChangeEndsTheSession() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertTrue(model.isSignedIn)

        auth.authState = .signedOut
        NotificationCenter.default.post(name: .harnaxCredentialsDropped, object: nil)

        // The handler hops onto the main actor, so the queued re-read has to be given its turn.
        var settled = false
        for _ in 0..<40 {
            if !model.isSignedIn {
                settled = true
                break
            }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTAssertTrue(settled, "the root re-read the session the address change ended")
        XCTAssertNil(model.account, "and it stopped showing an account the keychain no longer holds")
    }

    /// A session read is a suspension on the main actor, so the sign-out button really can be answered while
    /// one is outstanding. The read left on the old session number has no say left: publishing its answer would
    /// swap the tab bar back over the top of the sign-out, showing an account with no bearer behind it.
    func testASessionReadThatLandsAfterTheSignOutCannotPutTheAccountBack() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertTrue(model.isSignedIn)

        auth.stateGate.arm()
        let reading = Task { await model.sync() }
        try await waitUntil { auth.stateCalls == 2 }
        XCTAssertEqual(auth.stateCalls, 2, "the read is parked with its answer already taken")
        await model.signOut()
        auth.stateGate.release()
        _ = await reading.value

        XCTAssertFalse(model.isSignedIn, "the answer belongs to a session the operator ended")
        XCTAssertNil(model.account)
        let parked = try XCTUnwrap(auth.stateAnswers.last)
        XCTAssertNotNil(parked.account, "the read did hand back an account — the number is what dropped it")
        XCTAssertEqual(auth.logoutCalls, 1, "and the sign-out still happened exactly once")
    }

    /// The same read refused in the other direction. A sign-out settles the root itself, so a read that left
    /// before it has nothing left to report in either direction: an operator who signs straight back in would
    /// otherwise be bounced to the credential form by a `.signedOut` answer that names a session already
    /// closed twice over.
    func testASessionReadThatLandsAfterASecondSignInCannotBounceTheRootOut() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertTrue(model.isSignedIn)

        auth.authState = .signedOut
        auth.stateGate.arm()
        let reading = Task { await model.sync() }
        try await waitUntil { auth.stateCalls == 2 }
        await model.signOut()

        let login = LoginViewModel(auth: auth)
        login.username = "admin"
        login.password = "admin123"
        await login.submit()
        await model.sync()
        XCTAssertTrue(model.isSignedIn, "the new session is on screen before the old read answers")

        auth.stateGate.release()
        _ = await reading.value

        XCTAssertTrue(model.isSignedIn, "the sign-in the operator is looking at survives a retired read")
        XCTAssertEqual(model.account?.username, "admin")
        XCTAssertEqual(auth.stateAnswers.count, 3, "launch, the parked read, the re-sync after the sign-in")
        XCTAssertEqual(auth.stateAnswers[1], .signedOut, "the parked read did say signed out")
    }

    /// `.unknown` is the keychain declining to answer, not a verdict. Published over a settled session it puts
    /// the launch placeholder back on screen, which discards `LoginView` with everything typed into it — so a
    /// refused re-read leaves the state the user is looking at alone.
    func testARefusedSessionReadDoesNotPutTheLaunchPlaceholderBack() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.sync()
        XCTAssertTrue(model.isSignedIn)

        auth.authState = .unknown
        auth.stateGate.arm()
        let reading = Task { await model.sync() }
        try await waitUntil { auth.stateCalls == 2 }
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        auth.stateGate.release()
        _ = await reading.value

        XCTAssertFalse(model.isRestoring, "a read that gave no verdict is not allowed to reset the root")
        XCTAssertTrue(model.isSignedIn)
        let parked = try XCTUnwrap(auth.stateAnswers.last)
        XCTAssertEqual(parked, .unknown, "the refused read happened; the root just does not wear it")
    }

    /// The complement of the case above, and the one with no way out: the placeholder is a promise about one
    /// read in flight, so a launch read the keychain refuses has to hand the root over to something. It answers
    /// `.unknown` for any status short of `itemNotFound` — `-34018`, which is what a build carrying no
    /// `application-identifier` gets, measured on the simulator — and writing that back over the placeholder
    /// left 「加载中…」 on screen as the whole app, with no retry and no entry.
    func testARefusedLaunchReadHandsTheRootToTheSignIn() async throws {
        let (model, auth) = makeModel()
        auth.authState = .unknown
        XCTAssertTrue(model.isRestoring, "the root is on the placeholder until the launch read answers")

        await model.restore()

        XCTAssertFalse(model.isRestoring, "one refused read cannot keep the placeholder")
        XCTAssertFalse(model.isSignedIn, "and it claims no account it never read")
        await model.restore()
        XCTAssertEqual(auth.stateCalls, 1, "the fallback is that answer, not a retry loop")
    }

    /// The unlock suspends the main actor while the system dialog is up, so a second tap used to reach
    /// `LAContext` a second time: two prompts for one touch, and two answers fighting over the gate and the
    /// banner. One prompt stays open until it is answered.
    func testOneUnlockPromptIsOpenAtATime() async throws {
        let biometrics = ScriptedBiometrics(result: .failure(.failed))
        let (model, auth) = makeModel(biometrics: biometrics, gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        XCTAssertTrue(model.isAwaitingBiometric)

        biometrics.gate.arm()
        let first = Task { await model.unlockWithBiometrics(reason: "r") }
        try await waitUntil { biometrics.attempts == 1 }
        await model.unlockWithBiometrics(reason: "r")
        XCTAssertEqual(biometrics.attempts, 1, "a tap while the dialog is up asks the system nothing")
        biometrics.gate.release()
        await first.value

        XCTAssertEqual(biometrics.reasons, ["r"])
        XCTAssertEqual(model.biometricFailure, .failed, "and one outcome is reported, once")
        XCTAssertTrue(model.isAwaitingBiometric, "the gate is still standing for the form")
        XCTAssertFalse(model.isSignedIn)
    }

    // MARK: - the cold-start reconcile (DESIGN.md:125)

    /// 「冷启动用 `GET /api/admin/auth/me` 恢复登录态与租户，不靠本地缓存判身份」 — the keychain answer is a
    /// placeholder the server has to confirm, not the verdict the root settles on. Without this leg a card
    /// renamed, demoted or re-tenanted since the last launch shows as the current identity until somebody
    /// opens 「我的」, which is the only other place `profile()` is reached.
    func testColdRestoreReconcilesTheCachedCardThroughAuthMe() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin", tenantID: 1))
        try auth.seedProfile(["username": "admin", "nickname": "Vipamp", "isAdmin": 1])

        await model.restore()

        XCTAssertEqual(auth.profileCalls, 1, "launch asks the server who this is")
        XCTAssertEqual(model.account?.nickname, "Vipamp", "and wears the answer rather than the blob")
        XCTAssertTrue(model.account?.isAdministrator ?? false)
        XCTAssertEqual(model.account?.tenantID, 1, "this payload answers no tenant, so the cached one carries over")
    }

    /// The other half of judging identity from the server: a 401 on that read means the bearer has no future,
    /// and `AuthFlow.profile()` ends the session for it (`AuthFlow.swift:112`). The root has to publish that
    /// answer, or the shell greets an account by name while every screen below it fails on its own schedule.
    func testA401OnTheColdStartReconcileEndsTheSession() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin", tenantID: 1))
        auth.profileResult = .failure(.unauthorized)

        await model.restore()

        XCTAssertFalse(model.isSignedIn, "the server said this session is gone")
        XCTAssertNil(model.account, "and the root stopped naming it")
    }

    /// A reconcile that could not be made is not a verdict about the identity. Offline, a timeout or a business
    /// error all leave the cached card standing — otherwise a cold start on a train tunnel would throw away a
    /// session the keychain still holds valid and demand a password the user has no reason to retype.
    func testAReconcileThatReachedNoServerLeavesTheCachedIdentityStanding() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin", nickname: "Vipamp", tenantID: 1))
        auth.profileResult = .failure(.offline)

        await model.restore()

        XCTAssertEqual(auth.profileCalls, 1, "the read still went out")
        XCTAssertTrue(model.isSignedIn)
        XCTAssertEqual(model.account?.nickname, "Vipamp", "and the placeholder is what stays on screen")
    }

    /// Nobody signed in means nobody to reconcile: `me` would be an unauthenticated call whose 401 the root
    /// would then publish as an answer about a session that never existed.
    func testRestoreSendsNoProfileRequestWhenTheKeychainHoldsNoSession() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedOut

        await model.restore()

        XCTAssertFalse(model.isSignedIn)
        XCTAssertEqual(auth.profileCalls, 0, "the credential form needs no identity read")
    }

    /// The gate stands in front of the same session, so it gets the same reconcile — when it opens. Reconciling
    /// behind a prompt would spend a network round on a session the owner has not been allowed to see yet.
    func testTheGateReconcilesOnceItIsAnswered() async throws {
        let (model, auth) = makeModel(biometrics: ScriptedBiometrics(), gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin", tenantID: 1))
        try auth.seedProfile(["username": "admin", "nickname": "Vipamp"])

        await model.restore()
        XCTAssertEqual(auth.profileCalls, 0, "nothing is reconciled while the gate is still the answer")
        XCTAssertTrue(model.isAwaitingBiometric)

        await model.answerGate()
        XCTAssertEqual(auth.profileCalls, 1)
        XCTAssertEqual(model.account?.nickname, "Vipamp")
    }

    /// The reconcile is a suspension on the main actor, so the sign-out button really can be answered while it
    /// is parked. Its re-read left on the old session number has no say — the same rule `publish` already holds
    /// for the launch read, applied to the second read this method adds.
    func testASignOutDuringTheReconcileIsNotOverwritten() async throws {
        let (model, auth) = makeModel()
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        try auth.seedProfile(["username": "admin", "nickname": "Vipamp"])
        auth.profileGate.arm()

        let restoring = Task { await model.restore() }
        try await waitUntil { auth.profileCalls == 1 }
        await model.signOut()
        auth.profileGate.release()
        _ = await restoring.value

        XCTAssertFalse(model.isSignedIn, "the reconcile answers a session the operator closed")
        XCTAssertNil(model.account)
        XCTAssertEqual(model.tab, .agents, "and the sign-out's own answers stand")
    }

    /// Polls a condition the fake records on, so the test can act while a read really is in flight. The
    /// 400 × 5 ms budget is the same shape the other suites' helpers use.
    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

/// The guard's seam under test control: one canned outcome per attempt, and the prompt texts it was asked
/// to show. File scope so the async requirement is not main-actor isolated.
private final class ScriptedBiometrics: BiometricUnlocking, @unchecked Sendable {
    private let result: Result<Void, BiometricUnlockFailure>
    private(set) var reasons: [String] = []
    /// How many prompts actually reached the seam — one tap sequence must not produce two.
    private(set) var attempts = 0
    /// Holds the next prompt open while the test taps again, on the same `arm`/`absorb`/`release` triad as
    /// `PageReadGate`.
    let gate = PageReadGate<Result<Void, BiometricUnlockFailure>>()

    init(available: Bool = true, result: Result<Void, BiometricUnlockFailure> = .success(())) {
        self.available = available
        self.result = result
    }

    var isAvailable: Bool { available }
    private let available: Bool

    func unlock(reason: String) async -> Result<Void, BiometricUnlockFailure> {
        reasons.append(reason)
        attempts += 1
        let reply = result
        return await gate.absorb(reply)
    }
}

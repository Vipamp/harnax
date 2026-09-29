import XCTest
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
        model.tab = .system
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
        await model.sync()
        XCTAssertFalse(model.isAwaitingBiometric)
        XCTAssertTrue(model.isSignedIn)
    }

    func testSigningOutWithdrawsTheGate() async {
        let (model, auth) = makeModel(biometrics: ScriptedBiometrics(), gateEnabled: true)
        auth.authState = .signedIn(AccountSnapshot(username: "admin"))
        await model.restore()
        await model.signOut()
        XCTAssertFalse(model.isAwaitingBiometric)
        XCTAssertNil(model.biometricFailure)
    }
}

/// The guard's seam under test control: one canned outcome per attempt, and the prompt texts it was asked
/// to show. File scope so the async requirement is not main-actor isolated.
private final class ScriptedBiometrics: BiometricUnlocking, @unchecked Sendable {
    private let result: Result<Void, BiometricUnlockFailure>
    private(set) var reasons: [String] = []

    init(available: Bool = true, result: Result<Void, BiometricUnlockFailure> = .success(())) {
        self.available = available
        self.result = result
    }

    var isAvailable: Bool { available }
    private let available: Bool

    func unlock(reason: String) async -> Result<Void, BiometricUnlockFailure> {
        reasons.append(reason)
        return result
    }
}

import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

@MainActor
final class AppModelTests: XCTestCase {
    private func makeModel(_ auth: FakeAuth = FakeAuth()) -> (AppModel, FakeAuth) {
        // Nothing here opens the context tab or the scheduled-task column, so those catalogs share one
        // failing double; the chat and system tabs have their own.
        let unwired = UnwiredCatalogs()
        let chat = UnwiredChat()
        let system = UnwiredSystem()
        let model = AppModel(dependencies: HarnaxDependencies(
            auth: auth,
            agents: FakeAgents(),
            teams: FakeTeams(),
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
            chatHistory: chat,
            commands: chat,
            streaming: chat
        ))
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
}

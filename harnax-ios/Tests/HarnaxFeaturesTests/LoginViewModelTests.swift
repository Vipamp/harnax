import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// Copy is compared against `hx(...)` rather than an English or Chinese literal, because another target
/// in the same process flips the catalogue mid-run.
@MainActor
final class LoginViewModelTests: XCTestCase {
    func testEmptyFieldsNeverLeaveTheDevice() async {
        let auth = FakeAuth()
        let vm = LoginViewModel(auth: auth)
        await vm.submit()
        XCTAssertEqual(vm.errorText, hx("login.needCredentials"))
        XCTAssertTrue(auth.loginCalls.isEmpty)
    }

    func testAPasswordAloneIsStillAnIncompleteForm() async {
        let auth = FakeAuth()
        let vm = LoginViewModel(auth: auth)
        vm.password = "admin123"
        await vm.submit()
        XCTAssertTrue(auth.loginCalls.isEmpty)
    }

    func testSurroundingSpacesAreTrimmedOffTheUsername() async {
        let auth = FakeAuth()
        let vm = LoginViewModel(auth: auth)
        vm.username = "  admin  "
        vm.password = "admin123"
        await vm.submit()
        XCTAssertEqual(auth.loginCalls.map(\.username), ["admin"])
        XCTAssertEqual(vm.signedInAccount?.username, "admin")
    }

    func testASuccessfulSignInClearsThePasswordFromTheField() async {
        let vm = LoginViewModel(auth: FakeAuth())
        vm.username = "admin"
        vm.password = "admin123"
        await vm.submit()
        XCTAssertNotNil(vm.signedInAccount)
        XCTAssertTrue(vm.password.isEmpty)
        XCTAssertNil(vm.errorText)
    }

    func testServerTextBeatsTheClientDictionary() async {
        let auth = FakeAuth()
        auth.loginResult = .failure(.business(code: 400, message: "用户名或密码错误"))
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "wrong"
        await vm.submit()
        XCTAssertEqual(vm.errorText, "用户名或密码错误")
        XCTAssertNil(vm.signedInAccount)
    }

    func testThrottlingReportsTheRemainingSeconds() async {
        let auth = FakeAuth()
        auth.loginResult = .failure(.throttled(seconds: 30))
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "x"
        await vm.submit()
        XCTAssertEqual(vm.errorText, hx("error.throttled", 30))
    }

    func testARetypingRequestEmptiesThePasswordField() async {
        let auth = FakeAuth()
        auth.loginResult = .failure(.refillPassword)
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "whatever-is-already-there"
        await vm.submit()
        XCTAssertTrue(vm.password.isEmpty)
        XCTAssertEqual(vm.errorText, hx("error.refillPassword"))
    }

    func testARejectedCredentialDoesNotClearTheUsername() async {
        let auth = FakeAuth()
        auth.loginResult = .failure(.offline)
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "admin123"
        await vm.submit()
        XCTAssertEqual(vm.username, "admin")
        XCTAssertEqual(vm.password, "admin123", "an unreachable server is not the user's typo")
    }

    func testTheErrorLineClearsOnTheNextAttempt() async {
        let auth = FakeAuth()
        auth.loginResult = .failure(.offline)
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "admin123"
        await vm.submit()
        XCTAssertNotNil(vm.errorText)
        auth.loginResult = .success(AccountSnapshot(username: "admin"))
        await vm.submit()
        XCTAssertNil(vm.errorText)
    }

    func testTheServerLineFollowsTheConfiguredStack() async {
        let vm = LoginViewModel(auth: FakeAuth())
        await vm.reload()
        XCTAssertEqual(
            vm.serverLine,
            ServerAddressSummary.line(admin: "http://127.0.0.1:28080", router: "http://127.0.0.1:28081")
        )
    }

    func testAnUnreadableConfigurationLeavesTheLineEmptyRatherThanWrong() async {
        let auth = FakeAuth()
        auth.serverConfigurationResult = .failure(.decoding)
        let vm = LoginViewModel(auth: auth)
        await vm.reload()
        XCTAssertTrue(vm.serverLine.isEmpty)
        XCTAssertEqual(vm.errorText, hx("error.decoding"))
    }
}

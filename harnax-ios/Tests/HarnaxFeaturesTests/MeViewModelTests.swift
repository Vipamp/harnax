import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

@MainActor
final class MeViewModelTests: XCTestCase {
    func testReloadReadsBothTheAccountAndTheConfiguredStack() async throws {
        let auth = FakeAuth()
        try auth.seedProfile(["username": "admin", "isAdmin": 1])
        let vm = MeViewModel(auth: auth)
        await vm.reload()
        XCTAssertEqual(auth.profileCalls, 1)
        XCTAssertNil(vm.errorText)
        XCTAssertEqual(
            vm.serverLine,
            ServerAddressSummary.line(admin: "http://127.0.0.1:28080", router: "http://127.0.0.1:28081")
        )
    }

    func testAServerRejectionIsSurfacedAsItsOwnText() async {
        let auth = FakeAuth()
        auth.profileResult = .failure(.business(code: 403, message: "无权访问"))
        let vm = MeViewModel(auth: auth)
        await vm.reload()
        XCTAssertEqual(vm.errorText, "无权访问")
    }

    func testAnExpiredSessionUsesTheSignInCopyNotServerText() async {
        let auth = FakeAuth()
        auth.profileResult = .failure(.unauthorized)
        let vm = MeViewModel(auth: auth)
        await vm.reload()
        XCTAssertEqual(vm.errorText, hx("error.unauthorized"))
    }

    func testTheBannerClearsOnceTheAccountReadsAgain() async throws {
        let auth = FakeAuth()
        auth.profileResult = .failure(.offline)
        let vm = MeViewModel(auth: auth)
        await vm.reload()
        XCTAssertEqual(vm.errorText, hx("error.offline"))
        try auth.seedProfile(["username": "admin"])
        await vm.reload()
        XCTAssertNil(vm.errorText)
    }

    func testAnUnreadableServerConfigLeavesTheRowEmpty() async throws {
        let auth = FakeAuth()
        auth.serverConfigurationResult = .failure(.decoding)
        try auth.seedProfile(["username": "admin"])
        let vm = MeViewModel(auth: auth)
        await vm.reload()
        XCTAssertTrue(vm.serverLine.isEmpty)
    }
}

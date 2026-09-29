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

    /// A picker over one row is a step to nowhere, so the entry itself is what the count decides.
    func testTheSwitchEntryAppearsOnlyWithASecondTenant() async throws {
        let auth = FakeAuth()
        auth.tenantOptionsResult = .success([TenantSummary(id: 1, name: "Default", status: 1)])
        let vm = MeViewModel(auth: auth)
        await vm.reload()
        XCTAssertFalse(vm.canSwitchTenant)

        auth.tenantOptionsResult = .success([
            TenantSummary(id: 1, name: "Default", status: 1),
            TenantSummary(id: 2, name: "Acme Workspace", status: 1),
        ])
        await vm.reload()
        XCTAssertTrue(vm.canSwitchTenant)
    }

    /// A failed read is not the same fact as "this account has one tenant", and the second would hide the
    /// only way into the other one.
    func testAFailedTenantReadKeepsTheRowsItAlreadyHad() async throws {
        let auth = FakeAuth()
        auth.tenantOptionsResult = .success([
            TenantSummary(id: 1, name: "Default", status: 1),
            TenantSummary(id: 2, name: "Acme Workspace", status: 1),
        ])
        let vm = MeViewModel(auth: auth)
        await vm.reload()

        auth.tenantOptionsResult = .failure(.business(code: 500, message: "服务暂不可用"))
        await vm.reload()
        XCTAssertEqual(vm.tenantErrorText, "服务暂不可用")
        XCTAssertEqual(vm.tenants.count, 2)
        XCTAssertTrue(vm.canSwitchTenant)

        auth.tenantOptionsResult = .success([TenantSummary(id: 1, name: "Default", status: 1)])
        await vm.reload()
        XCTAssertNil(vm.tenantErrorText)
    }

    func testSwitchingGivesTheSessionTheRowTheOperatorPicked() async throws {
        let auth = FakeAuth()
        let vm = MeViewModel(auth: auth)
        let target = TenantSummary(id: 2, name: "Acme Workspace", status: 1)

        let moved = await vm.switchTo(target)
        XCTAssertTrue(moved)
        XCTAssertEqual(auth.switchCalls, [target])
    }

    /// A refusal keeps the panel open with its reason; the row the user ticked is still on screen.
    func testARefusedSwitchKeepsThePanelOpenWithItsReason() async throws {
        let auth = FakeAuth()
        auth.switchResult = .failure(.business(code: 403, message: "无权切换到该租户"))
        let vm = MeViewModel(auth: auth)

        let moved = await vm.switchTo(TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        XCTAssertFalse(moved)
        XCTAssertEqual(vm.switchErrorText, "无权切换到该租户")
        XCTAssertFalse(vm.isSwitching)

        auth.switchResult = .success(())
        let second = await vm.switchTo(TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        XCTAssertTrue(second)
        XCTAssertNil(vm.switchErrorText)
    }
}

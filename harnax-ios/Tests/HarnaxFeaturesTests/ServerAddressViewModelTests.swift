import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

@MainActor
final class ServerAddressViewModelTests: XCTestCase {
    func testLoadingFillsBothFieldsFromTheStackInTheKeychain() async {
        let vm = ServerAddressViewModel(auth: FakeAuth())
        await vm.load()
        XCTAssertEqual(vm.adminAddress, "http://127.0.0.1:28080")
        XCTAssertEqual(vm.routerAddress, "http://127.0.0.1:28081")
    }

    func testASecondLoadDoesNotDiscardWhatIsBeingTyped() async {
        let vm = ServerAddressViewModel(auth: FakeAuth())
        await vm.load()
        vm.adminAddress = "http://10.0.0.2:28080"
        await vm.load()
        XCTAssertEqual(vm.adminAddress, "http://10.0.0.2:28080")
    }

    func testAnAddressThisSideCannotParseNeverReachesTheStore() async {
        let auth = FakeAuth()
        let vm = ServerAddressViewModel(auth: auth)
        vm.adminAddress = "10.0.0.1:28080"
        vm.routerAddress = "http://127.0.0.1:28081"
        await vm.save()
        XCTAssertEqual(vm.errorText, hx("error.serverConfig"))
        XCTAssertFalse(vm.saved)
        XCTAssertTrue(auth.savedConfigs.isEmpty)
    }

    func testAHostWithoutASchemeIsRejectedToo() async {
        let vm = ServerAddressViewModel(auth: FakeAuth())
        vm.adminAddress = "harnax.internal"
        vm.routerAddress = "http://127.0.0.1:28081"
        await vm.save()
        XCTAssertFalse(vm.saved)
    }

    func testASaveStoresTheNormalisedPairAndReadsItBack() async {
        let auth = FakeAuth()
        let vm = ServerAddressViewModel(auth: auth)
        vm.adminAddress = " https://harnax.internal:28443/ "
        vm.routerAddress = "https://harnax.internal:28444"
        await vm.save()
        XCTAssertEqual(auth.savedConfigs.map(\.adminBaseURL), ["https://harnax.internal:28443"])
        XCTAssertEqual(vm.adminAddress, "https://harnax.internal:28443")
        XCTAssertTrue(vm.saved)
        XCTAssertNil(vm.errorText)
    }

    func testAFailedWriteClearsTheSuccessFlag() async {
        let auth = FakeAuth()
        auth.saveResult = .failure(.business(code: 500, message: "boom"))
        let vm = ServerAddressViewModel(auth: auth)
        vm.adminAddress = "http://127.0.0.1:28080"
        vm.routerAddress = "http://127.0.0.1:28081"
        await vm.save()
        XCTAssertFalse(vm.saved)
        XCTAssertEqual(vm.errorText, "boom")
    }

    func testTheStoredPairSurvivesAnEditingSessionUnchangedOnFailure() async {
        let auth = FakeAuth()
        auth.saveResult = .failure(.offline)
        let vm = ServerAddressViewModel(auth: auth)
        vm.adminAddress = "http://192.168.1.20:28080"
        vm.routerAddress = "http://192.168.1.20:28081"
        await vm.save()
        XCTAssertEqual(vm.adminAddress, "http://192.168.1.20:28080", "the fields keep the text that failed")
    }
}

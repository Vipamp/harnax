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

    /// The sheet is pushed from `Me`, and a session that ends anywhere — a 401 on another screen, an address
    /// change that drops the old host's bearer — makes the root re-read while the operator is mid-word here.
    /// The pair that answers afterwards belongs to the stack they are leaving, so the emptiness seen on entry
    /// is no longer an answer: what was typed stays in the field, byte for byte.
    func testAPairThatAnswersLateDoesNotOverwriteTheAddressBeingTyped() async throws {
        let auth = FakeAuth()
        let vm = ServerAddressViewModel(auth: auth)
        auth.serverConfigGate.arm()
        let loading = Task { await vm.load() }
        try await waitUntil { auth.serverConfigurationCalls == 1 }
        vm.adminAddress = "https://harnax.internal:28443"
        vm.routerAddress = "https://harnax.internal:28444"
        auth.serverConfigGate.release()
        await loading.value

        XCTAssertEqual(vm.adminAddress, "https://harnax.internal:28443", "the stored pair does not land on the text")
        XCTAssertEqual(vm.routerAddress, "https://harnax.internal:28444")
    }

    /// Same parking, the other direction: a load nobody has typed over yet still fills both fields, so the
    /// guard above cannot simply become "never write after a suspension".
    func testAPairThatAnswersLateStillFillsFieldsNobodyHasTouched() async throws {
        let auth = FakeAuth()
        let vm = ServerAddressViewModel(auth: auth)
        auth.serverConfigGate.arm()
        let loading = Task { await vm.load() }
        try await waitUntil { auth.serverConfigurationCalls == 1 }
        auth.serverConfigGate.release()
        await loading.value

        XCTAssertEqual(vm.adminAddress, "http://127.0.0.1:28080")
        XCTAssertEqual(vm.routerAddress, "http://127.0.0.1:28081")
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

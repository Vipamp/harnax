import XCTest

@testable import HarnaxCore

final class MemorySecretStoreTests: XCTestCase {
    func testSlotsAreIndependent() throws {
        let store = MemorySecretStore()
        try store.setValue("token-a", for: .accessToken)
        try store.setValue("http://127.0.0.1:28080", for: .adminBaseURL)
        XCTAssertEqual(try store.value(for: .accessToken), "token-a")
        XCTAssertEqual(try store.value(for: .adminBaseURL), "http://127.0.0.1:28080")

        try store.setValue(nil, for: .accessToken)
        XCTAssertNil(try store.value(for: .accessToken))
        XCTAssertEqual(try store.value(for: .adminBaseURL), "http://127.0.0.1:28080")
    }

    func testRemoveAllClearsEverySlot() throws {
        let store = MemorySecretStore()
        for key in SecretKey.allCases { try store.setValue("v-\(key.rawValue)", for: key) }
        try store.removeAll()
        for key in SecretKey.allCases { XCTAssertNil(try store.value(for: key), key.rawValue) }
    }
}

/// Exercises the real `SecItem` calls. A failure here with `errSecMissingEntitlement` or
/// `errSecInteractionNotAllowed` means the host keychain is unreachable from a bare test binary;
/// in that case this file moves to the iOS test destination instead of being weakened.
final class KeychainStoreTests: XCTestCase {
    private var store: KeychainStore!

    override func setUp() {
        super.setUp()
        store = KeychainStore(service: "com.agnetix.harnax.ios.tests.\(UUID().uuidString)")
    }

    override func tearDown() {
        XCTAssertNoThrow(try store.removeAll())
        super.tearDown()
    }

    func testRoundTripOverwriteAndDelete() throws {
        XCTAssertNil(try store.value(for: .accessToken))

        try store.setValue("eyJhbGciOi.first", for: .accessToken)
        XCTAssertEqual(try store.value(for: .accessToken), "eyJhbGciOi.first")

        try store.setValue("eyJhbGciOi.second", for: .accessToken)
        XCTAssertEqual(try store.value(for: .accessToken), "eyJhbGciOi.second")

        try store.setValue(nil, for: .accessToken)
        XCTAssertNil(try store.value(for: .accessToken))
    }

    func testRouterApiKeySurvivesAccessTokenRotation() throws {
        try store.setValue("hnx_sk_live_permanent", for: .routerApiKey)
        try store.setValue("token-one", for: .accessToken)
        try store.setValue("token-two", for: .accessToken)
        XCTAssertEqual(try store.value(for: .routerApiKey), "hnx_sk_live_permanent")
    }
}

import XCTest

@testable import HarnaxCore

/// The address sheet is the one place a build can be pointed at another stack, so what it accepts and what
/// it rewrites has to be pinned.
final class ServerConfigTests: XCTestCase {
    private func normalized(_ raw: String) throws -> String {
        try ServerConfig.normalized(raw, label: "admin")
    }

    func testTrailingSlashesAreTrimmed() throws {
        XCTAssertEqual(try normalized("https://harnax.example.com/"), "https://harnax.example.com")
        XCTAssertEqual(try normalized("https://harnax.example.com///"), "https://harnax.example.com")
    }

    func testSurroundingWhitespaceIsIgnored() throws {
        XCTAssertEqual(try normalized("  http://127.0.0.1:28080 \n"), "http://127.0.0.1:28080")
    }

    func testPathPrefixAndPortAreKept() throws {
        XCTAssertEqual(try normalized("https://gateway.example.com:8443/harnax/"), "https://gateway.example.com:8443/harnax")
    }

    /// Everything below is a paste that would build a request against the wrong host or leak a credential
    /// into the stored value.
    func testRejectsAnythingThatIsNotAnOrigin() {
        for raw in ["127.0.0.1:28080", "ftp://harnax.example.com", "harnax.example.com", "", "http://", "/harnax"] {
            XCTAssertThrowsError(try normalized(raw), "accepted \(raw)")
        }
    }

    func testRejectsCredentialsAndQueryInAnAddress() {
        for raw in ["http://admin:admin123@harnax.example.com", "http://harnax.example.com/?token=abc"] {
            XCTAssertThrowsError(try normalized(raw), "accepted \(raw)")
        }
    }

    func testSaveAndLoadRoundTrip() throws {
        let store = MemorySecretStore()
        let config = try ServerConfig(
            adminBaseURL: "https://harnax.example.com/",
            routerBaseURL: "https://router.example.com:28081/"
        )
        try config.save(into: store)

        let loaded = try ServerConfig.load(from: store)
        XCTAssertEqual(loaded, config)
        XCTAssertEqual(try store.value(for: .adminBaseURL), "https://harnax.example.com")
    }

    /// First run has nothing saved, and the dev stack is the only sane default for a debug build.
    func testLoadFallsBackToTheDevStack() throws {
        let loaded = try ServerConfig.load(from: MemorySecretStore())
        XCTAssertEqual(loaded.adminBaseURL, ServerConfig.devAdminBaseURL)
        XCTAssertEqual(loaded.routerBaseURL, ServerConfig.devRouterBaseURL)
    }

    /// A stored value can only be malformed if another build wrote it; loading must fail loudly rather than
    /// send requests to a half-parsed host.
    func testLoadRejectsACorruptStoredValue() throws {
        let store = MemorySecretStore()
        try store.setValue("not a url", for: .adminBaseURL)
        XCTAssertThrowsError(try ServerConfig.load(from: store))
    }
}

import WebKit
import XCTest
@testable import HarnaxFeatures

/// The authorization session keeps nothing of the identity provider past the sheet that showed it.
///
/// An MCP server authorized over OAuth2 means this app opens the provider's own sign-in page inside a
/// `WKWebView`. WebKit's default instance stores that page in the app's *persistent* website data store, so
/// the provider's session cookie — and every cookie the sign-in flow sets along the way — would sit in the
/// app's container after the sheet closes, survive logout, and ride out to whoever restores the backup.
/// The next authorize would then silently reuse a cookie this app never showed the user.
///
/// The console has no equivalent leak: it hands the URL to the browser and keeps no copy. This app cannot
/// hand it away (`McpOAuthReturnLegGateTests` documents why the callback never leaves the process), so the
/// ephemeral store is what keeps the two designs honest.
///
/// The behavioural half asserts the object the factory actually returns; the source half is there because
/// the factory has two callers on two platforms and a branch that drifts back to `WKWebView()` would keep
/// the first assertion green.
final class McpWebSessionEphemeralStoreTests: XCTestCase {
    /// The web view the flow builds, not a stand-in: the store is read off the thing that will load the page.
    @MainActor
    func testTheAuthorizationWebViewIsBuiltOnAnEphemeralDataStore() throws {
        let view = makeMcpAuthorizationWebView()
        XCTAssertFalse(
            view.configuration.websiteDataStore.isPersistent,
            "the sign-in page would leave the provider's cookies in the app's persistent store"
        )
    }

    /// Both platform branches reach for the shared factory.
    ///
    /// Walked as code lines only, the same way `McpOAuthReturnLegGateTests` scans: this file names the bare
    /// initializer in its own argument for why it is wrong, and a comment is not a violation.
    func testNoBranchConstructsAWebViewWithoutTheFactory() throws {
        let path = try authorizationSheetPath()
        var violations: [String] = []
        for (number, text) in try codeLines(of: path) {
            guard text.contains("WKWebView()") else { continue }
            violations.append("\(number): \(text.trimmingCharacters(in: .whitespaces))")
        }
        XCTAssertTrue(
            violations.isEmpty,
            "McpAuthorizationWebSheet.swift builds a WKWebView outside the factory at: \n\(violations.joined(separator: "\n"))"
        )
    }

    // MARK: - the scan

    /// This test's own compiled-in path reaches the source root the way the Kit gates do it.
    private func authorizationSheetPath() throws -> String {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // Tests/HarnaxFeaturesTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // harnax-ios
            .appendingPathComponent("Sources/HarnaxFeatures/Mcp/McpAuthorizationWebSheet.swift")
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.path), "\(root.path) is not where this gate looks")
        return root.path
    }

    private func codeLines(of path: String) throws -> [(Int, String)] {
        let lines = try String(contentsOfFile: path, encoding: .utf8).components(separatedBy: "\n")
        return lines.enumerated().compactMap { index, text in
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            return trimmed.hasPrefix("//") ? nil : (index + 1, text)
        }
    }
}

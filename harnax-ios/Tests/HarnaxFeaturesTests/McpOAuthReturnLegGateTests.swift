import XCTest

/// The three source gates the in-app return leg depends on, over the MCP OAuth flow itself.
///
/// All three are behavioural, and all three are the kind of thing that comes back as a "fix" rather than as a
/// bug.
///
/// The first is the design that died: a custom scheme callback. `mcp_oauth_client` is one row per
/// (tenant, issuer) and its `redirect_uri` is `client.callbackUrl`, which `saveClient` runs through
/// `validateHttpUrl` — http(s) and a host only
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt:103-109`,
/// `:433-448`), so `harnax://…` can never be registered and an `ASWebAuthenticationSession` with
/// `callbackURLScheme:` has nothing legal to register against. `McpContractTests` pins the bound itself; this
/// gate stops the *client* from quietly reaching for the scheme that bound rules out.
///
/// The second is the one-time material. `state` and `code` are spent inside one run of this app and are worth
/// nothing after it — and the web console erases the query the moment it has read it
/// (`harnax-webui/src/pages/mcp/oauth-callback.tsx:26-30`, `history.replace`). A persisting copy is a replay
/// waiting on a device backup, so nothing in this domain gets a storage handle at all.
///
/// The third keeps the needle where it came from: the address to watch for is the `redirect_uri` of the
/// request this device just asked for, so hardcoding the console's own callback path would have a session
/// watch for a page an authorization server in another deployment will never send anyone to.
final class McpOAuthReturnLegGateTests: XCTestCase {
    /// Everything the return leg is made of: the whole MCP screen domain plus the two contracts it is built
    /// from (`Sources/HarnaxFeatures/Mcp/**` and the OAuth block of `HarnaxCore`).
    ///
    /// Walked rather than listed, so a file added to the domain is gated the day it lands.
    private var flowFiles: [String] {
        swiftFiles(in: sourcesRoot.appendingPathComponent("HarnaxFeatures/Mcp"))
            .map { "HarnaxFeatures/Mcp/\($0.lastPathComponent)" }
            .sorted() + [
                "HarnaxCore/Contract/McpOAuth.swift",
                "HarnaxCore/Contract/McpCataloging.swift",
            ]
    }

    private func swiftFiles(in directory: URL) -> [URL] {
        guard let walker = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else {
            return []
        }
        return walker.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
    }

    /// `…/harnax-ios/Sources`, reached from this file's own compiled-in path the way the Kit gates do it.
    private var sourcesRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // Tests/HarnaxFeaturesTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // harnax-ios
            .appendingPathComponent("Sources")
    }

    /// The scheme-callback design, in each spelling a re-implementation would reach for.
    private let schemeCallbackTokens = [
        "ASWebAuthenticationSession",
        "callbackURLScheme",
        "CFBundleURLTypes",
        "onOpenURL",
        "harnax://",
    ]

    /// Every handle that could keep a code or a state past the run that spent it.
    private let persistenceTokens = [
        "UserDefaults",
        "Keychain",
        "SecItem",
        "FileManager",
        "createFile",
        "write(to:",
    ]

    /// The redirect to watch for is the one the server put into this very request
    /// (`McpOAuthUserServiceImpl.kt:103-107`, `:152`), never an address this app remembers from the console's
    /// own page: a deployment that moved its frontend, or a second deployment in the same tenant, would have
    /// the session watch for a page the authorization server will not send anyone to.
    private let inventedCallbackTokens = [
        "/mcp/oauth/callback",
        "CALLBACK_PATH",
    ]

    /// An empty walk reads as a clean sweep, so the set is asserted before its contents are: a gate that has
    /// lost sight of the flow it guards is worse than no gate at all.
    func testTheGatedFileSetIsTheFlowItself() throws {
        let files = flowFiles
        for expected in [
            "HarnaxFeatures/Mcp/McpAuthorizationWebSheet.swift",
            "HarnaxFeatures/Mcp/McpDetailViewModel.swift",
            "HarnaxCore/Contract/McpOAuth.swift",
        ] {
            XCTAssertTrue(files.contains(expected), "\(expected) is not in the gated set: \(files)")
        }
        XCTAssertGreaterThanOrEqual(files.count, 5)
        for file in files {
            XCTAssertFalse(try codeLines(of: source(file)).isEmpty, "\(file) scanned as no code lines at all")
        }
    }

    /// The comment exclusion, turned on itself. `McpOAuth.swift` names `harnax://` in the argument for why that
    /// design is dead, so the file both holds the token and passes the gate — which is only true while the
    /// scan reads real bytes and drops the comment lines, and either half breaking turns this red.
    func testTheCommentExclusionIsNotWhatCarriesTheGate() throws {
        let raw = try String(contentsOf: source("HarnaxCore/Contract/McpOAuth.swift"), encoding: .utf8)
        XCTAssertTrue(raw.contains("harnax://"), "the file no longer argues against the scheme it guards")
        XCTAssertFalse(try codeLines(of: source("HarnaxCore/Contract/McpOAuth.swift"))
            .contains { _, text in text.contains("harnax://") })
    }

    func testTheMcpFlowNeverReachesForASchemeCallback() throws {
        try assertNo(flowFiles, contain: schemeCallbackTokens, rule: "a callback URL has to be http(s) with a host")
    }

    func testTheMcpFlowKeepsNoOneTimeMaterial() throws {
        try assertNo(flowFiles, contain: persistenceTokens, rule: "a code or a state may not survive the run that spent it")
    }

    func testTheMcpFlowInventsNoCallbackAddress() throws {
        try assertNo(flowFiles, contain: inventedCallbackTokens, rule: "the redirect comes from the authorization request")
    }

    // MARK: - the scan

    struct Violation {
        let rule: String
        let location: String
        let line: String
    }

    /// Comment lines are where the dead design is *named* — every one of these files documents why it is dead,
    /// and `harnax://` appears in that argument. Only live code may not use it.
    private func assertNo(_ files: [String], contain tokens: [String], rule: String) throws {
        var violations: [Violation] = []
        for file in files {
            for (number, text) in try codeLines(of: try source(file)) {
                guard let token = tokens.first(where: { text.contains($0) }) else { continue }
                violations.append(Violation(
                    rule: "\(rule) — \(token)",
                    location: "\(file):\(number)",
                    line: text
                ))
            }
        }
        report(violations)
    }

    /// The file with comment lines dropped and re-numbered so a violation still points at the real line.
    private func codeLines(of url: URL) throws -> [(Int, String)] {
        let lines = try String(contentsOf: url, encoding: .utf8).components(separatedBy: "\n")
        return lines.enumerated().compactMap { index, text in
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            return trimmed.hasPrefix("//") ? nil : (index + 1, text)
        }
    }

    private func report(_ violations: [Violation], file: StaticString = #filePath, line: UInt = #line) {
        guard !violations.isEmpty else { return }
        let body = violations
            .map { "  \($0.location) — \($0.rule):\n    \($0.line.trimmingCharacters(in: .whitespaces))" }
            .joined(separator: "\n")
        XCTFail("\(violations.count) gate violation(s):\n\(body)", file: file, line: line)
    }

    private func source(_ relative: String) throws -> URL {
        let url = sourcesRoot.appendingPathComponent(relative)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "\(relative) is not where this gate looks")
        return url
    }
}

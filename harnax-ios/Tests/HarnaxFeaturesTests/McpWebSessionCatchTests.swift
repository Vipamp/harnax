import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The in-app session's own return-leg rule: which navigation counts as this registration's callback, and
/// what happens to it.
///
/// `McpOAuthCallback` has its contract tests and the view model has the exchange tests. What sits between them
/// is the decision taken inside the `WKWebView`, and it carries three rules the whole design leans on: the
/// address is matched against the redirect that came out of *this* authorization request rather than any
/// callback the app was told about; a navigation that reaches the registered address with neither a code nor
/// an error is not a return leg; and the leg itself is recognised exactly once, because the pending entry is
/// burned before anything is minted
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt:77-82`), so a second
/// spend cannot succeed and must not even be attempted.
///
/// These drive `classify`, the seam that owns that one-shot flag: a `WKNavigationAction` has no initialiser
/// off a device, and the delegate method is a two-line translation of this decision into WebKit's policy plus
/// the hand-off.
final class McpWebSessionCatchTests: XCTestCase {
    private let registered = "http://127.0.0.1:28081/mcp/oauth/callback"

    /// The leg the authorization server actually performs: the registered address plus its keys.
    /// `McpOAuthUserServiceImpl.kt:103-107`, `:152` put `client.callbackUrl` into `redirect_uri` verbatim, so
    /// this is the exact needle the session was built to watch for.
    func testTheRegisteredAddressIsTakenAndTheCodeReadOffIt() throws {
        let caught = session(registered).classify(URL(string:
            "\(registered)?code=Sp%2Bend&state=kQ7x"
        ))

        let draft = try XCTUnwrap(caught.draft)
        XCTAssertEqual(draft.code, "Sp+end")
        XCTAssertEqual(draft.state, "kQ7x", "the state rides along; nothing here parses or checks it")
        XCTAssertNil(draft.error)
    }

    /// A consent the user refused is still this registration's return leg and still has to be reported: the
    /// exchange endpoint answers a refusal as a successful `authorized = false`
    /// (`McpOAuthController.kt:101-102`), so swallowing it here would leave the sheet waiting for a grant that
    /// can never arrive.
    func testARefusalArrivingOnTheRegisteredAddressIsTakenToo() throws {
        let caught = session(registered).classify(URL(string:
            "\(registered)?error=access_denied&error_description=The%20user%20said%20no"
        ))

        let draft = try XCTUnwrap(caught.draft)
        XCTAssertTrue(draft.isRefusal)
        XCTAssertNil(draft.code)
    }

    /// The login page, the consent page and anything else the user reaches inside the session are ordinary
    /// navigations: an address that is not the registered one cannot end the session, and it must not be
    /// cancelled either — that would blank the sheet on a page the operator still has to fill in.
    func testAnyOtherPageInTheSessionIsLeftToLoad() {
        let caught = session(registered)
        for address in [
            "https://auth.example.com/realms/harnax/protocol/openid-connect/auth?client_id=x",
            "https://auth.example.com/realms/harnax/protocol/openid-connect/token",
            // Same path on another port: the registration is one exact string and the AS compares it as one.
            "http://127.0.0.1:28082/mcp/oauth/callback?code=Sp%2Bend&state=kQ7x",
            // Same address with nothing to spend — a bookmark, or the page reloading.
            "\(registered)?state=kQ7x",
        ] {
            XCTAssertEqual(caught.classify(URL(string: address)), .allow, address)
        }
    }

    /// One return leg per session. A cancelled navigation can be re-attempted by the web view, and the state
    /// behind it is already burned, so the second arrival must produce no draft to hand over and must not be
    /// cancelled into a blank sheet either.
    func testTheSameLegIsNeverTakenTwice() throws {
        let caught = session(registered)
        let leg = URL(string: "\(registered)?code=Sp%2Bend&state=kQ7x")

        XCTAssertEqual(caught.classify(leg), .catchReturn(McpOAuthExchangeDraft(code: "Sp+end", state: "kQ7x")))
        XCTAssertEqual(caught.classify(leg), .allow, "the second time this is only a page")
        XCTAssertEqual(caught.classify(URL(string: "\(registered)?code=another&state=another")), .allow)
    }

    /// A registration the session was never given matches nothing, so no navigation can end it. The view model
    /// refuses to open such a session at all (`McpDetailViewModel.startInAppAuthorization`), and this is what
    /// it would face if that guard were ever loosened.
    func testASessionWithNoRegisteredAddressTakesNothing() {
        for needle in ["", "   "] {
            let caught = session(needle)
            XCTAssertEqual(caught.classify(URL(string: "\(registered)?code=A&state=B")), .allow)
            XCTAssertEqual(caught.classify(nil), .allow)
        }
    }

    /// The decision alone is under test: nothing in these calls reaches the hand-off, which the delegate method
    /// performs after it has cancelled the navigation.
    private func session(_ redirect: String) -> McpWebSessionCoordinator {
        McpWebSessionCoordinator(redirect: redirect, onReturn: { _ in
            XCTFail("these tests drive the decision, not the delegate method")
        })
    }
}

private extension McpSessionNavigation {
    /// The draft this decision carries, or nil for a navigation that is not this session's return leg.
    var draft: McpOAuthExchangeDraft? {
        if case let .catchReturn(draft) = self { return draft }
        return nil
    }
}

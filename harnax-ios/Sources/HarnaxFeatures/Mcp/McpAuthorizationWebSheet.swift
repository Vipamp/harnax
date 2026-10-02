import SwiftUI
import HarnaxCore
import HarnaxKit
#if canImport(WebKit)
import WebKit
#endif

/// The authorization session this app can watch, so the code is spent by this device.
///
/// Why this exists at all: the redirect on file is the console's own page, because
/// `POST /{id}/oauth/client` runs the `callbackUrl` through `validateHttpUrl`, which accepts http(s) only and
/// demands a host (`McpOAuthServiceImpl.kt:433-448`). A custom scheme of our own is therefore unregistrable,
/// and the registration belongs to the tenant rather than to one handset, so rewriting it for this device is
/// not an option either. What an app *can* do is load the authorization request itself and take the code out
/// of the navigation that would have reached that page — before the page loads, and therefore before it
/// exchanges the code under whoever happens to be signed in to the console in that browser.
///
/// Two rules the design keeps: the one-time `state` is never stored, and nothing here persists. The draft
/// lives only in the hand-off to the exchange call, and the session's cookie store dies with the sheet
/// (`makeMcpAuthorizationWebView`). In-process WebKit is a fallback for the unregistrable callback, not a
/// licence to keep the provider's cookies: an ephemeral store is the one property
/// `ASWebAuthenticationSession` would have given and this design can still afford.
public struct McpAuthorizationWebSheet: View {
    @ObservedObject private var vm: McpDetailViewModel

    public init(vm: McpDetailViewModel) {
        self.vm = vm
    }

    public var body: some View {
        NavigationStack {
            Group {
                // The sheet's presence *is* `inAppTarget`, so a nil here means the dismissal is already
                // running and there is nothing left to show.
                if let target = vm.inAppTarget {
                    McpWebSessionView(url: target.url, redirect: target.redirect) { draft in
                        Task { await vm.finishInAppAuthorization(with: draft) }
                    }
                }
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("mcp.oauth.inAppTitle")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { vm.cancelInAppAuthorization() } label: { HXText("common.close") }
                }
            }
        }
    }
}

/// One authorization session: the request as the server built it, and the address to watch for inside it.
///
/// The platform split lives here rather than at the call site, so the screen presents one view on both
/// platforms and the test target — which runs this on macOS — compiles the whole file.
@MainActor
func makeMcpAuthorizationWebView() -> WKWebView {
    let configuration = WKWebViewConfiguration()
    configuration.websiteDataStore = .nonPersistent()
    return WKWebView(frame: .zero, configuration: configuration)
}

#if os(iOS)
struct McpWebSessionView: UIViewRepresentable {
    let url: URL
    let redirect: String
    let onReturn: (McpOAuthExchangeDraft) -> Void

    func makeCoordinator() -> McpWebSessionCoordinator {
        McpWebSessionCoordinator(redirect: redirect, onReturn: onReturn)
    }

    func makeUIView(context: Context) -> WKWebView {
        let view = makeMcpAuthorizationWebView()
        // Opaque-by-default means a white sheet in the dark tier until the provider paints. Transparent, the
        // screen's own themed background shows through the blank; a loaded page covers it as usual.
        view.isOpaque = false
        view.backgroundColor = .clear
        view.navigationDelegate = context.coordinator
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
#else
struct McpWebSessionView: NSViewRepresentable {
    let url: URL
    let redirect: String
    let onReturn: (McpOAuthExchangeDraft) -> Void

    func makeCoordinator() -> McpWebSessionCoordinator {
        McpWebSessionCoordinator(redirect: redirect, onReturn: onReturn)
    }

    func makeNSView(context: Context) -> WKWebView {
        let view = makeMcpAuthorizationWebView()
        view.navigationDelegate = context.coordinator
        view.load(URLRequest(url: url))
        return view
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {}
}
#endif

/// What the authorization session does with one navigation, in terms this app owns.
///
/// Kept apart from WebKit's own policy type because a `WKNavigationAction` cannot be built outside a device,
/// and the rule below — this registration's return leg is recognised once and the page never loads — is the
/// whole reason the session is in-app. The delegate method is two lines of translation from here.
enum McpSessionNavigation: Equatable {
    /// Not this registration's return: let it load.
    case allow
    /// This session's own return leg: stop the page from ever loading and spend what it carries.
    case catchReturn(McpOAuthExchangeDraft)
}

/// The delegate half: recognise this registration's own redirect, cancel the navigation into it, and hand the
/// code up before the page can load.
final class McpWebSessionCoordinator: NSObject, WKNavigationDelegate {
    private let redirect: String
    private let onReturn: (McpOAuthExchangeDraft) -> Void
    /// One return leg per session. A cancelled navigation can be re-attempted, and spending the same state
    /// twice is the one thing this flow must not do.
    private var caught = false

    init(redirect: String, onReturn: @escaping (McpOAuthExchangeDraft) -> Void) {
        self.redirect = redirect
        self.onReturn = onReturn
    }

    /// The decision, and the only place `caught` moves.
    ///
    /// Once a return leg has been taken, everything after it is an ordinary page — including the same
    /// address, which the authorization server may well navigate to again. Loading it a second time would
    /// hand the console's callback page a spent code, so the answer stays `.allow` and no draft is produced.
    func classify(_ url: URL?) -> McpSessionNavigation {
        guard !caught,
              let url,
              McpOAuthCallback.matches(url, redirect: redirect),
              let draft = McpOAuthCallback.draft(from: url)
        else { return .allow }
        caught = true
        return .catchReturn(draft)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        switch classify(navigationAction.request.url) {
        case .allow:
            decisionHandler(.allow)
        case let .catchReturn(draft):
            // Cancelled before anything is handed over: the callback page may not load at all, because it
            // would spend this code under whoever is signed in to the console rather than under this device.
            decisionHandler(.cancel)
            onReturn(draft)
        }
    }
}

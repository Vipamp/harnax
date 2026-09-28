import Foundation
import HarnaxCore
import HarnaxKit
#if canImport(UIKit)
import UIKit
#endif

/// Hands the authorize URL to the system browser.
///
/// Safari rather than `ASWebAuthenticationSession`: the deployed `redirect_uri` is one registration shared
/// by every server under the same issuer, so a custom-scheme callback claimed by this app would rewrite it
/// for the whole tenant. The console's callback page is what receives the code and admin stores the token,
/// which means nothing here can observe the return leg — the caller re-reads the status instead.
public struct SystemBrowserAuthorizer: McpAuthorizing {
    public init() {}

    public func presentAuthorizeURL(_ url: URL, for serverID: Int64) async -> McpAuthorizationHandoff {
        #if canImport(UIKit) && os(iOS)
        // `open` reports nothing back, so the question is whether the system has a handler at all. Answering
        // yes only means the hand-off happened; whether a browser then completed it is exactly what the
        // status poll is for.
        let handedOff = await MainActor.run {
            guard UIApplication.shared.canOpenURL(url) else { return false }
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            return true
        }
        return handedOff ? .openedInBrowser(url: url) : .refused(message: hx("mcp.oauth.noBrowser"))
        #else
        return .refused(message: hx("mcp.oauth.noBrowser"))
        #endif
    }
}

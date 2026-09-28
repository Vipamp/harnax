import HarnaxCore
import SwiftUI

@main
struct HarnaxApp: App {
    var body: some Scene {
        WindowGroup {
            Text(verbatim: Self.storedAdminBaseURL() ?? "harnax-ios")
        }
    }

    /// Reads the same Keychain slot the login screen will use, so the app target has to link
    /// `HarnaxCore` rather than merely compile against it.
    private static func storedAdminBaseURL() -> String? {
        try? KeychainStore().value(for: .adminBaseURL)
    }
}

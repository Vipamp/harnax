import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The two base URLs, edited on the stack that is about to be used.
@MainActor
public final class ServerAddressViewModel: ObservableObject {
    @Published public var adminAddress = "" {
        didSet { noteTypedChange() }
    }
    @Published public var routerAddress = "" {
        didSet { noteTypedChange() }
    }
    @Published public private(set) var isSaving = false
    @Published public private(set) var errorText: String?
    @Published public private(set) var saved = false

    private let auth: any AuthFlowing
    /// Counted on every value the operator put into a field, so a read that was already suspended can tell
    /// "nobody has typed yet" from "the text arrived while we were away reading".
    private var typedValues = 0
    /// True only while this type writes the fields itself — those are answers, not typing.
    private var applyingStoredValues = false

    public init(auth: any AuthFlowing) {
        self.auth = auth
    }

    /// Both fields start empty, which is also what they look like when the operator has just cleared them to
    /// retype. So the emptiness check cannot survive the suspension below on its own: a session that ends while
    /// this is parked — a 401 from any screen, or an address change that drops the old host's bearer — makes the
    /// root re-read, and the pair that answers then belongs to the stack the operator is already leaving.
    public func load() async {
        guard adminAddress.isEmpty, routerAddress.isEmpty else { return }
        let typedBeforeReading = typedValues
        guard case let .success(config) = await auth.serverConfiguration() else { return }
        guard typedBeforeReading == typedValues else { return }
        apply(admin: config.adminBaseURL, router: config.routerBaseURL)
    }

    /// Writes the two fields without counting them as typing.
    private func apply(admin: String, router: String) {
        applyingStoredValues = true
        adminAddress = admin
        routerAddress = router
        applyingStoredValues = false
    }

    private func noteTypedChange() {
        guard !applyingStoredValues else { return }
        typedValues += 1
    }

    /// An address this side cannot parse never reaches the network — the fields are the boundary, and
    /// `ServerConfig` is the validator.
    public func save() async {
        guard !isSaving else { return }
        let config: ServerConfig
        do {
            config = try ServerConfig(adminBaseURL: adminAddress, routerBaseURL: routerAddress)
        } catch {
            saved = false
            errorText = hx("error.serverConfig")
            return
        }
        isSaving = true
        let result = await auth.save(serverConfiguration: config)
        isSaving = false
        switch result {
        case .success:
            // The store normalised the input, so the fields are re-read rather than trusted.
            apply(admin: config.adminBaseURL, router: config.routerBaseURL)
            saved = true
            errorText = nil
        case let .failure(error):
            saved = false
            errorText = ErrorMessage.text(for: error)
        }
    }
}

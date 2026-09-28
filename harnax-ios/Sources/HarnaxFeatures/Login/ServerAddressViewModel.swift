import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The two base URLs, edited on the stack that is about to be used.
@MainActor
public final class ServerAddressViewModel: ObservableObject {
    @Published public var adminAddress = ""
    @Published public var routerAddress = ""
    @Published public private(set) var isSaving = false
    @Published public private(set) var errorText: String?
    @Published public private(set) var saved = false

    private let auth: any AuthFlowing

    public init(auth: any AuthFlowing) {
        self.auth = auth
    }

    public func load() async {
        guard adminAddress.isEmpty, routerAddress.isEmpty else { return }
        if case let .success(config) = await auth.serverConfiguration() {
            adminAddress = config.adminBaseURL
            routerAddress = config.routerBaseURL
        }
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
            adminAddress = config.adminBaseURL
            routerAddress = config.routerBaseURL
            saved = true
            errorText = nil
        case let .failure(error):
            saved = false
            errorText = ErrorMessage.text(for: error)
        }
    }
}

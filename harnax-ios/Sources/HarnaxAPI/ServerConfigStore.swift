import Foundation
import HarnaxCore

/// Cached, mutable pair of base URLs. The address sheet writes through here, so the change takes effect
/// on the next request instead of needing a new client.
public actor ServerConfigStore {
    private let store: SecretStoring
    private var cache: ServerConfig?

    public init(store: SecretStoring) {
        self.store = store
    }

    public func current() throws -> ServerConfig {
        if let cache { return cache }
        let loaded = try ServerConfig.load(from: store)
        cache = loaded
        return loaded
    }

    public func update(_ config: ServerConfig) throws {
        try config.save(into: store)
        cache = config
    }

    /// Drops the cache after the keychain was written elsewhere — sign-out keeps the two addresses, so
    /// only the in-memory copy is reset.
    public func invalidate() {
        cache = nil
    }

    func baseURL(for base: APIBase) throws -> String {
        let config = try current()
        switch base {
        case .admin: return config.adminBaseURL
        case .router: return config.routerBaseURL
        }
    }
}

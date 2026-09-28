import Foundation

public enum ServerConfigError: Error, Equatable, Sendable {
    case invalid(String)
}

/// The two base URLs a build has to be pointed at. Kept in the keychain rather than Info.plist because
/// one installed app gets used against several stacks.
public struct ServerConfig: Equatable, Sendable {
    public let adminBaseURL: String
    public let routerBaseURL: String

    public static let devAdminBaseURL = "http://127.0.0.1:28080"
    public static let devRouterBaseURL = "http://127.0.0.1:28081"

    public init(adminBaseURL: String, routerBaseURL: String) throws {
        self.adminBaseURL = try Self.normalized(adminBaseURL, label: "admin")
        self.routerBaseURL = try Self.normalized(routerBaseURL, label: "router")
    }

    /// Trims the trailing slash so callers can append `/api/...` without branching, and rejects
    /// anything that is not an absolute http(s) origin.
    static func normalized(_ raw: String, label: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              url.host != nil, !(url.host?.isEmpty ?? true)
        else {
            throw ServerConfigError.invalid("\(label): \(trimmed)")
        }
        guard url.user == nil, url.password == nil, url.query == nil else {
            throw ServerConfigError.invalid("\(label): \(trimmed)")
        }
        var path = url.path
        while path.hasSuffix("/") { path.removeLast() }
        let port = url.port.map { ":\($0)" } ?? ""
        let host = url.host ?? ""
        return "\(scheme)://\(host)\(port)\(path)"
    }

    public func save(into store: SecretStoring) throws {
        try store.setValue(adminBaseURL, for: .adminBaseURL)
        try store.setValue(routerBaseURL, for: .routerBaseURL)
    }

    /// Falls back to the dev stack when nothing has been saved yet, which is the first-run case.
    public static func load(from store: SecretStoring) throws -> ServerConfig {
        let admin = try store.value(for: .adminBaseURL) ?? devAdminBaseURL
        let router = try store.value(for: .routerBaseURL) ?? devRouterBaseURL
        return try ServerConfig(adminBaseURL: admin, routerBaseURL: router)
    }
}

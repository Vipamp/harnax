import Foundation

/// One Keychain-backed slot. Each value gets its own item rather than one blob, so a token
/// rotation never rewrites the server addresses.
public enum SecretKey: String, CaseIterable, Sendable {
    case accessToken
    case routerApiKey
    case tenantId
    case tokenExpiresAtMillis
    case tokenLifetimeMillis
    case adminBaseURL
    case routerBaseURL
}

public enum SecretStoreError: Error, Equatable, Sendable {
    case unexpectedStatus(Int)
}

/// Credential storage seam. Production uses `KeychainStore`; tests use `MemorySecretStore`.
public protocol SecretStoring: Sendable {
    func value(for key: SecretKey) throws -> String?
    func setValue(_ value: String?, for key: SecretKey) throws
    func removeAll() throws
}

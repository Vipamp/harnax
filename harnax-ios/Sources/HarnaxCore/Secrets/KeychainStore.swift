import Foundation
import Security

/// Keychain-backed `SecretStoring`.
///
/// Items go to the data-protection keychain with `afterFirstUnlock`, which is what lets a
/// background token refresh read the token before the user has unlocked the screen.
public struct KeychainStore: SecretStoring {
    private let service: String

    public init(service: String = "com.agnetix.harnax.ios") {
        self.service = service
    }

    private func baseQuery(_ key: SecretKey) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue,
        ]
        // iOS ships against the data-protection keychain. Two environments are excluded: macOS, where the same
        // attribute needs a code identity a bare `swift test` binary does not have (-34018), and the simulator,
        // where Xcode signs ad-hoc with no `application-identifier` at all and the same status comes back. Both
        // fall to the classic file keychain, and the real SecItem path still gets covered on the Mac.
        #if os(iOS) && !targetEnvironment(simulator)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        return query
    }

    public func value(for key: SecretKey) throws -> String? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess {
            guard let data = item as? Data else { return nil }
            return String(decoding: data, as: UTF8.self)
        }
        if status == errSecItemNotFound { return nil }
        throw SecretStoreError.unexpectedStatus(Int(status))
    }

    public func setValue(_ value: String?, for key: SecretKey) throws {
        let query = baseQuery(key)
        if value == nil {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw SecretStoreError.unexpectedStatus(Int(status))
            }
            return
        }
        let data = Data(value!.utf8)
        let updated = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else {
            throw SecretStoreError.unexpectedStatus(Int(updated))
        }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let added = SecItemAdd(add as CFDictionary, nil)
        guard added == errSecSuccess else {
            throw SecretStoreError.unexpectedStatus(Int(added))
        }
    }

    public func removeAll() throws {
        for key in SecretKey.allCases {
            let status = SecItemDelete(baseQuery(key) as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw SecretStoreError.unexpectedStatus(Int(status))
            }
        }
    }
}

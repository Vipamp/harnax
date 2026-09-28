import Foundation

public final class MemorySecretStore: SecretStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]

    public init() {}

    public func value(for key: SecretKey) throws -> String? {
        lock.lock()
        defer { lock.unlock() }
        return values[key.rawValue]
    }

    public func setValue(_ value: String?, for key: SecretKey) throws {
        lock.lock()
        defer { lock.unlock() }
        values[key.rawValue] = value
    }

    public func removeAll() throws {
        lock.lock()
        defer { lock.unlock() }
        values.removeAll()
    }
}

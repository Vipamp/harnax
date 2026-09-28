import Foundation

/// Client-side throttle for the login endpoint. `cli-login` is the one endpoint with no captcha, so the
/// backoff is what stands between a lost password and a wall of guesses against the admin service.
public struct LoginBackoff: Equatable, Sendable {
    public static let refillPasswordAfterFailures = 5
    public static let maximumDelay: TimeInterval = 60

    public private(set) var failures: Int
    public private(set) var lastFailure: Date?

    public init() {
        failures = 0
        lastFailure = nil
    }

    /// Seconds to wait before the next attempt, doubling from one second and capped at a minute.
    public var delay: TimeInterval {
        guard failures > 0 else { return 0 }
        return min(pow(2, Double(failures - 1)), Self.maximumDelay)
    }

    public var requiresPasswordRefill: Bool {
        failures >= Self.refillPasswordAfterFailures
    }

    public mutating func recordFailure(at date: Date) {
        failures += 1
        lastFailure = date
    }

    public mutating func recordSuccess() {
        failures = 0
        lastFailure = nil
    }

    public func retryAllowed(at date: Date) -> Bool {
        remainingDelay(at: date) == 0
    }

    public func remainingDelay(at date: Date) -> TimeInterval {
        guard failures > 0, let lastFailure else { return 0 }
        return max(0, delay - date.timeIntervalSince(lastFailure))
    }

    /// Only a rejected credential grows the streak. A timeout or a body this side cannot read would
    /// otherwise lock the caller out of their own account for one bad hop on the network.
    public static func isCredentialFailure(_ error: APIError) -> Bool {
        switch error {
        case .business, .unauthorized: true
        default: false
        }
    }
}

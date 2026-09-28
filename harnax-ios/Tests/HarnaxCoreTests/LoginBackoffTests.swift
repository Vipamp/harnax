import XCTest
@testable import HarnaxCore

/// The curve the login screen relies on: one second of waiting after the first rejection, doubling to a
/// minute, and a hand-clear of the password after five.
final class LoginBackoffTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_790_592_457)

    private func failing(_ times: Int) -> LoginBackoff {
        var backoff = LoginBackoff()
        for _ in 0 ..< times {
            backoff.recordFailure(at: start)
        }
        return backoff
    }

    func testDelayDoublesUpToTheCap() {
        XCTAssertEqual(failing(0).delay, 0)
        let expected: [TimeInterval] = [1, 2, 4, 8, 16, 32, 60, 60, 60]
        for (index, delay) in expected.enumerated() {
            XCTAssertEqual(failing(index + 1).delay, delay, "after \(index + 1) failures")
        }
    }

    func testRetryIsBlockedUntilTheWindowElapses() {
        let backoff = failing(3)
        XCTAssertEqual(backoff.remainingDelay(at: start), 4)
        XCTAssertEqual(backoff.remainingDelay(at: start.addingTimeInterval(1)), 3)
        XCTAssertEqual(backoff.remainingDelay(at: start.addingTimeInterval(4)), 0)
        XCTAssertTrue(backoff.retryAllowed(at: start.addingTimeInterval(4)))
        XCTAssertFalse(backoff.retryAllowed(at: start.addingTimeInterval(3.9)))
    }

    func testRefillIsRequestedOnTheSixthAttempt() {
        XCTAssertFalse(failing(LoginBackoff.refillPasswordAfterFailures - 1).requiresPasswordRefill)
        XCTAssertTrue(failing(LoginBackoff.refillPasswordAfterFailures).requiresPasswordRefill)
    }

    func testSuccessWipesTheStreak() {
        var backoff = failing(4)
        backoff.recordSuccess()
        XCTAssertEqual(backoff.failures, 0)
        XCTAssertEqual(backoff.delay, 0)
        XCTAssertNil(backoff.lastFailure)
        XCTAssertTrue(backoff.retryAllowed(at: start))
    }

    /// Only the two shapes that mean "those credentials were refused" count. Everything else is the
    /// network's fault and must not be paid for by the caller.
    func testOnlyCredentialRejectionsCount() {
        XCTAssertTrue(LoginBackoff.isCredentialFailure(.business(code: 400, message: "x")))
        XCTAssertTrue(LoginBackoff.isCredentialFailure(.unauthorized))
        for error in [APIError.offline, .timeout, .decoding, .unpackable, .refillPassword,
                      .throttled(seconds: 1), .invalidServerConfig("x")] {
            XCTAssertFalse(LoginBackoff.isCredentialFailure(error))
        }
    }
}

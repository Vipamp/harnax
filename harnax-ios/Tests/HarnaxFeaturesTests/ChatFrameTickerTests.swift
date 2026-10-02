import XCTest
@testable import HarnaxFeatures

/// The production clock's one promise: it wakes only while somebody is still waiting on a frame window.
///
/// `ChatViewModel` closes a window by calling `stop()`, and the hand-driven clock of
/// `ChatStreamPublishingTests` stands in for that path in every screen test. There is a second path with no
/// caller at all: an owner deallocated with a window still open — leaving the chat while a run is talking —
/// never gets to say `stop()`, and the task it started holds no reference back to the ticker. Cancelled only
/// on deallocation, that loop wakes at the display's cadence for a screen that is gone; the two tests below
/// are the two halves, because a clock that stopped ticking for some other reason would make the leak case
/// pass on its own.
@MainActor
final class ChatFrameTickerTests: XCTestCase {
    /// A tick counter the clock can be read through. A class, because the closure the ticker holds has to
    /// accumulate across the hops the test waits on.
    private final class Ticks {
        var count = 0
    }

    func testATickerThatIsStandingWakesTheWindow() async throws {
        let ticks = Ticks()
        let ticker = ChatFrameTicker(interval: .milliseconds(5))
        ticker.start { ticks.count += 1 }
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertGreaterThanOrEqual(ticks.count, 3, "a ticker that never ticks would prove nothing about stopping")
        ticker.stop()
        let stopped = ticks.count
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertEqual(ticks.count, stopped, "stop() ends the window's clock, so nothing wakes after it")
    }

    func testATickerWhoseOwnerWentAwayStopsWaking() async throws {
        let ticks = Ticks()
        do {
            let ticker = ChatFrameTicker(interval: .milliseconds(5))
            ticker.start { ticks.count += 1 }
        }
        // Any tick already in flight when the ticker died lands first; the reading is taken after it, so what
        // is being asserted is the wake-ups that follow, which an uncancelled loop would keep adding.
        try await Task.sleep(for: .milliseconds(40))
        let settled = ticks.count
        try await Task.sleep(for: .milliseconds(120))
        XCTAssertEqual(ticks.count, settled, "the ticker was released with its window open — that is the leak")
    }
}

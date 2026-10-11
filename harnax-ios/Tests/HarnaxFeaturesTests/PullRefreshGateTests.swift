import XCTest
@testable import HarnaxFeatures

/// Two source rules keep a pull-to-refresh from reporting itself as a failure.
///
/// Reported 2026-10-03, on a 上下文 column with nothing on screen: the pull came back as
/// `APIError.cancelled` and the screen raised it as an error card. Neither half of that is something the
/// compiler can see — the refresh control hangs off a scroll view whose own contents the refresh was
/// rewriting — so the rules are read out of the source instead.
///
/// Reported again 2026-10-11, on the 本会话自写技能 panel (one pull on an answered-empty panel printed
/// 「这个会话的技能读不出来」): that screen carried both faults under spellings these rules had not learned —
/// `isLoading, vm.rows.isEmpty` gating the spinner, and `unavailable = true` writing the verdict — which is
/// why each rule now names every spelling it knows.
final class PullRefreshGateTests: XCTestCase {
    /// A refresh may not replace the card the operator is pulling with a full-screen spinner. That card is
    /// the pull's own container, and swapping it mid-gesture is how the read cancels itself; the pull
    /// already carries a spinner. The very first read of a column needs no such line either, because its
    /// `phase` starts at `.loading` and stays there until an answer or a refusal lands.
    func testNoRefreshRewritesTheScreenItIsPulling() throws {
        let swapsThePulledScreen = [
            "isEmpty { phase = .loading }",
            "isLoading, vm.rows.isEmpty",
            "isLoading && vm.rows.isEmpty",
        ]
        for (path, text) in try FeatureSources.contents(ofDomain: "HarnaxFeatures") {
            for swap in swapsThePulledScreen {
                XCTAssertFalse(
                    text.contains(swap),
                    "\(path) swaps the screen the pull is attached for a spinner mid-gesture (\(swap))"
                )
            }
        }
    }

    /// An error card is a verdict on the stack, so the arm that raises one has to ask first whether the read
    /// was merely cancelled — a read nobody is waiting for any more is silence, not refusal. Two idioms ask
    /// the question: an error-valued read filters through `ErrorMessage.carriesNews`, and a read that carries
    /// its failures as fields rather than as a thrown error checks `Task.isCancelled` itself.
    func testEveryErrorCardAsksWhetherTheReadIsStillWanted() throws {
        for (path, text) in try FeatureSources.contents(ofDomain: "HarnaxFeatures")
        where text.contains("phase = .failed(text)") {
            XCTAssertTrue(
                text.contains("ErrorMessage.carriesNews(error)"),
                "\(path) raises an error card from a read that may only have been cancelled"
            )
        }
        for (path, text) in try FeatureSources.contents(ofDomain: "HarnaxFeatures")
        where text.contains("unavailable = true") {
            XCTAssertTrue(
                text.contains("Task.isCancelled"),
                "\(path) writes a failure verdict from a read that may only have been cancelled"
            )
        }
    }
}

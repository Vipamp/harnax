import XCTest

@testable import HarnaxCore

/// What one `COMPACT` reply actually did.
///
/// The three-way reading is the console's (`harnax-webui/src/pages/session/components/contextUsage.ts:73-80`),
/// and the leg worth the most care is the counted no-op: the runtime answers a session too short to keep a tail
/// with `success: true` and the same count on both sides
/// (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/ContextCompactionService.kt:162-171`),
/// so "it worked" and "there was nothing to do" arrive in one envelope and are not the same report.
final class CompactionOutcomeTests: XCTestCase {
    private func outcome(_ reply: AgentCommandReply) -> CompactionOutcome {
        CompactionOutcome.compact(.success(reply))
    }

    func testCountsThatDifferAreACompaction() {
        XCTAssertEqual(
            outcome(AgentCommandReply(success: true, result: .init(beforeMessages: 40, afterMessages: 9))),
            .done
        )
    }

    func testEqualCountsAreTheDocumentedNoOp() {
        XCTAssertEqual(
            outcome(AgentCommandReply(success: true, result: .init(beforeMessages: 4, afterMessages: 4))),
            .noop
        )
    }

    func testARefusedCommandFails() {
        XCTAssertEqual(
            outcome(AgentCommandReply(success: false, message: "This session has no live agent state to compact.")),
            .failed
        )
    }

    func testAReplyThatNeverArrivedFails() {
        XCTAssertEqual(
            CompactionOutcome.compact(
                .failure(.business(code: 500, message: "No context held for session s-1 on this instance"))
            ),
            .failed
        )
    }

    /// A success that carried no counts to argue with is still a compaction the server said it ran — the other
    /// commands on this endpoint answer with no payload at all, and reading their silence as a refusal would
    /// turn a working `/compact` into an error message.
    func testACompactionWithNoCountsIsNotCalledAFailure() {
        XCTAssertEqual(outcome(AgentCommandReply(success: true)), .done)
        XCTAssertEqual(outcome(AgentCommandReply(success: true, result: nil)), .done)
    }

    /// A no-op has to be *counted*: one side missing is not evidence that nothing was removed.
    func testOneSidedCountsDoNotMakeANoOp() {
        XCTAssertEqual(outcome(AgentCommandReply(success: true, result: .init(beforeMessages: 9))), .done)
        XCTAssertEqual(outcome(AgentCommandReply(success: true, result: .init(afterMessages: 9))), .done)
    }

    /// The flag has to say `true`, which is the console's rule (`contextUsage.ts:76`) and the safe one: the
    /// command channel declares `success` non-null
    /// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/CommandResponse.kt:13-17`), so a body
    /// that does not carry it is one this side did not read rather than a compaction that ran. Reading it as a
    /// completed compaction would put 「已压缩上下文」 under a call that never reported one.
    func testAReplyThatCarriesNoSuccessFlagIsReadAsAFailure() {
        XCTAssertEqual(outcome(AgentCommandReply()), .failed)
        XCTAssertEqual(
            outcome(AgentCommandReply(result: .init(beforeMessages: 4, afterMessages: 4))),
            .failed,
            "counts do not stand in for the flag"
        )
    }
}

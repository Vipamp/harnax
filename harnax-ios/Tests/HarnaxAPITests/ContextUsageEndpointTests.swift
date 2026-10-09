import XCTest
import HarnaxCore

@testable import HarnaxAPI

/// The occupancy read's route and its three answers.
///
/// The route is worth pinning on its own because the read is the only thing that puts a number in the chat
/// header, and the failure modes are all shapes the router genuinely sends: a payload, a `null` where the
/// payload should be, and a business error that arrives over HTTP 200.
final class ContextUsageEndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    // MARK: - the route

    func testUsageIsAGetOnTheRouterBase() throws {
        let endpoint = ContextUsageEndpoint.usage(sessionId: "s-1")

        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/router/agent/context/s-1")
        XCTAssertEqual(endpoint.url(baseURL: admin)?.absoluteString, "\(admin)/api/router/agent/context/s-1")
        XCTAssertTrue(endpoint.query.isEmpty, "the key is the whole request")
        XCTAssertNil(endpoint.body)
        if case .router = endpoint.base {} else {
            XCTFail("the read lives behind the router, which knows which instance holds the session")
        }
    }

    /// A session key is a caller-supplied string going into one path segment: an unencoded `/` would reach the
    /// router as two segments and miss the binding entirely.
    func testASlashInTheSessionKeyStaysInsideItsSegment() throws {
        let encoded = "a%2Fb%3Fc%23d"
        let endpoint = ContextUsageEndpoint.usage(sessionId: "a/b?c#d")

        XCTAssertEqual(endpoint.path, "/api/router/agent/context/\(encoded)")
        // The absolute string is what goes on the wire; `URL.path` hands back a decoded string and would show
        // the two extra segments this encoding exists to prevent.
        XCTAssertEqual(
            try XCTUnwrap(endpoint.url(baseURL: admin)).absoluteString,
            "\(admin)/api/router/agent/context/\(encoded)"
        )
    }

    // MARK: - the three answers

    private func harness() async -> APIHarness {
        let harness = APIHarness()
        try? await harness.signIn()
        return harness
    }

    /// The shape a session with a billed call answers with
    /// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:49-59`).
    func testAFullPayloadBecomesAReading() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, Wire.success("""
        {"messageCount":42,"estimatedTokens":1800,"lastCallInputTokens":36000,"contextWindow":128000,\
        "windowSource":"MODEL_FIELD","ratio":0.28125,"triggerTokens":108000,"triggerMessages":50}
        """))

        let result = await harness.agents.contextUsage(sessionId: "s-1")

        let usage = try XCTUnwrap(result.value)
        XCTAssertTrue(usage.isReadable)
        XCTAssertEqual(usage.percentText, "28%")
        XCTAssertEqual(usage.basis, .billed)
    }

    /// `ResultVo.success(null)` is a session never bound to an instance
    /// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:397-407`).
    /// It has to arrive as a value the caller can hide, not as an error the caller would have to explain.
    func testANullDataAnswerIsNoReadingRatherThanAFailure() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, Wire.success())

        let result = await harness.agents.contextUsage(sessionId: "s-1")

        XCTAssertNil(result.failure)
        XCTAssertEqual(result.value, ContextUsage())
    }

    /// The other no-reading leg: the instance that holds the session has no live agent, and answers
    /// `ResultVo.error(...)` — a business code inside an HTTP 200, which the shared mapper hands over as a
    /// failure with the server's own sentence attached
    /// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt:123-134`).
    func testABusinessCodeIsAFailureCarryingTheServersSentence() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, Wire.business(500, "No context held for session s-1 on this instance"))

        let result = await harness.agents.contextUsage(sessionId: "s-1")

        guard case let .failure(error) = result else { return XCTFail("expected a failure") }
        XCTAssertEqual(error, .business(code: 500, message: "No context held for session s-1 on this instance"))
    }

    /// A payload that does not carry the whole shape is refused at the transport rather than defaulted: an
    /// omitted `ratio` decoded to `0` would pass the readability guard on a real window and tell the user
    /// their context is empty.
    func testAPartialPayloadIsADecodeFailureNotAZeroReading() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, Wire.success(#"{"messageCount":3,"contextWindow":128000}"#))

        let result = await harness.agents.contextUsage(sessionId: "s-1")

        XCTAssertEqual(result.failure, .decoding)
        XCTAssertNil(result.value)
    }
}

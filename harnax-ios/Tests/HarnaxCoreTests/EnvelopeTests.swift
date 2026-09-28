import XCTest

@testable import HarnaxCore

final class EnvelopeTests: XCTestCase {
    func testSuccessEnvelopeDecodesEveryField() throws {
        let envelope = try Fixture.decode(Envelope<LoginResponse>.self, "login-success")
        XCTAssertEqual(envelope.code, 200)
        XCTAssertEqual(envelope.message, "success")
        XCTAssertEqual(envelope.timestamp, 1_790_592_445_754)
        XCTAssertNotNil(envelope.data)
    }

    /// `ResultVo.error(message)` leaves `data` null and Jackson 3 drops null keys, so the real
    /// failure body has no `data` at all — the extra `isSuccess` key must not break decoding either.
    func testErrorEnvelopeHasNoDataKeyAndStillDecodes() throws {
        let envelope = try Fixture.decode(Envelope<LoginResponse>.self, "login-invalid-credentials")
        XCTAssertEqual(envelope.code, 400)
        XCTAssertEqual(envelope.message, "Invalid username or password")
        XCTAssertNil(envelope.data)
    }
}

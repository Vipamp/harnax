import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The two shapes that decide the ordering inside `ResponseMapper`: 401 is answered outside the envelope,
/// and a business failure is an HTTP 200 that carries a non-200 code inside it.
final class ResponseMapperTests: XCTestCase {
    struct Payload: Decodable, Equatable {
        let id: Int64
    }

    func testUnauthorizedIgnoresItsNonEnvelopeBody() {
        XCTAssertThrowsError(
            try ResponseMapper.map(Payload.self, data: Data(Wire.unauthorized.utf8), statusCode: 401)
        ) { error in
            XCTAssertEqual(error as? APIError, .unauthorized)
        }
    }

    func testForbiddenAlsoEndsTheSession() {
        XCTAssertThrowsError(
            try ResponseMapper.map(Payload.self, data: Data("{}".utf8), statusCode: 403)
        ) { error in
            XCTAssertEqual(error as? APIError, .unauthorized)
        }
    }

    func testBusinessFailureArrivesAsHTTP200() {
        let body = Wire.business(400, "用户名或密码错误")
        XCTAssertThrowsError(try ResponseMapper.map(Payload.self, data: Data(body.utf8), statusCode: 200)) { error in
            XCTAssertEqual(error as? APIError, .business(code: 400, message: "用户名或密码错误"))
        }
    }

    /// The server's text has to survive to the screen; `error.business` is only the fallback copy.
    func testBusinessMessageKeepsServerText() {
        let body = Wire.business(400, "用户名或密码错误")
        XCTAssertThrowsError(try ResponseMapper.map(Payload.self, data: Data(body.utf8), statusCode: 200)) { error in
            let apiError = error as? APIError
            XCTAssertEqual(apiError?.serverMessage, "用户名或密码错误")
            XCTAssertNil(apiError?.copyKey)
        }
    }

    func testTransportStatusWithoutEnvelopeStillReportsStatus() {
        let body = "<html>Bad Gateway</html>"
        XCTAssertThrowsError(try ResponseMapper.map(Payload.self, data: Data(body.utf8), statusCode: 502)) { error in
            XCTAssertEqual(error as? APIError, .business(code: 502, message: ""))
        }
    }

    /// `ResultVo.success(null)` is a real answer, and the caller declares `EmptyResponse` for it.
    func testAbsentDataIsValidForVoidEndpoints() throws {
        let value = try ResponseMapper.map(EmptyResponse.self, data: Data(Wire.success(nil).utf8), statusCode: 200)
        XCTAssertEqual(value, EmptyResponse())
    }

    func testAbsentDataIsUnpackableForRealPayloads() {
        XCTAssertThrowsError(
            try ResponseMapper.map(Payload.self, data: Data(Wire.success(nil).utf8), statusCode: 200)
        ) { error in
            XCTAssertEqual(error as? APIError, .unpackable)
        }
    }

    func testUnparsableBodyIsDecoding() {
        XCTAssertThrowsError(
            try ResponseMapper.map(Payload.self, data: Data("not json".utf8), statusCode: 200)
        ) { error in
            XCTAssertEqual(error as? APIError, .decoding)
        }
    }

    /// Jackson 3 drops nulls and adds a derived `isSuccess`, so the decoder must tolerate extra keys.
    func testExtraIsSuccessKeyIsIgnored() throws {
        let body = Wire.success("{\"id\":12}")
        XCTAssertEqual(try ResponseMapper.map(Payload.self, data: Data(body.utf8), statusCode: 200), Payload(id: 12))
    }
}

import Foundation
import HarnaxCore

/// Turns `(status, body)` into either a value or an `APIError`.
///
/// Two shapes force the ordering here: 401 is answered outside the envelope, and a business failure
/// arrives as HTTP 200 with `code: 400` inside it, so the status alone never tells the whole story.
public enum ResponseMapper {
    public static func map<T: Decodable>(_ type: T.Type, data: Data, statusCode: Int) throws -> T {
        if statusCode == 401 || statusCode == 403 {
            throw APIError.unauthorized
        }
        guard (200..<300).contains(statusCode) else {
            throw APIError.business(code: statusCode, message: serverMessage(in: data) ?? "")
        }
        let envelope: Envelope<T>
        do {
            envelope = try JSONDecoder().decode(Envelope<T>.self, from: data)
        } catch {
            throw APIError.decoding
        }
        guard envelope.code == 200 else {
            throw APIError.business(code: envelope.code, message: envelope.message)
        }
        guard let value = envelope.data else {
            // Endpoints that answer `ResultVo.success(null)` are declared as EmptyResponse by the caller.
            if let void = T.self as? HarnaxVoid.Type { return void.init() as! T }
            throw APIError.unpackable
        }
        return value
    }

    /// A container-level error page has no envelope, but its `message` is still the most useful text.
    private static func serverMessage(in data: Data) -> String? {
        struct Bare: Decodable { let message: String? }
        return (try? JSONDecoder().decode(Bare.self, from: data))?.message
    }
}

public protocol HarnaxVoid {
    init()
}

extension EmptyResponse: HarnaxVoid {}

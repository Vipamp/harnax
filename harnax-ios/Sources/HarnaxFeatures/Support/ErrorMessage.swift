import Foundation
import HarnaxCore
import HarnaxKit

/// One place that turns a failure into the words a user sees.
///
/// Priority follows the mockup's F2: the backend's own text wins when it supplied any, because that
/// string is already localised server-side against `Accept-Language`; the client dictionary is the
/// fallback for the transport and decoding cases where the server never got to speak.
public enum ErrorMessage {
    public static func text(for error: APIError) -> String {
        if let key = error.copyKey {
            if case let .throttled(seconds) = error { return hx("error.throttled", seconds) }
            return hx(key)
        }
        return error.serverMessage ?? hx("error.business")
    }
}

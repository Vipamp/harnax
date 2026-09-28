import Foundation

/// Failure taxonomy for one request. Three layers stay distinct because the remedy differs: a transport
/// failure retries itself, a business error is the server's answer and must be shown, and 401 is a
/// session event that logs the user out rather than surfacing text.
public enum APIError: Error, Equatable, Sendable {
    case offline
    case timeout
    /// 401. The body is not the usual envelope, so its message is never shown.
    case unauthorized
    /// HTTP 200 with a non-200 `code` in the envelope — the backend's business failure shape.
    case business(code: Int, message: String)
    /// Envelope decoded, `code` was 200, but `data` was absent.
    case unpackable
    /// Body was not valid envelope JSON at all.
    case decoding
    case invalidServerConfig(String)
    case throttled(seconds: Int)
    case refillPassword

    /// Catalog key for the copy to show. `nil` when the server already supplied readable text.
    public var copyKey: String? {
        switch self {
        case .offline: "error.offline"
        case .timeout: "error.timeout"
        case .unauthorized: "error.unauthorized"
        case .business: serverMessage?.isEmpty == false ? nil : "error.business"
        case .unpackable: "error.unpackable"
        case .decoding: "error.decoding"
        case .invalidServerConfig: "error.serverConfig"
        case .throttled: "error.throttled"
        case .refillPassword: "error.refillPassword"
        }
    }

    /// Server-side text, English-only in practice; shown as-is when there is nothing better.
    public var serverMessage: String? {
        if case let .business(_, message) = self { return message }
        return nil
    }

    public var throttleSeconds: Int? {
        if case let .throttled(seconds) = self { return seconds }
        return nil
    }
}

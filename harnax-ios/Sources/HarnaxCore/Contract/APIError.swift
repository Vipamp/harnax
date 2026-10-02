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
    /// The object store is switched off in this deployment: `minio.enabled=false` leaves
    /// `OutputFileController.kt:38` and `TeamArtifactController.kt:42` unregistered, so their routes answer 404
    /// as unknown paths. A named state, because the remedy is a server setting rather than anything the user
    /// did (`DESIGN.md` O8).
    case objectStoreDisabled
    /// Envelope decoded, `code` was 200, but `data` was absent.
    case unpackable
    /// Body was not valid envelope JSON at all.
    case decoding
    case invalidServerConfig(String)
    case throttled(seconds: Int)
    case refillPassword

    /// admin's own sentence for a path that matched neither a controller nor a static resource
    /// (`GlobalExceptionHandler.kt:91`, `:105`).
    static let unregisteredRouteMessage = "Requested resource not found"

    /// Whether this is admin saying the route does not exist, which is the shape a `@ConditionalOnProperty`
    /// controller leaves behind when its switch is off.
    ///
    /// Matched on the server's own sentence rather than on the status alone, because these routes also answer
    /// 404 for one file that is gone or not the caller's — and a controller's `ResponseEntity.notFound().build()`
    /// carries no body at all, so it stays an ordinary business error (`TeamArtifactController.kt:80-87`,
    /// `OutputFileController.kt:95`, `:105`).
    public var isUnregisteredRoute: Bool {
        if case let .business(code, message) = self {
            return code == 404 && message == Self.unregisteredRouteMessage
        }
        return false
    }

    /// Catalog key for the copy to show. `nil` when the server already supplied readable text.
    public var copyKey: String? {
        switch self {
        case .offline: "error.offline"
        case .timeout: "error.timeout"
        case .unauthorized: "error.unauthorized"
        case .business: serverMessage?.isEmpty == false ? nil : "error.business"
        case .objectStoreDisabled: "error.objectStoreDisabled"
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

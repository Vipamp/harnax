import Foundation
import HarnaxCore

public protocol HTTPRequesting: Sendable {
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionTransport: HTTPRequesting {
    private let session: URLSession

    public init(session: URLSession = URLSessionTransport.default) {
        self.session = session
    }

    /// 15s: SSE streams are the router's job and never go through this client, so a slow admin call is
    /// a failure rather than something to wait out.
    public static let `default`: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }()

    public func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError.unreachable
        }
        return (data, http)
    }

    /// Which of the four pre-answer outcomes the socket reported, because each names a different thing to
    /// go and check. The `default` is deliberately *not* `.offline`: claiming the user's network failed is
    /// the one reading this side has no evidence for, and an unknown code at worst says the server never
    /// answered, which is what almost every unsorted `URLError` really was.
    public static func map(_ error: Error) -> APIError {
        if error is CancellationError { return .cancelled }
        guard let urlError = error as? URLError else { return .requestNotSent }
        switch urlError.code {
        case .timedOut: return .timeout
        case .cancelled: return .cancelled
        case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .dataNotAllowed:
            return .offline
        case .badURL, .unsupportedURL: return .requestNotSent
        default: return .unreachable
        }
    }
}

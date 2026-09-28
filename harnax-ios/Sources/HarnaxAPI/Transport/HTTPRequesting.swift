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
            throw APIError.offline
        }
        return (data, http)
    }

    /// Everything the socket can say is folded into two user-facing outcomes; unknown failures read as
    /// offline because the remedy the user can act on is the same.
    public static func map(_ error: Error) -> APIError {
        guard let urlError = error as? URLError else { return .offline }
        switch urlError.code {
        case .timedOut: return .timeout
        default: return .offline
        }
    }
}

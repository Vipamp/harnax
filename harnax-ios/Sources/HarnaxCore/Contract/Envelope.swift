import Foundation

/// Wire shape of every admin/router JSON response.
///
/// Backend: `harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/ResultVo.kt:11-24`.
/// Jackson 3 omits nulls by default, so `data` is absent (not `null`) on error bodies, and the
/// serialised envelope carries an extra `isSuccess` key derived from `ResultVo.isSuccess()`.
public struct Envelope<T: Decodable>: Decodable, Sendable where T: Sendable {
    public let code: Int
    public let message: String
    public let data: T?
    public let timestamp: Int64

    public init(code: Int, message: String, data: T?, timestamp: Int64) {
        self.code = code
        self.message = message
        self.data = data
        self.timestamp = timestamp
    }
}

/// Stand-in for endpoints whose `data` is unused (`ResultVo<Void>`).
public struct EmptyResponse: Decodable, Equatable, Sendable {
    public init() {}
}

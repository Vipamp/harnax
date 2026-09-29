import Foundation
import HarnaxCore

/// The conversation's chat configuration, read and written through admin.
///
/// Both routes address the row by its **string** business key, so this is the one session surface that cannot
/// reuse the numeric id the row menu already has (`SessionController.kt:111-141`).
extension AdminClient: SessionConfiguring {
    /// Three answers have to stay three states for the caller, and this method is where they are separated:
    /// `code = 404` for a row that is not there or not in the caller's tenant, `code = 403` for one that exists
    /// but is switched off — a business 403 carried by HTTP 200 (`SessionServiceImpl.kt:276-279`), so it does
    /// not trip the transport's `403 → unauthorized` rule — and `unpackable` for a `code = 200` that carried no
    /// row at all. The shared transport already produces all three; the sheet only has to read the code back.
    public func sessionConfig(sessionId: String) async -> Result<SessionSummary, APIError> {
        await client.send(SessionSummary.self, SessionEndpoint.config(sessionId: sessionId))
    }

    /// The body is `SessionChatChange`, whose encoder writes every key it has — the changed ones and the
    /// unchanged ones as explicit nulls. That is not decoration: the three flags are `Boolean? = false` on the
    /// DTO and the stack registers `jackson-module-kotlin` (`harnax-admin/pom.xml:233`), which fills an
    /// *absent* property with its default, so a body that simply left a field off would switch it off
    /// (`SessionServiceImpl.kt:288-300` applies only non-null values).
    ///
    /// "Nothing changed" is not handled here, because it cannot arrive: the only initialiser the screens use
    /// is `SessionChatChange.init?(stored:draft:)`, which answers `nil` for a draft that moved none of the four
    /// writable fields, so the sheet keeps its save off and no call is made at all.
    public func updateSessionConfig(
        sessionId: String,
        _ change: SessionChatChange
    ) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? SessionEndpoint.updateConfig(sessionId: sessionId, change) else {
            return .failure(.decoding)
        }
        return await client.send(EmptyResponse.self, endpoint)
    }
}

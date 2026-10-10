import Foundation
import HarnaxCore

/// The owner's own memory routes: `/api/admin/memory` and its three reads/writes
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:42,49,64,83`).
///
/// The one shape that is easy to copy wrong is the path parameter: `{agentId}` binds the **runtime agent
/// name**, not the database id (`MemoryController.kt:70`, `:89`), so it has to be percent-encoded as a path
/// segment — a name containing a `/` would otherwise be read as another segment, and a name with a `?` or a
/// `#` would open a query or a fragment on a request that has neither.
enum MemoryEndpoint {
    static let root = "/api/admin/memory"

    static func list() -> Endpoint {
        Endpoint(.get, path: root)
    }

    static func read(agentName: String) -> Endpoint {
        Endpoint(.get, path: "\(root)/\(Endpoint.segment(agentName))")
    }

    static func delete(agentName: String) -> Endpoint {
        Endpoint(.delete, path: "\(root)/\(Endpoint.segment(agentName))")
    }
}

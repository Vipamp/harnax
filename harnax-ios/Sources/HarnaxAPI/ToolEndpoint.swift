import Foundation
import HarnaxCore

/// The three tool routes this stack touches. All are GETs under `/api/admin/tools`, and there is nothing
/// else to model: this API has no write path (`AgentToolController.kt:13-15`).
///
/// The table screen reads `/builtin` rather than `/page`, which is also what the console reads
/// (`harnax-webui/src/services/ant-design-pro/tool.ts:13-16`). That is a data-source decision with two
/// consequences written down where they bite: the read carries no filter at all, so the search and the
/// status choice are applied to the rows this side already holds (`ToolListViewModel`), and the answer is a
/// bare array — `ResultVo<List<AgentToolResponse>>` (`AgentToolController.kt:72-78`) — so there is no page
/// counter to reconcile and nothing to append a second page to.
enum ToolEndpoint {
    /// `AgentToolController.kt:67-78`, backed by `SELECT * FROM agent_tool WHERE active = 1 ORDER BY name`
    /// (`AgentToolMapper.xml:64-67`). No query item: the route takes no parameter, and the row order is the
    /// server's `name` order — which is not the `/page` route's `status DESC, update_time DESC`, so the
    /// table's own order is this route's, exactly as the console's is.
    static var builtin: Endpoint {
        Endpoint(.get, path: "/api/admin/tools/builtin")
    }

    /// `AgentToolController.kt:41-52`: a missing id is a normal envelope answer of `code: 404`, not an HTTP
    /// 404, so the caller's error mapping already carries the server's "tool not found" sentence.
    static func detail(id: Int64) -> Endpoint {
        Endpoint(.get, path: "/api/admin/tools/\(id)")
    }

    /// `AgentToolController.kt:54-65`, the agent wizard's tool candidates. No path parameter and no query
    /// item: the server does the filtering (`WHERE status = 1 AND active = 1 AND is_required = 0`, ordered
    /// by `name`, `AgentToolMapper.xml:60-62`), so narrowing or re-sorting this list locally would simply
    /// diverge from the web form.
    static var available: Endpoint {
        Endpoint(.get, path: "/api/admin/tools/available")
    }
}

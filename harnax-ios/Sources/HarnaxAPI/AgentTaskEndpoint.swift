import Foundation
import HarnaxCore

/// The scheduled-task routes the three screens touch: the task surface and the log surface (`§1`, `§5.3`).
///
/// Three shapes here cannot be inferred from the neighbouring domains, which is why they are spelled out per
/// route rather than folded into a shared helper:
///
/// - the toggle is `POST /toggle/{id}?status=`, id *after* the verb — agents and teams do the same, but env
///   variables put the id first (`EnvVariableController.kt:95-99`) and API Keys put `status` in the body.
/// - the filter that means "name" is `name` on the page route and `taskName` on the log route
///   (`AgentTaskController.kt:58` against `:174`), and the flag this page filters on is `taskStatus`, not the
///   `status` every other list uses. The one bare `status` in this file is the toggle's 0/1.
/// - `trigger`, `delete` and `update` all address the task by `/api/admin/agent-tasks/{id}…`, while
///   `toggle/{id}` does not — so the id is interpolated two different ways in one file, on purpose.
enum AgentTaskEndpoint {
    static let root = "/api/admin/agent-tasks"

    /// `agentId` is a real parameter of this route (`AgentTaskController.kt:59`) that neither the console's
    /// list nor the iOS one exposes, so it is absent here by decision and not by omission.
    static func page(name: String?, taskStatus: Int?, num: Int, size: Int) -> Endpoint {
        var items = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        if let name = hxPresented(name) {
            items.append(URLQueryItem(name: "name", value: name))
        }
        if let taskStatus {
            items.append(URLQueryItem(name: "taskStatus", value: String(taskStatus)))
        }
        return Endpoint(.get, path: "\(root)/page", query: items)
    }

    /// Answers `ResultVo<Void>`; admin stamps `agentName` from the `agentId` before forwarding
    /// (`AgentTaskController.kt:88,230-239`), so the body carries the id only.
    static func create(_ draft: AgentTaskDraft) throws -> Endpoint {
        Endpoint(.post, path: root, body: try APIClient.encodeBody(draft))
    }

    /// The bare `{id}` route, with a body whose missing keys mean "keep the stored value".
    static func update(id: Int64, _ change: AgentTaskChange) throws -> Endpoint {
        Endpoint(.put, path: "\(root)/\(id)", body: try APIClient.encodeBody(change))
    }

    /// `1` starts, `0` pauses. The name is `status` on this route even though the page filter for the same
    /// column is `taskStatus` (`:129-131` against `:58`).
    static func toggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .post,
            path: "\(root)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    /// One run now. Shares its prefix with `DELETE /{id}` and `PUT /{id}` and differs only in the suffix, so a
    /// dropped `/trigger` would silently become a delete.
    static func trigger(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(root)/\(id)/trigger")
    }

    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(root)/\(id)")
    }

    /// The one route in this domain admin answers itself instead of forwarding: a `{id,name}` projection of the
    /// live agents, with no query parameters at all (`AgentTaskController.kt:202-210`).
    static func agents() -> Endpoint {
        Endpoint(.get, path: "\(root)/agents")
    }

    /// One entry of `GET /api/admin/agent-tasks/{id}/logs` (`§5.3`).
    ///
    /// `taskName` is a real parameter of this route (`AgentTaskController.kt:174`) and is absent by decision: the
    /// modal is already scoped by the task in the path, and the console does not offer the filter inside it
    /// either. The three time spellings cannot be guessed — the pair is `startTimeFrom`/`startTimeTo` in
    /// `yyyy-MM-dd HH:mm:ss`, while this domain's *task* page names its status filter `taskStatus` and this one
    /// names it plain `status` (`:58` against `:175`).
    static func logs(
        taskID: Int64,
        filter: AgentTaskLogFilter,
        num: Int,
        size: Int
    ) -> Endpoint {
        var items = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        if let state = filter.state {
            items.append(URLQueryItem(name: "status", value: String(state.rawValue)))
        }
        if let from = filter.from {
            items.append(URLQueryItem(name: "startTimeFrom", value: hxWallClockString(from)))
        }
        if let to = filter.to {
            items.append(URLQueryItem(name: "startTimeTo", value: hxWallClockString(to)))
        }
        if let keyword = hxPresented(filter.keyword) {
            items.append(URLQueryItem(name: "keyword", value: keyword))
        }
        return Endpoint(.get, path: "\(root)/\(taskID)/logs", query: items)
    }

    /// `POST /logs/{logId}/stop`, addressed by the **log** id (`AgentTaskController.kt:166-168`). It sits under
    /// the same root as `DELETE /{id}` with only `logs/` between them, and it is the one write in this domain that
    /// is not gated by `scheduler.enabled` (`SchedulerController.kt:132-141`), so it works on a node that refuses
    /// to schedule.
    static func stopLog(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(root)/logs/\(id)/stop")
    }
}

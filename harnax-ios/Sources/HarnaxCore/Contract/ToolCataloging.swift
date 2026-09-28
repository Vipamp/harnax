import Foundation

/// The tool surface, and it is read-only in every direction: tools are declared by annotations in the
/// running code, so the stack has no create/update/delete route to offer
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:13-15`,
/// whose five mappings are all `@GetMapping`).
///
/// Two of those routes matter to a list screen:
/// `GET /api/admin/tools/page` (`:26`) and `GET /api/admin/tools/{id}` (`:41`). `/available` and `/builtin`
/// answer an unpaged array for the binding wizard and the web table, and `/{id}/required-env-params` answers
/// key names for a form that pre-checks them — neither is a list screen's data source here.
///
/// The page route's keyword parameter is called `keyword`, not `name` as the agent and team routes have it
/// (`AgentToolController.kt:31`), and the server matches it against `name`, `display_name` and `description`
/// only — the Chinese display column is searchable in the web table because that one filters locally
/// (`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:45-57`).
///
/// `status` is the raw 0/1 column; leaving it off the request means "both".
public protocol ToolCataloging: Sendable {
    func toolPage(keyword: String?, status: Int?, num: Int, size: Int) async -> Result<Page<ToolSummary>, APIError>

    /// The same row the page answers, read again by id. A tool's parameter table is re-synced at every
    /// boot, so a row that has been on screen a while can be refreshed without reloading the list.
    func toolDetail(id: Int64) async -> Result<ToolSummary, APIError>
}

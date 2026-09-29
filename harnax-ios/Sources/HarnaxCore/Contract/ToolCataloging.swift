import Foundation

/// The tool surface, and it is read-only in every direction: tools are declared by annotations in the
/// running code, so the stack has no create/update/delete route to offer
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:13-15`,
/// whose five mappings are all `@GetMapping`).
///
/// Two of those routes matter to a list screen:
/// `GET /api/admin/tools/page` (`:26`) and `GET /api/admin/tools/{id}` (`:41`). `/{id}/required-env-params`
/// answers key names for a form that pre-checks them, and `/builtin` answers the web table's own projection;
/// neither is used here.
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

    /// The candidates the agent wizard's tool step offers: `GET /api/admin/tools/available` (`:54-65`),
    /// an unpaged `ResultVo<List<AgentToolResponse>>` with no query parameters at all.
    ///
    /// It is not a thinner row — the payload is the same DTO as the page, so `ToolSummary` decodes it
    /// verbatim, `envParams` included, and the wizard needs those declarations to build binding rows. The
    /// server has already filtered it: `WHERE status = 1 AND active = 1 AND is_required = 0 ORDER BY name`
    /// (`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:60-62`). Two consequences the wizard
    /// must not re-implement: a tool that is mandatory is *absent* from this list because it is not
    /// selectable, and the order is the server's `name` order rather than anything local.
    func availableTools() async -> Result<[ToolSummary], APIError>
}

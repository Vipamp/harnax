import Foundation

/// The tool surface, and it is read-only in every direction: tools are declared by annotations in the
/// running code, so the stack has no create/update/delete route to offer
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:13-15`,
/// whose five mappings are all `@GetMapping`).
///
/// Two of those routes matter to the table screen: `GET /api/admin/tools/builtin` (`:67`) is the row set,
/// and `GET /api/admin/tools/{id}` (`:41`) re-reads one row. `/{id}/required-env-params` answers key names
/// for a form that pre-checks them, and `/page` is the paginated projection of the same rows; neither is
/// used here.
///
/// The list reads `/builtin` because that is the route the console reads
/// (`harnax-webui/src/services/ant-design-pro/tool.ts:13-16`), and the choice decides where the search
/// happens. `/page` hands its `keyword` to MySQL, which matches `name`, `display_name` and `description`
/// only — the Chinese column is not in the pattern, so 读取文件 finds nothing there
/// (`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:45-57`). `/builtin` carries no filter at
/// all and answers every active row, which is what lets this side match the four columns the row actually
/// shows (`ToolListViewModel`), Chinese included. Two facts come with the route: the answer is a bare array
/// with no page counter, so there is nothing to append, and the order is the server's `ORDER BY name`
/// (`AgentToolMapper.xml:64-67`) rather than the page route's `status DESC, update_time DESC`.
///
/// `status` is the raw 0/1 column, and it is filtered here rather than sent: leaving the choice on `all`
/// judges nothing.
public protocol ToolCataloging: Sendable {
    /// `ResultVo<List<AgentToolResponse>>` (`AgentToolController.kt:72-78`) — the same DTO as every other
    /// tool route, so the same row type decodes it.
    func builtinTools() async -> Result<[ToolSummary], APIError>

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

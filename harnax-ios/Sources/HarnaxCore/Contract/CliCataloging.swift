import Foundation

/// The CLI surface: four reads and the one switch.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:37-107`.
/// That class registers no create, update or delete route at all — a package is published by dropping its
/// `.harnaxcli.zip` into admin's package directory, where `CliPackageAutoRegistrar` reads it at startup
/// (design D2). So this protocol has no writer besides `setCliStatus`, and iOS must not offer a form.
///
/// `toggleCliStatus` is deliberately *not* refused while agents still bind the CLI (design D9,
/// `service/impl/CliServiceImpl.kt:53-64`): the blast radius is shown to the operator instead, which is why
/// `cliRelatedAgents` exists as its own read and why the caller has to ask before it flips the switch off.
///
/// The method names carry the domain because one `AdminClient` conforms to this and to `AgentCataloging`
/// and `TeamCataloging` at once, and a bare `page(status:)` with a different element type cannot sit
/// beside the other two.
public protocol CliCataloging: Sendable {
    /// `name` is a `LIKE` keyword and `status` the raw 0/1 column; both optional on the wire
    /// (`CliController.kt:39-50`).
    func cliPage(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<CliSummary>, APIError>

    /// The only reader of the three detail-only fields: `payloadDigest`, `depsApt`, `runtimeEnv`.
    func cliDetail(id: Int64) async -> Result<CliSummary, APIError>

    /// Who binds this CLI right now. A failure here must never be rendered as an empty list — the two mean
    /// opposite things, and an empty answer is the one that lets a disable run without confirmation.
    func cliRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError>

    /// Sessions of every agent bound to this CLI, the same shape the agent and team reads answer
    /// (`service/impl/AgentSessionRefreshService.kt:182-188`). Pushing them goes through `SessionRefreshing`.
    func cliRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError>

    /// The kill switch. `status` travels as a 0/1 query parameter and no body; the shipped skill follows the
    /// same value server-side (`CliServiceImpl.kt:66-80`).
    func setCliStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
}

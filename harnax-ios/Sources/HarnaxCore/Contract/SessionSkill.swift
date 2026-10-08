import Foundation

/// One row of the session's own skill panel: what this conversation's agent wrote, and whether the session is
/// already allowed to use it.
///
/// The row answers two questions at once, because the screen it feeds reads two routes to build it — Admin's
/// PENDING nominations for this session (`SkillDraftController.kt:50`, filtered by `sessionId` since Task 10)
/// and agent-service's enabled directory
/// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SessionSkillController.kt`'s
/// `SessionSkillView(name, description, enabledAt)`), reached through the router's proxy
/// (`GET /api/router/agent/session-skills/{sessionId}`,
/// `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt`).
///
/// `enabledAt` is kept as the server's own text and never re-formatted: the two stamps this app prints elsewhere
/// come from `RowMeta`/`SkillDraftCopy` slicing rather than a `DateFormatter`, and this column has no rule of
/// its own to add.
public struct SessionSkillRow: Identifiable, Hashable, Sendable {
    /// The skill's name — also the only handle the enable route takes, which is why it is the identity.
    public let name: String
    public let description: String?
    /// Whether this session can use it *now*. `false` is not a refusal: it means the agent proposed the skill
    /// and nobody has enabled it into this conversation yet.
    public let enabled: Bool
    /// When the enable happened, or `nil` for a row that has never been enabled.
    public let enabledAt: String?

    public var id: String { name }

    public init(
        name: String,
        description: String? = nil,
        enabled: Bool,
        enabledAt: String? = nil
    ) {
        self.name = name
        self.description = description
        self.enabled = enabled
        self.enabledAt = enabledAt
    }
}

/// The panel's one merge: the queue says what this session nominated, agent-service says what it enabled,
/// and a name present in both is one row that knows it is enabled.
///
/// Lives in Core so iOS and the console cannot drift apart on which read wins a field
/// (`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx` does the same for the same rows).
public enum SessionSkillRules {
    /// The slice of a queue row this screen reads. Not `SkillDraftRow`: the merge has nothing to do with
    /// the wire shape, and building a wire row in a test means decoding a fixture.
    public struct Draft: Equatable, Sendable {
        public let name: String
        public let description: String?

        public init(name: String, description: String?) {
            self.name = name
            self.description = description
        }
    }

    /// Draft order is kept: the queue answers newest proposal first and the panel has no sort of its own to
    /// re-derive. The directory's leftovers come after, by name, so a re-read cannot reshuffle the head of the
    /// list.
    ///
    /// The queue wins `description` wherever both reads name the same skill — the directory's copy is whatever
    /// was in `SKILL.md` when the enable copied it, and the draft is the live text. Only `enabledAt` is taken
    /// from the directory, since nothing else on this screen has a stamp the queue could supply.
    public static func merged(drafts: [Draft], enabled: [SessionSkillRow]) -> [SessionSkillRow] {
        // `uniquingKeysWith` rather than `uniqueKeysWithValues`: a duplicate name in the directory is a server
        // contract break, and the panel's job is to show the row once — not to end the process over it.
        var remaining = Dictionary(
            enabled.map { ($0.name, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var rows: [SessionSkillRow] = []
        for draft in drafts {
            let hit = remaining.removeValue(forKey: draft.name)
            rows.append(
                SessionSkillRow(
                    name: draft.name,
                    description: draft.description,
                    enabled: hit != nil,
                    enabledAt: hit?.enabledAt
                )
            )
        }
        // Enabled but no longer nominated: the agent rewrote or archived the draft after the enable. The
        // session is still using it, so the row stays.
        for (name, row) in remaining.sorted(by: { $0.key < $1.key }) {
            rows.append(SessionSkillRow(name: name, description: nil, enabled: true, enabledAt: row.enabledAt))
        }
        return rows
    }
}

/// The two reads behind the session's own skill panel, and the one action it offers.
///
/// Its own protocol rather than a member of `SkillDraftCataloging`: the queue is a reviewer's surface on the
/// admin routes and answers about a draft that may never become anything, while these two legs are scoped to
/// one conversation and one of them is not an admin route at all. `ContextUsageReading` set the precedent for
/// a session-scoped router read with a protocol of its own (`ContextUsage.swift:171-179`).
public protocol SessionSkillReading: Sendable {
    /// Both reads merged (`SessionSkillRules.merged`).
    ///
    /// A failure of *either* leg throws. Neither leg may answer "this session wrote no skill" on its own behalf:
    /// the queue is a read the reviewer's service can be down for and the directory is a read the router can
    /// refuse to answer, and either one swallowed into an empty list would look like a conversation whose agent
    /// never proposed anything.
    ///
    /// A session with no running sandbox is **not** such a failure. The directory answers an unbound or stopped
    /// session with an empty list — an answer, not a refusal — and 410 belongs to `enable` alone, the only call
    /// here that changes anything (`docs/superpowers/specs/2026-10-08-session-skill-lifecycle-design.md`: §7 has
    /// the directory answer empty for an absent container and for an unbound session, §10 verifies that read as
    /// empty against the enable's 410, and `harnax-webui/src/services/ant-design-pro/sessionSkill.ts` says the
    /// same about the same two routes).
    func rows(sessionId: String) async throws -> [SessionSkillRow]

    /// Copy one of this session's drafts into its enabled set.
    ///
    /// Refusals are named by their own cause (`SessionSkillRefusal`), and the transport's non-business failures
    /// arrive as code `-1` rather than as silence: a button that did nothing is the one answer this action must
    /// not give.
    func enable(sessionId: String, name: String) async throws
}

/// Why an enable was refused, as the envelope's own code names it.
///
/// The five codes are `SessionSkillController`'s refusals plus the router's: 403 for a scan verdict that blocks
/// (`EnableOutcome.Blocked`), 409 for the ten-skill ceiling (`EnableOutcome.Full`), 404 for a draft that is gone
/// (`SourceMissing`), 500 for a container that refused the copy (`Failed`), and 410 for a session with no running
/// sandbox — either the store's `NoSandbox` or `SessionRouterService` answering it before the request is placed
/// anywhere. Anything else, transport included, says only that the enable did not happen: a code this app has not
/// been told the meaning of must not claim the draft is gone.
public struct SessionSkillRefusal: Error, Equatable, Sendable {
    public let code: Int
    public init(code: Int) { self.code = code }

    /// The catalogue key for this refusal. Every arm resolves in both shipped languages; `-1`, which the client
    /// uses for a failure that never carried an envelope code, resolves to the plain "it did not work".
    public var messageKey: String {
        switch code {
        case 403: return "chat.skills.blocked"
        case 409: return "chat.skills.full"
        case 410: return "chat.skills.noSandbox"
        case 404: return "chat.skills.sourceGone"
        case 500: return "chat.skills.copyFailed"
        default: return "chat.skills.enableFailed"
        }
    }
}

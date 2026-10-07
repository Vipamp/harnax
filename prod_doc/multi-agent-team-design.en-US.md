# Harnax Multi-agent Team Design (English)

> Chinese version: [multi-agent-team-design.zh-CN.md](./multi-agent-team-design.zh-CN.md).
>
> This document describes the current implementation: the team configuration model, the page flows, the assembly chain, sandboxes, artifact handover, events and confirmations. Every statement maps to Kotlin or TypeScript in this repository; the file index at the end lists the entry points.

## 1. Goal and Reuse Boundary

The product shape of multi-agent collaboration is: a user assembles a team, states a goal to the lead, the lead delegates concrete work to members, reviews what they report and summarizes the answer.

The implementation makes a team a standalone configuration and reuses the existing agent infrastructure as far as possible:

- A team carries only the lead's own configuration: model, system prompt and skills. A member's capabilities stay in its own `agent` row; joining a team copies no second set of tools, MCP servers, CLI packages or credentials.
- Configuration delivery, model and tool assembly, session routing, sandbox management and MinIO object storage are the existing implementations; the team adds its own resolution branch and its own tools.
- Lead and members run inside the same agent-service instance. Members use their own session state and their own sandbox, created on demand; the lead creates no execution sandbox.
- No workflow engine and no remote agent discovery service were introduced, and a team opens no second event channel.
- Ordinary agents keep their configuration UI and their standalone usage: the team's role restrictions apply to the instance a team run assembles.

## 2. Product Boundaries

| Subject | Statement |
|----|-----------|
| The team object | A team is its own configuration object: not a kind of `agent` row, and not a switch on some agent. |
| Where the lead comes from | The lead *is* configuration the team carries: `team.system_prompt`, `team.model_id` plus team-level skill bindings. No `agent` row stands for the lead; `agent` has exactly two roles — conversable on its own, or a member of some team. |
| Bindings on a team | `team_skill_binding` is the only binding table a team owns. Tool, MCP and CLI are never configured on a team, and the lead's three sections are empty by assembly. |
| Where members come from | Members reference existing agents. One agent can sit in several teams and still be used standalone; each member is assembled with its own configuration. |
| Division of work | The lead decomposes, delegates, reviews, re-delegates and summarizes. Concrete execution — research, code, file handling, report writing — belongs to members. |
| Reach of the team role | The team role affects only the instance this run assembles; the `agent` row's persisted configuration is untouched. |
| Sandboxes and artifacts | Each member executes in its own sandbox and hands files over through MinIO artifacts; members share no workspace. The lead has neither a sandbox nor a workspace. |
| Where a decision lands | A member's execution is visible, actions needing approval go to the user, and the user's decision reaches the member run that asked. |
| Delegation shape | Delegation is foreground, sequential and single-level: one `team_delegate` call blocks until the member reports back, and one member runs one task at a time. |

Sequential delegation is an invariant the assembly and the runtime enforce together: `TeamOrchestrator` claims a member by its id (`busyMembers`), and a second concurrent delegation to the same member is refused with readable text the model can act on instead of a silent queue.

## 3. Objects, Data and Sessions

### 3.1 The Three Objects

| Object | Holds | Does not hold |
|--------|-------|---------------|
| Agent | model, prompt, tools, MCP, skills, CLI configuration | the role of any team's lead; no second capability set for being a member |
| Team | name, description, the lead's system prompt and model, status and ownership, the lead's skill bindings | tools, MCP, CLI; a reference to some agent as its lead |
| Team member | the team reference, the member agent reference, what that agent is responsible for in this team | the member's model, tools, credentials, skills |

The responsibility note (`delegation_description`) starts from the agent's own description, is editable per team and is never written back to `agent.description`.

### 3.2 Tables and Columns

The DDL lives in `harnax-admin/src/main/resources/db/migration/`; the current shape is:

| Target | Content |
|--------|---------|
| `team` | `id`, `tenant_id`, `name`, `description`, `system_prompt`, `model_id`, `status`, `is_public`, `creator`, `active`, timestamps |
| `team_skill_binding` | `team_id` + `skill_id`, unique as a pair. No environment column: per-skill environment values have no consumer on either side |
| `team_member` | `team_id` + `member_agent_id` (unique as a pair), `delegation_description` |
| `team_artifact` | `file_id` (UUID, unique), `tenant_id`, `session_id` (root team session), `team_id`, `member_agent_id`, `child_session_id`, `file_name`, `mime_type`, `size_bytes`, `object_key` |
| `session` | `agent_id` is nullable; a team session has `agent_id` NULL and a non-null `team_id` |

`team.name` uniqueness is enforced in the service per tenant (`TeamMapper.selectByName`), not by a database key: rows are logically deleted (`active = 0`), and a unique key would make the name of a deleted team impossible to reuse forever.

`team_artifact.team_id` and `child_session_id` are written at publish time; ownership and authorization resolve on `tenant_id` + `session_id`, so those two columns stay a lead for manual investigation.

Attribution of a run comes from that run's own spec: for the lead, `AgentSpec.attributableAgentId` is null (its `id` is the lead sentinel 0 and the property reads that back as absence); for a member it is the member's own `agent.id`. So a lead's rows in `token_stats`, `tool_invocation_log` and `process_log` carry no agent attribution while a member's rows attribute to the member, and `process_log` attribution is written into the middleware each instance builds — building a member does not change where the lead's later rows go.

### 3.3 Session Typing and the Team Entry

There are two kinds of session, told apart by `session.team_id`:

- Non-null: a team session. `agent_id` is NULL, and title, description, system prompt, owner and model are snapshotted from the `team` row at creation for display.
- Null: an ordinary agent session.

The `session_id` prefix tells them apart not at all: a team chat opened from the web is still an ordinary `web-` id. The runtime asks admin (`GET /sessions/{sessionId}/team`, which reads the session row's `team_id`) and picks its resolution endpoint accordingly. `chn-` and `task-` conversations have no `session` row and therefore can never be team sessions.

A team session resolves its lead by `team_id`, so a client cannot submit a lead identity; `SessionServiceImpl.updateSession` refuses to attach an `agentId` to a team session, and `team_id` is written only at creation — the update statement never touches it.

### 3.4 Validation, Visibility and Lifecycle

Saving a team (`TeamServiceImpl.createTeam` / `updateTeam`) checks:

- The name is unique within the tenant.
- The lead's model exists, belongs to this tenant and is available to the caller (shared within the tenant or created by them), is enabled and is of type chat. The runtime adds no second gate: delivery only asks whether the model row resolves, so switching a model off after saving does not stop a team from running on it; deleting the model row makes the lead fail at build time when no model configuration can be found.
- At least one member, member ids present and not repeated; each member must exist, share the tenant, be enabled and be visible under the caller's own read rule (`is_public` or created by them). Seeing a team does not hand out its private agents.
- The lead's skills pass the same selectable range and write-time checks as on the agent side (`SkillBindingResolver.resolveBindable`): missing, disabled, built-in CLI repository source and name collisions are all refused.
- On update, omitting `members` leaves the roster alone; `skillIds` null leaves the lead's skills alone and an empty list clears them. The skill set is resolved before anything is written.

Team visibility is `is_public OR creator`, the same rule the list query applies: read, edit, delete, enable and disable all go through `requireVisibleTeam`, and another user in the same tenant gets `Team not found` for a private team.

`deleteTeam` refuses while the team still has sessions (`active = 1`) and names them — up to 5 ids, the rest collapsed into an ellipsis — so those sessions have to be deleted one by one first. Deleting a session is also what cleans its team artifacts (`TeamArtifactCleaner`). A state where `session.team_id` still points at a deleted team is therefore unreachable.

Runtime resolution (`InternalApiController.resolveTeamSpec`) does not degrade silently: a missing team, a disabled team, a team whose tenant differs from the session's, an empty roster, or a member agent that is gone or disabled makes the whole resolution throw. A team session never starts on a partial roster.

After a team's configuration changes, an already cached runtime instance keeps serving from the spec it was built with. The team list's related-sessions entry lists the team's sessions, and together with `agents/refresh-sessions` it pushes the REFRESH command so the next message re-resolves from admin and rebuilds the lead and each member.

## 4. The Team Pages

### 4.1 Team Management

`harnax-webui/src/pages/team/index.tsx` is a standalone entry: paginated list, name search, create, edit, enable/disable switch, related sessions and delete. The row offers no "start chat" — a team session is created on the session page, see "Creating a team session vs. a single-agent session".

Deletion follows the same rule as the service: the backend refuses while sessions exist and the frontend surfaces the reason, session ids included.

### 4.2 The Two-Step Wizard

Create and edit share `TeamWizard.tsx`, two steps:

```text
Step 1 · Basics and skills                        [Next]
Team name   [Research report team]
Description [Collects material, analyses it, writes the report]
System prompt [You are the lead of this collaboration…]   ← the lead's whole prompt
Model       [qwen3-max]
Public      [on/off]
Skills      [Research standards] [Report writing standards] ← Skill only, no Tool/MCP/CLI

Step 2 · Members                                [Add member]
Researcher    Responsibility: gather material and cite sources
Analyst       Responsibility: analyse data, distill findings
Writer        Responsibility: turn material and findings into a report
                                              [Back] [Cancel] [Save]
```

Details:

1. Name, description, system prompt and model are required in step 1, matching step 1 of the agent wizard; skill picking reuses the agent side's `SkillConfigPanel` with the same source filtering and the same pre-submit checks (`findSkillIssue` / `describeConfigIssue`).
2. The model picker lists only usable models. If the lead's model has since been disabled, deleted or switched to a non-chat type, edit mode prepends the current value as a disabled option with a reason, so a save refusal does not point at a bare id.
3. Members are handled by `MembersField.tsx`: the selectable range is the agents the caller may use, and the responsibility note starts from the agent's description.
4. Unavailable members and skills stay visible in edit mode, flagged: the runtime simply does not load them, and hiding the row from the page would keep a bad binding invisible.
5. The wizard has no tool, MCP or CLI fields — the capability boundary is a property of the configuration shape, and the assembly guard is the second line.
6. Members are identified by stable server ids; display names and responsibility notes are for reading and for delegation decisions.

### 4.3 Creating a Team Session vs. a Single-Agent Session

In the session page's "new session" dialog (`SettingsModal.tsx`), the executor picker is grouped into Agents and Teams with values like `agent:12` or `team:3`. Choosing a team submits `teamId` and no `agentId`, and `SessionServiceImpl.createSession` checks that the team exists, shares the tenant, is visible to the caller, is enabled and has at least one member before creating the team session.

The ordinary agent-session path is unchanged, and there is no way to switch an existing plain session into team mode.

The session list and header branch on `session.teamId`: a team session carries a Team tag, its toolbar gains an artifacts entry opening `TeamArtifactsDrawer`, and the detail panel (`DetailModal.tsx`) labels the associated object "Team Lead", reads the lead's name and description off the team, and leaves the agent-only blocks out.

### 4.4 What a Team Session Shows

There is one SSE stream and one conversation thread:

- The lead's turn stays one continuously appended bubble; on a team session that bubble carries the lead's name (taken from the session's snapshot name, shown only when `teamId` is set) so it reads against the members inside the cards.
- A member's run renders inside the `team_delegate` tool card that delegated it, not as a sibling bubble.
- The confirmation entry sits inline in the member run that asked.
- The artifact list lives in the drawer, downloadable by `fileId`, with the reference copyable so it can be pasted back to the lead or a member.

## 5. What the Lead and a Member May Do

Assembly happens in one place, `HarnessAgentLauncher.createAgentBase`, and `TeamRole` picks the branch. The role is passed in by the runtime, so configuration cannot talk an instance into the other role.

| Capability | Team lead | Team member | Ordinary agent |
|------------|-----------|-------------|----------------|
| Model and prompt | the team row's model; `team.system_prompt` plus the lead block (roster and working rules) | its own model and prompt, plus the member file rule and this task brief | unchanged |
| Session switches | deep thinking, web search, planning and permission mode from the session row | its own model configuration; permission mode from the root session | unchanged |
| Business tools | not assembled, and platform-required tools are not appended | assembled from its own configuration, including required tools and per-method grant trimming | unchanged |
| Meta tool | off (`enableMetaTool` is not delivered), so tools cannot be acquired at runtime | per its own configuration | per its own configuration |
| MCP | not connected (the delivered list is empty and assembly refuses again) | connected per its own configuration, with the same per-user OAuth and stdio rules as any agent | unchanged |
| Skills | skill content loads through the same path as any agent; the files a skill ships are neither readable nor executable for a lead, and assembly logs that by name | loaded per its own configuration | unchanged |
| CLI and sandbox image | no CLI configuration; no sandbox and no filesystem assembled at all | image resolved from its own CLI set (default image when it has none), sandbox assembled per its own configuration | unchanged |
| Filesystem and shell tools | `disableFilesystemTools()` + `disableShellTool()` | kept | kept |
| Framework subagents | off via `disableSubagents()`, so no second delegation path escapes the team | assembled like an ordinary agent, so it may use subagents inside its own session and sandbox | available |
| Team tools | `team_members`, `team_delegate`, `team_artifacts` | `team_artifact_publish`, `team_artifact_fetch`, `team_artifacts` | none |
| Output-file detection | detector and store assembled as usual (there is no workspace to scan) | not assembled: a member's files leave the sandbox only through publish | unchanged |
| Turn budget | `harness.team.turn-timeout-seconds` | the same number (the tighter member-turn timeout is what applies) | `harness.turn-timeout-seconds` |

All three team tools get ALLOW rules in `PermissionContextState`, alongside plan, todo and the other framework tools, so the permission engine never asks the user about them; what needs confirmation is a member's own business tool.

The lead's prompt block (`leadOrchestrationPrompt`) states the roster, how to delegate, that files travel only as `fileId` references, and that failures are reported as such. It describes the boundary; it is not the boundary: a lead has no business tools, no MCP, no shell and no sandbox, so a lead that ignores the text still has nothing to execute with.

The "required tools" rule for ordinary agents is untouched: a lead has no `agent` row and therefore no binding to tighten or delete, and its empty tool/MCP/CLI sections follow from a team carrying no such configuration.

## 6. Configuration Delivery and Member Loading

### 6.1 The Assembly Chain

```text
Web UI creates a team session (submits teamId only)
    ↓ the root sessionId routes as it always does
agent-service: AgentSpecResolver.isTeamSession(sessionId)
    ↓ admin reads session.team_id; team sessions resolve via /team-spec, others via /agent-spec
TeamOrchestrator is built together with the lead (no member exists yet)
    ↓ the lead calls team_delegate
the member run is created lazily by member id (child session + its own model/tools/MCP/skills/CLI/sandbox)
    ↓ member events reach the root SSE stamped with their source; a confirmation parks the run
the member's text and published artifacts become the delegation's return value → the lead continues or summarizes
```

### 6.2 What `/team-spec` Delivers

`GET /api/internal/team-spec/{sessionId}` returns a `TeamSpecInfoResponse`: `teamId`, `tenantId`, `teamName`, `lead` (one complete `AgentSpecInfoResponse`) and `members` (one complete spec each, in assembly order).

- The lead's spec is synthesized by `specForTeam`: prompt, model and tenant come from the `team` row, skills from `team_skill_binding`, and the tool / MCP / CLI / required-tool lists are passed empty explicitly, with `agentId` = 0 and `agentName` = the team name. The four binding reads of `buildAgentSpecResponse` are parameters, so team and agent share one serialization path.
- A member's spec comes from `specForAgent` in the same shape an ordinary session gets: model, tools, MCP, skills and CLI in full, so nothing depends on the lead's adaptors interpreting another agent's bindings.
- `/agent-spec` throws for a session whose `team_id` is set, and `/team-spec` refuses every other session. Each endpoint recognizes only its own entry, which removes the room to guess.
- A member's child session id is never sent to admin: it has no `session` row and no prefix-based entry point knows it, so member configuration is always derived from the team spec that was authorized for the root session.

`AgentSpecResolver.resolveTeam` turns that response into a `TeamRuntimeSpec`: the lead's `AgentSpec` / `ChatSpec` and one `TeamMemberSpec` per member (each carrying `specInfo`, that member's own admin response). The model, MCP, tool and skill adaptors read `AgentSpecContextHolder` (a ThreadLocal for the synchronous build phase), so each build installs its own spec first and clears it afterwards: `leadSpecInfo` around the lead, `member.specInfo` inside the member factory.

### 6.3 Delegation Mechanism and Member Assembly

Members are not AgentScope native subagents. The SDK's built-in subagent path inherits the parent toolkit and always adds a general-purpose entry, while a team needs a member carrying its own model, tools, MCP, skills, CLI and sandbox; so a member is built by `launcher.createTeamMember` through the same assembly an ordinary agent goes through, and the lead additionally disables framework subagents to avoid leaving a second delegation path outside the team's management.

The lead's orchestration surface is a ToolBox (`TeamLeadToolBox`), not a prompt convention:

- `team_members` returns the roster as text.
- `team_delegate(member_agent_id, task, file_ids)` performs one delegation and blocks until the member reports; the return value is text the lead reads. An id outside the roster, an empty task, a busy member, an exhausted budget and a stopped team each come back as a readable refusal the model can act on, instead of an exception that ends the turn.
- `team_artifacts` lists the artifacts published in this session.

The member-side `TeamMemberToolBox` is bound to a member id and resolves its run through `orchestrator.currentRunOf(memberAgentId)`: delegation is foreground, so a member has at most one unfinished run, which is how a member tool knows whose run it is without the framework carrying a team identity. Reached outside a delegation, the tools answer that no delegation is in progress.

The task brief (`buildTaskBrief`) carries the team name, this member's responsibility in this team, the task text, the `fileId`s to handle and the delivery requirement. A member sees neither the user nor the root conversation, which is why the brief has to state the goal and the acceptance criteria.

### 6.4 Child Session Ids, Lazy Creation and Reuse

`TeamSessions` owns the spelling of a member's child session id: `team-<rootSessionId>-m<memberAgentId>`. It keys the state store, the sandbox container and a workspace path segment, so the runtime that creates it and the history replay that reads it back have to share this one definition; the id contains no slash and is never taken from an event source string.

- A member's agent instance is built on the first delegation to it (`memberWrapper`), outside the map lock, because building makes network calls (model configuration, MCP handshakes).
- Within the same root session, later delegations to that member reuse the instance and the same child-session state, continuing its own conversation. Reuse is by member instance, not a guess from the member's agent id.
- When a run becomes untrustworthy (execution error, stopped by the user, repeated confirmations, abandoned while waiting) the member's instance is `evict`ed: `interrupt` latches and a turn abandoned at a confirmation leaves a tool call waiting for an answer nobody will give. That poison sits on the in-memory instance, not on the persisted child session, so the next delegation reads the same history into a fresh agent.
- A new root call taking over the event stream (`openEventStream`) cancels leftover waits, drops members still claimed and clears the run ledger and the delegation counter: a client that disappeared can leave a delegation thread parked on a confirmation, and that thread holds the member's agent.
- While the previous root call still owns the stream (one of its members is waiting for a confirmation), a new request is refused with `RESOURCE_LOCKED` rather than pulling those events into an unrelated response.

### 6.5 User Identity and MCP

- A member executes as the authenticated user of the root session: `createTeamMember` passes `authSessionId` as the root session id on purpose, because an OAuth grant belongs to whoever opened the root session and admin resolves the identity from that id. A child session id is unknown to admin.
- The MCP details in a team spec come only from each member's own agent and tenant (admin filters by the agent's tenant); the lead receives none of the members' credentials.
- A team run has no caller-supplied `userId` parameter; identity comes from the trusted session plus the JWT.
- The stdio ban, per-user authorization and runtime token injection stay on the ordinary path: a member having its own sandbox does not reopen stdio, and MinIO or Docker administration credentials are not injected into a member container.

## 7. Sandboxes

A team runs as "no sandbox for the lead, one per member":

1. The lead assembles no filesystem at all: both the sandbox branch and the snapshot branch carry a `!isLead` condition, and `disableFilesystemTools()` plus `disableShellTool()` close the framework's own two entrances. Filesystem capability never falls back to host execution.
2. A member assembles a sandbox from its own configuration: the image is resolved from its own CLI set (the default image when it has none), environment values come from its own CLI packages and bindings, the isolation scope is `harness.sandbox.isolation-scope`, and snapshots use the same mechanism as any other session. With `harness.sandbox.enabled=false` and MinIO configured, the runtime falls back to `RemoteFilesystemSpec` with `IsolationScope.SESSION`, so members still get per-session file space.
3. The isolation scope is tenant, root team session and member child run. The underlying container and workspace accept one session id, so the child session id maps that scope rather than an `agentId` or a `teamId`. Different users, different root sessions and the same member in different root sessions never land in one container.
4. Continuing the same child session restores that member's own workspace and snapshot; a member's files leave the sandbox only through publish, and nothing on the host scans a member workspace.
5. Lifecycle: the `STOP_SANDBOX` command destroys the root session's container and, via `launcher.memberSessionIds(sessionId)`, every member child-session container this root owns in the state store; `TeamOrchestrator.stop(destroySandboxes = true)` uses the same handle. MCP clients close with the member instance's `release()`.
6. Separate containers are one layer, not a total-isolation promise: no host Docker socket, no shared member workspaces, and network plus resource limits come from the deployment's sandbox configuration.

## 8. Artifact Handover

### 8.1 Publish and Fetch

Both actions are member-side tools whose scope the server resolves; the model supplies only a path and a `fileId`:

| Action | Input | Behavior and output |
|--------|-------|---------------------|
| `team_artifact_publish` | `path`, relative to the workspace | reads the file from the current child run's sandbox, uploads to MinIO, registers a `team_artifact` row, returns a `fileId` |
| `team_artifact_fetch` | `file_id` + `dest_path` | checks the artifact belongs to this team session, downloads it and writes it into the member's own sandbox |
| `team_artifacts` | none | lists artifacts published in this root session with size and producing member |

A typical handover: the researcher publishes `data.csv` and gets `fileId=A`; the lead writes A into the task for the analyst; the analyst fetches A, produces findings and publishes `fileId=B`; the writer fetches B, produces `report.md` and publishes C; the lead reviews C and delivers it to the user as the final file reference.

The lead passes references and summaries only and never downloads a file: it has no workspace and no tool that could read one. Verifying file content stays a delegation to a member.

### 8.2 Storage, References and Invariants

- Storage: `MinioTeamArtifactGateway`, in the output bucket under a fixed key shape `team-artifacts/<tenantId>/<rootSessionId>/<fileId>`. The `team-artifacts` prefix is outside the session types the general output-file route whitelists, so that route cannot name a team object even by accident.
- Every publish mints a new UUID `fileId`, so an earlier artifact cannot be overwritten and same-name files coexist; which version reaches the next member is the lead's decision. `mime_type` is inferred from the extension table, falling back to `application/octet-stream`.
- A reference is returned only after the upload landed and the row registered; a failed registration removes the object and rethrows, so nothing addressable stays on the server with no owner and no way to clean it.
- Reads resolve ownership first, `findOwned(fileId, tenantId, rootSessionId)`: tenant and root session come from the trusted `TeamRuntimeSpec` of this run, and a `fileId` proves nothing by itself. A model cannot name a bucket or an object key.
- Publishing is limited to a regular file inside the current member's workspace: `SandboxFileWriter.safeRelativePath` rejects traversal and `..`; the size check `sandboxSize` rules out directories with `-f` and symlinks with `! -L`, and requires the `readlink -f` target to stay under `$sandboxWorkspaceRoot/`. Fetching writes only to a relative path inside the member's own workspace.
- The size cap `harness.team.max-artifact-bytes` (20 MiB by default) is checked on both publish and fetch.
- Only explicitly named files are published: never a whole workspace, environment values, secrets or a snapshot.
- With MinIO disabled the `TeamArtifactGateway` bean does not exist. The team still delegates, and all three artifact tools answer with text that names the cause ("artifact storage is not enabled, MinIO is not configured", including "do not substitute a public link or a host directory"). A failed listing also returns its error text rather than an empty list — an empty list reads to the model as "nobody produced a file".

### 8.3 The User's Download Entry

`TeamArtifactController` serves `GET /api/admin/team-artifacts?sessionId=` and `GET /api/admin/team-artifacts/{fileId}?sessionId=`, registered only when `minio.enabled=true`. Object-level authorization is `ownedTeamSession`: the `sessionId` must match `[a-zA-Z0-9_-]{1,128}`, the session must exist and be active, its `team_id` must be set, and its `creator` must be the current user; the listing additionally filters rows whose own `tenant_id` differs from the session's. A download's object key always comes from the `team_artifact` row; `fileId` must match the UUID shape, and a `fileId` belonging to another session or tenant answers 404 rather than 403 (this endpoint must not confirm that someone else's reference exists). The file name in `Content-Disposition` is stripped of quotes, CR, LF and semicolons, and an unparseable MIME type falls back to `application/octet-stream`.

A member's run cannot reach this endpoint at all — it holds no admin credential — so fetching an artifact into a sandbox stays a server-side read inside agent-service.

### 8.4 Cleanup

Deleting a session is the only action that makes artifacts stop being reachable. `TeamArtifactCleaner.deleteForSession` removes the MinIO object first and the `team_artifact` row second: object storage has no transaction, and the reverse order would leave objects no row names, which nothing can find again. With this order a database failure leaves a row pointing at nothing, which a retry repairs (MinIO treats a repeated delete as a no-op). An object that cannot be deleted keeps its row and is logged, so the gap stays visible instead of turning into a broken download. When MinIO is not configured the rows are kept and a warning is logged: the objects are still there, and dropping their rows would leave them somewhere nobody can find.

A team cannot be deleted while it has sessions, so the combination "team gone, artifacts still keyed to a session" does not occur.

## 9. Events, Confirmation and Failure

### 9.1 One Root Stream, Stamped Provenance

Externally there is still one SSE stream, the root session's: `DefaultAgentRunner.withMemberEvents` opens the `TeamOrchestrator` member stream at subscribe time, strictly before subscribing the lead's stream, and merges both into one response. Routing and stickiness work on the root sessionId; a delegation creates no external routing request of its own.

Provenance is `ChatEvent.source` (`EventSource`): `teamId`, `teamName`, `memberAgentId`, `memberAgentName`, `childRunId`, `childSessionId`. Member events are forwarded one by one in `collectTurn` with `withSource(run.source)`; `EndEventChatEvent` is filtered out server-side and never reaches the client.

`childRunId` identifies one delegation (a UUID) and tells two delegations to the same member apart; neither a display name nor an `agentId` is the test. Tool argument and result buffers are per member run, so a `toolCallId` only has to be matchable inside one run.

A member's text reaches the lead's context only as the delegation's return value; it is never concatenated into the lead's answer. Token statistics are attributed per instance, so the lead's rows and each member's rows are counted separately.

### 9.2 The Confirmation Loop

```text
A member's tool hits an ASK rule
    ↓ ToolConfirmEvent is emitted with that run's source (the delegation card opens the entry inline)
The user approves or denies (the frontend answers by childRunId, one decision per round)
    ↓ admin/router route the request back to the same instance by the root sessionId
TeamOrchestrator.answerConfirmation(childRunId, approved) completes that run's wait
    ↓ the member continues inside the same delegation, output still on the original root stream
The lead receives the member's state and result and carries on
```

Points:

- The answer goes to `/api/router/agent/confirm` with `childRunId`; when `ConfirmAgentRequest` leaves that field empty the request is the session's own confirmation, otherwise it is handed to the team orchestrator. A successful answer returns one End event and nothing else: the resumed output comes back on the stream that is still open, so the answer carries no content of its own.
- Several pending tools in one wait are decided together (`toolResults.all { it.confirmed }`), so a mixed answer denies the run rather than executing tools the user left unchecked.
- A decision is delivered exactly once: the future is taken with `pending.getAndSet(null)`, a duplicate submission gets `ALREADY_ANSWERED`, no wait gets `NO_PENDING`, an id this orchestrator does not hold gets `NOT_IN_THIS_TEAM`, a stopped root run gets `STOPPED`. All four refusals come back as readable text, and the tool never executes.
- The wait loops in 30-second slices, the length being `HarnessConfig.TeamConfig.confirmHeartbeatSeconds` at its data-class default: the `TeamConfig` assembly never passes that field and `harness.team` has no key for it, so there is nothing to tune from configuration. Every unanswered slice emits a `KeepAliveChatEvent` carrying only a source — no content, no usage — which every consumer drops. The heartbeat answers two idle timeouts, and both of those are configuration keys: session-router's `router.proxy.stream-idle-timeout-seconds` (120 by default) and channel-service's `channel.proxy.stream-idle-timeout-ms` (180000 by default), while a legitimate wait can last far longer than either.
- Closing the window (`harness.team.confirm-timeout-seconds`, 600 by default), an interrupted thread, or a stop before anyone answers all end as "not completed": the tool did not run, a wait is never read as approval, and the member task returns to the lead as a failure text, after at most `MAX_CONFIRM_ROUNDS` (5) repeated requests.
- While a member run waits, the previous root call keeps owning the event stream and `openEventStream` returns null for anyone else. A user message sent at that moment is answered by `DefaultAgentRunner` with a single `RESOURCE_LOCKED` (plus one warn log): the new call neither takes that wait over nor releases it, which is the same rule section 6.4 states. The wait is dropped at the moment a later root call actually opens the stream — that `openEventStream` cancels the delegation still waiting and `evict`s the member instance parked on a confirmation, so a later answer for that same `childRunId` gets `NOT_IN_THIS_TEAM` and the UI says the confirmation has lapsed. A pending card is not replayed from the run's persisted state.
- A team session does not open a second concurrent round while a member run is unreturned (ownership of the event stream is unique), and where the lead's own confirmation may pop a modal and pause reading the stream, a member's must be answered inline.
- Auto-approval, stripping confirmation-requiring tools off a member, blanket auto-denial, or letting the lead perform the dangerous action are none of them this loop. An entry point with no interactive capability needs its own confirmation policy before it can take a team.

### 9.3 Stop, Failure and Budgets

- Member failures are always reported as text and never disguised. `runTask` ends in failure on: an `ErrorEvent` from the member, the user stopping mid-execution, repeated confirmation requests, a confirmation that timed out or was cancelled, a member producing neither text nor artifacts, and the delegation itself throwing. Each carries the part that did complete; after a failure the member instance is `evict`ed and its claim released.
- Outside `failReport`, those endings return as a normal value (the `team_delegate` result carries no failure marker); the visible consequence is under "What the frontend shows".
- Stop: `stopExecution` calls `orchestrator.stop()` before interrupting the lead. A delegation blocks a tool thread *of the lead*, so interrupting only the lead would leave a member running (or still parked on a confirmation); `stop()` releases those waits without approving them, sets `stopped` so further delegation is refused, and interrupts what is executing. `STOP_SANDBOX` additionally destroys member sandboxes and invalidates the cached instance. External side effects already sent by a shell or an MCP call are not rolled back by cancelling.
- Budgets: `harness.team.max-delegations` (20) is counted and enforced by the runtime, and the refusal text tells the lead to summarize what it has; `member-turn-timeout-seconds` (900) wraps a member's stream from outside; `turn-timeout-seconds` (1800) is the root turn budget and should exceed the former — when it does not, assembly logs which layer will fire. The artifact size cap is covered above.
- After a process restart or a run landing on another instance, snapshots and workspaces can be restored while in-flight delegations are not replayed: an interrupted run ends as interrupted, and a user or the lead starts it again.

### 9.4 What the Frontend Shows

Presentation answers only "who said this and how far it got" and leaves the provenance contract, the confirmation loop and the lifecycle semantics alone. The shared logic is in `harnax-webui/src/pages/session/components/teamRun.ts`, and the live stream and the replay use the same tests.

- One member run is one bubble state (grouped by `childRunId`) rendered inside the `team_delegate` tool card that delegated it. The join is decided when the message is created, not at render time: live takes the earliest card this member emitted and no run has claimed (`openDelegateCards`), replay scans that turn's cards in order (`claimDelegateCard`); both key on member id and call order and never on timestamps, so the order seen live is the order seen after a reload.
- A run that claims no card still becomes its own bubble: a refused delegation, a lead turn with no recorded call in the persisted history, or an unreadable roster — none of them may lose a member's output from the screen. The lead's bubble is not split at the delegation point, which would separate a `team_delegate` card from its own result event.
- The close signal is the lead's `team_delegate` result event: the tool call id locates the card, the member id is read from its arguments (`parseDelegateCall`), and the matching run closes. When a card returns a result that no run claimed, its id is dropped from the pending queue, otherwise it would take the next delegation's place for that member. Three fallbacks close everything else: the lead's turn ending, the lead's error, and the root stream breaking — a run still open at that point is put in a terminal state.
- Only one member run per member is open at a time and delegation blocks, which is what keeps the card-to-run mapping unique.
- Four states: `running`, `awaiting_confirm`, `done`, `failed`. "Waiting for confirmation" is the UI entry of the confirmation loop (`answerConfirmation`'s wait) and the card stays expanded while it holds. The fold control *is* the `team_delegate` card header: it expands automatically while a run is executing or awaiting confirmation, collapses once closed, and a manual click wins over the automatic state (the override is per card and clears on session switch). Even collapsed, the header shows who got the card; a card holding several runs shows "name +N". Expanded, each run has its own title line — member name, team, status, tool count, duration — with its own text, tool cards and confirmation entry below.
- "Done" means "this delegation closed and no failure event arrived while it ran". Only the member's own `ErrorEvent` path renders `failed`; being stopped mid-execution, repeated confirmations, a timed-out wait, an empty answer and a throwing run all close as normal delegations and render as done. A replay carries no lifecycle information, so after a reload a member run starts out as `done`.
- Tool-level state does show where a round was cut: a call whose result log never appears in the replay, and a call still without a result when a live stream closes, is rendered as interrupted — except cards in the confirmation state, which already have a state of their own.
- The task text of a member run comes from the lead's `team_delegate` arguments (neither a member's events nor its persisted logs carry it), remembered per member by most recent call, and is used on the title line only when the run stands alone as its own bubble.

### 9.5 History Replay

`DefaultAgentRunner.loadHistory` wraps the ordinary history read in `TeamHistoryReplay.merge`:

1. `launcher.memberSessionIds(rootSessionId)` finds member child sessions in the state store by the prefix `team-<root>-m`. An ordinary session pays this step too, since only this call can answer "are there member sessions", and the read then returns immediately.
2. The roster comes from admin's `getTeamSpec(rootSessionId)`, and each child session gets an `EventSource` with every field filled. Names follow the current roster rather than a snapshot — after a member is renamed the user should see the name it has now. No run id was ever persisted, so replay uses the child session id as `childRunId`.
3. Each member child session's persisted messages are read, keeping only the `ASSISTANT` and `TOOL` roles: a member's user message is the lead's brief and it is already in the lead's turn.
4. Interleaving moves by whole turns. The lead's turn (an assistant message plus the tool results right after it) is the insertion block, and member logs move a full turn at a time, landing before the block they were produced under. Sorting all logs by timestamp would slide one member's message between another member's call and its result, and that tool card would never close. A member run that outlived the lead's last persisted message is still appended at the end.
5. The child session id does not distinguish delegations, so runs are cut by contiguity: member logs continuing the same child session join the current run, and a lead log in between starts a new one. However many member runs were visible live, that many are visible after a reload.
6. The degraded paths affect only the team part: an unreadable roster, empty child sessions or empty member logs all return the lead's history alone, and a team whose history cannot be fully read does not make the session unopenable. A member removed from the team follows the same degradation — its child session is out of the roster, so its logs do not surface and the delegation remains only as the `team_delegate` result text inside the lead's bubble.
7. Persisted tool logs carry no tool call id, so replay mints one per message (`${msgId}-t${seq}`); claiming only needs it stable within that message, uniqueness across sessions is not required. Several calls in one turn first collect their results by source, then pair them one by one by name.

## 10. Configuration and Module Responsibilities

Runtime configuration lives under `harness.team.*`; the defaults are `TeamConfig`:

| Key | Default | Purpose |
|-----|---------|---------|
| `max-delegations` | 20 | delegations allowed in one root run |
| `member-turn-timeout-seconds` | 900 | one member turn |
| `turn-timeout-seconds` | 1800 | the team's root turn budget, expected above the former |
| `confirm-timeout-seconds` | 600 | how long one member confirmation waits |
| `max-artifact-bytes` | 20 MiB | publish and fetch cap for one artifact |

`TeamConfig` also carries `confirmHeartbeatSeconds` (30), which is absent from that table on purpose: `harness.team`
has no key for it and the `TeamConfig` assembly never passes it, so the value is the data class's default and the
root-stream heartbeat while a confirmation waits is fixed at 30-second slices.

| Module | Team responsibilities |
|--------|-----------------------|
| `harnax-entity` | `Team`, `TeamMember`, `TeamSkillBinding`, `TeamArtifact`, nullable `Session.agentId` plus `teamId`; `TeamSpecInfoResponse` and the mappers |
| `harnax-admin` | team CRUD and validation, session typing and snapshots, `/team-spec` and `/sessions/{id}/team`, artifact metadata and authorized download, artifact cleanup on session deletion |
| `harnax-agent-service` | team session detection and spec resolution, orchestrator and lead construction, the member factory and thread context, stream merging, confirmation dispatch, stop, history merging |
| `harnax-harness-core` | role-based assembly (lead/member), team tools, the orchestrator, child session ids, the artifact gateway, sandbox and statistics attribution |
| `harnax-protocol` | `EventSource` fields, `withSource`, `KeepAliveChatEvent`, `childRunId` on the confirm request |
| `harnax-webui` | team list and two-step wizard, executor choice, member runs inside delegation cards, inline confirmation, the artifact drawer, lead labeling |
| `harnax-session-router` | sticky routing by root sessionId and the stream idle timeout (a team adds no external routing dimension; `childRunId` travels on the request) |
| channel / scheduler / client | handle provenance and heartbeats where their protocols consume events; the team entry today is the web session |

Deployment stays on `harnax-deploy` and a team introduces no new service. Member sandboxes add demand for containers, images and object storage, so capacity limits, lifecycle and deployment notes are maintained together with the sandbox and MinIO configurations.

## 11. Explicitly Not Done

| Out of scope | Current shape |
|--------------|---------------|
| A team as a kind of agent, or a team switch on an agent | a team is its own configuration object and its lead's configuration lives on the `team` row |
| A hidden `agent` row standing in for the lead | the lead's spec is synthesized by `specForTeam`; `agent` has only the conversable and the member role |
| Tool, MCP or CLI bindings on a team | a team owns only `team_skill_binding`; those three exist solely on member agents |
| The lead executing business work with tools, MCP, shell or a sandbox | assembly withholds them outright; the prompt only explains that |
| A shared writable workspace, or one sandbox shared by members | one sandbox per member, files only via MinIO artifacts |
| Passing business files through a workspace snapshot or by pasting file content | snapshots restore workspaces; files travel as `fileId` references |
| Parallel, background, nested or cross-team delegation inside a team | foreground, sequential, single-level; concurrent delegation to one member is refused |
| Group-chat negotiation, workflow canvases, A2A, remote agent discovery | not implemented and not on any existing code path |
| Auto-approval, blanket auto-denial, the lead performing dangerous actions instead | the loop requires the user's decision to reach the member run |
| A separate sub-session chat window per member | member output renders inside the lead's delegation card, on one session |
| Turning an existing plain session into team mode | a team session is only ever created |
| Channel delivery (DingTalk, WeChat, scheduled tasks) as a team entry | `chn-` and `task-` conversations have no `session` row, so team detection never covers them |

## 12. File Index

| Path | Content |
|------|---------|
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Team.kt` | the team row, host of the lead's configuration |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamMember.kt` | member reference and per-team responsibility note |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamSkillBinding.kt` | the lead's skill bindings |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamArtifact.kt` | artifact metadata and reference semantics |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Session.kt` | nullable `agentId` and `teamId` |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/TeamSpecInfoResponse.kt` | the delivered team spec shape |
| `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` | admin's schema baseline: the `team`, `team_member`, `team_artifact` and `team_skill_binding` create statements and `session.team_id` are all written in this one script, and the lead's own configuration — the `system_prompt` and `model_id` columns — sits inside the `team` create statement |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt` | team CRUD, enable/disable, related sessions |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt` | save-time validation, visibility, delete refusal |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` | `/team-spec`, `/sessions/{id}/team`, `specForTeam`, `buildAgentSpecResponse` |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt` | team session creation, snapshots, refusing an agent on a team session |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt` | artifact listing and authorized download |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamArtifactCleaner.kt` | artifact cleanup on session deletion |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt` | trusted team runtime configuration, child session spelling, the lead's prompt block |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRole.kt` | lead and member roles |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt` | the lead's three team tools and the member's three artifact tools |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamOrchestrator.kt` | delegation, member instances and the run ledger, confirmation waits and heartbeats, publish and fetch, stop and budgets |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamArtifactGateway.kt` | the MinIO artifact gateway and its key rule |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` | `createTeamLead`, `createTeamMember`, the per-role assembly, `memberSessionIds` |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt` | `TeamConfig` defaults |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` | `harness.team.*` binding and the `teamArtifactGateway` bean |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt` | `attributableAgentId` (a lead has no agent attribution) |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` | `isTeamSession`, `resolveTeam` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt` | `buildTeamAgent`, stream merging, confirmation dispatch, stop, the history entry |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/TeamHistoryReplay.kt` | finding member child sessions, stamping provenance, interleaving by turn |
| `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt` | `EventSource` fields and `withSource` |
| `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt` | `childRunId` on the confirm request |
| `harnax-webui/src/pages/team/index.tsx` | the team management page |
| `harnax-webui/src/pages/team/components/TeamWizard.tsx` | the two-step wizard and unavailable markers |
| `harnax-webui/src/pages/team/components/MembersField.tsx` | member picking and responsibility notes |
| `harnax-webui/src/pages/session/components/SettingsModal.tsx` | grouped executor choice, team session creation |
| `harnax-webui/src/pages/session/components/teamRun.ts` | card claiming, delegate-argument parsing, run status tests |
| `harnax-webui/src/pages/session/components/ChatWindow.tsx` | member run rendering, inline confirmation, folding, replay claiming |
| `harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx` | artifact list and download |
| `harnax-webui/src/pages/session/components/DetailModal.tsx` | the session detail's team branch |
| `harnax-webui/src/services/ant-design-pro/team.ts` | team and artifact API wrappers |
| `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt` | the root stream idle timeout the heartbeat answers |
| `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/client/RouterClient.kt` | the channel-side stream idle timeout |

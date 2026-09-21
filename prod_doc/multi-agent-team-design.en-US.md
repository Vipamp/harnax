# Harnax Multi-agent Team Design and Trade-offs (English)

> For the Chinese version, see [multi-agent-team-design.zh-CN.md](./multi-agent-team-design.zh-CN.md).
>
> Decision date: 2026-09-18. This document consolidates the product boundaries, target design, alternatives, and trade-offs confirmed in this round of discussion. It does not mean the feature has been implemented.
> The current deliverable is a design document only: no Team tables, management pages, team runtime endpoints, or team file tools have been added; runtime prototype validation has not been completed.
> The user's final choice is: **an independent Team, reuse of existing Agents, an orchestration-only lead, execution by members, independent member sandboxes, MinIO artifact handoff, process visibility, and human confirmation**. The earlier shared-sandbox and lead-toggle proposals are no longer the basis for implementation.
>
> **Amendment 2026-09-20 (D1/D2 changed)**: the lead no longer references an existing Agent. The Team carries the lead's own configuration — the fields of the agent wizard's "Basic information" step (name, description, system prompt, model) are now team configuration, and `team.instructions` has been merged into that system prompt. The lead may additionally configure Skills only; Tool, MCP, and CLI are gone. Member semantics are unchanged. The affected sections below have been rewritten accordingly; implementation details live in [Team-owned lead configuration design](../docs/superpowers/specs/2026-09-20-team-own-lead-config-design.md).

## 1. Goals and Design Principles

The goal is to support configurable multi-agent collaboration with as few changes to existing code as practical: users assemble a team and give its lead a goal; the lead assigns concrete tasks to members.

“Minimal code changes” has explicit boundaries:

- Reuse existing Agent definitions, configuration delivery, model and tool assembly, session routing, sandbox management, and MinIO infrastructure.
- The team carries exactly one set of its own configuration — the lead's model, prompt, and Skills. Members' capabilities stay entirely on their own Agents: assembling a team never duplicates a second set of Tool, MCP, CLI, or credentials, and introduces no new workflow engine or remote Agent service-discovery system.
- Do not reduce code at the expense of member capabilities, by expanding the lead's permissions, or by bypassing human confirmation.
- Existing Agent configurations and standalone usage remain unchanged. This does not mean that all existing code can remain untouched.

## 2. Boundaries Between Final Decisions and Engineering Recommendations

| ID | Conclusion | Status |
|------|------|------|
| D1 | Team is an independent team configuration, not a new Agent type or a toggle on the lead Agent; it is also **the host of the lead's configuration** (model, prompt, and Skills live on the Team) | Confirmed (amended 2026-09-20) |
| D2 | **Members** reference existing Agents, and the same Agent can be reused in several Teams; **the lead references no Agent at all** — the `agent` table holds only "Agents you can converse with" and "Agents acting as team members" | Confirmed (amended 2026-09-20) |
| D3 | The lead only decomposes tasks, delegates, coordinates, reviews results, reassigns work, and produces the final summary; members perform concrete execution | Confirmed |
| D4 | Team-role restrictions apply only to the current runtime instance and do not modify the original Agent's persisted configuration | Confirmed |
| D5 | The lead and members run within the same agent-service instance; members use independent session state and independent sandboxes created on demand, while the lead creates no execution sandbox | Confirmed; independent sandboxes replace the earlier shared approach |
| D6 | Members publish and retrieve non-overwritable file artifacts through MinIO rather than sharing a complete writable workspace | Confirmed |
| D7 | Member execution is visible, and actions requiring confirmation are presented to the user; after approval, the corresponding member resumes before control returns to the lead | Confirmed; no silent degradation |
| D8 | Prefer reusing AgentScope's native delegation mechanism, with member factories assembling each member's full capabilities; do not mix two delegation protocols | Recommended integration approach; prototype validation required |
| D9 | For the first release, single-level, sequential, foreground delegation is recommended, starting with the Web UI; parallel background tasks, nested teams, A2A, and a workflow canvas should not ship at the same time | Scope recommendation, not a delivered capability or a reason to revoke D7 |

D9 is intended to control engineering scope. Any later addition of parallelism or new entry points requires separate acceptance checks for concurrency correlation, confirmation, cancellation, and identity propagation. A UI that allows Team selection does not justify claiming support across all channels.

## 3. Product Model: Agents Are Conversable Units and Members; the Team Owns Its Lead

### 3.1 Three Types of Objects

| Object | What it stores | What it does not store |
|------|--------|----------|
| Agent | Model, prompt, Tool, MCP, Skill, and CLI configurations | No agent row ever stands in for a team lead; no duplicate capability configuration just because it joins a team |
| Team | Name, description, **the lead's configuration (system prompt, model) and the lead's Skill bindings**, enabled/disabled state, and ownership information | No Tool, MCP, or CLI; no reference to any agent as its lead |
| Team Member | Team reference, member Agent reference, and description of its responsibilities in that team | No copies of the member's model, tools, credentials, or skills |

A "member" still references an existing Agent, while a "lead" is now a set of columns on the Team itself and maps to no agent row. The same Agent can serve as a member of several Teams and still be used on its own.

### 3.2 Data Placement

`team`, `team_member`, `team_artifact`, and `session.team_id` shipped with V32. The final shape after the 2026-09-20 amendment is Flyway V34:

| Data location | Required information |
|------|----------|
| `team` | `id`, `tenant_id`, name, description, **`system_prompt`, `model_id`**, status, creator, and timestamps; no longer `lead_agent_id` or `instructions` |
| `team_skill_binding` | `team_id`, `skill_id`, combination unique; no `env_bindings` column (per-skill environment variables have no consumer) |
| `team_member` | `team_id`, `member_agent_id`, `delegation_description`; the team/member combination is unique |
| Session | `agent_id` and `team_id` are mutually exclusive: a team session stores NULL `agent_id`, a non-null `team_id`, and takes its name/prompt/model snapshots from the Team row |
| Member run record | Root session, current root run, member reference, child-run and child-session identifiers, status, and associated file references |
| File artifact metadata | File ID, owning tenant and root session, producing child run, original filename, type, size, and internal storage location |

Member run records and file metadata should preferably fit existing storage extension points. This document does not require a separate new database table for every logical object.

The session's `teamId` determines entry into team mode, and a NULL `agent_id` states plainly that this is not an Agent's session: an ordinary entry point never starts a team automatically, and a team session is never resolved through some `agentId`. The server resolves the lead's configuration from the Team row; clients never submit a lead identity.

### 3.3 Permissions and Validity

- When creating, editing, or starting a Team, validate the status and ownership of the Team itself, plus the tenant, usage permission, status, and existence of each member. The model of the lead is checked at save time for existence, picker-equivalent visibility (`is_public` or created by the current user), enabled status, and a chat type — tenancy is not the rule, so a shared public model stays usable. Runtime gets no second chance to “switch to another model”. A model disabled after the team was saved raises no alarm and does not block the conversation: delivery only looks up whether the model row exists, and no second enabled check runs anywhere (an ordinary agent behaves the same), so the team keeps running on it. Only a deleted model fails, and it fails at delivery when its configuration can no longer be resolved.
- Select at least one member; members must be unique. The lead is no longer an Agent reference, so there is no "lead cannot also be a member" check to make.
- Visibility of a Team does not grant the right to use any private Agent within it. Team configuration must not bypass members' own access controls.
- Under the recommended single-level scope for the first release, members acting as executors do not load their own team relationships and cannot spawn further subteams. There is no need to build an arbitrary DAG executor for this.
- If a Team or Agent becomes invalid, return an explicit error at startup or before the next delegation. Do not silently omit members or switch to the lead's model.
- Keep the resolved configuration fixed throughout a run and its confirmation/resume cycle. Do not switch members, tools, or parameters while waiting for user approval. Configuration activation and cache invalidation must be validated during implementation.

## 4. Assembling and Using Teams in the UI

Add an independent “Team Management” entry to the admin console. Assemble teams with a two-step wizard, not a drag-and-drop graph. Step 1 is exactly the “Basic Information” screen of the existing Agent wizard; step 2 is the member roster.

```text
Step 1 · Basic information and skills                          [Next]
Team name     [Research Report Team]
Description   [Collects sources, analyzes data, and generates reports]
System prompt [You are the lead of this collaboration…]        ← the lead's entire prompt, old team instructions folded in
Model         [qwen3-max]
Skills        [Source research spec] [Report writing spec]      ← Skills only; no Tool, MCP, or CLI

Step 2 · Team members                                          [Add member]
Researcher      Responsibilities: Gather material and provide sources
Data analyst    Responsibilities: Analyze data and extract conclusions
Report writer   Responsibilities: Write reports from material and conclusions
                                          [Back] [Cancel] [Save]
```

### 4.1 Configuration Interaction

1. Step 1 collects the team name, description, system prompt, and model, plus any skills. Description and system prompt are required, matching step 1 of the Agent wizard.
2. Skill eligibility and write-time validation follow the same rules as the Agent side: missing, disabled, builtin-CLI-repository origin, and name conflicts are all rejected on save.
3. Step 2 adds members. Responsibility descriptions default to the Agent description and can be adjusted for the team without writing back to the original description.
4. Tool, MCP, and CLI are configured only on each member's own Agent page; the Team accepts none of those three fields, and the runtime keeps a separate lead guard.
5. Validate members and permissions on save. Independent sandboxes do not require members to have matching CLI versions or environment variables.
6. Editing a team reuses the same wizard and pre-fills the current configuration.

Team members use stable server-side identifiers. Display names and responsibility descriptions support reading and delegation decisions; potentially duplicate display names must not serve as unique runtime keys.

### 4.2 Conversation Interaction

- The team list provides “Edit” and “Start Conversation” actions; starting a conversation creates a new session bound to that Team.
- The existing entry point for selecting an Agent and chatting with it independently remains unchanged. Do not silently switch an existing ordinary session into team mode midway through the conversation.
- The conversation area shows the lead's assignments, member status, displayable execution text, tool calls, file artifacts, and the final summary.
- Member activity folds inside the lead's own delegation tool card, separate from the lead's final answer and no longer a parallel bubble. “Process visibility” does not mean displaying credentials or requiring models to reveal internal chain-of-thought. Card shape, run-closure signals, and reload replay rules are defined in §9.4.
- Human confirmation clearly shows the member, action, and parameters. Final files are provided through authenticated and authorized attachment access in the Web UI.
- New copy follows the existing Chinese/English internationalization approach. Separate child-session chat windows are not introduced for teams at this stage.

## 5. Capability Boundaries for Leads and Members

| Capability | Team lead | Team member | Original Agent used independently |
|------|----------|----------|------------------|
| Model and prompt | **Configured on the Team itself**, augmented with the lead role and member roster block | Its own model, augmented with the current responsibilities and task instructions | Existing behavior preserved |
| Skill | Loaded: SKILL.md text and its resource content reach the lead's context and skill-reading tool; **anything script-backed or requiring on-disk execution is not executable**, and each degradation is named at load time | Loaded according to its own Agent configuration | Existing behavior preserved |
| Business Tool and MCP | Not loaded or connected (the Team accepts neither field either) | Loaded according to its own Agent configuration | Existing behavior preserved |
| CLI, Shell, and execution sandbox | Not provided; with no shell the lead has no workspace | Its own configuration and permission constraints retained | Existing behavior preserved |
| Delegation, progress, and task coordination | May use this Team's orchestration capabilities | Further delegation is not exposed under the recommended single-level first-release scope | Existing behavior preserved |
| File artifacts | Receives and passes references; selects files for final delivery | Publishes, retrieves, and processes files in its own sandbox | Existing behavior preserved |

Lead permission restrictions must be enforced through assembly and runtime validation, not a prompt that says “do not execute tasks yourself.” Existing mandatory tools, framework-default Shell, dynamic subagents, and skill loading must also pass through the team-role policy so that default registration paths cannot reintroduce capabilities.

This does not change the “mandatory tools” rule for ordinary Agents: a team lead has no agent row, so there is no binding to narrow or delete. Its empty Tool/MCP/CLI set is the consequence of the Team not accepting those fields; the runtime guard is only the second line.

The lead still needs to understand member reports, judge whether the goal has been met, and summarize the response. “Orchestration only” does not mean merely forwarding messages mechanically. Concrete work such as processing material, querying databases, and generating files is delegated to members.

## 6. Configuration Delivery and Member Loading

### 6.1 Target Flow

```text
Web UI creates a Team session
    ↓ Root sessionId follows the existing router
agent-service obtains the team runtime configuration
    ↓ admin validates the Team and members: the lead's configuration comes from the Team row and team_skill_binding, each member's from its own agent row
Assemble the lead + member factories (no member runtime instances created yet)
    ↓ The lead delegates according to responsibilities
Create a child-run identifier → lazily create a runtime instance from the member configuration
    ↓ Child-session state + that member's own Tool / MCP / Skill / CLI
Create an independent sandbox on demand and execute the task
    ↓ Results, file references, events, and confirmation requests
The lead reviews results, delegates further, or summarizes → return to the user over the existing SSE channel
```

### 6.2 Separate Configuration from Instances

- Members reuse Agent configuration definitions, not another ordinary session's runtime instance, chat history, or sandbox. The lead's configuration exists only on the Team row and `team_skill_binding`; what the runtime receives is a lead spec shaped exactly like an Agent's, with `agentId` fixed at 0 and `agentName` set to the team name.
- admin delivers full member configurations. It must not supply only `modelId` and `toolId` and assume that the lead's adapter can resolve every member.
- A factory captures the corresponding member configuration and trusted runtime scope. The existing `AgentSpecContextHolder` is a `ThreadLocal` used during synchronous creation; do not assume it still exists during lazy creation, and never allow members to read the lead's configuration.
- Do not pass arbitrary derived child-session IDs directly to the existing admin entry point that resolves `web-`, `mp-`, `chn-`, and `task-` prefixes. Child runs derive from the team configuration already authorized for the root session.
- Child-session identifiers must be opaque, safe identifiers accepted by state storage and sandbox management, not slash-delimited paths taken directly from event source strings.
- Lazy factory creation does not mean “automatically destroyed after every call.” The first delegation creates a child session; an explicit continuation of the same delegation reuses it. Whether a new task or retry reuses a session must be explicit in the run record, not guessed solely from the member Agent ID.

### 6.3 AgentScope Integration Choice

Prioritize validating native `SubagentsMiddleware`, `SubagentEntry`, and `SubagentFactory` with parent-run context. Use factories to assemble full member capabilities while retaining native delegation. Do not build a custom scheduling engine or substitute a declarative subset of parent tools for member configurations.

This is a recommended integration point, not a validated, drop-in adapter. Middleware initialization, tool registration, the task repository, actual child-session identifiers, the Harness lifecycle, confirmation/resume, and cleanup all need correct handling. Calling `addMiddleware` alone does not establish that every tool is ready, and returning only a bare ReAct delegate must not bypass the Harness sandbox lifecycle.

Team mode registers only configured members and disables default general-purpose subagents and dynamic workspace member discovery in that mode. Ordinary Agent mode is not changed as a side effect.

### 6.4 User Identity and MCP

- Child Agents execute on behalf of the authenticated user of the root session. They must not impersonate the current user using the Team creator's or member Agent creator's OAuth authorization.
- A member may load only MCP configured for that member and authorized for use. The lead neither connects to members' MCP nor receives their credentials.
- MCP token exchange remains in admin, which resolves identity from a trusted session. Do not add a caller-supplied `userId` parameter to token exchange.
- Child runs must distinguish the “child-session identifier for state/sandbox” from the “root-session identifier to which authorization belongs.” Unknown child IDs cannot be used directly as the existing OAuth exchange `sessionId`; team integration must retain and validate the relationship between the root session and member runs.
- The current token exchange method validates the session user, tenant, and user authorization, but not team membership. Team member/MCP scope validation is a new requirement, not a capability already provided by the existing endpoint.
- Retain the established policy of disabled stdio, per-user authorization, and runtime-side token injection. Independent member sandboxes do not justify re-enabling stdio or injecting MinIO or Docker management credentials into them.

For the current state, see [MCP Management](./mcp-management.en-US.md) and [MCP Outbound Authorization Design](./mcp-authorization-design.en-US.md).

## 7. Sandbox Alternatives and Final Choice

| Dimension | One sandbox shared by members | Independent member sandboxes (selected) |
|------|------------------|----------------------|
| Resources and startup | One container; lower resource usage | Multiple containers; higher startup and memory costs |
| CLI and dependencies | Must be unified; versions and environments may conflict | Each member retains its own CLI, dependencies, and configuration |
| File collaboration | Direct reads and writes in the same directory make handoff convenient | Explicit artifact publication and retrieval required |
| Credential boundary | Shell can access the shared environment; separate directories do not provide security isolation | Stronger member-level isolation, still affected by mounts, networking, and container privileges |
| Parallel execution | Prone to file overwrites, port contention, and shared-state changes | Less file and process interference; better suited to future parallelism |
| Failure impact | A damaged environment affects all members | Usually limited to the corresponding member |
| Snapshots and reclamation | One workspace; fewer lifecycles | Multiple states and snapshots; more lifecycle management |
| Integration cost | Requires dedicated adaptation for shared objects, credentials, and reference lifecycles | Closer to existing session-based sandbox management, but still requires child-run identifiers and artifact handoff |

**Independent sandboxes are the final choice.** The earlier recommendation to share a sandbox primarily sought lower resource costs, but the fewest resources do not necessarily mean the fewest code changes. Independent sandboxes better serve the goal of preserving each member's Agent capabilities.

The constraints are:

1. The lead only orchestrates and creates no execution sandbox. Filesystem capabilities must not fall back to execution on the host.
2. Create a container only when a member actually needs a filesystem or Shell execution environment. Model-only or remote MCP calls need not start Docker for this reason. Whether existing warm-up and workspace hooks permit genuinely lazy creation remains a validation item.
3. The logical isolation scope comprises the tenant, root team session, and member child run. If the underlying layer still accepts only one sessionId, map this scope to a unique child-session ID, not just an `agentId` or `teamId`.
4. Explicit continuation of the same child session can restore its own workspace and snapshot. Different users, different root sessions, and different concurrent tasks of the same member must not accidentally share a container.
5. Stopping the root run cascades cancellation to members. Containers, MCP clients, task records, and snapshots all fall under lifecycle management; do not rely on incidental cache expiration for cleanup.
6. Independent containers are not a promise of complete isolation. Do not mount the host Docker socket or other members' workspaces, or share members' secret environments. Retain deployment-level network and resource restrictions.

## 8. File Sharing: MinIO Artifact Handoff

### 8.1 Why Not Share the Entire Workspace

| Approach | Benefits | Costs or issues | Choice |
|------|------|------------|------|
| Same writable directory or shared volume | Direct reads and writes; low transfer cost | Concurrent overwrites, overlapping permissions, and workspace coupling weaken independent sandboxes | Not selected |
| Handoff of the entire workspace or sandbox snapshot | Can reconstruct a complete environment | Large and may carry credentials; snapshots are for recovery, not business-file sharing | Not selected |
| Put all file content into prompts | Convenient for small text | Large files are expensive or exceed limits; binary files cannot be handed off directly | Small text summaries only |
| MinIO non-overwritable artifacts + file references | Supports authorization and traceability; suitable for independent sandboxes | Upload/download latency; metadata and lifecycle management required | Selected |

The team file area is a “logical artifact collection isolated by tenant and root session.” It does not require a new object-storage service or mean mounting the same disk in every container.

### 8.2 Two New Logical Actions

| Action | Input and behavior | Output |
|------|------------|------|
| Publish file | Read a specified file from the current member's permitted workspace, upload it to MinIO, and register artifact metadata | `fileId`, filename, type, size, and producer association |
| Retrieve file | Validate that the current child run may access the root session's artifact, then download the specified `fileId` to this member's workspace | A local file reference for this member |

These two actions are not implemented. Their names describe business semantics, not published tool names or HTTP endpoints. Prefer reusing existing file-storage primitives, but do not treat existing `persist/retrieve` operations as complete interfaces with team permissions and cross-sandbox write capabilities.

```text
Researcher sandbox: generate data.csv
    ↓ Publish; obtain fileId=A
MinIO team-session artifact collection
    ↓ The lead passes task instructions and A to the analyst
Analyst sandbox: retrieve A, analyze it, and produce conclusions
    ↓ Publish; obtain fileId=B
Writer sandbox: retrieve B and generate report.md
    ↓ Publish; obtain fileId=C
The lead reviews the result and delivers C to the user as the final attachment reference
```

### 8.3 Mandatory File Boundaries

- A `fileId` is a reference, not an authorization credential. Retrieval, preview, and user download must all validate the tenant, root-session permissions, and run ownership.
- Models cannot specify arbitrary MinIO bucket/objectKey values. The server resolves storage locations from authorized metadata.
- Publication may read only files within the current member's permitted directories; retrieval may write only to that member's own workspace. Reject path traversal and symlink escapes, and limit file sizes and transfer resource consumption.
- Every publication generates a new file ID without overwriting previous artifacts. Files with the same name may coexist; the lead explicitly selects the version to pass to subsequent members.
- Publish only explicitly selected artifacts. Do not automatically upload the entire workspace, environment variables, keys, or full snapshots. Secret files should not enter publishable directories or result prompts.
- Return a usable reference only after the upload completes and readable metadata is registered. Failed publication must not return a file ID that appears successful.
- Persist file references and ownership so they are not lost when a sandbox is reclaimed. Link artifact cleanup to session retention policies to avoid unlimited permanent accumulation.
- If MinIO is unavailable, explicitly report artifact-handoff failure. Do not silently switch to public temporary links, host directories, or passing large files through prompts.

The lead passes only references and summaries, without downloading files to a lead sandbox. Any necessary inspection of file contents is still delegated to members.

### 8.4 Limits on Reusing the Existing Attachment Path

The current `OutputFileStore` provides MinIO file write/read primitives, but the existing download Controller checks only login status for web/task files and lacks team-session-level ownership validation. **Team artifacts must not simply be placed where the old access rules can read them and then be claimed to be isolated.** Implementation must add object-level authorization to the corresponding download entry point, or use a protected storage scope inaccessible through the old entry point and provide an authenticated and authorized entry point.

Reusing infrastructure does not mean reusing all of its default permissions. Existing sandbox snapshots are for workspace recovery, not team artifacts. Final file delivery starts with the Web UI; this does not expand channel file-delivery capabilities.

## 9. Events, Confirmation, and Failure Handling

### 9.1 Keep One Root Session and Preserve Child-Run Provenance

Externally, continue using the root session's SSE channel and sticky routing. Members execute within the same instance; do not create a separate external routing request for each delegation.

Team events must at least support correlation with the root run, member, child run, and relevant tool calls. Explicit fields or server-side mappings are acceptable, but `agentId`, display names, or SDK `source` strings alone cannot distinguish multiple tasks by the same member. Tool-argument and result buffers must also account for child-run scope rather than assume that every `toolCallId` is globally unique.

Only member reports and artifact references become input to the lead. Do not concatenate every chunk of member streaming text directly into the lead's final answer. Member execution logs, user-visible text, and token statistics should identify their source; avoid double-counting when aggregating costs for the root session.

### 9.2 End-to-End Human Confirmation

```text
A member requests confirmation
    ↓ Record the root session, child run, tool call, and parameters awaiting confirmation
The frontend displays the member and action; the user approves or rejects
    ↓ The server validates the user, ownership, pending state, and one-time decision
Return the decision to the original child run, not the lead's own tool list
    ↓ The member executes or handles rejection and produces a result
The lead continues after receiving the member's status and result
```

Waiting for confirmation must not count as task success. Rejection, expiration, cancellation, and duplicate submissions require explicit handling and must not cause tools to execute more than once. Preserve the original runtime context while waiting. A timeout, disconnection, or process exit must not imply approval or allow the task to resume as completed.

The root stream must not go silent while it waits. Session-router declares a stream idle after 120 seconds with no event and channel-service after 180 seconds, while a human answer can take minutes, so silence would tear down a run that was still able to continue. The runtime publishes a `KeepAliveEvent` on the root SSE every `harness.team.confirm-heartbeat-seconds` (30 by default): it carries the child run's source and nothing else, and every consumer ignores it as neither output nor end.

A heartbeat only protects a stream that is still connected. When a waiting stream is cut (timeout, closed tab, process exit), the tools still awaiting a decision survive only as ASKING records in the persisted state, so the next request must replay that confirmation card with its original arguments; returning an unactionable error instead kills the session — every later message reports the same pause, and confirming reports that nothing is pending.

The following are not acceptable substitutes for D7: automatic approval, removing tools that require confirmation from members, automatically rejecting every request, or having the lead execute all dangerous actions. Any non-interactive entry point that adopts Teams must define its confirmation policy separately; it is not supported by default.

### 9.3 Lifecycle and Budgets

- When a member fails, return an explicit failure status, usable results, and published file references to the lead. The lead decides whether to reassign the work; do not return false success.
- When the user stops the root run, stop executing members, prevent subsequent delegation, and close runtime resources. Cancellation cannot roll back external side effects already issued through Shell/MCP.
- Explicitly bound total delegations, member iterations, runtime, active containers, and file-transfer volume. Specific thresholds will be determined through implementation and validation; this design does not invent default numbers.
- If the foreground sequential-delegation recommendation is adopted, enforce concurrency limits and disable automatic timeout-to-background transitions at runtime, not merely in the lead's prompt.
- After a process crash or routing migration, available snapshots do not mean in-flight tasks can resume without loss. The first release must not automatically replay tool calls with side effects. Mark runs as interrupted and allow controlled re-initiation; validate confirmation recovery and sandbox recovery separately.

### 9.4 How Member Messages Are Shown and Replayed in the Session Page

Presentation only answers "who said this". It does not change the provenance contract of §9.1, the confirmation loop of §9.2, or the lifecycle semantics of §9.3.

**One provenance contract serves both live and reload.** Live events carry `ChatEvent.source`. Persisted history carries no source, and a member's conversation is stored in its own child session, so replay resolves the root session's Team roster and stamps each member child session with an `EventSource` whose fields match the live one — team, member, child run, child session. Names come from the current roster rather than the snapshot taken at the time: when a member is renamed later, the user should see who it is now. No run identifier was ever persisted, so the child session identifier stands in for it during replay — it says which member a run belongs to, but a member child session is reused across delegations, so on its own it cannot say which delegation a run came from or which card it belongs in. The frontend still must not infer ownership from display names or `agentId`: that something is a member run is decided solely by the child run identifier, while which delegation it nests in is claimed separately, by call order (below).

**Member runs render inside the lead's delegation card.** The lead's answer for a turn still stays one continuously appended bubble; a member no longer gets its own bubble beside it but renders inside the `team_delegate` tool card it was delegated from. Ownership is settled when the message is created rather than searched for at render time: live takes the earliest card that member has emitted and no run has claimed yet, in FIFO order, and replay scans the cards already present in that turn in order. Both paths key on the member identifier and call order, never on timestamps, so the timeline seen live is the same one seen after reopening. A member run that claims no card stays a bubble of its own — a rejected delegation, a lead turn that left no recorded call in history, or the roster-unreadable fallback must never make member output disappear from the screen. The lead bubble is still not split at the delegation point — splitting it would separate the `team_delegate` tool card from its own result event, leaving tool state unable to resolve.

**The lead's delegation result is the only signal that closes a member run.** The server filters out the member's end event, so the frontend never receives "child run finished". Closure comes from the lead's `team_delegate` result event: the tool call identifier links it to that delegation, and the member identifier in its arguments locates the card and the run inside it. Because only one open run per member is allowed and delegation is foreground-blocking, the mapping stays unique. A card whose result came back but that no run claimed — the delegation was rejected, or it failed outright — has to be released from the pending set; left in place it would steal the slot of that member's next delegation. Three fallbacks remain: lead end, lead error, and root-stream teardown must all move any still-open member run to a terminal state. "No more events" is not "done"; an interruption has to render as an interruption.

**Closure is not success.** The lead decides whether to reassign or wrap up from the delegation's return value, so apart from the one path where the member emits its own error event, every other failure ending (stopped by the user mid-run, aborted after repeated confirmations, confirmation wait timed out, member returned nothing, member run threw) comes back to the lead as an ordinary tool result. The `team_delegate` result event carries no failure marker, and those runs render as done. "Done" therefore currently means exactly "this delegation closed, and no failure event was seen while it ran". Making it honest needs either a failed state on the delegation result or a source-stamped closure event at the end of a member run; both change the event stream channels consume and the tool result the model sees, so the decision is deferred. Replay carries no lifecycle information at all, so a member run reopened from history always renders as done; the tool cards from that turn that never got a result, though, render as interrupted — the tool-level state shows where the turn was cut even when the run-level state cannot.

**Folding: the delegation card stays collapsed unless one of its runs is live.** The fold control is the `team_delegate` card's own header — there is no separate member bubble header any more. Collapsed, the header still has to say who the delegation went to: the member name shows at the right of the card header, and a card holding several runs reads "name +N". Expanded, each run inside it has its own summary line — member name, team, status, tool count, elapsed time — followed by its own text and tool cards, confirmation entry point included. The task first line is not repeated there; it already sits in that same card's delegation arguments, and only a run that claimed no card and stands as its own bubble shows it in its header. A card auto-expands while one of its runs is working or waiting for confirmation and collapses once closed; a manual toggle overrides the automatic state, applies to that one card, and clears on session switch. All four statuses stay visible — working, waiting for confirmation, done, failed — and "waiting for confirmation" is the entry point into §9.2, so the card stays open while it waits instead of hiding the decision behind a fold.

**History replay merges child sessions without a migration.** Reading history keeps the lead session as the backbone, recovers member messages by the child-session naming rule, retains only member displayable text and tool calls, and interleaves them. Both interleaving and run grouping work a whole turn at a time rather than a flat timestamp sort. Splitting a lead assistant message from its own tool results breaks the pairing the frontend relies on; and since two members may run concurrently, sorting their messages together would let one member's log land between another member's tool call and its result, leaving a card that never resolves. The insertion unit is therefore one lead turn (an assistant message plus the tool results that follow it): member logs land after the turn that produced them and before the lead's next turn, and the frontend claims them into that turn's `team_delegate` cards in call order, which reproduces the live order — deterministic because delegation is foreground-blocking. Persisted tool logs carry no tool call identifier, so replay gives every card a synthetic one numbered within its own message; the claim only needs it stable inside one message, never unique across sessions. Because the child session identifier does not distinguish one delegation from the next, replay groups by contiguity: member logs sharing the previous log's child session join the current run, and a lead log in between starts a new one, so the number of member runs seen live is the number seen after reopening, each nested in its own card. The task first line comes from the lead's persisted delegation arguments, since the member's task brief is not part of the merged timeline, and it is read in scan order: the arguments are persisted before the member runs, so scanning the lead turn records that member's latest delegated task and the member runs that follow take it — where it is used only on the header of a run that ends up standing as its own bubble. No per-member task queue is kept — a delegation without task text, or a member that produced no logs at all, would shift such a queue out of step with the runs, while overwriting on the second delegation lines up with the member's own logs exactly. A turn may hold several tool calls, and replay has to pair each one with its own same-source result log by name; absorbing only the first leaves the rest spinning as "calling" forever. The same rule governs tool-level interruption: a call that matches no result in replay, or that still had no result when the live stream closed (lead end, lead error, root-stream teardown), settles as "interrupted" — except a card waiting for confirmation, which already has its own state and must not be overwritten. Member logs newer than the lead's last persisted message (an interruption, or a lead that never summarized them) are appended at the end instead of disappearing. When the roster cannot be read or a member child session has no content, the response falls back to lead-only history; incomplete team history must never make the session unopenable. A member removed from the team takes that same fallback, so its member runs stop appearing and the delegation survives only as `team_delegate` result text inside the lead's bubble. The cost of not migrating sits in recovering the member child sessions: every session identifier is listed and then filtered by prefix, and "does this session have member child sessions" can only be answered by that step, so every history read pays one full session scan — ordinary sessions included, which simply return after the scan without fetching the roster or reading any member session. If that scan becomes hot, query by prefix instead of adding a second index over sessions.

**The lead label appears only in team sessions.** The lead's name comes from the agent name already returned by session configuration and is shown on bubbles only when the session is bound to a team, so it can be read against the member runs nested in delegation cards; when it is absent nothing renders, and no new field or request is introduced for it.

## 10. Comparison and Trade-off History

### 10.1 Collaboration Paradigms

| Approach | Where it fits | Main cost | Conclusion in this round |
|------|--------|----------|----------|
| Lead-driven delegation | The lead dynamically assigns specialist members; matches the goal | An extra layer of model calls; budgets and result review required | Selected |
| Fixed pipeline or graph | Deterministic ordering and reproducibility | Additional workflow configuration and runtime semantics | Not selected for the first release; this is not a claim that the SDK has no related capabilities |
| Group-chat negotiation | Discussion from multiple perspectives | More complex message broadcasting, turn ordering, and termination control | Not selected |
| Cross-service Agent/A2A | Independent deployment and cross-system reuse | Remote identity, discovery, task protocols, and failure handling expand the scope | Not selected; not prohibited for the future |

### 10.2 Team Modeling

| Approach | Benefit | Problem | Conclusion |
|------|------|------|------|
| Add a team toggle and member bindings to Agent | Less data modeling and fewer pages | Team is not an independent object; ordinary and team-leading usage are coupled | Withdrawn |
| Bind Team one-to-one to a lead; accessing the lead accesses the team | Reuses the existing entry point | Difficult to distinguish the same lead's ordinary sessions and different team compositions | Withdrawn |
| Independent Team + lead references an existing Agent + explicit team sessions | Zero duplicate maintenance of the lead's capabilities | A team cannot exist without a ready-made Agent; "configure a team" becomes "configure an Agent first, then pick it" | Selected 2026-09-18, superseded 2026-09-20 by the next row |
| **Team carries its own lead configuration (model, prompt, Skills)** | The team is a self-contained object and step 1 of the wizard configures the lead outright; `agent` keeps exactly two identities, conversable and member | The delivery side must synthesize a spec for the lead; a session's `agent_id` must become nullable | **Selected** (2026-09-20) |
| Team owns one hidden `agent` row as its lead | Runtime stays completely unchanged, and `session.agent_id` needs no change either | Introduces a phantom agent: every list and selector query must remember to filter it, names and workspace keys need collision care, and direct edit/delete need extra guards | Rejected after evaluation — trades a one-time cost for a permanent trap |
| Team additionally stores Tool, MCP, and CLI bindings | Fully self-contained team configuration | Duplicate, drift-prone maintenance alongside member Agent capabilities, and the lead should not execute anyway | Not selected; the team carries only the lead's model, prompt, and Skills |

A parent-child binding table can itself represent many-to-many reuse. Choosing an independent Team is a product boundary, not a consequence of a supposed rule that “a binding table can belong to only one team.”

### 10.3 AgentScope Integration

| Integration approach | Benefits | Limitations | Conclusion |
|------|------|------|------|
| Native declarative subagents | Reuses native capabilities with a few declarations | Inherits the parent's toolkit/skill and other settings by default; cannot directly represent independent member configurations, and an empty tool list must not be treated as zero permissions | Not the primary approach |
| Attach the union of member tools/MCP to the lead | Easy to select subsets from the parent set | Expands lead capabilities and mixes credential boundaries; prompts are not permission boundaries | Explicitly rejected |
| Native delegation + full member factories | Reuses delegation while retaining each member's own configuration | Requires adaptation for scope, Harness lifecycle, confirmation, and cancellation | Recommended primary approach; validation required |
| Agent-as-tool | Easy to invoke specialist Agents as tools; the SDK also supports session state | Not equivalent to Harness task, sandbox, and confirmation semantics | Do not mix with the primary approach; do not claim it lacks session capabilities |
| A fully custom orchestration engine | Flexible control | Rebuilds state, events, scheduling, and recovery; conflicts with the minimal-change goal | Not selected |

### 10.4 Lead, Visibility, and File Strategies

| Decision branch | Final trade-off and rationale |
|------|----------------|
| Lead executes directly or only assigns work | Only assigns, reviews, and summarizes; restrict capabilities at runtime while preserving standalone behavior of the original Agent |
| Shared or independent sandboxes | Independent; preserve member environments and reduce interference, accepting higher container costs |
| Shared directory or artifact handoff | MinIO file references; accept transfer costs for explicit permissions and traceable versions |
| Final answer only or visible member activity | Visible activity; requires event ownership and frontend presentation, so zero frontend changes cannot be claimed |
| Display confirmation events or support full approval/resume | Full end-to-end handling; forwarding events does not mean the original child run can resume |
| Minimize code at all costs or preserve full member capabilities | Minimize changes while preserving capabilities and security boundaries, not by substituting tool subsets or automatic degradation for apparent simplicity |

### 10.5 Corrected Technical Assessments

- Having no configured business members does not mean the SDK has no subagent tools. The local 2.0.2 source adds a default `general-purpose` member.
- `AgentEvent.source` is a string, not the old `EventSource` object. Nonexistent `source.agentId/depth/path` properties cannot be read directly.
- `persistSession=false` does not mean every call automatically clears all instances and state. Continued conversations, caching, and recovery must follow actual runtime semantics.
- Local child confirmation requests being able to propagate upward does not mean confirmation decisions can already be routed back to the correct child. `RemoteAskPolicy` is not an implementation of the local end-to-end flow.
- Independent session state or different workspace paths do not imply independent Docker sandboxes. Actual filesystem and container ownership must be explicitly validated.
- A member model failure must not silently switch execution to the lead's model on the grounds that “the framework supports fallback.”

## 11. Change Surface and Reuse Boundaries

| Module | Proposed additions or adjustments | Preserved boundary |
|------|--------------|----------|
| `harnax-entity` / admin | Team (including the lead's model, prompt, and skill bindings), membership relationships, session-Team association, permission checks, and team configuration delivery | Members remain owned solely by their existing Agent and capability bindings; the lead is owned solely by the Team, and no agent row represents a lead any more |
| `harnax-agent-service` | Correlation between root team runs and child runs, member configuration scopes, and routing of confirmation and stop actions | Preserve ordinary Agent entry points and behavior |
| `harnax-harness-core` | Lead/member role assembly, native delegation integration point, independent sandbox lifecycle, and artifact actions | Reuse model, Tool, MCP, Skill, CLI, and sandbox components wherever practical |
| `harnax-protocol` / consumers | Team event provenance, child-run confirmation correlation, and file references | Continue using the root SSE channel without confusing existing event meanings |
| `harnax-webui` | Team management, team-session creation, member activity, and confirmation cards | Do not duplicate existing Agent configuration entry points |
| File storage / admin downloads | Artifact metadata, team-scoped authorization, publication/retrieval, and protected downloads | Reuse MinIO; do not mistake old login checks for object-level permissions |
| `harnax-session-router` | Verify forwarding of new request/event fields and retain root session routing | No separate external route per member or introduction of A2A |
| channel / scheduler / client | Evaluate compatibility at actual entry points and protocol consumers | Do not claim automatic Team support in the first release or change established channel-file and OAuth policies |

Deployment continues to use `docker-new`; this round introduces no new services. Independent sandboxes increase resource requirements for containers, images, snapshots, and file transfers. Actual implementation must update capacity limits, lifecycle handling, and deployment instructions together, not just UI documentation.

## 12. Prototype Validation and Acceptance Gates

All items below are pending engineering validation, not test results from this documentation delivery.

| ID | Validation point | Acceptance criteria |
|------|--------|----------|
| V1 | Independent Teams and ordinary sessions | The same Agent can be used independently and referenced by multiple Teams as a member; ordinary sessions do not accidentally start teams; a Team no longer has to create a lead Agent first in order to exist |
| V2 | Lead capability restrictions | The final tool set contains no business Tool, MCP, or Shell capabilities and none leaked through default paths; no execution sandbox is created; the Skills the Team configures for the lead are visible as text, while their scripts and resource files are not executable and each degradation is named at load time |
| V3 | Full member assembly | Different models, Tool, MCP, Skill, CLI, and environments take effect independently; the lead can delegate without possessing member capabilities |
| V4 | Lazy creation and configuration context | No reliance on expired ThreadLocal context; no reuse of the lead's ToolBox, logging identity, or secret parameters |
| V5 | Independent containers and snapshots | Two members' environments do not interfere; containers are not mixed across tenants, sessions, or repeated tasks, and continuation restores correctly |
| V6 | Artifact handoff | End-to-end flow from A publishing, to B retrieving and processing, to final Web UI download; unauthorized access, out-of-bounds paths, and bypass through the old download entry point are rejected |
| V7 | Event correlation | Multiple tasks by the same member, identical tool names, or repeated local call IDs do not mix streams or merge incorrectly; costs are not double-counted |
| V8 | Human confirmation | User approval/rejection affects only the original child run; duplicate, expired, or post-cancellation confirmation does not replay tools; the lead then continues correctly |
| V9 | Stop and failure | Stopping the root stops members and prevents new delegation; timeouts do not unexpectedly move execution to the background; resources can be cleaned up, and failures are not disguised as success |
| V10 | MCP identity | Correct root user, tenant, and member MCP scope; unknown child IDs do not trigger incorrect token exchange; no switch to the member creator's identity and no enabling of stdio |
| V11 | Existing-feature regression | Ordinary Agent tools, models, MCP, file output, confirmation, and session routing are unchanged by team-role restrictions |
| V12 | Capacity and recovery | Active containers, delegations, and file budgets are bounded; process interruption does not automatically replay operations with side effects, and snapshots alone are not claimed to provide lossless recovery |

Recommended validation order: first build a minimal prototype with a lead and two members to validate factories, independent sandboxes, events, and human confirmation; then implement Team management and complete file handoff; finally perform regression and deployment acceptance checks. If the D8 integration point does not work, revise the engineering approach first rather than silently reducing the confirmed D1–D7 requirements.

## 13. Current-State Evidence and Reading References

The following source locations were checked in this round; they do not mean Team has been implemented there. Line numbers change as code evolves, so use the symbols in the files as the reference.

| Location | Existing fact and integration point for this design |
|------|------------------------|
| `agent-scope.version` in the [root pom.xml](../pom.xml) | Currently uses AgentScope 2.0.2 |
| [Agent](../harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Agent.kt) and [Session](../harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Session.kt) | Session already carries `teamId` (V32); from V34 a team session leaves `agent_id` NULL. The DDL already permits NULL — what has to change is the entity's property type |
| `saveSkillBindings` in [AgentServiceImpl](../harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt) | The four write-time skill guards (missing, disabled, builtin-CLI-repository origin, name conflict) are exactly what the lead's skills should reuse: extract shared validation instead of writing a second copy on the Team side |
| `getAgentSpec/buildAgentSpecResponse` in [InternalApiController](../harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt) | Resolves by external session prefix and delivers the full capabilities of a single Agent |
| [AgentSpecResolver](../harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt) and [AgentSpecContextHolder](../harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AgentSpecContextHolder.kt) | Configuration resolution and synchronous ThreadLocal creation context; lazy member creation needs its own scope |
| `createAgentBase` in [HarnessAgentLauncher](../harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt) | Existing model, MCP, Tool, Skill, CLI, permission, and sandbox assembly does not mean it can be copied directly and safely for members |
| `callStreamInternal` in [HarnessAgentWrapper](../harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt) and [ChatEventConverter](../harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEventConverter.kt) | Current confirmation lists and tool buffers have no team child-run correlation |
| [McpSessionOwnerResolver](../harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt) and `accessToken` in [McpOAuthUserServiceImpl](../harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt) | Existing identity resolution, tenant, and user-authorization logic; arbitrary child IDs are not accepted, and Team membership is not validated |
| [OutputFileStore](../harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/output/OutputFileStore.kt) and [OutputFileController](../harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/OutputFileController.kt) | Existing attachment-storage primitives, but no team publication/retrieval or session-scoped download authorization |

SDK assessments are based on the locally available `agentscope-2.0.2-sources.jar` and `agentscope-harness-2.0.2-sources.jar`. Key symbols include `HarnessAgentBuilderSupport.buildStaticSubagentEntries/allowlistedInheritedToolkit`, `SubagentsMiddleware`, `AgentSpawnTool`, `AgentEvent`, and `SubAgentTool`. These are source-review evidence, not runtime test records.

Related documents: [Lead vs. Sub-agent Architecture Trade-offs](./multi-agent-leader-subagent-design.en-US.md), [Tool Integration Design](./tool-integration-design.en-US.md), [Tool Capabilities](./tool-capability.en-US.md), [Skill Management](./skill-management.en-US.md), and [Session Routing](./session-routing.en-US.md).

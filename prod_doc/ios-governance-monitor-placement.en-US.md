# Harnax iOS: Where Optimization Governance and Cluster Monitoring Land in the App

## 0. Scope and the baseline the anchors were read from

- The console gives these two blocks a first-level menu each. The app has no first-level row to add: the bottom bar is fixed at four tabs (`harnax-ios/Sources/HarnaxFeatures/Root/HarnaxTab.swift:5-9`, one navigation stack per tab at `harnax-ios/Sources/HarnaxFeatures/Root/HarnaxRootView.swift:50-62`). This document decides which tab and which layer each block hangs on, in what order its rows appear, and which gate keeps that shape.
- Anchors come from the current working tree (the navigation shell and the five screens on top of commit `16c488c5`). Console line numbers are the route object's own brace span, checked per object; client line numbers are the ones that actually carry the `case` or the `hx("…")` call.
- Three nouns are fixed: **hub screen** = draws entry rows only, the work happens one push deeper; **queue** = a list waiting for a person to decide row by row, where approving and rejecting are each a write; **monitor** = a read that only adds up records already there, with no write on screen.

## 1. The ownership test: why the two blocks go separate ways

| Test | Optimization governance | Cluster monitoring |
|---|---|---|
| Console access rule | None of the three routes carries one: `:126-133` `/optimization/memory/drafts`, `:139-146` `/optimization/memory`, `:147-152` `/optimization/skill-drafts` (`harnax-webui/config/routes.ts:119-159`) | Also none: `:165-169` `/monitor/skill-usage`, `:170-174` `/monitor/call-metrics`, `:175-179` `/monitor/token-monitor` (`harnax-webui/config/routes.ts:160-181`) |
| Whose data | What the owner's own agents grew — memory merged out of their conversations, skills they proposed, what each of them remembers | Tenant-wide aggregate counters: how often skills are read and used, the tool/MCP/CLI call bill, token spend |
| Kind of action | Read plus approve/reject; one decision moves one state | Pure read, nothing writes |
| The account screen's blocks | Stays out. `SystemRoute` is the block of records somebody creates and deletes (variables, keys, channels), the `/system` group runs `harnax-webui/config/routes.ts:182-223`, and `:205-210` `/system/api-key` is the only gated row in it | Goes in, as its own block. The account screen therefore draws two blocks rather than one long list (`harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:79-94` and `:95-119`) |
| Landing spot | The fourth segment of the agents tab (`harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8-12` for the segment enum, `:59` for its column) | The second block on the account screen, headed Cluster monitoring (`harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:105`) |

One sentence: **did the owner's own agent grow it**. If yes it belongs to the agents tab; if it is an aggregate read it belongs to the account screen.

## 2. The landing spot for Optimization Governance

Four candidates and the verdict:

| Candidate | Where | Verdict |
|---|---|---|
| A Fourth segment of the agents tab | `harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8-12`, `:51-61` | **Taken.** That tab's columns are already one row per agent, and governance is about what those agents grew; the fourth segment costs no width — `harnax-ios/Sources/HarnaxKit/Components/HXSegmented.swift:42` divides the row by option, and the context tab already shows five |
| B Sixth segment of the context tab | `harnax-ios/Sources/HarnaxFeatures/Context/ContextDomain.swift:5` | No. Those five segments are resources an agent can bind, and the column switch expands that enum; an approval queue and a memory store are nothing to bind |
| C A third block on the account screen | beside `harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:79-119` | No. That screen means account and administration; hanging the owner's own queues there reads as an operator's approvals, while every row on them belongs to the signed-in person |
| D The chat tab | The conversation list and the chat window a row opens (`harnax-ios/Sources/HarnaxFeatures/Chat/ChatTab.swift:18-29`) | Cannot carry it. That screen holds conversations only, and not one of the three rows is a conversation's own content |

The segment name and the hub screen's title are two keys on purpose: the segment says Governance (`agent.segment.governance`, `harnax-ios/Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1449` and `harnax-ios/Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1448`) because it has to stay short, while the screen says Optimization governance (`governance.title`, `zh:1452` / `en:1451`) to match the console's first-level menu (`harnax-webui/src/locales/zh-CN/menu.ts:14` / `harnax-webui/src/locales/en-US/menu.ts:14`).

## 3. The hub's three rows and their navigation shape

- The order is the console's: Session memory approvals, then Agent memory, then Skill promotion. A conversation's merge only reaches the long-term layer once its owner says so, so the approval queue is listed before the page showing what those layers already hold (`harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceRoute.swift:21-27`; the console keeps the same order with `harnax-webui/config/routes.ts:126-133` `/optimization/memory/drafts` before `:139-146` `/optimization/memory`).
- The hub lists only the queues it can actually reach (`harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceHomeView.swift:77-85`): when a domain's facade is nil in the assembly, its row is not offered rather than being a button that can only fail. In the assembled app all three are there, because every `/api/admin/**` facade is answered by the same client (`harnax-ios/Sources/HarnaxFeatures/Support/HarnaxDependencies.swift:196-199`).
- All three rows are value pushes: `GovernanceRoute` registers its destinations at `harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceHomeView.swift:27-42`. Neither queue nor the memory list is a leaf — each pushes a detail of its own — so they are driven by the path and never by presentation state, which a deeper push from the same screen would re-present.
- The switch row travels inside the column: the hub draws `HXSegmentBarRow()` in its own scroll (`harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceHomeView.swift:49-72`) and the host only hands the bar down (`harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:38-44`). Pinned outside, the bar held still while the rows and the title scrolled away.

## 4. The monitor's three rows and their visibility

- `MonitorRoute` carries all three (`harnax-ios/Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:16-22`) in the console's Cluster monitoring order: skill monitoring, call metrics, token monitor.
- None is gated: the `/system` group (`harnax-webui/config/routes.ts:182-223`) puts `access` on `:205-210` `/system/api-key` and on no monitor route, so a member sees exactly the rows an administrator does. Trimming by account happens only in the block above (`harnax-ios/Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:23-25`).
- The same existence rule as the hub: skill monitoring and call metrics are filtered on whether their facade was assembled (`harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:98-104`); the token monitor is always there, since it reads the statistics this domain has always had.
- Call metrics goes one layer deeper: of the five groupings on its summary rows, only the rows that resolve to one object are drillable (`harnax-ios/Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:142` registers the destination), and that opens the call list of that one object.

## 5. One queue, one entry

Each of the three queues has exactly one entry row in the app, and it is on the governance hub: session memory approvals, agent memory, skill promotion (`harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceHomeView.swift:27-42`). The conversation list carries no queue row — two entries for one queue means keeping one name and one set of assembly conditions in two places, and the console already answers one menu row to one page.

The pending count appears only on the queue screen's own segment labels (`harnax-ios/Sources/HarnaxFeatures/SkillDrafts/SkillDraftListViewModel.swift:69-72` works out the condition, `harnax-ios/Sources/HarnaxFeatures/SkillDrafts/SkillDraftListView.swift:66` draws it); the hub row carries no number. The hub row and the queue screen draw the same key, `skill.draft.title`: the row-is-named-like-the-page rule treats all three places alike, with no exception (clause 3 in §9).

## 6. Naming rule: one row, one name, and the row's name is the page's name

Every entry row's `titleKey` is the key the screen behind it passes to its own `harnaxTitle`; no row-only title key is added to the catalogues. Two keys for one place do drift — the approval row used to open a queue whose screen titled itself with a different word for the same thing. The page names themselves are the console's menu labels, `harnax-webui/src/locales/zh-CN/menu.ts:14-17` and `:29-32` with their English twin at `harnax-webui/src/locales/en-US/menu.ts:14-17` and `:29-32`.

| Row / screen | The one key | Label the app draws | The console's menu label |
|---|---|---|---|
| Session memory approvals | `memory.draft.title` (`zh:1500` / `en:1499`) | Session memory approvals | Session Memory Approvals |
| Agent memory | `memory.list.title` (`zh:1467` / `en:1466`) | Agent memory | Agent Memory |
| Skill promotion | `skill.draft.title` (`zh:1341` / `en:1340`) | Skill promotion | Skill Promotion |
| Skill monitoring | `skill.usage.title` (`zh:1584` / `en:1583`) | Skill monitoring | Skill Monitoring |
| Call metrics | `monitor.calls.title` (`zh:1458` / `en:1457`) | Call metrics | Call Metrics |
| Token monitor | `monitor.title` | Token monitor | Token Monitor |

Subtitles belong to the row (`governance.memoryDrafts.subtitle` and its two siblings at `zh:1453-1455` / `en:1452-1454`, `monitor.skillUsage.subtitle` at `zh:1457` / `en:1456`) — the destination screen never shows that sentence, so it takes no part in the rule above. `agent.segment.governance` and `governance.title` are two keys for the same reason, not a drift.

## 7. Assembly and the files that carry it

Each of the four new domains hangs one optional facade off the dependency pack (`harnax-ios/Sources/HarnaxFeatures/Support/HarnaxDependencies.swift:33-42`, parameters at `:97-100`): `memory`, `memoryDrafts`, `skillUsage`, `toolMetrics`; the skill queue reuses the existing `drafts` (`:24-27`). Transport is one pair of files per domain, with prefixes at `harnax-ios/Sources/HarnaxAPI/MemoryEndpoint.swift:12`, `harnax-ios/Sources/HarnaxAPI/MemoryDraftEndpoint.swift:12`, `harnax-ios/Sources/HarnaxAPI/SkillUsageEndpoint.swift:11` and `harnax-ios/Sources/HarnaxAPI/ToolMetricsEndpoint.swift:16`.

Screens are one directory per domain: `harnax-ios/Sources/HarnaxFeatures/Governance/`, `harnax-ios/Sources/HarnaxFeatures/Memory/`, `harnax-ios/Sources/HarnaxFeatures/MemoryDrafts/`, `harnax-ios/Sources/HarnaxFeatures/SkillUsage/`, `harnax-ios/Sources/HarnaxFeatures/CallMetrics/`. The long-term memory switch sits on the agent wizard (`harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormView.swift:366-383`) and follows the same three-state rule as the self-evolution switch: untouched, the key is not sent.

## 8. Gates

| Gate | What it holds |
|---|---|
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:44` | Each of the nine rows (three administration, three monitor, three governance) is named by the key its own destination screen titles itself with — it reads nine screen sources, not the routes' constants |
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:15`, `:22`, `:31`, `:37` | Four tabs and no system slot; which rows an administrator, a member and an unknown account each get |
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:71` | The three monitors are their own block in console order, and the token monitor is not drawn as an administration row |
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:79`, `:93` | Every row title and subtitle resolves in both catalogues |
| `harnax-ios/Tests/HarnaxFeaturesTests/SegmentBarGateTests.swift:12-15`, `:19-29` | Both hosts hand the switch row down and all nine columns draw it inside their own scroll — the governance hub is the ninth column |
| `harnax-ios/Tests/HarnaxKitTests/LocalizationKeyTests.swift:28`, `:99` | The two catalogues hold exactly the same key set; every key used in source exists in both |

## 9. Acceptance clauses

1. **One block, one home.** The three governance rows appear only on the fourth segment of the agents tab; the three monitor rows appear only in the second block of the account screen. No row is drawn twice on one screen.
2. **Fourth segment of four.** The agents tab is agents, teams, tasks, governance, with governance last (`harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8-12`); segment labels get an equal share of the row and are not truncated onto two lines.
3. **A row is named like the page behind it.** Pushing any hub row shows its own label in the navigation bar, and the skill promotion row reads the queue screen's own `skill.draft.title` — no exception.
4. **Monitor rows do not move with the account.** A member sees all three, exactly as an administrator does; only the administration block loses the key list.
5. **No facade, no row.** When an assembly has no read capability for a domain, that row is absent rather than present and doomed.
6. **The switch row scrolls with the column.** The hub draws the bar inside its own scroll, like the other nine columns, so bar and rows move at the same speed.
7. **Approve and reject are writes.** Each decision sends one write; a failure stays on screen and can be retried, and no screen turns a decision into a batch action.

## 10. Not doing, and the decisions taken

Not doing: a fifth bottom tab for these two blocks (four is the constraint in §0); making the hub a dashboard of cards; collapsing the three monitors into one screen with a switch; batch approval on either queue; editing memory text in the app.

The three open items were decided (2026-10-11):

| Item | Outcome |
|---|---|
| The skill promotion entry | The hub row only: the conversation list carries no queue row, so this queue has one entry in the app. The row-name-equals-screen-name gate at `harnax-ios/Tests/HarnaxFeaturesTests/SkillNavigationTests.swift:221` holds it, and neither `skill.draft.entry` nor `skill.draft.pendingCount` is left in either catalogue |
| The hub screen's title | Kept as Optimization governance: it matches the console's first-level menu, so the screen still says which console group it answers for once the tab bar is out of view; the segment/screen two-key split stands (§2) |
| The remaining call-metric groupings | Left as they are: only rows that resolve to one object drill down; the console leaves the other three groupings as plain reads too. Whether they deserve a phone drill-down remains a real-usage question |

# Harnax iOS: where the memory domain lands, and its contract

## 0. Terminology and evidence baseline

- This is a **decided but not yet built** design piece: the client has no memory surface today. What follows is the entry point, the cross-end contract and the acceptance clauses; once it is built, every clause here is re-checked against the same shape.
- The evidence baseline is `fefe78ed` on `kotlin-dev`. The three admin read routes, the session-layer decision points on the assembly side, and the client's existing layering and gates were each read back from source; anchors are full repository-relative paths with line numbers.
- Three nouns are fixed: the **long-term layer** is the owner's curated file across conversations (`root/MEMORY.md`) plus its daily ledger (`memory/<date>.md`); the **session layer** is one conversation's own layer, nested inside the agent segment; **pending promotion** is how many **conversations** of this agent still hold content that has not been merged.

## 1. The client answers two blocks, not one

| Block | Content | Server evidence |
|---|---|---|
| Read and clear | List the agents of this owner that have memory, read one agent's curated file and daily entries, delete that agent's memory | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:49`, `:64`, `:83` |
| Per-agent switches | Two switches: long-term memory and session memory, on the agent wizard | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:61`, `:64`, writable on `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:55`, `:63` and `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentUpdateRequest.kt:46`, `:53` |

Neither switch exists on the client: the row model `harnax-ios/Sources/HarnaxCore/Contract/AgentSummary.swift:15` carries neither field and neither does the save model `harnax-ios/Sources/HarnaxCore/Contract/AgentSaveDraft.swift:12`, so the wizard has nothing to toggle. Both server columns already reach the runtime, so the client adds two fields to the row model it already reads rather than a new endpoint.

## 2. How far the read side goes (and what that allows on screen)

- The listing groups over both layers and does the subtraction after grouping (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:81`, `:82`), so an agent that has so far only written conversation layers still occupies a row; its text and dates come from the long-term layer.
- The detail subtracts first and then reads (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:109`), so the curated text and every daily entry it returns are long-term layer objects. Session-layer objects sit under the same prefix, but no route addresses them by conversation.
- Two signals about the session layer can reach the client today: `pendingSessionLayers` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:95` → `:209`, using the promotion's own rule at `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt:282`, deduplicated by conversation) and `sessionMemory` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryServiceImpl.kt:54` looks the agent row up by name under the same tenant the listing itself used, reading `:100`). When no row answers (the memory a rename or a delete left in the bucket) the whole key is absent rather than false, so the client must not render "never answered" as "switched off".
- The client therefore **shows no session-layer text**. Showing it would first need two admin read routes (list by conversation, read one conversation); the key layout already exists (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt:162`), and two consequences have to be accepted: a session layer's text is cleared as each promotion succeeds (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromoter.kt:195`), so such a screen shows promotion progress rather than an archive; and a team member's child session key is derived from the root session (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt:56`), so listing conversations would show rows that keep appearing and never reach a merge.
- The delete enumerates raw object names under a prefix (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:140`) and takes both layers at once. The confirmation copy therefore has to name the curated file, every daily entry and the memory still waiting in conversation layers; naming less under-reports what goes away.

## 3. The entry point: four candidates and the recommendation

| Candidate | Landing | Verdict |
|---|---|---|
| A fourth segment of the Agents tab | Add a case to the segment enum at `harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8`, its title at `:15`, its exit in the switch at `:49` | **Recommended**. The web console mounts this page inside the agent menu group and says out loud that it takes no administrator gate (`harnax-webui/config/routes.ts:59`); a fourth segment is the same ownership. The segment row splits its width evenly between options (`harnax-ios/Sources/HarnaxKit/Components/HXSegmented.swift:26`) and the Context tab already carries five, so a fourth here is not a width problem |
| The administration group on the Me tab | Add a case to `harnax-ios/Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:13` | No. That group is administration and gets a shorter list for a member (`harnax-ios/Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:19`, registered and exited at `harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:79`, `:106`); memory is the owner's own data, so it would either be cut by that gate or empty the group of its meaning |
| A sixth segment of the Context tab | Add a case to `harnax-ios/Sources/HarnaxFeatures/Context/ContextDomain.swift:5` | No. Those five segments are the resources an agent binds to, and `harnax-ios/Sources/HarnaxFeatures/Context/ContextView.swift:35` expands exactly that enum; memory is not a bindable resource |
| A drill-down on the agent row | The row's drill-down lives at `harnax-ios/Sources/HarnaxFeatures/Agents/AgentListView.swift:53` | No. Going agent by agent cannot answer "see it all and clear it", which is the reason this screen exists |

The recommended navigation: the fourth segment of the Agents tab → the list of this owner's agents that have memory (one row per agent, text from the curated file, date count next to the pending-promotion count) → one agent's detail (the whole curated file and every daily entry) → delete from the row, behind a confirmation that names all three kinds of content.

## 4. Cross-end contract clauses

1. The path parameter is spelled `agentId` and carries the **agent name the runtime keys memory on**, not a database id (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:70`, `:89`). The row's `name` is the key; addressing by `id` reads back as "no such agent".
2. A business failure is a 200 response carrying an error code in the envelope (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:58`, `:77`), and the detail answers code `404` when the owner has no memory for that agent (`:73`). "Nothing remembered yet" is an empty state, not an error state, and renders as one.
3. The listing returns an array, not a page body (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:54`), so the client's shared paged state machine has no scope on this route.
4. The owner never becomes a parameter: tenant and user both come from the session credentials, and the tenant header the shared transport injects is the only lever. The client composes no owner prefix and puts nothing but a validated agent name into a path.
5. Both switches are three-state integers: a row answers `0` or `1`, a save writes a key only for the switch the operator moved and omits the other entirely, following the existing `skillSelfWrite` handling (`harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormViewModel.swift:183` keeps the switch and the "touched" state, writes at `:1089`, prefills at `:1131`). Sending the seeded value back would be a write the operator never asked for.
6. The team lead gets neither switch: the server hands a lead a fixed `1` and `0` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:645`, `:646`), and the team save model `harnax-ios/Sources/HarnaxCore/Contract/AgentSaveDraft.swift:168` has no such fields, so no step of the team wizard offers either control.

## 5. Files the landing touches

| Action | File |
|---|---|
| New | `harnax-ios/Sources/HarnaxCore/Contract/MemorySummary.swift`: the list row, the detail and the delete result |
| New | `harnax-ios/Sources/HarnaxCore/Contract/MemoryCataloging.swift`: this domain's read-and-clear facade, shaped like `harnax-ios/Sources/HarnaxCore/Contract/TokenStatsCataloging.swift:19` |
| New | `harnax-ios/Sources/HarnaxAPI/MemoryEndpoint.swift`: the three routes and their arguments, following `harnax-ios/Sources/HarnaxAPI/TokenStatsEndpoint.swift:14` |
| New | `harnax-ios/Sources/HarnaxAPI/MemoryClient.swift`: the `extension AdminClient` conformance, over `harnax-ios/Sources/HarnaxAPI/AdminClient.swift:6` |
| New | `harnax-ios/Sources/HarnaxFeatures/Memory/MemoryListView.swift`, `MemoryListViewModel.swift`, `MemoryDetailView.swift`, `MemoryDetailViewModel.swift` |
| New | `harnax-ios/specs/08-memory.md`: the client spec once built, in the same shape as this piece |
| Change | `harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift`: the segment enum, its title key and its exit |
| Change | `harnax-ios/Sources/HarnaxFeatures/Support/HarnaxDependencies.swift:32`: the new dependency, wired at `:157` |
| Change | `harnax-ios/Sources/HarnaxCore/Contract/AgentSummary.swift`, `harnax-ios/Sources/HarnaxCore/Contract/AgentSaveDraft.swift`: the two fields and the three-state encoding |
| Change | `harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormView.swift:338`, `harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormViewModel.swift`: two control rows and their state transitions |
| Change | `harnax-ios/Sources/HarnaxKit/Resources/en.lproj/Localizable.strings`, `harnax-ios/Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings`: the new keys |

## 6. Copy and the localisation gates

- New keys in the two catalogs are **appended as one memory block at the end of each file** rather than inserted between existing lines: the segment name, the list title, the empty state, the three kinds of content named in the delete confirmation, and the two switches with their hint text.
- What each gate guards: `harnax-ios/Tests/HarnaxKitTests/LocalizationKeyTests.swift:27` requires both catalogs to define the same key set, and `:98` requires every key used in source to exist in both. New copy therefore lands in Chinese and English in the same batch; one side missing turns the build red.
- The path a confirmation sentence promises has to exist on the screen that renders it: the "memory still waiting in conversation layers" it names is taken by the server's prefix sweep, so the item stays in the copy even while the client shows no session-layer text.

## 7. Assertable clauses

1. **The listing shows only the long-term layer.** An agent that has so far only written conversation layers appears in the list with empty text and empty dates plus a pending-promotion number; its detail reads the long-term curated file and no session-layer text appears on screen.
2. **"Nothing yet" and "could not read" stay apart.** Code `404` renders as the empty state, any other error code or a transport failure renders as the error state, and the two never share one sentence.
3. **The delete names all three kinds of content.** The confirmation shows the curated file, every daily entry and the memory waiting in conversation layers; after a successful delete the agent leaves the list and `deletedObjects` is reported as the number it is.
4. **The three states of the two switches hold.** Opening the wizard and saving without touching a switch leaves both keys out of the body; touching one still leaves the other out; a touched switch writes `0` or `1` and the value the row carried takes no part.
5. **Addressing is by name.** The jump from a list row to its detail carries the agent name, and an agent whose name contains a dot or a hyphen reads back its own memory instead of "no such agent".
6. **No switches on the lead screen.** No step of the team wizard offers either the long-term or the session memory control.

## 8. Out of scope, and what is still open

Out of scope on the client: showing session-layer text (no route addresses a conversation); editing memory content by hand; deleting one conversation's memory; a "consolidate now" action that forces a promotion; rendering memory as chat bubbles; an owner-wide roll-up across agents.

Open, and only the controller decides:

| Open item | Options and recommendation |
|---|---|
| Entry point | A recommended (the fourth segment of the Agents tab), B second (its own ungated row on the Me tab); the reasons for rejecting C and D are in the candidate table above |
| Whether both blocks ship together | Recommended together: the two switches already have a landing on the agent wizard, and splitting them only adds one more regression pass |
| Session-layer text | Recommended to wait until "does a team member get a session layer at all" is decided, because that answer is what such a screen would have to show |

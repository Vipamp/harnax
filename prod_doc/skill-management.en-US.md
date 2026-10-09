# Harnax Skill Domain Design

This document describes the current implementation of the skill domain: the data model for skill sources and skill content, source categories and loaders, the management API and its persistence rules, how skills bind to agents / team leads / CLI packages, visibility and tenant read semantics, the Admin-to-agent-service delivery contract, the runtime assembly chain, and the mechanism that projects skill files onto the sandbox filesystem.

## 1. Scope and Overview

A skill consists of a name, a description, a `SKILL.md` body and a set of bundled resource files. Skill content lives in columns of the MySQL `skill` table; the platform keeps no on-disk copy of skill files. Bundled resources are stored in `skill.resources` as a JSON object of `relative path -> file content`. Whether a remote source is reachable only affects synchronisation, not execution.

A skill reaches a running session through exactly two sources:

1. skills the operator bound explicitly — rows in `agent_skill_binding` (an ordinary agent) or `team_skill_binding` (a team's lead);
2. the skill shipped by a selected CLI package — the row pointed at by `cli.skill_id`, delivered inline on `cliDetails` and obtainable only by selecting that CLI.

Both sources are merged into one skill list on the agent-service side, installed into the harness's in-memory skill repository by `harnax-harness-core`, and written by `SandboxSkillProjector` into `<workspaceRoot>/skills` of the session's container. The runtime side never reads the skill table: all content comes from the one Admin delivery that was already authorised.

The management plane lives in `harnax-admin` (Kotlin) and exposes two API surfaces: `/api/admin/skill-sources` (create-and-install) and `/api/admin/skill-repositories` + `/api/admin/skills` (repository and skill separated, two-step sync). Both read and write the same `skill_repository` / `skill` rows and share one validation rule set (`SkillSourcePolicy`) and one persistence path (`SkillInstaller`).

Installation has partial-success semantics: only some skills of a source may be stored. A successful HTTP response does not mean everything landed. The response carries a `SkillInstallResponse` that places each item into `installed` / `updated` / `failed` (with reason) / `flagged` (content-scan hit, stored disabled), plus the source-level fields `stale`, `sourceError` and `emptyReason`, and the derived `savedCount` / `failedCount` / `complete` / `summary`. Callers must surface it.

## 2. Code Layout

| Location | Responsibility |
| --- | --- |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt` | `/api/admin/skill-sources` endpoints |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillRepositoryController.kt` | `/api/admin/skill-repositories` endpoints |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt` | `/api/admin/skills` endpoints |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillSourceServiceImpl.kt` | create-and-install, ZIP upload, preview, re-install |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillRepositoryServiceImpl.kt` | repository row reads/writes and the cascade-delete entry point |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillServiceImpl.kt` | skill row reads/writes, list predicate, disable/delete preconditions |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/` | source policy, load-and-persist, sync recording, content scanning, binding resolution |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/` | GIT / NPM / ZIP loaders and `SKILL.md` parsing |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` | delivery contract: `buildAgentSpecResponse`, `specForTeam`, `skillDetail` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` | delivery to runtime spec: `withCliSkills`, `buildAgentSpec` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillAdaptorImpl.kt` | reduction of delivered skills to `AgentSkill` |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt` | in-memory skill repository registration, default workspace skills disabled |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt` | projection trigger once the container handle exists: `projectSkills`, `deliveredSkills` |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SandboxSkillProjector.kt` | skill file projection and per-turn convergence |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/`, `harnax-entity/src/main/resources/mapper/` | entities, mapper interfaces and XML statements |
| `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` | admin's schema baseline: every table and column definition in the admin database lives in this one script |
| `harnax-webui/src/pages/skill/`, `harnax-webui/src/services/ant-design-pro/skillSource.ts` | console skill pages and source service calls |
| `harnax-cli/cmd/skill.go` | command-line calls to `/api/admin/skills` and `/api/admin/skill-repositories` |

## 3. Data Model

Table structure is defined by admin's schema baseline `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`: that one script holds every table and column definition, and there is no later version to stack on top of it.

### 3.1 skill_repository (skill source)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/SkillRepository.kt`

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | bigint | primary key |
| `tenant_id` | bigint, default 1 | owning tenant |
| `name` | varchar(100) NOT NULL | source name; unique among active rows of the tenant |
| `url` / `branch` | varchar(500) / varchar(100) default 'main' | GIT address and branch; also the fallback read when `source_config` is unusable |
| `source_type` | VARCHAR(20) default 'GIT' | `GIT` \| `NPM` \| `ZIP` \| `BUILTIN` |
| `source_config` | text | source config JSON: GIT `url`/`branch`, NPM `packageName`/`registry`, ZIP `zipPath` |
| `version` | varchar(100) | version identifier, written into `skill.version` row by row during sync |
| `description` | text | free text |
| `status` | tinyint(1) default 1 | 0 disabled / 1 enabled, and only those two values |
| `is_public` | tinyint default 0 | visibility; `skill.is_public` follows it |
| `creator` | varchar(100) | creating username |
| `active` | tinyint(1) default 1 | 1 present / 0 deleted |
| `last_sync_time` | datetime NULL | when the last sync finished; NULL until the source has been read once |
| `last_sync_status` | varchar(16) NULL | `SUCCESS` \| `PARTIAL` \| `FAILED` \| `EMPTY` |
| `last_sync_detail` | mediumtext NULL | sync report JSON: `saved`/`installed`/`updated`/`failed`/`flagged`/`stale`/`error` |

There is no content storage path column: skill content exists only in the `skill` table's columns.

### 3.2 skill (main table)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt`

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | bigint | primary key |
| `tenant_id` | bigint, default 1 | owning tenant; taken from the source row on the sync path, from the caller's tenant on the manual create path |
| `name` | varchar(100) NOT NULL | skill name; unique among active rows of the repository |
| `repository_id` | bigint NOT NULL | owning source |
| `description` | text | skill description, loaded into `AgentSkill.description` |
| `skillmd` | mediumtext | `SKILL.md` body (frontmatter included), loaded into `AgentSkill.skillContent` |
| `resources` | mediumtext | resources JSON, `file name -> file content`; an empty string means no bundled files |
| `version` | varchar(100) | set to the source's `version` during sync |
| `status` | tinyint(1) default 1 | 0 disabled / 1 enabled; disabled takes the skill out of delivery scope |
| `is_public` | tinyint default 0 | follows the source repository's visibility |
| `creator` | varchar(100) | username; imported skills inherit the source row's creator |
| `active` | tinyint(1) default 1 | 1 present / 0 deleted (`SkillMapper.deleteById` is an `UPDATE ... SET active = 0`) |

`AgentSkill` refuses a blank name, description or body, so those columns carry non-blank checks on the write paths: `SkillSourcePolicy.requireContentOnCreate` / `requireContentOnUpdate`.

`resources` must parse as `Map<String, String>`, enforced at save time by `SkillSourcePolicy.requireValidResources`; the runtime's handling of an unparseable value is covered in "From spec to AgentSkill".

A downloaded package's content is written into the columns above and the temporary directory is then discarded, so MySQL is the only place skill content lives; `skillmd` and `resources` are MEDIUMTEXT rather than TEXT because the resources JSON embeds every bundled file into one value.

### 3.3 agent_skill_binding and team_skill_binding

| Table | Business columns | Unique key |
| --- | --- | --- |
| `agent_skill_binding` | `agent_id`, `skill_id` | `uk_agent_skill_binding_agent_id_skill_id (agent_id, skill_id)` |
| `team_skill_binding` | `team_id`, `skill_id` | `uk_team_skill_binding_team_id_skill_id (team_id, skill_id)` |

Both tables also carry `create_time` / `update_time` and nothing else beyond that pair of ids. There is no per-skill environment channel resolved at skill level: `AgentToolBinding` / `AgentMcpBinding` / `AgentCliBinding` have an `env_bindings` column that Admin resolves into plaintext on delivery, and the skill side has no consumer of such a column, so neither skill binding table has one.

`team_skill_binding` is the only capability table a team owns. Tool, MCP and CLI configuration stays on the agent, because a lead orchestrates and executes nothing itself, so there is no entry point that could fill in such a binding.

A save rewrites the whole set: `AgentSkillBindingMapper.deleteByAgentId` followed by `batchInsert`, so the unique key can only be hit by duplicate ids inside one request — `SkillBindingResolver.resolveBindable` applies `distinct()` precisely so a database error is never what the operator reads.

### 3.4 cli.skill_id: the package's own skill

The `cli` table carries a nullable `skill_id` meaning "the `skill` row registered from the `SKILL.md` this CLI package ships under its `skill/` directory". "Which skill belongs to which CLI" is a column on `cli`, not a many-to-many join table. A CLI's identity is its package name (`uk_cli_name`): a new version overwrites the same row, and the row id stays.

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrar.kt` is the only writer of that relationship; no page and no API writes the `cli` table:

- a new package = one `cli` row plus one `skill` row, committed inside the same `TransactionTemplate` execution, so a package that fails halfway leaves no orphan skill row that nothing points at and nothing reclaims;
- a registered package has every manifest-owned column overwritten, so a rewritten in-package `SKILL.md` converges in place onto the same skill row;
- when a package leaves the directory, its `cli` row is deleted, its shipped skill is logically deleted with the usual convention (`active = 0`) together with the agent / team bindings pointing at it (`pruneMissingPackages`), and a re-registered package gets a fresh row;
- two packages declaring one name: the higher manifest version wins; two files with the same name and same version are both left unregistered, because nothing says which one the operator means;
- `cli.status` is the operator's kill switch and re-registration does not overwrite it; the shipped skill row follows that switch when the package is re-registered.

`SkillBindingResolver.resolveBindable` refuses to let an agent or a team lead bind a skill from the builtin repository directly, so a `cli.skill_id` row reaches the runtime only through its CLI.

### 3.5 Constraints and indexes

| Constraint | Where | Effect |
| --- | --- | --- |
| `uk_skill_repository_tenant_active_name (tenant_id, active_name)` | `skill_repository`, where `active_name` is a virtual `IF(active = 1, name, NULL)` column | source names unique per tenant among active rows; a logically deleted row releases its name, so a deleted name can be recreated |
| `uk_skill_repository_builtin_guard` | virtual `builtin_guard` column, 1 only when active and named `builtin-cli-skills` | at most one platform builtin repository row |
| `uk_skill_repo_active_name (repository_id, active_name)` | `skill` | skill names unique per repository among active rows |
| `idx_skill_repository_name (name)` | `skill_repository` | the builtin-repository lookup statement carries no tenant condition while the unique key leads with `tenant_id` and cannot serve it; that lookup happens on every agent spec delivery |
| `idx_agent_skill_binding_skill_id`, `idx_team_skill_binding_skill_id` | the two binding tables | the reverse lookups the delete paths use to clear bindings by skill id |
| `uk_cli_name (name)` | `cli` | package name is the CLI identity |

`SkillInstaller.persist` rejects names longer than `skill.name`'s 100 characters before the write, so the error names the skill instead of the column.

## 4. Skill Source Categories

### 4.1 User repositories

Rows with `source_type` `GIT` / `NPM` / `ZIP`, owned by a tenant, whose content is read from outside by a loader. User sources can be created, changed and deleted, and re-installed repeatedly (ZIP excepted, below).

### 4.2 The platform builtin repository `builtin-cli-skills`

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/constant/BuiltinRepository.kt` declares the reserved name `builtin-cli-skills` and its dedicated `source_type = BUILTIN`. The frontend mirrors the constant in `harnax-webui/src/constants/builtinRepository.ts`.

- it is provisioned with the platform; `url` and `source_config` are empty strings, there is no remote and the registry has no loader for it; `BUILTIN` is deliberately not one of GIT / NPM / ZIP;
- its skill rows are written by the package registration flow (see "cli.skill_id");
- management writes are refused on it: `SkillServiceImpl.requireWritableRepo` and `SkillRepositoryServiceImpl.requireNotBuiltin` refuse to create / modify / delete its skills by name, `SkillSourcePolicy.requireUsableName` refuses the name for any source, and `SkillSourcePolicy.requireRefreshable` refuses to refresh it;
- its skills are visible and deliverable to every tenant: every tenant predicate carries a fixed exemption for the builtin repository row, see `SkillServiceImpl.readable` and `SkillBindingResolver.deliverableWithin`. Without that exemption the skills that arrive via a CLI would be usable only by the tenant they were provisioned under;
- agents and team leads cannot bind its skills directly; those skills arrive with the CLI.

### 4.3 The loader registry and the three source types

`SkillLoaderRegistry` injects every `SkillLoader` implementation and matches on an equal `sourceType`; no match throws `Unsupported skill source type: <type>`. Each loader offers `validateConfig` (surfacing config errors before anything is fetched) and `loadSkills(config, tmpDir)`.

| Loader | sourceType | How it reads | Key constraints |
| --- | --- | --- | --- |
| `GitSkillLoader` | `GIT` | clones into a temporary directory via agentscope's `GitSkillRepository`, then reads skill directories | clone timeout 180s, run on its own thread pool so a slow remote cannot hold the request thread; URL prefix whitelist `https://` `http://` `ssh://` `git://` `git@` (`file://` and `ext::` are refused — they turn a "remote repository" into arbitrary local filesystem access); branch matches `^[A-Za-z0-9._/-]+$`; url ≤ 500 and branch ≤ 100, mirroring the two `skill_repository` columns; the `scheme://user:info@` segment is scrubbed out of error text |
| `NpmSkillLoader` | `NPM` | `npm install <pkg> --prefix <tmp> --ignore-scripts --no-audit --no-fund`, optional `--registry` | package name matches npm rules and is ≤ 214 characters, must not start with `-` (so it cannot be mistaken for a CLI flag) and must not contain `..`; 120s timeout plus killing the whole process tree; output redirected to a file and only its last 400 characters reported (npm puts the real error at the end, and 400 stays under `ApiErrors`' 500-character ceiling so the tail survives intact instead of being cut from the front); `--ignore-scripts` guarantees no lifecycle script of a downloaded package runs inside the admin JVM's process context |
| `ZipSkillLoader` | `ZIP` | extracts `zipPath` into a temporary directory, then reads skill directories | ≤ 5000 entries, ≤ 20 MiB per entry, ≤ 200 MiB total extracted, entry names ≤ 512 characters; a missing `zipPath` key throws `ZIP source config requires 'zipPath'` before anything is extracted, and a `zipPath` whose archive is not on disk throws `ZIP sources are installed once at upload time; upload the archive again to refresh their skills.` |

A ZIP source's archive is deleted once the install request finishes, so `SkillSourcePolicy.requireRefreshable` refuses a refresh or preview of a ZIP source before any loader is even picked — the honest answer is "upload it again", not the loader's internal config error.

### 4.4 SKILL.md parsing and resource loading

`SkillFileParser` (`internal object`) is shared by all three load paths:

- frontmatter is delimited by `---`, and only top-level `key: value` lines are understood (nested structures are skipped); `name` falls back to the directory name; a missing `description` falls back to the first meaningful body line (skipping blank lines, headings, rules and table rows, stripping the quote marker), truncated to 500 characters; a heading-only body yields the skill name as its description — `AgentSkill.builder()` refuses a blank description and such skills would otherwise vanish from the import without a trace;
- resources are read from the skill directory's `resources/` subdirectory into `relative path -> content`; files over 512 KiB, files that push a skill past 4 MiB in total, and anything that fails a strict UTF-8 decode (binary) are skipped with a warning, so the aborted unit is one file rather than the whole skill;
- a failure reason string is truncated to 200 characters, because an exception message can embed whole files.

The display side reads the same boundary: the console's skill detail page and draft review page, and their iOS counterparts, drop the leading frontmatter block before rendering the body (`harnax-webui/src/utils/skillMarkdown.ts`, `harnax-ios/Sources/HarnaxCore/Contract/SkillMarkdown.swift`), because `name` and `description` come from the page header. The stored column keeps the block — loading, scanning and the content digest all work on the document as saved, and the strip happens only at render time.

Whether a directory counts as a skill turns on one thing: does it hold a `SKILL.md`. Directories without that file (`node_modules`, shared assets, a `docs` folder) are not recorded as failures — npm packages and archives routinely hold many directories that were never meant to be skills, and reporting them would bury the handful of real problems.

Directory discovery per loader:

| Loader | Where skill directories are looked for | Notes |
| --- | --- | --- |
| `GIT` | delegated to agentscope's `GitSkillRepository`: the repository's `skills/` subdirectory when it exists, otherwise the repository root | every skill needs its own subdirectory (`skills/<name>/SKILL.md` or `<name>/SKILL.md`); a single-skill repository that keeps `SKILL.md` at the root loads as empty, and `describeEmptyClone` spells that case out while the clone is still on disk |
| `ZIP` | treats a lone top-level extracted directory as the wrapper; prefers a `skills/` folder beneath it (or at the extraction root), then iterates the direct child directories sorted by name | a GitHub archive's `<repo>-<ref>/skills/<name>/SKILL.md` follows the same convention as GIT; when no child directory is a skill, the wrapper directory itself is tried as one skill |
| `NPM` | `installDir/node_modules/<packageName>`, iterating its direct child directories in name order — each child holding a `SKILL.md` counts as one skill; when no child yields one, the package root itself is tried as a skill | this path carries no `skills/` preference — that rule belongs to ZIP and GIT alone — so a package shaped as `skills/<name>/SKILL.md` installs nothing here: the `skills` directory has no `SKILL.md` of its own, counts only as skipped, and its children are never visited. The resolved package directory must still sit inside `installDir` (the name is already validated; this is defence in depth) |

Reading one skill directory fails three ways: no `SKILL.md` (skipped silently), a blank `SKILL.md` (recorded as `SKILL.md is empty`), and a file that is there but cannot be parsed (recorded as `SKILL.md could not be parsed: <reason>`). The latter two go into `failures`, because dropping them would let the request answer 200 with a smaller count. Per-directory attribution on the GIT path happens inside agentscope, so that loader cannot report a directory-level failure; what it can say is why a clean clone yielded nothing.

When a source reads empty and produced no failures, the sentence generated by `describeEmpty` / `describeEmptyClone` goes to the log and back to the caller: a source stored with an unexplained empty report looks like a platform bug to whoever opens it next.

### 4.5 Failure granularity of a load

`SkillLoadResult` carries `skills` and `failures: List<SkillLoadFailure>`, the latter keyed on the directory name — a parse failure means precisely that the frontmatter name is unavailable, so the directory is the only identifier guaranteed to exist. Two reserved names occupy their own fields rather than the skill list:

- `<source>` (`SkillLoadFailure.WHOLE_SOURCE`): the source as a whole could not be read; becomes the response's `sourceError`;
- `<empty>` (`EMPTY_SOURCE`): the source was read to the end and holds no skill; becomes `emptyReason`.

"a broken address" and "the repository or archive is shaped differently than the loader expects" are two problems with two fixes, hence two reserved names. Whole-source problems (a clone that fails, an `npm install` exiting non-zero, an archive that will not open) are still thrown, since nothing exists to attribute them to, and the caller turns them into one error for the operator.

## 5. The Management Plane: Two API Surfaces, One Persistence Path

### 5.1 /api/admin/skill-sources

| Method | Path | Behaviour |
| --- | --- | --- |
| GET | `/page` | paginated list |
| GET | `/active` | enabled sources of this tenant plus the shared builtin one, for pickers |
| GET | `/{id}` | one source |
| POST | `/` | create-and-install: read the source once, `SkillInstaller.createWithSkills` inserts the source row and its skills in one transaction, then `SkillSyncRecorder.record` |
| POST | `/{id}/install` | re-install; no body stores the whole source, a `names` list stores only those |
| GET | `/{id}/fetch` | preview what the source exposes, compared against the names already in the database |
| PUT | `/{id}` | change name, description, version, visibility, `status`, source config; changing the config installs nothing — `/{id}/install` does |
| PUT | `/toggle/{id}` | change `status` |
| DELETE | `/{id}` | cascade delete |
| POST | `/upload` | upload a ZIP and install it; the archive is read exactly once |

In `updateSkillSource`, an `isPublic` change rewrites `is_public` on every skill row under that source, otherwise a public source keeps private skills that never appear in the skill list. `applySourceConfigChange` keeps `url` / `branch` and the JSON config aligned for GIT sources (and validates the merged result); for a ZIP source the config update is ignored, since it keeps no archive.

### 5.2 /api/admin/skill-repositories and /api/admin/skills

`/api/admin/skill-repositories`: `GET /page`, `GET /active`, `GET /{id}`, `POST`, `PUT /update/{id}`, `PUT /toggle/{id}`, `DELETE /{id}`, `GET /fetch/{id}`.

`/api/admin/skills`: `GET /page`, `GET /{id}`, `POST`, `PUT /update/{id}`, `PUT /toggle/{id}`, `DELETE /{id}`, `POST /batch`.

`POST /skills/batch` is the selective sync: `SkillServiceImpl.batchSaveSkillsDetailed` loads the source outside the transaction first (a clone or an `npm install` can take minutes and must not hold row locks), hands persistence to `SkillInstaller.persist(only = selected names)` and finishes with `SkillSyncRecorder.record`. An empty selection is honoured as "store nothing" rather than read as "no selection given" — the latter would install the whole source.

On `PUT /skills/update/{id}`, `status` is routed separately to `SkillMapper.updateStatus`: the `updateById` statement's column list does not include `status`, so setting the field on the entity writes nothing there. Status changes must go through the dedicated statement (`/skill-sources/{id}` handles it the same way).

`SkillController` and `SkillRepositoryController` are the endpoints `harnax-cli/cmd/skill.go` calls (`skillBasePath = /api/admin/skills`, `skillRepoBasePath = /api/admin/skill-repositories`).

### 5.3 Shared write policy

`SkillSourcePolicy` holds the rules that apply to a source regardless of which API entry point reached it; both surfaces call it. A check that lives in only one entry point makes the two answer the same bad input differently.

| Function | Rule |
| --- | --- |
| `requireUsableText` | trims and rejects blank input. `name` is compared against the stored value to decide whether a rename happened and participates in a unique index, so an untrimmed value is a different key holding the same skill |
| `requireUsableName` | as above plus refusal of the reserved name `builtin-cli-skills` |
| `requireStatus` | only 0 or 1. An out-of-range value fits the tinyint, yet every delivery path compares with `== 1`, so it silently reads as "disabled" forever and the UI switch cannot bring the row back |
| `requireRefreshable` | ZIP and BUILTIN sources have nothing to refresh; the ZIP case is answered before a loader is picked |
| `requireContentOnCreate` / `requireContentOnUpdate` | body and description must not be blank; on update a field the caller left out keeps whatever the row already holds, so the status-only edit that disables an already-broken skill stays possible. Validation never trims — the body is stored as the operator wrote it |
| `requireValidResources` | a non-blank `resources` must parse as `Map<String, String>`; blank means "no bundled files" |
| `normalizeSelection` | trims entries, drops blanks (a failure line naming an empty string tells the caller nothing), deduplicates, ceiling of `MAX_SKILLS_PER_REQUEST = 1000` (both selective-install endpoints take an unbounded JSON list and report every name the source does not hold, so one request could otherwise write tens of thousands of failure entries into a stored column that every source-list read parses and ships to the browser); `null` and an empty list mean different things — the whole source versus "store nothing" |

`SkillSourceConfigs` owns `source_config`: `parse` reads the JSON and falls back to the `url` / `branch` columns with a warning for GIT sources only, while a non-GIT source throws instead (pretending otherwise makes an NPM repository fail later with "requires 'packageName'" and hides the corrupt config); `forApi` is a pure projection — server-internal keys such as `zipPath` never leave the server and nothing is synthesized from `url` / `branch`, because both response DTOs expose those columns top-level and inventing a Git-shaped map would only make GIT and NPM behave differently for the same blank input. `normalized` trims every text value before it is stored.

### 5.4 SkillInstaller persistence rules

`SkillInstaller.persist` is the single write path shared by both entry points; keeping it in its own bean also keeps the transaction short, since all source reading happens before it is called.

- names are trimmed before being stored; a blank name, a name over 100 characters, a blank `SKILL.md` body and a name the source declares twice each produce one `failed` entry. The duplicate check uses an in-run `seen` set: without it the second occurrence finds the row the first one just wrote inside the same transaction, is counted as an update on top of that insert, and the report claims two stored skills where the repository holds one;
- an existing row (`selectByNameAndRepo`) gets its description, body, resources JSON, `version` and `is_public` updated and counts as `updated`; otherwise a row is inserted with `tenant_id`, `is_public` and `creator` inherited from the source row, `version` from the source, `active = 1`, and counts as `installed`;
- with `only` set, candidates are indexed by trimmed name with `putIfAbsent` (first occurrence wins, matching a full import) and taken in selection order, so the report reads like the selection dialog; a selected name the source does not yield answers `Not present in the source anymore` unless it was already reported as a parse failure or the run never reached the source;
- a skill with any content-scan finding is stored with `status = 0` (through the insert's initial status, or one dedicated `updateStatus` on re-import) and reported under `flagged`; a re-import runs the gate again;
- `flagged` is recorded only once the row exists, otherwise the same name lands in both `flagged` and `failed` whenever persistence throws, and the report claims a skill was stored disabled when nothing was stored at all;
- a persistence exception on one skill never disappears silently: the reason passes through `ApiErrors.message` and rides inside a successful HTTP response as that skill's `failed` entry;
- load-stage failures are folded into the same `failed` list before the selection is resolved (a run that stored three of five requested skills must not answer as a plain success), and the two source-level reserved names leave the list for their own fields, so "the archive holds no skill" does not read as "1 of 1 skills failed to store";
- `stale` (names the database still holds that the source does not yield) is computed only for a full-source install that reached the source: subtract the names stored by this run and the names still present or reported failed, and what the repository holds is stale. Nothing is deleted here — a skill the source dropped may still be bound, and deleting it would take the bindings with it. Reporting it is the whole of the job;
- `createWithSkills` re-runs the source-name lookup inside the transaction; the real guard remains `uk_skill_repository_tenant_active_name`, and the re-check exists only to produce a readable error for the common race.

`deleteWithSkills(repository)`: requires zero skills with `status = 1` under the source ("N skills are still enabled, so this source cannot be deleted"), then requires no rows in either binding table for those skills (counted directly rather than inferred from status, because the exception below can disable a bound skill), then deletes bindings, deletes skill rows one by one and deletes the source row, all in one transaction.

### 5.5 Sync recording

`SkillSyncRecorder.record` writes one report onto its source row (`last_sync_time` / `last_sync_status` / `last_sync_detail`) and mirrors it onto the caller's object — callers answer with a response mapped from that same object, so a source the server has just marked `FAILED` must not come back as "never synced". All four write paths end here, which keeps the rule about what counts as a failed sync single. Status precedence:

| Status | Condition |
| --- | --- |
| `FAILED` | the report carries a `sourceError`. Checked before the counters: a fetch that produced nothing must not read as an empty source — a different problem with a different fix |
| `EMPTY` | `savedCount == 0`, whether the source held no skill or every candidate failed on the way in. `PARTIAL` claims a part, and a part of zero is zero |
| `PARTIAL` | something was stored and something also `failed` |
| `SUCCESS` | otherwise |

`last_sync_detail` has fixed keys: `saved`, `installed`, `updated`, `failed` (name + reason each), `flagged` (name + reasons each), `stale`, and one optional `error` (`sourceError` wins over `emptyReason` — the badge already says whether the source broke or simply holds nothing, and the UI shows a single sentence under it either way). `SkillSyncRecorder.forApi` parses the stored report for responses and answers `null` when it cannot, so the source list renders its badge instead of failing on one unreadable row.

### 5.6 Content scanning

`SkillContentScanner.scan(skillmd, resources)` scans the body plus every non-blank resource file. A hit does not reject the import — a documentation skill may legitimately quote a dangerous command — it downgrades the skill to `status = 0` for a human to enable explicitly. Nine rules:

| ruleId | Detects |
| --- | --- |
| `recursive-root-delete` | recursive deletion of a root-level path |
| `windows-drive-wipe` | wiping a Windows drive (`del/erase /s /f /q X:\`, `format X:`) |
| `disk-overwrite` | overwriting a block device or filesystem (`dd ... of=/dev/`, `mkfs /dev/`) |
| `remote-pipe-to-shell` | piping a remote payload straight into a shell |
| `reverse-shell` | opening a reverse shell (`/dev/tcp/`, `nc -e`, `bash -i >& /dev/`) |
| `fork-bomb` | a fork bomb |
| `permission-escalation-of-root` | recursively opening permissions on a system path (`chmod -R 777 /`) |
| `history-and-audit-tampering` | erasing shell history or audit logs |
| `credential-harvest-upload` | exfiltrating local credentials to a remote endpoint (`curl -d/-F/-T ...` hitting `credentials`, `.ssh/`, `.aws/`, `.netrc`, `id_rsa`) |

Command boundaries come from `(?:^|[^\w-])` and `(?:[^\w]|$)`: skills quote shell snippets inside Markdown inline code, fenced blocks and plain sentences, so matching only on shell metacharacters misses most of them — any character that cannot be part of a command name also starts or ends one. A trailing `-` is excluded on purpose so hyphenated names such as `x-rm` are not read as a bare `rm`. Each finding records `resource`, `ruleId`, `reason` and a 20/120-character excerpt around the match.

The scanner exists because the bundled `resources/` files land in the agent's workspace where shell and file tools can execute them: an imported package is effectively untrusted code.

### 5.7 Disable and delete preconditions

`SkillServiceImpl.requireUnbound(skill, action)` applies on both the disable paths (`toggleSkillStatus` to 0, and a `status` change inside `updateSkill`) and the delete path, and refuses while any of these is non-zero:

| Holder | Read used | Refusal |
| --- | --- | --- |
| agents | `AgentSkillBindingMapper.selectAgentBindingCounts` | `Skill '<name>' is bound to N agents, so it cannot be disabled/deleted` |
| team leads | `TeamSkillBindingMapper.selectTeamBindingCounts` | singular reads `a team lead` |
| CLI packages | `CliMapper.selectBySkillIds` | `Skill '<name>' ships with CLI package(s) ..., so it cannot be ... from here` |

Those counts come from the same grouped reads that feed the skill list response (`SkillServiceImpl.boundAgentCounts` / `boundTeamCounts` into `SkillResponse.boundAgentCount` / `boundTeamCount`), so the number next to a switch and the guard behind it cannot drift. A skill bound only to a lead shows `boundAgentCount` 0 and `boundTeamCount` 1 and is still refused — both figures are on the page.

The CLI-package rule is about ownership: `cli.skill_id` is how the package owns that row, the registrar rewrites or removes it with the package, and the skill page is not its switch.

A delete has stricter preconditions than a disable, because it cascades through the bindings; `deleteSkill` still calls `deleteBySkillIds` explicitly after `requireUnbound`, so no dangling binding row survives the delete.

### 5.8 Manual create and update of a skill row

`SkillServiceImpl.createSkill`: `requireUsableText` on the name → `repositoryId` required → `status` defaults to 1 and passes `requireStatus` → content and resources validation → authorisation (`requireWritableRepo`) before the duplicate-name lookup (establishing whether a name is taken must not be possible for a repository the caller may not write to) → same-repository duplicate refused → row inserted with `isPublic` from the repository, `tenantId` from `TenantResolver.resolve` and `creator` from the current username.

`updateSkill`: load by id and `requireReadable`, then `requireWritableRepo(skill.repositoryId)` (skills of the builtin repository are read-only, and skills cannot be moved in or out of it), and if the request names a new repository that one must be writable too; the final name and repository are resolved before the uniqueness check, so a rename-preserving move into a repository that already holds that name answers "Skill name already exists" instead of a raw SQL error from the unique index; `isPublic` follows the destination repository; description, body and resources update selectively.

## 6. Visibility and Tenant Semantics

### 6.1 The list predicate

`SkillMapper.selectSkillList` (`harnax-entity/src/main/resources/mapper/SkillMapper.xml`) is:

```
active = 1
AND (is_public = 1 OR creator = #{currentUsername})
AND (tenant_id = #{tenantId} OR repository_id = #{builtinRepositoryId})
```

plus optional `name` (LIKE), `repository_id` and `status` filters, ordered by `update_time DESC`. When `tenantId` is null the whole tenant clause is absent (an internal/system call); the builtin exemption clause is likewise absent when `builtinRepositoryId` is null. Page parameters are clamped to `pageNum >= 1` and `pageSize ∈ [1, 1000]`.

### 6.2 Read by visibility, answer as absent when not visible

`SkillServiceImpl.getSkill(id)` is `skillMapper.selectById(id)?.takeIf { readable(it) }`. A row that exists but the caller may not see returns null — the same answer as an unknown id. The reason: that row carries the full `SKILL.md` and every bundled resource, so reading it across tenants would leak another tenant's skill content, while refusing out loud would confirm that the skill exists and whose it is, and the controller would turn that into a 500.

The write paths need to say why they refused, so they use `requireReadable`, which throws a `BizException` carrying the `error.skill.no_permission` message.

### 6.3 Three tenant predicates

| Predicate | Implementation | Use |
| --- | --- | --- |
| visibility | `SkillServiceImpl.readable` / `requireWritableRepo` read `TenantContext` directly, where null means "an internal call with no tenant to gate by" → allowed | resolving that null to a concrete tenant would turn an internal call into a membership check against the default workspace |
| a definite tenant id | `TenantResolver.resolve(jwtUtil)`: a request without `X-Tenant-ID` is read as the caller's own tenant rather than as tenant 1 | the `tenant_id` stamped on a new skill row, the tenant basis of binding saves |
| the delivery basis | `buildAgentSpecResponse(agentTenantId)`, taken from the agent or team row, never from the request | `SkillBindingResolver.deliverable(skillIds, tenantId)` |

`SkillBindingResolver.deliverableWithin`'s predicate is `it.tenantId == tenantId || it.repositoryId == builtinRepositoryId`. Its basis: `SkillMapper.selectByIds` carries no tenant condition and an internal delivery call carries no trustworthy tenant header, so the holder's own tenant is the only comparable basis; without it a cross-tenant binding row written before the save-time check existed hands over another tenant's `SKILL.md` and every bundled resource. The builtin repository is a single platform-wide row and stays exempt.

`SkillBindingResolver.resolveBindable` looks up the builtin repository without a tenant filter on purpose: a tenant-scoped lookup misses that platform-wide row and silently drops the "no direct binding of builtin skills" constraint.

### 6.4 Visibility follows the repository

`skill.is_public` comes from the source, in four places: `SkillServiceImpl.createSkill` takes the repository's value; `updateSkill` takes the destination repository's value when a skill moves; `SkillInstaller.persist` takes the source row's value on both insert and update; `SkillSourceServiceImpl.updateSkillSource` propagates an `isPublic` change to every skill row under that source. Not following it produces a visibility mismatch in both directions: a private skill inside a public repository never appears in the list, and a public source keeps private skills that stay invisible.

## 7. Bindings

### 7.1 An agent's skills

`AgentServiceImpl.saveSkillBindings(agentId, skillList)` runs on agent create and update: `deleteByAgentId` clears the set → the comma-separated id string is parsed into `Long`s (unparseable entries dropped) → `SkillBindingResolver.resolveBindable` → `batchInsert`. A binding row carries nothing beyond the pair of ids and the two timestamps.

`AgentServiceImpl`'s detail assembly reads the bindings back from the binding table into `AgentResponse.SkillItem` (skillId / skillName / skillDescription / repositoryId / repositoryName), one `skillService.getSkill` per binding, skipping any row that returns null.

### 7.2 A team lead's skills

`TeamServiceImpl` passes `request.skillIds` to the same `resolveBindable` on create and update, writing `team_skill_binding`. The lead configuration page reads them through `TeamServiceImpl.skillsOf`, which calls `deliverable(...)` — the same tenant predicate as binding saves and as delivery, so the page describes only what the runtime will actually load.

### 7.3 How a package's skill arrives

A package skill's visibility comes from `cli.skill_id`: `AgentServiceImpl`'s detail assembly fills each `AgentResponse.CliItem`'s `skillList` with that one shipped skill, so the configuration panel reads "this CLI and what it teaches the agent" as one thing rather than two unrelated rows that happen to share a name. The skill never appears in the skill picker, because `resolveBindable` refuses builtin-repository skills.

### 7.4 Save-time constraints

`SkillBindingResolver.resolveBindable(skillIds)` applies the same checks to both holders, after `distinct()` on the ids:

1. every id must resolve to an active skill visible to the current tenant (the builtin repository row exempt, the same rule as `SkillServiceImpl.readable`); otherwise the whole request is refused naming the missing ids — `Skill is missing, deleted, or outside your tenant: ...`;
2. a skill with `status != 1` cannot be bound — `Skill is disabled, enable it before binding: ...`;
3. a skill from `builtin-cli-skills` cannot be bound directly — `Skills from 'builtin-cli-skills' cannot be bound directly (auto-loaded via CLI): ...`;
4. two skills sharing a name cannot be bound together — `Skills bound together must have distinct names, duplicated: ...`, because the harness keys skills by name.

All four fire at save time, while the configuration panel is still open and the row is still fixable. On the runtime side a dropped skill leaves nothing but a log line, which is how an operator loses a skill without noticing.

## 8. The Admin-to-agent-service Delivery Contract

### 8.1 Endpoints and payload

`InternalApiController` (`@RequestMapping("/api/admin/internal")`) serves `GET /agent-spec/{sessionId}` and `GET /team-spec/{sessionId}`, returning `AgentSpecInfoResponse` (`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/AgentSpecInfoResponse.kt`). The skill parts are two fields:

- `skillDetails: List<SkillDetailDto>` — the skills the agent or lead bound itself;
- `cliDetails[].skill: SkillDetailDto?` — the skill each selected CLI package ships.

`SkillDetailDto` is a six-tuple — `id`, `name`, `description`, `skillmd`, `resources`, `version` — mapped column by column by `InternalApiController.skillDetail(skill)`. `tenant_id`, `creator`, `is_public`, `status` and `active` do not cross the boundary: they are consumed by the delivery decision. The response's `tenantId` is the holder row's own tenant, which the runtime stamps onto `token_stats` / `tool_invocation_log` / `process_log`.

### 8.2 Resolution

`buildAgentSpecResponse` is the single construction point and `skillIds` is one of its parameters: an ordinary agent's default reads `AgentSkillBindingMapper.selectByAgentId`, while `specForTeam` passes `TeamSkillBindingMapper.selectByTeamId` together with empty `toolBindings` / `mcpBindings` / `cliBindings` / `requiredToolIds` — a lead has its own prompt, model and tenant and may have skills, and resolves no other capability configuration because it has no shell and no sandbox to run a tool in.

Skill resolution: `skillIdsToDeliver = skillIds.distinct()` → one `skillBindingResolver.deliverable(skillIdsToDeliver, agentTenantId)` for all resolvable rows, indexed by id → `mapNotNull` in request order into `skillDetails`. An empty id list returns before the resolver (an empty `selectByIds` renders `IN ()`, which MySQL rejects), and `deliverable` also checks emptiness before paying for a builtin-repository lookup.

### 8.3 What absence and disablement answer

| Case | Answer | Log |
| --- | --- | --- |
| not resolvable (row missing, `active = 0`, outside the tenant) | absent from `skillDetails` | WARN naming the skillId and the agent's tenant |
| `status == 0` | absent from `skillDetails` | INFO |
| normal | one `SkillDetailDto` | — |

A package's own skill goes through the same resolver on the same tenant basis, with two extra gates: `cli.status == 0` drops the whole CLI item, and a `cli.skill_id` row that is not resolvable (`CLI '{}' (id={}) points at skill {} which is gone or outside agent tenant {}`) or is itself disabled yields `skill = null` while the CLI item is still delivered. The package registrar keeps that row in step with `cli.status`, so `skill == null` only remains possible when the skill row was switched off on its own or removed.

### 8.4 Convergence of one delivery

`AgentSpecResolver.withCliSkills` (agent-service) merges `cliDetails[].skill` into the skill list:

- deduplicated by id first, so one skill cannot appear twice through two paths;
- then deduplicated by name, with the injected side yielding: when an explicitly bound skill already uses a name, the package skill is not injected and the operator's binding wins, with one INFO line naming the shadowed names;
- injected items are placed at the front (`injectedSkills + specInfo.skillDetails`).

The step is necessary because skill names are unique only inside a repository (`uk_skill_repo_active_name`) and can collide across repositories, while the harness keys skills by name (`AgentSkill.getSkillId()` is `name + "_" + source`): delivering both copies would let the registry and the in-memory repository disagree about which one is live.

A CLI whose `skill` is null produces one WARN naming the session and the CLIs: either its package registered no skill, or that row was deleted since.

`buildAgentSpec` then turns each `skillDetails` entry into `SkillSpec(skillId, skillName)` inside `AgentSpec.skills`.

## 9. Runtime Assembly

### 9.1 From spec to AgentSkill

`HarnessAgentLauncher` iterates `agentSpec.skills` and calls `skillAdaptor.getSkill(skillId)` for each id: a non-null result goes to `agentBuilder.addSkill(skill)`; a null one produces one WARN and the loop continues — one unusable skill must not cost the agent every other capability it has. A lead goes through the same loop: skills are part of the team's own configuration and the lead does load them.

`SkillAdaptorImpl` (agent-service) looks skills up only in the `skillDetails` currently held by `AgentSpecContextHolder`, by id:

| Case | Behaviour |
| --- | --- |
| `skillId <= 0` | WARN + null |
| not in the delivered list | WARN (`Skill N is not in the delivered spec, so it cannot be loaded here`), null |
| blank `resources` | loaded with no files |
| unparseable `resources` | loaded with no files, WARN naming the skill and id |
| `AgentSkill.builder()` throws (blank name / description / body) | ERROR naming it as "delivered but cannot be loaded", null. Distinct from the row above: that one is a skill that is not there, this one is a row that exists and was delivered with non-conforming content. Merging them sends the operator looking for a deletion that never happened |

An `AgentSkill` is built from `name`, `skillContent = skillmd`, `description` and `resources` (`Map<String, String>`). This class does not query the skill table: Admin decides what a session may load — deleted, disabled, or simply outside the caller's reach all come out as an absence from the delivered list — and `SkillMapper.selectById` filters only `active`, so reading the database here would hand back content Admin had already withheld, including another tenant's.

### 9.2 The in-memory skill repository

`HarnessAgentBuilder`:

- `addSkill` deduplicates by name with last-write-wins and logs a WARN saying the later binding was kept. The binding table is read `ORDER BY id`, so "the binding registered last" must be a determinate answer, otherwise the configuration panel lists one skill and the repository resolves another;
- `build()` calls `builder.disableDefaultWorkspaceSkills()` first: the harness would otherwise read a `skills/` directory out of the container workspace and merge it in, and disabling that means an agent cannot override an operator-configured skill by writing a same-named `SKILL.md` into its own sandbox. `enableSkillManageTool` is never called and no skill directory is provisioned at runtime — Admin is the only skill source;
- skills are registered through `InMemorySkillRepository(skills.toList())` (only when non-empty), whose `getSource()` returns the constant `IN_MEMORY_SKILL_SOURCE = "in-memory"` and whose `getRepositoryInfo()` returns `(in-memory, "memory", false)`; that repository's `save` / `delete` both return false and `getSkill(name)` returns the first name match.

### 9.3 Reading the delivered set back out

`HarnessAgentWrapper.deliveredSkills()` takes `harnessAgent.skillRepositories`, keeps only those whose `source == IN_MEMORY_SKILL_SOURCE`, and merges their skills by name (last one winning). That is the projection's only skill source: the framework merges its own workspace repository on top of these, so filtering by source is what keeps the set to what was authorised. The merge rule matches how the harness itself resolves a name clash between two repositories, so the projection cannot disagree with the prompt about which skill a name means.

An exception while reading the repositories is caught and yields an empty list — the same posture as the projection.

## 10. Projecting Skill Files into the Sandbox

### 10.1 Who projects, and when

`HarnessAgentWrapper` holds a lazily created `SandboxSkillProjector(sandboxWorkspaceRoot)` (configuration key `harness.sandbox.workspace-root`, `/workspace` by default); a wrapper that never gets a keep-alive sandbox therefore never constructs it.

Inside `buildRuntimeContext()`, after `KeepAliveSandboxManager.getOrCreate(sessionId, WorkspaceSpec(), ...)` returns the session's container handle and before the agent runs, it calls `projectSkills(sandbox)`. Both the blocking and the streaming path go through `buildRuntimeContext`, so every turn on a reused container runs one projection; the `WorkspaceSpec()` above is empty, so nothing else would put a file in it.

`projectSkills` first takes `deliveredSkills()`: when empty it returns immediately — an agent with no skills leaves the container completely untouched, not even given a skills directory; otherwise it calls `skillProjector.project(sandbox, skills)`. An outer catch covers construction of the projector itself (`project` swallows everything that happens while writing). All three layers hold the same posture.

### 10.2 Layout

`SandboxSkillProjector.skillsRoot = workspaceRoot.trimEnd('/') + "/skills"`, one directory per skill, named after `AgentSkill.name`:

```
/workspace/skills/<skillName>/SKILL.md      <- AgentSkill.skillContent (admin's skillmd column)
/workspace/skills/<skillName>/<relative>    <- each AgentSkill.resources entry
/workspace/skills/.harnax-skills.json       <- the manifest
```

The skills root is an absolute path inside the container, the same root `Sandbox.exec` resolves against, the same one the output-file detector scans as `/workspace/output` and the team orchestrator writes artifacts under. It is also the prefix agentscope's own `ShellPathPolicy` renders into a skill's `<files-root>`, so a skill that says "run `scripts/run.sh`" lands on the path the prompt gives the model. The skill file name matches how agentscope names workspace skills (`SKILL_FILE`).

### 10.3 Manifest and per-turn convergence

The manifest is a JSON object mapping each path relative to the skills root to the SHA-256 of its bytes (`sha256:` prefix plus hex), computed here because no content hash exists anywhere upstream. A keep-alive container is reused across turns, so this cannot be write-only. `converge` runs each turn:

1. read the manifest; a missing file (the session's first turn, or a container that has never been projected) or an unreadable one is treated as empty — a manifest that cannot be read says nothing about what is on disk, so the turn rewrites every file it owns;
2. `stale` = paths the manifest lists that the current desired set does not; `pending` = desired paths whose digest differs from the manifest;
3. when both the desired set and the manifest are empty, return `ProjectionResult.EMPTY` and leave the container untouched; when nothing is pending and nothing is stale, return `unchangedFiles = desired.size` and issue no write at all;
4. deletions come before writes: a skill that is absent from the current desired set has its directory deleted recursively, a renamed or dropped resource has its single file deleted. Only paths the manifest knows about are ever removed — anything else under the skills root, such as a file the agent created there, is left alone;
5. write `pending`, then assemble the new manifest from the retained entries plus this turn's writes;
6. write the manifest last; when the new manifest is empty, the manifest file is deleted.

Trust is placed in the manifest rather than in a stat of each file: an agent that deletes its own skill files mid-session gets them back when its container is rebuilt, not on the next turn.

`ProjectionResult` reports what changed, for logging and for tests that assert a converged turn did nothing: `written` (relative paths (re)written), `deleted` (stale paths removed, a whole skill directory appearing as `name/`), `rejected` (items refused instead of written, with the reason), `unchangedFiles` (files already on disk exactly as delivered), and `changed` (`written` or `deleted` non-empty).

### 10.4 What is projected and what is refused

`desiredFiles` screens every candidate before anything is written; refusals collect into `rejected` and are logged as one WARN naming the skills and the reason, because the operator's next question is "why can't the agent run its script":

| Refused when | Reason |
| --- | --- |
| the skill name is not one safe path segment | `SandboxFileWriter.safeRelativePath(name)` returns null or contains `/`; one directory per skill, so the name must be a single segment |
| `skillContent` is blank | nothing to write as `SKILL.md` (`Skill '<name>' (no SKILL.md content was delivered)`) |
| a resource key starts with `/`, or fails `safeRelativePath` | absolute path, a `..` that climbs out of the skill directory, or blank |
| a resource key resolves to `SKILL.md` | it would replace the main file |
| a resource value is null | no content to write |

A resources key is admin-authored, but it names a file this process is about to create inside a container, so it is treated as untrusted input: it must be relative and must not climb out of the skill's directory.

### 10.5 Failure semantics

`project()` never throws. The whole call is wrapped: a projection error logs one WARN (naming the skills root, the skill names and the reason) and the turn continues with a skill the model can read the text of but not run. Skill files are an enhancement to a turn Admin already authorised, and a container hiccup — `mkdir` refused, disk full, the exec timeout elapsing — must not turn it into a failed reply. The manifest is rewritten only once every file of this turn is in place, so the work that failed repeats next turn and there is no half-committed state where the manifest records a file that was never written.

## 11. Boundaries and What Is Deliberately Not Done

| Boundary | Where |
| --- | --- |
| No per-skill environment channel; neither binding table has such a column | `AgentSkillBinding`, `TeamSkillBinding`, `AgentServiceImpl.saveSkillBindings` |
| Skill content is never stored on disk; neither `skill` nor `skill_repository` has a content path column | the two entities' column lists, `SkillMapper.xml` |
| The runtime does not write skills: the in-memory repository's `save` / `delete` return false, default workspace skills are disabled, no skill-management tool exists | `HarnessAgentBuilder` |
| The runtime never re-queries the skill table; absent from the delivery means absent from the container | `SkillAdaptorImpl`, `SandboxSkillProjector`, `HarnessAgentWrapper.deliveredSkills` |
| The builtin repository's skills cannot be created / modified / deleted / refreshed, nor bound directly | `SkillSourcePolicy`, `SkillServiceImpl.requireWritableRepo`, `SkillRepositoryServiceImpl.requireNotBuiltin`, `SkillBindingResolver` |
| A ZIP source is installed once, keeps no archive, and a config update to it does nothing | `SkillSourcePolicy.requireRefreshable`, `SkillSourceServiceImpl.applySourceConfigChange` |
| Skills the source dropped are reported, never deleted | `SkillInstaller.staleNames` |
| Content scanning does not reject an import, it downgrades to disabled | `SkillContentScanner`, `SkillInstaller.persist` |
| A skill held by an agent, a team lead or a CLI package cannot be disabled or deleted | `SkillServiceImpl.requireUnbound`, `SkillInstaller.deleteWithSkills` |
| Nothing but the package registration flow writes the `cli` table | `CliPackageAutoRegistrar` |
| The projection never deletes paths it did not write, and creates no skills root for an agent with no skills | `SandboxSkillProjector.deleteStale`, `HarnessAgentWrapper.projectSkills` |
| A projection failure does not affect the turn's reply | `SandboxSkillProjector.project`, `HarnessAgentWrapper.projectSkills` |
| At most 1000 skill names per request | `SkillSourcePolicy.MAX_SKILLS_PER_REQUEST` |
| One skill failing to load does not affect the agent's other capabilities | the skill loop in `HarnessAgentLauncher` |

## 12. Key File Index

Management plane (harnax-admin)

- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillRepositoryController.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillServiceImpl.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillRepositoryServiceImpl.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillSourceServiceImpl.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillBindingResolver.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillInstaller.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillSourcePolicy.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillSourceConfigs.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillSyncRecorder.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillContentScanner.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillLoader.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillLoaderRegistry.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillLoadResult.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillFileParser.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/GitSkillLoader.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/NpmSkillLoader.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/ZipSkillLoader.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrar.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/constant/BuiltinRepository.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillCreateRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUpdateRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillInstallResponse.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillResponse.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceCreateRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceUpdateRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceInstallRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillSourceInstallResponse.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillRepositoryCreateRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillRepositoryUpdateRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillRepositoryResponse.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SyncSkillResponse.kt`

Entities and persistence

- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/SkillRepository.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentSkillBinding.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamSkillBinding.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/SkillDetailDto.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/AgentSpecInfoResponse.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/SkillAgentBindingCount.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/SkillTeamBindingCount.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/SkillRepositoryEnabledCount.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/SkillMapper.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/SkillRepositoryMapper.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentSkillBindingMapper.kt`
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/TeamSkillBindingMapper.kt`
- `harnax-entity/src/main/resources/mapper/SkillMapper.xml`
- `harnax-entity/src/main/resources/mapper/SkillRepositoryMapper.xml`
- `harnax-entity/src/main/resources/mapper/AgentSkillBindingMapper.xml`
- `harnax-entity/src/main/resources/mapper/TeamSkillBindingMapper.xml`
- `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`

Runtime (harnax-agent)

- `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt`
- `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillAdaptorImpl.kt`
- `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AgentSpecContextHolder.kt`
- `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/SkillAdaptor.kt`
- `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt`
- `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`
- `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt`
- `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`
- `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SandboxSkillProjector.kt`
- `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/SandboxFileWriter.kt`

Interfaces

- `harnax-webui/src/pages/skill/index.tsx`
- `harnax-webui/src/pages/skill/detail.tsx`
- `harnax-webui/src/pages/skill/components/SkillList.tsx`
- `harnax-webui/src/pages/skill/components/RepositoryList.tsx`
- `harnax-webui/src/pages/skill/components/RepositoryForm.tsx`
- `harnax-webui/src/pages/skill/components/SyncSkillModal.tsx`
- `harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx`
- `harnax-webui/src/services/ant-design-pro/skill.ts`
- `harnax-webui/src/services/ant-design-pro/skillSource.ts`
- `harnax-webui/src/constants/builtinRepository.ts`
- `harnax-cli/cmd/skill.go`
- `harnax-cli/SKILL.md`

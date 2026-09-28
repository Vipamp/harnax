# Harnax CLI Plugin Package Design

For platform implementers and operators: the code, tables and storage a CLI plugin package passes through between landing on the shelf directory and appearing inside a container, plus day-to-day operations.

How this document pairs with "Harnax CLI Plugin Package Specification": that one is read by package authors and covers the shape of a package and its verbatim validation contract. This one is read by the platform side and covers the registration path, how a package reaches an image and a container, the split of duties between the two digests, the pages and API, and reclamation and incident handling. Each is self-contained; neither depends on reading the other.

## 1. The model in one sentence

A CLI is a package-registered asset, not a record typed into a form. One `cli` row means "one passing `.harnaxcli.zip` is in the shelf directory", and outside two write paths (the registration upsert and the operator's enable/disable switch) nothing writes the table.

One chain turns a package into files inside a container: admin registers it at startup and uploads the archive → agent-service receives the two digests and the object key while resolving the agent spec → `CliPackageStore` fetches by `packageDigest`, verifies, and unpacks the payload tree → `CliImageBuilder` `COPY`s that tree onto the container root and hashes the CLI set into an image tag → `runtimeEnv` and the agent-level bindings are injected when the container is created.

Keeping the two digests apart is the pivot of the whole design: `packageDigest` answers "is this the same byte sequence", `payloadDigest` answers "must the image be rebuilt".

## 2. Data shape

### 2.1 The `cli` table

| Column | Source | Writer |
| --- | --- | --- |
| `id` | auto-increment | registration; a new version of a same-named package reuses the same id |
| `name` | `plugin.yaml`'s `name` | registration; `uk_cli_name` is where same-name convergence happens |
| `description` | `plugin.yaml`'s `description` | registration, overwritten every pass |
| `version` | `plugin.yaml`'s `version` | registration, overwritten every pass |
| `check_command` | `plugin.yaml`'s `checkCommand` | registration, overwritten every pass |
| `skill_id` | the skill row registered from the package's `skill/SKILL.md` | registration; `cli` points at its skill directly, not through a binding table |
| `package_digest` | sha256 of the whole package | registration; part of the object key and the "must we re-upload" test |
| `payload_digest` | canonical sha256 of payload plus apt | registration; carries the image semantics |
| `package_object` | `<name>/<packageDigest>.harnaxcli.zip` | registration |
| `deps_apt` | JSON array | registration; an empty list writes `NULL` |
| `runtime_env` | JSON object | registration; an empty map writes `NULL` |
| `env_params` | JSON array, secret values encrypted | registration |
| `status` | none | written only by the switch; the registration upsert does not name this column |
| `active` | none | registration writes 1; the prune path hard-deletes rather than soft-deleting |
| `create_time` / `update_time` | none | `NOW()` on the SQL side |

The table has no `tenant_id`, `creator`, `is_public` or `install_script` column. The column set is what the one `CREATE TABLE` for `cli` in the baseline `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` writes in a single statement: the 16 columns the table above lists, and besides the auto-increment primary key the only key on the table is `uk_cli_name (name)` — there is no other index. The three visibility columns are absent because a published package is a platform asset with no visibility to express, and an install script has no counterpart — the payload plus a declarative `deps.apt` list are what the package carries. The only associations a `cli` row participates in are the `agent_cli_binding` rows naming it and the one skill row reached through `cli.skill_id` — that skill carries the package's instruction sheet and its assets.

### 2.2 `agent_cli_binding`

One row = one agent selected one CLI, carrying `env_bindings` (that agent's snapshot of values typed for this CLI). The table's only keys are its auto-increment primary key and `uk_agent_cli_binding_agent_id_cli_id (agent_id, cli_id)`: an `(agent_id, cli_id)` pair exists at most once, and a lookup by `agent_id` rides on that key's leftmost prefix, which is why no separate `agent_id` index is on the table. Bindings point at a CLI, not at a version, so a package upgrade does not reset them.

The save path rewrites the whole set. Delivery maps one row per binding into the spec, so a duplicate would ship the same CLI twice and compute the image material per copy — the unique key stops that in the database.

The prune path deletes the binding rows of a taken-off CLI (`deleteByCliIds`).

### 2.3 The skill row a package registers

`skill/SKILL.md` lands in the `skill` table under the CLI's own name, in the managed repository `BuiltinRepository.CLI_SKILLS`, with `creator` recorded as `SYSTEM`, `is_public` recorded as public and `active = 1`. The skill row's `status` follows the CLI's `status` (forced to disabled when the content scan hits). On take-off it is set to `active = 0`, following the convention every skill delete uses, and the agent and team bindings on it are deleted; re-registering produces a freshly inserted skill row.

### 2.4 Constraints at the SQL level

`CliMapper.upsertCliPackage` is an `INSERT ... ON DUPLICATE KEY UPDATE` whose update list covers every manifest-owned column plus `active = 1`, and pointedly never mentions `status`. `selectByName` carries no `active` condition (the registrar must recognise a row it wrote and then disabled), and "a package name lookup never lands on a soft-deleted row" is now held by the schema and the write paths together, with no out-of-band device: `uk_cli_name` caps a package name at one row, so that `LIMIT 1` does not pick a winner among several; a `cli` row has exactly two write paths, the registration upsert, which always writes `active = 1`, and the switch's `updateStatus`, which writes only `status`, and neither has a branch that soft-deletes; take-off goes through `deleteByIds`, a hard delete, because an `active = 0` row would keep holding the name under `uk_cli_name` and leave a re-added package nowhere to write.

`selectByNameForUpdate` is the identical WHERE clause of `selectByName` plus `FOR UPDATE`. The registrar reads `status` through it inside the transaction, so the switch's `updateStatus` has to wait for that transaction to commit and the two rows (`cli` and its `skill`) cannot disagree.

## 3. The registration path

### 3.1 Trigger

`CliPackageAutoRegistrar.syncCliPackages()` listens for `ApplicationReadyEvent` and runs one pass per startup; there is no timer. Three configuration keys:

| Key | Default | Role |
| --- | --- | --- |
| `harnax.cli.package-dir` | env `HARNAX_CLI_PACKAGE_DIR`; `/home/harnax/cli-packages` inside the admin image; the code default is an empty string | the shelf directory. An empty string skips the pass with one INFO line |
| `minio.cli-package-bucket` | `harnax-cli-packages` (env `MINIO_CLI_PACKAGE_BUCKET`) | the archive bucket; agent-service's `harness.minio.cli-package-bucket` must hold the same value |
| `harnax.cli.archive-retention-days` | 7 (env `HARNAX_CLI_ARCHIVE_RETENTION_DAYS`) | how long an unreferenced archive is kept |

### 3.2 Preconditions, in order

- Blank directory path → one INFO line, return.
- Directory absent → one ERROR `Package directory {} does not exist — no package registered and no row pruned`, return. Configured but invisible means the volume is not mounted; "register nothing and prune everything" would be a far worse answer than saying so and leaving the rows alone.
- List the files ending in `.harnaxcli.zip`, sorted by name.
- None found → one WARN `Package directory {} holds no .harnaxcli.zip file`, and the pass continues regardless (so the prune does run).
- Packages present but no `MinioClient` available → throw `IllegalStateException` and fail startup: `N CLI package(s) in <dir> but MinIO is not enabled — set minio.enabled=true and the minio.* connection properties, or empty the package directory`. Being unable to store packages is a deployment mistake; letting the platform run silently with no CLIs at all is worse.

### 3.3 Parsing

Every file is parsed before any is registered, because two files may declare the same name and the winner has to be decided by manifest version rather than by where the directory listing happened to put them. Each file goes through `CliPackageParser.parse(file)`; an exception costs one ERROR `Failed to register package {}: {}` and one `failCount`, and the other packages continue. The parser touches no database, starts no process and never reaches the object store.

### 3.4 Same-name arbitration

Parsed packages are grouped by `manifest.name`:

- one per group: register directly.
- several per group: the highest manifest version is registered, the others skipped, with an ERROR `Name '{}' is declared by {} packages; registering {} (version {}) and skipping {}`. `compareVersions` splits on `[._+-]` and compares numeric segments as numbers, so `1.0.10` outranks `1.0.9`; a pre-release suffix is just another segment, so `1.0.0-rc1` outranks `1.0.0` — an inversion that is acceptable because the only question is which of two files declaring one name wins.
- a tie at the top (same name, same version, different files): neither is registered, with `Name '{}' is declared by {} packages at version {} ({}) — registering neither` plus one `failCount`. The name therefore stays out of `registered`, so a live row for it is not read as gone and deleted.

### 3.5 Storing the archive

The object key is `<name>/<packageDigest>.harnaxcli.zip` with `Content-Type: application/zip`; the bucket is created if missing. When an existing row already matches on both `packageDigest` and `package_object`, the upload is skipped — one indexed read.

The upload is deliberately before the transaction. After it, a failed upload could leave a committed `cli` row naming an object key that does not exist, and the "does it match" test above would then read that row as done, so the missing archive would never be re-tried. A stray object from a failed run costs disk; a row pointing at nothing costs every agent bound to that CLI its image.

### 3.6 Writing the two rows

Inside one `TransactionTemplate`: fetch the managed repository (missing it throws `managed skill repository '...' is missing — cannot register the shipped skill`) → locked read of the `cli` row for its `status` → `upsertSkill` → `upsertCliPackage`. Both rows commit together, so a package that fails halfway leaves no orphan skill row that nothing points at and nothing prunes.

`upsertSkill` runs `SkillContentScanner.scan(skillMd, skillAssets)` first — the same scanner the GIT/NPM/ZIP loaders run, because a package is as untrusted as a third-party source and its `SKILL.md` reaches every bound agent's prompt. On a hit the skill is stored disabled and the ERROR names the resource and the rule: `Skill {} was stored disabled by the content scan: {}`. This is the only feedback channel the path has.

For an existing skill row, `description`/`skillmd`/`resources`/`version` are updated; `updateById` pointedly leaves `status` alone, so the status follow-up needs its own `updateStatus` statement.

Success prints one INFO: `Registered CLI package {} {} (payload {}, {})` — name, version, the first 12 characters of `payloadDigest`, and `unchanged` or `uploaded`.

### 3.7 Pruning

`pruneMissingPackages(registered, failCount)`. Four judgements, in order:

1. `failCount > 0` → ERROR `Skipping prune: {} package(s) failed to register, so the live set is not trustworthy`, return. A pass that could not read every package has no evidence about which rows are stale.
2. Read all `cli` rows and keep only those with a non-empty `package_digest` — the ones the registrar wrote.
3. Among those, rows whose name is not in `registered` are stale; none means return.
4. `stale.size >= live` → ERROR `Skipping prune: {} row(s) not in the directory vs only {} registered — this looks like a missing package directory, not retired packages. Stale: {}`, return. A stale count at the level of the registered count reads far more like an unmounted volume than like the operator taking half the platform off the shelf.

Only then: one transaction with `deleteByCliIds` (bindings) → `deleteBySkillIds` (agent bindings, team bindings) plus the logical skill delete → the hard `cli` delete; after the commit, the archives in the object store.

Object deletion runs outside the transaction: an object store cannot roll back, so a prune whose deletes were undone must leave every archive alone. This step is best effort — a refused delete is logged and the rows stay deleted.

`status = 0` (disabled) takes part in no prune judgement: the switch is not a deletion.

### 3.8 Archive reclamation

`reclaimOrphanArchives()` collects what an in-place upgrade leaves behind: while a row stays live and only its digest changes, `removeArchivesOf` never sees that key.

Three conditions must hold together: the key has the shape this class writes (`<name>/<sha256>.harnaxcli.zip`, each half checked against `NAME_PATTERN` and `DIGEST_PATTERN`, so an object the operator put in the bucket by hand is out of reach); the key is absent from `selectPackageObjects()`; and the object's last-modified time is older than `archiveRetentionDays` days, because a row that stopped naming a key seconds ago does not mean no running agent-service is still downloading it — the row is admin's view, not the fleet's.

A listing that fails to read costs no bytes at all (`Keeping every stored archive: {} could not be listed ({})`); an entry whose own metadata cannot be read is kept on its own.

## 4. How a package reaches an image and a container

### 4.1 Spec delivery

In the internal API's agent-spec resolution, `cliDetails` maps one row per `agent_cli_binding`: rows come from `selectByIds` (with `active = 1`), `status == 0` is skipped with an INFO `CLI '{}' (id={}) is disabled, skipping`, and a missing row logs `CLI not found: cliId={}`. Each `CliDetailDto` carries:

- image material: `id`, `name`, `version`, `checkCommand`, `packageObject`, `packageDigest`, `payloadDigest`, `depsApt` — produced by `imageFields` in exactly one place, shared by the agent spec and `GET /internal/cli/inventory`;
- `runtimeEnv`: `cli.runtime_env` deserialised, placeholders left unresolved;
- `envBindings`: `[{envKey, envValue}]`, values the agent typed first, unfilled keys topped up from the defaults declared in `cli.env_params`, a key with nothing on either side not sent at all; secret values are decrypted with AES here on the way out;
- `skill`: the package's complete `SkillDetailDto` (`skillmd` + `resources` + `version`), delivered inline with the spec so the runtime needs no second query.

Skill visibility is decided by the same `skillBindingResolver.deliverable(skillIds, agentTenantId)` used for the agent's own skills, and `cli.skill_id` is an additional holder of that resource: a guard reading only the binding tables would let a shipped skill be disabled or deleted while the CLI stays live. A disabled skill is not delivered and logs an INFO.

`AgentSpecResolver.withCliSkills` merges those skills into the agent's skill list, de-duplicating by id and then by name (skill names are unique only per repository, so a tenant's own skill can share a CLI's name; the harness keys skills by `name + "_" + source`, so delivering both would let the registry and the in-memory repository disagree about which copy is live, and the operator's explicit binding wins). A CLI without a skill logs a WARN.

`AgentSpec.cliSpecs` is produced by `CliDetailDto.toCliSpec()`, the one reading of admin's CLI rows as a `CliSpec`, which also collapses `[{envKey, envValue}]` into a map.

### 4.2 Fetching and unpacking: `CliPackageStore`

The cache directory is host-configured; each package gets `<cacheDir>/<packageDigest>/` plus a sibling marker `<packageDigest>.complete`.

`materialize(packageDigest, objectKey)`:

1. `packageDigest` must pass `DIGEST_PATTERN` — it is both a cache directory name and a marker name, and it arrives over the network from admin. Otherwise: `CLI package digest (N chars) is not a sha256 hex — this CLI was not registered from a package`. A blank `objectKey` gives `CLI package <digest> has no object key — it was not registered from a package`.
2. Tree present and marker present → return immediately, refreshing the directory's mtime on the way (reading the cache is a use, and eviction measures idleness from that moment).
3. Otherwise take a per-digest JVM-wide lock, so two concurrent sessions do not each fetch the same 40 MB.
4. Create a `.staging-*` directory inside `cacheDir`, stream the object from MinIO while hashing it, and compare: a mismatch gives `CLI package object '<key>' hashes to <actual>, expected <expected> — the stored archive is not what admin registered`.
5. Unpack with `CliPackageArchive.extractTree("payload/", payload)`. Every destination is re-checked with `checkPayloadPath` and `resolveInside` on the way out, so an archive that does not match what was registered is refused here too, as `<reason> — refusing to extract <entry>`. The packed mode is restored after each write (skipped on a filesystem with no posix view, leaving the bits to the Linux host that runs the build).
6. `written == 0` → `CLI package <digest> carries no payload/ entries`.
7. An existing tree of the same name is deleted first (rename refuses to move onto a non-empty directory), then the payload is moved to its final position with `ATOMIC_MOVE` (falling back to a plain move), then the marker is written.
8. `finally` cleans the staging directory.

Net effect: the tree appears whole, or not at all.

### 4.3 Building the image: `CliImageBuilder`

`resolveImage(cliSpecs)`:

- an empty set returns the base image and builds nothing;
- `validate` re-checks `name`/`version`/`packageDigest`/`payloadDigest`/`depsApt` against the four patterns in `CliPackageLayout`. Admin checked them at registration, but the runtime has no reason to assume the row it was handed was written by the registrar: these strings land in a generated Dockerfile (as a comment and as `apt-get` arguments), and one newline in `version` would be one more instruction. The message names only the offending field, never its text: `CLI <id> carries a value no package could have registered: <fields>`;
- the set is sorted by `cliId`, `tagOf` is computed, and a hit in the locally-confirmed set or a successful `docker image inspect` reuses it; otherwise a per-tag lock, a double check, then the build. The lock keeps two sessions in one JVM from each building the same image; `knownImages` keeps every container creation from spawning a `docker image inspect`.

`generateDockerfile`:

```dockerfile
FROM <baseImage>
# CLI: harnax 1.0.0 (payload sha256:abcdef123456)
COPY f5fa5f8e2c1d4b6a9e0c7d3b1a5f8e2c1d4b6a9e0c7d3b1a5f8e2c1d4b6a9e0c/ /
# CLI: lark-cli 1.0.96 (payload sha256:9876543210ab)
COPY 2b7e9d4a1c8f03b6a5e27d94c1f80b3e2b7e9d4a1c8f03b6a5e27d94c1f80b3e/ /
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
```

The `COPY` path is the `packageDigest` (`COPY ${cli.packageDigest}/ /`), because that is the name the cached tree and the build-context directory carry; the `payload sha256:` string in the comment is the first 12 characters of the `payloadDigest`, and on one package the two values are not equal. The `COPY` destination is `/`, because the image's `WORKDIR` is `/workspace` and `./` would drop the whole tree into the agent's working directory instead of onto the filesystem its paths describe. The apt list is `distinct().sorted()` before being joined, so order inside the package influences neither the command line nor the digest. No `RUN` executes package content.

Tag: `harnax-sandbox:cli-<combinationHash>`, hashing `baseImage` plus each CLI's `cliId:version:payloadDigest` in `cliId` order, taking the first 12 hex characters. `tagOf` is the only place a tag is assembled, and `evictUnusedImages` uses it for the whitelist — a second copy of the formula that ever disagreed with the build path would delete the image a live agent starts from.

The build context is a temporary directory holding each needed payload tree, hard-linked (a plain attribute-preserving copy across filesystems) under the name `packageDigest`. The context carries only the trees this one image needs: Docker tars the whole context on every build, so pointing it at the cache would ship every package this host has ever fetched.

After the build, every CLI with a non-blank `checkCommand` gets one `docker run --rm --entrypoint /bin/sh <tag> -c "<checkCommand>"`; a non-zero exit runs `docker rmi -f <tag>` and throws `CLI '<name>' check command failed in image <tag>: <last 500 characters of output>`. A failed build throws `Failed to build CLI sandbox image <tag> (CLIs: <names>): <last 2000 characters of output>`.

Every `docker` call carries its own budget and a missing answer counts as failure rather than as permission to wait: 120000 ms for ordinary commands, 1800000 ms for `docker build`, 300000 ms for the acceptance command, 300000 ms for `docker rmi` (the constants live in `DockerCommandExecutor`). `Process.waitFor` is not interruptible, so after the reclaim task gives up waiting, an in-flight `docker` call still only returns when its own budget expires.

### 4.4 Injecting parameters when the container is created

`HarnessAgentLauncher` when building an agent:

```kotlin
val cliEnv = cliEnvironment(agentSpec.cliSpecs)
val resolvedSandboxImage = when {
    isLead || agentSpec.cliSpecs.isEmpty() -> harnessConfig.sandbox.image
    cliImageBuilder == null -> throw IllegalStateException(...)
    else -> cliImageBuilder.resolveImage(agentSpec.cliSpecs)
}
```

- a team lead always uses the base image: the lead runs no sandbox, CLIs belong to members;
- CLIs selected but no MinIO in the runtime (`harness.minio.enabled` off) throws, naming the CLI names and the first 12 digest characters, rather than degrading to the base image.

`cliEnvironment`: `runtimeEnv` is expanded per CLI in order. A literal is used directly; a whole-value `${slot}` is resolved from `harnessConfig.sandbox.platformAdminUrl` / `platformInternalToken` (assembled from configuration by `HarnessAutoConfiguration`). When a slot is unpublished or blank the variable is left unset and one WARN is logged: `CLI '{}' asks for runtimeEnv {} = {} but this deployment publishes no {} — the variable is left unset so the CLI reports why it cannot work`. Afterwards every CLI's `envBindings` is merged on top, so agent-level values win.

The result reaches the container through `DockerFilesystemSpec.environment(cliEnv)` and is also kept on `HarnessAgentWrapper` as `sandboxImage` / `sandboxEnv`, so a keep-alive container is reused with the same pair. It never enters the image: changing a value needs no rebuild, and a recreated container sees it.

### 4.5 Reclamation: `CliArtifactReaper`

Payload trees and images accumulate in one direction only. `CliArtifactReaper` (present when `harness.sandbox.enabled=true`) supplies the reverse as a fixed-delay task:

| Key | Default |
| --- | --- |
| `harness.sandbox.cli-reclaim-interval-ms` | 3600000 |
| `harness.sandbox.cli-reclaim-initial-delay-ms` | 600000 |
| `harness.sandbox.cli-reclaim-sweep-timeout-ms` | 600000 |
| `harness.sandbox.cli-reclaim-grace-minutes` | 360 |

It is a `SchedulingConfigurer` rather than `@Scheduled` because the period cannot be shorter than the grace window (`effectiveIntervalMs` clamps it and logs `... above the grace window, or lower ...`). Both sweeps run on one single-threaded worker, so a round cannot overlap a round even after the wait was abandoned.

Each round first asks admin for `GET /internal/cli/inventory` and deletes nothing when that fails: both sweeps read "absent from the whitelist" as "nothing refers to this artifact", so an empty answer would strip this host's whole CLI cache and every image it built. The image sweep goes first (it is the one shelling out to `docker`), and running out of budget keeps everything for the same reason.

The whitelist comes from admin's `resolveCliInventory()`: `packageDigests` holds every registered row with a non-empty digest (`status` is deliberately not a condition — a disabled package is still registered, its archive is still in the bucket and its tree still rebuildable), and `agentCliSets` holds each agent's enabled CLI set built through the same `imageFields` the spec uses, so the tags `tagOf` produces match the ones the build path produced.

`evictUnusedImages` drops a tag only when all of: it is not among the live sets' tags; no image listed by `docker ps -a` references it (which covers keep-alive containers, stopped rather than removed); and it was built before the grace window. A failing `docker ps -a` or `docker images` aborts the whole sweep; an unreadable build time keeps that one tag; tags outside `harnax-sandbox:cli-` are never candidates, which is what keeps the base image out of reach. A deleted tag is also evicted from `knownImages`, or the cache would keep asserting that an image Docker on this host does not have is present and the next agent would fail at `docker create`.

`CliPackageStore.evictUnused` needs: the digest absent from `inUse`; both the tree and the marker older than the grace window (anything newer is kept); and the directory name passing `DIGEST_PATTERN` (nothing this class did not write is touched). Marker and tree go together, a partial delete retries on the next sweep, and `.staging-*` directories are cleared on the same window.

`removeArchivesOf` and `reclaimOrphanArchives` sit on admin, `evictUnused` and `evictUnusedImages` on the runtime: each side can only see its own storage, and one inventory answer is the basis they share.

## 5. Why two digests

| Digest | Material | Purpose | Changes when |
| --- | --- | --- | --- |
| `package_digest` | the bytes of the whole zip | package identity; the object key; the "re-upload needed" test; the cache directory name | any byte, including comments, timestamps, entry order, wording |
| `payload_digest` | each `payload/` file entry as `path\|mode\|sha256(content)` sorted by path, plus one `#apt:` line with the sorted apt list | image fingerprint: tag material, and the only signal that container contents changed | only bytes that land in the image, or the apt set |

The payoff: editing one sentence of `SKILL.md` yields a new `packageDigest` (fresh object, updated row, a new prompt for the next session) while running containers keep their images. Merged into one digest, every wording change would rebuild the sandbox of every bound agent.

All three canonicalisation details are load-bearing: sorting by path (packing order must not leak into the fingerprint), including mode (otherwise a package that lost its execute bits and one that kept them share a digest), including the apt list (otherwise adding a `deps.apt` would not change the image).

Both sides must agree byte for byte: admin computes and delivers, the runtime reproduces the tree and the image through the same `CliPackageLayout`; two drifting implementations produce images that silently fail to match what was registered. That is why this object lives in `harnax-common`.

`runtimeEnv` and `env_params` sit at the boundary between the two digests: they are part of the zip, so inside `packageDigest`; they do not enter the image, so outside `payloadDigest`. Changing an environment declaration therefore does not change the image — it becomes visible when the container is recreated, and adds one new key to the bucket.

## 6. Pages and API

### 6.1 admin's public API

| Method and path | Purpose |
| --- | --- |
| `GET /api/admin/clis/page` | paginated read, `name` as substring and `status` as equality, `pageSize` clamped to 1..1000, ordered by name |
| `GET /api/admin/clis/{id}` | single-row read; a miss answers with the `error.cli.notfound` i18n text. The three fields the list omits (`payloadDigest`, `depsApt`, `runtimeEnv`) are answered only here |
| `PUT /api/admin/clis/toggle/{id}?status=N` | the status switch; the only write route |
| `GET /api/admin/clis/{id}/related-agents` | agents bound to this CLI |
| `GET /api/admin/clis/{id}/related-sessions` | the sessions of those agents; pair it with `POST /api/admin/agents/refresh-sessions` to push REFRESH so live sessions pick up the new configuration |

There is no create, update or delete route. The platform's own Go CLI wraps those five routes into a command surface: `harnax cli list` (`--name` / `--status` / `--page` / `--size`, against `/api/admin/clis/page`), `harnax cli get <id>`, `harnax cli toggle <id>` (`--status 0|1`; without that flag it flips the current value), all three landing on `/api/admin/clis` (`harnax-cli/cmd/cli_resource.go`). The `Short` text of `harnax cli` reads, verbatim: `Inspect CLI plugin packages registered by admin (status is the only writable field)` — nothing but status has a write command.

`toggleCliStatus` behaviour: `SkillSourcePolicy.requireStatus(status)` runs first and accepts only 0 and 1, reporting `Status must be 0 (disabled) or 1 (enabled), got <status>` otherwise — every consumer compares with `== 1`, so an out-of-range value would not fail loudly: it would read as "disabled" forever and the UI switch could not bring the row back. A missing row throws `CLI not found`; `cli.status` is written; the shipped skill's `status` then follows, with one exception — enabling does not lift a content-scan quarantine. If re-scanning the skill's content still hits, it stays disabled (without reading the content back, the second writer of that column would also be a way to approve what the first refused; content that cannot be read counts as quarantined). A skill row that has gone logs a WARN without failing: `CLI {} (id={}) points at skill {} which is gone; its status could not follow`.

Disabling is not blocked by "agents still bind this" (design intent, not an oversight); the blast radius is shown instead, through `related-agents`, for the operator to weigh before confirming. The switch exists to stop a CLI that turned out to be a problem, and an operator who has to unbind every agent first cannot do that in one action.

`CliResponse` masks secret parameter values by decrypting and keeping the first 3 and last 4 characters around `****`, or answering `******` when the plaintext is 7 characters or shorter or cannot be decrypted. An unreadable manifest column renders empty in the detail view with a WARN rather than failing the whole read.

### 6.2 Internal API

| Method and path | Purpose |
| --- | --- |
| `GET <internal>/cli/inventory` | the sole basis of the reclaim sweep: `packageDigests` plus `agentCliSets`. Cross-tenant on purpose, like the spec endpoints — a package is registrar-owned and an image tag says nothing about who selected it |
| `cliDetails` on the agent spec response | per CLI: image material, `runtimeEnv`, `envBindings`, and the inline `skill` |

On failure, inventory answers `Failed to resolve CLI inventory: <cause>`; the caller (`CliArtifactReaper`) reads that as "no information" and deletes nothing.

### 6.3 Pages

`harnax-webui/src/pages/cli/index.tsx`: search box, status filter, table, status switch, detail drawer. Columns give name, description, version, `checkCommand`, `packageDigest` (shown as the package summary — two operators reading the same string are looking at the same build), `envParams` (a popover, secrets masked), and the shipped skill's name and description. The only operator actions are the switch and the view.

`CliDetailDrawer.tsx` spreads out `payloadDigest`, `depsApt` and `runtimeEnv`, which the list never returns, reading them from `GET /api/admin/clis/{id}`.

On the agent side, `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx` selects CLIs, renders one parameter row per declaration with the input starting empty, and shares `EnvParamTable` and the `env_variable` dropdown with MCP. Row count follows `cli.envParams` rather than the stored records — rendering by stored count would make a parameter added by a package upgrade never appear, and keep a parameter the package removed on the form for someone to fill. Only rows with a value are sent.

`configValidation.ts` applies the CLI required-field rule identically to MCP: a `defaultValue` declared in the package is delivered wholesale into the sandbox, so leaving a field blank really does obtain a value, and only a required field with neither a typed value nor a default counts as missing.

The three bindable asset kinds (tool / MCP / CLI) share one declaration shape, `ToolEnvParamEntry` (`envParamName` / `description` / `required` / `secret` / `defaultValue`), one encryption path (`serializeToolEnvParams` / `decryptToolEnvParamsToMap`) and one front-end table component; values always live in the respective `*_binding.env_bindings`, and `agent_tool_env_param` is only the tool side keeping them as rows.

## 7. Key design decisions

| Decision | Content | Reason |
| --- | --- | --- |
| one lifecycle entry point | pages and API never write `cli`; a package in the directory is live, one gone from it is pruned | with two writers, "the operator deleted a row" and "the package is still on the shelf" overwrite each other; the startup sync is the single source of truth |
| two digests | see "Why two digests" | the instruction sheet and the binary have different update cadences |
| all validation at registration | paths, permission bits, sizes, entry shapes, manifest fields all refused at startup | a package that would produce a broken sandbox is far cheaper to refuse on startup: the operator sees one ERROR line naming the file, not a session failing minutes later with an unexplained exit code |
| package content is never executed | the parser only reads bytes; the image contains only `COPY` and apt | a badly behaved `checkCommand` cannot run during registration |
| `status` excluded from overwrite | it is the operator's switch and survives a restart | resetting it on restart would re-arm a disabled CLI the next time the container bounces |
| the skill follows the switch | `cli.status` changes and the package's skill changes with it; enabling does not lift a quarantine | registering a CLI and teaching it are one offer; half of it staying live leaves agents told to use a command their sandbox does not have |
| platform-wide visibility | no tenant, author or public flag on `cli` | a published package is a platform asset with no visibility to express, so reads are not tenant-scoped either |
| ambiguity picks no side | two files at one name and version register neither, and the prune is frozen | nothing states which one the operator means; guessing produces an upgrade that quietly never installs |
| missing object store fails startup | packages present with MinIO disabled throws | a platform silently running with no CLIs is harder to diagnose than a failed start |
| three brakes on the prune | a failed parse / only registrar-written rows / stale at the level of live | this is the only place the platform deletes a CLI an agent may still be configured with |
| one tag formula | `tagOf` serves both the build and the eviction | two formulas that drift delete the image a live agent starts from |
| the whitelist comes from admin | the runtime does not infer it from its own sessions | one image is shared by every agent that happens to select the same CLI set, so a host seeing only one of them would delete another's image |

## 8. Explicitly not done

- No creating, editing or deleting a CLI from a page or an API.
- No build-time execution inside a package. There is no `RUN` that runs package content; system-level preparation has exactly one route, `deps.apt`.
- No architecture probe. The binary serves a container built by the host's Docker, and the platform does not test whether it matches your machine's format.
- No coexisting versions. `uk_cli_name` gives one name one row, and bindings point at a CLI rather than a version; grey rollout means "which file is on the shelf".
- No package-to-package dependencies. Each package is independent and `deps.apt` reaches only apt package names.
- No tenant or author visibility for packages.
- No pre-filling of package defaults into the agent form (values are topped up at delivery; a blank field stays blank in the UI).
- No resolution of platform slots at registration or at spec delivery. Slots expand only when the container is created, because only that process knows this deployment's admin URL and internal token.
- No online registration. Registration happens on `ApplicationReadyEvent`; after dropping a package you restart (or recreate the container) for it to take effect.

## 9. Boundaries and known traps

- **One bad package freezes the prune but not the other packages.** Both parse failures and registration failures add to `failCount`, and `failCount > 0` skips the prune for the whole pass. Doing a publish and a take-off in one restart means the take-off does not happen if the new package is refused.
- **A missing directory and an empty directory behave differently.** The first logs an ERROR and deletes nothing; the second logs a WARN and lets the prune run. A failed volume mount is therefore better diagnosed than a mistaken bulk take-off — and the `stale >= live` brake exists to catch the case where the mount point exists but is empty.
- **`compareVersions` treats a pre-release suffix as just another segment.** `1.0.0-rc1` outranks `1.0.0` (the `rc1` segment beats nothing), the opposite of semantic versioning. It only ever decides which of two same-named files wins.
- **`upsertSkill` never renames a skill row.** Rows are located by `(name, repository_id)`, so a package changing its name is a take-off plus a publish, and both prune judgements apply.
- **A content-scan hit does not affect the CLI row.** Only the skill is stored disabled. The CLI still enters images and still appears on the tool surface; the model simply gets no instruction sheet, and `AgentSpecResolver` logs a WARN.
- **One image = one CLI set.** A single CLI's failing `checkCommand` leaves the whole set without an image, taking the other CLIs down with it.
- **`evictUnusedImages` depends on the `docker` subcommand.** The runtime container must reach the host Docker daemon; when it cannot answer, the behaviour is to keep everything.
- **`knownImages` is an optimistic in-JVM cache.** If another process removes an image, this process does not learn immediately and `docker create` fails once; the eviction path removes its own tags from the set synchronously.
- **The grace window trades safety against disk.** A period shorter than the grace is clamped back by `effectiveIntervalMs`, so shortening the interval alone does nothing unless `cli-reclaim-grace-minutes` comes down too.
- **`archive-retention-days` reads the object's last-modified time.** Overwriting an object by hand refreshes that stamp, so it then reads as "how long since these bytes were written" rather than "how long since anyone wanted them".
- **A row with an empty `package_object` is excluded from the archive whitelist** — `selectPackageObjects()` filters on `<> ''` — so such a row can never be deleted by the reclaim step.
- **The bucket name must match on both sides.** admin's `minio.cli-package-bucket` and the agent's `harness.minio.cli-package-bucket` are fed by the same `MINIO_CLI_PACKAGE_BUCKET`; setting them apart shows up as the runtime reporting "the object does not exist" rather than "the bucket does not exist".
- **The bind mount hides the baked-in copy.** Under docker-compose, `/home/harnax/cli-packages` is a read-only mount of the host's `harnax-deploy/dist/cli-packages`, so the host shelf — not the image's COPY — is what admin reads. The directory must be kept non-empty on purpose: an absent path is what Docker creates as empty, and admin then reads every CLI on the shelf as taken off and prunes it.

## 10. Operations manual

### 10.1 Configuration

| Side | Key | Environment variable | Default |
| --- | --- | --- | --- |
| admin | `harnax.cli.package-dir` | `HARNAX_CLI_PACKAGE_DIR` | `/home/harnax/cli-packages` |
| admin | `harnax.cli.archive-retention-days` | `HARNAX_CLI_ARCHIVE_RETENTION_DAYS` | 7 |
| admin | `minio.cli-package-bucket` | `MINIO_CLI_PACKAGE_BUCKET` | `harnax-cli-packages` |
| agent | `harness.minio.cli-package-bucket` | `MINIO_CLI_PACKAGE_BUCKET` | `harnax-cli-packages` |
| agent | `harness.minio.enabled` | `MINIO_ENABLED` | false; with it off, any agent that selects a CLI fails when the agent is built |
| agent | `harness.sandbox.enabled` | `SANDBOX_ENABLED` | false; gates the image capability and the reclaim task |
| agent | `harness.sandbox.image` | `SANDBOX_IMAGE` | `harnax-sandbox:py-node` (the code default is `python:3.11-slim`); the sandbox with no CLI, and the `FROM` of every CLI image |
| agent | `harness.sandbox.cli-package-cache-dir` | `SANDBOX_CLI_PACKAGE_CACHE_DIR` | `/tmp/harnax-agent/cli-packages`; holds payload trees, `.complete` markers and `.staging-*` directories |
| agent | `harness.sandbox.cli-reclaim-interval-ms` | `SANDBOX_CLI_RECLAIM_INTERVAL_MS` | 3600000 |
| agent | `harness.sandbox.cli-reclaim-initial-delay-ms` | `SANDBOX_CLI_RECLAIM_INITIAL_DELAY_MS` | 600000 |
| agent | `harness.sandbox.cli-reclaim-sweep-timeout-ms` | `SANDBOX_CLI_RECLAIM_SWEEP_TIMEOUT_MS` | 600000; this key is not part of the `harness.sandbox` binding — `CliArtifactReaper` reads it on its own through an `@Value` constructor parameter |
| agent | `harness.sandbox.cli-reclaim-grace-minutes` | `SANDBOX_CLI_RECLAIM_GRACE_MINUTES` | 360; how long a tree must have been idle, or an image built, before the sweep may take it |
| agent | slots `harness.sandbox.platform-admin-url` / `platform-internal-token` | `SANDBOX_PLATFORM_ADMIN_URL` / `SANDBOX_PLATFORM_INTERNAL_TOKEN` | an empty string means "this deployment publishes no such slot"; the `runtimeEnv` variables bound to it stay unset. With `SANDBOX_PLATFORM_INTERNAL_TOKEN` absent, the value falls back to `ADMIN_INTERNAL_API_SECRET` |

### 10.2 How packages get into a deployment

`harnax-deploy/build.sh`, `deploy-all.sh` and `deploy-service.sh` all run the same sequence: `./cli-packages/build.sh` → `cp cli-packages/dist/*.harnaxcli.zip harnax-deploy/dist/cli-packages/` → `Dockerfile.admin`'s `COPY harnax-deploy/dist/cli-packages/ /home/harnax/cli-packages/`. The build scripts refuse to ship at all when `cli-packages/build.sh` fails, rather than emitting a partial shelf.

`cli-packages/build.sh` holds three rules of its own: it never clears the shelf (clearing it would take every hand-delivered package with it, and a missing package is not "nothing happened" to admin — it is "this CLI was taken off", and the row is pruned); exactly one `harnax-cli` artifact must exist (two same-named packages land on the shelf and the newest wins by manifest version, which is not the same question as which one this build just produced); and a shelf with no package at all exits with an ERROR, because that would start admin with no CLI registered. With several packages declaring one name, the highest manifest version is kept and the rest move into `dist/.superseded/`; several at the same name and version is an outright ERROR. That matches admin's arbitration, applied earlier so the ambiguity never reaches the shelf.

`harnax-cli/Makefile`'s `package` target is the reference self-pack: `CGO_ENABLED=0` cross-compilation, `-ldflags "-X main.version=$(PKG_VERSION)"`, `plugin.yaml` and `SKILL.md` placed at the two documented locations, `chmod 755` on the binary, `zip -X -q -r` of the three locations — and a preceding `rm -f dist/*.harnaxcli.zip`.

### 10.3 Adding a package

1. The author produces `<name>-<version>.harnaxcli.zip` per the specification.
2. It goes onto the shelf (under compose, the host's `harnax-deploy/dist/cli-packages/`).
3. Restart admin.
4. Read startup: `Sync complete: N registered, M failed`. With `M > 0`, find the `Failed to register package` line above it — that ERROR carries the complete refusal list, all reasons at once.
5. Confirm on the page (`GET /api/admin/clis/page`) that the row exists and the digest is the expected one.
6. Select it on an agent and open a session; the runtime log should show `Resolved CLI sandbox image ...` and `Image ... built and verified`. The first build includes one download and one `docker build`, and its duration grows with the package.

### 10.4 Emergency disable

The page switch (`PUT /api/admin/clis/toggle/{id}?status=0`). Afterwards: spec resolution skips the CLI (it is absent from `cliDetails` and therefore from new images), its skill leaves the prompt, and `GET /internal/cli/inventory` still reports its `packageDigest` so the cache and archive survive and re-enabling needs no re-download. Already-built images are unaffected, since an image belongs to the CLI set that existed when it was built; running containers are unaffected too. To move live sessions onto the new configuration, list them with `related-sessions` and push a refresh.

### 10.5 Taking a package off the shelf

Remove the file → restart admin → look for `Removed {} CLI row(s) whose package left the directory: ...` (WARN), plus `Removed stored archive {}` and `Reclaimed {} unreferenced CLI archive(s) from {}`.

If you only see `Skipping prune: ...`, one of the four judgements did not pass. Follow the text: a parse failure means read the refusal list; an unmounted volume means check the directory; `stale >= live` means confirm the operator really intends to take that many CLIs off.

The runtime-side trees and images go in later rounds of `CliArtifactReaper`. To free disk immediately, `docker rmi harnax-sandbox:cli-<hash>` by hand — but not while `docker ps -a --format '{{.Image}}'` still lists a container referencing it.

### 10.6 Where to look, per symptom

| Symptom | Look at first | Decisive evidence |
| --- | --- | --- |
| the CLI is not on the page | admin startup log | a `Failed to register package <file>` line → the refusal list; `Package directory ... does not exist` → the volume; `holds no .harnaxcli.zip file` → the file never arrived |
| startup fails outright | the thrown `IllegalStateException` | `... but MinIO is not enabled ...` → object store unconfigured while the shelf holds packages |
| session creation reports "CLI check command failed" | runtime log plus the last 500 characters of output | wrong binary architecture, a missing shared library (not declared in `deps.apt`), or a `checkCommand` needing credentials or network |
| session creation reports "Failed to build CLI sandbox image" | same, last 2000 characters | an illegal `COPY` destination, or an apt package name absent from the sources this deployment can reach |
| `carries a value no package could have registered` | the `cli` row | the row was not written by the registrar, or the two sides' `CliPackageLayout` differ |
| `hashes to <actual>, expected <expected>` | the object in the bucket | the object was overwritten; fix by letting admin re-upload (change the package bytes and restart) or by deleting the key so registration re-runs |
| `this runtime has no MinIO to fetch them from` | agent configuration | `harness.minio.enabled` is off |
| WARN `asks for runtimeEnv ... publishes no ...` | the agent's slot configuration | this deployment has no admin URL / internal token configured; the variable is unset and the CLI reports its own error |
| the CLI is installed but the model never uses it | `Selected CLI(s) ship no skill to load` / `Skill ... of CLI ... is disabled` | the skill is under content-scan quarantine, or its row was deleted |
| disk only grows | the `[cliReclaim]` lines | `Keeping every CLI artifact: admin did not answer the inventory` → the internal route is unreachable; `Keeping every CLI image: docker could not list ...` → the daemon is unreachable |
| a disabled CLI reappears in sessions | was admin restarted, and is the package still on the shelf | only `status` survives a restart; every other column is overwritten from the package, but `status` never is |
| an agent lost its bindings after a version bump | whether the `agent_cli_binding` rows are still there | a same-named package should overwrite in place and keep the id; a "registering neither" ERROR means the shelf holds two files at one name and version |

### 10.7 Log lines and identifiers

`CliPackageAutoRegistrar` prefixes every line with `[CliPackageAutoRegistrar]`; `CliImageBuilder` uses `[cliImage]`, `CliPackageStore` `[cliCache]`, `CliArtifactReaper` `[cliReclaim]`. The image tag is `harnax-sandbox:cli-<12 hex>`, the cache and context directory names are the `packageDigest` (logs usually show its first 12 characters), and the object key is `<name>/<packageDigest>.harnaxcli.zip`. Threading one incident together takes four identifiers: the file name, the `packageDigest` (locates the object and the tree), the first 12 characters of `payloadDigest` (locates the CLI comment in the generated Dockerfile and hence the image), and the image tag (locates the container).

## 11. Key file index

| Path | Content |
| --- | --- |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrar.kt` | the startup sync: preconditions, same-name arbitration, upload, the two rows, the prune, archive reclamation |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParser.kt` | validation and refusal strings, producing `ParsedCliPackage` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageLayout.kt` | layout constants and the four patterns shared by both sides, the payload deny list, `packageDigest` / `payloadDigest` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageArchive.kt` | read-only archive view and `extractTree` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/ZipCentralDirectoryModes.kt` | central-directory external attributes to unix mode |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Cli.kt` | the entity columns |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentCliBinding.kt` | the binding and its `env_bindings` snapshot |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/CliMapper.kt` | the read methods, `selectByNameForUpdate`, `selectPackageObjects` |
| `harnax-entity/src/main/resources/mapper/CliMapper.xml` | which columns `upsertCliPackage` overwrites and which it does not, `updateStatus`, `deleteByIds` |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/CliDetailDto.kt` | the shape delivered to agent-service |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/CliPackageInventoryResponse.kt` | the inventory shape for the reclaim sweep |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt` | the read routes and the switch |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/CliServiceImpl.kt` | switch semantics, skill following, quarantine not lifted |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt` | the list/detail field split and secret masking |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` | `imageFields`, `mergeCliEnvBindings`, `cliDetails`, `GET /cli/inventory` |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt` | `serializeToolEnvParams` / `decryptToolEnvParamsToMap` |
| `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` | admin's schema baseline: the whole `cli` column set and `uk_cli_name`, `agent_cli_binding`'s `uk_agent_cli_binding_agent_id_cli_id`, and the `skill` / `skill_repository` table definitions all live in this one file; of the initial-data rows the only one belonging to this domain is the `builtin-cli-skills` repository row, and no skill row is seeded |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/CliPackageStore.kt` | digest-verified download, atomic publish, idle eviction |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/CliImageBuilder.kt` | revalidation, Dockerfile generation, tagging, build and acceptance, image eviction |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/DockerCommandExecutor.kt` | `docker` invocation and its timeouts |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` | `cliEnvironment`, image resolution, `DockerFilesystemSpec` assembly |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt` | `CliSpec` and `toCliSpec()` |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` | the two platform slots, and the construction of `CliImageBuilder` / `CliPackageStore` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/sandbox/CliArtifactReaper.kt` | the reclaim round, its budget and the whitelist |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` | `withCliSkills` and `cliSpecs` assembly |
| `harnax-agent/harnax-agent-service/src/main/resources/application.yml` | `harness.minio.cli-package-bucket` and the reclaim parameters |
| `harnax-admin/src/main/resources/application.yml` | `harnax.cli.*` and `minio.cli-package-bucket` |
| `harnax-webui/src/pages/cli/index.tsx` | the CLI page: reads and the switch |
| `harnax-webui/src/pages/cli/components/CliDetailDrawer.tsx` | digests, apt and `runtimeEnv` spread out |
| `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx` | parameter entry on the agent |
| `harnax-webui/src/services/ant-design-pro/cli.ts` | the five routes wrapped for the front end |
| `harnax-deploy/Dockerfile.admin` | where the shelf is copied into the image |
| `harnax-deploy/docker-compose.yml` | the read-only mount of `/home/harnax/cli-packages` |
| `harnax-deploy/build.sh` | shelf assembly at build time |
| `cli-packages/build.sh` | shelf rules: never clear it, one package per name, an empty shelf fails the build |
| `harnax-cli/Makefile` | the `package` target: the reference self-pack |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrarTest.kt` | convergence, same-name arbitration, the prune brakes, archive reclamation |
| `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/sandbox/CliImageBuilderTest.kt` | the tag formula, Dockerfile generation, revalidation |
| `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/sandbox/CliPackageStoreTest.kt` | digest mismatch, atomic publish, idle eviction |
| `harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/sandbox/CliArtifactReaperTest.kt` | whitelist and budget behaviour |

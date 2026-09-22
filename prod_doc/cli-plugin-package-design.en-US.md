# Harnax CLI Plugin Package Design (English)

> Chinese version: [cli-plugin-package-design.zh-CN.md](./cli-plugin-package-design.zh-CN.md)
>
> Tool integration: [tool-integration-design.en-US.md](./tool-integration-design.en-US.md) · Skill system: [skill-management.en-US.md](./skill-management.en-US.md) · MCP: [mcp-management.en-US.md](./mcp-management.en-US.md)
>
> **The spec package authors copy from is its own document**: [cli-package-spec.en-US.md](./cli-package-spec.en-US.md). This one covers the platform-side chain — registration, images, delivery, the UI — and the invariants with their reasons.
>
> The change list, migration and test plan live in `docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md`.

## 0. Document status

Everything below describes **behaviour that is in place today**: the registration path, image building, skill delivery, the UI and the API. The character classes, caps, deny list, packaging commands and self-check belong to [cli-package-spec.en-US.md](./cli-package-spec.en-US.md); both documents take `CliPackageLayout.kt` and `CliPackageParser.kt` as their common source of truth. The implementation spec — decisions D1~D15, the V35~V38 migrations, the change list and the test plan — is `docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md`.

Only the ten boundaries in §10 are still open. Of those, "`skill/assets/` never reaches the container" is not a gap in this design; it is the general skill-file projection problem tracked in the skill domain (`skill-management` TODO-10). The package spec allows the directory, the runtime does not promise to deliver it.

## 1. The model in one line

**A CLI = a binary payload + a skill document, and the two must live and die together.**

- The binary payload provides **capability**: whether the executable exists in the container.
- The skill document provides **awareness**: whether the model knows which command to run and which arguments to pass.

A CLI without a skill simply does not exist as far as the model is concerned. That is why the skill is a mandatory member of the package rather than an option (§2.2 I6) — this single judgement shapes the rest of the design.

There is no "built-in" versus "custom" split: `harnax` itself is package No. 1 under this spec (§2.3).

## 2. The package spec

The spec a package author copies from is its own document: [cli-package-spec.en-US.md](./cli-package-spec.en-US.md). It carries the layout and file-name rules, the seven `plugin.yaml` fields with their character classes, the payload path rules and deny list, the size caps, how to write `checkCommand`, the packaging commands, shelving and retirement, the pre-release checklist, the refusal-message table, and one third-party worked example.

What stays here are the two things the design side owns: the shape of a package, and the seven invariants. §3~§10 build on them, and every mention of I3, I5 or I7 below points at §2.2.

### 2.1 Shape

```
<name>-<version>.harnaxcli.zip
├── plugin.yaml            # single source of metadata; the platform reads nothing else
├── skill/SKILL.md         # required, and its skill name equals `name`
└── payload/               # file tree baked into the image; a relative path is the container's absolute path
    └── usr/local/bin/<name>
```

Those three locations are all a package has. The platform runs no install script: a package can only declare which files land where and which apt packages to install. There is no `install` section and no field that accepts a script (D6).

### 2.2 Invariants

- **I1** `name` is the identity; a new version overwrites the same row, no coexisting versions;
- **I2** two digests: `packageDigest = sha256(zip)` is the package identity, `payloadDigest` is the image fingerprint (§6);
- **I3** every destination path under `payload/` must have **exactly one spelling**: absolute paths, empty segments (`usr//bin/x`), `.` segments (`./etc/x`, `usr/./bin/x`), `..` segments, NUL bytes and colons inside a segment are all refused. The deny list — `etc/`, `root/`, `usr/bin/docker`, `usr/local/bin/docker`, `bin/su`, `usr/bin/su`, `sbin/`, `usr/sbin/`, `var/run/docker.sock` — is compared per **path segment**: an entry with a trailing slash is a directory prefix (`etc/passwd` hits, `etc2/x` does not), one without a slash is a full path (`usr/bin/su` hits, `usr/bin/su-client` does not). Symbolic links, setuid/setgid/sticky bits and stray entries outside `plugin.yaml`/`skill/`/`payload/` are refused; an entry name that appears twice in the package is refused; the payload must carry at least one executable bit (otherwise the package installs no command);
- **I4** parsing reads package content only and **never executes anything inside the package**;
- **I5** disabling a CLI disables its skill too — the two never diverge;
- **I6** the skill name inside `skill/SKILL.md` must equal `name`;
- **I7** `payloadDigest` must be canonical: take the **file** entries under `payload/` (directory entries stay out of the fingerprint — `zip -r` writes a line for each of them), concatenate `path|mode|sha256(content)` sorted by destination path, then fold in the sorted `deps.apt`. Packaging timestamps, the order of entries inside the zip and the directory entries the packer additionally records must not affect the result.

Each class of content has its own size cap, and exceeding one refuses registration: `plugin.yaml` 64 KB, `skill/SKILL.md` 1 MB, an individual `skill/assets/` file 512 KB with 4 MB for that class in total, at most 2000 payload files, at most 512 MB once unpacked, at most 50 `deps.apt` entries. The first three are text and are read with the length **computed as bytes stream in** — the size in the central directory is filled in by the packer itself and is simply `-1` for a streamed write, so it is not taken as evidence; the manifest is additionally bound by YAML nesting-depth and duplicate-key limits.

The unique-spelling requirement exists because there are two pieces of code, one inside the gate and one outside: inside, the deny list is matched against the path text; outside, the path is joined onto a target directory and then normalised. A path such as `usr/./bin/docker` passes as text and lands on the deny list once it has landed on disk, so only one side would catch it. The same invariant also holds the digest up — `payload/./usr/bin/x` and `payload/usr/bin/x` are two spellings of one file, and if both entered the fingerprint the image tag would stop describing what is in the image.

Executable bits travel in the zip's unix mode external attribute and the platform never rewrites them, so packaging must **`chmod 755` first, then `zip -X`**. Only entries whose `versionMadeBy` claims UNIX are trusted. A packer that writes no mode at all gets the package refused at registration rather than silently unpacked as `0644` — the latter only surfaces at image-build time as `checkCommand` exiting 126 (Permission denied), which is far harder to attribute.

### 2.3 Reference implementation: `harnax` itself

`harnax-cli/` is a Go command line for platform administration and is package No. 1. It exercises the two slots that specs like this usually omit:

- `checkCommand: harnax --version` — build-time acceptance. cobra generates the `--version` flag but no `version` subcommand, so writing the latter fails as an "unknown command" with exit code 1, and the image build is voided with it;
- `runtimeEnv: {HARNAX_URL: ${platform.adminUrl}, HARNAX_TOKEN: ${platform.internalToken}}` — it cannot work without knowing the platform address and a token.

A container has no home directory to write `~/.harnax` into, so `harnax` reads those two environment variables instead. This adds no new authentication mode: when the token is present it returns the CLI's already-existing internal credential (`Bearer <secret>`). Precedence is `--server-url` > `HARNAX_URL` > profile, and with **any** `--profile` passed neither environment variable is honoured — that flag says "talk to some other environment", and otherwise the CLI would take the platform's own internal token to whatever prod an operator happened to configure and report success. When `HARNAX_URL` is set but `HARNAX_TOKEN` is empty, it errors out ("pointing at a platform it cannot authenticate to") instead of falling back to the profile: a silent downgrade would look exactly like an ordinary login failure.

Under the package model `harnax` travels the same path as every other CLI: the binary in the image comes from the package payload, the skill on the page comes from the package's `skill/SKILL.md`, and there is no mount or base-image branch on the image-build side that exists just for this repository. `sandbox-plugins/build.sh` only builds the default sandbox image `harnax-sandbox:py-node` (base `python:3.11-slim`) and has no part in CLI delivery.

`harnax cli` has exactly three actions: `list`, `get`, and `toggle <id>`. `toggle`'s `--status` may be omitted, and omitting it flips the current value — the flip is done on the CLI side, which `get`s the current `status` first and then submits the target value explicitly, while `status` is always required on the admin side. The default of `--status` is deliberately not 1: otherwise a command named toggle would be enabling its target package on every run, including skills that the content scan had taken offline (§7).

## 3. Registration path (admin startup)

```
/home/harnax/cli-packages/*.harnaxcli.zip
        │  ApplicationReadyEvent (after Flyway)
        ▼
CliPackageParser.parse      pure function: manifest + two digests + structural validation
        │  invalid → skip that package, ERROR log, other packages unaffected, boot continues
        ▼
group by manifest name      several packages under one name → register only the highest `version`, ERROR on the rest
        │  two copies of one version → neither is registered, counted as a failure
        ▼
packageDigest differs from the stored one? ──no→ upsert the row only, no object upload
        │yes
        ▼
MinIO putObject  harnax-cli-packages/<name>/<packageDigest>.harnaxcli.zip
        ▼
upsert cli row (name/version/description/check_command/env_params/both digests/object key)
        ▼
upsert the skill row in the managed repository (SKILL.md body + assets) → write back cli.skill_id
        │  skill status follows the cli row's status; a content-scan hit forces 0 and ERROR lists the matched rules
        ▼
pruneMissingPackages  packages gone from the directory → hard-delete the cli row, cascade over
                      agent_cli_binding / agent_skill_binding / team_skill_binding, soft-delete the
                      shipped skill row; WARN lists the package names and row ids
```

A configured directory that does not exist is treated as an unmounted volume: the registrar logs ERROR and then neither registers nor prunes. Running the prune step in that state would delete every CLI on the platform along with its bindings, over a missing mount.

Several packages under one name are arbitrated on the manifest's `version`, comparing its numeric segment by segment (`1.0.10` is newer than `1.0.9`), so forgetting to take the old package off the shelf during an upgrade does not come down to whichever order the directory happens to list in. A pre-release suffix is just another text segment here, which means `1.0.0-rc1` does judge as newer than `1.0.0` — the two files carry the same name anyway and this step only answers which one goes on the shelf, so the inversion is acceptable.

There are three brakes in total: a run where any package failed to read is not pruned (there is no trustworthy live set); **only rows whose `package_digest` is non-empty are candidates** — a non-empty digest is evidence that the registrar wrote the row with its own hand, so it never deletes rows it did not write, and the "stale / remaining" ratio is computed only among those rows; **the prune refuses with an ERROR as soon as the stale count is at least as large as the number of packages registered in this run**. The third one bites operators when the package directory is small: with two packages present, removing one is `stale 1 / remaining 1`, which falls on the refused side, and the log says outright "looks like a missing package directory, not retired packages". Retiring a package legitimately requires more packages to stay on the shelf than to leave, and that is also the floor this brake puts under prune: a run can only delete while at least two packages stay on the shelf, so the registrar-owned set never shrinks to a single CLI — retiring the second-to-last one takes SQL.

Hand-entered `cli` rows carry an empty `package_digest` and therefore never become prune candidates — they are not "a package that came off the shelf" but records this model cannot produce. Those rows are retired once by `V38__retire_pre_package_cli_rows.sql`: bindings hard-deleted, the row set `active=0`, and its name given a `#retired-<id>` suffix (`uk_cli_name` covers soft-deleted rows too, so without the suffix a new package under the same name would overwrite the carcass).

This is deliberately the same shape as tools: `BuiltinToolAutoRegistrar` syncs `agent_tool` from `@Tool`/`@ToolMeta` annotations at startup, the CLI registrar syncs `cli` from the package directory. Both tables have exactly one source, and both pages are read-only.

The object key carries no `version`: `name` is the identity (one CLI, one row) and the difference between builds is expressed by the digest.

Registration fails startup when the directory really does hold packages but MinIO is disabled — no local fallback, because a fallback branch would create a second source of truth and a second path to test. An empty directory requires nothing: a deployment that uses no CLI should not be handed an external dependency it cannot use.

## 4. Reaching the image and the container

`agent-service` resolves the image before creating a sandbox (only for non-lead agents, and only when that agent selected at least one CLI):

1. Compute the tag from an image fingerprint: the first 12 hex digits of `sha256(base image + each CLI's cliId:version:payloadDigest, sorted by cliId)`, written as `harnax-sandbox:cli-<12hex>` — it is not the `payloadDigest` itself, and the same set of CLIs yields the same tag regardless of listing order; if the tag exists locally, the whole build is skipped;
2. When a build is needed, fetch packages from MinIO and unpack `payload/` into a local cache directory sharded by `packageDigest`. The inputs are re-checked first: `packageDigest` must be sha256 hex and `objectKey` must be non-empty — both are about to be used as path components, and they arrive in a spec pushed down by admin rather than values this process built. The download hashes sha256 as it streams and throws if the result differs from the expected digest (the stored bytes are not the package that was registered). Unpacking re-runs the I3 deny list and escape check entry by entry, instead of assuming "that check already happened when admin read the package"; a directory entry is checked after dropping the trailing slash that makes it a directory, because registration only checks file entries and both sides must read the same name the same way;
3. Generate the Dockerfile: `FROM <base>` + `COPY <packageDigest>/ /` + an optional apt layer;
4. `docker build` — the context may be a path inside the agent-service container, since the CLI tars and streams it to the daemon;
5. Run each `checkCommand` inside the new image; **any non-zero result triggers `docker rmi -f` and throws** — a bad package never reaches runtime;
6. Flatten `env_params` + `runtimeEnv` into container env, injected at creation time.

Keep-alive semantics (verified against current code, unchanged by this design): env is injected only at container creation, so **changing CLI parameters requires a new session**. Replacing a binary behaves the same way — **a live keep-alive container is not switched to the new image**: `KeepAliveSandboxManager.getOrCreate` returns the in-memory sandbox before the image comparison is ever reached, and restarting agent-service does not help because the startup scan (`scanAndRestore`) re-adopts the existing containers into the same cache. For a running session to pick up a new binary it must first be idle-reclaimed, or removed by hand with `docker rm -f agentscope-sandbox-<sessionId>`.

## 5. How the skill reaches the model

`skill/SKILL.md` lands in the `skill` table (the body is the source of truth; runtime never re-reads package files) and is injected into the agent context after being delivered to agent-service **inline in `cliDetails`** — a skill has no endpoint of its own to fetch it from; its courier is the CLI it belongs to.

This path has exactly one writer for the skill row: the registrar that runs at admin startup. The row lands in the managed repository `builtin-cli-skills`, and that repository is read-only for the skill page (create, update and delete are all refused). A skill inside it may **not be bound to an agent on its own** either — `SkillBindingResolver` refuses directly, and the only way to load one is to bind the CLI, which is where §8's D3 ("the binary and its document live and die together") lands in the data layer. The read side works the same way: delivery carries only the single skill inlined on this CLI, and its name matches the row `cli.skill_id` points at.

## 6. Why two digests

| Digest | Covers | Used for |
|---|---|---|
| `packageDigest` | the whole zip | MinIO object key, "must I re-register?" |
| `payloadDigest` | `payload/` tree + `deps.apt` | image tag fingerprint |

Using only the whole-package digest would be wrong: fixing a typo in `SKILL.md` would change the tag, so a fresh image layer and a fresh cache directory would be built for a byte-identical file tree, and the tag would stop saying anything about what is inside the image. With the split: editing documentation → the image stays, the next session gets the new prompt; replacing a binary → new sessions run the new image (sessions already kept alive stay on the old one, see §4). This is the one place the package model is easy to get wrong.

## 7. UI and API

`/context/cli` is a single read-only table — Name / Description / Version / Shipped skill / Health check / Package digest (first 12 hex of `packageDigest`) / Status. The shipped-skill cell is an entry point: it opens that skill's detail page (`/context/skill/detail/:id`). Toggle is the only action on the page that changes state. How a package gets published is not stated on the page: what an author copies verbatim — fields, limits, packaging — lives in the standalone [cli-package-spec.en-US.md](./cli-package-spec.en-US.md), and what the platform does once a package is placed lives in §3 (registration path).

The API surface is those five routes: `GET /api/admin/clis/page`, `GET /{id}`, `PUT /toggle/{id}`, `GET /{id}/related-agents`, `GET /{id}/related-sessions`; under `/api/admin/clis` there is no create, update or delete route at all. Toggle does not refuse because "an enabled agent still binds it" — it is a kill switch, and blocking the stop action contradicts its purpose. Blast radius is shown by the `related-agents` preview (a warning, not a guard), and after a change the page offers to refresh the affected sessions.

`PUT /toggle/{id}` requires `status` and accepts only 0 or 1; anything outside that range errors outright, because every consumer site-wide compares this field with `== 1` — a value of 2 would not error, it would simply read as "disabled" forever and the page could never switch it back. Disabling takes the shipped skill row along with it (I5); enabling has one exception — it **does not lift the content-scan quarantine**. The quarantine is not a flag but a verdict: the `skillmd` + resources stored in the database are scanned again and still match some rules. When the body cannot be read, the skill is treated as quarantined — the cost is one extra manual enable, paid to avoid "a toggle that also approved `curl | sh`". Lifting a quarantine means changing the skill's content itself.

## 8. Key decisions

All 15 decisions (D1~D15) with their reasoning are in the implementation spec §1. Only three affect external implementers:

- **D3 skill is mandatory**: a package without `SKILL.md` is invalid; there is no "install the binary first, document it later" path;
- **D6 no arbitrary shell**: installation is payload placement plus an apt list only; the manifest accepts no script field, and authors must not assume the container has network at build time;
- **D15 two digests**: the image tag depends on `payloadDigest` alone, so editing documentation never builds a new image layer.

## 9. Consistency with tools / skills / MCP

| | Source | Registered when | Page | Table |
|---|---|---|---|---|
| Tool | `@Tool` in code | admin startup | read-only | `agent_tool` |
| **CLI package** | **`.harnaxcli.zip` in a directory** | **admin startup** | **read-only + toggle** | **`cli`** |
| Skill | Git repository / manual | user installs | writable | `skill` |
| MCP | config + remote | user config | writable | `mcp_*` |

Sharing the shape of tools costs the same thing tools cost: both need a restart, neither hot-reloads. It pays back the same way: one source, a page that cannot lie, and the image guaranteed to be derived from the same row the UI shows.

## 10. Known boundaries

1. **The platform becomes a software distribution channel.** Anyone who can drop a file into the package directory decides what lands as root in every agent sandbox. The denied prefixes in I3 are one layer of defence in depth, not a sandbox. The trust boundary moved from "web form validation" to "release artefact control" — moved, not removed.
2. **`runtimeEnv` ships secrets into containers.** If `HARNAX_TOKEN` is the admin internal secret, a process inside the container can call admin as SYSTEM. The slot is part of a published standard, so other packages will copy the same shape. A per-session short-lived token should replace it.
3. **`deps.apt` needs a reachable mirror.** Declarative is not the same as offline, and this environment cannot reach GitHub directly from host or container. The recommended shape is a static binary under `payload/` with no deps.
4. **Registration results are invisible to the UI.** A misplaced package or a broken manifest shows up as "it never appeared"; the truth is in the admin log. A read-only "last registration result" endpoint is missing.
5. **`skill/assets/` never reaches the container.** Bundled skill files are still not projected into the sandbox (TODO-10 in `skill-management`); a package may carry them but runtime does not promise delivery.
6. **No version coexistence.** During a rollout every agent gets the latest version; there is no per-agent pinning or staged rollout.
7. **An upgrade does not follow sessions already kept alive.** A new binary reaches new sessions only; an old session keeps running the old container from the old image until it is idle-reclaimed or removed by hand (mechanism in §4). Not a regression — it is the existing keep-alive cache semantics — but the version column in the UI shows the latest registered value and may disagree with what a live session is running.
8. **The version segment in the file name is for human eyes only.** The registration identity comes from the manifest's `name`/`version`, and the arbitration between same-name packages reads only the manifest's `version`. Name the file `harnax-9.9.9.harnaxcli.zip` while the manifest says `1.0.0` and the platform registers it as `1.0.0` — which is also what the page shows.
9. **Package objects, payload caches and CLI images only grow.** MinIO holds one object per `packageDigest`, agent-service one local cache directory per `packageDigest`, and docker one tag per `payloadDigest`; none of the three has a reclaim path, and the `knownImages` check does not heal itself after a `docker image prune` either. The packaging side adds to this: `make package` does not pin mtimes, so repacking identical content still produces a fresh `packageDigest` each time, and every release therefore leaves one more object and one more cache directory behind (the `payloadDigest` is the same, so no extra image). Removing objects and images for packages that have come off the shelf is a purely manual operations task today.
10. **No build mutex when several agent-service replicas share one docker daemon.** Image tags are content-addressed, so the outcome agrees, but two replicas building the same tag at once — or one running `docker rmi -f` on an image that failed acceptance while another is mid-build — leaves one side seeing a build failure. A single-replica deployment never hits this; it needs solving before agent-service is scaled out.

## 11. Operations runbook

| Action | How | When it takes effect |
|---|---|---|
| Add a CLI | with a source: self-built in `harnax-cli/`, third-party in a new `cli-packages/<name>/`. An already-packed zip: drop it straight onto the shelf `cli-packages/dist/`, no source directory needed. Both paths rejoin at one step — `./cli-packages/build.sh` (it only builds packages that have a source and settles same-name ties on the way) → copy `cli-packages/dist/*.harnaxcli.zip` into `docker-new/dist/cli-packages/` → restart admin. The docker-new scripts already chain the last two | new sessions |
| Upgrade a CLI | change that package's source (raise `version` in the manifest), or drop the newer zip onto the shelf — with two packages under one name `build.sh` keeps the higher `version` and moves the loser into `cli-packages/dist/.superseded/`; then rerun the row above (same `name` overwrites the same row) and restart admin | new sessions. Sessions already kept alive stay on the old image; to force it, wait for idle reclaim or `docker rm -f agentscope-sandbox-<sessionId>` |
| Documentation only | edit `SKILL.md`, repack (same `name`), put it back on the shelf, restart admin | the new prompt reaches new sessions while the image stays put: `packageDigest` changed, so one more MinIO object and one more local cache directory appear; `payloadDigest` did not change, so no new image is built (§10-9) |
| Retire a CLI | remove the package from both shelves — `docker-new/dist/cli-packages/` and the shelf `cli-packages/dist/` (one that has a source directory also needs `cli-packages/<name>/` gone, otherwise the next build puts it back on the shelf) — then restart admin | cascade: cli row hard-deleted, the three binding tables cleared, shipped skill `active=0`; WARN lists package names and row ids. **Precondition: more packages must stay than leave**, otherwise the third brake refuses and logs ERROR (see §3) |
| Emergency stop | toggle in the UI (bindings preserved) | next configuration resolve |
| "The model says the command is missing" | in order: row present in the UI → admin registration log → agent-service `Resolved CLI sandbox image` → file and mode inside the container → run `checkCommand` by hand | — |

Delivery runs through a shelf: `cli-packages/build.sh` tops up only the packages that have a source (the self-built `harnax-cli` via `make package`, each third-party package via its own `cli-packages/<name>/build.sh`) into `cli-packages/dist/` and **never clears it** — a hand-dropped zip stays exactly as it is and ships with the rest, copied wholesale by the three entry scripts into `docker-new/dist/cli-packages/` for `Dockerfile.admin`'s `COPY`, while compose covers the container path `/home/harnax/cli-packages` with a **read-only bind mount** of that host directory. The host copy is the only copy: the "the image holds the new package, the volume the old one" desync is gone, and so is `docker cp` (the mount is read-only, so writing into the container is refused). Same-name ties are settled in that same step: the higher `version` stays and the loser moves into `cli-packages/dist/.superseded/` (moved, not deleted — a hand drop has no source to rebuild it), while two packages at the same name *and* version fail the build. The price is one new failure mode — emptying the shelf reports every CLI on it as retired, which is what prune's three brakes catch (see §3).

Registration only happens at admin startup; there is no "rescan" endpoint. The page will not tell you why a package is missing either — a wrong directory or a broken manifest looks exactly like "nothing new appeared". The truth is in the admin log lines tagged `CliPackageAutoRegistrar`.

## 12. File index

| Stage | File | Role |
|---|---|---|
| Package shape | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageLayout.kt` | suffix, the three entry constants, four regexes (name/version/apt/digest), the deny list, `checkRelativePath`/`checkPayloadPath`/`resolveInside`, both digest computations, the file-count and byte caps |
| Package shape | `.../common/cli/CliPackageArchive.kt`, `ZipCentralDirectoryModes.kt` | read-only unpacking, `readBounded` which measures the length as bytes stream in, the duplicate-entry-name list; unix mode taken from the central directory, which is the real fix for §2.2's trap |
| Registration | `harnax-admin/.../registrar/CliPackageParser.kt` | zip → manifest + digests + structural checks, pure function; reports every refusal reason in one pass |
| Registration | `harnax-admin/.../registrar/CliPackageAutoRegistrar.kt` | scans the directory on `ApplicationReadyEvent`, arbitrates packages that share a name, uploads to MinIO, upserts `cli` and its skill, prunes behind three brakes |
| Registration | `harnax-admin/.../constant/BuiltinRepository.kt` | `CLI_SKILLS` — the managed repository that hosts CLI-shipped skills |
| Outward API | `harnax-admin/.../controller/CliController.kt`, `dto/CliResponse.kt` | five read/toggle routes; response carries `packageDigest` and the inline `skill` |
| Outward API | `harnax-admin/.../service/impl/CliServiceImpl.kt` | toggle and the skill-status follow-through; re-scans the content on enable to decide the quarantine |
| Delivery | `harnax-admin/.../controller/InternalApiController.kt` | `cliDetails` inlines the skill, skips `status=0` |
| Data | `harnax-entity/.../entity/Cli.kt`, `mapper/CliMapper.kt`, `resources/mapper/CliMapper.xml` | `skill_id` / both digests / `package_object` |
| Data | `harnax-admin/src/main/resources/db/migration/V35__cli_package_registration.sql` | adds columns, drops `install_script` and the tenant columns, drops three dead tables |
| Data | `harnax-admin/src/main/resources/db/migration/V36__drop_skill_binding_env_bindings.sql` | drops the dead `agent_skill_binding.env_bindings` column, which never had a consumer |
| Data | `harnax-admin/src/main/resources/db/migration/V37__retire_seeded_builtin_cli_skill.sql` | soft-deletes the hand-seeded `harnax-cli` skill row (that name belongs to the package) |
| Data | `harnax-admin/src/main/resources/db/migration/V38__retire_pre_package_cli_rows.sql` | retires the leftover `cli` rows whose `package_digest` is empty: bindings cleared, `active=0`, name suffixed with `#retired-<id>` (§3) |
| Runtime | `harnax-agent/harnax-harness-core/.../sandbox/CliPackageStore.kt` | re-checks the digests before using them as path components, fetches by `packageDigest` and verifies the hash, unpacks `payload/` into the cache directory |
| Runtime | `.../sandbox/CliImageBuilder.kt` | image tag, Dockerfile, `checkCommand` acceptance (`rmi` on failure) |
| Runtime | `.../harness/HarnessAgentLauncher.kt` | resolves `runtimeEnv` into container env, entry point for image resolution |
| Runtime | `.../harness/config/MinioConfig.kt`, `harnax-agent-service/src/main/resources/application.yml` | `cli-package-bucket` and `ensureBuckets` |
| Runtime | `harnax-agent/harnax-agent-service/.../runner/AgentSpecResolver.kt` | `withCliSkills` merges `cliDetails[].skill` into the spec |
| UI | `harnax-webui/src/pages/cli/index.tsx` | single read-only table; toggle is the only interaction |
| Package No. 1 | `harnax-cli/plugin.yaml`, `Makefile` (`package` target) | manifest and packaging (the `package` target does not pin mtimes, see §10-9) |
| Package No. 1 | `harnax-cli/internal/config/config.go`, `cmd/root.go` | `HARNAX_URL`/`HARNAX_TOKEN` → internal credential; the `--profile` and half-configured-environment refusals (§2.3) |
| Package No. 1 | `harnax-cli/cmd/cli_resource.go` | the three actions `list`/`get`/`toggle`; an omitted `--status` flips the current value |
| Deployment | `cli-packages/build.sh` | Tops up the shelf `cli-packages/dist/`: never clears it (a hand drop stays), runs each package script, keeps the highest `version` per name and moves the loser into `dist/.superseded/`; it fails only when the shelf is empty or two packages share both name and version |
| Deployment | `docker-new/Dockerfile.admin`, `docker-compose.yml` | COPY of the package directory; read-only bind mount `./dist/cli-packages` → `/home/harnax/cli-packages` |
| Deployment | `docker-new/build.sh`, `deploy-all.sh`, `deploy-service.sh` | all three run `cli-packages/build.sh` first, then stage `docker-new/dist/cli-packages/` |
| Test | `harnax-common/src/test/kotlin/.../cli/CliPackageLayoutTest.kt` | the shared path rules and the four character classes — admin and agent-service use this one copy of the rules |
| Test | `harnax-admin/src/test/kotlin/.../registrar/CliPackageParserTest.kt`, `CliPackageAutoRegistrarTest.kt`, `.../it/CliManagementIT.kt` | every refusal rule at parse time, registration/arbitration/prune behaviour, and the upsert plus cascade delete against a real database |
| Test | `harnax-agent/harnax-harness-core/src/test/kotlin/.../sandbox/CliPackageStoreTest.kt`, `CliImageBuilderTest.kt`, `.../harness/HarnessAgentLauncherCliEnvTest.kt` | the fetch side's digest re-check, hash-while-download comparison and per-entry unpack review; image tag, Dockerfile and build-time acceptance; `runtimeEnv` reaching the container env |
| Test | `harnax-cli/internal/config/config_test.go`, `cmd/cli_resource_test.go` | `--profile` precedence and the half-configured-environment error; `toggle` flipping the current value when `--status` is omitted |
| Author side | `prod_doc/cli-package-spec.en-US.md`, `cli-packages/lark-cli/` | the standalone package spec (character classes, caps, deny list, packaging and self-check, refusal-message table), plus package No. 2: how a third-party binary is fetched and packed |

# Harnax CLI Plugin Package Specification

For authors of CLI packages: what shape the file you ship has to have, which fields `plugin.yaml` carries, why the platform refuses a package, how to pack it, how to put it on the shelf and take it off.

How this document pairs with "Harnax CLI Plugin Package Design": this one is read by package authors and covers the shape of a package and its validation contract. The other is read by platform implementers and operators and covers the registration path, how a package reaches an image and a container, the shelf directory and the object store. Each is self-contained; neither depends on reading the other.

Every refusal string in the hard-constraints section and in the refusal table is copied character for character out of the code and can be read as a contract: the English string the program emits matches what is written here exactly. Angle-bracketed content is a variable the code interpolates.

## 1. The model in one sentence

One CLI plugin package = one `.harnaxcli.zip` file. The author drops it into the shelf directory; the platform reads it at startup and does three things in one pass: stores the archive in the object store under its `packageDigest`, registers the `skill/SKILL.md` inside the package as this CLI's own skill, and writes the `plugin.yaml` fields as one `cli` row.

Nothing inside a package is ever executed by the platform. Registration only reads bytes and starts no process, so a `checkCommand` can be as badly written as it likes and still cannot run at registration time; the place it actually executes is after the image has been built.

A package has no tenant, no author and no visibility concept: putting it on the shelf makes it a platform-wide asset.

## 2. Package layout and file name

The file name is `<name>-<version>.harnaxcli.zip`, and `<name>` must equal the `name` declared in `plugin.yaml`. Validation derives the identity from the file name first and then re-checks it against the declared name; a disagreement is a refusal.

Inside the archive exactly three locations are recognised:

```text
plugin.yaml                  # the manifest, at the archive root
skill/
  SKILL.md                   # the instruction sheet this CLI ships
  assets/
    <relative path>          # optional; text resources of the skill
payload/
  usr/local/bin/<binary>     # a path relative to the container root, written verbatim minus the payload/ prefix
  <any file>                 # permission bits come from the packed st_mode
```

A path under `payload/` minus its prefix is the path inside the container, so a binary installed at `/usr/local/bin/foo` is packed as `payload/usr/local/bin/foo`.

Directory entries (the ones whose archive name ends in `/`) carry no resource, are not path-checked and do not enter either digest; only file entries are processed.

A registration run ends in one of two states: the package passes and the rows are written, or the package is refused and every reason is listed. There is no half-registered package.

## 3. What the platform does with a package (consequences an author needs)

1. **Digests carry identity.** `packageDigest` is the sha256 of the whole zip; it is both the object-store key and the test for "did this package change". `payloadDigest` is the canonical sha256 of every `payload/` file as `path|mode|sha256(content)` sorted by path, followed by one `#apt:` line holding the sorted apt list; it is the image fingerprint.
2. **The instruction sheet reaches the prompt.** `skill/SKILL.md` becomes a skill row named after the CLI, pointed at directly by `cli.skill_id`. The content goes through the same content scanner the third-party skill loaders run. On a hit the skill is stored disabled and the log names the resource and the rule.
3. **The row id survives a version bump.** When a same-named package ships a new version, the platform overwrites every manifest-owned column in place and keeps the row id, so agent bindings do not break because you released a version. `status` (the switch) is not among the overwritten columns.

## 4. `plugin.yaml` fields

Seven top-level keys are known: `name`, `version`, `description`, `checkCommand`, `deps`, `envParams`, `runtimeEnv`. Any eighth is a refusal.

| Field | Required | Type | Constraints | Where it lands |
| --- | --- | --- | --- | --- |
| `name` | yes | string | matches `^[a-z][a-z0-9-]{1,63}$`; equals the first half of the file-name stem; equals the skill name in `skill/SKILL.md` | `cli.name` (unique key `uk_cli_name`), and the skill name |
| `version` | yes | string | matches `^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$` | `cli.version`; the tie-break between two same-named packages; one input to the image tag hash |
| `description` | yes | string | non-blank | `cli.description`, used by the page and the tool description |
| `checkCommand` | yes | string | non-blank | `cli.check_command`, run once inside the built image |
| `deps.apt` | no | list of strings | at most 50; each matches `^[a-z0-9][a-z0-9+.-]{0,62}(=[0-9A-Za-z.+-]{1,32})?$`; no duplicates | `cli.deps_apt` (JSON array), goes into `apt-get install -y` |
| `envParams` | no | list of mappings | see the "Parameters and credentials" section | `cli.env_params` (JSON, secret values encrypted at rest) |
| `runtimeEnv` | no | mapping | see the "Parameters and credentials" section | `cli.runtime_env` (JSON), injected when the container is created |

Under `deps` there is no section besides `apt`.

The YAML is loaded with the safe loader: unknown tags do not instantiate objects, duplicate keys are an error rather than a silent last-wins, nesting depth is capped at 20 and code points at 65536. String values are trimmed. Booleans accept `true`/`false` literals and the case-insensitive string `"true"`; anything else reads as `false`.

## 5. Hard constraints: what gets refused

Each line below is the actual string from the code; parentheses state the trigger. Angle-bracketed content is interpolated.

### 5.1 File name and readability

- When the file name does not end in `.harnaxcli.zip`: `file name must end with .harnaxcli.zip`. `checkFileName` returns as soon as it has added that line, so what it skips is the stem check inside the same function — the manifest, skill and `payload/` checks still run and accumulate next to it (see section 5.7).
- The stem has no `-` that is neither first nor last: `file name must be <name>-<version>.harnaxcli.zip, got "<file>"`
- The stem does not start with "declared name plus `-`": `file name "<file>" does not start with the declared name plus a version, as in "<name>-1.4.0.harnaxcli.zip" — rename the file, the declared name is the identity`
- The zip itself cannot be opened: thrown immediately as `<file>: unreadable package (<cause>)`.

### 5.2 A path must have exactly one spelling

`checkRelativePath` is the whole anti-traversal rule for names inside a package, shared by `skill/assets/` keys and `payload/` destinations. It refuses:

| Returned reason | Trigger |
| --- | --- |
| `empty path` | blank path |
| `path must be relative: <path>` | starts with `/` |
| `path contains a NUL byte` | contains a NUL character |
| `path has an empty segment: <path>` | doubled or trailing slash |
| `path is not in canonical form: <path>` | a segment is `.` |
| `path escapes its root: <path>` | a segment is `..` |
| `path contains a colon: <path>` | any segment contains `:` |

The table gives the reason text itself. When a `payload/` entry triggers one, the line that reaches the problem list is `<reason> (entry <archive path>)` — `readPayload` appends the full archive entry name, `payload/` prefix included. A `skill/assets/` key appends a longer tail instead, as section 6.4 states.

One location is allowed exactly one spelling: `usr//bin/su`, `./etc/passwd` and `usr/./bin/docker` slip past a deny list as text but normalise straight onto those targets when written; `payload/./usr/bin/x` and `payload/usr/bin/x` are the same file twice and two hashed entries, so an image tag and the image contents start describing each other separately.

### 5.3 The payload destination deny list

`checkPayloadPath` layers one more check over the relative-path rule, returns `payload path lands on a denied target: <path>` on a hit, and passes the section 5.2 relative-path reasons straight through. Both reach the problem list with the ` (entry <archive path>)` tail appended. The list is `DENIED_PAYLOAD_TARGETS`:

| Entry | Match |
| --- | --- |
| `etc/` | segment-aligned prefix |
| `root/` | segment-aligned prefix |
| `usr/bin/docker` | whole path equality |
| `usr/local/bin/docker` | whole path equality |
| `bin/su` | whole path equality |
| `usr/bin/su` | whole path equality |
| `sbin/` | segment-aligned prefix |
| `usr/sbin/` | segment-aligned prefix |
| `var/run/docker.sock` | whole path equality |

This is defence in depth, not a sandbox: whoever can put a file in the package directory already decides what runs as root in every agent container.

`extractTree` re-runs this check on the way out, together with a normalisation check against the target root itself. An archive that slipped past registration validation is still refused when unpacked, as `<reason> — refusing to extract <entry>`.

### 5.4 Payload entry shape

- Symbolic link: `<entry> is a symbolic link — extraction writes file content, never links, so it would arrive as a text file holding a path`
- setuid / setgid / sticky bits: `<entry> carries setuid/setgid/sticky bits (mode <octal>)`, where the octal comes from `Integer.toOctalString(mode)` and includes the type bits.
- Permission bits must genuinely be there. Payload entries the zip central directory recorded no unix mode for are listed as: `<up to the first 5 paths, comma separated> carry no unix mode, so they would land 0644 and fail as "permission denied" — repack on a filesystem that stores permissions (zip -X, or the platform packer)`; past five the list ends with `, … (N total)`.
- The extra line is `no payload file carries an execute bit — nothing the check command could run would start`, and its condition is `offenders.size < files.size && files.none { it.isExecutable }`: the no-mode entries have to leave at least one payload file unaccounted for, and no file under `payload/` may carry an execute bit. When the no-mode entries cover the whole payload, the line above already says all there is to say and this one is not added.

"Executable" means any of the three x bits is set. The modes used by both the digest and extraction come from `ZipCentralDirectoryModes` walking the central directory's external attributes directly; a package whose two views disagree is refused by the two lines above rather than being handed a guessed 0755.

### 5.5 Size and count budgets

| Budget | Value | Refusal string |
| --- | --- | --- |
| manifest bytes | 65536 | `plugin.yaml is over the 65536 limit — the manifest describes a CLI, it is not where a package parks data` |
| skill bytes | 1048576 | `skill/SKILL.md is over the 1048576 limit — a skill teaches one CLI, and past this size it is documentation that belongs next to the binary` |
| per-asset bytes | 524288 | `<entry> is <N> bytes, over the 524288 per-asset limit` |
| asset total bytes | 4194304 | `skill assets exceed the 4194304 byte budget (<entry> is the one that tipped it)` |
| payload file count | 2000 | `payload has <N> files, over the 2000 limit` |
| payload unpacked bytes | 536870912 | `payload unpacks to <N> bytes, over the 536870912 limit` |
| `deps.apt` entries | 50 | `plugin.yaml deps.apt lists <N> packages, over the 50 limit` |

The manifest, the skill and the assets are read through `readBounded`: the size the central directory claims is whatever the packer wrote and is `-1` for a streamed entry, so a cap can only be enforced by stopping mid-read. An asset entry that trips it has its own line, `<entry> ships more than the 524288 per-asset limit`. The payload total is summed from the central directory's claimed sizes.

### 5.6 Archive layout

Nothing may sit outside the three documented locations. When something does:

`unexpected entry outside plugin.yaml, skill/ and payload/: <first 5>` (plural `unexpected entries` past one, and ` (+N more)` past five)

When the archive carries a name twice:

`<name> appears more than once in the archive — one name has to mean one entry, because the digests hash every copy while reading answers with the first and extraction keeps the last`

Loose files would be silently ignored (so the author keeps shipping them) and directories such as `__MACOSX/` would enter the digest and turn the object key into a different value, so both are refused outright.

### 5.7 All reasons at once

Validation does not stop at the first problem. The one case that ends the whole check on the spot is an archive that cannot be opened — `<file>: unreadable package (<cause>)` is thrown directly. A wrong file suffix only makes `checkFileName` return early; the manifest, skill and `payload/` checks still run. Everything accumulates and is thrown once:

```text
<file>: refused with <N> problem(s):
  - <reason 1>
  - <reason 2>
```

Work down the list, fix, and run again to see what is left. When the manifest cannot be read, the line in the list is the one `readManifest` records before handing back nothing — typically `no plugin.yaml at the archive root — a package without a manifest cannot be registered`; an oversize, blank, non-mapping or unparsable manifest each contributes its own line (sections 5.5 and 13). `<file>: plugin.yaml is missing` is never what the author sees: every path that returns nothing has already added an issue, and a non-empty list is thrown before that line is reached.

## 6. Writing `skill/SKILL.md`

`skill/SKILL.md` is mandatory: `no skill/SKILL.md — a CLI ships its own skill, and one without it leaves the agent with a binary it has been told nothing about`. Present but blank gives `skill/SKILL.md is empty`.

### 6.1 Frontmatter

The skill description is read from this markdown's frontmatter by `SkillFileParser.parseMeta`, shared with the skill loaders. A `name` that exists and differs from `plugin.yaml`'s `name` is a refusal:

`skill/SKILL.md declares name "<skillName>" while plugin.yaml declares "<pluginName>" — the skill takes the CLI's name, so remove the frontmatter name or fix it`

The recommended form is to omit `name` so the skill inherits the CLI name. Write a `description`: it becomes the skill row's description column.

### 6.2 The four things the body must carry

The body reaches every bound agent's prompt, so write it so it can be followed literally, in four blocks:

1. what problem this CLI solves, one sentence;
2. where it is installed and what the command is called, with the executable path inside the container;
3. the shape of the common commands, each as one complete command line that can be copied;
4. what to look at when it fails: the separate tells for authentication, network and argument problems.

### 6.3 A CLI with a very large command surface: index-style writing

Past a hundred commands, do not enumerate. Put a short section of complete high-frequency examples in the body and, for the rest, group by topic and give "command name + one line of purpose" so the model fetches `--help` when it needs one. A full command inventory belongs in a resource file, whose path the body names.

### 6.4 `skill/assets/`

Keys are the paths with `skill/assets/` stripped, values are text, and the whole map is stored as the skill's resources. Limits: 524288 bytes per asset, 4194304 in total, UTF-8 text only or `<entry> is not UTF-8 text — a skill's resources ship as text, binaries belong in payload/`; a key must pass `checkRelativePath`, and a failure reports `<reason> (entry <path>) — a skill's resource keys are the paths they are written to, so one has to name exactly one location`.

## 7. Parameters and credentials: `envParams` or `runtimeEnv`

The two blocks solve two different problems, and picking wrong is what puts a credential inside a distributed zip.

### 7.1 `envParams`: declaring "which environment variables this CLI needs"

Each item is a mapping with `envParamName` (required, matching `^[A-Za-z_][A-Za-z0-9_]{0,63}$`), `description`, `required`, `secret`, `defaultValue`. It is a declaration only: it tells whoever configures an agent what has to be filled in.

- an entry that is not a mapping: `plugin.yaml envParams entries must be mappings, found <item>`
- missing `envParamName`: `plugin.yaml envParams entry is missing envParamName`
- a name that does not fit: `plugin.yaml envParams name "<paramName>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$`
- one name declared twice: `plugin.yaml envParams declares <[names]> more than once`
- `secret: true` together with a non-blank `defaultValue`: `plugin.yaml envParams "<paramName>" is marked secret and carries a defaultValue — a package is distributed as a plain zip, so credentials belong in env-var bindings, not here`

Values are entered on the agent and stored as that agent's snapshot in `agent_cli_binding.env_bindings`. The agent form renders one row per declared parameter and every input starts empty — a package `defaultValue` is not pre-filled into the form, so "nobody has typed this yet" stays visible.

What happens at delivery is a different rule: values the agent typed win, keys the agent left unfilled are topped up from the declared `defaultValue`, and a key with nothing on either side is not sent at all (an absent variable and `TOKEN=` are two states for a CLI that decides whether it is configured by looking for the name). Required-field validation therefore only counts a field missing when it has neither a typed value nor a default.

To express a suggested value inside a package, writing it in the `SKILL.md` body is clearer than putting it in `defaultValue`: the latter takes effect silently, the former is seen and explained by the model.

A non-secret `defaultValue` is stored in plaintext; a secret one goes through the encryptor. When the page reads it back, a secret value is masked to its first 3 and last 4 characters, and becomes `******` when the plaintext is 7 characters or shorter or cannot be decrypted.

### 7.2 `runtimeEnv`: declaring "what the platform injects when the container is created"

This is a `name -> value` mapping. Keys must match `^[A-Za-z_][A-Za-z0-9_]{0,63}$`. A value has exactly two legal shapes: a literal string, or a platform slot placeholder that is the whole value, `${...}`. The platform publishes two slots: `platform.adminUrl` and `platform.internalToken`.

- a key that does not fit: `plugin.yaml runtimeEnv key "<key>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$`
- a value that is not a string: `plugin.yaml runtimeEnv <name> must be a string, found <value>`
- a blank value: `plugin.yaml runtimeEnv <name> has an empty value`
- a placeholder embedded in a longer value: `plugin.yaml runtimeEnv <name> writes "<value>" — a placeholder has to be the whole value, as in <name>: ${platform.adminUrl}`
- a slot outside the supported set: `plugin.yaml runtimeEnv <name> asks for <value>, which the platform does not publish (supported: platform.adminUrl, platform.internalToken)`

Injection happens when the container is created and takes no part in the image, so editing `runtimeEnv` does not change the image fingerprint. Slot values come from the deployment; when a deployment publishes no such slot the variable is left unset entirely and the agent log carries a warning naming the slot.

When the same variable name is also covered by an agent-level binding, the binding wins: bindings are merged after `runtimeEnv`.

### 7.3 How to choose

Credentials → bind them on the agent; the declaration in the package carries `secret: true` and no default. The platform's own URL and internal token → `runtimeEnv` with a slot. Never hard-code a URL into a package: it makes one package unusable on another deployment. A fixed constant (a log level, say) → `runtimeEnv` with a literal. A value that differs per agent → `envParams` plus an agent binding.

## 8. `checkCommand`: the only acceptance hook

`checkCommand` is required and non-blank; its absence reports `plugin.yaml: checkCommand is required`.

Once the image is built the platform runs `docker run --rm --entrypoint /bin/sh <tag> -c "<checkCommand>"` once. Any non-zero exit removes the freshly built image with `docker rmi -f` and makes this image resolution throw, with the last 500 characters of the command's output appended.

It is not an input to `payloadDigest` and does not by itself decide the image tag: the tag hashes `cliId`, `version`, `payloadDigest` and the base image name. So editing only `checkCommand` changes `packageDigest` (the zip bytes changed), which adds one archive to the object store and updates `cli.check_command`, while running containers and their images are untouched; the next build is the one that uses the new command.

Four practical constraints on what you write:

1. It is the argument of `/bin/sh -c`, so a basic probe fits best: `<binary> --version`, `<binary> --help`, `command -v <binary>`.
2. Assume no network. Do not put a command there that needs credentials or egress.
3. It runs after `deps.apt`, so it may rely on what apt installed.
4. One image is built for one agent's whole CLI set and every CLI in it gets a check; one failure leaves that set without an image, together with the other CLIs in it.

## 9. Packing

### 9.1 The smallest possible package

```text
mycli-1.4.0.harnaxcli.zip
├── plugin.yaml
├── skill/
│   └── SKILL.md
└── payload/
    └── usr/local/bin/mycli
```

`plugin.yaml`:

```yaml
name: mycli
version: 1.4.0
description: One-line description shown on the CLI page
checkCommand: mycli --version
deps:
  apt:
    - ca-certificates
envParams:
  - envParamName: MYCLI_TOKEN
    description: Token for the mycli service
    required: true
    secret: true
runtimeEnv:
  MYCLI_URL: ${platform.adminUrl}
  MYCLI_LOG: info
```

### 9.2 Packing steps

```bash
cd mycli-1.4.0-src
chmod 755 payload/usr/local/bin/mycli
zip -X -r ../mycli-1.4.0.harnaxcli.zip plugin.yaml skill payload
```

`-X` writes no extra fields and, on most platforms, preserves permission bits. To confirm the bits really reached the central directory, run `zipinfo -v` and look for an octal mode in the external attributes field; packing on Windows typically does not get them, and such a package is refused by the two rules in section 5.4.

On macOS, Finder compression injects `__MACOSX/`; use command-line `zip` rather than Finder, or strip it afterwards.

### 9.3 Self-check before packing

```bash
# layout: only plugin.yaml / skill/ / payload/ at the top level
unzip -Z1 mycli-1.4.0.harnaxcli.zip | sed 's#/.*##' | sort -u
# entry inventory
zipinfo -1 mycli-1.4.0.harnaxcli.zip | grep '^payload/'
# names appearing twice
unzip -Z1 mycli-1.4.0.harnaxcli.zip | sort | uniq -d
```

## 10. Putting a package on the shelf and taking it off

**Publish**: put the `.harnaxcli.zip` into admin's configured package directory (`harnax.cli.package-dir`; in a containerised deployment that is the shelf directory) and restart admin to trigger one sync. On completion startup prints `Sync complete: N registered, M failed`.

**Upgrade**: put the newer file into the same directory. With two same-named packages present, the one with the higher manifest version is registered and the other is skipped with an ERROR line. Versions are split on `[._+-]` and numeric segments compare numerically, so `1.0.10` is newer than `1.0.9`; a pre-release suffix is just another segment, so `1.0.0-rc1` ranks after `1.0.0`. Two files with the same name and the same version register neither, and that also makes the prune of the same pass skip entirely.

**Unpublish**: remove the file from the directory and restart admin. The platform hard-deletes the `cli` row, logically deletes the skill it shipped following the usual skill-delete convention (together with the agent and team bindings on it), and removes the archive from the object store. Three brakes stop the prune from running: a package failed to parse during this pass; the stale count reaches the registered count; the row's `package_digest` is empty (a row the packages did not write is not touched by the prune).

**Disable temporarily (kill switch)**: flip the status on the page. This is the one write path that survives a restart — `status` is not overwritten by registration, and the shipped skill's status follows it. Disabling does not require unbinding agents first; the blast radius is measured before the switch is thrown through the `related-agents` and `related-sessions` read-only routes.

## 11. Pre-publication checklist

- [ ] file name = `<name>-<version>.harnaxcli.zip`, and `<name>` matches `plugin.yaml`'s `name` character for character
- [ ] `name` starts with a lowercase letter and contains only lowercase letters, digits and dashes, length 2–64
- [ ] `version` starts with a letter or digit and contains only alphanumerics plus `.` `_` `+` `-`, total length ≤ 32
- [ ] the top level holds only the seven known keys; no mistyped eighth
- [ ] `skill/SKILL.md` exists, is not blank, and its frontmatter declares no conflicting `name`
- [ ] everything under `skill/assets/` is UTF-8 text
- [ ] `payload/` holds at least one file, and at least one carries an execute bit
- [ ] `payload/` contains no symbolic link, no setuid/setgid/sticky bit, and no destination on the deny list
- [ ] paths are canonical: no `//`, no `./`, no `../`, no `:`
- [ ] no secret entry carries a `defaultValue`
- [ ] every `runtimeEnv` placeholder is a whole value, and every slot name is one of the two supported ones
- [ ] every `deps.apt` entry is a bare package name or `name=version`, with no space, `;`, `$`, backquote, leading `-`
- [ ] the archive was packed on a filesystem that stores permissions
- [ ] the binary is built for the container architecture (Linux), not for your development machine

## 12. Worked examples

### 12.1 The platform's own CLI: `harnax`

`harnax-cli/plugin.yaml` is a minimal example: four required fields plus `runtimeEnv`.

```yaml
name: harnax
version: 1.0.0
description: Harnax 平台管理命令行，在沙箱内以注入的内部令牌读写 Agent、模型、技能、会话等资源
checkCommand: harnax --version
runtimeEnv:
  HARNAX_URL: ${platform.adminUrl}
  HARNAX_TOKEN: ${platform.internalToken}
```

There is no `deps.apt` because the payload is a single CGO-free static binary and the image needs nothing extra at the system layer. What `harnax-cli/Makefile`'s `package` target does: clear the staging directory and the whole `dist/*.harnaxcli.zip` (a zip left behind by an earlier version is a second package declaring the same CLI name), cross-compile per `GOOS`/`GOARCH` with `-X main.version` injected, place `plugin.yaml` and `SKILL.md` at `plugin.yaml` and `skill/SKILL.md`, `chmod 755` the binary, then `zip -X -q -r` the three locations.

`cli-packages/build.sh` then copies the result onto the shelf. The Go CLI itself is 25 `.go` files, with a command surface covering agent / model / skill / session / task / mcp / tool / channel / envvar / apikey / user / tenant / health / config / cli; `harnax-cli/SKILL.md` covers those command groups index-style. The `cli` group is `harnax cli list`, `harnax cli get <id>` and `harnax cli toggle <id>`, and all three address `/api/admin/clis` (`harnax-cli/cmd/cli_resource.go`); the `Short` text of `harnax cli` reads, verbatim: `Inspect CLI plugin packages registered by admin (status is the only writable field)` — status is the one writable field, and there is no create, update or delete command.

### 12.2 A third-party CLI: `lark-cli`

`cli-packages/lark-cli/` on the shelf is a complete third-party source: `plugin.yaml` + `skill/SKILL.md` + `build.sh` + `checksums.txt`. Its `build.sh` fetches `lark-cli-<version>-linux-amd64.tar.gz` from upstream, verifies it against `checksums.txt`, places the extracted binary at `payload/usr/local/bin/` and packs `dist/lark-cli-1.0.96.harnaxcli.zip`.

Its manifest documents three judgements:

- `checkCommand: lark-cli --version`. Upstream uses cobra, which exposes the `--version` flag but has no `version` subcommand, so `lark-cli version` would fail as "unknown command" and leave the whole image unbuildable; `--version` is the least that proves the binary landed with its execute bit intact.
- No `deps.apt`: one static Go binary with no shared-library needs, so the build stays offline.
- `envParams` rather than `runtimeEnv`: `LARKSUITE_CLI_APP_ID` (`required: true`, `secret: false`) and `LARKSUITE_CLI_APP_SECRET` (`required: true`, `secret: true`). The platform publishes no slot for a third-party application, and a package is a plaintext zip, so credentials are always entered on the agent side.

Three things worth copying: the binary is fetched for linux/amd64 (packing your own machine's format yields a package that cannot run in the container, and the failure only becomes visible at the post-build check); the version is carried verbatim from the upstream version string into both `plugin.yaml` and the file name; `checksums.txt` makes "which byte sequence was fetched" readable in the source while `payloadDigest` makes it verifiable on the platform side.

## 13. Refusal string, verbatim, and the fix

| Refusal (verbatim) | Fix |
| --- | --- |
| `file name must end with .harnaxcli.zip` | rename with the `.harnaxcli.zip` suffix |
| `file name must be <name>-<version>.harnaxcli.zip, got "<file>"` | add the version segment |
| `file name "<file>" does not start with the declared name plus a version, as in "<name>-1.4.0.harnaxcli.zip" — rename the file, the declared name is the identity` | rename the file to match the declared name; do not change `name` instead |
| `unreadable package (<cause>)` | check whether the zip is truncated or zero bytes |
| `no plugin.yaml at the archive root — a package without a manifest cannot be registered` | put the manifest at the archive root, not in a subdirectory |
| `plugin.yaml is empty` | write at least the four required fields |
| `plugin.yaml must be a YAML mapping, found <type>` | make the top level a mapping |
| `plugin.yaml is not valid YAML: <problem>` | fix per the problem; duplicate keys surface here |
| `plugin.yaml is over the 65536 limit — the manifest describes a CLI, it is not where a package parks data` | take data out of the manifest |
| `unknown plugin.yaml key(s) <[keys]>: the manifest is the only metadata source, so an unrecognised key is a typo that would otherwise be dropped` | delete it, or correct it to one of the seven known names |
| `plugin.yaml: name is required` | add `name` |
| `name "<n>" must match ^[a-z][a-z0-9-]{1,63}$ (lowercase letters, digits and dashes; it is also the skill name and the file-name stem)` | drop capitals, underscores and dots; start with a lowercase letter |
| `plugin.yaml: version is required` | add `version` |
| `version "<v>" must match ^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$` | drop the leading symbol and any disallowed character |
| `plugin.yaml: description is required` | add `description` |
| `plugin.yaml: checkCommand is required` | add `checkCommand` |
| `plugin.yaml deps has an unsupported section "<k>" — only deps.apt exists` | delete the other sections under `deps` |
| `plugin.yaml deps.apt must be a list of package names` | make each item a bare string, not a nested mapping |
| `plugin.yaml deps.apt lists <N> packages, over the 50 limit` | trim the dependencies |
| `plugin.yaml deps.apt entry "<x>" is not a plain apt package name — it is interpolated into an apt-get command line, so only [a-z0-9+.-] and an optional =version are allowed` | drop spaces, `;`, `$`, backquotes, a leading `-`, `>`/`<` comparison operators |
| `plugin.yaml deps.apt lists <[x]> more than once` | de-duplicate |
| `plugin.yaml envParams entries must be mappings, found <item>` | write each item as a mapping |
| `plugin.yaml envParams entry is missing envParamName` | add `envParamName` |
| `plugin.yaml envParams name "<p>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$` | use a valid shell-variable shape |
| `plugin.yaml envParams declares <[x]> more than once` | de-duplicate |
| `plugin.yaml envParams "<p>" is marked secret and carries a defaultValue — a package is distributed as a plain zip, so credentials belong in env-var bindings, not here` | delete the `defaultValue`, or set `secret: false` |
| `plugin.yaml runtimeEnv key "<key>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$` | fix the key |
| `plugin.yaml runtimeEnv <n> must be a string, found <v>` | quote the value; no nested mapping, no bare number |
| `plugin.yaml runtimeEnv <n> has an empty value` | give a non-blank value or delete the entry |
| `plugin.yaml runtimeEnv <n> writes "<v>" — a placeholder has to be the whole value, as in <name>: ${platform.adminUrl}` | split it into either one literal or one whole slot |
| `plugin.yaml runtimeEnv <n> asks for <v>, which the platform does not publish (supported: platform.adminUrl, platform.internalToken)` | use one of those two slots, or write a literal |
| `no skill/SKILL.md — a CLI ships its own skill, and one without it leaves the agent with a binary it has been told nothing about` | add an instruction sheet, even a minimal four-block one |
| `skill/SKILL.md is over the 1048576 limit — a skill teaches one CLI, and past this size it is documentation that belongs next to the binary` | move long documentation into `payload/` and keep only an index in the body |
| `skill/SKILL.md is empty` | write content |
| `skill/SKILL.md declares name "<s>" while plugin.yaml declares "<p>" — the skill takes the CLI's name, so remove the frontmatter name or fix it` | delete the frontmatter `name` |
| `<reason> (entry <path>) — a skill's resource keys are the paths they are written to, so one has to name exactly one location` | canonicalise the resource key |
| `<entry> is <N> bytes, over the 524288 per-asset limit` | split the file |
| `<entry> ships more than the 524288 per-asset limit` | same (the central directory's claimed size exceeds it) |
| `skill assets exceed the 4194304 byte budget (<entry> is the one that tipped it)` | trim the total asset volume |
| `<entry> is not UTF-8 text — a skill's resources ship as text, binaries belong in payload/` | move the binary to `payload/` |
| `no files under payload/ — the payload is what the image copies in, a CLI with nothing to install does not need a package` | if nothing is installed, do not make a package |
| `payload has <N> files, over the 2000 limit` | ship only necessary files |
| `payload unpacks to <N> bytes, over the 536870912 limit` | past 512 MiB the package should not carry it wholesale |
| `payload path lands on a denied target: <path> (entry <archive path>)` | choose another destination; `usr/local/bin/` is the default |
| `path escapes its root: <path> (entry <archive path>)` | remove the `../` segment |
| `path is not in canonical form: <path> (entry <archive path>)` | remove the `.` segment |
| `path has an empty segment: <path> (entry <archive path>)` | remove the doubled or trailing slash |
| `path must be relative: <path> (entry <archive path>)` | remove the leading `/` |
| `path contains a colon: <path> (entry <archive path>)` | drop the colon |
| `empty path (entry <archive path>)` | the entry name is empty once the prefix is stripped; check the depth under `payload/` |
| `<entry> is a symbolic link — extraction writes file content, never links, so it would arrive as a text file holding a path` | ship the link target as a real file |
| `<entry> carries setuid/setgid/sticky bits (mode <octal>)` | `chmod 755`; anything needing elevation belongs to the deployment, not to the package |
| `<list> carry no unix mode, so they would land 0644 and fail as "permission denied" — repack on a filesystem that stores permissions (zip -X, or the platform packer)` | repack with `zip -X` on Linux or macOS |
| `no payload file carries an execute bit — nothing the check command could run would start` | `chmod` the main binary and repack |
| `unexpected entr(y\|ies) outside plugin.yaml, skill/ and payload/: <list>` | strip the extra entries (usually `__MACOSX/`, `.DS_Store`, `README.md`) |
| `<name> appears more than once in the archive — one name has to mean one entry, because the digests hash every copy while reading answers with the first and extraction keeps the last` | repack so each name occurs once |

## 14. Boundaries and known traps

- **A package is not a container.** `payload/` can only copy files; there is no "run something at build time". System-level preparation goes through `deps.apt`, and a CLI that needs more than apt should not ship as a package.
- **One image = one agent's CLI set.** The set is sorted by `cliId` and hashed together with the base image name into the tag. Adding one CLI to an agent builds a new image, and every CLI's `checkCommand` in that set has to pass.
- **Editing `SKILL.md` does not rebuild an image.** `payloadDigest` looks only at payload and apt, so a wording change updates the prompt while running containers keep their image.
- **Editing `version` does change the image tag.** `version` is tag material, so bumping the version alone (with byte-identical payload) produces a new tag and a new build.
- **An unpublished `runtimeEnv` slot leaves the variable unset, not empty.** The CLI reports its own error; the platform does not paper over it.
- **Name ambiguity freezes the prune.** Two files with the same name and version make the whole prune pass skip, so taking a file off the shelf does not unregister it while the ambiguity stands.
- **`status` is the only column that survives a restart.** Every other column is overwritten from the package on each startup, so "edit a field on the page" has no corresponding path — the page can read and disable, nothing else.
- **An empty shelf is not "nothing happened".** With the directory present but holding no package, the log prints one WARN line and the prune runs, deleting registered rows.
- **`deps.apt` is sorted and de-duplicated again before being joined into `apt-get`.** Order inside the package affects neither the command line nor the digest (which uses the sorted list).
- **The platform does not probe architectures.** A binary for your machine's architecture in a package whose container needs another is not caught at registration; the failure lands on the post-build `checkCommand`.
- **`skill/assets/` holds text only.** There is no route for an image or similar resource: skill resources are stored as strings and non-UTF-8 content is refused outright.

## 15. Key file index

| Path | Content |
| --- | --- |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParser.kt` | every validation rule and refusal string; reading `plugin.yaml` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageLayout.kt` | layout constants, the four patterns, the payload deny list, path canonicalisation, both digest algorithms |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageArchive.kt` | read-only archive view: entry modes, symlink and special-bit tests, `readBounded`, `extractTree` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/ZipCentralDirectoryModes.kt` | unix mode from the zip central directory's external attributes |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParserTest.kt` | a case per refusal string |
| `harnax-cli/plugin.yaml` | the platform's own CLI manifest |
| `harnax-cli/Makefile` | the `package` target: cross-compile and self-pack |
| `harnax-cli/SKILL.md` | index-style instruction sheet sample |
| `cli-packages/lark-cli/plugin.yaml` | a complete third-party manifest |
| `cli-packages/lark-cli/skill/SKILL.md` | a third-party instruction sheet |
| `cli-packages/lark-cli/build.sh` | fetch the upstream binary, verify digests, pack |
| `cli-packages/lark-cli/checksums.txt` | digests of the upstream artifact |
| `cli-packages/build.sh` | shelf build: never clear the shelf, one package per name |

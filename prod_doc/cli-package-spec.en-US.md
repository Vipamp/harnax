# Harnax CLI Plugin Package Specification

> Chinese version: [cli-package-spec.zh-CN.md](./cli-package-spec.zh-CN.md)
>
> The platform-side path (registration, MinIO, image building, delivery, pages and API, design decisions and boundaries) is in [cli-plugin-package-design.en-US.md](./cli-plugin-package-design.en-US.md).
>
> This document is **the contract for package authors**: character sets, limits, the refusal list, how to write things, packaging and going on the shelf — item by item, each one maps to code. The source of truth is `harnax-common/.../cli/CliPackageLayout.kt` and `harnax-admin/.../registrar/CliPackageParser.kt`. Change those and this file must be updated to match.

## 0. What this document answers

Three kinds of reader, three questions:

| Who you are | What you want to do | Which section to read |
|---|---|---|
| You already have a CLI (a binary built from Go / Rust / Node) and want agents to use it | Turn the upstream binary into a harnax package | §2, §4, §9, §13 |
| You are building a CLI of your own as product capability | Follow the spec from the very first commit | §4~§8, §11 |
| Ops: the package is in the directory but the page shows nothing | Find the refusal reason | §10, §13, §14 |

The scope ends at "a `.harnaxcli.zip` has been built and put on the package directory". What the platform does after the package is placed is the design document's business.

## 1. The model in one line

**One package = one binary payload (`payload/`) + one skill document (`skill/SKILL.md`), and both must be present.**

- `payload/` decides **capability**: whether the executable exists in the container;
- `skill/SKILL.md` decides **awareness**: whether the model knows which command to type and how to pass the arguments.

A package that ships only a binary does not exist as far as the model is concerned — it does not know the command name, and it does not know your CLI's conventions. The skill document is therefore not optional, and the validator refuses a package with no `SKILL.md` outright.

A package carries exactly **one** skill document, and its skill name is forced to equal the CLI name (§6.1). A CLI with a large command surface is handled by writing the document as an index — see §6.3.

## 2. Package layout and file name

```
lark-cli-1.0.96.harnaxcli.zip
├── plugin.yaml                    # the only metadata source — the platform reads no configuration from anywhere else
├── skill/
│   ├── SKILL.md                   # required
│   └── assets/<path>              # optional (the runtime does not yet promise to deliver it to the container, §14-5)
└── payload/                       # the file tree that goes into the image
    └── usr/local/bin/lark-cli     # relative path = absolute path inside the container
```

Those three locations and nothing else. A fourth one in the package (`README.md`, `__MACOSX/`, `.DS_Store`, `deps/`…) is always refused — a stray entry is either silently ignored (so the author keeps carrying it forever) or it pollutes the digest.

Two file-name rules, both checked:

1. the suffix must be `.harnaxcli.zip`, and the name left after stripping it must contain a version segment separated by `-`;
2. the file name must start with the `name` declared in `plugin.yaml` followed by one `-`.

Rule 2 is a backstop for packaging scripts: a wrong name means publishing under another CLI's identity, and the package directory gives no clue that this happened. The version segment only matters to the human eye — the platform never compares it against the manifest's `version`. **The registration identity always comes from the manifest.**

## 3. The three things the platform does to a package (consequences an author must know)

1. **Read only, never execute**: registration runs nothing from the package. `checkCommand` also runs only **inside the newly built image**, never on the host.
2. **Two digests**: `packageDigest = sha256(whole zip file)` is the package's identity — it decides the MinIO object key and whether re-registration is needed; `payloadDigest` covers only the `payload/` file tree and `deps.apt`, and it decides the image tag. Editing the skill document does not rebuild the image; replacing the binary does.
3. **Overwrite by `name`**: one `name` has one row. A new version overwrites that same row; versions do not coexist.

## 4. `plugin.yaml` fields

Seven keys, not one more:

| Field | Required | Shape | Meaning |
|---|---|---|---|
| `name` | yes | `^[a-z][a-z0-9-]{1,63}$` | Unique platform-wide, and it is the identity; it is also the skill name and the file-name prefix |
| `version` | yes | `^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$` | Not part of uniqueness; when several packages share a name it decides which one goes on the shelf; it ends up in the generated Dockerfile, which is why the character class excludes newlines |
| `description` | yes | non-empty text | One line for administrators, shown on the CLI page and in the agent configuration |
| `checkCommand` | yes | non-empty text | Executed inside the new image after the build; non-zero voids the whole image. The author's only acceptance hook — see §8 |
| `deps.apt` | no | list of strings, ≤50 entries, no duplicates | Per-entry shape `^[a-z0-9][a-z0-9+.-]{0,62}(=[0-9A-Za-z.+-]{1,32})?$`; empty means the build needs no network |
| `envParams` | no | list of objects, `envParamName` must not repeat | Env declarations an administrator fills in on each agent — see §7 |
| `runtimeEnv` | no | key → value | Fixed env injected by the platform at the moment the container is created — see §7 |

**There is no `install` section, and no field that accepts a script.** The platform does two things with a package and nothing else: lay `payload/` down on the matching paths of the image, and install system packages per `deps.apt`. You may not assume the container has network access, and you may not make it run your own install logic.

Three kinds of strictness that people trip over:

- An unrecognised key ⇒ refused. Dropping it silently would let a package that believes it configured something ship without it configured.
- The same key written twice ⇒ refused (the YAML parser rejects duplicate keys; the later one does not overwrite the earlier one).
- Under `deps`, only `apt` is recognised; `deps.pip` is refused, not ignored.

`version` ordering semantics: **segment-by-segment numeric comparison**, so `1.0.10` is newer than `1.0.9`. A pre-release suffix is just one more text segment here, so `1.0.0-rc1` is judged newer than `1.0.0`. The judgement only answers "of two files under one name, which one goes on the shelf", so this inversion is acceptable.

## 5. Hard constraints: what gets refused

### 5.1 Every path must have exactly one spelling

Every name under `payload/` and `skill/assets/` must be a normalised relative path. All of the following are refused:

- absolute paths (`/usr/bin/x`);
- empty segments (`usr//bin/x`);
- `.` segments (`./etc/x`, `usr/./bin/x`);
- `..` segments (`../x`);
- a segment containing a colon (`bin/a:b`) or a NUL byte;
- an empty name.

The requirement exists because two separate pieces of code see the same path: the registration side matches the refusal list against the literal text, the runtime side joins the path onto a target directory and normalises afterwards. A path like `usr/./bin/docker` passes as text but lands on the refusal list once it is resolved, so only one of the two sides would ever catch it. The same invariant also protects the digest — `payload/./usr/bin/x` and `payload/usr/bin/x` are two spellings of one file; if both entered the fingerprint, the image tag would no longer describe what the image contains.

### 5.2 Refusal list for target locations

A target path in `payload/` may not land on any of these positions (compared **by path segment**):

| Entry | How it matches | Example that hits | Example that does not |
|---|---|---|---|
| `etc/` | Directory prefix | `etc/passwd` | `etc2/x` |
| `root/` | Directory prefix | `root/.ssh/authorized_keys` | `rootfs/app` |
| `sbin/`, `usr/sbin/` | Directory prefix | `sbin/x` | — |
| `usr/bin/docker`, `usr/local/bin/docker` | Full path | `usr/bin/docker` | `usr/bin/docker-compose` |
| `bin/su`, `usr/bin/su` | Full path | `usr/bin/su` | `usr/bin/su-client` |
| `var/run/docker.sock` | Full path | — | — |

This is one layer of defence in depth, not a sandbox: anyone who can put a file into the package directory can already decide what files land as root inside every agent sandbox. The list stops honest mistakes and part of the overreach.

Install your CLI under `usr/local/bin/` (or under its own `usr/local/lib/<name>/`).

### 5.3 Entry shape

- Symbolic link ⇒ refused (unpacking writes file content only, so a link turns into a file holding path text);
- setuid / setgid / sticky bits ⇒ refused;
- The same name appearing twice in the package ⇒ refused (the digest counts every copy, while reading takes the first one and unpacking keeps the last one);
- No file at all under `payload/` ⇒ refused (a package with nothing to install is not needed);
- Not a single execute bit in the whole payload ⇒ refused (this package cannot install any command).

### 5.4 Permission bits come from the packer

The execute bit travels in the unix mode of the zip central directory entry, and the platform **neither rewrites nor restores it**. Therefore:

```bash
chmod 755 stage/payload/usr/local/bin/mycli   # chmod first
(cd stage && zip -X -q -r ../mycli-1.0.0.harnaxcli.zip plugin.yaml skill payload)   # then pack
```

When the packer wrote no mode at all, registration is refused rather than quietly unpacking the binary as `0644` — the latter only surfaces during the image build, as `checkCommand` exiting 126 (Permission denied), and attributing it costs far more. Packages built with Windows-side tools or some archive libraries trip over this most often.

### 5.5 Size and count limits

| Object | Limit | Consequence when exceeded |
|---|---|---|
| `plugin.yaml` | 64 KB | Refused |
| `skill/SKILL.md` | 1 MB | Refused |
| A single `skill/assets/` file | 512 KB | Refused |
| `skill/assets/` in total | 4 MB | Refused |
| Number of payload files | 2000 | Refused |
| Payload bytes after unpacking | 512 MB | Refused |
| Number of `deps.apt` entries | 50 | Refused |

For the text limits the length is accumulated while reading; the size recorded in the central directory is not used as evidence (the packer fills it itself, and for streamed writes it is simply `-1`).

### 5.6 Everything is reported at once

The validator does not stop at the first problem: a package with 5 issues produces 5 lines in the log. Fixing them all before restarting is faster than iterating one round at a time.

## 6. How to write `skill/SKILL.md`

### 6.1 Frontmatter

```yaml
---
name: lark-cli
description: 用 lark-cli 命令行读写飞书 / Lark 开放平台资源……当用户要操作飞书里的对象时使用。
---
```

- `name` **must equal** the `name` in `plugin.yaml`, otherwise the package is refused. One skill row cannot answer to two identities at once;
- `description` decides that line in the skill list, and it is the model's first signal for "should I use this CLI". State clearly **what objects it can operate on** and **in which situations it should be used**;
- With no frontmatter `name`, the skill name falls back to the CLI name — so the laziest correct form is simply to omit `name`.

### 6.2 The four elements of the body

A skill document that works answers these four things; writing them in this order costs the least:

1. **When to use it**: one sentence naming the classes of objects this CLI covers;
2. **How to write commands**: 2~4 commands the reader can **copy directly**, showing how the arguments are given;
3. **Credentials and prerequisites**: which environment variables are needed (matching `envParams` in §7 one to one), whether a login is required, and how to confirm that you are usable (a self-check command such as `whoami`);
4. **Boundaries and how to read failures**: which errors an agent cannot fix itself (permission not granted, quota, platform-side configuration) and whom to report them to.

The model reads only this one file that you wrote. Where you are vague, it will guess, and a wrong guess shows up as "command not found" or "bad argument".

### 6.3 A CLI with a large command surface: write it as an index

One package carries one skill document, but some CLIs have dozens of domains and hundreds of commands. Do not stuff them all into `SKILL.md` — you hit the 1 MB limit, and the model cannot read it anyway.

The form is: **`SKILL.md` is the map; the depth stays inside the CLI**. The lark-cli package does exactly this (§12): the body covers only how to choose between options, risk levels and output control, and then points at one self-service command:

```bash
lark-cli skills list                 # which domain guides exist
lark-cli skills read lark-calendar   # read the full usage of one domain
```

There is a side benefit: the domain guides ship with the binary, so they can never drift away from the documentation site.

The precondition is that the target CLI itself offers a self-service help / documentation subcommand. If it does not, either keep the body to the most commonly used 20% of commands, or add such a subcommand upstream first.

### 6.4 `skill/assets/`

You may ship this directory; the path rules are the same as §5.1 (the key is the relative path it is written to). **But the runtime currently does not promise to project these files into the sandbox** (§14-5). So the skill body has to stand on its own — do not point `SKILL.md` at files the container cannot fetch.

## 7. Parameters and credentials: `envParams` or `runtimeEnv`

| | `envParams` | `runtimeEnv` |
|---|---|---|
| Who supplies the value | The administrator, per parameter, on the CLI step of the agent wizard | The platform, at container creation |
| Can the package carry a default | Yes (`defaultValue`), but **not** when `secret: true` | Not applicable |
| Shape of the value | Any string | A literal, or `${platform.<slot>}` with the anchor occupying the whole value |
| Storage | The declaration is stored with the package (a `secret` entry's default is AES-encrypted and masked on the page); for the value: a reference stores only a pointer (the global variable itself is encrypted at rest), a typed-in literal goes into the binding snapshot as plaintext | Plaintext in the database (contains no key material) |
| Typical use | A third-party platform's App ID / Token / endpoint | The CLI needs the platform address and an internal token to work |

The one-line rule: **the value varies per deployment or per tenant ⇒ `envParams`; the value comes from the platform itself ⇒ `runtimeEnv`.** Either way, **never ship a real secret in the package** — a package is a plaintext zip; it goes into object storage and it gets copied.

**Who fills it, and where:** the CLI step of the agent wizard is one card per CLI — press "Add CLI" to get a card, and switch which package it installs from the dropdown inside it. That card holds this package's parameter table, one row per `envParams` declaration. Each row has two ways to supply a value: reference a global environment variable (the page stores the reference; the runtime resolves the value by it), or pick "custom" and type the literal. The two are not equally safe: a global variable is encrypted at rest and the binding keeps only a pointer, while a typed-in literal — as with tools and MCP — lands in the binding snapshot in plaintext and the read API echoes it back, so a `secret: true` parameter should go through a reference. Leaving a row blank is not "an empty string was set" — that declaration is not delivered at all, and the package's `defaultValue` takes over. The same declarations are visible read-only in the "Env Params" column of the CLI Tools page: the cell is a count badge, and hovering opens a small table with one row per parameter — name, description, required, secret and the package default — the same shape as the "Environment Parameters" column on the Tools page. Values cannot be edited there, because **the parameter belongs to the package, the value belongs to the agent**.

**`required: true` is a mandatory question:** when a declaration has no value on this agent and nothing falls back under it, the front end blocks "Finish" and names which CLI and which parameter, and the back end refuses the same thing when it writes the binding. Three things count as filled: a value typed in, a reference to a global environment variable, and a `defaultValue` shipped by the package. So, for whoever builds the package — **a parameter you don't want administrators to miss should be `required: true` with no `defaultValue`; mark it `required: false` and the missing-value error only shows up at runtime, on the CLI.**

Delivery rules (these decide a lot of behaviour):

- Each key is merged separately: a value set on the agent wins, and the rest fall back to the `defaultValue` declared by the package;
- **A declaration with no value at all is not delivered** (no empty string is sent). For a CLI that decides whether it is configured by whether the environment variable exists, "missing" and `TOKEN=` are two different states;
- Env is injected **only at the moment the container is created**. After changing a parameter or swapping a version, a **new session** is required for it to take effect (a kept-alive container is not replaced — see §14-3).

Two hard rules for `runtimeEnv`:

1. The only slots are `platform.adminUrl` and `platform.internalToken`; anything else is refused outright;
2. The anchor must occupy the whole value. `MY_URL: http://x/${platform.adminUrl}` is refused — split it across two variables.

## 8. `checkCommand`: the only acceptance hook

How it is executed and what it costs:

```bash
docker run --rm --entrypoint /bin/sh <new image> -c "<checkCommand>"
```

Any non-zero exit ⇒ `docker rmi -f <new image>` and an exception is thrown. A broken package never reaches the runtime; the price is that this build was wasted.

Three requirements on how to write it:

1. **It must pass without any business credentials.** At build time there are neither the `envParams` an administrator fills in nor the moment to inject `runtimeEnv`. Any form that reads "log in first, then run" makes the image unbuildable forever.
2. **It must actually verify something.** The most common acceptable form is `<cli> --version`: it proves at once that the binary is there, that the execute bit survived, and that the dynamic links are satisfied.
3. **Watch the difference between a subcommand and a flag.** Go/cobra-style CLIs often generate a `--version` flag but no `version` subcommand; writing the latter fails as "unknown command" with exit code 1, and the image is voided with it. harnax's own package No. 1, `harnax --version`, and lark-cli's `lark-cli --version` are both measured results of this rule.

To verify more deeply, pick a subcommand that **needs neither network nor credentials** (`--help`, or a command that lists built-in resources). Exit codes mean: `126` lost the execute bit (back to §5.4), `127` the command name is wrong, `1` is most often how the subcommand was written.

## 9. Packaging

### 9.1 The smallest possible package

For a script tool that only has to exist, this is the entire content:

```
greet-0.1.0.harnaxcli.zip
├── plugin.yaml
├── skill/SKILL.md
└── payload/usr/local/bin/greet      # mode 0755
```

```yaml
name: greet
version: 0.1.0
description: Greeting utility
checkCommand: greet --version
```

`SKILL.md` only has to cover "when to use it, how to write the command, how to pass the arguments" — it is the model's only source of information.

### 9.2 Packaging steps

```bash
NAME=lark-cli VERSION=1.0.96 SHELF=../dist
rm -rf stage && mkdir -p stage/skill stage/payload/usr/local/bin
cp plugin.yaml stage/
cp SKILL.md stage/skill/SKILL.md
cp bin/lark-cli stage/payload/usr/local/bin/lark-cli
chmod 755 stage/payload/usr/local/bin/lark-cli
(cd stage && zip -X -q -r $SHELF/${NAME}-${VERSION}.harnaxcli.zip plugin.yaml skill payload)
```

Each of the three actions has a reason:

- `chmod 755` before `zip`: the execute bit travels in the central directory (§5.4);
- `zip -X`: keeps extra fields out of the package, so the same content packs into the same package every time;
- add only those three entries: a fourth location is refused (§2).

**A package script clears nothing from the shelf, not even its own older versions.** An old version lying next to the new one is exactly "two files under one name" — but that is also what it looks like when someone hand-drops an upgrade, and `rm` by name would take that file with it, which is a silent rollback. The arbitration lives in `cli-packages/build.sh`: the package with the higher `version` stays, the loser moves to `cli-packages/dist/.superseded/` (§10).

The repository has three scripts you can copy directly — one builds the whole shelf, two pack a single package:

| Situation | Location | Characteristics |
|---|---|---|
| The shelf: top up every package that has a source | `cli-packages/build.sh` | Never clears the shelf (a hand-dropped package stays), runs `harnax-cli`'s `make package` plus each `cli-packages/<name>/build.sh`, then moves the same-name losers into `.superseded/` |
| A CLI of your own, source in the same repository | The `package` target in `harnax-cli/Makefile` | `CGO_ENABLED=0 GOOS=linux GOARCH=amd64` cross-compile + the version number taken from `plugin.yaml` |
| A third-party CLI, the binary upstream | `cli-packages/lark-cli/build.sh` | Downloaded at packaging time, verified against a pinned sha256, mirror source first |

`packageDigest` is sensitive to timestamps: `zip` writes each entry's mtime into the package, so repacking the same content produces a **new** `packageDigest` every time (`payloadDigest` is unchanged, so no extra image is built). This is a known operational debt (§14-4), not a bug.

## 10. Going on the shelf and retiring

**Going on the shelf = drop the package into the shelf** `cli-packages/dist/`, then run packaging and deployment once more. Registration happens only at admin startup, and there is no "rescan once" endpoint:

```bash
cp mycli-1.2.0.harnaxcli.zip cli-packages/dist/                                   # onto the shelf
cp cli-packages/dist/*.harnaxcli.zip docker-new/dist/cli-packages/                # into the build input
docker compose -f docker-new/docker-compose.yml up -d --force-recreate admin
```

The last two lines are already chained by `docker-new/build.sh`, `deploy-all.sh` and `deploy-service.sh admin` (each runs `cli-packages/build.sh` first, then copies the shelf wholesale), so the only manual act is the drop plus one deployment. **The shelf is never cleared**: `cli-packages/build.sh` tops up the packages that have a source (`harnax-cli` and `cli-packages/<name>/`) and leaves a hand-dropped package exactly as it is, so it ships with the rest — that is what the drop path is for.

docker-new covers the container path `/home/harnax/cli-packages` with a **read-only bind mount** of the host's `docker-new/dist/cli-packages/`, so what the container sees is that copy: the "the image holds the new package, the running one the old" desync is gone, and so is `docker cp` — the mount is read-only, so copying into the container is refused outright.

**Never empty either shelf copy**: `cli-packages/dist/` (where you drop) and `docker-new/dist/cli-packages/` (the build input) carry the same consequence if emptied — and a missing directory is created empty by Docker on the spot — because admin reading an empty shelf reports every CLI on it as retired. The three brakes under **Retiring** below stop most accidents like this, but they are a backstop, not a safety net.

**Seeing the result**: `docker logs harnax-admin | grep CliPackageAutoRegistrar`. The page will not tell you why a package did not appear — wrong directory, broken manifest, both look like "no new row appeared" on the page.

**Several packages under one name**: the shelf keeps one package per CLI name — the one with the highest numeric `version` — and `cli-packages/build.sh` moves the loser into `cli-packages/dist/.superseded/` (moved, not deleted: a hand-dropped package has no source to rebuild it). Two packages with the same name *and* the same version fail the build outright, because neither side can tell you which one to keep; what admin does when such a pair reaches it past the scripts is register neither and count both as failures.

**Retiring**: remove the package from both shelves — `docker-new/dist/cli-packages/` and the shelf `cli-packages/dist/` (a package that has a source also needs its `cli-packages/<name>/` directory gone, otherwise the next build puts it back on the shelf) — then restart admin. The consequence cascades — the `cli` row is hard-deleted, the three binding tables are cleaned out, the skill row it shipped is soft-deleted, and a WARN lists the package names and the row ids. Three brakes protect you:

1. If any package failed to be read in this round, the cleanup does not run at all (there is no trustworthy on-shelf set at that point);
2. Only rows whose `package_digest` is non-empty are candidates (only what the registrar wrote itself may be deleted by it);
3. **If the number to delete is not smaller than the number that remains, refuse to execute and log an ERROR**.

The third brake bites ops when the package directory is small: with only 2 packages on the shelf, deleting 1 lands exactly on the refusing side of the ratio. To retire legitimately, the packages that stay must outnumber the ones to delete.

## 11. Pre-release self-check list

- [ ] The file name is `<name>-<version>.harnaxcli.zip` and its prefix equals the manifest's `name`;
- [ ] The package contains only `plugin.yaml`, `skill/` and `payload/` — no fourth thing;
- [ ] The manifest uses only those seven keys, has no duplicate key, and all four required fields are non-empty;
- [ ] The relative paths from `unzip -Z1 pkg.zip | grep '^payload/'` are the container paths you want, and not one of them lands on the §5.2 list;
- [ ] The first column of `zipinfo pkg.zip 'payload/usr/local/bin/*'` is `-rwxr-xr-x`, not `-rw-r--r--`;
- [ ] The payload has at least one execute bit, no symbolic links, no setuid;
- [ ] The frontmatter `name` in `skill/SKILL.md` equals the CLI name;
- [ ] Every environment variable mentioned in `SKILL.md` is declared in `envParams` or `runtimeEnv`;
- [ ] No declaration with `secret: true` carries a `defaultValue`;
- [ ] For every `required: true` declaration you decided where the value comes from: carrying a `defaultValue` answers that mandatory question on the administrator's behalf, and the wizard stops asking (§7);
- [ ] `checkCommand` exits 0 without credentials (verify once locally in a same-architecture container: `docker run --rm -v $PWD/bin:/usr/local/bin/mycli:ro <base> -c 'mycli --version'`);
- [ ] The shelf holds one package under this name: the highest `version` stays, the loser is moved to `cli-packages/dist/.superseded/` by `cli-packages/build.sh`; two packages at the same name and version fail the build (§10).

## 12. A complete example: lark-cli (a third-party CLI)

The official command line of the Feishu / Lark Open Platform. It is the whole path from nothing to executable-inside-the-image, and it is worth copying because **it proves that this spec can take a 45 MB third-party binary**.

### 12.1 Upstream facts decide the shape of the package

| Upstream fact | Decision on the package side |
|---|---|
| The npm package `@larksuite/cli` is only a downloader (`postinstall` fetches the binary); the real artifact is a single static binary of about 45 MB | What goes into `payload/` is the binary, not npm; the container has no node dependency |
| The binary is published per platform on GitHub Releases, and there is an official mirror source | Downloaded at packaging time; the repository stores no binary |
| Upstream publishes a sha256 manifest for the linux architectures | `checksums.txt` pins the digests; an upstream version bump fails the build instead of quietly swapping bytes |
| In a headless environment reading `LARKSUITE_CLI_APP_ID` / `LARKSUITE_CLI_APP_SECRET` is enough; no config file and no login are needed | Credentials go through `envParams`, `checkCommand` uses `--version` |
| The command surface covers a dozen or so domains, and it ships its own `lark-cli skills list/read` | The skill document uses the index form (§6.3) |

### 12.2 `cli-packages/lark-cli/plugin.yaml`

```yaml
name: lark-cli
version: 1.0.96
description: 飞书 / Lark 开放平台命令行，读写消息、日历、多维表格、文档、云盘、审批、任务、邮箱、通讯录等平台资源
checkCommand: lark-cli --version
envParams:
  - envParamName: LARKSUITE_CLI_APP_ID
    description: 飞书开放平台应用的 App ID（形如 cli_xxx），在「凭证与基础信息」页取
    required: true
    secret: false
  - envParamName: LARKSUITE_CLI_APP_SECRET
    description: 对应的 App Secret；应用需已开通所要调用的接口权限
    required: true
    secret: true
```

There is no `deps.apt` (a pure static binary, zero network at build time) and no `runtimeEnv` (the platform publishes no Feishu slot, and the package should not carry a key either).

Both declarations are `required: true` with no `defaultValue`, so on the agent wizard an agent that ticks lark-cli cannot be saved until each of the two rows has a value (or each references a global environment variable) — §7. For `LARKSUITE_CLI_APP_SECRET`, follow §7 and use a reference rather than typing it in.

### 12.3 The skill document

`skill/SKILL.md` is 5743 bytes. Its structure is the four elements of §6.2 plus one index-style convergence: prerequisites (it states outright "do not execute `login` / `config init --new` / `update`", and explains that the `config_file` check of `doctor` necessarily fails inside a sandbox, so `whoami` is the authority) → the order of choosing a command (`+shortcut` > typed method > raw `api`) → risk and confirmation (`read|write|high-risk-write`, `--yes` only after the user confirms) → output control (`--jq`, `--format`, `--page-all`) → identity switching (`--as`) → the domain overview → deeper usage pointing at `lark-cli skills read lark-<domain>`.

### 12.4 Measured results (local arm64 host, base image `harnax-sandbox:py-node`)

| Step | Result |
|---|---|
| Packaging | `dist/lark-cli-1.0.96.harnaxcli.zip`, 14132636 bytes, linux/amd64 |
| Registration | `Registered CLI package lark-cli 1.0.96 (payload e4223d4a14b1, uploaded)`; this round `Sync complete: 3 registered, 0 failed` |
| The `cli` row | id=39, `version=1.0.96`, `status=1`, `skill_id=40`, `package_digest=3f337b54c511c2d6321a1e793b24f141378add0caa09afe7d9fcdcc183fe5011`, `payload_digest=e4223d4a14b1…52be` |
| Digest consistency | `package_digest` in the database == the sha256 of the local zip |
| MinIO object | `harnax-cli-packages/lark-cli/3f337b54c511…5011.harnaxcli.zip` |
| Skill row | id=40, `status=1`, `repository_id=1` (the hosting repository `builtin-cli-skills`), body 5743 bytes |
| Image | `harnax-sandbox:cli-2ba7f7813168` (the combined tag of the two CLIs, harnax + lark-cli) |
| File inside the image | `-rwxr-xr-x 1 root root 48124066 /usr/local/bin/lark-cli` |
| `checkCommand` | `lark-cli version 1.0.96`, exit code 0 |
| Credential-readiness self-check | With only the two env vars injected, `lark-cli whoami` → `identity: bot`, `tokenStatus: ready` |

The image-tag step also verifies the formula in reverse: the tag was hand-computed first with the algorithm in design document §4, then compared character by character against the tag the runtime produced — identical.

**The last mile, unverified**: binding this CLI to an agent and actually making one Feishu API call. That needs an App ID / App Secret filled in by hand, and the automation in this repository does not do it for you.

## 13. Refusal messages verbatim → what to change

The log format is fixed as `<file name>: refused with N problem(s):`, followed by one line per reason. The table below lists the fourteen most common ones, quoting the code verbatim.

| Reason as it appears in the log (verbatim excerpt) | Why | What to change |
|---|---|---|
| `file name must end with .harnaxcli.zip` | Wrong suffix | Rename the file |
| `file name "…" does not start with the declared name plus a version` | The file-name prefix does not match `name` | Rename to `<name>-<version>.harnaxcli.zip` |
| `unknown plugin.yaml key(s) [install]` | There is an unrecognised key | Delete it; the platform accepts no install script |
| `plugin.yaml is not valid YAML: <problem>` | YAML syntax / duplicate key / nested too deeply | Fix the line named in `<problem>` |
| `name "MyCLI" must match ^[a-z][a-z0-9-]{1,63}$` | The name contains uppercase or an underscore | All lowercase, `-` only |
| `version "v1.0" must match …` | A version may not start with `-`/`.` | Drop the leading `v` and anything like it |
| `plugin.yaml: checkCommand is required` | A required field is missing | Add it — see §8 |
| `plugin.yaml deps.apt entry "…" is not a plain apt package name` | The entry contains a space, `$`, a semicolon, or a leading `-` | Keep only the package name and the optional `=version` |
| `plugin.yaml envParams "TOKEN" is marked secret and carries a defaultValue` | A package may not carry a secret | Drop `defaultValue`; the administrator supplies the value |
| `plugin.yaml runtimeEnv KEY asks for ${platform.foo}, which the platform does not publish` | The slot does not exist | Use `platform.adminUrl` / `platform.internalToken`, or move to `envParams` |
| `payload path lands on a denied target: etc/cron.d/x` | It lands on the refusal list | Install it under `usr/local/…` instead |
| `payload/… is a symbolic link` / `carries setuid/setgid/sticky bits` | Invalid entry shape | Use a real file and clear the special bits |
| `… carry no unix mode, so they would land 0644 and fail as "permission denied"` | The packer recorded no mode | `chmod 755` on a filesystem that stores permissions, then repack with `zip -X` |
| `unexpected entries outside plugin.yaml, skill/ and payload/: …` | The package has a fourth location | Remove it from the packaging list (Finder's "compress" adds `__MACOSX/`) |

Two more come from the skill side:

| Reason | What to change |
|---|---|
| `no skill/SKILL.md — a CLI ships its own skill…` | Add one — see §6 |
| `skill/SKILL.md declares name "x" while plugin.yaml declares "y"` | Make the two agree (the skill follows the CLI name) |

## 14. Boundaries and known traps

Worth knowing before you write a package; the platform does not solve these today:

1. **The platform is a software distribution channel**: anyone who can put a file into the package directory decides what files land as root inside every agent sandbox. The list in §5.2 is defence in depth, not a sandbox.
2. **No coexisting versions**: during an upgrade every agent gets the latest version; there is no per-agent pinning and no canary.
3. **Upgrades do not follow sessions that are already kept alive**: swapping the binary takes effect for new sessions only. Old sessions keep running the old container from the old image until idle reclamation, or until someone runs `docker rm -f agentscope-sandbox-<sessionId>` by hand. The "version" column on the CLI page shows the newest registered value and may differ from the container in use.
4. **Package objects, payload caches and CLI images only grow**: MinIO holds one object per `packageDigest`, agent-service one cache directory per `packageDigest`, docker one tag per `payloadDigest`, and none of the three has a reclamation path. Combined with the mtime sensitivity described in §9, every repack adds one object and one cache directory.
5. **`skill/assets/` does not reach the container**: a package may carry it, and the runtime does not promise to project it.
6. **`deps.apt` requires a reachable mirror**: declarative does not mean offline. The recommended form is a static binary with nothing but payload.
7. **Registration results are invisible to the page**: the truth is only in the admin log (§10).
8. **Several agent-service replicas sharing one docker daemon have no build mutex**: tags are content-addressed, so the results agree, but two replicas building the same tag at the same time means one of them sees a failure.

## 15. Related documents

| Document | Content |
|---|---|
| [cli-plugin-package-design.en-US.md](./cli-plugin-package-design.en-US.md) | The full platform-side path, invariants and reasons, decisions D1~D15, the operations manual, the index of key files |
| [docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md](../docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md) | The implementation spec: change list, V35~V38 migrations, test plan |
| [skill-management.en-US.md](./skill-management.en-US.md) | The skill system (a CLI's own skill lands in the hosting repository and cascades with the package lifecycle) |
| [tool-integration-design.en-US.md](./tool-integration-design.en-US.md) | The tool system (same shape as a CLI: registered at startup, read-only on the page) |
| [docs/deploy-harnax-admin.md](../docs/deploy-harnax-admin.md) | admin deployment and the ops of mounting the CLI package directory |
| `harnax-cli/`, `cli-packages/lark-cli/` | Two packaging examples you can copy directly: a CLI of your own, and a third-party CLI |

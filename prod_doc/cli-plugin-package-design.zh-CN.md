# Harnax CLI 插件包设计

面向平台实现者与运维：一个 CLI 插件包从落进货架目录到出现在容器里，中间经过哪些代码、哪些表、哪些存储，以及日常运维怎么做。

本文与《Harnax CLI 插件包规范》的分工：那本的读者是包作者，内容是包的形状、`plugin.yaml` 字段与逐字的拒绝契约；本文的读者是平台侧，内容是登记链路、镜像与容器的到达路径、双摘要的职责划分、页面与接口、以及回收与故障处置。两份文档各自完整，不需要对照阅读。

## 1. 一句话模型

CLI 是包登记的资产，不是页面录入的记录。`cli` 表的一行等价于「货架目录里有一个通过的 `.harnaxcli.zip`」，两行写路径（登记期的 upsert、运维的启停开关）之外没有任何人写它。

一条链路把包变成容器里的文件：admin 启动登记并上传归档 → agent-service 解析智能体规格时拿到两个摘要与对象 key → `CliPackageStore` 按 `packageDigest` 拉取校验并解出 payload 树 → `CliImageBuilder` 把这棵树 `COPY` 到容器根、按 CLI 集合哈希出镜像标签 → 容器创建时注入 `runtimeEnv` 与智能体级绑定。

两个摘要分开是这套设计的支点：`packageDigest` 管「包是不是同一份字节」，`payloadDigest` 管「镜像要不要重建」。

## 2. 数据形状

### 2.1 `cli` 表

| 列 | 来源 | 谁写 |
| --- | --- | --- |
| `id` | 自增 | 登记路径；同名包的新版本复用同一个 id |
| `name` | `plugin.yaml` 的 `name` | 登记；`uk_cli_name` 是同名收敛的落点 |
| `description` | `plugin.yaml` 的 `description` | 登记，每次覆盖 |
| `version` | `plugin.yaml` 的 `version` | 登记，每次覆盖 |
| `check_command` | `plugin.yaml` 的 `checkCommand` | 登记，每次覆盖 |
| `skill_id` | 包内 `skill/SKILL.md` 登记的技能行 id | 登记；`cli` 到技能是直接指针，不经绑定表 |
| `package_digest` | 整包 sha256 | 登记；同时是对象 key 的一部分与「要不要重传」的判据 |
| `payload_digest` | payload 加 apt 的规范 sha256 | 登记；镜像语义由它承担 |
| `package_object` | `<name>/<packageDigest>.harnaxcli.zip` | 登记 |
| `deps_apt` | JSON 数组 | 登记，空列表写 `NULL` |
| `runtime_env` | JSON 对象 | 登记，空映射写 `NULL` |
| `env_params` | JSON 数组，secret 项的值加密 | 登记 |
| `status` | 无 | 只有启停开关写；登记的 upsert 语句里不含这一列 |
| `active` | 无 | 登记写 1；清理路径硬删，不走软删 |
| `create_time` / `update_time` | 无 | SQL 侧 `NOW()` |

表上没有 `tenant_id`、`creator`、`is_public`、`install_script` 这四列。这一组的列集合由两支脚本叠加而成：`env_params`、`status`、`active`、`create_time` / `update_time` 由 `V8__add_cli_management.sql` 建表时写下，`V35__cli_package_registration.sql` 一支加包列（`skill_id`、`package_digest`、`payload_digest`、`package_object`、`deps_apt`、`runtime_env`）与 `uk_cli_name`、另一支把那四列从表上收掉。三个可见性列没有对应物，因为发布的包是平台级资产、没有可表达的可见性；`install_script` 也没有，包携带的是 payload 加声明式 `deps.apt`。一行 `cli` 参与的关联只有两处：指向它的 `agent_cli_binding` 行，以及经 `cli.skill_id` 拿到的那一条技能行——说明书与它的资源都在那条技能上。

### 2.2 `agent_cli_binding`

一行 = 一个智能体选了一个 CLI，携带 `env_bindings`（该智能体为这个 CLI 填的参数值快照）。`V41__add_cli_binding_unique_key.sql` 加了 `uk_agent_cli_binding_agent_id_cli_id (agent_id, cli_id)`，并先把重复行折叠成保留最新一条；同时删掉 `idx_agent_cli_binding_agent_id`（`agent_id` 是新键的最左前缀）。绑定指向的是 CLI 而不是版本，所以包升级不重置绑定。

保存路径整体重写这个集合。投递侧按一行一份映射进规格，因此重复行会让同一个 CLI 装两遍、并按每一份算一次镜像材料——唯一键把这件事挡在库里。

清理路径会删掉被下架 CLI 的绑定行（`deleteByCliIds`）。

### 2.3 包登记的技能行

包内 `skill/SKILL.md` 落进 `skill` 表，名字与 CLI 名一致，挂在管理仓储 `BuiltinRepository.CLI_SKILLS` 下，`creator` 记为 `SYSTEM`、`is_public` 记为公开，`active = 1`。技能行的 `status` 由 CLI 的 `status` 决定（内容扫描命中时强制为停用）。下架时按各技能删除的惯例置 `active = 0`，并删掉它身上的智能体绑定与团队绑定；重新上架会拿到一条新建的技能行。

### 2.4 库层的语句约束

`CliMapper.upsertCliPackage` 是 `INSERT ... ON DUPLICATE KEY UPDATE`，更新列表覆盖清单拥有的全部列与 `active = 1`，唯独不出现 `status`。`selectByName` 不带 `active` 条件（登记器必须认得自己写过并停用的行），`V42__retire_soft_deleted_pre_package_cli_rows.sql` 用「按包外行改名的 `#retired-<id>` 后缀」把这一条从「几乎成立」变成成立：`V38` 处理还活着的行，`V42` 处理页面上被软删、名字还被唯一键占着的行，两支都限定 `package_digest` 为空，都不碰登记器写的行。

`selectByNameForUpdate` 是与 `selectByName` 完全相同的 WHERE 加 `FOR UPDATE`。登记器在事务里用它读 `status`，于是启停开关的 `updateStatus` 必须等这条事务提交，两行（`cli` 与它的 `skill`）不会各说一套。

## 3. 登记链路

### 3.1 触发点

`CliPackageAutoRegistrar.syncCliPackages()` 监听 `ApplicationReadyEvent`，每次启动跑一轮，无定时器。三个配置项：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `harnax.cli.package-dir` | 环境变量 `HARNAX_CLI_PACKAGE_DIR`，容器镜像内为 `/home/harnax/cli-packages`；代码内默认空串 | 货架目录。空串时整轮跳过并记一行 INFO |
| `minio.cli-package-bucket` | `harnax-cli-packages`（环境变量 `MINIO_CLI_PACKAGE_BUCKET`） | 归档桶；agent 侧 `harness.minio.cli-package-bucket` 必须取同一个值 |
| `harnax.cli.archive-retention-days` | 7（环境变量 `HARNAX_CLI_ARCHIVE_RETENTION_DAYS`） | 无引用归档的保留天数 |

### 3.2 前置判定

按顺序：

- 目录路径为空白 → 一行 INFO，返回。
- 目录不存在 → 一行 ERROR `Package directory {} does not exist — no package registered and no row pruned`，返回。配置了但看不见意味着卷没挂上；此时「什么都不登记并且把全部行删掉」是比原地不动更坏的答案。
- 列出目录里以 `.harnaxcli.zip` 结尾的文件，按文件名排序。
- 一个都没有 → 一行 WARN `Package directory {} holds no .harnaxcli.zip file`，然后照常往下走（清理因此会执行）。
- 有包但 `MinioClient` 不可用 → 抛 `IllegalStateException`，启动失败：`N CLI package(s) in <dir> but MinIO is not enabled — set minio.enabled=true and the minio.* connection properties, or empty the package directory`。存不下包是部署错误，让平台静默地跑成一个没有 CLI 的系统更糟。

### 3.3 解析

全部文件先解析完再登记，因为两个文件可能声明同一个名字，赢家必须由清单版本决定而不是由目录列出顺序决定。每个文件一次 `CliPackageParser.parse(file)`；抛异常就是这一行 ERROR `Failed to register package {}: {}` 加一个 `failCount`，其他包继续。解析器不查库、不起进程、不碰对象存储。

### 3.4 同名仲裁

解析成功的包按 `manifest.name` 分组：

- 一组一个：直接登记。
- 一组多个：取清单版本最高的登记，其余跳过并留一行 ERROR `Name '{}' is declared by {} packages; registering {} (version {}) and skipping {}`。版本比较 `compareVersions` 按 `[._+-]` 分段、数字段按数值比，所以 `1.0.10` 新于 `1.0.9`；预发布后缀只是另一段，于是 `1.0.0-rc1` 排在 `1.0.0` 之后——这一点在「同一名字的两个文件里挑一个」的场景里可接受。
- 最高版本并列（同名同版本、不同文件）：谁都不登记，ERROR `Name '{}' is declared by {} packages at version {} ({}) — registering neither`，同时加一个 `failCount`。名字因此不进 `registered` 集合，活着的行不会被当成已退役而删掉。

### 3.5 存归档

对象 key 是 `<name>/<packageDigest>.harnaxcli.zip`，`Content-Type: application/zip`；桶不存在时先建。已有行的 `packageDigest` 与 `package_object` 都对得上就跳过上传（一次索引读的成本）。

上传刻意放在事务之前。放后面的话，上传失败时已提交的事务会留下一行指向不存在对象 key 的记录，而下一次「对得上」的判断会把这行读成已完成，缺失的归档就此永远不会被重传。一次失败留下的多余对象只占磁盘；一行指向空的记录会让每个绑定这个 CLI 的智能体拿不到镜像。

### 3.6 两行落库

一个 `TransactionTemplate` 事务里做完：取管理仓储（缺了就抛 `managed skill repository '...' is missing — cannot register the shipped skill`）→ 锁读 `cli` 行取 `status` → `upsertSkill` → `upsertCliPackage`。两行一起提交，半途失败不会留下一条没人指、也没人清的技能行。

`upsertSkill` 先跑 `SkillContentScanner.scan(skillMd, skillAssets)`——与 GIT/NPM/ZIP 加载器同一个扫描器，因为一个包和一份第三方技能同样不可信，而它的 `SKILL.md` 会进每个绑定智能体的提示词。命中规则时技能以停用状态入库，ERROR 里点出命中的资源与规则：`Skill {} was stored disabled by the content scan: {}`。这是这条链路唯一的反馈通道。

已有技能行时更新 `description`/`skillmd`/`resources`/`version`，`updateById` 故意不动 `status`，所以状态跟随要单独一条 `updateStatus`。

成功后一行 INFO：`Registered CLI package {} {} (payload {}, {})`，`{}` 分别是名字、版本、`payloadDigest` 前 12 位、`unchanged` 或 `uploaded`。

### 3.7 清理

`pruneMissingPackages(registered, failCount)`。四道判断，按顺序：

1. `failCount > 0` → ERROR `Skipping prune: {} package(s) failed to register, so the live set is not trustworthy`，返回。一轮没能读全所有包，它就没有关于哪些行过时的证据。
2. 取全部 `cli` 行，只留 `package_digest` 非空的（登记器自己写过的）。
3. 这些行里名字不在 `registered` 中的是 stale；stale 为空则返回。
4. `stale.size >= live` → ERROR `Skipping prune: {} row(s) not in the directory vs only {} registered — this looks like a missing package directory, not retired packages. Stale: {}`，返回。待删数量与在册数量同级，更像卷没挂而不是运维退役了半个平台。

通过后才删：一个事务里 `deleteByCliIds`（绑定）→ `deleteBySkillIds`（智能体绑定、团队绑定）+ 技能逻辑删 → `cli` 硬删；提交之后删对象存储里的归档。

对象删除跑在事务外：对象存储不能回滚，一次被撤销的清理必须一个归档都不动。这一步是尽力而为——删不掉就记 WARN，行仍然算删过了。

`status = 0`（停用）不进入任何清理判断：开关不是删除。

### 3.8 归档回收

`reclaimOrphanArchives()` 收的是「原地升级留下的、没有行再点名的归档」：一行仍然活着、只是换了摘要时，`removeArchivesOf` 看不到那个 key。

三条判据同时成立才删：key 形状是本类写出来的（`<name>/<sha256>.harnaxcli.zip`，用 `NAME_PATTERN` 与 `DIGEST_PATTERN` 各自验一段，所以运维手工塞进桶的对象不在射程内）；key 不在 `selectPackageObjects()` 的回答里；对象的最后修改时间早于 `archiveRetentionDays` 天前——一秒钟前停止点名的行不代表没有仍在跑的 agent-service 还在下载它，那一行是 admin 的视图，不是整个机群的视图。

列目录整体读失败就一行都不删（`Keeping every stored archive: {} could not be listed ({})`）；单个条目的元数据读不到只留那一个。

## 4. 如何到达镜像与容器

### 4.1 规格下发

内部 API 的 agent 规格解析里，`cliDetails` 由 `agent_cli_binding` 逐行映射：`selectByIds` 取行（带 `active = 1`）、`status == 0` 的跳过并记 INFO `CLI '{}' (id={}) is disabled, skipping`、找不到行记 WARN `CLI not found: cliId={}`。每份 `CliDetailDto` 带：

- 镜像材料：`id`、`name`、`version`、`checkCommand`、`packageObject`、`packageDigest`、`payloadDigest`、`depsApt`——由 `imageFields` 一处生产，agent 规格与 `GET /internal/cli/inventory` 共用它；
- `runtimeEnv`：`cli.runtime_env` 反序列化，占位符不解开；
- `envBindings`：`[{envKey, envValue}]`，智能体填的值优先，未填的键由 `cli.env_params` 里声明的默认值补齐，两边都无值的键不发送；secret 值在这一步用 AES 解开；
- `skill`：包内说明书的完整 `SkillDetailDto`（`skillmd` + `resources` + `version`），随规格内联下发，runtime 无需二次查询。

技能行的可见性判定走与智能体自身技能同一个 `skillBindingResolver.deliverable(skillIds, agentTenantId)`，`cli.skill_id` 是这一份资源的额外持有者：只读绑定表的守卫会让一个自带技能被停用或删除后，CLI 还在。停用的技能不下发并记 INFO。

`AgentSpecResolver.withCliSkills` 把这些技能并进智能体技能列表：先按 id 去重、再按名字去重（技能名只在仓储内唯一，所以租户自己的技能可能与 CLI 的重名；harness 按 `name + "_" + source` 键控技能，两份都发会让注册表与内存仓储各认一份，运维显式绑定的那份赢）。没有说明书的 CLI 记一行 WARN。

`AgentSpec.cliSpecs` 由 `CliDetailDto.toCliSpec()` 生产，这是 admin 行到 `CliSpec` 的唯一读法，`envBindings` 在这里从 `[{envKey, envValue}]` 收成 map。

### 4.2 取包与解包：`CliPackageStore`

缓存目录按宿主配置，每个包一个 `<cacheDir>/<packageDigest>/`，旁边一个 `<packageDigest>.complete` 标记。

`materialize(packageDigest, objectKey)`：

1. `packageDigest` 必须过 `DIGEST_PATTERN`（它既是缓存目录名也是标记名，且是从 admin 经网络到达的字符串）；不合形时 `CLI package digest (N chars) is not a sha256 hex — this CLI was not registered from a package`。`objectKey` 空白时 `CLI package <digest> has no object key — it was not registered from a package`。
2. 树存在且标记存在 → 直接返回，顺手刷新目录的 mtime（读缓存就是一次使用，回收按这一刻算闲置）。
3. 否则按摘要加 JVM 内锁，避免两个并发会话各拉一遍同一个 40 MB。
4. 在 `cacheDir` 内建 `.staging-*` 临时目录，从 MinIO 流式取对象、边写边算 SHA-256；实算与期望不符则 `CLI package object '<key>' hashes to <actual>, expected <expected> — the stored archive is not what admin registered`。
5. 用 `CliPackageArchive.extractTree("payload/", payload)` 解包。落盘时对每个目标再跑一次 `checkPayloadPath` 与 `resolveInside`，一个与登记内容不符的归档在这里也会被拒：`<原因> — refusing to extract <entry>`。写完恢复 packed mode（文件系统没有 posix 视图时跳过，让镜像构建那台 Linux 上去落实位）。
6. `written == 0` → `CLI package <digest> carries no payload/ entries`。
7. 已有的同名树先整删（`rename` 不肯移到非空目录上），再把 payload 以 `ATOMIC_MOVE` 移到最终位置（不支持时退化成普通移动），最后写标记。
8. `finally` 清 staging。

结论：树要么以完整形态出现，要么不出现。

### 4.3 镜像构建：`CliImageBuilder`

`resolveImage(cliSpecs)`：

- 空集合直接返回基础镜像，不起构建。
- `validate`：`name`/`version`/`packageDigest`/`payloadDigest`/`depsApt` 逐项复查 `CliPackageLayout` 里的四个正则。admin 登记时查过一遍，但 runtime 没有理由假设手里这行是登记器写的——这些字符串会进生成的 Dockerfile（注释与 `apt-get` 参数），`version` 里一个换行就多一条指令。报错只点名出问题的字段、不回显内容：`CLI <id> carries a value no package could have registered: <fields>`。
- 集合按 `cliId` 排序 → `tagOf` → 命中本地已确认集合或 `docker image inspect` 成功就复用；否则按标签取锁、双检后构建。锁避免同一 JVM 里两个会话各构建一次；`knownImages` 避免每次创建容器都起一个 `docker image inspect` 子进程。

`generateDockerfile`：

```dockerfile
FROM <baseImage>
# CLI: harnax 1.0.0 (payload sha256:abcdef123456)
COPY f5fa5f8e2c1d4b6a9e0c7d3b1a5f8e2c1d4b6a9e0c7d3b1a5f8e2c1d4b6a9e0c/ /
# CLI: lark-cli 1.0.96 (payload sha256:9876543210ab)
COPY 2b7e9d4a1c8f03b6a5e27d94c1f80b3e2b7e9d4a1c8f03b6a5e27d94c1f80b3e/ /
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
```

`COPY` 的路径是 `packageDigest`（`COPY ${cli.packageDigest}/ /`），因为缓存树与构建上下文目录都以它命名；注释里那串 `payload sha256:` 才是 `payloadDigest` 的前 12 位，两个值在同一个包上并不相同。`COPY` 的目标是 `/`，因为镜像的 `WORKDIR` 是 `/workspace`，写 `./` 会把整棵树倒进智能体的工作目录。`apt` 列表 `distinct().sorted()` 后拼接，所以包内顺序既不影响命令行也不影响摘要。没有一条 `RUN` 执行包内容。

标签：`harnax-sandbox:cli-<combinationHash>`，哈希材料是 `baseImage` 与每个 CLI 的 `cliId:version:payloadDigest`（按 `cliId` 排序），取 sha256 前 12 位。`tagOf` 是唯一组装标签的地方，`evictUnusedImages` 用它做白名单——第二份公式与构建路径一旦不一致，就会被删掉的正是一个活智能体要用的镜像。

构建上下文是临时目录，把每个需要的 payload 树以硬链接摆进去（跨文件系统时退化为带属性的普通复制），目录名就是 `packageDigest`。上下文只装这张镜像要用的树：Docker 每次构建都 tar 整个上下文，指向缓存会带走这台机上拉过的每一个包。

构建完，对每个 `checkCommand` 非空的 CLI 跑一次 `docker run --rm --entrypoint /bin/sh <tag> -c "<checkCommand>"`；非零就 `docker rmi -f <tag>` 再抛 `CLI '<name>' check command failed in image <tag>: <输出末 500 字符>`。构建本身失败抛 `Failed to build CLI sandbox image <tag> (CLIs: <names>): <输出末 2000 字符>`。

每一条 `docker` 调用都有各自的预算，取不到答案时按失败处理而不是无限等：普通命令 120000 ms、`docker build` 1800000 ms、验收命令 300000 ms、`docker rmi` 300000 ms（常量在 `DockerCommandExecutor`）。`Process.waitFor` 不可中断，所以回收任务放弃等待之后，在途的那条 `docker` 仍然只会在这套预算到点时返回。

### 4.4 容器创建时注入环境参数

`HarnessAgentLauncher` 在建 agent 时：

```kotlin
val cliEnv = cliEnvironment(agentSpec.cliSpecs)
val resolvedSandboxImage = when {
    isLead || agentSpec.cliSpecs.isEmpty() -> harnessConfig.sandbox.image
    cliImageBuilder == null -> throw IllegalStateException(...)
    else -> cliImageBuilder.resolveImage(agentSpec.cliSpecs)
}
```

- 团队的 lead 角色永远用基础镜像：lead 不起沙箱，CLI 属于成员。
- 有 CLI 但 runtime 没有 MinIO（`harness.minio.enabled` 未开）时报错并列出 CLI 名与摘要前 12 位，不静默退化到基础镜像。

`cliEnvironment`：先按 CLI 顺序展开 `runtimeEnv`。值是字面量直接用；整体是 `${slot}` 时用部署侧的两个槽解，槽值来自 `harnessConfig.sandbox.platformAdminUrl` / `platformInternalToken`（由 `HarnessAutoConfiguration` 从配置装配）。槽未发布或为空时该变量整体不设，并记 WARN：`CLI '{}' asks for runtimeEnv {} = {} but this deployment publishes no {} — the variable is left unset so the CLI reports why it cannot work`。展开完再把所有 CLI 的 `envBindings` 覆盖上去，所以智能体级的值赢。

结果通过 `DockerFilesystemSpec.environment(cliEnv)` 交给容器创建，也随 `HarnessAgentWrapper` 的 `sandboxImage` / `sandboxEnv` 保存，keep-alive 复用容器时用同一对值。它不进镜像：换变量值不需要重建，容器重建即可见。

### 4.5 回收：`CliArtifactReaper`

payload 树与镜像都只朝一个方向积累。`CliArtifactReaper`（`harness.sandbox.enabled=true` 时装配）以固定延迟任务补上反向：

| 键 | 默认 |
| --- | --- |
| `harness.sandbox.cli-reclaim-interval-ms` | 3600000 |
| `harness.sandbox.cli-reclaim-initial-delay-ms` | 600000 |
| `harness.sandbox.cli-reclaim-sweep-timeout-ms` | 600000 |
| `harness.sandbox.cli-reclaim-grace-minutes` | 360 |

它是 `SchedulingConfigurer` 而非 `@Scheduled`，因为周期不能小于宽限窗口（`effectiveIntervalMs` 会夹紧）。两个清扫跑在一个单线程 worker 上，一轮没结束不会并上第二轮。

每轮先向 admin 取 `GET /internal/cli/inventory`，取不到就一行都不删：两个清扫都把「不在白名单」读成「已无引用」，一份空回答会清空这台机器的 CLI 缓存和它建过的所有镜像。镜像清扫先跑（它是外呼 `docker` 的那个），超时出预算同样保留全部。

白名单口径来自 admin 的 `resolveCliInventory()`：`packageDigests` 是全部在册且非空摘要的行（`status` 不作条件——停用包仍是登记着的、归档仍在桶里、树仍可重建），`agentCliSets` 是每个智能体的启用 CLI 集合、用与规格路径同一个 `imageFields` 生产，于是 `tagOf` 算出的标签与构建时一致。

`evictUnusedImages` 删一个标签要同时满足：不在活集合的标签里；`docker ps -a` 列出的任何镜像都不引用它（覆盖 keep-alive 的停止态容器）；构建时间早于宽限窗口。`docker ps -a` 或 `docker images` 拿不到回答就整个清扫放弃；单个标签的构建时间读不到只留那一个；`harnax-sandbox:cli-` 前缀之外的标签永远不是候选（基础镜像因此不在射程内）。删成功的标签同时从 `knownImages` 移掉，否则缓存会一直宣称一个本机 Docker 里查不到的镜像存在，下一个智能体就会在 `docker create` 处失败。

`CliPackageStore.evictUnused` 要同时满足：摘要不在 `inUse` 里；树与标记的最后修改时间都早于宽限窗口（任一新于窗口就留）；目录名过 `DIGEST_PATTERN`（本类没写过的东西不动）。标记与树一起删，部分删除会在下轮重试；`.staging-*` 目录按同一个窗口清。

`removeArchivesOf` 与 `reclaimOrphanArchives` 在 admin 侧，`evictUnused` 与 `evictUnusedImages` 在 runtime 侧，各看各的存储，同一份清单是它们的共同依据。

## 5. 为什么是两个摘要

| 摘要 | 材料 | 用途 | 变则 |
| --- | --- | --- | --- |
| `package_digest` | 整个 zip 的字节 | 包的身份；对象 key；「要不要重传」的判据；缓存目录名 | 任何字节变化，包括注释、时间戳、条目顺序、措辞 |
| `payload_digest` | `payload/` 每条文件条目的 `path\|mode\|sha256(content)` 按路径排序，接一行 `#apt:` 与排序后的 apt 列表 | 镜像指纹：标签材料，也是唯一「容器内容变了」的信号 | 只有落进镜像的字节或 apt 集合变化 |

分开的收益：改一句 `SKILL.md` 措辞会得到新的 `packageDigest`（新对象、更新的行、下一轮会话的新提示词），而运行中的容器保留它们的镜像。合并成一条摘要的话，说明书的每次改动都会重建所有绑定智能体的沙箱。

规范化的三个细节都必要：按路径排序（打包顺序不能漏进指纹）、带上 mode（否则丢可执行位的包与保留可执行位的包会同摘要）、带上 apt 列表（否则加一个 `deps.apt` 不换镜像）。

两侧必须逐字节一致：admin 计算并下发，runtime 用同一个 `CliPackageLayout` 复现树与镜像；两份实现漂移会产出静默不匹配的镜像。这也是这个类放在 `harnax-common` 的原因。

`runtimeEnv` 与 `env_params` 落在两个摘要的交界处：它们是 zip 的一部分，所以在 `packageDigest` 之内；它们不进镜像，所以在 `payloadDigest` 之外。改一个环境变量声明因此不换镜像，容器重建后即可见，而归档里会多出一个新 key。

## 6. 页面与接口

### 6.1 admin 对外接口

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/admin/clis/page` | 分页读，可按 `name` 模糊、按 `status` 精确过滤，`pageSize` 夹在 1..1000，按名字升序 |
| `GET /api/admin/clis/{id}` | 单行读；404 时返回 `error.cli.notfound` 的 i18n 文案。列表不返回的三个字段（`payloadDigest`、`depsApt`、`runtimeEnv`）只有这条路径给 |
| `PUT /api/admin/clis/toggle/{id}?status=N` | 启停开关，唯一的写路由 |
| `GET /api/admin/clis/{id}/related-agents` | 绑了这个 CLI 的智能体 |
| `GET /api/admin/clis/{id}/related-sessions` | 这些智能体的会话；配 `POST /api/admin/agents/refresh-sessions` 推 REFRESH 让在线会话取新配置 |

没有创建、更新、删除路由。平台自带的 Go CLI 把这五条包成命令面：`harnax cli list`（`--name` / `--status` / `--page` / `--size`，打 `/api/admin/clis/page`）、`harnax cli get <id>`、`harnax cli toggle <id>`（`--status 0|1`，省略这个标志时翻转当前值），三条都落在 `/api/admin/clis` 这一组路由上（`harnax-cli/cmd/cli_resource.go`）。`harnax cli` 的 `Short` 原文就是 `Inspect CLI plugin packages registered by admin (status is the only writable field)`——除状态之外没有写命令。

`toggleCliStatus` 的行为：先过 `SkillSourcePolicy.requireStatus(status)`，取值只接受 0 与 1，越界时报 `Status must be 0 (disabled) or 1 (enabled), got <status>`——所有消费方都按 `== 1` 比较，一个越界值不会响亮地失败，它会被永久读成「停用」，而页面上的开关再也拉不回来。行不存在抛 `CLI not found`；写 `cli.status`；随后让自带技能的 `status` 跟随，唯一例外是启用不解除内容扫描的隔离——启用时若技能的内容重新扫仍然命中，技能保持停用（读不回内容的话，这条列的第二个写者就成了批准第一个写者所拒之物的通道；内容读不出来时按「仍隔离」处理）。技能行已经不在时记 WARN，不报错：`CLI {} (id={}) points at skill {} which is gone; its status could not follow`。

停出不被「还有智能体绑着」挡住，影响面用 `related-agents` 显出来让运维自己量：开关的意义就是停一个刚发现问题的 CLI，要求先解绑每个智能体就没法一步做到。

`CliResponse` 的 secret 参数值掩码：解出明文后取 `前 3 + **** + 后 4`，长度 ≤ 7 或解密失败给 `******`。两个清单 JSON 列读不动时详情视图显示为空并记 WARN，不让整个详情失败。

### 6.2 内部接口

| 方法与路径 | 用途 |
| --- | --- |
| `GET <internal>/cli/inventory` | 回收扫描的唯一依据：`packageDigests` + `agentCliSets`。跨租户是有意的——包由登记器持有，而一个镜像标签不表达谁选的 |
| agent 规格响应里的 `cliDetails` | 每个 CLI 的镜像材料、`runtimeEnv`、`envBindings`、内联 `skill` |

失败时 inventory 返回 `Failed to resolve CLI inventory: <原因>`；调用方（`CliArtifactReaper`）把这读成「什么都不知道」，于是什么都不删。

### 6.3 页面

`harnax-webui/src/pages/cli/index.tsx`：搜索框 + 状态过滤 + 表格 + 状态开关 + 详情抽屉。列给出名字、描述、版本、`checkCommand`、`packageDigest`（页面上是「包摘要」，两个人对同一串说明看的是同一次构建）、`envParams`（弹层展示，secret 已掩码）、自带技能的名称与描述。操作只有开关和查看。

`CliDetailDrawer.tsx`：把列表不给的 `payloadDigest`、`depsApt`、`runtimeEnv` 摊开，数据来源是 `GET /api/admin/clis/{id}`。

智能体侧 `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx`：勾选 CLI、按包声明逐条渲染参数行、值起始为空、与 MCP 共用 `EnvParamTable` 组件与 `env_variable` 下拉。渲染行数以 `cli.envParams` 为准而不是以存储记录为准——按存储数量渲染会让包升级新增的参数永远不出现，删掉的参数则一直留在表单里被人填。只把有值的行发给后端。

`configValidation.ts` 的 CLI 必填判定与 MCP 同一条规则：包 `envParams` 的默认值会整份下发进沙箱，留空确实拿得到值，所以只有既没填又没默认值的必填项才算缺。

三类可绑定资产（tool / MCP / CLI）的环境参数声明共用一个形状 `ToolEnvParamEntry`（`envParamName` / `description` / `required` / `secret` / `defaultValue`）、共用一条加解密通道（`serializeToolEnvParams` / `decryptToolEnvParamsToMap`）、共用一个前端表格组件；值一律落在各自的 `*_binding.env_bindings` 上，`agent_tool_env_param` 只是工具侧按行存的那一份。

## 7. 关键设计决策

| 决策 | 内容 | 为什么 |
| --- | --- | --- |
| 单一生命周期入口 | 页面与接口都不写 `cli`；包在目录里就在册，离开目录就被清 | 一个资产有两个写者时，「页面上删了一行」和「货架上还有包」会互相覆盖；启动同步是唯一事实来源 |
| 双摘要 | 见「为什么是两个摘要」 | 说明书与二进制有各自的更新节奏 |
| 校验全在登记期 | 路径、权限位、大小、条目形状、清单字段全在启动期拒 | 一个会产出坏沙箱的包在启动期拒，成本是运维看到的一行 ERROR 点名文件；放到构建期就变成几分钟后一个说不清的退出码 |
| 包内容永不执行 | 解析器只读字节；镜像里只有 `COPY` 与 apt | `checkCommand` 写得再离谱也不可能在登记期跑起来 |
| 状态列不参与覆盖 | `status` 是运维开关，重启不重置 | 容器一 bounce 就把被停用的 CLI 重新武装，开关等于不存在 |
| 技能跟随开关 | `cli.status` 变，包内技能同步变；启用不解除隔离 | 登记一个 CLI 与教会它怎么用是一件事，留一半活会让智能体被告知用一个沙箱里没有的命令 |
| 平台级可见性 | `cli` 无租户、无作者、无公开位 | 发布的包是平台资产，没有可表达的可见性；读侧因此也不按租户过滤 |
| 歧义不选边 | 同名同版本两文件谁都不登记，并且冻结清理 | 没有任何依据说明运维指的是哪一个；猜一个的下场是一个静默不安装的升级 |
| 缺对象存储则启动失败 | 有包而 MinIO 未启用直接抛 | 静默跑成一个没有 CLI 的平台，比一次失败的启动更难诊断 |
| 清理有三道闸 | 解析失败 / 只删登记器写过的行 / stale 与在册同量 | 这是平台唯一会删掉「智能体仍在配置」的 CLI 的地方 |
| 标签公式唯一 | `tagOf` 同时服务构建与回收 | 两份公式一旦漂移，被删掉的是活智能体的镜像 |
| 白名单来自 admin | runtime 不从不自己的会话推断 | 一个镜像被所有恰好选同一集合的智能体共用，只看自己那份视图会删掉别人正要启动的镜像 |

## 8. 明确不做

- 不在页面或接口上创建、编辑、删除 CLI。
- 不在包内执行构建步骤。没有 `RUN` 跑包内容这一说，需要系统级准备只有 `deps.apt` 一条路。
- 不做架构探测：包里的二进制是给宿主 Docker 构建出来的容器用的，平台不判断它是不是本机格式。
- 不做多版本共存：`uk_cli_name` 让一个名字一行，绑定指向 CLI 而非版本。要灰度只能靠「哪个文件在货架上」。
- 不做包之间的依赖：包各自独立，`deps.apt` 只到 apt 包名一层。
- 不给包加租户或作者可见性。
- 不回填包默认值到智能体表单（值在下发时补齐，界面上留空就是留空）。
- 不在登记期或下发期解析平台槽；槽只在容器创建时展开，因为只有那台进程知道本部署的 admin 地址与内部令牌。
- 不做在线注册：登记只在 `ApplicationReadyEvent`。放包后要重启（或重建容器）才生效。

## 9. 边界与已知的坑

- **一轮里一个坏包会挡住清理，但不会挡住其他包。** 解析失败与登记失败都计入 `failCount`，`failCount > 0` 时整轮 prune 跳过。同一次重启里既上架一个新包又下架另一个包时，如果新包被拒，那个删除也不会发生。
- **目录不存在与目录为空是两种行为。** 前者只记 ERROR 并且一行不删；后者记 WARN 并让清理照常执行。挂卷失败因此比误删好诊断，但一个「空卷但挂载点存在」的场景仍然会被读成批量退役——`stale >= live` 那道闸挡的就是这个。
- **`compareVersions` 把预发布后缀当成普通段。** `1.0.0-rc1` 排在 `1.0.0` 之后（`rc1` 这一段大于空），方向与语义化版本相反。这只在同名两文件里选一个时起作用。
- **`upsertSkill` 的更新不覆盖技能名。** 名字由 `(name, repository_id)` 定位，包换名等于下架加新增，两条清理判断都会命中。
- **`SkillContentScanner` 命中不影响 CLI 行本身。** 只把技能存成停用。CLI 仍然进镜像、仍然出现在工具面上，只是模型拿不到说明书；`AgentSpecResolver` 会记一行 WARN。
- **一个镜像 = 一个 CLI 集合。** 集合里任意一个 CLI 的 `checkCommand` 失败会让整个集合拿不到镜像，其他 CLI 一起受影响。
- **`evictUnusedImages` 依赖 `docker` 子命令。** runtime 容器必须能访问宿主 Docker daemon；拿不到回答时行为是保留全部。
- **`knownImages` 是 JVM 内的乐观缓存。** 别的进程删掉镜像后这一进程不会立刻知道，`docker create` 会失败一次；回收路径删自己的标签时会同步失效这个集合。
- **宽限窗口是安全与磁盘的折中。** 窗口小于清扫周期会被 `effectiveIntervalMs` 夹回去，所以调小周期不生效，只能同时调小 `grace`。
- **`archive-retention-days` 的依据是对象的最后修改时间。** 手工覆盖过桶里的对象，这个时间就不代表「这串字节多久没人用了」。
- **`package_object` 为空的行不进归档白名单**，`selectPackageObjects()` 用 `<> ''` 过滤，这样的行也就不会被回收步骤误删。
- **桶名两侧必须一致。** admin 侧 `minio.cli-package-bucket` 与 agent 侧 `harness.minio.cli-package-bucket` 由同一个环境变量 `MINIO_CLI_PACKAGE_BUCKET` 供给；分开设成不同值时的症状是 runtime 报「对象不存在」而不是「桶不存在」。
- **bind mount 优先于镜像内的 COPY。** 用 docker-compose 时 `/home/harnax/cli-packages` 是宿主 `docker-new/dist/cli-packages` 的只读挂载，镜像里 baked 的那份被遮住；因此宿主目录必须非空——目录不存在正是 Docker 会替你建成空目录的情形，admin 于是把架上每个 CLI 读成已退役并 prune。

## 10. 运维手册

### 10.1 配置项清单

| 侧 | 键 | 环境变量 | 默认 |
| --- | --- | --- | --- |
| admin | `harnax.cli.package-dir` | `HARNAX_CLI_PACKAGE_DIR` | `/home/harnax/cli-packages` |
| admin | `harnax.cli.archive-retention-days` | `HARNAX_CLI_ARCHIVE_RETENTION_DAYS` | 7 |
| admin | `minio.cli-package-bucket` | `MINIO_CLI_PACKAGE_BUCKET` | `harnax-cli-packages` |
| agent | `harness.minio.cli-package-bucket` | `MINIO_CLI_PACKAGE_BUCKET` | `harnax-cli-packages` |
| agent | `harness.minio.enabled` | `MINIO_ENABLED` | false；关掉它，任何选了 CLI 的智能体都会在建 agent 时报错 |
| agent | `harness.sandbox.enabled` | `SANDBOX_ENABLED` | false；决定镜像能力与回收任务是否存在 |
| agent | `harness.sandbox.image` | `SANDBOX_IMAGE` | `harnax-sandbox:py-node`（代码内默认 `python:3.11-slim`）；无 CLI 时的沙箱镜像，也是所有 CLI 镜像的 `FROM` |
| agent | `harness.sandbox.cli-package-cache-dir` | `SANDBOX_CLI_PACKAGE_CACHE_DIR` | `/tmp/harnax-agent/cli-packages`；payload 树与 `.complete` 标记与 `.staging-*` 目录的所在 |
| agent | `harness.sandbox.cli-reclaim-interval-ms` | `SANDBOX_CLI_RECLAIM_INTERVAL_MS` | 3600000 |
| agent | `harness.sandbox.cli-reclaim-initial-delay-ms` | `SANDBOX_CLI_RECLAIM_INITIAL_DELAY_MS` | 600000 |
| agent | `harness.sandbox.cli-reclaim-sweep-timeout-ms` | `SANDBOX_CLI_RECLAIM_SWEEP_TIMEOUT_MS` | 600000；这一条不进 `harness.sandbox` 的绑定，由 `CliArtifactReaper` 构造参数上的 `@Value` 单独读 |
| agent | `harness.sandbox.cli-reclaim-grace-minutes` | `SANDBOX_CLI_RECLAIM_GRACE_MINUTES` | 360；树要闲置够这么久、镜像要建出够这么久才会被回收 |
| agent | 槽 `harness.sandbox.platform-admin-url` / `platform-internal-token` | `SANDBOX_PLATFORM_ADMIN_URL` / `SANDBOX_PLATFORM_INTERNAL_TOKEN` | 空串即「本部署未发布该槽」；相关 `runtimeEnv` 变量随之不设。`SANDBOX_PLATFORM_INTERNAL_TOKEN` 缺省时回落 `ADMIN_INTERNAL_API_SECRET` |

### 10.2 一次部署里包怎么进镜像

`docker-new/build.sh`、`deploy-all.sh`、`deploy-service.sh` 三者都做同一串：`./cli-packages/build.sh` → `cp cli-packages/dist/*.harnaxcli.zip docker-new/dist/cli-packages/` → `Dockerfile.admin` 的 `COPY docker-new/dist/cli-packages/ /home/harnax/cli-packages/`。构建脚本在 `cli-packages/build.sh` 失败时直接拒绝出货，以免发出一个残缺的货架。

`cli-packages/build.sh` 的三条自身规则：绝不清架（清架会把手工投递的包一起带走，而少一个包对 admin 不是「什么都没发生」，是「这个 CLI 退役了」）；`harnax-cli` 的产物必须恰好一份（两份同名的包会按清单版本决定赢家，那不是「哪一个刚被构建出来」）；架上一个包都没有时以 ERROR 退出，因为那会让 admin 启动成没有 CLI 的平台。同名多份时留清单版本最高的，其余移进 `dist/.superseded/`；同名同版本多份直接以 ERROR 退出——这与 admin 的仲裁规则一致，只是提前到出货前，让歧义在货架上就不存在。

`harnax-cli/Makefile` 的 `package` 目标是自打样本：`CGO_ENABLED=0` 交叉编译、`-ldflags "-X main.version=$(PKG_VERSION)"`、把 `plugin.yaml` 与 `SKILL.md` 摆到两个规定落点、`chmod 755` 二进制、`zip -X -q -r` 三个落点，并且先 `rm -f dist/*.harnaxcli.zip`。

### 10.3 加一个包

1. 作者按规范产出 `<name>-<version>.harnaxcli.zip`。
2. 放进货架（compose 部署里是宿主 `docker-new/dist/cli-packages/`）。
3. 重启 admin。
4. 看启动日志：`Sync complete: N registered, M failed`。`M > 0` 时往上找 `Failed to register package`，那条 ERROR 带完整的拒绝列表（一次报全）。
5. 页面 `GET /api/admin/clis/page` 确认这行在，摘要与预期一致。
6. 给智能体勾上，开一次会话；runtime 日志里找 `Resolved CLI sandbox image ...` 与 `Image ... built and verified`。首次构建包含一次下载与一次 `docker build`，耗时随包体增长。

### 10.4 紧急停用

页面开关（`PUT /api/admin/clis/toggle/{id}?status=0`）。这一步之后：规格解析跳过该 CLI（`cliDetails` 里不出现，因此它不进新镜像）、它的技能不进提示词、`GET /internal/cli/inventory` 仍报告它的 `packageDigest`（缓存与归档保留，重新启用不需要重新下载）。已经建好的镜像不受影响，因为它属于那一张镜像被创建时的集合；正在跑的容器也不受影响。让在线会话立刻按新配置走，用 `related-sessions` 找到会话再推 refresh。

### 10.5 下架

从货架删文件 → 重启 admin → 日志里找 `Removed {} CLI row(s) whose package left the directory: ...`（WARN）与 `Removed stored archive {}` / `Reclaimed {} unreferenced CLI archive(s) from {}`。

如果只看到 `Skipping prune: ...`，说明四道判断里有一道没过，按文案定位：解析失败先看拒绝列表；卷没挂看目录是否存在；`stale >= live` 时先确认这次是不是真的要退役这么多。

runtime 侧的树与镜像由 `CliArtifactReaper` 在后续轮次收掉。要立刻腾磁盘，只能手工 `docker rmi harnax-sandbox:cli-<hash>`：`docker ps -a --format '{{.Image}}'` 里还有引用的就别删。

### 10.6 常见故障的定位顺序

| 症状 | 先看 | 判据 |
| --- | --- | --- |
| 页面没有这个 CLI | admin 启动日志 | 有 `Failed to register package <file>` → 拒绝列表；有 `Package directory ... does not exist` → 卷；有 `holding no .harnaxcli.zip file` → 文件没投到位 |
| 启动直接失败 | 抛出的 `IllegalStateException` | `... but MinIO is not enabled ...` → 对象存储未配而架上有包 |
| 会话创建报「CLI check command failed」 | runtime 日志与命令输出末 500 字符 | 二进制架构不对、缺共享库（`deps.apt` 没声明）、`checkCommand` 依赖凭证或网络 |
| 会话创建报「Failed to build CLI sandbox image」 | 同上，输出末 2000 字符 | `COPY` 目的地不合法、apt 包名在本部署可达的源里不存在 |
| 报 `carries a value no package could have registered` | 库里这一行 | 这一行不是登记器写的，或两侧 `CliPackageLayout` 不一致 |
| 报 `hashes to <actual>, expected <expected>` | 桶里的对象 | 对象被覆盖过；正确处置是让 admin 重传（改包内容再重启），或删掉 key 让登记重跑 |
| 报 `this runtime has no MinIO to fetch them from` | agent 配置 | `harness.minio.enabled` 未开 |
| WARN `asks for runtimeEnv ... publishes no ...` | agent 的槽配置 | 该部署没配 admin URL / 内部令牌；变量未设，CLI 报自己的错 |
| CLI 装了但模型不用 | `Selected CLI(s) ship no skill to load` / `Skill ... of CLI ... is disabled` | 技能被内容扫描隔离，或技能行被删 |
| 磁盘只涨不降 | `[cliReclaim]` 行 | `Keeping every CLI artifact: admin did not answer the inventory` → 内部接口不通；`Keeping every CLI image: docker could not list ...` → daemon 不可达 |
| 一个 CLI 停用后又出现在会话里 | 是否重启过 admin，并且货架上仍有它的包 | 只有 `status` 跨重启保持；其余列每次启动都被包覆盖，但 `status` 不会被包覆盖 |
| 包换了版本，智能体绑定没了 | `agent_cli_binding` 是否还在 | 同名包应原地覆盖并保留 id；出现「谁都不登记」的 ERROR 时说明架上同名同版本有两个文件 |

### 10.7 日志与标识

`CliPackageAutoRegistrar` 的每行都带 `[CliPackageAutoRegistrar]` 前缀；`CliImageBuilder` 带 `[cliImage]`，`CliPackageStore` 带 `[cliCache]`，`CliArtifactReaper` 带 `[cliReclaim]`。镜像标签 `harnax-sandbox:cli-<12 位>`、缓存目录与上下文目录名都是 `packageDigest`（日志里常截前 12 位），对象 key 是 `<name>/<packageDigest>.harnaxcli.zip`。串起一次故障需要四个标识：文件名、`packageDigest`（定位对象与树）、`payloadDigest` 前 12 位（定位 Dockerfile 注释里的 CLI 与镜像）、镜像标签（定位容器）。

## 11. 关键文件索引

| 路径 | 内容 |
| --- | --- |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrar.kt` | 启动期同步：前置判定、同名仲裁、上传、两行落库、清理、归档回收 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParser.kt` | 校验与拒绝文案，产出 `ParsedCliPackage` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageLayout.kt` | 两侧共用的布局常量、四个正则、payload 拒绝清单、`packageDigest` / `payloadDigest` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageArchive.kt` | 只读归档视图与 `extractTree` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/ZipCentralDirectoryModes.kt` | 中央目录外部属性 → unix mode |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Cli.kt` | 实体列 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentCliBinding.kt` | 绑定与 `env_bindings` 快照 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/CliMapper.kt` | 读方法、`selectByNameForUpdate`、`selectPackageObjects` |
| `harnax-entity/src/main/resources/mapper/CliMapper.xml` | `upsertCliPackage` 的覆盖列与不覆盖列、`updateStatus`、`deleteByIds` |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/CliDetailDto.kt` | 下发给 agent-service 的形状 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/CliPackageInventoryResponse.kt` | 回收扫描的清单形状 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt` | 读侧与开关路由 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/CliServiceImpl.kt` | 开关语义、技能跟随、隔离不解除 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt` | 列表与详情的字段划分、secret 掩码 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` | `imageFields`、`mergeCliEnvBindings`、`cliDetails`、`GET /cli/inventory` |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt` | `serializeToolEnvParams` / `decryptToolEnvParamsToMap` |
| `harnax-admin/src/main/resources/db/migration/V8__add_cli_management.sql` | `cli` 表初始 DDL |
| `harnax-admin/src/main/resources/db/migration/V35__cli_package_registration.sql` | `cli` 的包化列与 `uk_cli_name` 的定义处 |
| `harnax-admin/src/main/resources/db/migration/V37__retire_seeded_builtin_cli_skill.sql` | 手工播种的技能行处置 |
| `harnax-admin/src/main/resources/db/migration/V38__retire_pre_package_cli_rows.sql` | 非包行的停用与改名 |
| `harnax-admin/src/main/resources/db/migration/V41__add_cli_binding_unique_key.sql` | `uk_agent_cli_binding_agent_id_cli_id` |
| `harnax-admin/src/main/resources/db/migration/V42__retire_soft_deleted_pre_package_cli_rows.sql` | 已软删非包行的名字释放 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/CliPackageStore.kt` | 摘要校验下载、原子发布、闲置回收 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/CliImageBuilder.kt` | 二次校验、Dockerfile 生成、标签、构建与验收、镜像回收 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/DockerCommandExecutor.kt` | `docker` 调用与超时 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` | `cliEnvironment`、镜像解析、`DockerFilesystemSpec` 装配 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt` | `CliSpec` 与 `toCliSpec()` |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` | 两个平台槽的装配、`CliImageBuilder` 与 `CliPackageStore` 的构造 |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/sandbox/CliArtifactReaper.kt` | 回收轮次、预算与白名单 |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` | `withCliSkills` 与 `cliSpecs` 装配 |
| `harnax-agent/harnax-agent-service/src/main/resources/application.yml` | `harness.minio.cli-package-bucket` 与回收参数 |
| `harnax-admin/src/main/resources/application.yml` | `harnax.cli.*` 与 `minio.cli-package-bucket` |
| `harnax-webui/src/pages/cli/index.tsx` | CLI 页：读与开关 |
| `harnax-webui/src/pages/cli/components/CliDetailDrawer.tsx` | 摘要、apt、`runtimeEnv` 的摊开 |
| `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx` | 智能体上的参数填写 |
| `harnax-webui/src/services/ant-design-pro/cli.ts` | 五条路由的前端封装 |
| `docker-new/Dockerfile.admin` | 货架 COPY 进镜像的路径 |
| `docker-new/docker-compose.yml` | `/home/harnax/cli-packages` 的只读挂载 |
| `docker-new/build.sh` | 构建期的货架装配 |
| `cli-packages/build.sh` | 货架规则：不清架、同名留一个、空架即失败 |
| `harnax-cli/Makefile` | `package` 目标：自打样本 |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrarTest.kt` | 收敛、同名仲裁、清理四道闸、归档回收的用例 |
| `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/sandbox/CliImageBuilderTest.kt` | 标签公式、Dockerfile 生成、二次校验的用例 |
| `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/sandbox/CliPackageStoreTest.kt` | 摘要不符、原子发布、闲置回收的用例 |
| `harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/sandbox/CliArtifactReaperTest.kt` | 白名单与预算的用例 |

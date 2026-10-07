# Harnax 技能（Skill）域设计

本文覆盖技能域当前的实现：技能来源与技能内容的数据模型、来源分类与 Loader、管理面端点与落库规则、技能与 agent / 团队 lead / CLI 包的绑定、可见性与租户读取语义、Admin 到 agent-service 的下发契约、运行侧装配链路，以及技能文件到沙箱文件系统的投影机制。

## 1. 范围与总览

一条技能（skill）由名称、描述、`SKILL.md` 正文和一组随包资源文件构成。技能内容存放在 MySQL 的 `skill` 表列里，平台不保存技能文件的磁盘副本；资源文件以 `相对路径 -> 文件内容` 的 JSON 对象存在 `skill.resources` 列中。远端来源是否可达只影响同步，不影响运行。

技能到达一次会话的运行时只有两条来源：

1. 操作者显式绑定的技能 —— `agent_skill_binding`（普通 agent）或 `team_skill_binding`（团队 lead）；
2. 所选 CLI 包自带的技能 —— `cli.skill_id` 指向的那一行，它随 `cliDetails` 内联下发，只能通过选中该 CLI 来获得。

两条来源在 agent-service 侧合并成一份技能清单，再由 `harnax-harness-core` 装进 harness 的内存技能仓库，并由 `SandboxSkillProjector` 把技能文件写进该会话容器的 `<workspaceRoot>/skills`。运行时侧不读技能表：内容一律来自 Admin 已经鉴过权的那一次下发。

管理面在 `harnax-admin`（Kotlin）。它对外提供两套端点：`/api/admin/skill-sources`（创建即安装）与 `/api/admin/skill-repositories` + `/api/admin/skills`（仓库与技能分离、两步式同步）。两套端点读写同一批 `skill_repository` / `skill` 行，共用同一组校验规则（`SkillSourcePolicy`）与同一条落库路径（`SkillInstaller`）。

安装是部分成功语义：一个来源里可能只有部分技能落库。接口返回成功不代表全部成功，响应携带 `SkillInstallResponse`，把每一项归入 `installed` / `updated` / `failed`（带原因）/ `flagged`（内容扫描命中，以停用状态落库），另有来源级的 `stale`、`sourceError`、`emptyReason` 三个字段和派生的 `savedCount` / `failedCount` / `complete` / `summary`。

## 2. 代码分布

| 位置 | 职责 |
| --- | --- |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt` | `/api/admin/skill-sources` 端点 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillRepositoryController.kt` | `/api/admin/skill-repositories` 端点 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt` | `/api/admin/skills` 端点 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillSourceServiceImpl.kt` | 来源的创建即安装、ZIP 上传、预览、重装 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillRepositoryServiceImpl.kt` | 仓库行读写与级联删除入口 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillServiceImpl.kt` | 技能行读写、列表谓词、停用与删除前置条件 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/` | 来源策略、装载落库、同步记录、内容扫描、绑定解析 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/` | GIT / NPM / ZIP 三种来源 Loader 与 `SKILL.md` 解析 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` | 下发契约：`buildAgentSpecResponse`、`specForTeam`、`skillDetail` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` | 下发结果转运行规格：`withCliSkills`、`buildAgentSpec` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillAdaptorImpl.kt` | 下发技能到 `AgentSkill` 的缩减 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt` | 内存技能仓库注册、默认工作区技能关闭 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt` | 容器句柄就绪时触发投影：`projectSkills`、`deliveredSkills` |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SandboxSkillProjector.kt` | 沙箱技能文件的投影与逐轮收敛 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/`、`harnax-entity/src/main/resources/mapper/` | 实体、Mapper 接口与 XML 语句 |
| `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` | admin 的 schema 基线：整个 admin 库的建表与列定义都在这一个脚本里 |
| `harnax-webui/src/pages/skill/`、`harnax-webui/src/services/ant-design-pro/skillSource.ts` | 管理台技能页与来源服务调用 |
| `harnax-cli/cmd/skill.go` | 命令行侧调用 `/api/admin/skills` 与 `/api/admin/skill-repositories` |

## 3. 数据模型

表结构以 admin 的 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 为准：这一个脚本就是全部建表与列定义，没有需要往上叠加的后续版本。

### 3.1 skill_repository（技能来源）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/SkillRepository.kt`

| 列 | 类型 | 语义 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `tenant_id` | bigint，默认 1 | 归属租户 |
| `name` | varchar(100) NOT NULL | 来源名；在同租户活跃行内唯一 |
| `url` / `branch` | varchar(500) / varchar(100) 默认 'main' | GIT 来源的地址与分支，同时是 `source_config` 缺位时的回退读取 |
| `source_type` | VARCHAR(20) 默认 'GIT' | `GIT` \| `NPM` \| `ZIP` \| `BUILTIN` |
| `source_config` | text | 来源配置 JSON：GIT 的 `url`/`branch`，NPM 的 `packageName`/`registry`，ZIP 的 `zipPath` |
| `version` | varchar(100) | 版本标识，同步时逐行写进 `skill.version` |
| `description` | text | 备注 |
| `status` | tinyint(1) 默认 1 | 0 停用 / 1 启用，只允许这两个值 |
| `is_public` | tinyint 默认 0 | 可见性；技能行的 `is_public` 跟随它 |
| `creator` | varchar(100) | 创建者用户名 |
| `active` | tinyint(1) 默认 1 | 1 存在 / 0 已删除 |
| `last_sync_time` | datetime NULL | 最近一次同步结束时刻；来源未被读过一次则为 NULL |
| `last_sync_status` | varchar(16) NULL | `SUCCESS` \| `PARTIAL` \| `FAILED` \| `EMPTY` |
| `last_sync_detail` | mediumtext NULL | 同步报告 JSON：`saved`/`installed`/`updated`/`failed`/`flagged`/`stale`/`error` |

表上没有内容存储路径列：技能内容只存在 `skill` 表的列里。

### 3.2 skill（技能主表）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt`

| 列 | 类型 | 语义 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `tenant_id` | bigint，默认 1 | 归属租户；由同步路径的来源行决定，由手工创建路径的调用方租户决定 |
| `name` | varchar(100) NOT NULL | 技能名；同仓库活跃行内唯一 |
| `repository_id` | bigint NOT NULL | 所属来源 |
| `description` | text | 技能描述，装载进 `AgentSkill.description` |
| `skillmd` | mediumtext | `SKILL.md` 正文（含 frontmatter），装载进 `AgentSkill.skillContent` |
| `resources` | mediumtext | 资源 JSON，`文件名 -> 文件内容`；空串表示无随包文件 |
| `version` | varchar(100) | 同步时写入来源的 `version` |
| `status` | tinyint(1) 默认 1 | 0 停用 / 1 启用；停用即退出下发范围 |
| `is_public` | tinyint 默认 0 | 跟随来源仓库的可见性 |
| `creator` | varchar(100) | 创建者用户名；导入的技能继承来源行的 creator |
| `active` | tinyint(1) 默认 1 | 1 存在 / 0 已删除（`SkillMapper.deleteById` 是一条 `UPDATE ... SET active = 0`） |

`AgentSkill` 拒绝空白的名称、描述与正文，因此这三列在写入路径上都有非空校验：`SkillSourcePolicy.requireContentOnCreate` / `requireContentOnUpdate`。

`resources` 必须能解析成 `Map<String, String>`，由 `SkillSourcePolicy.requireValidResources` 在保存期把关；运行时侧的解析失败处理见「从规格到 AgentSkill」。

一次技能包下载解出的内容写进上述列之后临时目录即被删除，MySQL 是技能内容的唯一存放处，因此 `skillmd` 与 `resources` 是 MEDIUMTEXT 而不是 TEXT：资源 JSON 把每个随包文件都嵌在同一个值里。

### 3.3 agent_skill_binding 与 team_skill_binding

| 表 | 业务列 | 唯一键 |
| --- | --- | --- |
| `agent_skill_binding` | `agent_id`, `skill_id` | `uk_agent_skill_binding_agent_id_skill_id (agent_id, skill_id)` |
| `team_skill_binding` | `team_id`, `skill_id` | `uk_team_skill_binding_team_id_skill_id (team_id, skill_id)` |

两张表都带 `create_time` / `update_time`，除此之外只有这一对 id。技能不存在按技能解析的环境变量通道：`AgentToolBinding` / `AgentMcpBinding` / `AgentCliBinding` 的 `env_bindings` 由 Admin 在下发时解成明文，技能侧没有任何读取方，所以两张技能绑定表都没有该列。

`team_skill_binding` 是团队唯一持有的能力表。工具、MCP、CLI 的配置都挂在 agent 上，因为 lead 只做编排、不执行任何工具，没有能填这类配置的入口。

一次保存是整组重写：`AgentSkillBindingMapper.deleteByAgentId` 后 `batchInsert`，唯一键因此只可能因同一请求里重复的 id 被触碰——`SkillBindingResolver.resolveBindable` 先做 `distinct()` 就是为了不把数据库错误抛给操作者。

### 3.4 cli.skill_id：包自带技能

`cli` 表带一个可空的 `skill_id`，含义是「这个 CLI 包在包内 `skill/` 目录下带的那份 `SKILL.md` 注册出来的那一行技能」。「哪个技能属于哪个 CLI」是 `cli` 上的一列，不是一张多对多连接表。CLI 身份是包名（`uk_cli_name`），新版本覆盖同一行，行 id 不变。

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrar.kt` 是这条关系的唯一写入者，没有任何页面或 API 写 `cli` 表：

- 一个新包 = 一行 `cli` 加一行 `skill`，两行在同一次 `TransactionTemplate` 执行里提交，因此半途失败的包不会留下无人指向、也无人回收的技能行；
- 已登记的包按 manifest 覆盖其拥有的列，包内 `SKILL.md` 改写会就地收敛到同一行技能；
- 包从目录里消失时，`cli` 行被删掉，它带的技能行按删除惯例置 `active = 0` 并清掉指向它的 agent / team 绑定（`pruneMissingPackages`），重新登记会拿到一条新行；
- 两个包声明同一个名字时按 manifest 版本高者胜出；同名同版本的两份文件都不登记，因为没有依据判断操作者指的是哪一个；
- `cli.status` 是操作者的总开关，重新登记不覆盖它；包重新登记时它带的技能行跟随该开关。

`SkillBindingResolver.resolveBindable` 拒绝把内置仓库的技能直接绑给 agent 或 lead，`cli.skill_id` 那一行只能通过选中 CLI 到达运行时。

### 3.5 约束与索引

| 约束 | 位置 | 作用 |
| --- | --- | --- |
| `uk_skill_repository_tenant_active_name (tenant_id, active_name)` | `skill_repository`；`active_name` 是 `IF(active = 1, name, NULL)` 虚列 | 同租户活跃来源名唯一；软删行交出名字，同名可重建 |
| `uk_skill_repository_builtin_guard` | `builtin_guard` 虚列，仅当活跃且名为 `builtin-cli-skills` 时为 1 | 平台内置仓库至多一行 |
| `uk_skill_repo_active_name (repository_id, active_name)` | `skill` | 同仓库活跃技能名唯一 |
| `idx_skill_repository_name (name)` | `skill_repository` | 内置仓库的查找语句不带租户条件，而唯一键以 `tenant_id` 领头，用不上；这条查找发生在每次 agent 规格下发里 |
| `idx_agent_skill_binding_skill_id`、`idx_team_skill_binding_skill_id` | 两张绑定表 | 按技能 id 反向清理绑定的删除路径 |
| `uk_cli_name (name)` | `cli` | 包名即 CLI 身份 |

`SkillInstaller.persist` 在写库前按 `skill.name` 的 100 字符上限拒绝超长名，让报错点名技能而不是点名列。

## 4. 技能来源分类

### 4.1 用户仓库

`source_type` 为 `GIT` / `NPM` / `ZIP` 的行，归某个租户，内容通过 Loader 从外部读入。用户仓库可增删改，可反复重装（ZIP 除外，见下）。

### 4.2 平台内置仓库 builtin-cli-skills

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/constant/BuiltinRepository.kt` 声明了保留名 `builtin-cli-skills` 与它专属的 `source_type = BUILTIN`。该常量在前端 `harnax-webui/src/constants/builtinRepository.ts` 有同名镜像。

- 它随平台预置，`url` 与 `source_config` 都是空串，没有远端，注册表里也没有对应的 Loader；`BUILTIN` 刻意不属于 GIT / NPM / ZIP 三者；
- 它的技能行由包登记流程写入（见「cli.skill_id」）；
- 管理面读写都被挡住：`SkillServiceImpl.requireWritableRepo` 与 `SkillRepositoryServiceImpl.requireNotBuiltin` 按名字拒绝创建 / 修改 / 删除其技能，`SkillSourcePolicy.requireUsableName` 拒绝任何来源使用这个名字，`SkillSourcePolicy.requireRefreshable` 拒绝刷新它；
- 它的技能对每个租户可见、可下发：所有租户判定里内置仓库行是一处固定的豁免，见 `SkillServiceImpl.readable` 与 `SkillBindingResolver.deliverableWithin`。不加这层豁免，随 CLI 到达的技能就只有预置它的那个租户能用；
- agent 与 lead 不能直接绑它的技能，这些技能随 CLI 到达。

### 4.3 Loader 注册表与三种来源

`SkillLoaderRegistry` 注入所有 `SkillLoader` 实现，按 `sourceType` 相等匹配；匹配不到抛 `Unsupported skill source type: <type>`。每个 Loader 提供 `validateConfig`（在取到内容之前把配置错误暴露出来）、`loadSkills(config, tmpDir)`。

| Loader | sourceType | 读取方式 | 关键约束 |
| --- | --- | --- | --- |
| `GitSkillLoader` | `GIT` | JGit clone 到临时目录，再按目录解析 | 克隆超时 180s，跑在独立线程池上以免请求线程被慢远端拖住；地址前缀白名单 `https://` `http://` `ssh://` `git://` `git@`（`file://`、`ext::` 不接受，它们会把「远端仓库」变成任意本地文件访问）；分支名匹配 `^[A-Za-z0-9._/-]+$`；url ≤ 500、branch ≤ 100，与 `skill_repository` 两列同宽；报错信息里抹掉 `scheme://user:info@` 段 |
| `NpmSkillLoader` | `NPM` | `npm install <pkg> --prefix <tmp> --ignore-scripts --no-audit --no-fund`，可选 `--registry` | 包名匹配 npm 规则且 ≤ 214 字符、不以 `-` 开头（防止被当成 CLI 开关）、不含 `..`；超时 120s 并杀掉进程树；输出重定向到文件，回报只取末尾 400 字符（npm 的真实错误在末尾，而 400 小于 `ApiErrors` 的 500 字符截断线，尾巴不会被从前面剪掉）；`--ignore-scripts` 保证不执行下载来的包的生命周期脚本 |
| `ZipSkillLoader` | `ZIP` | 按 `zipPath` 解开临时目录，再按目录解析 | 条目数 ≤ 5000、单条目 ≤ 20 MiB、总解压量 ≤ 200 MiB、条目名 ≤ 512 字符；`zipPath` 这个键缺失时在解包之前就抛 `ZIP source config requires 'zipPath'`，`zipPath` 指向的归档已经不在盘上时抛 `ZIP archive is no longer available. ZIP sources are installed once at upload time; upload the archive again to refresh their skills.` |

ZIP 来源的归档在安装请求结束后即被删除，因此 `SkillSourcePolicy.requireRefreshable` 在任何 Loader 被选中之前先拒绝对 ZIP 来源做刷新或预览——诚实的回答是「重新上传」，而不是 Loader 内部的配置错误。

### 4.4 SKILL.md 解析与资源读取

`SkillFileParser`（`internal object`）被三条装载路径共用：

- frontmatter 以 `---` 界定，只识别顶层 `key: value`，嵌套结构跳过；`name` 缺省时回退到目录名；`description` 缺省时回退到正文里第一条有意义的行（跳过空行、标题、分隔线、表格行，去掉引用符），截断到 500 字符；正文只有标题时描述等于技能名——`AgentSkill.builder()` 拒绝空描述，否则这种技能会在导入中无声消失；
- 资源取自技能目录下的 `resources/`，读成 `相对路径 -> 内容`；单文件超过 512 KiB、整个技能累计超过 4 MiB、或无法按严格 UTF-8 解码（二进制）的文件被跳过并告警，中断的是那一个文件而不是整个技能的导入；
- 附在失败项上的原因文本截断到 200 字符，因为异常消息里可能嵌着整个文件。

一个目录算不算技能，判据是它有没有 `SKILL.md`。没有该文件的目录（`node_modules`、共享资产、`docs`）不被记为失败项：npm 包和归档里例行存在大量从来没打算当技能的目录，报出来会埋掉真正的少数几条。

三条路径的目录发现规则：

| Loader | 从哪里找技能目录 | 说明 |
| --- | --- | --- |
| `GIT` | 交给 agentscope 的 `GitSkillRepository`：仓库里有 `skills/` 子目录时只扫它，否则扫仓库根 | 每个技能必须有自己的一层子目录（`skills/<name>/SKILL.md` 或 `<name>/SKILL.md`）；把 `SKILL.md` 放在仓库根上的单技能仓库会装载为空，`describeEmptyClone` 专门把这一种情况说清楚，克隆目录此时仍在盘上 |
| `ZIP` | 解出的顶层只有一个目录时以它为包装目录；其下（或解压根本身）有 `skills/` 时优先取 `skills/`，然后按名字排序遍历直接子目录 | GitHub 归档的 `<repo>-<ref>/skills/<name>/SKILL.md` 与 GIT 走的是同一个约定；一个技能子目录都没有时，退回到把包装目录自己当一个技能读 |
| `NPM` | `installDir/node_modules/<packageName>`，按名字排序遍历它的一级子目录，每个含 `SKILL.md` 的子目录算一个技能；一级都没命中时再把包根自己当一个技能读 | 这一条路径没有 ZIP 那条 `skills/` 优先规则：`skills/<name>/SKILL.md` 形状的包在 NPM 来源下装不到——`skills` 这一层没有自己的 `SKILL.md`，只会记为跳过，它的子目录不会被访问。解析出的包目录必须仍在 `installDir` 之内（名字已校验过，这一道是纵深防御） |

一条技能目录的读取失败分三种：没有 `SKILL.md`（静默跳过）、`SKILL.md` 存在但为空白（记 `SKILL.md is empty`）、读出来却解析失败（记 `SKILL.md could not be parsed: <原因>`）。后两种进 `failures`，因为丢掉它们会让请求照样返回 200 而计数变小。GIT 路径的逐目录归因发生在 agentscope 内部，那个 Loader 报不出目录级的失败，能报的只有一份干净克隆里为什么什么都没读到。

来源读完为空且没有任何失败项时，`describeEmpty` / `describeEmptyClone` 生成的那句话既进日志也回给调用方：一份被存下来却没有任何解释的空报告，在下一个打开它的人眼里就像平台问题。

### 4.5 装载结果的失败粒度

`SkillLoadResult` 携带 `skills` 与 `failures: List<SkillLoadFailure>`，后者按目录名归因——解析失败恰恰意味着 frontmatter 名字不可用，目录是唯一保证存在的标识。两个保留名占据自己的字段而不是技能列表：

- `<source>`（`SkillLoadFailure.WHOLE_SOURCE`）：整个来源没读成功，落到响应的 `sourceError`；
- `<empty>`（`EMPTY_SOURCE`）：来源读到底、里面没有任何技能，落到 `emptyReason`。

「地址坏了」和「仓库形状和 Loader 预期不一样」是两件事、两种修法，所以分成两个保留名。克隆失败、`npm install` 非零退出、归档打不开这类整体问题仍以异常抛出，由调用方统一转成一条错误。

## 5. 管理面：两套端点、一条落库路径

### 5.1 /api/admin/skill-sources

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/page` | 分页列表 |
| GET | `/active` | 本租户启用来源 + 共享内置来源，供选择器使用 |
| GET | `/{id}` | 单个来源 |
| POST | `/` | 创建即安装：读一次来源，`SkillInstaller.createWithSkills` 在同一事务里插入来源行与其技能，随后 `SkillSyncRecorder.record` |
| POST | `/{id}/install` | 重装；无 body 装整个来源，带 `names` 只装选中的名字 |
| GET | `/{id}/fetch` | 预览来源里可装的技能，并与库里已有名字对照 |
| PUT | `/{id}` | 改名字、描述、版本、可见性、`status`、来源配置；改配置不重装内容，需另调 `/{id}/install` |
| PUT | `/toggle/{id}` | 改 `status` |
| DELETE | `/{id}` | 级联删除 |
| POST | `/upload` | 上传 ZIP 并安装，归档只读一次 |

`updateSkillSource` 中 `isPublic` 一项会带着改写完该来源下所有技能行的 `is_public`，否则一个公开来源里会留着永不列表的私有技能。`applySourceConfigChange` 对 GIT 来源把 `url` / `branch` 与 JSON 配置双向对齐后再校验，对 ZIP 来源忽略配置更新（它没有归档）。

### 5.2 /api/admin/skill-repositories 与 /api/admin/skills

`/api/admin/skill-repositories`：`GET /page`、`GET /active`、`GET /{id}`、`POST`、`PUT /update/{id}`、`PUT /toggle/{id}`、`DELETE /{id}`、`GET /fetch/{id}`。

`/api/admin/skills`：`GET /page`、`GET /{id}`、`POST`、`PUT /update/{id}`、`PUT /toggle/{id}`、`DELETE /{id}`、`POST /batch`。

`POST /skills/batch` 是选择性同步：`SkillServiceImpl.batchSaveSkillsDetailed` 先在事务外读一次来源（一次 clone 或 `npm install` 可能耗时数分钟，不能占住行锁），再把落库交给 `SkillInstaller.persist(only = 选中的名字)`，最后 `SkillSyncRecorder.record`。空选择按「什么都不存」处理，不当作「未给选择」（后者会装下整个来源）。

`PUT /skills/update/{id}` 上的 `status` 被单独路由到 `SkillMapper.updateStatus`：`updateById` 语句的列清单不含 `status`，把字段挂上去不会写进那一列，因此状态变更必须由专用语句完成（`/skill-sources/{id}` 同此处理）。

`SkillController` 与 `SkillRepositoryController` 是 `harnax-cli/cmd/skill.go` 调用的端点（`skillBasePath = /api/admin/skills`、`skillRepoBasePath = /api/admin/skill-repositories`）。

### 5.3 共同的写入策略

`SkillSourcePolicy` 是与入口无关的规则集合，两套端点都调它——检查只落在一个入口上，两个入口就会对同一份坏输入给出不同答案。

| 函数 | 规则 |
| --- | --- |
| `requireUsableText` | 去空白并拒绝空值。`name` 参与重命名判定与唯一索引，带首尾空白的同一个值是两个不同的键 |
| `requireUsableName` | 在上者基础上拒绝保留名 `builtin-cli-skills` |
| `requireStatus` | 只接受 0 或 1。越界值塞得进 tinyint，而每个下发路径都判 `== 1`，于是它永远读成「停用」且开关再也拉不回来 |
| `requireRefreshable` | ZIP 与 BUILTIN 两类来源没有可刷新的来源；ZIP 一项在选择 Loader 之前判定 |
| `requireContentOnCreate` / `requireContentOnUpdate` | 正文与描述不得为空白；更新时未提供的字段保留行上现值，因此一个内容本来就不合规的技能仍可被停用（那通常正是操作者要做的那次编辑）。校验不做 trim，正文按操作者写的样子存 |
| `requireValidResources` | 非空的 `resources` 必须解析成 `Map<String, String>`；空白等于「无随包文件」 |
| `normalizeSelection` | 名字列表去空白、丢空串（空串名的失败项对调用方没有信息量）、去重，上限 `MAX_SKILLS_PER_REQUEST = 1000`（两个选择性安装端点都收任意长度的 JSON 列表并把来源不含的每个名字回报一条失败项，没有上限时一次请求就能写进几万条失败记录，而这份记录会被每次来源列表读取解析并送到浏览器）；`null` 与空列表语义不同——前者是整个来源，后者是「什么都不存」 |

`SkillSourceConfigs` 负责 `source_config`：`parse` 解析 JSON，GIT 来源在解析失败时回退到 `url` / `branch` 两列并打一行告警，非 GIT 来源直接抛出（把 NPM 的配置错误伪装成 Git 形状会藏掉真正的病因）；`forApi` 是纯投影，`zipPath` 这类服务端内部键不出响应，也不从 `url` / `branch` 合成内容——两个响应 DTO 都把那两列平铺在外层，凭空造一个 Git 形状的 map 只会让 GIT 与 NPM 对同样的空输入表现不一致。写入前 `normalized` 会 trim 每个文本值。

### 5.4 SkillInstaller 的落库规则

`SkillInstaller.persist` 是两条入口共用的唯一写路径，独立成一个 bean 也让事务保持在最短：来源读取全部发生在它被调用之前。

- 技能名 trim 后写库；空名、超过 100 字符、`SKILL.md` 正文空白、同一来源里重复的名字，各记一条 `failed`。重复判定用同一次调用内的 `seen` 集合：不加它，来源里声明两次同一名字的第二份会找到同一次调用刚写下的那行、被计成一次 update，于是报告说存了两个技能而库里只有一个；
- 已存在的行（`selectByNameAndRepo`）更新描述、正文、资源 JSON、`version`、`is_public`，计入 `updated`；不存在则插入，`tenant_id` / `is_public` / `creator` 继承来源行，`version` 取来源的 `version`，`active = 1`，计入 `installed`；
- `only` 给定时，按 trim 后的名字建索引（`putIfAbsent`，首个胜出，与整来源导入的一致）再按选择顺序取，因此报告读起来像选择对话框；来源里没有的名字回答 `Not present in the source anymore`，除非它已在 `failed` 里被归因为解析失败，或那一次运行压根没读到来源；
- 内容扫描命中任何规则的技能以 `status = 0` 落库（新建走 `insert` 的初始状态，更新走一次专用 `updateStatus`），并计入 `flagged`；重导入会重新做一次判定；
- `flagged` 只在行确实写成功之后记录，否则同一个名字会同时出现在 `flagged` 与 `failed` 里，报告声称存了一条其实没存的技能；
- 单条技能持久化抛错不静默消失，原因文本经 `ApiErrors.message` 过滤后放进那条 `failed`，随一个成功的 HTTP 响应返回；
- 装载阶段的解析失败项在名字判定之前就被并入同一份 `failed`（一个装了三个技能的「五个全成功」的答复是不成立的），两个来源级保留名各走自己的字段，因此「归档里没有技能」不会读成「1 个技能里有 1 个存失败」；
- `stale`（来源已经不持有、库里仍留着的名字）只在整来源安装且来源读成功时计算：先取这一次同步已存下的名字集合与「来源仍持有 + 这一次失败」的名字集合，库里剩下的那些即 `stale`。此处不删任何东西——被来源丢掉的技能可能仍被绑着，删它会连绑定一起带走。报告它是这件事的全部；
- `createWithSkills` 在事务内再跑一次来源名占用检查，唯一的实际防线仍然是 `uk_skill_repository_tenant_active_name`，这一次重查只为给常见竞态一个可读的错误。

`deleteWithSkills(repository)`：要求该来源下 `status = 1` 的技能数为 0（「N skills are still enabled, so this source cannot be deleted」），再要求这些技能在 agent 与 team lead 两张绑定表里都没有行（单独数一遍，不从状态推断，因为下面那条例外写入可以停用被绑定的技能），然后删绑定行、逐行删技能、删来源行，全程一个事务。

### 5.5 同步结果记录

`SkillSyncRecorder.record` 把一次报告写到来源行上（`last_sync_time` / `last_sync_status` / `last_sync_detail`），并同步镜像回调用方手里的对象——调用方的响应是从同一个对象映射的，服务端刚把来源标成 `FAILED` 就不能以「从未同步」的面目返回。四条写入路径都收口到这里，「什么算一次失败同步」这条规则只有一处。状态判定顺序：

| 状态 | 条件 |
| --- | --- |
| `FAILED` | 报告里有 `sourceError`。先于计数判定：一次什么都没产出的抓取不能读成空来源，那是另一个问题、另一种修法 |
| `EMPTY` | `savedCount == 0`，无论来源是空的还是所有候选都没存进去。`PARTIAL` 声称有一部分，而零的一部分还是零 |
| `PARTIAL` | 有落库且同时有 `failed` |
| `SUCCESS` | 其余 |

`last_sync_detail` 的 JSON 键固定为 `saved`、`installed`、`updated`、`failed`（每项 name + reason）、`flagged`（每项 name + reasons）、`stale`、可选的单一 `error`（`sourceError` 优先于 `emptyReason`，徽标已经区分了「来源坏了」与「来源是空的」，UI 两种情况都在下面显示一句话）。`SkillSyncRecorder.forApi` 把库里的报告解成响应字段，解不出来时回答 `null`，让列表继续渲染它的徽标而不是因一行坏数据整个失败。

### 5.6 内容安全扫描

`SkillContentScanner.scan(skillmd, resources)` 扫正文加每一个非空白的资源文件。命中规则不拒绝导入——一个文档型技能有正当理由引用危险命令——而是把技能降为 `status = 0`，由人工确认后手工启用。规则集共 9 条：

| ruleId | 判定 |
| --- | --- |
| `recursive-root-delete` | 递归删除根级路径 |
| `windows-drive-wipe` | 清空白盘（`del/erase /s /f /q X:\`、`format X:`） |
| `disk-overwrite` | 覆写块设备或文件系统（`dd ... of=/dev/`、`mkfs /dev/`） |
| `remote-pipe-to-shell` | 把远端载荷直接管进 shell |
| `reverse-shell` | 反弹 shell（`/dev/tcp/`、`nc -e`、`bash -i >& /dev/`） |
| `fork-bomb` | fork 炸弹 |
| `permission-escalation-of-root` | 递归放开系统路径权限（`chmod -R 777 /`） |
| `history-and-audit-tampering` | 清除 shell 历史或审计日志 |
| `credential-harvest-upload` | 把本地凭据外传到远端（`curl -d/-F/-T ...` 且命中 `credentials`、`.ssh/`、`.aws/`、`.netrc`、`id_rsa`） |

命令边界由 `(?:^|[^\w-])` 与 `(?:[^\w]|$)` 两段拼出：技能正文里的 shell 片段大量出现在 Markdown 行内代码、围栏块和句子里，只按 shell 元字符匹配会漏掉大多数；任何不能成为命令名一部分的字符都同时是命令的起止边界。尾部排除 `-` 是有意的，`x-rm` 这类连字符名字因此不会被读成裸 `rm`。每条命中记录 `resource`、`ruleId`、`reason` 与命中点前后各 20/120 字符的摘录。

扫描存在的理由：随包 `resources/` 文件会落到 agent 的工作区，shell 与文件工具在那里可以执行它们，一个导入的包因此等价于不可信代码。

### 5.7 停用与删除的前置条件

`SkillServiceImpl.requireUnbound(skill, action)` 在停用（`toggleSkillStatus` 置 0、`updateSkill` 里的 status 变更）与删除两条路径上生效，只要下面三项非零就拒绝：

| 持有者 | 判定读取 | 拒绝语 |
| --- | --- | --- |
| agent | `AgentSkillBindingMapper.selectAgentBindingCounts` | `Skill '<name>' is bound to N agents, so it cannot be disabled/deleted` |
| 团队 lead | `TeamSkillBindingMapper.selectTeamBindingCounts` | 单数说 `a team lead` |
| CLI 包 | `CliMapper.selectBySkillIds` | `Skill '<name>' ships with CLI package(s) ..., so it cannot be ... from here` |

三个计数与技能列表页响应里渲染的是同一批分组读取（`SkillServiceImpl.boundAgentCounts` / `boundTeamCounts` 喂给 `SkillResponse` 的 `boundAgentCount` / `boundTeamCount`），页面上的数字和这道闸的判定不会各自漂移。一个只绑在 lead 上的技能，其 `boundAgentCount` 为 0 而 `boundTeamCount` 为 1，仍会被拒绝——两项都在页面上。

CLI 包那条的理由是所有权：`cli.skill_id` 就是包拥有这一行的方式，包登记流程会随包改写或删除它，技能页不是它的开关。

一次删除比一次停用条件更严：删除会级联清掉绑定，因此不能拥有更松的前置条件。`deleteSkill` 在 `requireUnbound` 之后仍显式调 `deleteBySkillIds`，不留悬空行。

### 5.8 技能行的手工创建与更新

`SkillServiceImpl.createSkill`：`requireUsableText` 名字 → `repositoryId` 必填 → `status` 缺省 1 并过 `requireStatus` → 内容与 resources 校验 → 鉴权（`requireWritableRepo`）先于名字占用探测（对不可写的仓库探测名字是否被占用本身就是一次泄漏）→ 同仓库重名拒绝 → 落行，`isPublic` 取仓库值、`tenantId` 取 `TenantResolver.resolve`、`creator` 取当前用户名。

`updateSkill`：先按 id 取行并 `requireReadable`，再 `requireWritableRepo(skill.repositoryId)`（内置仓库的技能只读，技能也不能进出该仓库），若请求换了仓库则目标仓库同样要可写；名字与仓库都解析成最终值之后再做占用检查，一次保持名字不变的移动若撞上目标仓库里的同名行，回答的是「Skill name already exists」而不是唯一索引抛出的裸 SQL 错误；换仓库时 `isPublic` 跟随目标仓库；描述 / 正文 / 资源按字段选择性更新。

## 6. 可见性与租户语义

### 6.1 列表的可见性谓词

`SkillMapper.selectSkillList`（`harnax-entity/src/main/resources/mapper/SkillMapper.xml`）的条件：

```
active = 1
AND (is_public = 1 OR creator = #{currentUsername})
AND (tenant_id = #{tenantId} OR repository_id = #{builtinRepositoryId})
```

外加 `name` 模糊、`repository_id`、`status` 三个可选过滤，按 `update_time DESC` 排序。`tenantId` 为 null 时整个租户段缺席（内部 / 系统调用）；`builtinRepositoryId` 为 null 时豁免段同样缺席。分页参数被夹到 `pageNum >= 1`、`pageSize ∈ [1, 1000]`。

### 6.2 单行读取按可见性解析、不可见时按缺席回答

`SkillServiceImpl.getSkill(id)` 是 `skillMapper.selectById(id)?.takeIf { readable(it) }`。一行存在但当前调用方看不见它时，返回 null——与「id 不存在」同一个回答。理由：这一行带着完整的 `SKILL.md` 与全部资源文件，跨租户读到即是内容外泄；而显式拒绝会确认「这个技能存在、属于谁」，控制器还会把它变成 500。

写路径需要一个说法，所以那里用 `requireReadable`，抛出带 `error.skill.no_permission` 文案的 `BizException`。

### 6.3 三处租户口径

| 口径 | 实现 | 用法 |
| --- | --- | --- |
| 可见性判定 | `SkillServiceImpl.readable` / `requireWritableRepo` 直接读 `TenantContext`，null 视为「内部调用，无租户可依据」→ 放行 | 把 null 解析成某个具体租户，会让内部调用变成一次对默认工作区的成员校验 |
| 需要一个确定的租户 id | `TenantResolver.resolve(jwtUtil)`：没有 `X-Tenant-ID` 的请求读作调用方自己的租户，而不是租户 1 | 新技能行的 `tenant_id` 戳记、绑定保存期的租户基准 |
| 下发的租户基准 | `buildAgentSpecResponse(agentTenantId)`，取自 agent 行或 team 行，从不取自请求 | `SkillBindingResolver.deliverable(skillIds, tenantId)` |

`SkillBindingResolver.deliverableWithin` 的谓词是 `it.tenantId == tenantId || it.repositoryId == builtinRepositoryId`。这个过滤存在的根据：`SkillMapper.selectByIds` 没有租户条件，而内部下发调用不带可信的租户头，所以持有者自己的租户是唯一的可比依据；没有它，一条保存期约束之前写入的跨租户绑定行就会把另一个租户的 `SKILL.md` 和全部随包文件交出去。内置仓库那一行是全平台单行，因此必须豁免。

`SkillBindingResolver.resolveBindable` 里的内置仓库查找刻意不带租户过滤：按租户查会漏掉这一行平台级记录，从而漏掉「内置仓库技能不可直接绑定」这条约束。

### 6.4 可见性跟随仓库

`skill.is_public` 由来源决定，四处落地：`SkillServiceImpl.createSkill` 取来源行的值；`updateSkill` 在技能换仓库时取目标仓库的值；`SkillInstaller.persist` 对插入与更新都取来源行的值；`SkillSourceServiceImpl.updateSkillSource` 改 `isPublic` 时批量带上该来源下所有技能行。不跟随的后果是可见性错配：私有仓库里的技能在列表里看不见，公开来源下的私有技能永远不出现。

## 7. 绑定

### 7.1 Agent 的技能绑定

`AgentServiceImpl.saveSkillBindings(agentId, skillList)` 在创建与更新 agent 时调用：`deleteByAgentId` 整组清空 → 逗号分隔的 id 串拆成 `Long` 列表（不可解析的项丢弃）→ `SkillBindingResolver.resolveBindable` → `batchInsert`。绑定行除了一对 id 与两个时间戳不写任何东西。

`AgentServiceImpl` 的详情组装从绑定表回读 `AgentResponse.SkillItem`（skillId / skillName / skillDescription / repositoryId / repositoryName），逐条走 `skillService.getSkill`，取不到即跳过。

### 7.2 团队 lead 的技能绑定

`TeamServiceImpl` 在创建与更新时把 `request.skillIds` 交给同一个 `resolveBindable`，写 `team_skill_binding`。lead 配置页回读走 `TeamServiceImpl.skillsOf`，它调的是 `deliverable(...)`——与绑定期、下发期同一个租户谓词，因此页面列出的正好是运行时真会加载的那些。

### 7.3 CLI 包技能的到达方式

包技能的可见性来自 `cli.skill_id`：`AgentServiceImpl` 的详情组装把每个 `AgentResponse.CliItem` 的 `skillList` 填成该包自带的那一条，让「这个 CLI 和它教给 agent 的东西」在配置面板上读成一件事。它不出现在技能选择器里，因为 `resolveBindable` 拒绝内置仓库的技能被直接绑定。

### 7.4 保存期约束

`SkillBindingResolver.resolveBindable(skillIds)` 对 agent 与 lead 两个入口执行同一套判定，id 先 `distinct()`：

1. 每个 id 必须解析成当前租户可见的活跃技能（内置仓库行豁免，与 `SkillServiceImpl.readable` 同一规则）；解析不到的整体拒绝并点名缺失 id —— `Skill is missing, deleted, or outside your tenant: ...`；
2. `status != 1` 的技能不能绑 —— `Skill is disabled, enable it before binding: ...`；
3. 来自 `builtin-cli-skills` 的技能不能直接绑 —— `Skills from 'builtin-cli-skills' cannot be bound directly (auto-loaded via CLI): ...`；
4. 一次绑定里不能有同名技能 —— `Skills bound together must have distinct names, duplicated: ...`，因为 harness 按名字索引技能。

四条全部发生在保存期，而运行时的技能丢弃只留一行日志：一个会在运行时被无声丢掉的绑定，等于操作者在没看着那个配置页的时候丢了一个技能。

## 8. Admin → agent-service 下发契约

### 8.1 内部端点与载体

`InternalApiController`（`@RequestMapping("/api/admin/internal")`）提供 `GET /agent-spec/{sessionId}` 与 `GET /team-spec/{sessionId}`，返回 `AgentSpecInfoResponse`（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/AgentSpecInfoResponse.kt`）。技能部分两个字段：

- `skillDetails: List<SkillDetailDto>` —— 该 agent 或 lead 自己绑定的技能；
- `cliDetails[].skill: SkillDetailDto?` —— 每个被选 CLI 包自带的技能。

`SkillDetailDto` 是六元组：`id`、`name`、`description`、`skillmd`、`resources`、`version`，由 `InternalApiController.skillDetail(skill)` 从实体逐列映射。`tenant_id`、`creator`、`is_public`、`status`、`active` 不过界——它们已经在下发判定里被消费掉了。响应的 `tenantId` 是持有者行自己的租户，agent-service 把它落到 `token_stats` / `tool_invocation_log` / `process_log` 上。

### 8.2 下发解析

`buildAgentSpecResponse` 是唯一的构造点，`skillIds` 是它的入参之一：普通 agent 由默认值取 `AgentSkillBindingMapper.selectByAgentId`，lead 由 `specForTeam` 取 `TeamSkillBindingMapper.selectByTeamId`，同时 `toolBindings` / `mcpBindings` / `cliBindings` / `requiredToolIds` 传空列表——lead 有自己的提示词、模型和租户，也允许有技能，除此之外不解析任何能力配置，因为它没有 shell 也没有沙箱去运行一个工具。

技能解析：`skillIdsToDeliver = skillIds.distinct()` → 一次 `skillBindingResolver.deliverable(skillIdsToDeliver, agentTenantId)` 拿到全部可解析行并按 id 建索引 → 按请求顺序 `mapNotNull` 成 `skillDetails`。空 id 列表在进入解析器前就返回空（`selectByIds` 传空列表会渲染出 MySQL 拒绝的 `IN ()`），`deliverable` 也先做非空检查再花一次内置仓库查询。

### 8.3 缺席与停用的回答

| 情形 | 回答 | 日志 |
| --- | --- | --- |
| 解析不到（行不存在、`active = 0`、跨租户） | 从 `skillDetails` 里缺席 | WARN，点名 skillId 与 agent 租户 |
| `status == 0` | 从 `skillDetails` 里缺席 | INFO |
| 正常 | 一条 `SkillDetailDto` | — |

CLI 包自带的技能走同一个解析器、同一个租户基准，另外多两道判定：`cli.status == 0` 时整个 CLI 项不下发；`cli.skill_id` 指向的行解析不到（`CLI '{}' (id={}) points at skill {} which is gone or outside agent tenant {}`）或该技能自己被停用时，`skill` 字段为 null，CLI 项照常下发。包登记流程会让那一行跟随 `cli.status`，所以 `skill == null` 只剩「这一行被单独停用或删除过」两种原因。

### 8.4 一次下发的收敛

`AgentSpecResolver.withCliSkills`（agent-service）把 `cliDetails[].skill` 注入技能清单，规则是：

- 先按 id 去重，同一条技能不会因两条路径出现两次；
- 再按名字去重，注入方让位：agent 自己绑定的技能与包技能重名时，包技能不注入，操作者的显式绑定赢，并打一行 INFO 列出被遮蔽的名字；
- 注入项排在清单最前（`injectedSkills + specInfo.skillDetails`）。

这一步必要的根据：技能名只在仓库内唯一（`uk_skill_repo_active_name`），跨仓库可以撞名，而 harness 按名字索引技能（`AgentSkill.getSkillId()` 是 `name + "_" + source`），两条同名的技能同时下发会让注册表与内存仓库对「哪一份是活的」给出不同答案。

`cliDetails` 里 `skill == null` 的 CLI 会打一行 WARN 点名会话与 CLI 清单：包没登记技能，或那一行已不在。

`buildAgentSpec` 随后把每条 `skillDetails` 变成 `SkillSpec(skillId, skillName)` 装进 `AgentSpec.skills`。

## 9. 运行侧装载链路

### 9.1 从规格到 AgentSkill

`HarnessAgentLauncher` 遍历 `agentSpec.skills`，对每个 id 调 `skillAdaptor.getSkill(skillId)`：非 null 才 `agentBuilder.addSkill(skill)`；null 则一行 WARN 并继续——一个坏技能不带走这个 agent 的其他能力。lead 走的是同一条循环：技能属于团队自己的配置，而 lead 确实装载它们。

`SkillAdaptorImpl`（agent-service）只从 `AgentSpecContextHolder` 当前持有的 `skillDetails` 里按 id 找：

| 情况 | 行为 |
| --- | --- |
| `skillId <= 0` | WARN + null |
| 下发里没有这一条 | WARN（`Skill N is not in the delivered spec, so it cannot be loaded here`），返回 null |
| `resources` 空白 | 装载，无文件 |
| `resources` 解析失败 | 装载，无文件，WARN 点名技能名与 id |
| `AgentSkill.builder()` 抛错（名字 / 描述 / 正文空白） | ERROR 点名「已下发但无法装载」，返回 null。与上一行区分：那一条是找不到的技能，这一条是行存在且已下发但内容不合规，混为一谈会让操作者去找一次并不存在的删除 |

`AgentSkill` 由 `name`、`skillContent = skillmd`、`description`、`resources`（`Map<String, String>`）四项构成。这个类不查技能表：Admin 已经决定了本次会话允许装载什么——删除、停用、够不着都体现为下发列表里的缺席——而 `SkillMapper.selectById` 只过滤 `active`，回查数据库会把 Admin 已经扣留的内容（包括别的租户的）交回去。

### 9.2 内存技能仓库

`HarnessAgentBuilder`：

- `addSkill` 按名去重、后写覆盖先写，并打一行 WARN 说明保留的是最后一条绑定。绑定表按 id 排序读取，「最后注册的那条」必须是确定的答案，否则配置页列一条、技能仓库解析另一条；
- `build()` 先 `builder.disableDefaultWorkspaceSkills()`：harness 默认会从容器工作区读 `skills/` 目录并合并进来，关掉它，agent 就不能靠往自己沙箱里写一个同名 `SKILL.md` 来覆盖操作者配置的技能。`enableSkillManageTool` 从未被调用，运行时也不 provision 任何技能目录，Admin 是唯一的技能来源；
- 技能通过 `InMemorySkillRepository(skills.toList())` 注册（`skills` 非空时才注册），其 `getSource()` 返回常量 `IN_MEMORY_SKILL_SOURCE = "in-memory"`，`getRepositoryInfo()` 返回 `(in-memory, "memory", false)`；该仓库的 `save` / `delete` 都返回 false，`getSkill(name)` 取第一条名字匹配的技能。

### 9.3 交付集的回读

`HarnessAgentWrapper.deliveredSkills()` 从已构造的 `HarnessAgent.skillRepositories` 里筛出 `source == IN_MEMORY_SKILL_SOURCE` 的那些仓库，把它们的全部技能按名合并（后写赢）后返回。这是投影唯一的技能来源：框架自己的 workspace 仓库会叠在它们之上，按 source 过滤才把范围锁在鉴过权的那一份；合并规则与 harness 自身解析跨仓库重名的规则一致，投影不能和提示词对「这个名字指哪个技能」给出不同答案。

回读异常被捕获并返回空清单，与投影同一姿态。

## 10. 技能文件到沙箱的投影

### 10.1 谁投影、何时投影

`HarnessAgentWrapper` 持有一个懒初始化的 `SandboxSkillProjector(sandboxWorkspaceRoot)`（配置项 `harness.sandbox.workspace-root`，默认 `/workspace`）；从未拿到保活沙箱的 wrapper 因此不会构造它。

在 `buildRuntimeContext()` 里，`KeepAliveSandboxManager.getOrCreate(sessionId, WorkspaceSpec(), ...)` 取到本会话的容器句柄之后、agent 开跑之前调用 `projectSkills(sandbox)`。阻塞与流式两条路径都经过 `buildRuntimeContext`，因此复用容器上的每个回合都会执行一次投影；上面的 `WorkspaceSpec()` 是空的，没有别的东西会往容器里放文件。

`projectSkills` 先取 `deliveredSkills()`：为空则直接返回——这个 agent 没有技能时容器一个字节都不动，连 `skills` 目录都不创建；非空时交给 `skillProjector.project(sandbox, skills)`。外层再包一层 catch，覆盖 projector 自身构造（`project` 内部吞掉写入期的一切异常）。

### 10.2 目录布局

`SandboxSkillProjector.skillsRoot = workspaceRoot.trimEnd('/') + "/skills"`，一个技能一个目录，目录名取 `AgentSkill.name`：

```
/workspace/skills/<skillName>/SKILL.md      <- AgentSkill.skillContent（admin 的 skillmd 列）
/workspace/skills/<skillName>/<relative>    <- AgentSkill.resources 的每一项
/workspace/skills/.harnax-skills.json       <- 清单
```

技能根是容器内部的绝对路径，与 `Sandbox.exec` 解析路径用的根、输出文件检测器扫的 `/workspace/output`、团队编排器写产物用的根是同一个，也正是 agentscope 的 `ShellPathPolicy` 渲染进技能 `<files-root>` 的那个前缀。因此一个写着「运行 `scripts/run.sh`」的技能，文件就落在提示词给模型的那个路径上。`SKILL.md` 的文件名与 agentscope 工作区技能的命名一致（`SKILL_FILE`）。

### 10.3 清单与逐轮收敛

清单是「相对技能根的路径 -> 字节内容的 SHA-256（`sha256:` 前缀 + 十六进制）」的 JSON 对象，哈希在本类计算——上游任何地方都不存在内容哈希。保活容器跨轮复用，因此这件事不能只做追加写入。`converge` 每轮执行：

1. 读清单；文件不存在（该会话的第一个回合，或这个容器从未被投影过）或解不出来时视为空清单——一份读不动的清单对磁盘上有什么不作任何陈述，于是这一次重写它拥有的全部文件；
2. `stale` = 清单里有、当前期望集里没有的路径；`pending` = 期望里哈希与清单不一致的路径；
3. 期望集与清单都为空时直接返回 `ProjectionResult.EMPTY`，容器完全不动；两者一致（无 pending 无 stale）时返回 `unchangedFiles = 期望数`，一次写都不发；
4. 先删后写：当前期望集里已经没有的技能，其目录递归删除；只是资源被改名或丢弃的，单文件删除。删除顺序在前，是为了让这一回合移动了文件的技能收敛而不是留下两个目录。删除只覆盖清单登记过的路径，技能根下 agent 自己创建的东西一律不动；
5. 写 `pending`，把清单里保留下来的路径连同这一次新写入的哈希组成新清单；
6. 清单最后写；新清单为空时删除清单文件。

信任放在清单上而不是逐文件 stat：一个在会话中途删掉自己技能文件的 agent，要等容器重建才会拿回文件，而不是等下一个回合。

`ProjectionResult` 汇报这一回合的变化，供日志与测试断言：`written`（这一回合（重）写的相对路径）、`deleted`（被删的失效路径，技能目录以 `name/` 形式出现）、`rejected`（被拒绝而从未写入的项，含原因）、`unchangedFiles`（磁盘上已与下发一致、无需再写的文件数）、`changed`（`written` 或 `deleted` 非空）。

### 10.4 投影什么、拒绝什么

`desiredFiles` 在写之前逐项筛，被拒项收进 `rejected` 并带名字与原因打成一行 WARN——操作者的下一个问题是「我的 agent 为什么跑不了它的脚本」：

| 拒绝条件 | 说明 |
| --- | --- |
| 技能名不是单个安全路径段 | `SandboxFileWriter.safeRelativePath(name)` 为空或含 `/`；一个技能一个目录，目录名必须是一段 |
| `skillContent` 为空白 | 没有 `SKILL.md` 可写（`Skill '<name>' (no SKILL.md content was delivered)`） |
| 资源 key 以 `/` 开头，或不通过 `safeRelativePath` | 绝对路径、越出技能目录的 `..`、空白 key |
| 资源 key 解析后等于 `SKILL.md` | 不允许替换主文件 |
| 资源值为 null | 无内容可写 |

资源 key 是 admin 写的，但它点名的是本进程即将在容器里创建的一个文件，因此按不可信输入校验：必须相对，且不得爬出该技能的目录。

### 10.5 失败语义

`project()` 不抛异常。整体 try/catch：投影出错时打一行 WARN（点名技能根、技能名清单和原因），该回合在「模型能读到技能文本、跑不了脚本」的状态下继续。技能文件是对一次 Admin 已经鉴过权的回合的增强，容器层面的意外——`mkdir` 被拒、磁盘满、exec 超时——不该把它变成一次失败的回复。清单在该回合所有文件就位之后才重写，因此失败的写入到下一个回合会重做，不会出现「文件没写、清单已记」的半提交状态。

## 11. 边界与明确不做

| 边界 | 落点 |
| --- | --- |
| 技能不存在按技能解析的环境变量通道，两张绑定表都没有相关列 | `AgentSkillBinding`、`TeamSkillBinding`、`AgentServiceImpl.saveSkillBindings` |
| 技能内容不落磁盘，`skill` / `skill_repository` 都没有内容路径列 | 两个实体的列清单、`SkillMapper.xml` |
| 运行时不写技能：内存仓库的 `save` / `delete` 返回 false，默认工作区技能关闭，无技能管理工具 | `HarnessAgentBuilder` |
| 运行侧不回查技能表，缺失即缺席 | `SkillAdaptorImpl`、`SandboxSkillProjector`、`HarnessAgentWrapper.deliveredSkills` |
| 内置仓库的技能不可创建 / 修改 / 删除 / 刷新，不可直接绑定 | `SkillSourcePolicy`、`SkillServiceImpl.requireWritableRepo`、`SkillRepositoryServiceImpl.requireNotBuiltin`、`SkillBindingResolver` |
| ZIP 来源一次性安装、无归档留存，配置更新对其无效 | `SkillSourcePolicy.requireRefreshable`、`SkillSourceServiceImpl.applySourceConfigChange` |
| 来源丢掉的技能只汇报不删除 | `SkillInstaller.staleNames` |
| 内容扫描不拒绝导入，只降为停用 | `SkillContentScanner`、`SkillInstaller.persist` |
| 被 agent / lead / CLI 包持有的技能不能被停用或删除 | `SkillServiceImpl.requireUnbound`、`SkillInstaller.deleteWithSkills` |
| `cli` 表没有任何页面或 API 写入入口，只有包登记流程写 | `CliPackageAutoRegistrar` |
| 投影不删除自己没写过的路径，也不为没有技能的 agent 创建技能根 | `SandboxSkillProjector.deleteStale`、`HarnessAgentWrapper.projectSkills` |
| 投影失败不影响该回合的回复 | `SandboxSkillProjector.project`、`HarnessAgentWrapper.projectSkills` |
| 一次请求选择的技能名上限 1000 | `SkillSourcePolicy.MAX_SKILLS_PER_REQUEST` |
| 技能装载失败不影响该 agent 的其他能力 | `HarnessAgentLauncher` 的技能循环 |

## 12. 关键文件索引

管理面（harnax-admin）

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

实体与持久层

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

运行侧（harnax-agent）

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

接口面

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

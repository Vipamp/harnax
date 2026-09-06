# Harnax Skill 技能体系全流程（中文）

> 英文版本见 [skill-management.en-US.md](./skill-management.en-US.md)
>
> 工具体系整体设计见 [tool-capability.zh-CN.md](./tool-capability.zh-CN.md)，MCP 服务管理见 [mcp-management.zh-CN.md](./mcp-management.zh-CN.md)。本文覆盖 Skill 的数据模型、仓库分类、同步落库、Agent 配置、配置下发与运行时装配全链路，文末附全链路走读发现的问题、修复记录与仍待办事项。

## 1. 概述

Skill（技能）是按 `SKILL.md` 规范组织的能力包：一份 Markdown 说明书 + 若干附属资源（脚本、模板等）。它与内置工具（`BUILTIN`）、HTTP 代理工具（`HTTP`）、MCP 服务并列，是 Agent 的四类能力来源之一。

核心设计：**技能内容直接落库**——`skill.skillmd` 保存 SKILL.md 全文，`skill.resources` 保存 `Map<相对路径, 文件内容>` 的 JSON。运行时不访问仓库文件，因此同步落库之后即使远端仓库不可达，也不影响 Agent 使用技能。

安装是**部分成功语义**：一个来源里可能只有部分技能能落库（解析失败、内容为空、超配额、写库异常），接口返回 HTTP 200 不代表全部成功。响应携带 `SkillInstallResponse`，把每个技能归入 `installed` / `updated` / `failed`（带原因）/ `flagged`（内容扫描命中，已置为禁用）四个桶之一，调用方必须按它提示用户——早期实现只 `log.error` 后继续，导致「接口成功、列表是空」且无任何提示。

分层与 tool / MCP 体系一致：

- **harnax-entity**：`skill_repository` / `skill` / `agent_skill_binding` / `cli_skill_binding` 四张表与 Mapper；
- **harnax-admin**：仓库 CRUD、多来源 Loader、同步落库、绑定管理、内部 API 下发；
- **harnax-agent-service**：拉取智能体配置、内置技能缓存、运行时装配；
- **harnax-harness-core**：把 `AgentSkill` 交给 agentscope（包装为 `InMemorySkillRepository`）；
- **agentscope**：技能目录注入系统提示词 + 按需加载全文与资源。

## 2. 数据模型

### 2.1 skill_repository（技能来源仓库）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/SkillRepository.kt`

| 字段 | 说明 |
|------|------|
| `name` | 仓库名，**租户内唯一**（`selectByName(name, tenantId)` + 唯一索引 `uk_skill_repository_tenant_active_name`），`builtin-cli-skills` 为平台保留名（另有 `uk_skill_repository_builtin_guard` 保证全库只有一个内置仓库） |
| `sourceType` | 来源类型：`GIT` / `NPM` / `ZIP` / `BUILTIN`（`BUILTIN` 为平台预置，无对应 Loader，不可拉取或重装） |
| `sourceConfig` | 来源配置 JSON（新口径），如 `{"url": "...", "branch": "main"}` 或 `{"packageName": "..."}` |
| `url` / `branch` | legacy 列，仅 GIT 类型冗余写回，便于旧代码读取 |
| `version` | 版本标识，同步落库时写入 `skill.version` |
| `storagePath` | 预留字段，当前无写入方（见 TODO-1） |
| `status` / `isPublic` / `creator` / `tenantId` / `active` | 启停、公开、归属与逻辑删除 |

配置解析有两个入口，语义刻意不同：

- `SkillSourceConfigs.parse(repository)`（**给 Loader 用**）：优先解析 `sourceConfig` JSON；解析失败时只有 GIT 类型回退 legacy `url` / `branch` 列，NPM / ZIP 直接抛 `IllegalStateException`——对它们合成一份 Git 样式配置，只会让真实原因（`sourceConfig` 损坏）被「requires 'packageName'」掩盖；
- `SkillSourceConfigs.forApi(repository)`（**给响应 DTO 用**）：`sourceConfig` 的纯投影，为空返回 `null`，过滤掉 `zipPath` 这类不出服务器的内部键，不从 legacy 列合成任何内容。两个响应 DTO 共用它，两套 API 的 `sourceConfig` 语义不会再分叉。

### 2.2 skill（技能主表）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt`

| 字段 | 说明 |
|------|------|
| `name` | 技能名，**仓库内唯一**（`selectByNameAndRepo(name, repositoryId)` + 唯一索引 `uk_skill_repo_active_name`），同步时以此为 upsert 键 |
| `repositoryId` | 所属仓库 |
| `description` | 描述，优先取 SKILL.md 的 YAML frontmatter，回退正文首个有效行（截断 500 字符），再回退技能名 |
| `skillmd` | SKILL.md 全文（`MEDIUMTEXT`，见 `V9__skill_content_in_mysql.sql`） |
| `resources` | 附属资源 JSON：`{"scripts/foo.sh": "内容...", "templates/bar.md": "内容..."}`（`MEDIUMTEXT`） |
| `version` | 跟随仓库版本 |
| `isPublic` | **继承所属仓库**（新建与更新都同步刷新）。列表查询条件是 `(is_public=1 OR creator=当前用户)`，早期实现把它写死为 0，导致仓库设为公开后其他用户能看到仓库却看不到任何技能 |
| `status` | 0 禁用 / 1 启用（禁用技能不参与下发与内置注入）；内容扫描命中时强制置 0 待人工审核 |

### 2.3 绑定表

- `agent_skill_binding`：`agentId` + `skillId`，智能体直绑技能；`envBindings` 列存在但保存时不写入（见 TODO-1）；
- `cli_skill_binding`：`cliId` + `skillId`，CLI 工具关联技能，语义是「教会 Agent 使用这个 CLI」。

### 2.4 SkillDetailDto（内部 API 下发载体）

`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/SkillDetailDto.kt`：携带 `id` / `name` / `description` / `skillmd` / `resources`（`Map<String, String>`），Agent 侧凭此直接还原 `AgentSkill`，无需再查库。

## 3. 仓库分类

### 3.1 用户仓库

通过管理 API 创建，可增删改；所有写操作经 `requireWritable` / `requireSameTenant` 做租户隔离校验。

### 3.2 内置仓库 `builtin-cli-skills`（平台托管、只读）

常量：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/constant/BuiltinRepository.kt`

- 由 Flyway `V12__seed_builtin_cli_skills.sql` 初始化（含 `harnax-cli` 技能），`V14` 更新过其 SKILL.md，`V15` 修正其来源语义（`source_type` 由 `ZIP` 改为 `BUILTIN`，`source_config` / `url` 由 NULL 改为空串）；
- 定位方式确定性：`SkillRepositoryMapper.selectBuiltinRepository(name)` 按 `name` + `active = 1` 查询、`ORDER BY id ASC LIMIT 1`，**不依赖调用方租户**。这里刻意不再按 `source_type` 过滤——`V15` 的 `uk_skill_repository_builtin_guard` 已保证全库至多一行叫这个名，再叠一层类型过滤只会让 `V15` 尚未执行的库查不到内置仓库，把「注入每个会话」的技能静默丢掉。内置技能会注入每一个会话，早期用带租户的 `selectByName` 查询，既会在存在同名仓库时返回不确定结果，也会让非 1 号租户查不到内置仓库、进而跳过绑定约束校验；
- **管理 API 完全只读**：写操作经 `requireNotBuiltinRepo` / `requireWritable` 拦截，仓库名在创建、上传、改名三个入口一律被保留名校验拒绝；
- 三条专属流转规则：
  1. CLI 绑定的技能**只能**来自该仓库（`CliServiceImpl.saveSkillBindings` 校验）；
  2. 智能体**不能**直绑该仓库的技能（`AgentServiceImpl.saveSkillBindings` 抛错，提示「auto-loaded via CLI」）；
  3. 该仓库全部 `status=1` 的技能经 `/api/admin/internal/builtin-skills` 下发，由 agent-service 启动时全量缓存并**注入每一个会话**（见第 7 节）。

### 3.3 来源 Loader（三种）

注册与分派：`SkillLoaderRegistry.getLoader(sourceType)`。

| Loader | 加载方式 | 关键细节 |
|--------|---------|---------|
| `GitSkillLoader` | 委托 agentscope 的 `GitSkillRepository(url, branch, tmpDir)`，clone 到临时目录后解析 | clone 跑在独立 daemon 线程池 `git-skill-loader` 上，180 秒超时（`@PreDestroy` 里 `shutdownNow()`）；URL 协议白名单 `https://` / `http://` / `ssh://` / `git://` / `git@`，拒绝 `-` 开头（防参数注入）与 `file://` / `ext::`（防探测本地与内网仓库）；`branch` 校验 `^[A-Za-z0-9._/-]+$`，缺省 `main`；临时目录用完即删 |
| `NpmSkillLoader` | `npm install <pkg> --prefix <dir> --ignore-scripts --no-audit --no-fund [--registry <r>]` | **`--ignore-scripts` 关掉 lifecycle 脚本**（npm 默认会执行目标包及其依赖的 `preinstall` / `install` / `postinstall`，等于在 admin 进程内跑任意代码）；120 秒超时、子进程输出重定向到文件防阻塞；包名正则 `^(?:@[a-z0-9~][a-z0-9._~-]*/)?[a-z0-9~][a-z0-9._~-]*$`（不含连续点号）、长度上限 214、显式拒绝包含 `..`；registry 校验 `^https?://[^\s]+$`；安装结果目录必须落在 `installDir` 内（纵深防御）；先扫子目录找 SKILL.md，找不到回退包根目录 |
| `ZipSkillLoader` | 解压本地 zip 后解析 | **Zip-Slip 防护**（entry 规范化路径必须落在解压目录内）+ **解压配额**：entry 数 ≤ 5 000、单文件 ≤ 20 MB、总量 ≤ 200 MB、entry 名 ≤ 512 字符，声明大小与实际写入字节双重校验（防 zip bomb）；`Files.copy` 带 `REPLACE_EXISTING`（zip 内重复 entry 不再抛 `FileAlreadyExistsException`）；单一根目录自动下钻一层；zip 不存在时直接提示「上传时已一次性安装」 |

统一解析规则（`SkillFileParser`）：目录内存在 `SKILL.md` 即视为一个技能；技能名与描述优先取 **YAML frontmatter**（支持 `key: value`、块标量 `|` / `>` / `|-` / `>-`、缩进续行、双/单引号剥离），技能名回退目录名，描述回退正文首个有效行（跳过空行、`#` 标题、全是 `-` / `*` / `_` / `=` 的分隔线、`|` 表格行，截断 500 字符）再回退技能名；`resources/` 子目录下的文件按严格 UTF-8 解码读成 `Map<相对路径, 文本内容>`，解码失败（二进制文件）或超配额（单文件 512 KB、总量 4 MB）的文件单独跳过而不拖垮整个技能，相对路径分隔符归一为 `/`；产物是 agentscope 的 `AgentSkill` 对象。

> frontmatter 必须优先：agentscope 的 SKILL.md 规范要求文件以 frontmatter 开头，早期自研解析器不认它，会把 `---` 当成描述落库——运行时不再过 `MarkdownSkillParser`，`SkillBox.getSkillPrompt()` 直接把这份描述注入提示词，LLM 无从判断何时调用，ZIP / NPM 来源的技能实质失效。现三种来源的元数据口径一致。

落库前每个技能都过一遍 `SkillContentScanner`（9 条高危命令规则：递归删根、Windows 盘符擦除、磁盘覆写、远程管道执行、反弹 shell、fork 炸弹、root 提权、历史与审计篡改、凭据收集外传）。命令边界用 `(?:^|[^\w-])` / `(?:[^\w]|$)`，能命中 Markdown 内联代码。命中**不拒绝导入**，而是把技能置为 `status=0` 待人工审核，并计入响应的 `flagged` 清单。

## 4. Admin 管理接口（两套并存）

### 4.1 旧版：仓库与技能分离，两步式同步

- `SkillRepositoryController`（`/api/admin/skill-repositories`）：`/page`、`/active`、`/{id}`、创建、`/update/{id}`、`/toggle/{id}`、`/{id}`（DELETE），以及 `GET /fetch/{id}` —— 实时拉取远端技能列表**预览**，返回带 `exists`（该名字是否已落库）标记的 `SyncSkillResponse`，临时目录加载后立即清理；
- `SkillController`（`/api/admin/skills`）：`/page`、`/{id}`、创建、`/update/{id}`、`/toggle/{id}`、`/{id}`（DELETE），以及 `POST /batch?repositoryId=` —— **选择性同步落库**：再次经 Loader 全量加载（在事务外），按前端勾选的技能名交给 `SkillInstaller.persist` upsert（同名覆盖 `description` / `skillmd` / `resources` / `version` / `isPublic`，源中已不存在的名字记入 `failed`），返回 `SkillInstallResponse`；旧签名 `batchSaveSkills(...)` 保留为 `savedCount`。

即旧版同步是「fetch 预览 → 人工勾选 → batch 落库」的两步式。

### 4.2 新版：SkillSource（创建即安装，全量）

`SkillSourceController`（`/api/admin/skill-sources`）+ `SkillSourceServiceImpl`：

| 接口 | 返回 | 行为 |
|------|------|------|
| `POST /` | `SkillSourceInstallResponse` | 保留名校验 → `loader.validateConfig` → 事务外加载 → `SkillInstaller.createWithSkills` 短事务落库；GIT 类型同时冗余写回 `url` / `branch` |
| `POST /upload` | `SkillSourceInstallResponse` | 接收 multipart → 存临时 zip → 创建 ZIP 仓库 → 立即安装 → `finally` 删除临时 zip；`sourceConfig` 只记 `originalFilename`，不留服务器路径 |
| `POST /{id}/install` | `SkillInstallResponse` | **重新安装**：`requireWritable` + `requireRefreshable` + `loader.validateConfig` → 事务外加载 → `SkillInstaller.persist` 全量 upsert；ZIP 与 BUILTIN 被 `requireRefreshable` 直接拒绝 |
| `GET /{id}/fetch` | `List<SyncSkillResponse>` | 与旧版等价的远端预览（纯数组，不是分页对象），每项带 `exists` 标记；ZIP 与 BUILTIN 拒绝 |
| `PUT /{id}` | `Void` | 仅更新仓库记录（名称查重、保留名拦截、`isPublic` 变更时级联刷新已装技能），**不重新安装技能**——改完地址、分支或包名需自行调 `POST /{id}/install` |
| `DELETE /{id}` | `Void` | 级联清理（见 4.3） |
| `PUT /toggle/{id}` | `Void` | 启停仓库 |

两个安装类接口（`POST /` 与 `POST /upload`）返回的 `SkillSourceInstallResponse` 是 `{ source, install }`，其中 `install` 就是上节描述的 `SkillInstallResponse`。创建/重装失败不会回滚已成功的部分，调用方需按 `savedCount` 与 `failed` 分级提示。

### 4.3 删除级联

仓库删除：新版 `deleteSkillSource` 与旧版 `deleteSkillRepository` 共用 `SkillInstaller.deleteWithSkills(repository)`——先取该仓库全部技能 ID → `agentSkillBindingMapper.deleteBySkillIds` + `cliSkillBindingMapper.deleteBySkillIds` → 逐个删技能 → 删仓库，不留悬空绑定。单删技能（`SkillController.deleteSkill`）同样先清绑定。

## 5. Agent 与 CLI 配置技能

- 智能体保存时 `skillList`（逗号分隔技能 ID）→ `AgentServiceImpl.saveSkillBindings` **先删后插** `agent_skill_binding`，插入前校验两项：禁止直绑内置仓库技能、禁止同一 agent 绑定两个同名技能（技能名只在仓库内唯一，而 harness 按 name 归并，见 R5-3）。校验在事务内、抛错即整体回滚。
- CLI 保存时写入 `cli_skill_binding`，且只接受内置仓库技能；
- 详情回显 `convertToResponse`：技能项带所属仓库名；CLI 项内嵌其关联技能列表（供前端展示「这个 CLI 会附带哪些技能」）。

## 6. 配置下发（Admin → agent-service）

`InternalApiController.buildAgentSpecResponse`（`GET /api/admin/internal/agent-spec/{sessionId}`）中与技能相关的三份数据：

1. `skillList`：绑定 ID 的 CSV 字符串（legacy 字段，保留兼容）；
2. `skillDetails`：逐个查 `skill` 表组装的完整 `SkillDetailDto`；
3. **CLI 技能合并**：查智能体的 CLI 绑定 → `cliSkillBindingMapper.selectByCliIds` 取各 CLI 的技能 ID → 去重后并入 `skillDetails`；**禁用的 CLI 整条跳过**，其技能随之下发不到；合并时同时认技能的 `status` 与 name 两件事（见 R5-1、R5-3）；
4. 另有独立接口 `GET /api/admin/internal/builtin-skills`：返回内置仓库全部 `status=1` 技能（不存在该仓库时返回空列表），仓库经 `selectBuiltinRepository` 确定性定位、不传租户上下文。

## 7. 运行时装配链路（agent-service → HarnessAgent → agentscope）

```
BuiltinSkillRegistry
    ├─ ApplicationReadyEvent 启动时拉取 /builtin-skills 并缓存（@Volatile List）
    └─ admin 不可达 → 空缓存，getSkills() 时懒重试（避免永久缺失内置技能）
        ▼
AgentSpecResolver.resolve(sessionId)
    ├─ 内置技能注入：builtinSkills 排在前，按 skillId 与 specInfo.skillDetails 去重
    │     与 spec 技能同名的内置技能让位（同名两份会让 harness 两层的判断相反，见 R5-3）
    │     → effectiveSpecInfo（copy(skillDetails = merged)）
    ├─ specContextHolder.set(effectiveSpecInfo)        ← 供 SkillAdaptor 读取
    └─ builder.addSkill(SkillSpec(skillId, skillName))  // skipIfMissing 默认 true
        ▼
HarnessAgentLauncher.createAgentBase()  遍历 agentSpec.skills：
    ├─ skillAdaptor.getSkill(skillId)
    │     优先：context 中的 skillDetails（DTO → AgentSkill，含 resources 反序列化为 Map）
    │     回退：SkillMapper.selectById 直查数据库（该语句只过滤 active，停用标记在这里认，见 R5-2）
    ├─ 命中 → agentBuilder.addSkill(AgentSkill)
    └─ 缺失 → skipIfMissing=false 抛 AGENT_SKILL_NOT_FOUND；true（默认）仅告警跳过
        ▼
HarnessAgentBuilder.build()
    └─ skills 包装为私有 InMemorySkillRepository（只读：save/delete 返回 false，isWriteable=false）
       → HarnessAgent.Builder.skillRepository(...)
        ▼
agentscope 消费端（HarnessAgent 内部）
    ├─ HarnessSkillMiddleware 编排多个 skill repository（可叠加 workspace 技能）
    ├─ SkillBox.getSkillPrompt()：系统提示词只注入「技能目录」
    │     （name + description + skill-id），渐进式披露，不注入 SKILL.md 全文
    ├─ SkillToolFactory：注册 load_skill_through_path 工具，
    │     LLM 需要时才加载 SKILL.md 正文或 resources 里的脚本 / 模板
    └─ autoUploadSkill=true：技能文件上传到工作区 skills/<skillId>/ 子树，
        shell / 文件类工具可直接执行技能附带脚本
```

**技能生效方式**：不是把 SKILL.md 全文塞满上下文，而是「目录进提示词 + 工具按需读全文 + 资源文件落工作区」的渐进式披露，上下文成本与技能数量近似线性、与技能大小基本无关。

## 8. 技能到达 Agent 的三条路径

| 路径 | 绑定表 | 来源限制 | 合并位置 |
|------|--------|---------|---------|
| 智能体直绑 | `agent_skill_binding` | 禁止内置仓库技能 | Admin 下发 `skillDetails` |
| CLI 关联 | `cli_skill_binding` | 仅内置仓库技能 | Admin 下发时并入 `skillDetails`（禁用 CLI 跳过） |
| 全局内置注入 | 无（全量缓存） | 内置仓库全部启用技能 | agent-service `AgentSpecResolver` 注入每个会话 |

三条路径最终都汇入 `skillDetails` → `SkillSpec` → `AgentSkill`，并按 skillId / name 去重。两侧的去重规则必须一致：合并处与注入处都保留运维显式绑定的那一份（见 R5-3），否则 agent 自己绑的技能会被内置的同名技能覆盖。

## 9. 问题与修复记录

> 2026-09 对「前端 webui / 微信小程序 → admin Controller / Service / Loader / 解析器 → Mapper XML → Flyway DDL → harnax-cli 调用方」做了一次全链路走读，共发现 24 项问题（P0 五项、P1 十项、P2 九项），**已全部修复**，验证细节见 9.6 节。修复过程中另识别出 5 项残留与遗留事项，其中 TODO-4 已在第三轮修复、TODO-2 已在第四轮修复、TODO-3 已在第五轮结案，其余 2 项记在 9.5 节，**尚未处理**。
>
> 第一轮之后又按「功能流程是否通顺、边界情况是否处理充分」完整复查了两遍：第二遍聚焦写入路径与来源配置的边界，第三遍聚焦归一化、DTO 校验与读取闸门，并顺着收紧后的校验回查三个调用方（webui、harnax-cli、微信小程序）会不会被挡住，共新发现 19 项，**同样已全部修复**，逐项记在 9.4 节。
>
> 两遍复查之后又把 9.5 节优先级最高的待办做掉了一项：Loader 层的静默丢弃（原 TODO-2，现 R4-1），9.4 节因此合计 20 项。
>
> 第五轮换了切法：不再按「写入路径」横切，而是把 skill 的**前后端管理路径**与**agent 加载路径**逐条纵向对齐——前端调用 ↔ Controller 映射、Mapper 方法 ↔ XML 语句 ↔ 实体字段 ↔ Flyway 列、下发 ↔ 解析 ↔ 装配。新发现 5 项（R5-1 至 R5-5）、结案 1 项待办（原 TODO-3，现 R5-6），9.4 节因此合计 26 项。

### 9.1 P0：会造成数据错误或安全后果（已全部修复）

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| P0-1 | **NPM / ZIP 解析器不认 YAML frontmatter，description 恒为 `---`**：`extractDescription` 跳过 `#` 行后返回首个非空行，对标准 SKILL.md 返回 `---`，技能名取目录名而非 frontmatter 的 `name`。落库后经 `SkillAdaptorImpl` 直接转 `AgentSkill`（运行时不再过 `MarkdownSkillParser`），`SkillBox.getSkillPrompt()` 把这份描述注入提示词 → LLM 无法判断何时调用，ZIP / NPM 来源的技能实质失效，且与 GIT 来源结果不一致 | `SkillFileParser` 新增 frontmatter 解析（`key: value`、块标量 `\|` / `>` / `\|-` / `>-`、缩进续行、引号剥离），`parseMeta(skillmd, fallbackName)` 让 name / description 优先取 frontmatter，描述回退顺序为 frontmatter → 正文首个有效行 → 技能名，三种来源口径统一。（review 建议直接复用 agentscope 的 `SkillUtil.createFrom`，最终选择扩展现有解析器，避免把 admin 的落库口径绑死在框架内部工具类上） |
| P0-2 | **大技能包静默丢失**：`loadResources` 用 `file.readText()` 严格 UTF-8 解码读 `resources/` 下所有文件，遇到 png / pdf / xlsx 抛 `MalformedInputException`；上层 catch 只 `log.error` 后 `continue` → 接口返回 200，技能一条没进库，前端刷新看到空列表 | ① `loadResources` 改为解码失败即跳过该文件（不拖垮整个技能），并加配额：单文件 512 KB、总量 4 MB；② 落库结果不再静默，`SkillInstaller` 把每个技能归入 `installed` / `updated` / `failed` / `flagged`，`failed` 带原因随响应返回；③ 前端按 `savedCount` / `failed` 分级提示。注：review 中「两列是 `text`（64 KB 上限）」的判断已被推翻，`V9__skill_content_in_mysql.sql` 早已改为 `MEDIUMTEXT` |
| P0-3 | **`npm install` 未加 `--ignore-scripts`**：npm 默认执行目标包及依赖的 `preinstall` / `install` / `postinstall`，任何能创建 NPM 来源的用户（或投毒的公开包）都能在 admin 服务器上跑任意命令，而 admin 持有全库 DB 凭证与内部 API 共享密钥。另外包名正则 `^[a-z0-9@/._-]+$` 允许 `..`，registry 完全未校验 | 命令改为 `npm install <pkg> --prefix <dir> --ignore-scripts --no-audit --no-fund [--registry <r>]`；包名正则收紧为 `^(?:@[a-z0-9~][a-z0-9._~-]*/)?[a-z0-9~][a-z0-9._~-]*$`、长度上限 214、显式拒绝含 `..`；registry 校验 `^https?://[^\s]+$`；安装结果目录必须落在 `installDir` 内 |
| P0-4 | **创建接口缺保留名校验，可伪造 `builtin-cli-skills`**：`createSkillSource` 与 `uploadAndInstall` 都没有拦截，查重用带租户的 `selectByName`，而内置仓库 seed 在 `tenant_id=1` → 租户 2 能创建同名仓库，且创建后自己删不掉（被判定为平台只读），成为永久垃圾数据。更关键：`getBuiltinSkills` 用不带 tenantId 的 `selectByName` 且 SQL 无 `ORDER BY` + `LIMIT 1`，多个同名仓库时返回哪个是未定义行为，而结果会注入每个会话；`AgentServiceImpl.saveSkillBindings` 用带租户的 `getByName` → 租户 2 查不到内置仓库 → 走 `log.warn` 分支跳过约束校验 | ① 创建、上传、改名三个入口统一经 `requireNotBuiltinName` 拦截；② 新增 `SkillRepositoryMapper.selectBuiltinRepository(name)`，按 `name` + `active = 1` + `ORDER BY id ASC LIMIT 1` 确定性定位（不按 `source_type` 过滤，理由见 3.2），`getBuiltinRepository()`、`getBuiltinSkills`、绑定约束校验全部改走它，不再依赖租户上下文；③ `V15` 追加唯一索引 `uk_skill_repository_builtin_guard`，从 DB 层杜绝第二个内置仓库 |
| P0-5 | **ZIP 无解压配额 + 导入链路绕过安全扫描**：Zip-Slip 防护正确，但没有总字节数、单文件大小、entry 数量上限，几十 KB 的 zip bomb 可撑满磁盘；`Files.copy` 未传 `REPLACE_EXISTING`，zip 内重复 entry 抛 `FileAlreadyExistsException`。同时 agentscope 自己的技能写入路径带 `SkillSecurityScanner.scan`，而 admin 导入落库完全不做扫描，落库技能随后会被 `autoUploadSkill` 上传到工作区、可被 shell 工具执行 | ① 解压配额：entry 数 ≤ 5 000、单文件 ≤ 20 MB、总量 ≤ 200 MB、entry 名 ≤ 512 字符，声明大小与实际写入字节双重校验；`Files.copy` 加 `REPLACE_EXISTING`；② 新增 `SkillContentScanner`（9 条高危命令规则，命令边界能命中 Markdown 内联代码），命中不拒绝导入而是置 `status=0` 待审核并计入 `flagged` |

### 9.2 P1：越权、一致性与容量（已全部修复）

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| P1-6 | **按 ID 的读写全无租户过滤**：`fetchSkills` / `fetchRemoteSkills` 不校验租户也不调 `requireWritable` → 跨租户触发 git clone / npm install（SSRF + 资源消耗）；`getSkillSource(id)` / `getSkillRepository(id)` / `getSkill(id)` 同样裸查 → 越权读取他人仓库配置与 `skillmd` 全文 | 服务层补齐：`SkillServiceImpl` 的读改写删统一过 `requireReadable`（租户 + 内置仓库豁免，null 上下文视为内部调用）；`fetchRemoteSkills` / `fetchSkills` / `installSkills` / `getSkillSource` 补 `requireSameTenant` / `requireReadable`，写操作补 `requireWritable`。**Mapper 层仍是裸查（已按 R5-6 结案为显式契约）；`getSkillRepository(id)` 已在 R3-5 补上校验** |
| P1-7 | **长事务**：`createSkillSource` 与 `batchSaveSkills` 都在 `@Transactional` 内做 git clone / npm install（NPM 超时上限 120 秒）→ DB 连接被长时间占用，并发下连接池耗尽 | 抽出独立 `@Service SkillInstaller`（同类内私有方法自调用 `@Transactional` 不生效的问题一并解决）；加载全部移到事务外，落库走 `createWithSkills` / `persist` / `deleteWithSkills` 三个短事务 |
| P1-8 | **技能 `is_public` 恒为 0，公开共享实际不可用**：`installSkills` / `createSkill` 都写死 0，前端也无编辑入口，而列表条件是 `(is_public=1 OR creator=当前用户)` → 仓库设为公开后其他用户能看到仓库、看不到任何技能 | `SkillInstaller.persist` 新建时 `isPublic = repository.isPublic`、更新时同步刷新；`createSkill` 改为继承仓库；`updateSkillSource` 改 `isPublic` 时级联更新已装技能；`V15` 回填存量数据 |
| P1-9 | **无唯一索引**：`skill_repository` 缺 `(tenant_id, name)`、`skill` 缺 `(repository_id, name)` → 应用层查重存在竞态，并发创建产生重复行，之后 `LIMIT 1` 结果不确定 | `V15__skill_source_integrity.sql`：先把存量重复名重命名为 `LEFT(CONCAT(SUBSTRING(name,1,80),'#dup-',id),100)`（保留最旧行，不删数据），再加生成列 `active_name = IF(active=1, name, NULL)` 与三个唯一索引；用生成列是为了让逻辑删除的行不参与唯一约束 |
| P1-10 | **`PUT /skill-sources/{id}` 不重装技能**：改了 url / branch / packageName 后库里内容仍是旧的，无任何提示；且前端 update 恒传 `url` / `branch`（NPM / ZIP 时为空串）→ 覆盖 legacy 列 | 新增 `POST /skill-sources/{id}/install`；`applySourceConfigChange` 的 KDoc 写明「改配置不重装，调用方必须自行触发 install」；前端列表加「重新安装」按钮（ZIP 与 BUILTIN 不显示），编辑保存后弹 warning 明示，`RepositoryForm` 不再对 NPM / ZIP 恒传空 `url` / `branch` |
| P1-11 | **同一件事两种失败语义**：`installSkills` 单技能失败继续，`batchSaveSkills` 单技能失败整体回滚 | 两条入口都收敛到 `SkillInstaller.persist`，统一为「单技能失败不阻断其余 + 返回失败清单」；`batchSaveSkillsDetailed` 返回 `SkillInstallResponse` |
| P1-12 | **ZIP 的 `zipPath` 指向已删除文件**：控制器 `finally` 删临时文件，服务层却仍写入 `sourceConfig.zipPath`，且注释写的是「no path is recorded here」——注释与代码矛盾；API 层未拦截，二次 fetch / batch 必报 `ZIP file not found` | `uploadAndInstall` 的 `sourceConfig` 只写 `originalFilename`；`persistableConfig` 与 `forApi` 都把 `zipPath` 列为不出服务器的内部键；`requireRefreshable` / `fetchRemoteSkills` 按 `sourceType` 直接返回「ZIP 为一次性安装，请重新上传」；前端隐藏 ZIP 与 BUILTIN 的同步入口 |
| P1-13 | **旧版删除不级联**：`deleteSkillRepository` 只删仓库，留下孤儿技能（仍可被下发） | 改为 `skillInstaller.deleteWithSkills(repository)`，与新版共用同一套级联 |
| P1-14 | **旧版创建默认公开**：`createSkillRepository` 不设 `isPublic` / `sourceType` / `sourceConfig` → 取实体默认值 `isPublic = 1`，经 harnax-cli 创建的仓库默认对全租户公开，与前端创建的默认私有相反 | 显式 `isPublic = 0`，并同步写 `sourceType = "GIT"` 与 `sourceConfig`，legacy 列与 JSON 配置不再脱节 |
| P1-15 | **内置仓库 seed 数据语义错误**：`source_type='ZIP'`、`source_config=NULL`、`url=NULL`，且 `tenant_id=1` 导致其他租户在 UI 上完全看不到内置仓库，但其会话却被注入了 `harnax-cli` 技能，也无法给自己的 CLI 绑定它 → 多租户下 CLI 技能功能不可用 | `V15` 改为 `source_type='BUILTIN'`、`source_config=''`、`url=''`（非 NULL，因 Kotlin 实体属性非空）；`SkillLoaderRegistry` 无 BUILTIN 的 loader，fetch / install 显式拒绝并给出「平台预置、无来源可拉取」的提示；跨租户可见性由 `selectSkillList` 的 `OR repository_id = #{builtinRepositoryId}` 与 `requireReadable` 的内置仓库豁免解决 |

### 9.3 P2：健壮性与清理（已全部修复）

| 问题 | 修复方式 |
|------|---------|
| `installSkills` 末尾 `skillRepositoryMapper.updateById(repository)` 是无字段变更的空转写，且会覆盖全部列（有并发修改被回写的风险） | 删除该语句 |
| `requireNotBuiltinRepo` 在仓库不存在时 `?: return` 静默放行 → 可给不存在的 `repositoryId` 建技能，产生孤儿数据；`createSkill` 还是先查重后鉴权 | `requireWritableRepo` 改为仓库不存在直接抛错（不再一次废掉两项校验）；`createSkill` 改为先鉴权后查重，避免探测技能名是否被占用 |
| `SkillSourceConfigs.parse` 解析失败时无条件回退 `{url, branch}`，对 NPM / ZIP 是错误配置 → 报「requires 'packageName'」而非真实原因 | 非 GIT 类型抛 `IllegalStateException`（带仓库 ID 与 sourceType）；另新增 `forApi` 纯投影供两个响应 DTO 共用 |
| `GitSkillLoader` 无超时、无 URL 协议白名单（JGit 支持 `file://`，可探测本地与内网仓库）、`branch` 未校验 | 独立 daemon 线程池 + `future.get(180s)` + `@PreDestroy shutdownNow()`；协议白名单与 `-` 开头拒绝；`branch` 正则校验 |
| 空 `SKILL.md` 跳过逻辑只在 ZIP 侧有，NPM 侧缺失 → 行为不一致 | 统一由 `SkillInstaller` 处理：空内容记入 `failed`（原因 `SKILL.md is empty`），两种来源一致且不再静默 |
| 两套响应 DTO 语义不同：`SkillSourceResponse.sourceConfig` 为空返回 `null`，`SkillRepositoryResponse` 返回 `{url:"",branch:""}`（永不 null） | 两者共用 `SkillSourceConfigs.forApi`，为空一律 `null` |
| MyBatis 未显式配 `call-setters-on-nulls`（默认 false），恰好使 seed 的 NULL 列不触发 Kotlin 非空 setter 异常——属隐式依赖，一旦有人打开该配置内置仓库查询会直接 NPE | 两个 `application.yml` 显式钉死为 `false` 并注明原因 |
| 死代码与残留：`harnax-webui/src/services/ant-design-pro/skillRepository.ts` 无任何引用；`SkillMapper.xml` 的 `updateSkillFields` 无调用方；两个 Controller 注释仍写「Available only for public edition」 | 全部清理。`SkillRepositoryController` **未删**，harnax-cli 与两个集成测试仍在用 |
| 前端重复 toast 与不可达分支：`errorThrower` 已对 `code !== 200` 抛 `BizError`、`errorHandler` 已全局弹 toast，组件自己的 `message.error` 与 `response.code === 200` 的 `else` 分支都是死代码 | webui 三个组件的重复提示与不可达分支删除；表单校验失败与请求失败分离（校验失败静默 return）；新增 `src/utils/skillInstall.ts` 作为提示级别的唯一裁决点。小程序侧另修掉一个真实功能性 bug：`fetchRemoteSkills` 原声明 `request<string[]>`，但后端返回对象数组，页面直接把对象 POST 给只收 `List<String>` 的 `/skills/batch`，同步功能实际不可用；现改为 `API.SkillSyncItem[]` 并在页面取 `name` 再提交 |

### 9.4 第二至五轮复查：新发现并已修复（26 项）

#### 第二轮：写入路径与来源配置的边界

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| R2-1 | **ZIP 来源可以用 `POST /skill-sources` 加一个服务器本地 `zipPath` 创建**：控制器在响应返回前就删掉了临时文件，存下来的配置指向一个已不存在的路径，之后任何同步都报 `ZIP file not found`；而当时的报错是 loader 的「ZIP source config requires 'zipPath'」，既不说明该用哪个接口，也让人以为是配置写错了 | `createSkillSource` 对 `sourceType == "ZIP"` 直接拒绝，消息里点名 `POST /api/admin/skill-sources/upload`；拒绝发生在配置校验之前，因此与 `sourceConfig` 里有什么无关 |
| R2-2 | **校验与克隆用的不是同一个值**：`validateConfig` 先 trim 再匹配传输白名单，`loadSkills` 却拿原值去 clone → `" https://host/repo "` 能通过校验，再在 JGit 内部失败，错误里是一个没人输入过的地址 | `loadSkills` 按与校验完全相同的口径取 `url` / `branch`（trim、空分支回退 `main`），并在注释里写明两处必须同步 |
| R2-3 | **Git URL 里内嵌的凭据会进日志与错误消息**：私有技能仓库常用 `https://user:token@host/repo` 克隆，JGit 的传输错误会原样引用整个 URI，于是 token 落到日志文件与接口响应里 | 新增 `redact()`：把文本中每个 URL 的 `user:password` 段替换为 `***`，超时、克隆失败、URL 不合法三条消息统一走它；scp 形式 `git@host:org/repo` 不带凭据也没有 scheme，正则不会误伤 |
| R2-4 | **技能名在三处口径不一致**：预览接口回显源里的原始名，`SkillInstaller` 落库存 trim 后的名，批量提交按原始名解析 → 名字带空格时预览的 `exists` 判断是错的，用户照着预览勾选、提交却解析不到 | 预览（`fetchSkills`）与批量选择（`batchSaveSkills`）都改用 trim 后的名字，预览、选择、落库三处统一 |
| R2-5 | **ZIP 上传被 Spring 默认 1 MB 的 multipart 上限挡在 loader 之外**：`ZipSkillLoader` 自己有单文件 20 MB、总量 200 MB 的配额，但带几个资源文件的普通技能包在 multipart resolver 那一层就被拒了，报「File size exceeds limit」 | `application.yml` 显式配置 `spring.servlet.multipart`（`max-file-size` 200 MB、`max-request-size` 205 MB，可用 `SKILL_UPLOAD_MAX_FILE_SIZE` / `SKILL_UPLOAD_MAX_REQUEST_SIZE` 覆盖），request 上限给随包提交的表单字段留余量 |

#### 第三轮：归一化、DTO 校验与读取闸门

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| R3-1 | **存进库的配置没有归一化**：`createSkillSource`、`applySourceConfigChange` 与旧版 create / update 都原样写入，URL 与分支带着从聊天窗口或终端粘贴来的空格。loader 侧的 trim 只保证「能用」，列表页显示的仍是一个没人输入过的地址，编辑表单再把它回填给用户，legacy 列与 JSON 配置也会各存一份 | 新增 `SkillSourceConfigs.normalized()`，5 个写入点（新版 create、新版 update 的两个分支、旧版 create、旧版 update）统一接入；loader 侧 trim 保留，用于兜住此改动之前写入的存量行 |
| R3-2 | **Git url / branch 没有长度上界**：`sourceConfig` 是自由 map，DTO 上的 `@Size` 管不到里面的值 → 超长 URL 被接受、被写库，最后由 MySQL 回一句 data-truncation，指的是列名而不是调用方填错的字段 | `GitSkillLoader.validateConfig` 补 `MAX_URL_LENGTH = 500` / `MAX_BRANCH_LENGTH = 100`（对齐 `skill_repository.url` / `branch` 列宽），这里是所有写入路径的汇聚点；长度检查排在所有会回显 URL 的分支之前，且消息不含 URL 本身——超长正是最可能内嵌凭据的情形 |
| R3-3 | **旧版 `createSkillRepository` 完全不做配置校验**：新版 create、新版 update、旧版 update 都调 `validateConfig`，唯独它不调 → harnax-cli 能创建一个永远拉不动的仓库，真实原因（「Unsupported Git URL」）要到第一次同步才暴露，离造成它的那次输入很远。另外 `repository.branch = request.branch` 没有 `ifBlank { "main" }`，空分支时 legacy 列存 `""` 而 JSON 配置存 `"main"`，两处自相矛盾 | 写入前先 `validateConfig`；legacy 列与 JSON 配置改为从同一个已校验、已归一化的 map 派生 |
| R3-4 | **5 个 Skill DTO 上的 `@Size` 全是死代码**：Kotlin data class 构造参数上的裸注解按 param > property > field 的优先级落到 param，而 Bean Validation 只读字段与 getter，Spring MVC 的 `@Valid @RequestBody` 又不做构造参数校验 → 声明的长度限制一条都没生效，只存在于 OpenAPI 文档里。全项目 `@field:` 前缀 51 处、裸注解 56 处并存，`ModelProviderCreateRequest` 全用 `@field:` 且其验证测试全绿，可作反证 | 5 个 Skill DTO（`SkillRepositoryCreateRequest` / `SkillRepositoryUpdateRequest` / `SkillSourceCreateRequest` / `SkillSourceUpdateRequest` / `SkillUpdateRequest`）改为 `@field:Size`，`version` 补上 varchar(100) 约束（它会被复制到每个落库技能，`skill.version` 同为 varchar(100)）；新增 `SkillRequestValidationTest`（16 项）钉住这一约定。**其余模块约 50 处同类裸注解本轮未动**，超出 Skill 范围 |
| R3-5 | **旧版 `GET /skill-repositories/{id}` 跨租户可读**（即原 TODO-4）：`getSkillRepository` 直接 `selectById` 返回，仓库配置含 Git URL、分支、NPM 包名与私有 registry 地址，其中 Git URL 可能内嵌克隆用的 token | `getSkillRepository` 读取后过 `requireSameTenant`（内置仓库豁免、null 上下文视为内部调用），与新版 `getSkillSource` 的 `requireReadable` 对齐；`fetchRemoteSkills` 里重复的同一段判定改为复用该方法。选择抛异常而非返回 null，是为了保住 `requireWritableRepo` 既有的「belongs to another tenant」文案与相关断言。已核实 7 个调用方无一会被新增拒绝：`AgentServiceImpl` 与 `SessionServiceImpl` 都先经过 `skillService.getSkill()`（内含 `requireReadable`） |
| R3-6 | **下发 agent 配置时不过滤技能 `status`**：`/builtin-skills` 过滤 `status == 1`、同一函数的 CLI 分支跳过被停用的 CLI，唯独 agent 绑定的技能照发 → 运维手动停用、或重新导入时被 `SkillContentScanner` 降级为 `status = 0` 的技能仍会进 harness，评审闸门形同虚设；同一响应里 `skillList` 还会把刚被丢弃的技能 ID 列出来，两半自相矛盾 | 补 `status == 0` 跳过分支并记日志，写法对齐同函数既有分支；`skillListStr` 改为从已解析的 `skillDetails` 派生。CLI 合并处当时**未加**同类过滤：`saveSkillBindings` 强制 CLI 绑定只能引用内置仓库的技能，而内置技能不可 toggle，看上去加了是死代码——这一判断在第五轮被推翻，见 R5-1 |
| R3-7 | **nginx 默认 1 MB 的 `client_max_body_size` 会把 ZIP 上传直接 413**：返回的是一个裸 HTML 页面，不进前端错误处理，用户看不到可读原因 | nginx 配置（`docker-new/nginx.conf`，仓库内唯一的部署入口）在反代 admin API 的 location 上加 `client_max_body_size 205m;`，与 R2-5 的 multipart 上限、`ZipSkillLoader` 的配额三层对齐；前端上传组件本就没有客户端大小限制，无需改动 |
| R3-8 | **两个集成测试编码的是已被修掉的旧契约**：用服务器本地 `zipPath` 走 POST 创建 ZIP 源（R2-1 已拒）、断言 `sourceConfig.zipPath` 回显（`persistableConfig` 与 `forApi` 都会剥掉）、对 ZIP 源调 `/fetch`（`requireRefreshable` 会拒）、并按扁平结构读 `SkillSourceInstallResponse`（实际是 `{source, install}`）→ 10 项失败。`*IT` 不在 surefire 默认包含范围内（admin pom 显式 exclude，failsafe 只在 `-DskipITs=false` 时跑），是测试过滤式把它们带进来的，所以此前一直没暴露 | `BaseAdminIT` 新增共用的 `uploadSkillZip()`（multipart 不能走 `exchange`，后者恒按 JSON 序列化）；两个 IT 全部改走上传、按 `{source, install}` 取字段，删掉对已移除行为的断言，改为断言当前契约：JSON 端拒绝 ZIP 且点名上传接口、ZIP 的 fetch / install 说明「一次性安装」、`status = 99` 被拒且不落库、内置仓库只读、`zipPath` 不出服务器 |
| R3-9 | **`InternalApiControllerTest` 整类全红**：控制器是构造注入且有 19 个参数，测试只声明了 11 个 `@Mock`，Mockito 对没声明的参数传 null，而 Kotlin 的非空参数在构造时就校验实参 → 抛 `InjectMocksException`，全类 14 项用例一个都跑不到（改动前即如此，见 9.6 节既存失败表） | 补齐 8 个缺失的 `@Mock`，并写明「控制器构造函数加参时这里要同步补上」；顺带让 R3-6 的闸门有了单元测试覆盖（停用技能不下发、悬空绑定不影响其余技能） |
| R3-10 | **harnax-cli 的 `skill-repo create` 必填项与服务端契约不一致**：`--name` 与 `--url` 都没有 `MarkFlagRequired`（CLI 里其余 create 命令都标了必填），`SKILL.md` 还把 `--url` 记成可选；而旧版接口只会创建 GIT 仓库，R3-3 之后缺 url 会在服务端被拒 → `harnax skill-repo create --name x` 要白跑一趟网络请求才拿到错误。反过来核实过「无 URL 仓库当手工技能容器」不是产品支持的用法：webui 的 GIT url 是必填，新版 `createSkillSource` 对空 url 的 GIT 同样抛错，旧版接口里 `sourceType` 恒为 `"GIT"` | 给 `skillRepoCreateCmd` 补 `MarkFlagRequired("name")` / `MarkFlagRequired("url")`，usage 串标注 `(required)`，与 `mcp create` 等命令写法一致；`SKILL.md` 把 `[--url <url>]` 改为 `--url <url>`，并说明该命令只创建 Git 仓库、`--branch` 缺省 `main`、URL 在创建时即校验 |
| R3-11 | **CLI 的 cobra 层错误全部静默**：`rootCmd` 设了 `SilenceErrors: true`，而 `main.go` 拿到 `cmd.Execute()` 返回的 error 后只 `os.Exit(1)`，消息被丢掉 → 缺必填 flag、子命令拼错、参数个数不对一律「退出码 1、零输出」。命令内部走 `exitError` / `exitAPIError` 的路径不受影响（它们直接 `os.Exit`，不返回 main），所以哑的只有 cobra 这一层；R3-10 补的必填校验正好落在这条静默路径上 | `main.go` 退出前补 `output.PrintError(err.Error())`，与 `exitError` 用同一个输出函数。全 CLI 没有 `RunE`，也没有从 `Run` 里 `return err` 的写法，因此不会重复打印。实测四种情形：缺 `--url`、缺 `--repository-id`、参数个数不对都从「无输出」变为可读错误，`--help` 与正常路径的退出码不变 |
| R3-12 | **微信小程序的仓库表单发的是旧版 DTO 收不下的字段**：表单按新版数据模型写（来源类型选择器 GIT / NPM、`sourceConfig`、`version`，`onLoad` 也从 `sourceConfig` 回填），却 POST 到旧版 `/skill-repositories`：该 DTO 只有 `name` / `url` / `branch` / `description` / `status`，`sourceType`、`sourceConfig`、`version` 被 Jackson 静默丢弃，而服务端真正读的顶层 `url` 根本没发。于是 R3-3 之前是「静默建出一个 url 为空、永远拉不动的 GIT 仓库」，NPM 选项从来不可能生效；R3-3 之后变成一律报「Git source config requires 'url'」。编辑同样坏：url / branch 从不发送，改地址存不下任何东西，界面却提示「已保存」 | `createRepo` / `updateRepo` 改走 `skill-sources`：新版 create 收 `sourceType` + `sourceConfig` 且创建即安装，PUT 只写配置。typings 里两个旧请求类型换成 `SkillSourceCreateRequest` / `SkillSourceUpdateRequest` / `SkillSourceInstallResult`；create 超时放宽到 190 秒（服务端同步克隆上限 180 秒，默认 30 秒会先超时）；创建结果按 `install` 分级提示，编辑成功改为提示「配置已保存，需重新同步才会生效」，与 webui 的 `updateHint` 一致；`onLoad` 遇到 ZIP / BUILTIN 直接说明不支持编辑并退回，不再退化成 GIT 表单（服务端本就忽略 ZIP 的配置更新、拒写内置仓库，这里是把死胡同提前说明）。新增 `utils/skillInstall.ts`，同步与创建两个入口共用一份分级文案，顺带补上原先漏掉的 `flagged` 分支 |
| R3-13 | **9.2 节 P1-10 记的「前端不再为 NPM / ZIP 发送空 `url` / `branch`」在代码里并不成立**：`RepositoryForm` 的更新调用一直带着这两个字段，NPM 下是空串，ZIP 下 `sourceConfig` 还是空对象 → 服务端会走进 `applySourceConfigChange` 的 `incoming == null` 分支，把 `repository.url` / `branch` 写成空串。今天不出事只因为 ZIP 行的这两列本来就是空串（实体默认值），而 GIT / NPM 的 `sourceConfig` 非空会让服务端优先走另一条分支——靠巧合而不是靠设计 | 更新调用不再发送 `url` / `branch`：`sourceConfig` 是唯一载体，服务端自己把它镜像回旧版列（且只对 GIT 镜像）。创建调用保持不变，`SkillSourceCreateRequest` 里这两个字段本就是标注为 backward compat 的兼容入口 |
| R3-14 | **`harnax skill sync` 用提交数量报成功**——本轮修复自己引入的回归：第一轮按 P1-11 把 `POST /skills/batch` 的返回从 `ResultVo<Int>` 改成 `ResultVo<SkillInstallResponse>` 时，webui 与小程序都跟着改了，CLI 漏了。`batchResult.DecodeData(&count)` 解一个 JSON 对象必然失败，于是永久走 fallback，拿 `len(names)`（提交数量，不是落库数量）打印绿色的「Synced N skills」并以退出码 0 结束 → 部分失败、全部失败、命中内容扫描被置为待审核三种情况一律显示成功，正是这一整轮要消灭的「提示成功、技能没落库」 | 解码到 `SkillInstallResult`（只声明 CLI 真正会读的 `failed` / `flagged` / `savedCount` / `summary`），按结果分级汇报：有失败就打印服务端 `summary` 加前 3 条「技能名: 原因」（超出补 `and N more`）并以退出码 1 结束，让依赖它的流水线能感知；只有 `flagged` 时给警告但退出码 0（技能已落库，只是待审核）；`savedCount == 0` 时不再报「Synced 0」。`internal/output` 补 `PrintWarning`（原来只有 success / error 两级）。响应体解不开时不再静默兜底，改为打印截断后的原始 body——那正是「服务端还是旧版、返回一个整数」的形态。六种响应用本地桩服务逐一跑通：全成功、部分失败、全失败、仅 `flagged`、零保存、旧版整数 |

#### 第四轮：待办项收敛（1 项）

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| R4-1 | **Loader 层解析失败仍会静默丢技能**（原 TODO-2）：`NpmSkillLoader.buildSkill` 与 `ZipSkillLoader.buildSkill` 都是 `catch (e: Exception) { log.warn(...); null }`，加上「SKILL.md 存在但内容为空」也返回 null，解析失败的目录直接从结果里消失。`SkillInstaller` 的 `failed` 只覆盖「加载成功但落库失败」，盖不到这一层 → 一个 SKILL.md 损坏的目录会让接口答 200、`summary` 报「1 saved」，而源里其实有 2 个目录；R3-14 之后 CLI 会照实转述这个少了的数字，但仍然说不出少了哪一个、为什么少 | `SkillLoader.loadSkills` 的返回类型从 `List<AgentSkill>` 改为 `SkillLoadResult(skills, failures)`，失败项是 `SkillLoadFailure(name, reason)`，`name` 用目录名（解析正是失败的那一步，frontmatter 里的名字不可信）。两个 loader 只在**真有 SKILL.md 却读不出来**时记失败——目录里压根没有 SKILL.md 仍静默跳过，npm 包与压缩包里的非技能目录（`node_modules`、共享资源）本来就是常态，全报成 failed 会淹掉真问题。`SkillInstaller.persist` / `createWithSkills` 新增 `loadFailures` 参数并合并进同一个 `failed` 清单；合并排在解析勾选清单之前，这样一个读不出来的目录报的是真实原因，而不是退化成「Not present in the source anymore」，也不会两条都报。选择性同步只回显勾选到的名字：失败项按目录名索引，与预览显示的名字未必一致，全量回显会怪到用户没选的技能头上。原因文案经 `SkillFileParser.failureReason()` 截断到 200 字符（异常消息可能内嵌整个文件），消息为空时退回异常类型名。Git 源做不到同等归因——目录级解析在 agentscope 的 `GitSkillRepository` 内部，代码里注明了这一点 |

#### 第五轮：前后端管理路径与 agent 加载路径逐条对齐（6 项）

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| R5-1 | **CLI 合并分支不看技能 `status`**：R3-6 给直绑技能补了停用闸门，同一个函数里的 CLI 合并却只按 skillId 去重，而同一次下发里 `/builtin-skills` 与直绑分支都已拦了停用技能 → 三条路径的闸门不一致。**不是当下可复现的数据损坏**：`CliServiceImpl.saveSkillBindings` 保证 CLI 只能引用内置仓库技能，`toggleSkillStatus` 又经 `requireWritableRepo` 拒绝内置仓库，所以现有的写入接口确实停不掉一个 CLI 关联的技能 | 仍补上 `status == 0` 的跳过分支（记 info 日志、与直绑分支同一写法），理由有三：V12 / V14 由 Flyway 直接写的 seed 不受服务层约束，一行 `status = 0` 就能把技能推进内置仓库；历史绑定可能因直接改库而变悬；只要某一条路径不拦，下一次放宽 CLI 约束时就会悄悄地打开缺口。同时推翻 R3-6 的「加了是死代码」判断：三条路径共用一份规则比三条路径各自推导能不能省下一行代码更值钱 |
| R5-2 | **`SkillAdaptorImpl` 的 DB 回退不看 `status`**：`getSkill(skillId)` 在 spec 上下文未命中时回退 `SkillMapper.selectById`，而该语句只过滤 `active`（隔离契约见 R5-6）→ admin 三条下发路径都拦住的停用技能，只要 harness 拿着一个上下文里没有的 skillId 来取，就又被装进了 `InMemorySkillRepository` | 回退分支补 `status == 0` 判定并返回 null（走 `skipIfMissing` 默认的跳过语义）。单测同时覆盖「上下文为空回退」与「上下文非空但未命中再回退」两种进入方式，后者是常见情形（CLI 合并阶段刚丢掉的技能） |
| R5-3 | **同名技能在 harness 里的归属是未定义的**：`skill.name` 的唯一约束只到仓库级（`uk_skill_repo_active_name (repository_id, active_name)`），内置仓库与租户自己的仓库放一个同名技能完全合法；而 agentscope 的 `SkillRegistry` 按 `AgentSkill.getSkillId()` 存放，该值等于 `getName() + "_" + source`，`source` 默认 `"custom"` 且 harnax 从不设置 → 同名两份的 key 完全相同、后注册者替换前者；harnax 自己的 `InMemorySkillRepository.getSkill(name)` 又用 `skills.first { it.name == name }` 取第一个，两层的判断正好相反 | 三层各自裁决，规则一致：**admin 合并处**（`InternalApiController`）agent 自己绑定的技能优先，CLI 带来的同名技能跳过并记日志；**resolver 注入处**（`AgentSpecResolver`）spec 已定义的名字优先、内置技能让位并记日志，且合并后的结果同时写 `specContextHolder` 与 `buildAgentSpec`，否则 adaptor 回退到 DB 会把刚让位的那个又捞回来；**绑定写入处**（`AgentServiceImpl.saveSkillBindings`）同名直接 `BizException` 拒绝并在消息里点名，因为运维在配置面板上看得见这两行、当场就能改 |
| R5-4 | **两个 update 端点接受 `status` 但从不读它**：`SkillUpdateRequest` 与 `SkillRepositoryUpdateRequest` 都声明了 `status`、Swagger 标注「0:disabled 1:enabled」，而 `SkillServiceImpl.updateSkill` 与 `SkillRepositoryServiceImpl.updateSkillRepository` 没有任何一处引用该字段，`updateById` 语句本来也不含 `status` 列（启停走专用的 `updateStatus`，为的是不让一次普通编辑把扫描器降级为待审核的技能又打开）→ 接口回 200，`harnax skill update <id> --status 0` 打印「Skill updated successfully」，库里纹丝不动 | 两处都对齐 `updateSkillSource` 已有的正确范式：请求带 `status` 且与当前值不同时走 `updateStatus` 专用语句，不带时绝不动它（保留扫描器降级的效果）。**webui 不受影响**（页面只调 `toggleSkillStatus`、不发 update），受害的是 `harnax skill update` / `harnax skill-repo update` 两个命令与直接调 API 的集成方 |
| R5-5 | **三个 create 不校验 `status` 取值**：`createSkill` / `createSkillRepository` / `createSkillSource` 都写 `request.status ?: 1`，任何整数照收；`status` 是 TINYINT，存 7 不报错，但全链路都用 `status == 1` 判断（`/builtin-skills` 过滤、agent 直绑、CLI 合并、`SkillAdaptorImpl` 回退），于是造出一条既切不动也永不加载的记录。而三个 toggle 已经统一走 `SkillSourcePolicy.requireStatus` —— 同一个字段两套口径 | 三个 create 与两个 update（R5-4 新增的分支）全部补 `requireStatus`，且校验排在任何库访问与远端拉取之前：`createSkillSource` 尤其要紧，克隆是整个请求里最贵的一步，顺带也避免非法请求探测「仓库 / 名字是否存在」。`uploadAndInstall` 硬编码 `status = 1`、分页查询的 `status` 是读过滤，都无需改动 |
| R5-6 | **TODO-3（Mapper 层按 ID 查询缺 `tenant_id`）结案：不下推 SQL**。原建议是给按 ID 的查询加可选 `tenantId` 参数并在 XML 里条件下推。逐个核对调用方后否决：内部调用（`InternalApiController` 下发、`SkillAdaptorImpl` 装配）本就没有租户上下文，跨租户可见的内置仓库技能也要靠这些裸查；更关键的是服务层的检查会**抛**「Skill belongs to another tenant」，而 SQL 过滤会把同一请求变成空结果、把答复降级成「not found」，抹掉「不是你的」与「不存在」的区别 | 改为把契约写清：`SkillMapper` 的类级 KDoc 说明隔离不在这一层、判定归 `requireReadable` / `requireSameTenant` 所有、新调用方必须经服务层或复用既有检查（否则重新打开跨租户读取全文）；`SkillRepositoryMapper` 同步 |

### 9.5 仍待办

> 以下 2 项不属于上述 26 项，都是本文早先版本就记下的遗留项。原 TODO-2（Loader 层静默丢技能）已在第四轮修复，见 R4-1；原 TODO-4（`getSkillRepository(id)` 无租户校验）已在第三轮修复，见 R3-5；原 TODO-3（Mapper 层缺 `tenant_id`）已在第五轮结案，见 R5-6。编号保留原样，便于与历史讨论对照。

#### TODO-1（低）：预留字段未落地

- `SkillRepository.storagePath` 与 `Skill.storagePath`：除测试种子数据外无任何写入方，与「内容落库」的设计重复；
- `AgentSkillBinding.envBindings`：`saveSkillBindings` 只写 `agentId` / `skillId` / `createTime`，技能级环境变量当前不生效（工具与 MCP 通道有，技能通道没有）。
- **处理建议**：确认后续是否需要支持技能级环境变量；不需要则删除冗余列，避免误导。

#### TODO-3（已结案，见 R5-6）

#### TODO-5（低，重构类）：技能管理入口重复

- **现象**：`SkillRepositoryController` + `SkillController`（旧）与 `SkillSourceController`（新）能力高度重叠（CRUD / toggle / fetch 各有两套路径与 DTO）。本轮已统一失败语义与安装实现（P1-11），但两套路径、两套 DTO 仍在。
- **处理建议**：以 `skill-sources` 为唯一入口收敛，旧接口标记 deprecated 并保留过渡期——harnax-cli 与微信小程序仍依赖旧版，两侧都迁完才能删。
- **调用方迁移进展**：小程序的新建与编辑已在 R3-12 迁到 `skill-sources`，剩下 6 个调用点（分页、详情、启停、删除、fetch 预览、batch 落库）仍走旧版；小程序开发已暂停，逐条迁移清单（含 `batch` → `install` 的语义差异）记在 `harnax-wechat-app/DESIGN.md` 第五章。CLI 侧未开始迁移，而且它用满了 `SkillRepositoryController` 的全部 8 个端点（`/page`、`/active`、`/{id}` 读写与删除、创建、`/update/{id}`、`/toggle/{id}`、`/fetch/{id}`），是旧接口无法先删的主因。

### 9.6 验证记录

#### V15 迁移在真实 MySQL 8.0 上实测

`harnax-entity` 的 Mapper 测试用的是手工维护的 `src/test/resources/schema-test.sql`，**不走 Flyway**，所以「Mapper 测试全绿」并不能证明迁移可执行。V15 另有实测：把线上库的 `skill` / `skill_repository` 结构与数据复制到一次性库后执行迁移，结果——

- **结构变更**：2 个生成列（`active_name` / `builtin_guard`）与 3 个唯一索引全部建成；内置仓库由 `source_type='ZIP'`、`source_config=NULL` 修正为 `BUILTIN` + 空串；`is_public` 不一致行数归零。
- **约束行为**（8 项）：同租户重名仓库被拒、跨租户同名放行、其他租户伪造第二个 `builtin-cli-skills` 被 `builtin_guard` 拒、软删后复用同名放行、同仓库重名技能被拒、跨仓库同名技能放行、软删技能后复用同名放行。
- **存量重名数据**：另造含重复名的库验证 3a/3b 的改名逻辑——保留最旧行原名，其余改为 `原名#dup-<id>`，跨租户与软删行不受影响，仓库 7 行 / 技能 5 行一条未删。

#### 复核中发现并修复的一处回归

`CliServiceImplTest` 仍在 mock 旧的 `selectByName(name, tenantId)`，而 `CliServiceImpl` 已按 P0-4 改走 `selectBuiltinRepository(name)`：2 个用例报「内置仓库不存在」，另有 2 个用例因为拿到 null 提前抛错而「以错误的原因通过」。已改为 mock 新方法，该类 34 项全绿。

#### 前端类型检查

- 小程序：`tsc --noEmit` 零错误（含 R3-12 改动后的仓库表单与新增的 `utils/skillInstall.ts`）。
- webui：根 `tsconfig.json` 是残缺配置（`watch: true`、无 `paths`），`npm run tsc` 项目级失效；改用 umi 生成的 `src/.umi/tsconfig.json` 检查，skill 相关文件零错误。全库另有 54 条既存错误，分布在 `token-monitor` / `model` / `user` / `session` / `mcp` 等模块，与本轮无关。
- 另有 9 个既存 i18n key 缺口（`pages.skill.source.type`、`npm.package`、`npm.registry`、`zip.file`、`zip.select`、`version`、`selectRepository`、`noRepository`、`repository.confirmDelete`），中英两边都未定义，靠 `defaultMessage` 兜底显示英文；改动前即存在。

#### 与 Skill 无关的既存测试失败（不在本轮范围）

| 范围 | 结果 | 归属 |
|------|------|------|
| `harnax-admin` 全量 | 1475 项，14 failures / 27 errors | `AgentTask` / `TokenStats` / `AdminUserInitializer` / `ApiKeyService` / `AuthService` / `SecretFieldEncryptor` / `InternalApi` 七类；Skill 与 Cli 相关类全绿 |
| `harnax-entity` 全量 | 203 项，2 failures / 142 errors | 140 errors 是 CGLIB 无法代理 final Kotlin 测试类（`AopConfigException`，未执行到 SQL），2 errors 是 `AgentToolMapperTest` 报 `Unknown column 'is_required'`；2 failures 是同一个类的两条断言（软删行仍被返回、按 id 查回的是另一行）。Skill 相关的两个类 35 项全绿 |

逐类核实过归属：这些测试文件与被测主代码均未被第一轮改动。`InternalApiControllerTest` 的失败是测试自身只声明 11 个 `@Mock`、缺 `modelProviderMapper`，而该构造参数在第一轮之前就已存在——**该项已在第三轮修复**，见 R3-9。

> **归因更正**：本节早先的版本把 `harnax-entity` 的 142 errors 笼统归给「`schema-test.sql` 与迁移漂移」。重跑后逐项数过：其中 140 项是测试类为 final Kotlin 类、Spring 测试上下文用 CGLIB 代理时报 `AopConfigException`（根本没执行到 SQL），与 schema 无关；真正源于漂移的只有 `agent_tool` 缺 V5 的 `is_required`（2 errors）与同类的 2 个断言失败。`skill` / `skill_repository` 两表的漂移已在第五轮对齐（`skillmd` / `resources` 改为 `MEDIUMTEXT`，补上 `V15` 的 `active_name` / `builtin_guard` 生成列与三个唯一索引，`source_type` 注释补 `BUILTIN`）；`agent_tool` 等非 Skill 表的漂移不在本轮范围，而那 13 个测试类的 `final` 修改属于测试基础设施、超出 Skill 范围，已撤回。

另需留意：改动前的 HEAD 基线上 `harnax-admin` 的测试代码**根本无法编译**（`JwtAuthenticationFilterTest` 缺 `internalApiSecret` 参数、`SecurityUtilsTest` 类型不匹配），工作区已修好，因此无法取得 HEAD 的 admin 测试基线做逐项对照。

#### 第二、三轮修复后的验证

| 范围 | 结果 |
|------|------|
| `harnax-admin` Skill + Cli 单元测试 | 317 项全绿（Skill 283 + Cli 34） |
| `harnax-entity` Skill Mapper 测试 | 35 项全绿（`SkillMapperTest` + `SkillRepositoryMapperTest`） |
| `InternalApiControllerTest` | 16 项全绿（原 14 项 + 新增 2 项）；修复前整类因 `InjectMocksException` 全红 |
| 集成测试（`-DskipITs=false`） | 17 项全绿：`SkillSourceCrudIT` 11 项 + `SkillSourceExtraIT` 6 项，跑在 Testcontainers 的真实 MySQL 8.0 上，Flyway 执行到 V15 |
| harnax-cli | `go build ./...`、`go vet ./...` 与 `gofmt -l` 均无输出；该项目没有 Go 测试文件，`make test` 是空跑。R3-10 / R3-11 的四种 cobra 错误路径逐一实测；R3-14 用一个本地桩服务冒充 admin API，把 `/skills/batch` 的六种响应（全成功、部分失败、全失败、仅 `flagged`、零保存、旧版整数）逐一跑过并核对输出与退出码，`--names` 子集与不在 fetch 结果里的技能名也各验一次，验证后桩服务与临时凭据已删除。另把 `cmd/skill.go` 的 15 个调用点逐一对过两个 Controller 的 mapping 与响应 DTO（含 `SkillResponse.repositoryName` 这类 `var` 字段与 `SyncSkillResponse` 的字段名），除 R3-14 外无其余契约漂移 |
| 微信小程序 | `npm run tsc`（即 `tsc --noEmit`）零错误，覆盖 R3-12 改动后的表单、服务层与新增的 `utils/skillInstall.ts` |

第三轮回查调用方时只改了 harnax-cli 与小程序（R3-10 至 R3-14），未触碰任何 Kotlin 代码，上表的后端数字仍然成立。

本轮新增与扩充的测试：

- `SkillRequestValidationTest`（新增 16 项）：钉住 DTO 的 `@field:Size` 真的生效。没有 R3-4 的修复，其中 URL / branch / name / version 的超限断言全部会失败；
- `SkillLoaderTest`（+3 项）：Git url / branch 的长度上界，以及「长度量的是 trim 后的那一份」；
- `SkillSourceServiceImplTest`（+3 项）：新版 create 与 update 两个分支的配置归一化；
- `SkillRepositoryServiceImplTest`（+5 项）：旧版 create 的配置校验、legacy 列与 JSON 配置存同一份规范值、跨租户读取被拒、内置仓库与内部调用仍然放行；
- `InternalApiControllerTest`（+2 项）：停用技能既不进 `skillDetails` 也不进 `skillList`、悬空绑定不影响其余技能。当时的判断是「CLI 合并处不加同类过滤：`saveSkillBindings` 强制 CLI 绑定只能引用内置仓库的技能，而内置技能不可 toggle，加了是死代码」——这一判断在第五轮被推翻，见 R5-1
- `SkillSourceCrudIT` / `SkillSourceExtraIT`（重写，17 项）：改走 multipart 上传并断言当前契约，新增对「JSON 端拒绝 ZIP」「ZIP 的 fetch / install 为一次性」「`status = 99` 不落库」「内置仓库只读」「`zipPath` 不出服务器」的回归保护。

本轮**未处理**的既存失败（与 Skill 无关）：`AgentTask` / `TokenStats` / `AdminUserInitializer` / `ApiKeyService` / `AuthService` / `SecretFieldEncryptor` 六类；`harnax-entity` 的 Mapper 错误主要是 final 测试类的 CGLIB 代理问题（见上方归因更正），与 `schema-test.sql` 漂移无关。

#### 第四轮（R4-1）修复后的验证

| 范围 | 结果 |
|------|------|
| `harnax-admin` Skill 全量单元测试（`-Dtest='Skill*'`） | 328 项全绿，其中本轮新增 4 项 |
| 集成测试（`-DskipITs=false`） | 17 项全绿，与第二、三轮同一批用例（`SkillSourceCrudIT` 11 项 + `SkillSourceExtraIT` 6 项），走真实 ZIP 上传 → 安装 → 落库 |
| 前端与 CLI | 未改动：`SkillInstallResponse` 的结构没变，只是 `failed` 里多了一类来源，webui、小程序与 harnax-cli 的分级展示自动覆盖 |

本轮新增与调整的测试：

- `SkillLoaderTest`（+1 项，另改 2 项断言）：非法 UTF-8 的 `SKILL.md` 只让所在目录进 `failures`，同一个压缩包里的正常技能照常被解析；「有 SKILL.md 但内容为空」从「静默跳过」改为断言失败项的目录名与原因；「目录里没有 SKILL.md」补上 `failures` 为空的断言，钉住「非技能目录不算失败」这条边界；
- `SkillSourceServiceImplTest`（+1 项）：`installSkills` 把 loader 失败并进 `failed`，`complete` 转为 false；
- `SkillServiceImplTest`（+2 项）：选择性同步会报出勾选到的那个读不出来的目录，且原因是解析失败而不是「Not present in the source anymore」；没勾选的目录不进报告。

第一次跑就暴露了一个本轮自己引入的重复报告：勾选清单里既有能读的技能也有读不出来的目录时，该目录会同时被报成「解析失败」与「源里已不存在」。修法是把 loader 失败的合并提到解析勾选清单之前，并让「源里已不存在」分支跳过已经报过的名字——真实原因优先，且只报一次。

#### 前后端管理路径与 agent 加载路径的核对结论（第五轮）

- **前端 ↔ 后端**：webui 的 9 个 `skill-sources` 调用 ↔ `SkillSourceController` 的 9 个 mapping、7 个 `skills` 调用 ↔ `SkillController` 的 7 个 mapping，逐一对应；`agent.ts` 只用 `skill-repositories/page` 与 `skills/page` 两个接口；`SkillRepositoryController` 的其余端点由 harnax-cli 使用。**没有缺失的端点，也没有无人调用的接口**；
- **后端管理层**：`SkillMapper` 9 个方法 ↔ `SkillMapper.xml` 9 条语句、`SkillRepositoryMapper` 9 ↔ 9（差集只有 `resultMap` 定义）；实体字段 ↔ `resultMap` property 双向全等（`Skill` 15 个、`SkillRepository` 16 个）；DDL 列 ↔ 实体字段双向全等，`V15` 的 `active_name` / `builtin_guard` 是 VIRTUAL 生成列，正确地不在实体里；`insert` 覆盖全部可写列，`updateById` 有意不含 `status` / `tenant_id` / `create_time`——`tenant_id` 是租户归属、不可迁移，`create_time` 不应当成普通列被刷写，`status` 则走专用的 `updateStatus`，三者不进 `updateById` 都是设计；缺陷在于 DTO 上声明了 `status`、实现却不读（R5-4）；
- **agent 加载链路**：下发（`InternalApiController` 三条路径）→ 解析（`AgentSpecResolver`）→ 装配（`SkillAdaptorImpl` → `InMemorySkillRepository`）逐环核对，查出三项（R5-1 / R5-2 / R5-3）。

#### 第五轮（R5-1 至 R5-6）修复后的验证

| 范围 | 结果 |
|------|------|
| `harnax-admin`（`Skill*` + `InternalApiControllerTest` + `AgentServiceImplTest` + `CliServiceImplTest`） | 426 项全绿 |
| `harnax-agent-service`（`AgentSpecResolverTest` + `SkillAdaptorImplTest`） | 38 项，1 项失败——失败项与 Skill 无关，见下方 |
| `harnax-entity` Skill Mapper 测试 | 35 项全绿 |
| 集成测试（`-Pintegration-test -DskipITs=false`） | 17 项全绿（`SkillSourceCrudIT` 11 项 + `SkillSourceExtraIT` 6 项），跑在 Testcontainers 拉起的真实 MySQL 8.0 上、Flyway 执行到 V15 |

本轮新增 18 项用例（前 7 项盖 R5-1 / R5-3，后 11 项盖 R5-4 / R5-5）：

- `InternalApiControllerTest$AgentSkillDeliveryTests`（+2 项，该类现 4 项）：CLI 关联的停用技能不下发、CLI 带来的同名技能与 agent 直绑技能冲突时保留后者；
- `AgentServiceImplTest`（新增 `SkillBindingConstraintTests` 5 项）：内置仓库技能被拒、同名技能被拒且消息里点名、名字互不相同时照常写入、内置仓库缺失（`getBuiltinRepository()` 返 null）时同名拦截仍然生效、`selectByIds` 少返一行（已软删的 ID）不报错；
- `SkillServiceImplTest`（+5 项）：update 带 status 时走专用的 `updateStatus`、不带时绝不动它、带非法值时拒绝且不写库；create 带非法值时在**任何库访问之前**就拒绝、显式传 `status = 0` 时存成停用；
- `SkillRepositoryServiceImplTest`（+4 项）与 `SkillSourceServiceImplTest`（+2 项）：同一组断言。`SkillSourceServiceImplTest` 额外钉住「非法 status 必须在拉取之前拒绝」——克隆是整个请求里最贵的一步；
- `AgentSpecResolverTest`（新增 `BuiltinSkillInjection` 3 项，属 agent 加载链路）：内置技能排在 spec 自带技能之前、spec 已按 ID 带过的不重复注入、同名时内置让位——最后一条同时断言 `specContextHolder` 里存的也是合并后的结果，否则 adaptor 回退到 DB 又把让位的那个捞回来；
- `SkillAdaptorImplTest`（+2 项）：DB 回退落在停用技能时返回 null，分别走「上下文为空」与「上下文非空但未命中」两条入口。

跨模块构建的坑（记下以免下次重现）：`mvn -o -pl harnax-admin test` **不带 `-am`** 时用的是本地仓库里旧的 `harnax-entity` jar，会报出一批假编译错误（`Unresolved reference 'selectBuiltinRepository'`、`Too many arguments for 'fun selectRepositoryList(...)'`），看表面很容易误判为本次改动改坏了契约。

#### 本轮只记录、未改代码两项

- **`AgentSpecResolverTest` 的唯一失败项属 tool 范围**：`resolve should build tool specs from toolDetails` 断言 `assertEquals(1, agentSpec.toolSpecs[0].needConfirm)`，而 `ToolSpec.needConfirm` 是 `Boolean = false`（`harnax-tools-sdk/.../ToolSpec.kt`），这条断言恒失败。已用 `git diff` 确认本轮对 `AgentSpecResolver.kt` 的修改只涉及内置技能注入、未碰 tool 映射，`ToolSpec.kt` 也不在改动列表——属既有测试错误，不在 Skill 与 agent 加载范围内，只记录不修。
- **`V15` 在一个已被跨租户污染过的库上会中断迁移**：第 3a 步的重名去重按 `(tenant_id, name)` 分区、保留每个租户最旧的一行，所以租户 1 与租户 2 各有一行 `builtin-cli-skills` 时两行都会保留；紧接着的 `uk_skill_repository_builtin_guard` 会因为两行的 `builtin_guard` 都是 1 而创建失败，整个迁移卡在 V15。P0-4 的保留名校验只挡得住此后新写入，挡不住此前已建出的重复行。已执行过的库上不能就地改写 `V15`：`spring.flyway.validate-on-migrate: true` 生效， checksum 不一致会直接卡住启动；而 `application.yml` 里的 `spring.flyway.repair-on-migrate: true` 是一行**无效配置**——已反编译确认 Spring Boot 4.0.1 的 `FlywayProperties` 没有 `repairOnMigrate` 字段（只有 `validateOnMigrate` 与 `validateMigrationNaming`），而 `@ConfigurationProperties` 默认忽略未知字段，所以它不报错、也什么都没开启，不能指望它自动修正 checksum。**本轮未改动任何迁移脚本**；如需修复这类库，应新增一个 `V16` 先把多余的 builtin 行改名再补索引。

## 10. 关键文件索引

| 环节 | 文件 |
|------|------|
| 实体 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/SkillRepository.kt`、`Skill.kt`、`AgentSkillBinding.kt`、`CliSkillBinding.kt`、`dto/SkillDetailDto.kt` |
| Mapper | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/SkillMapper.kt`、`SkillRepositoryMapper.kt`、`AgentSkillBindingMapper.kt`、`CliSkillBindingMapper.kt`（XML 同名位于 `harnax-entity/src/main/resources/mapper/`） |
| 管理 API | `harnax-admin/.../controller/SkillSourceController.kt`（新）、`SkillController.kt`、`SkillRepositoryController.kt`（旧） |
| 管理服务 | `harnax-admin/.../service/impl/SkillSourceServiceImpl.kt`（新）、`SkillServiceImpl.kt`、`SkillRepositoryServiceImpl.kt`（旧） |
| Loader | `harnax-admin/.../skill/loader/`：`SkillLoader.kt`、`SkillLoadResult.kt`、`SkillLoaderRegistry.kt`、`SkillFileParser.kt`、`GitSkillLoader.kt`、`NpmSkillLoader.kt`、`ZipSkillLoader.kt` |
| 安装与内容安全 | `harnax-admin/.../skill/SkillInstaller.kt`（短事务落库 + 失败清单）、`SkillContentScanner.kt`（高危命令扫描） |
| 安装结果 DTO | `harnax-admin/.../dto/SkillInstallResponse.kt`、`SkillSourceInstallResponse.kt` |
| 来源配置与策略 | `harnax-admin/.../skill/SkillSourceConfigs.kt`（`parse` 给 Loader、`forApi` 给 DTO、`normalized` 给写入路径）、`SkillSourcePolicy.kt`（保留名、status 二态、不可刷新来源的统一裁决点）、`harnax-admin/.../constant/BuiltinRepository.kt` |
| 内置技能种子 | `harnax-admin/src/main/resources/db/migration/V12__seed_builtin_cli_skills.sql`、`V14__*.sql` |
| 完整性迁移 | `harnax-admin/src/main/resources/db/migration/V15__skill_source_integrity.sql`（内置仓库语义修正 + `is_public` 继承 + 重复名重命名 + 三个唯一索引）、`V9__skill_content_in_mysql.sql`（`MEDIUMTEXT`） |
| 绑定保存 / 下发 | `harnax-admin/.../service/impl/AgentServiceImpl.kt`、`CliServiceImpl.kt`、`controller/InternalApiController.kt` |
| 内置技能缓存 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/BuiltinSkillRegistry.kt`、`client/AdminApiClient.kt` |
| Spec 解析 | `harnax-agent/harnax-agent-service/.../runner/AgentSpecResolver.kt`、`harnax-harness-core/.../agent/AgentSpec.kt`（`SkillSpec`） |
| 技能适配器 | `harnax-agent/harnax-agent-service/.../adaptor/SkillAdaptorImpl.kt`、`harnax-harness-core/.../agent/adaptor/SkillAdaptor.kt` |
| 运行时装配 | `harnax-harness-core/.../HarnessAgentLauncher.kt`、`HarnessAgentBuilder.kt`（`InMemorySkillRepository`） |
| 前端（webui） | `harnax-webui/src/pages/skill/`：`index.tsx`、`components/RepositoryForm.tsx`、`RepositoryList.tsx`、`SyncSkillModal.tsx`；`harnax-webui/src/utils/skillInstall.ts`（安装结果提示的唯一裁决点） |
| 前端（小程序） | `harnax-wechat-app/miniprogram/services/skill.ts`、`pages/skill/list/index.ts`、`pages/skill/repo-form/index.ts`、`utils/skillInstall.ts`（安装结果提示的唯一裁决点）、`typings/api.d.ts` |
| 命令行调用方 | `harnax-cli/cmd/skill.go`（`skill` / `skill-repo` 两组命令）、`harnax-cli/main.go`（cobra 错误的唯一出口）、`harnax-cli/internal/output/formatter.go`（新增 warning 级别）、`harnax-cli/SKILL.md` |
| 消费端（参考） | `/Users/heqingsong/code/opensource/agentscope-java`：`agentscope-core/.../skill/SkillBox.java`、`SkillToolFactory.java`、`agentscope-harness/.../HarnessAgent.java` |

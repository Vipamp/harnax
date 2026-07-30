# CLI 管理与 Sandbox 集成设计

日期：2026-07-28
分支：kotlin-dev

## 背景

Agent 在 Docker sandbox 中执行任务时，经常需要调用通用命令行工具（kubectl、gh、awscli 等）。
当前平台没有 CLI 资源的管理能力：sandbox 镜像是全局唯一配置（`harness.sandbox.image`，默认
`python:3.11-slim`），无法按 agent 差异化提供工具；也没有机制把"教 agent 使用某工具"的 skill
与工具本身关联。

目标：
1. Admin 平台新增 CLI 资源管理（CRUD）。
2. Agent 可绑定 0..N 个 CLI。
3. 构建 sandbox 时按 agent 的 CLI 组合自动构建/复用 Docker 镜像。
4. CLI 关联的 skill 在运行时自动并入 agent 的 skill 集合。

## 决策记录

| 问题 | 决策 |
|------|------|
| CLI 类型 | 通用命令行工具（kubectl/gh/awscli 等），非 AI 编码 CLI |
| 进入 sandbox 方式 | 按 CLI 组合自动构建镜像（组合 hash 缓存复用） |
| 多 CLI 组合 | 平台自动拼 Dockerfile 构建，tag = `harnax-sandbox:cli-<hash>` |
| agent-CLI 基数 | 0..N，0 个时用基础镜像、跳过构建 |
| skill 关联 | CLI 实体引用 skill（`cli_skill_binding`），运行时动态合并，不写入 agent_skill_binding |
| CLI↔skill 配置入口 | 只在 CLI 管理页维护；agent 侧只选 CLI，skill 由后台自动带出（2026-07-29 补充） |
| CLI skill 归属 | CLI 只能绑定内置仓库 `builtin-cli-skills` 的 skill；该仓库对 agent 不可选、管理端只读（2026-07-30 补充） |

## 1. 数据模型（harnax-entity）

### 表 `cli`（实体 Cli.kt）
- id / tenant_id / name / description：通用字段
- version：CLI 版本（如 "1.30.0"）
- install_script：Dockerfile 安装片段（RUN 指令内容）
- check_command：安装校验命令（如 `kubectl version --client`）
- env_params：环境变量声明 JSON（沿用 EnvBinding 模式）
- status / is_public / creator / active / create_time / update_time

### 表 `cli_skill_binding`（实体 CliSkillBinding.kt）
- cli_id + skill_id，一个 CLI 关联 0..N 个 skill

### 表 `agent_cli_binding`（实体 AgentCliBinding.kt）
- agent_id + cli_id + env_bindings(JSON)，模式同 agent_tool_binding

DDL 追加到迁移脚本（V1__init_schema.sql 同目录新增迁移文件）。

## 2. Admin 管理端（harnax-admin）

- `CliController`：`/api/admin/clis`，page/get/create/update/toggle/delete，参考 SkillController
- `CliService` / `CliServiceImpl`：CRUD + skillIds 绑定（delete-then-insert 写 cli_skill_binding）
- DTO：CliCreateRequest / CliUpdateRequest / CliResponse（内嵌关联 skill 摘要）
- Agent 侧：
  - AgentCreateRequest / AgentUpdateRequest 增加 `cliList: List<CliConfig>`（id + envBindings）
  - AgentServiceImpl 增加 `saveCliBindings()`（delete-then-insert，同现有三个 saveXxxBindings）
  - AgentResponse 增加 cliList 嵌套信息（含每个 CLI 关联的 skill）

### 2.1 内置技能仓库约束（BuiltinRepository.CLI_SKILLS = "builtin-cli-skills"）

CLI 配套 skill 统一存放于内置仓库 `builtin-cli-skills`，该仓库由平台管理，后端强制以下规则
（常量定义 `constant/BuiltinRepository.kt`，前端镜像常量 `src/constants/builtinRepository.ts`，
两端改名需同步）：

| 规则 | 实现位置 |
|------|----------|
| CLI 只能绑定该仓库的 skill（逐条校验 repositoryId） | CliServiceImpl.saveSkillBindings |
| agent 不能直接绑定该仓库的 skill（经 CLI 自动加载） | AgentServiceImpl.saveSkillBindings |
| 仓库本身 update/toggle/delete 只读 | SkillRepositoryServiceImpl.requireNotBuiltin |
| 禁止创建/改名为保留名（防伪造同名仓库绕过校验） | createSkillRepository / updateSkillRepository |
| 该仓库下 skill 禁止 create/update/toggle/delete/batchSave | SkillServiceImpl.requireNotBuiltinRepo |
| skill 不可移入/移出该仓库（updateSkill 校验新旧 repositoryId） | SkillServiceImpl.updateSkill |

防绕过设计要点：约束按"仓库名"匹配，因此必须同时封住"把 skill 改挂到内置仓库"（混入合法来源）、
"把内置 skill 移出"（绕过 agent 禁绑）、"创建同名假仓库"（自造合法来源，且会误锁真仓库）三条路径。

### 2.2 跨租户写保护

仓库/skill/CLI 的写操作（update/toggle/delete 等）在 service 层校验资源 `tenantId` 与
`TenantContext` 一致，不一致抛 BizException；上下文为 null（内部/系统调用）跳过。
选择 service 层而非 mapper SQL 层：改 SQL 会影响无租户上下文的内部链路
（InternalApiController、agent-service），service 层只拦写路径、不影响读。
（mcp/tool/model 等其他实体存在同样历史问题，属系统性治理，不在本期范围。）

### 2.3 错误处理约定

约束违规统一抛 `BizException`（code=400，携带违规对象名）；AgentServiceImpl 的外层
try-catch 增加 BizException 透传分支，避免被包装成 RuntimeException 丢失业务码。

## 3. 运行时（harnax-agent）

### AgentSpec 扩展
- 增加 `cliSpecs: List<CliSpec>`；`CliSpec(cliId, name, version, installScript, checkCommand, envBindings, skillIds)`
- 下发链路：admin `InternalApiController.buildAgentSpecResponse` 组装 `cliDetails: List<CliDetailDto>`
  （批量查询 agent_cli_binding + cli + cli_skill_binding）→ agent-service `AgentSpecResolver`
  转换为 `CliSpec` 加入 AgentSpec

### 镜像构建（harnax-harness-core 新增 sandbox/CliImageBuilder.kt）
- 输入：基础镜像 + 按 cliId 排序的 CLI 列表
- hash = sha256(baseImage + 各 CLI 的 id:version:installScript)，取前 12 位
- tag = `harnax-sandbox:cli-<hash>`；内存缓存（knownImages）→ `docker image inspect` → 存在即复用，
  避免每次 agent 创建都起子进程
- 不存在则生成 Dockerfile：`FROM <base>` + 每个 CLI 一段 `RUN <installScript>`，
  `docker build`；构建后逐个执行 checkCommand 校验（`docker run --rm --entrypoint /bin/sh <tag> -c <checkCommand>`），
  校验失败则 `docker rmi` 清理并抛错
- 同一 tag 的并发构建通过 per-tag 锁串行化（buildLocks）
- 构建/校验失败：抛 `IllegalStateException`（含 CLI 名称与 docker 输出尾部），agent 创建失败并提示
- 复用现有 DockerCommandExecutor 抽象执行 docker 命令（可测试）
- 0 个 CLI：直接返回基础镜像，不构建

### Sandbox 镜像按 agent 覆盖
- `KeepAliveSandboxManager.getOrCreate` 增加 `imageOverride` / `env` 参数；未传用全局默认镜像
- **镜像过期重建**：attach 已存在容器前，`docker inspect` 同时读取容器实际镜像
  （`{{.Config.Image}}`）；与目标镜像不一致（agent 变更了 CLI 组合）时，先持久化工作区快照
  （persistFromOrphanedContainer）再删除旧容器，新容器以新镜像创建并从快照恢复工作区
- `HarnessAgentLauncher.createAgentBase` 调用 CliImageBuilder 解析镜像，
  用于 DockerFilesystemSpec 与 HarnessAgentWrapper（keep-alive 路径）

### Skill 合并
- 实际实现在 **admin 侧**（非 launcher 侧）：`InternalApiController.buildAgentSpecResponse`
  把 CLI 关联的 skill 去重后并入 `skillDetails`（agent 自身绑定优先，按 skillId 去重）。
  agent-service 拿到的 skillDetails 已含 CLI skill，launcher 现有加载逻辑无需改动。
  好处：合并逻辑单点、CLI 的 skill 变更对所有 agent 即时生效

### 环境变量
- agent_cli_binding.env_bindings 在 admin 侧经 resolveEnvBindingsJson 解析
  （envVarId → 最新值，fallback 快照；customValue 直用）后随 CliDetailDto 下发
- 运行时注入两处：DockerFilesystemSpec.environment()（非 keep-alive 路径）、
  KeepAliveSandboxManager 新建容器时 additionalRunArgs `-e k=v`（keep-alive 路径）

## 4. 管理入口

### webui（已实现）
- **CLI 管理页** `/context/cli`（菜单：上下文管理 → CLI 工具）：
  - `src/pages/cli/index.tsx`：列表（名称/版本/描述/关联技能 Tag/状态开关/创建人）+ 搜索 + 增删改
  - `src/pages/cli/components/CliForm.tsx`：创建/编辑共用表单；关联技能多选**仅加载内置仓库**下的 skill
- **agent 表单**（CreateForm/UpdateForm）：向导新增第 5 步 "CLI 配置"（`CliConfigPanel`，纯多选，
  选项内预览 CLI 的版本/描述/关联技能，不可编辑关联）；技能仓库下拉过滤内置仓库
- **智能体卡片**（`pages/agent/index.tsx`）：新增 CLIs 统计子卡片（紫色 #722ed1，位于 Skills 与
  Sessions 之间），Popover 列出 CLI 明细；`EntityCard` 统计网格改为
  `repeat(auto-fit, minmax(64px, 1fr))` 自适应，避免 5 项在窄屏挤压
- **仓库列表页**（`RepositoryList.tsx`）：内置仓库显示"内置"Tag，隐藏状态开关/同步/编辑/删除，仅可查看
- 基础设施：`services/ant-design-pro/cli.ts`、typings（CliItem 等 + AgentItem.cliList）、
  路由 `config/routes.ts`、中英文国际化（menu + pages）

### harnax-cli(Go)
- `cmd/cli_resource.go`：`harnax cli list/get/create/update/delete/toggle`，参考 cmd/tool.go 模式
- 使用文档：`harnax-cli/docs/harnax-cli-guide.md` "CLI 工具管理" 节（含 kubectl 注册示例）

## 5. 内置数据初始化（本地环境已执行，2026-07-30）

平台自身的 harnax CLI 已作为示例/自举数据入库：

| 数据 | 内容 |
|------|------|
| skill_repository | `builtin-cli-skills`（source_type=ZIP，平台内置，只读） |
| skill `harnax-cli` | skillmd = 使用前提（配置/登录/输出格式）+ 完整 harnax-cli-guide.md（约 9KB） |
| cli `harnax` | installScript 用 python3 urllib 下载二进制到 /usr/local/bin（基础镜像无 curl，curl 方案实测失败）；checkCommand = `harnax --version`；经 cli_skill_binding 关联上述 skill |
| 安装包 | linux/amd64 静态二进制（CGO_ENABLED=0，约 7.2MB），放 `harnax-webui/public/downloads/` 随前端构建发布（产物 gitignore，重建命令见 .gitignore 注释） |

注意：
- installScript 中的下载地址当前指向本地 nginx（`http://<host>:3389/downloads/...`），
  换环境需同步修改 cli 表该字段
- 新环境初始化步骤：建内置仓库 → 插入 skill → 插入 cli + 绑定 →（可选）发布二进制。
  后续可做成 Flyway 数据迁移或启动期 initializer（见"已知事项"）
- agent 在沙箱内实际使用 harnax 还需登录凭证（serverUrl + token），可经 CLI 的
  envBindings 注入或在 skill 中指导 agent 登录

## 6. 验证

1. 单测：CliImageBuilder hash 稳定性、Dockerfile 生成、0-CLI 短路；CliService CRUD；
   saveCliBindings delete-then-insert
2. 编译：`mvn -q compile`（Java/Kotlin）、`make build`（harnax-cli Go）
3. 集成（手动）：注册 kubectl CLI（installScript 用 curl 下载）→ 关联一个 skill →
   agent 绑定该 CLI → 发起会话 → 验证 sandbox 内 `kubectl version --client` 可用、
   skill 出现在 agent 可用技能中、第二次会话镜像复用不重建

## 非目标

- AI 编码类 CLI（claude code 等）的托管
- 镜像的跨节点分发/registry 推送（本期仅构建到本机 docker）
- 旧 agent 表遗留 JSON 列（mcp_list/skill_list/tool_list）清理

## 实现状态（更新至 2026-07-30）

已完成：
- 数据层：Cli / AgentCliBinding / CliSkillBinding 实体 + Mapper（含 selectByIds/selectByCliIds 批量方法）+
  `V8__add_cli_management.sql`；顺带为 SkillMapper 补充 selectByIds
- Admin：CliController/CliService/DTO 全套；Agent 的 cliList 绑定（save/read/delete）；
  InternalApiController 下发 cliDetails 并合并 CLI skill
- 运行时：CliImageBuilder（单测 10 例通过）、KeepAliveSandboxManager 镜像覆盖 + 过期重建、
  HarnessAgentLauncher/Wrapper 全链路传递
- Go CLI：`harnax cli` 命令组；文档 harnax-cli-guide.md 已更新
- webui（07-29~30）：CLI 管理页、agent 表单 CLI 步骤、智能体卡片 CLIs 统计、
  仓库列表内置只读展示、中英文国际化（见第 4 节）
- 内置数据（07-30）：builtin-cli-skills 仓库 + harnax-cli skill + harnax CLI + 二进制发布（见第 5 节）
- 约束与加固（07-30）：内置仓库六条防绕过规则、跨租户写保护、BizException 统一（见 2.1~2.3 节）

代码 review 后的优化（相对初版实现）：
- 三处 N+1 批量化：InternalApiController CLI 段、AgentServiceImpl.convertToResponse CLI 段、
  CliServiceImpl.convertToResponse；AgentServiceImpl 约束校验批量化
- CliImageBuilder 增加 knownImages 内存缓存
- keep-alive 容器镜像过期检测与快照保全重建（初版会一直沿用旧镜像）
- 前端：内置仓库名抽共享常量（消除 8 处硬编码）、CreateForm 提交前全量校验、
  CliConfigPanel options 显式 data 字段 + 空 id 过滤、EntityCard 统计网格自适应

相关 commit（kotlin-dev）：`3d365b3`（设计文档）→ `ff242d7`（后端+CLI 主体）→
`7f182e6`（webui）→ `9f992b2`（约束+租户保护）→ `2f37490`（测试修复）

已知事项 / 后续可选：
- `docker build` 在首次使用某 CLI 组合时同步执行（agent 创建阻塞至构建完成，fail-fast 设计）；
  如需异步预构建，可在 agent 保存时触发
- 内置数据初始化目前是手工 SQL，建议做成 Flyway 数据迁移或启动期 initializer，
  并将 installScript 的下载地址参数化（当前指向本地 nginx）
- 内置仓库名前后端各有一份常量（BuiltinRepository.kt / builtinRepository.ts），改名需同步；
  彻底方案是经 API 下发
- 跨租户写保护只覆盖了仓库/skill/CLI；mcp/tool/model 等实体的同类历史问题待系统性治理
- InternalApiController 中 tool/mcp/skill 段仍是存量的逐条 selectById 模式（预存在）；
  env 变量解析（resolveEnvBindingsJson）逐条查询同为预存在问题
- 端到端验证（第 6.3 节）未完成：admin 登录含验证码，命令行实测受阻；
  需在页面创建绑定 CLI 的 agent 发起会话验证镜像构建与 skill 加载

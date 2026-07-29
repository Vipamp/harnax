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

- webui：CLI 管理页 + agent 编辑页 CLI 多选（本期后端优先，webui 可后补）
- harnax-cli(Go)：新增 `cmd/cli_resource.go`，命令 `harnax cli list/get/create/update/delete/toggle`，
  参考 cmd/tool.go 模式

## 5. 验证

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

## 实现状态（2026-07-29）

已完成：
- 数据层：Cli / AgentCliBinding / CliSkillBinding 实体 + Mapper（含 selectByIds/selectByCliIds 批量方法）+
  `V8__add_cli_management.sql`；顺带为 SkillMapper 补充 selectByIds
- Admin：CliController/CliService/DTO 全套；Agent 的 cliList 绑定（save/read/delete）；
  InternalApiController 下发 cliDetails 并合并 CLI skill
- 运行时：CliImageBuilder（单测 10 例通过）、KeepAliveSandboxManager 镜像覆盖 + 过期重建、
  HarnessAgentLauncher/Wrapper 全链路传递
- Go CLI：`harnax cli` 命令组；文档 harnax-cli-guide.md 已更新

代码 review 后的优化（相对初版实现）：
- 三处 N+1 批量化：InternalApiController CLI 段、AgentServiceImpl.convertToResponse CLI 段、
  CliServiceImpl.convertToResponse
- CliImageBuilder 增加 knownImages 内存缓存
- keep-alive 容器镜像过期检测与快照保全重建（初版会一直沿用旧镜像）

已知事项 / 后续可选：
- `docker build` 在首次使用某 CLI 组合时同步执行（agent 创建阻塞至构建完成，fail-fast 设计）；
  如需异步预构建，可在 agent 保存时触发
- InternalApiController 中 tool/mcp/skill 段仍是存量的逐条 selectById 模式（预存在，未在本期范围）
- env 变量解析（resolveEnvBindingsJson）内部逐条查询为预存在问题
- webui 管理页未实现（本期后端优先）
- 端到端验证（第 5.3 节）待起服务后执行

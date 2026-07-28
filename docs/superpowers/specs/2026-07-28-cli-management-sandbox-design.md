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
- 增加 `cliSpecs: List<CliSpec>`；`CliSpec(cliId, name, installScript, checkCommand, envBindings, skillIds)`
- AgentSpecResolver 从 agent_cli_binding + cli + cli_skill_binding 组装

### 镜像构建（harnax-harness-core 新增 sandbox/CliImageBuilder.kt）
- 输入：基础镜像 + 按 cliId 排序的 CLI 列表
- hash = sha256(baseImage + 各 CLI 的 id:version:installScript)，取前 12 位
- tag = `harnax-sandbox:cli-<hash>`；`docker image inspect` 存在即复用
- 不存在则生成 Dockerfile：`FROM <base>` + 每个 CLI 一段 `RUN <installScript>`，
  `docker build`；构建后逐个执行 checkCommand 校验（`docker run --rm <tag> <checkCommand>`）
- 构建失败：抛出明确错误码（AGENT_CLI_IMAGE_BUILD_FAILED），agent 启动失败并提示
- 复用现有 DockerCommandExecutor 抽象执行 docker 命令（可测试）
- 0 个 CLI：直接返回基础镜像，不构建

### Sandbox 镜像按 agent 覆盖
- KeepAliveSandboxManager 当前构造时持有单一 image；改为 `getOrCreate` 支持传入
  per-session image（agent 解析后得到的 CLI 镜像），未传则用全局默认
- HarnessAgentLauncher 在装配 DockerFilesystemSpec 时使用 CliImageBuilder 的产物镜像

### Skill 合并
- HarnessAgentLauncher 加载 skill 处（现 321-330 行）：
  最终 skill 集合 = agentSpec.skills ∪ cliSpecs 的 skillIds（按 skillId 去重），
  经同一 SkillAdaptor.getSkill() 加载

### 环境变量
- agent_cli_binding.env_bindings 解析后注入 sandbox 容器环境变量（docker run -e），
  沿用现有 EnvBinding 解析逻辑（envVarId 引用 / customValue）

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

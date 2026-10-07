# Harnax 产品总览

> 项目性质：个人独立项目，架构设计、接口契约、全部实现与部署环境均为本人独立完成，非职务作品。本文按当前代码库实际能力整理，是 `prod_doc` 各域文档的门面：这里只给「能力由谁提供、走哪条链路」，细节留在各域文档。
>
> 规模口径：11 个顶层 Maven 模块，再加 14 个子模块——`harnax-agent` 4 个、`harnax-channel` 6 个、`harnax-client` 3 个、`harnax-tools-external` 1 个；后端主代码全量 Kotlin、无 Java 源码。库表由 Flyway 脚本承载，每个服务对自己的数据源各持一份 schema 基线；交付形态为 `harnax-deploy/docker-compose.yml` 一键起全链路。

## 1. 一句话定位

Harnax 把 AI Agent 从「一次调用」做成「可长期运行的系统」：配置化装配、可中断可恢复的流式执行、沙箱隔离的工具执行、会话粘性的多实例路由、集群化定时触发、Web 与 IM 多渠道接入，以及围绕这些能力的一整套管理面。

它解决的工程问题是：Agent 的可变状态（ReAct 循环上下文、workspace 文件、等待人工确认的挂起态）不在数据库里，而在某个进程的内存与磁盘上。一旦要横向扩容、要定时自启、要从 IM 侧触达，「无状态服务 + 轮询网关」的模型就会失效，整套架构围绕这个约束展开。

## 2. 分层与模块清单

### 2.1 后端 Maven 模块

| 模块路径 | 职责 |
|------|------|
| `harnax-admin` | 管理面：Agent、团队、技能、MCP、工具、CLI 包、模型与服务商、API Key、租户、渠道、会话、环境变量、Token 监控的 REST API；管理侧 Flyway 脚本在 `harnax-admin/src/main/resources/db/migration/` |
| `harnax-agent` | Agent 侧聚合 POM，含下列四个子模块 |
| `harnax-agent/harnax-harness-core` | 执行内核：AgentSpec 装配、沙箱、技能投影、MCP 配置解密、权限与危险输入检测、产物存储、团队编排、MinIO 快照 |
| `harnax-agent/harnax-agent-service` | 可部署的运行时服务：对话/流式/确认/命令/中断端点，持有实例内会话状态，实现内核要求的 Adaptor |
| `harnax-agent/harnax-tools-sdk` | 工具与执行上下文的编译期契约，含工具注册表，供工具实现依赖 |
| `harnax-agent/harnax-agent-utils` | 模型与 MCP 的配置适配实现，按服务商协议族建立客户端 |
| `harnax-session-router` | 会话网关：「会话 → 实例」放置决策、请求转发、健康探测、熔断、调用日志 |
| `harnax-scheduler` | 调度服务：Quartz 集群定时触发的 Agent 任务，使用独立数据源 |
| `harnax-channel` | 渠道侧聚合 POM，含下列六个子模块 |
| `harnax-channel/harnax-channel-sdk` | 渠道契约：统一消息模型、适配器接口与回传管线 |
| `harnax-channel/harnax-channel-service` | 可部署的渠道接入服务，含监听权抢占守卫 |
| `harnax-channel/harnax-channel-feishu` | 飞书适配，含 WebSocket 长连接模式 |
| `harnax-channel/harnax-channel-wechat` | 个人微信适配 |
| `harnax-channel/harnax-channel-wecom` | 企业微信适配 |
| `harnax-channel/harnax-channel-dingtalk` | 钉钉适配 |
| `harnax-auth` | 登录态、令牌与权限判定 |
| `harnax-entity` | 实体、MyBatis Mapper 与跨模块共享 DTO；持久层测试的落点 |
| `harnax-common` | 常量、异常、加密与工具类 |
| `harnax-protocol` | 服务间与流式事件的请求/响应契约 |
| `harnax-client` | 嵌入式客户端 SDK 聚合 POM：`harnax-client/harnax-client-common`、`harnax-client/harnax-single-client`、`harnax-client/harnax-springboot-client` |
| `harnax-tools-external` | 工具集聚合 POM，唯一子模块 `harnax-tools-external/harnax-tools-buildin` 承载随镜像提供的内置工具箱 |

技术栈：JDK 21 + Kotlin 2.2 + Spring Boot 4 + MyBatis + MySQL + Redis + Quartz + Jackson 3 + WebFlux/Reactor；Agent 能力内核基于 AgentScope 2.x，格式约束由根 `pom.xml` 的 Spotless 统一。

### 2.2 前端与移动端

| 目录 | 形态 | 说明 |
|------|------|------|
| `harnax-webui` | React + Ant Design Pro | 管理台，也是文件与产物的主要出入口 |
| `harnax-app` | uni-app | H5 与小程序双端 |
| `harnax-wechat-app` | 微信小程序 | 独立小程序工程 |
| `harnax-ui-test` | Playwright | 端到端用例 |

管理台页面按 `harnax-webui/src/pages` 的实际目录划分：`agent`、`agent-task`、`api-key`、`channel`、`cli`、`env-variable`、`mcp`、`model`、`session`、`skill`、`team`、`tenant`、`token-monitor`、`tool`、`user`。

### 2.3 部署与资产

| 目录 | 说明 |
|------|------|
| `harnax-deploy` | 部署唯一入口：各服务 Dockerfile、`harnax-deploy/docker-compose.yml`、`harnax-deploy/sql/init-databases.sql`、`harnax-deploy/nginx.conf`、`harnax-deploy/build.sh`、`harnax-deploy/deploy-all.sh`、`harnax-deploy/deploy-service.sh`、`harnax-deploy/roll-scheduler.sh`（scheduler 副本逐台滚动，`deploy-service.sh` 的 scheduler 分支调它）、`harnax-deploy/Dockerfile.router-native`（`build.sh` 构建 router 的 native 镜像）、`harnax-deploy/mcp-server/`（compose 里 `mcp-server` 服务的构建上下文） |
| `cli-packages` | CLI 插件包货架，按包存放可分发产物，随包提供 `cli-packages/build.sh` |
| `sandbox-plugins` | 沙箱侧扩展，含 `sandbox-plugins/Dockerfile.custom-sandbox` 与 `sandbox-plugins/build.sh` |
| `harnax-cli` | Go 语言 CLI，在沙箱内被 Agent 调用 |

`harnax-deploy/docker-compose.yml` 当前编排 10 个服务：`mysql`、`redis`、`minio`、`admin`、`router`、`scheduler`、`agent-service`、`channel-service`、`mcp-server`、`frontend`，全部挂在自建 bridge 网络上。

## 3. 核心能力

### 3.1 智能体与会话

Agent 规格的登记与编辑在 `harnax-admin`，运行在 `harnax-agent/harnax-agent-service`。一个 Agent 配置模型、系统提示词、迭代上限、工具集、MCP 服务、技能包与 CLI 工具链，绑定关系分别落在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolBinding.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentMcpBinding.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentSkillBinding.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentCliBinding.kt`；工具自身的参数与环境占位在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolEnvParam.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/EnvVariable.kt`。

执行内核不反向依赖配置来源：内核按接口向宿主索取信息，`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/` 目录提供九个实现——模型配置（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ChatModelConfigAdaptorImpl.kt`）、MCP 配置（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt`）、工具配置（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt`）、技能（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillAdaptorImpl.kt`）、Token 统计（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/TokenStatAdaptorImpl.kt`）、过程日志（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ProcessLogAdaptorImpl.kt`）、工具调用日志（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImpl.kt`）、计划便签（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/PlanNoteAdaptorImpl.kt`）、MCP 访问令牌来源（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`）。装配入口是 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` 与 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`。同一份内核因此既可嵌在服务里，也能通过 `harnax-client` 脱离配置中心独立运行。

会话与消息由 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Session.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/MpSession.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/MpChatMessage.kt` 承载。运行期端点在 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt`：除对话与流式外，另有 `/confirm`、`/command`、`/chat/interrupt/{sessionId}` 三类旁路，配合实例内状态实现挂起后回到同一实例续跑；另有 `GET /chat/history/{sessionId}` 读历史、`GET /context/{sessionId}` 读占用两条读端点。

页面的会话历史与模型的上下文是两份数据：模型侧 `AgentState.context` 允许被压缩覆写，页面侧读会话库那张只增不改的 `session_message`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/MysqlSessionMessageStore.kt`），所以压缩过一次或十次，`/chat/history` 返回的仍是全部原文气泡，且任何时候不出现摘要气泡。`/command` 的 `COMPACT` 分支按命令压缩，`GET /context/{sessionId}` 给占用比例（分子取账单真值），两者在 `harnax-webui` 与 `harnax-ios` 的会话聊天页都有入口；分母取模型域 `context_window` 列，留空时按模型名推断再兜底。

### 3.2 多智能体团队

团队实体 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Team.kt` 直接持有主管的全部配置：`systemPrompt` 与 `modelId` 只存在于团队行上，成员则是普通的 `Agent` 行，各自保留自己的工具、MCP、技能与 CLI 绑定。因此一个 Agent 只有两种角色——可独立对话，或作为某个团队的成员——不存在冒充主管的行。成员与团队的关联在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamMember.kt`，团队级技能绑定在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamSkillBinding.kt`，成员间交换的产物在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamArtifact.kt`。

运行期编排在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/`：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamOrchestrator.kt` 负责分工与回收，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRole.kt` 定义角色，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt` 描述运行期规格，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt` 按角色装配工具箱，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamArtifactGateway.kt` 定义产物交换通道并以 MinIO 为对象存储实现。管理台入口是 `harnax-webui/src/pages/team`。

### 3.3 技能

技能内容与版本存在 MySQL 中，由 `harnax-admin` 管理；来源可以是仓库登记（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/SkillRepository.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt`）或上传的包，上传路径经过 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillContentScanner.kt` 的内容检查与 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/ZipSkillLoader.kt` 的解包，来源类型由 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillSourcePolicy.kt` 判定，登记端点在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt`。Agent 与团队通过绑定表引用技能。

技能不只是提示词片段：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SandboxSkillProjector.kt` 在会话沙箱就绪后把技能包投影成工作区里真实存在的目录与文件，模型用文件工具读取它们。管理台入口是 `harnax-webui/src/pages/skill`。

### 3.4 MCP 服务

MCP 服务登记在 `harnax-admin`，运行期由 `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/` 建立连接并把远端工具并入模型可用工具集。传输类型为 `sse` 与 `streamablehttp`；`stdio` 行在管理面可登记但不参与运行，判定集中在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt`——stdio 意味着由 agent-service 直接拉起子进程，而该运行环境不做进程隔离。

鉴权类型是 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt` 的一组常量，被两个进程共享：管理侧决定存什么、下发什么，运行期据此在「静态请求头」与「按用户访问令牌」之间分支。运行期 honour 的集合为 `NONE`、`STATIC_HEADER`、`OAUTH2`。

按用户授权走 OAuth 2.1：授权与令牌交换端点在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt`，客户端注册与元数据发现在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt`，用户级授权码换令牌与刷新令牌集中保管续期在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt`，客户端与用户凭据分别落在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpOauthClient.kt` 与 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpUserCredential.kt`；`state` 参数由 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt` 持有以完成回调校验。落库的凭据以 AES-256-GCM 加密（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/AesUtil.kt`，字段级封装在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`），运行期由 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt` 还原为可用配置，令牌注入按调用上下文完成（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`）。MCP 调用逐次记入 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt`。管理台入口是 `harnax-webui/src/pages/mcp`。

### 3.5 CLI 插件包

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParser.kt` 解析包描述，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageAutoRegistrar.kt` 在启动时把货架上的包登记进 `cli` 表；管理与构建触发走 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt` 与 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/CliServiceImpl.kt`，登记后的包由 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Cli.kt` 承载。

运行期按包准备沙箱内的 CLI 产物：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/CliPackageStore.kt` 持有包产物，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/CliImageBuilder.kt` 按包构建沙箱镜像，`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/sandbox/CliArtifactReaper.kt` 回收过期产物。Agent 与 CLI 的关联在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentCliBinding.kt`，货架内容在 `cli-packages` 目录。管理台入口是 `harnax-webui/src/pages/cli`。

### 3.6 工具与工具注册

工具契约在 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`，注册表在 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt`：启动时扫描 Spring 容器里的 `ToolBox` bean 按名登记，并从方法上的工具与元数据注解抽出规格，装配期再按 Agent 的工具绑定挑选可见集合。`harnax-tools-external/harnax-tools-buildin` 提供随镜像的内置工具箱（`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt` 与 `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt`），团队角色用的工具箱由 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt` 装配；文件与命令类工具在沙箱执行环境一侧提供，MCP 与 CLI 分别把远端工具与命令行工具并入同一集合。

工具的登记、启停与参数规格在 `harnax-admin`，管理台入口是 `harnax-webui/src/pages/tool`；跨模块共享的工具元数据由 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvParamDef.kt` 定义，运行期上下文见 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt` 与 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt`。需要人工确认的调用以确认事件回到调用方，确认端点在 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt`；每次工具调用记入 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolCallLogEntity.kt`。

### 3.7 定时任务

`harnax-scheduler` 是独立可部署服务，触发权交给 Quartz JDBC 集群 JobStore，同一任务在多副本下只触发一次。它使用独立数据库 `harnax_scheduler`（`harnax-deploy/sql/init-databases.sql` 建库并授权），该库内既有 11 张 `QRTZ_*` 集群表，也有 Agent 任务域三张表：任务定义 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTask.kt`、执行账本 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskExecution.kt`、执行日志 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt`。管理侧不重复持有任务数据，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/SchedulerClient.kt`（实现 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt`）在两个服务之间同步任务，管理台入口是 `harnax-webui/src/pages/agent-task`。

任务与 Quartz 的对应关系由 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/TaskQuartzRegistrar.kt` 建立，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/TaskScheduleReconciler.kt` 与 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerReconcileJob.kt` 做差异式对账：库里与 JobStore 里的任务集各自补齐与摘除，而不是删除重建。执行入口分两种——`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskJob.kt` 允许并跑，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskNonConcurrentJob.kt` 用 Quartz 的非并发注解禁止同一任务重叠；两者共用 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AbstractAgentTaskJob.kt`，其中包含一条约束：共享 JobStore 会把某个节点从未注册过的 job 的触发交给它，此时该节点直接放弃。

执行日志的状态机是六个取值：0 失败、1 成功、2 超时、3 运行中、4 停止中、5 已停止，迁移规则写在 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/mapper/AgentTaskLogMapper.kt`：终态行只允许由产生结果的执行线程写入；超过任务超时 1.5 倍仍未收尾的行由回收路径批量判为超时，用以承接节点中途宕机留下的残行，被误判的行的真实结果可以覆回；`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerHousekeepingJob.kt` 负责保留期清扫，且只删终态行。防重入判定在 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/AgentTaskExecutionGuard.kt`，指标在 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/metrics/SchedulerMetrics.kt`（按节点维度采样，集群总量靠跨节点求和，单节点非零即为告警条件），健康检查端点在 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/`。

执行日志的可见性跟着任务走：一行日志只有在其所属任务对调用方可见时才可读（公开或创建者本人），该规则与任务列表一致，且刻意不带租户条件。

### 3.8 渠道接入

渠道层把「平台如何把消息送进来」抽象为回调推送与长连接两种模式。契约在 `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/`：统一消息模型 `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/message/ChannelMessage.kt`、适配器接口 `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelAdaptor.kt`、回传管线 `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/service/ChannelChatService.kt`。`harnax-channel/harnax-channel-service` 承载运行，平台差异分别在 `harnax-channel/harnax-channel-feishu`、`harnax-channel/harnax-channel-wechat`、`harnax-channel/harnax-channel-wecom`、`harnax-channel/harnax-channel-dingtalk`。飞书使用 WebSocket 长连接（`harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuWebSocketMode.kt` 与 `harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuWsTransport.kt`），无需公网回调入口。

多副本部署下，同一渠道的监听权由基于 MySQL 命名锁的单实例抢占保证，守卫在 `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/bootstrap/ChannelListenerLockGuard.kt`；消息去重在 `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/MessageDeduplicator.kt`、文本分片在 `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/TextChunker.kt`，都在回传管线内被调用。渠道上的能力开关（思考模式、联网、计划模式）作为 `channel` 表的列存放，由管理台 `harnax-webui/src/pages/channel` 配置。

文件交付是适配器的可选能力：`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelAdaptor.kt` 的 `supportsFileDelivery()` 默认关闭，当前只有个人微信适配 `harnax-channel/harnax-channel-wechat/src/main/kotlin/com/agnetix/harnax/channel/wechat/WechatAdaptor.kt` 声明为真。未声明该能力的渠道不会触发字节拉取，用户会收到一次点名所有产物的提示（`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/service/ChannelChatService.kt` 的 `deliverFileAttachments`）。入向消息以文本进入对话管线，非文本类型在会话记录中落为占位内容。

### 3.9 模型与服务商

服务商与模型是两层对象，均由 `harnax-admin` 管理、管理台 `harnax-webui/src/pages/model` 维护：服务商记录协议族、接入地址与凭据（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ModelProvider.kt`），模型记录具体标识与参数并归属某个服务商（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Model.kt`）。模型行另带一个可选的 `context_window`（单位 token），它是会话占用比例的分母；留空则由运行期按模型名推断，再落不到时兜底。`ModelProvider.type` 取值 `dashscope`、`openai`、`ollama`，其中 `openai` 覆盖 OpenAI 及兼容协议端点。运行期由 `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/model/ChatModelConfig.kt` 的三组配置类建立客户端，`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ChatModelConfigAdaptorImpl.kt` 把管理侧配置翻译成内核所需形状。

### 3.10 API Key 与租户隔离

对外调用凭据由 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ApiKeyEntity.kt` 承载，登记与维护在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt`（实现 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt`），管理台入口是 `harnax-webui/src/pages/api-key`；每个 Key 归属一个租户，租户由 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TenantEntity.kt` 描述、管理台 `harnax-webui/src/pages/tenant` 维护，用户与租户的归属关系在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/UserTenantEntity.kt`。

「本次请求在哪个租户内行事」由 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/TenantResolver.kt` 一处回答，全部管理侧服务共用同一条链，首个给出答案的步骤胜出：请求上下文里已校验过归属的租户、调用方令牌自带的租户声明、调用方账号行上的租户、默认租户。`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/interceptor/TenantInterceptor.kt` 只在 `X-Tenant-ID` 请求头到达时填充 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/context/TenantContext.kt`，且先校验调用方确属该租户；后两步只会说出调用方自己所在的租户，因此这条链可以比单看请求头更准确，但不会把调用方放进它不属于的工作区。

隔离写在哪条语句上是有讲究的：本项目没有 MyBatis 层的自动租户拦截器，隔离只存在于显式写了租户条件的 SQL 语句里。核心配置对象带租户列——`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Team.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Agent.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ModelProvider.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Cli.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ApiKeyEntity.kt`；三张绑定表不带租户列，租户谓词挂在它们所查询的 `agent` 行上（见 `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentMapper.kt` 的 `selectByEnvVarRef`，`tenantId` 是无默认值的硬参数）。统计与日志的租户口径就写在表上：admin 的 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 给 `token_stats`、`process_log`、`tool_call_log` 三张表各声明一个可空的 `tenant_id` 列与一个 `idx_tenant_ts (tenant_id, ts)` 组合索引——租户做前导列，是因为按租户的读取总是「租户等值 + 时间范围」一个形状。NULL 在这里是真实状态而不是租户 1 的别名：一次没解析出租户的运行写 NULL，此后任何按租户的读取都不命中它。

### 3.11 Token 用量与调用日志

用量按模型与时间维度累计，落库形状在 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TokenStats.kt`，写入路径由 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/TokenStatAdaptorImpl.kt` 完成。观测口径共四类记录，均异步写入：Token 统计、执行过程日志（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ProcessLogEntity.kt`）、工具调用日志（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolCallLogEntity.kt`）、MCP 调用日志（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt`）；计划便签由 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/PlanNoteEntity.kt` 承载，供长任务续跑时回看。网关侧另有一份跨请求口径的调用日志，由 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/entity/ApiCallLog.kt` 承载、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/ApiCallLogFilter.kt` 写入，含调用方、租户、会话、Agent、模型、端点、状态码、耗时与 requestId。管理台 `harnax-webui/src/pages/token-monitor` 提供查询与看板。

### 3.12 沙箱执行环境

工具、CLI 与技能投影都在 Docker 沙箱内执行。沙箱由 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/SandboxFactory.kt` 创建，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/DockerCommandExecutor.kt` 执行命令，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/KeepAliveSandboxManager.kt` 维护保活池以复用已就绪的沙箱，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/SandboxFileWriter.kt` 负责把文件写入工作区。镜像可按 CLI 包现场构建，跨重启的状态回收靠 MinIO：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/minio/MinioSnapshotClient.kt` 与 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/minio/MinioBaseStore.kt`。

执行前的拦截在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt`，配合工具自身的确认要求；产物识别与保存在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/output/OutputFileDetector.kt` 与 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/output/OutputFileStore.kt`，工作区文件经 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SandboxWorkspaceController.kt` 出入口供前端读写。过程日志与工具调用日志异步落库，形成可回溯的执行记录。

### 3.13 会话路由与多实例

`harnax-session-router` 只持有三份状态：实例注册表、会话绑定与熔断状态，本身不承载业务数据。它提供会话粘连路由、周期性健康探测、requestId 幂等与故障转移：去重键由 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/IdempotencyService.kt` 持有，故障转移只在连接类错误且请求尚未送达时重试，重试次数可配（`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt` 的 `router.proxy.failover-max-retries`，默认 2），并有专门的转移计数指标。熔断器契约在 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/InstanceCircuitBreaker.kt`，状态为 `CLOSED`/`OPEN`/`HALF_OPEN` 三态，判定与状态迁移分离（拒绝流量判定是纯读，不做迁移）。健康检查在 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/health/HeartbeatHealthChecker.kt`。

数据面支持两种形态：单机形态用 SQLite 加进程内状态、零外部依赖，见 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/SqliteInitConfig.kt` 与 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/SqliteDirectoryInitializer.kt`；集群形态用 MySQL 加 Redis，熔断器的两份实现分别在 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/LocalInstanceCircuitBreaker.kt` 与 `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisCircuitBreaker.kt`。集群形态下「会话到实例」绑定的读改写由单个 Lua 脚本完成（`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisSessionMappingService.kt`），Redis 不可用时经影子缓存降级放行，影子条目带 TTL 且不长于其所描述的绑定。Router 不支持 Redis Cluster——跨槽脚本无法满足，这是设计约束。

## 4. 工程质量与可观测

- 库表由 Flyway 脚本管理，每个服务对自己的数据源各持一份 schema 基线，每个模块的 migration 目录下只有这一份 init 脚本：管理侧的 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 一次写完 admin 库全部 34 张表与初数据；调度侧的 `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql` 一次写完 11 张 `QRTZ_*` 集群表加任务域的 `agent_task`、`agent_task_log`、`agent_task_execution` 三张表，并记在该服务自己的历史表 `flyway_schema_history_scheduler` 里；会话路由侧另有 `harnax-session-router/src/main/resources/db/migration/V1__create_session_router_tables.sql`；`harnax_scheduler` 库本身由 `harnax-deploy/sql/init-databases.sql` 建库并授权。改表的口径是改这份基线本身并重建环境：已经按旧形态建好的库不认改过的基线，改表结构与重建库因此是同一个动作。
- 持久层测试以 Testcontainers 拉起真实 MySQL 而非 mock，集中在 `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/`，覆盖 Agent、Team、Model、ModelProvider、McpServer、McpOauthClient、McpUserCredential、Skill、SkillRepository、Channel、Cli、Session、Token、PlanNote、ProcessLog、McpCallLog、SysUser、Tenant 等实体的真实读写。
- 服务侧集成用例以 `IT` 或 `IntegrationTest` 结尾，各自使用独立的 `*_it` 数据库，例如 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/it/BaseSchedulerIT.kt`；`harnax-channel/harnax-channel-service/src/test/kotlin/com/agnetix/harnax/channel/service/it/ChannelServiceContextIT.kt` 拉起渠道服务的完整上下文。
- 会话路由的 Redis 侧行为有专门集成用例，集中在 `harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/integration/`，覆盖实例注册、会话映射与熔断。
- 端到端用例在 `harnax-ui-test`，以 Playwright 驱动管理台真实页面。
- 部署与端到端验证以 `harnax-deploy` 为唯一入口：`harnax-deploy/build.sh` 产出各镜像，`harnax-deploy/docker-compose.yml` 编排全链路，`harnax-deploy/deploy-all.sh` 与 `harnax-deploy/deploy-service.sh` 分别完成整体与单服务发布；`harnax-deploy/nginx.conf` 负责前端与 API 反代，并对 SSE 路由关闭 `proxy_buffering` 与 `proxy_request_buffering`、下发 `X-Accel-Buffering: no`。
- 运行期观测以三类日志（过程、工具调用、MCP 调用）加 Token 统计为主，网关侧另有跨请求的调用日志；Router 的健康探测、熔断与渠道的监听权状态可查询。
- 代码格式由 Spotless 统一约束，配置在根 `pom.xml`。

## 5. 当前边界

- 后端运行时代码为 Kotlin；`harnax-cli` 为 Go，`harnax-webui`、`harnax-app`、`harnax-wechat-app`、`harnax-ui-test` 为 TypeScript，都不参与 Maven 构建。
- 会话粘性的前提是同一会话回到同一实例：实例下线时该实例内存中的挂起态由状态恢复与 MinIO 快照承接，Router 的路由决策不改变这一事实。
- Router 的集群形态要求非 Cluster 模式的 Redis；Redis 不可用时走降级放行而非拒绝请求。
- MCP 的 `stdio` 传输类型只作为登记信息存在，运行期不建立本地进程型连接；MCP 鉴权类型中 `BASIC` 在数据模型中存在，但不在运行期 honour 的集合内。
- 渠道的文件交付取决于适配器是否声明该能力，未声明的渠道只收到一次点名提示；入向非文本消息在会话记录中落为占位内容。
- 服务商协议族取值限定在 `dashscope`、`openai`、`ollama`；`openai` 覆盖 OpenAI 及兼容协议端点。
- 定时任务的触发权在 Quartz JDBC 集群 JobStore，任务定义、执行账本与执行日志三张表都只落在 `harnax_scheduler` 库，管理侧不重复持有。
- 执行日志的可见性跟着所属任务走（公开或创建者本人），该读取路径不带租户条件。
- 租户隔离没有 ORM 层的自动拦截器：它由统一的租户解析链与显式写了租户条件的 SQL 语句共同构成，新增查询需要自己带上谓词。
- 部署形态为 Docker Compose，单机可运维；未做 Kubernetes 化，也无线上并发与延迟指标可引用。
- 项目定位为自托管、单人可运维：租户用于隔离配置与统计口径，不构成对外售卖的计费边界。

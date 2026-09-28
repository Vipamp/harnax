# Session 分类重设计方案

## 一、Session 分类总览

| 类型 | sessionId 格式 | 存储位置 | 生命周期 |
|------|---------------|---------|---------|
| channel | `chn-{uuid}` | `channel` 表的 `session_id` 列 | 随 channel 创建，长期有效 |
| web | `web-{uuid}` | `session` 表 | 用户创建，长期有效 |
| mp | `mp-{uuid}` | `session` 表 + `mp_session` 表的 `router_session_id` 列 | 用户创建，长期有效 |
| task | `task-{taskId}-{agentId}-{uuid}` | admin 的 `session` 表无行；id 记在 scheduler 的 `agent_task_log.session_id` | 任务开始创建，任务结束销毁 |

判据只有前缀。四类会话的 `sessionId` 前缀互斥，admin 与 router 都按前缀决定读哪张表、放行哪类调用：channel 会话与 task 会话在 admin 的 `session` 表里都没有行，`session` 表只承载 web/mp 会话。一个 `web-` 会话是否跑在 team 上不在前缀里，由 `session.team_id` 回答（`GET /api/admin/internal/sessions/{sessionId}/team`）。task 会话的两段身份（taskId 与 agentId）都编在 id 里，语法由 `harnax-common/src/main/kotlin/com/agnetix/harnax/common/session/TaskSessionId.kt` 定义，scheduler 与 admin 共用。

## 二、各模块改动

### Task 1: AgentSpec 响应 DTO（harnax-entity）

内部 API 用一个 DTO 把四类会话的 agent 配置交付给 agent-service：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/AgentSpecInfoResponse.kt`。

```kotlin
data class AgentSpecInfoResponse(
    val agentId: Long,
    val tenantId: Long? = null,
    val agentName: String,
    val description: String,
    val systemPrompt: String,
    val modelId: Long,
    val toolList: String = "[]",
    val mcpList: String = "[]",
    val enableThink: Int = 0,
    val enableSearch: Int = 0,
    val enablePlan: Int = 0,
    val permissionMode: String = "DEFAULT",
    val modelSupportInternet: Int = 0,
    val modelSupportReasoning: Int = 0,
    val modelThinkingMode: Int = 0,
    val modelConfig: ModelConfigDto? = null,
    val toolDetails: List<ToolDetailDto> = emptyList(),
    val mcpDetails: List<McpDetailDto> = emptyList(),
    val skillDetails: List<SkillDetailDto> = emptyList(),
    val cliDetails: List<CliDetailDto> = emptyList(),
)
```

四类会话共用这一个响应形状：admin 只是按前缀换数据来源，不改交付字段。team 会话另有 `TeamSpecInfoResponse`（lead 加每个成员的一份 `AgentSpecInfoResponse`），`chn-` 的归属查询用只读投影 `ChannelSessionOwner`（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/ChannelSessionOwner.kt`）。

### Task 2: Session ID 前缀生成（harnax-admin）

三个生成点各自持有自己的前缀，互不重叠：

- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt:193` — `session.sessionId = "web-${UUID.randomUUID()}"`，随后写入 `session` 表。
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/mp/MpSessionService.kt:48` — `val routerSessionId = "mp-${UUID.randomUUID()}"`，同一轮里先插一行 `session`、再插一行 `mp_session`（id 存进 `router_session_id`）。
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt:329` — `private fun generateSessionId(): String = "chn-${UUID.randomUUID()}"`，随 `channel` 行一起写入。

task 会话的 id 不由 admin 生成。admin 的 `SessionService`/`SessionServiceImpl` 里没有面向运行时创建会话的方法，`InternalApiController` 也只有 `@GetMapping`/`@PostMapping`/`@PutMapping`，没有创建或删除 session 的端点：task 会话在 `session` 表里没有行，没有可创建、可删除的对象。用户会话的删除走 `SessionController` 的 `DELETE /api/admin/sessions/{id}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SessionController.kt:163`），那是 web/mp 会话的管理动作，与 task 无关。

### Task 3: Admin 内部 API 现状（harnax-admin）

**文件**: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`（类上 `@RequestMapping("/api/admin/internal")`）

分类相关的端点：

- `GET /api/admin/internal/agent-spec/{sessionId}` — 按前缀解析 agent 配置，返回 `AgentSpecInfoResponse`。
- `GET /api/admin/internal/team-spec/{sessionId}` — team 会话的配置。
- `GET /api/admin/internal/sessions/{sessionId}/info` — 会话归属：`chn-` 读 `channel` 行（`channelMapper.selectOwnerBySessionId`），其余读 `session` 行（`sessionMapper.selectBySessionIdAndStatus`）；`task-` 不由这里回答。
- `GET /api/admin/internal/sessions/{sessionId}/team` — 会话是否跑在 team 上。
- `PUT /api/admin/internal/sessions/{sessionId}/capabilities`、`PUT /api/admin/internal/sessions/{sessionId}/permission-mode` — `chn-` 写回 `channel` 行的同名列，其余写回 `session` 行。

task 会话与 channel 会话在 `session` 表里都没有行，所以内部 API 里既没有为 task 准备会话的入口，也没有清理它的入口：`InternalApiController` 的映射只有上面这些加上 `POST /api-keys/validate`、`POST /api-keys/system-key`、`POST /mcp/access-token`、`GET /cli/inventory`，没有 `DELETE` 路由，`SessionService` 与 `SessionServiceImpl` 也没有给 agent 用的创建方法。

### Task 4: Mapper 查询能力（harnax-entity）

分类不需要「按前缀取一批行」的查询，三个 mapper 都只按完整 `sessionId` 读单行：

- `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/SessionMapper.kt` — `selectBySessionId(sessionId)`、`selectBySessionIdAndStatus(sessionId, status)`；没有前缀查询方法，`session` 表本身就是 web/mp 会话的唯一来源。
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ChannelMapper.kt` — `selectBySessionId(sessionId)` 取渠道配置，`selectOwnerBySessionId(sessionId)` 取归属并返回 `ChannelSessionOwner`（软删行也照样回答）。
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/MpSessionMapper.kt` — mp 会话通过 `mp_session.router_session_id` 关联到 `session` 行。

### Task 5: 前缀路由（核心落点）

分派发生在 admin，不在 agent-service。`InternalApiController.getAgentSpec`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:284`）是唯一按前缀选择数据来源的地方：

```kotlin
val spec = when {
    sessionId.startsWith("web-") || sessionId.startsWith("mp-") -> resolveFromSession(sessionId)
    sessionId.startsWith("chn-") -> resolveFromChannel(sessionId)
    sessionId.startsWith("task-") -> resolveFromTask(sessionId)
    else -> return ResultVo.error("Unknown sessionId prefix: $sessionId")
}
```

三个解析函数各读自己的会话来源，最后都落到 `agent` 行与它的 `model` 行组装响应：`resolveFromSession` 读 `session` 行（`team_id` 非空时在这里被拒，让调用方改走 `/team-spec`）；`resolveFromChannel` 读 `channel` 行取 `agent_id`；`resolveFromTask` 用 `TaskSessionId.parse` 直接从 id 里取 taskId 与 agentId，不读任务表——`agent_task`、`agent_task_log`、`agent_task_execution` 三张表只在 `harnax-scheduler` 有 mapper，admin 既没有引用也没有这几张表，权限模式固定为 `BYPASS`。

agent-service 侧：

- **文件**: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` — 配置解析的唯一入口，`resolve(sessionId)` 直接问 admin 的 `agent-spec`，自己不查 session/channel/task 任何业务表。
- **文件**: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt` — `getOrCreateAgent(sessionId, userIdentifier)`（:757）只做缓存与所有权校验：先问 `isTeamSession`，team 走 `buildTeamAgent`，其余走 `agentSpecResolver.resolve`。前缀在这里只出现在两个拒绝分支上：`task-` 会话不接受能力位切换（:952）也不接受权限模式改动（:1025）。

router 侧的前缀规则是权限规则而不是解析规则：`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixes.kt` 把 `task-`（常量 `TASK = "task-"`）标为特权前缀，`SessionAccessGuard` 在代理转发之前就拒绝终端登录用户拿它发起会话，因为一个 task id 是可以数完的整数。

### Task 6: agent-service 的 AdminApiClient（harnax-agent-service）

**文件**: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AdminApiClient.kt`

构造只注入两个配置：`admin.service.url`（默认 `http://localhost:8080`）与 `admin.internal-api.secret`；每个请求以 `Bearer` 头带上这份 shared secret，admin 侧的 `InternalApiAuthFilter`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt`）只认这个密钥。

与分类有关的方法都是「按 sessionId 问 admin」，没有按 taskId 取配置的方法——task 的 id 直接进 `getAgentSpec`：

- `getAgentSpec(sessionId)` → `GET /api/admin/internal/agent-spec/{sessionId}`
- `isTeamSession(sessionId)` → `GET /api/admin/internal/sessions/{sessionId}/team`
- `getTeamSpec(sessionId)` → `GET /api/admin/internal/team-spec/{sessionId}`
- `toggleCapability(sessionId, capability, enable)` → `PUT /api/admin/internal/sessions/{sessionId}/capabilities`
- `updatePermissionMode(sessionId, mode)` → `PUT /api/admin/internal/sessions/{sessionId}/permission-mode`
- `getMcpAccessToken(sessionId, mcpId)` → `POST /api/admin/internal/mcp/access-token`
- `getCliPackageInventory()` → `GET /api/admin/internal/cli/inventory`

### Task 7: `chn-` 的配置来源

渠道会话的配置来自 `channel` 行，agent-service 不需要任何渠道侧的查询能力：admin 的 `resolveFromChannel`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:401`）先用 `channelMapper.selectBySessionId` 拿到 `agent_id`，再读 `agent` 行组装 `AgentSpecInfoResponse`，能力位取 `channel` 行上的 `enable_think`/`enable_search`/`enable_plan`/`permission_mode` 四列。

`harnax-agent-service` 的主代码里既不出现 `SessionMapper`，也不出现 `ChannelMapper`：它自己连库只服务日志与模型配置这类旁路读取（例如 `ToolCallLogAdaptorImpl`、`ChatModelConfigAdaptorImpl`），会话与渠道的配置一律从 admin 内部 API 拿。

### Task 8: Scheduler 侧的生成与回收（harnax-scheduler）

- **文件**: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt`
  - `insertRunningLog()` 铸造本次执行的 sessionId：`sessionId = TaskSessionId.of(task.id, task.agentId)`（:576），两段 id 取自同一行 `agent_task`，随 running log 一起写入 `agent_task_log.session_id`。
  - `executeTaskOnce(task, triggerTime)`（:486）用这个 id 调 `routerClient.chat(sessionId, task.prompt)`（:491）。
  - 收尾调 `routerClient.clearSession(sessionId)`（:530），清除 agent-service 上的 agent 缓存与沙箱。
- **文件**: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt` — `clearSession(sessionId)`（:218）打 `DELETE /api/router/agent/session/{sessionId}`，并有一份自己的超时预算。
- **文件**: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskJob.kt`（允许重叠的任务）与 `AgentTaskNonConcurrentJob.kt`（不允许重叠）都继承 `AbstractAgentTaskJob`，二者与手动触发（`POST /api/scheduler/tasks/{id}/trigger` → `runTaskOnce()` 投递一次性 Quartz job）走的是同一条 `executeTaskOnce()` 路径，因此 id 形态全仓只有一处定义。

task 会话在 admin 的 `session` 表里没有行，scheduler 与 session 生命周期有关的动作只有一个：执行结束时让 router 清掉运行时缓存。`harnax-scheduler` 的 client 包只有 `RouterClient.kt` 与 `CommandDelivery.kt`，对 admin 内部 API 的唯一调用是 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt:61` 的 `POST /api/admin/internal/api-keys/system-key`。

### Task 9: 数据库 schema 现状

**本节不涉及 schema 变更，也没有迁移脚本。** 分类只改 `sessionId` 的取值形态，承载它的列本来就是普通字符串列，长度与索引都不需要动：

| 表 | 列 | 定义位置 |
|----|----|---------|
| `channel` | `session_id`（`varchar(64) NOT NULL`，带 `idx_session_id`） | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:180` |
| `session` | `session_id`（`varchar(100) NOT NULL`） | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:460` |
| `mp_session` | `router_session_id`（`varchar(128) NOT NULL`） | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:403` |
| `agent_task_log` | `session_id`（`VARCHAR(128)`，注释即 `task-{taskId}-{agentId}-{uuid}`） | `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:239` |

表结构的最终态只写在这三份 init 基线里，没有别的 schema 来源：

- `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`
- `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql`
- `harnax-session-router/src/main/resources/db/migration/V1__create_session_router_tables.sql`

`channel` 的列集合以 admin 的 init 基线为准；router 侧的 init 基线只有 `api_call_log` 一张表，它的 `session_id` 是 `VARCHAR(128) NULL`，上面的 `idx_session_id` 是整列索引，不区分前缀。task 会话没有对应的表：scheduler 的业务表只有 `agent_task`、`agent_task_log`、`agent_task_execution` 三张，前缀形态只出现在 `agent_task_log.session_id` 的取值里。

### Task 10: 前端

分类对前端透明：`harnax-webui/src/pages/session/`（`index.tsx` 与 `components/`）原样展示与回传 `sessionId`，`harnax-webui/src` 的源码里没有任何 `web-`/`mp-`/`chn-`/`task-` 的字符串判断。小程序侧同样只看数字主键——`harnax-wechat-app/miniprogram/services/session.ts:41` 的 `deleteSession(id: number)` 打的是 `DELETE /api/admin/sessions/${id}`，带前缀的 `router_session_id` 只在后端使用。

### Task 11: 测试

本方案的每条判据都落在已有测试上：

- id 契约（生成与解析）：`harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/support/TaskSessionIdTest.kt`
- 前缀分派与归属查询：`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/InternalApiControllerTest.kt`
- 特权前缀规则与访问守卫：`harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixesTest.kt`、`harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/service/SessionAccessGuardTest.kt`
- spec 解析与运行时：`harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolverTest.kt`、`harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunnerTest.kt`
- 会话行与渠道配置：`harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/SessionMapperTest.kt`、`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImplTest.kt`
- 任务执行与收尾：`harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImplTest.kt`、`harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskJobExecutionTest.kt`

## 三、数据流示意

### web/mp session 流程

```
创建会话 → session 表（web-xxx / mp-xxx，mp 另存一行 mp_session.router_session_id）
对话请求 → router `/api/router/agent/chat` → agent-service
→ AgentSpecResolver.resolve(sessionId) → admin GET /api/admin/internal/agent-spec/{sessionId}
→ admin 按前缀读 session 行 → AgentSpecInfoResponse → 构建 Agent 并缓存
```

### channel session 流程

```
创建 channel → ChannelServiceImpl.generateSessionId() 产出 chn-xxx，写入 channel 行的 session_id
channel 消息 → router → agent-service → admin GET /api/admin/internal/agent-spec/{sessionId}
→ admin 按前缀读 channel 行，再读 agent 行 → AgentSpecInfoResponse → 构建 Agent 并缓存
（session 表里没有这一行）
```

### task session 流程

```
scheduler 触发（cron job 或一次性 job）→ insertRunningLog 用 TaskSessionId.of(task.id, task.agentId)
生成 "task-{taskId}-{agentId}-{uuid}"，写进 agent_task_log.session_id
→ RouterClient.chat(sessionId, task.prompt) → router → agent-service
→ admin GET /api/admin/internal/agent-spec/{sessionId}
→ TaskSessionId.parse 取 agentId → 读 agent 行（permissionMode 固定 BYPASS）→ AgentSpecInfoResponse
→ 执行结束 → RouterClient.clearSession(sessionId) → router DELETE /api/router/agent/session/{sessionId}
→ 清除 agent-service 上的 agent 缓存与沙箱；agent_task_log 行落响应、状态与耗时
（session 表里同样没有这一行）
```

## 四、实施顺序

分类判据穿过各模块的顺序如下，每一环只依赖前一环：

1. `harnax-common`: `TaskSessionId` 定义 task id 的语法，scheduler 与 admin 共用。
2. `harnax-entity`: `AgentSpecInfoResponse`/`TeamSpecInfoResponse`/`ChannelSessionOwner` 三个 DTO，加上按完整 sessionId 读行的 mapper。
3. `harnax-admin`: 生成 `web-`/`mp-`/`chn-` 三种 id 并持有其数据来源；`InternalApiController.getAgentSpec` 是前缀分派的唯一落点。
4. `harnax-session-router`: 按前缀执行权限规则（`PrivilegedSessionPrefixes`），并把清理请求转给 agent-service。
5. `harnax-agent-service`: 不读会话业务表，一律经 `AdminApiClient` 向 admin 取配置。
6. `harnax-scheduler`: 铸造 `task-` id、发起对话、结束时请求 router 清理运行时。

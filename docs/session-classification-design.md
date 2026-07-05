# Session 分类重设计方案

## 一、Session 分类总览

| 类型 | sessionId 格式 | 存储位置 | 生命周期 |
|------|---------------|---------|---------|
| channel | `chn-{uuid}` | channel_session 表 | 随 channel 创建，长期有效 |
| web | `web-{uuid}` | session 表 | 用户创建，长期有效 |
| mp | `mp-{uuid}` | session 表 + mp_session 表 | 用户创建，长期有效 |
| task | `task-{taskId}-{uuid}` | 无实体 | 任务开始创建，任务结束销毁 |

## 二、各模块改动

### Task 1: 新增 TaskAgentSpecResponse DTO（harnax-entity）

在 `harnax-entity` 中新增精简的 AgentSpec DTO，供 admin 内部 API 返回给 agent-service。

**新建文件**: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/TaskAgentSpecResponse.kt`

```kotlin
data class TaskAgentSpecResponse(
    val agentId: Long,
    val agentName: String,
    val description: String,
    val systemPrompt: String,
    val modelId: Long,
    val mcpList: String,       // JSON 格式
    val skillList: String,     // 逗号分隔 ID
    val enableThink: Int = 0,
    val enableSearch: Int = 0,
    val enablePlan: Int = 0,
)
```

### Task 2: Session ID 前缀生成改造（harnax-admin）

**2a. SessionServiceImpl** — `harnax-admin/.../service/impl/SessionServiceImpl.kt`
- `createSession()`: `sessionId = "web-${UUID.randomUUID()}"` （原来是纯 UUID）
- 移除 `createForAgent()` 方法（task session 不再需要在 admin 创建 DB 记录）

**2b. MpSessionService** — `harnax-admin/.../service/mp/MpSessionService.kt`
- `createSession()`: `routerSessionId = "mp-${UUID.randomUUID()}"` （原来是纯 UUID）

**2c. ChannelServiceImpl** — `harnax-admin/.../service/impl/ChannelServiceImpl.kt`
- `generateSessionId()`: 返回 `"chn-${UUID.randomUUID()}"` （原来是纯 UUID）

### Task 3: Admin 内部 API 改造（harnax-admin）

**修改文件**: `harnax-admin/.../controller/InternalApiController.kt`

3a. **新增** `GET /api/admin/internal/agent-tasks/{taskId}/spec` — 返回 TaskAgentSpecResponse
- 根据 taskId 查 AgentTask 表获取 agentId
- 再根据 agentId 查 Agent 表获取完整 AgentSpec 信息
- 返回 TaskAgentSpecResponse

3b. **移除** `POST /sessions/create-for-agent` 端点（不再需要为 task 创建临时 session）

3c. **移除** `DELETE /sessions/{id}` 端点（不再需要删除临时 session，task session 无 DB 记录）

**修改文件**: `harnax-admin/.../service/SessionService.kt`
- 移除 `createForAgent()` 方法签名

**修改文件**: `harnax-admin/.../service/impl/SessionServiceImpl.kt`
- 移除 `createForAgent()` 实现

### Task 4: SessionMapper 新增按 sessionId 前缀查询方法（harnax-entity）

**修改文件**: `harnax-entity/.../mapper/SessionMapper.kt`
- 新增 `selectBySessionIdPrefix` 等方法（如需要）

**修改文件**: `harnax-entity/.../mapper/ChannelSessionMapper.kt`
- 确认 `selectBySessionId` 方法已存在（已有）

### Task 5: agent-service Session 解析路由（核心改动）

**修改文件**: `harnax-agent/.../runner/impl/DefaultAgentRunner.kt`

在 `getOrCreateAgent()` 方法中，根据 sessionId 前缀分流：

```kotlin
private fun getOrCreateAgent(sessionId: String, userIdentifier: UserIdentifier): HarnessAgentWrapper = 
    agentCache.get(sessionId) { sid ->
        when {
            sid.startsWith("web-") || sid.startsWith("mp-") -> buildAgentFromSession(sid, userIdentifier)
            sid.startsWith("chn-") -> buildAgentFromChannelSession(sid, userIdentifier)
            sid.startsWith("task-") -> buildAgentFromTaskSpec(sid, userIdentifier)
            else -> buildAgentFromSession(sid, userIdentifier) // 向后兼容旧纯 UUID
        }
    }
```

- `buildAgentFromSession`: 现有逻辑，查 session 表
- `buildAgentFromChannelSession`: 查 channel_session 表获取 agentId 等配置（或复用 session 表，因为 channel_session 表结构不含完整 agent spec，需关联 agent 表）
- `buildAgentFromTaskSpec`: 解析 taskId，调用 admin 内部 API 获取 TaskAgentSpecResponse，构建 Agent（stateless=true）

**修改文件**: `harnax-agent/.../chat/ChatService.kt`
- 同样的前缀路由逻辑应用到 `getOrCreateAgent()` 和 `getSessionConfig()`

### Task 6: agent-service 新增 AdminClient（harnax-agent-service）

**新建文件**: `harnax-agent/.../client/AdminApiClient.kt`

```kotlin
@Component
class AdminApiClient(
    @Value("\${admin.service.url:http://localhost:8080}") private val adminUrl: String,
    private val tokenProvider: InternalTokenProvider,
) {
    fun getTaskAgentSpec(taskId: Long): TaskAgentSpecResponse {
        // GET /api/admin/internal/agent-tasks/{taskId}/spec
        // 带 internal auth header
    }
}
```

### Task 7: agent-service ChannelSession 查询能力

需要让 agent-service 能查 channel_session 表。当前 agent-service 已有 SessionMapper（查 session 表），需要新增 ChannelSessionMapper 的依赖或使用。

**方案**: agent-service 中注入 `ChannelSessionMapper`（harnax-entity 已有此 Mapper），根据 `chn-` 前缀从 channel_session 表获取 agentId，再查 Agent 表获取完整配置。

或者更简单的方案：channel session 创建时也在 session 表中插入一条记录（sessionId = `chn-{uuid}`），这样 web/mp/chn 都查 session 表即可，只是前缀不同。但这会增加冗余。

**推荐**: 复用现有 channel_session 表，agent-service 新增 ChannelSessionMapper 查询能力。

### Task 8: Scheduler 模块改造（harnax-scheduler）

**修改文件**: `harnax-scheduler/.../client/AdminClient.kt`
- 移除 `createTempSession()` 和 `deleteSession()` 方法

**修改文件**: `harnax-scheduler/.../job/AgentTaskJob.kt`
- 不再调用 `adminClient.createTempSession()`
- 直接生成 sessionId: `"task-${task.id}-${UUID.randomUUID()}"`
- 直接调用 `routerClient.chat(sessionId, task.prompt)`
- 不再调用 `adminClient.deleteSession()`（无 DB 记录需删除）
- 任务结束后，调用 router 的 `clearSession` API 清除 agent-service 上的 agent 缓存和沙箱

**修改文件**: `harnax-scheduler/.../service/impl/SchedulerServiceImpl.kt`
- 同步修改手动触发逻辑（与 AgentTaskJob 一致）

### Task 9: 数据库迁移脚本（Flyway）

**新建文件**: `harnax-admin/src/main/resources/db/migration/V{N}__session_prefix_migration.sql`

```sql
-- 现有 session 数据兼容：旧数据保持纯 UUID，新数据用前缀
-- 无需修改已有数据，agent-service 做了向后兼容（无前缀走 session 表）

-- channel_session 表无需改结构，只是 sessionId 字段值从 UUID 变为 chn-{uuid}
```

实际上不需要 DDL 变更，只是数据值的变化。如果有 Flyway 版本号需要递增，可以写一个空迁移或跳过。

### Task 10: 前端适配

**修改文件**: `harnax-webui/src/pages/session/` 相关组件
- 无需特别改动，前端展示 sessionId 时前缀不影响功能
- 可选：在会话列表中显示 session 类型标签（chn/web/mp）

### Task 11: 测试

- **单元测试**: 验证 sessionId 前缀生成逻辑（chn-/web-/mp-/task-）
- **单元测试**: 验证 DefaultAgentRunner 的前缀路由分支
- **单元测试**: 验证 AdminApiClient 获取 TaskAgentSpec
- **单元测试**: 验证 Scheduler 直接生成 task sessionId、不再创建/删除 session
- **集成测试**: 更新现有的 SessionMapperTest、ChannelServiceImpl 测试

## 三、数据流示意

### web/mp session 流程（不变）
```
前端创建 session → session 表 (web-xxx / mp-xxx)
对话请求 → router → agent-service → 查 session 表 → 构建 Agent
```

### channel session 流程
```
创建 channel → channel_session 表 (chn-xxx)
channel 消息 → router → agent-service → 识别 chn- 前缀 → 查 channel_session 表 → 构建 Agent
```

### task session 流程（全新）
```
scheduler 定时触发 → 生成 sessionId "task-{taskId}-{uuid}"
→ router → agent-service → 识别 task- 前缀
→ 解析 taskId → 调用 admin GET /api/admin/internal/agent-tasks/{taskId}/spec
→ 获取 TaskAgentSpecResponse → 构建 Agent (stateless=true)
→ 执行完毕 → router clearSession → 清除 agent 缓存和沙箱
→ 日志记录到 agent_task_log 表
```

## 四、实施顺序

1. Task 1: 新建 TaskAgentSpecResponse DTO
2. Task 2: 各 ServiceImpl 的 sessionId 前缀生成
3. Task 3: Admin 内部 API（新增 agent-tasks spec 端点 + 移除 createForAgent）
4. Task 6: agent-service AdminApiClient
5. Task 7: agent-service ChannelSessionMapper 查询能力
6. Task 5: agent-service 前缀路由逻辑（DefaultAgentRunner + ChatService）
7. Task 8: Scheduler 模块改造
8. Task 11: 测试
9. Task 9/10: 数据库迁移 + 前端适配（如需要）

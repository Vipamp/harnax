# Harnax Mobile App — 完整产品需求与技术设计文档

> 版本：v2.0.0
> 日期：2026-06-20
> 状态：草案
> 定位：面向企业内部员工的 AI 智能体对话 App，全新设计

---

## 一、产品概述

### 1.1 产品定位

Harnax Chat App 是面向企业内部员工的 AI 智能体对话客户端。员工通过用户名密码登录，浏览管理员分配的可用 Agent 列表，选择 Agent 创建会话，进行实时流式对话。

与 harnax-webui（管理后台）的分工：

| 系统 | 用户 | 核心功能 |
|------|------|---------|
| harnax-webui | 管理员 | Agent 创建/配置、模型管理、用户管理、监控 |
| harnax-app | 普通员工 | Agent 选择、对话、会话管理 |

### 1.2 目标用户

企业内部员工，由管理员在后台创建账号并分配可用 Agent。

### 1.3 核心用户流程

```
服务器配置 → 登录 → Agent 列表 → 选择 Agent → 创建会话 → 流式对话 → 管理会话
```

---

## 二、功能需求

### 2.1 功能全景

```
Harnax Chat App
├── P0 认证模块
│   ├── 服务器地址配置（首次使用）
│   ├── 用户名/密码登录
│   ├── 自动登出（Token 过期）
│   └── 退出登录
├── P0 Agent 浏览
│   ├── Agent 列表（卡片式）
│   ├── Agent 详情（描述、模型、MCP、技能）
│   └── Agent 搜索
├── P0 会话管理
│   ├── 创建会话（选择 Agent）
│   ├── 会话列表（按时间排序）
│   ├── 切换会话
│   ├── 重命名会话
│   └── 删除会话
├── P0 对话交互
│   ├── 发送文本消息
│   ├── 流式接收响应（SSE）
│   ├── 中断生成
│   ├── 工具确认/拒绝
│   └── 消息重试
├── P0 消息展示
│   ├── 思考过程（可折叠）
│   ├── Markdown 文本回复
│   ├── 代码块（带复制）
│   ├── 工具调用卡片
│   ├── 工具结果
│   ├── 错误提示
│   └── Token 用量统计
├── P1 用户中心
│   ├── 个人资料查看
│   └── 修改密码
├── P1 体验优化
│   ├── 深色模式
│   ├── 中英文切换
│   └── 对话历史本地缓存
└── P2 高级功能（二期）
    ├── 图片/文件上传
    ├── 对话导出
    ├── 推送通知
    └── 离线查看历史
```

### 2.2 功能详细说明

#### F1: 服务器配置

首次打开 App，用户需输入企业服务器地址：

- 输入统一的服务器 URL（如 `https://harnax.example.com`）
- App 自动发现 Admin API 和 Router 地址（通过 `/api/health` 探活）
- 如果自动发现失败，支持手动分别配置 Admin 和 Router 地址
- 地址保存在本地存储，下次打开自动加载

#### F2: 登录认证

- 输入用户名 + 密码
- 密码 SHA-256 哈希后传输（后端存储 BCrypt(SHA-256(plain))）
- 登录成功返回：
  - `accessToken`：JWT Token，用于 Admin API 调用
  - `routerApiKey`：自动生成的临时 API Key，用于 Router SSE 流式通信
  - `routerUrl`：Router 服务地址
  - `userInfo`：用户基本信息（用户名、昵称）
- Token 过期自动跳回登录页
- 退出登录清除所有本地缓存

#### F3: Agent 列表

从后端获取当前用户可用的 Agent 列表：

- 卡片式展示：Agent 名称、描述、关联模型名称
- 支持按名称搜索过滤
- 点击卡片进入 Agent 详情
- Agent 详情展示：描述、系统提示词（脱敏）、模型信息、MCP 工具列表、技能列表
- "开始对话"按钮：创建新会话并进入对话

#### F4: 会话管理

**创建会话：**
1. 用户从 Agent 列表选择一个 Agent
2. 可选输入会话名称（默认使用 Agent 名称 + 时间戳）
3. 后端创建 Session（绑定 Agent 配置 + 生成 routerSessionId）
4. 自动进入对话界面

**会话列表：**
- 左侧抽屉/边栏展示
- 按最后活跃时间排序
- 显示会话名称和最后一条消息摘要
- 支持删除（二次确认）
- 支持重命名

#### F5: 对话交互

与现有实现一致（已完成的组件复用）：
- 发送文本消息 → SSE 流式接收
- 思考过程折叠展示
- Markdown 渲染 + 代码高亮
- 工具调用卡片 + 确认交互
- 中断生成 + Token 用量

---

## 三、系统架构

### 3.1 整体架构

```
┌───────────────────────────────────────────────────────┐
│                  Harnax Chat App                       │
│                 (UniApp X 多端)                        │
│                                                        │
│  ┌─────────┐ ┌────────┐ ┌─────────┐ ┌─────────────┐  │
│  │ 登录页   │ │Agent列表│ │ 会话列表 │ │ 对话界面     │  │
│  └────┬────┘ └───┬────┘ └────┬────┘ └──────┬──────┘  │
│       │          │           │              │          │
│   JWT Auth    JWT Auth   JWT Auth     X-Api-Key       │
└───────┼──────────┼───────────┼──────────────┼──────────┘
        │          │           │              │
   ┌────▼──────────▼───────────▼──┐    ┌──────▼──────────┐
   │   harnax-admin :8080         │    │ Session Router   │
   │   /api/mp/** (MP 业务 API)   │    │     :8081        │
   │                              │    │ /api/router/     │
   │  登录/登出                    │    │   agent/ (SSE)   │
   │  Agent 列表                   │    │                  │
   │  会话 CRUD                    │    └────────┬────────┘
   │  聊天记录持久化               │             │
   │  用户资料                     │      JWT (内部)
   │                              │             │
   └──────────────────────────────┘    ┌────────▼────────┐
                                       │ Agent Service    │
                                       │   :8082          │
                                       │ AI Agent 执行    │
                                       └─────────────────┘
```

### 3.2 认证流程

```
App                         Admin                        Router
 │                           │                             │
 │ 1. POST /api/mp/auth/login│                             │
 │    {username, password}   │                             │
 │──────────────────────────>│                             │
 │                           │                             │
 │ 2. 验证用户 + 生成         │                             │
 │    routerApiKey            │                             │
 │                           │                             │
 │ 3. 返回 {accessToken,     │                             │
 │    routerApiKey, routerUrl}│                             │
 │<──────────────────────────│                             │
 │                           │                             │
 │ 4. Admin API: Bearer <JWT>│                             │
 │──────────────────────────>│                             │
 │                           │                             │
 │ 5. Router SSE: X-Api-Key: <routerApiKey>               │
 │───────────────────────────────────────────────────────>│
```

**routerApiKey 生成逻辑：**
- 登录时，Admin 在 `api_key` 表自动创建一条临时 API Key
- 关联当前用户的 tenantId
- 有效期与 JWT Token 一致（默认 24 小时）
- 登出时自动失效
- App 不需要用户手动输入 API Key

### 3.3 Agent 绑定流程

```
App                          Admin                         Router           Agent Service
 │                            │                              │                    │
 │ 1. GET /api/mp/agents      │                              │                    │
 │───────────────────────────>│                              │                    │
 │ 2. 返回 Agent 列表          │                              │                    │
 │<───────────────────────────│                              │                    │
 │                            │                              │                    │
 │ 3. POST /api/mp/sessions   │                              │                    │
 │    {agentId, sessionName}  │                              │                    │
 │───────────────────────────>│                              │                    │
 │                            │ 4. 创建 Session 记录          │                    │
 │                            │    (复制 Agent 配置)          │                    │
 │                            │ 5. 创建 MpSession 记录        │                    │
 │                            │    (关联 userId + sessionId)  │                    │
 │ 6. 返回 {sessionId,        │                              │                    │
 │    routerSessionId}        │                              │                    │
 │<───────────────────────────│                              │                    │
 │                            │                              │                    │
 │ 7. POST /chat/stream       │                              │                    │
 │    {sessionId, message}    │                              │                    │
 │    (X-Api-Key)             │                              │                    │
 │──────────────────────────────────────────────────────────>│                    │
 │                            │                              │ 8. 路由到实例        │
 │                            │                              │───────────────────>│
 │                            │                              │                    │ 9. sessionId
 │                            │                              │                    │    → 查 Session 表
 │                            │                              │                    │    → 获取 agentId
 │                            │                              │                    │    → 创建 Agent
 │                            │                              │                    │
 │ 10. SSE 事件流              │                              │                    │
 │<───────────────────────────────────────────────────────────────────────────────│
```

---

## 四、后端设计

### 4.1 已有后端能力

| 模块 | 端点 | 状态 | 说明 |
|------|------|------|------|
| MpAuthController | `POST /api/mp/auth/login` | ✅ 已有 | 用户名密码登录（无验证码） |
| MpAuthController | `POST /api/mp/auth/logout` | ✅ 已有 | 退出登录 |
| MpSessionController | `GET /api/mp/sessions` | ✅ 已有 | 会话列表 |
| MpSessionController | `POST /api/mp/sessions` | ⚠️ 需改造 | 创建会话（当前不创建 Session 记录） |
| MpSessionController | `PUT /api/mp/sessions/{id}` | ✅ 已有 | 重命名会话 |
| MpSessionController | `DELETE /api/mp/sessions/{id}` | ✅ 已有 | 删除会话 |
| MpChatController | `GET /api/mp/chat/history/{sessionId}` | ✅ 已有 | 聊天记录 |
| MpChatController | `POST /api/mp/chat/history/{sessionId}` | ✅ 已有 | 保存聊天记录 |
| MpChatController | `DELETE /api/mp/chat/history/{sessionId}` | ✅ 已有 | 删除聊天记录 |

### 4.2 需新增/改造的后端 API

#### 4.2.1 新增：Agent 列表（P0）

```
GET /api/mp/agents
Authorization: Bearer <JWT>

Response: {
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "代码助手",
      "description": "帮助你编写、调试和优化代码",
      "modelName": "GPT-4o",
      "icon": "code",
      "status": 1,
      "sessionCount": 3
    }
  ]
}
```

**实现：**
- 新增 `MpAgentController` + `MpAgentService`
- 查询 `agent` 表中 `status=1`（启用）的 Agent
- 一期先返回所有启用的 Agent，二期可按用户权限过滤
- 返回精简字段（不需要 systemPrompt 等内部配置）

#### 4.2.2 新增：Agent 详情（P0）

```
GET /api/mp/agents/{agentId}
Authorization: Bearer <JWT>

Response: {
  "code": 200,
  "data": {
    "id": 1,
    "name": "代码助手",
    "description": "帮助你编写、调试和优化代码的 AI 助手",
    "modelName": "GPT-4o",
    "modelProvider": "OpenAI",
    "mcpList": [{"id": 1, "name": "GitHub MCP"}],
    "skillList": [{"id": 1, "name": "代码审查"}],
    "enableThink": true,
    "enableSearch": false,
    "enablePlan": true
  }
}
```

#### 4.2.3 改造：登录接口返回 routerApiKey（P0）

```kotlin
fun mobileLogin(username: String, password: String): MpLoginResponse {
    val user = validateUser(username, password)
    val jwt = generateJwt(user)
    val apiKey = generateRouterApiKey(user)  // 【新增】
    val routerUrl = configService.getRouterUrl()  // 【新增】

    return MpLoginResponse(
        accessToken = jwt,
        routerApiKey = apiKey.keyValue,
        routerUrl = routerUrl,
        expiresIn = jwtExpireSeconds,
        userInfo = UserInfo(user.id, user.username, user.nickname)
    )
}
```

#### 4.2.4 改造：创建会话同时创建 Session 记录（P0）

```kotlin
fun createSession(userId: Long, request: MpCreateSessionRequest): MpSessionResponse {
    val agent = agentMapper.selectById(request.agentId)
        ?: throw BizException("Agent not found")

    val sessionId = UUID.randomUUID().toString()

    // 创建 Session 记录（供 Agent Service 使用）
    val session = Session().apply {
        this.sessionId = sessionId
        this.agentId = agent.id
        name = request.sessionName
        systemPrompt = agent.systemPrompt
        modelId = agent.modelId
        mcpList = agent.mcpList
        skillList = agent.skillList
        status = 1
        owner = userId.toString()
    }
    sessionMapper.insert(session)

    // 创建 MpSession 记录（关联 userId）
    val mpSession = MpSession().apply {
        this.userId = userId
        this.sessionName = request.sessionName
        this.routerSessionId = sessionId
        this.agentId = agent.id  // 【新增】
        status = 1
    }
    mpSessionMapper.insert(mpSession)

    return toResponse(mpSession, agent)
}
```

#### 4.2.5 数据库变更

```sql
ALTER TABLE mp_session ADD COLUMN agent_id BIGINT NOT NULL DEFAULT 0
  AFTER router_session_id;
ALTER TABLE mp_session ADD INDEX idx_mp_session_agent_id (agent_id);
```

#### 4.2.6 新增：用户资料（P1）

```
GET /api/mp/user/profile
PUT /api/mp/user/password  {oldPassword, newPassword}
```

### 4.3 新增/改造 DTO

```kotlin
data class MpLoginResponse(
    val accessToken: String,
    val routerApiKey: String,
    val routerUrl: String,
    val expiresIn: Long,
    val userInfo: UserInfo,
)

data class UserInfo(val userId: Long, val username: String, val nickname: String?)

data class MpAgentResponse(
    val id: Long, val name: String, val description: String,
    val modelName: String, val icon: String, val status: Int, val sessionCount: Int,
)

data class MpAgentDetailResponse(
    val id: Long, val name: String, val description: String,
    val modelName: String, val modelProvider: String,
    val mcpList: List<McpInfo>, val skillList: List<SkillInfo>,
    val enableThink: Boolean, val enableSearch: Boolean, val enablePlan: Boolean,
)

data class MpCreateSessionRequest(
    @field:NotBlank val sessionName: String,
    @field:NotNull val agentId: Long,
)

data class MpSessionResponse(
    val id: Long, val sessionName: String, val routerSessionId: String,
    val agentId: Long, val agentName: String,
    val status: Int, val messageCount: Int, val lastMessage: String?,
    val createTime: LocalDateTime, val updateTime: LocalDateTime,
)
```

---

## 五、前端设计

### 5.1 页面结构

```
pages/
├── setup/index.vue          # 服务器配置页（首次使用）
├── login/index.vue          # 登录页
├── agents/index.vue         # Agent 列表页
├── agents/detail.vue        # Agent 详情页
├── chat/index.vue           # 对话主页
└── profile/index.vue        # 个人中心页
```

**页面导航流程：**
```
首次使用: setup → login → agents
已登录:   → agents → chat
对话中:   chat ↔ agents (新建会话时选择 Agent)
          chat → profile (个人中心)
Token 过期: → login
```

### 5.2 页面详情

| 页面 | 核心功能 |
|------|---------|
| 服务器配置 | 输入 URL、自动探活、高级手动配置 |
| 登录 | 用户名密码、存储 token/routerApiKey/userInfo |
| Agent 列表 | 卡片列表、搜索、会话数、点击进详情 |
| Agent 详情 | 完整信息、开始对话按钮 |
| 对话主页 | 复用现有组件、顶栏返回、侧栏显示 Agent 名 |
| 个人中心 | 用户信息、修改密码、主题/语言、退出 |

### 5.3 组件/Store/API 改造

- 新增组件: AgentCard, AgentDetailPanel, LoginForm, ProfilePanel
- Store 改造: useConnectionStore 增加 routerApiKey/userInfo, useSessionStore 增加 agentId
- API 新增: mpListAgents, mpGetAgent, mpGetProfile, mpChangePassword

---

## 六、实现计划

### Phase 1: 后端 API（3 天）

| # | 任务 | 预估 |
|---|------|------|
| 1.1 | MpAgentController + Service | 0.5d |
| 1.2 | 登录返回 routerApiKey + routerUrl | 0.5d |
| 1.3 | 创建会话同时创建 Session 记录 | 0.5d |
| 1.4 | mp_session 表增加 agent_id + DTO | 0.5h |
| 1.5 | 删除会话级联清理 | 0.5d |
| 1.6 | 用户资料 + 修改密码 | 0.5d |

### Phase 2: 前端核心流程（5.5 天）

| # | 任务 | 预估 |
|---|------|------|
| 2.1 | 服务器配置页 | 0.5d |
| 2.2 | 登录页 | 0.5d |
| 2.3 | Agent 列表页 | 1d |
| 2.4 | Agent 详情页 | 0.5d |
| 2.5 | Store 改造 | 1d |
| 2.6 | API + 类型定义 | 0.5d |
| 2.7 | 对话页改造 | 0.5d |
| 2.8 | 个人中心页 | 0.5d |
| 2.9 | 路由 + i18n | 0.5d |

### Phase 3: 体验优化（3 天）

### 总计：约 11.5 个工作日

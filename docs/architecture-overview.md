# Harnax 完整架构概览

## 📊 系统架构图（含 Session Router）

```mermaid
graph TB
    subgraph "前端层 Frontend"
        WebUI[harnax-webui<br/>React + Ant Design Pro<br/>管理后台界面]
    end

    subgraph "后端服务层 Backend Services"
        Admin[harnax-admin<br/>Spring Boot<br/>管理后台服务<br/>Port: 8080]
        SessionRouter[harnax-session-router<br/>Spring Boot<br/>会话路由服务<br/>Port: 8081]
        AgentService1[harnax-agent-service<br/>实例 1<br/>Port: 8082]
        AgentService2[harnax-agent-service<br/>实例 2<br/>Port: 8083]
        AgentServiceN[harnax-agent-service<br/>实例 N<br/>...]
    end

    subgraph "通道层 Channel Layer"
        ChannelSDK[harnax-channel-sdk<br/>通道SDK抽象层]
        ChannelFeishu[harnax-channel-feishu<br/>飞书通道]
        ChannelWechat[harnax-channel-wechat<br/>微信通道]
    end

    subgraph "Agent核心层 Agent Core"
        AgentCore1[Agent Core<br/>实例 1]
        AgentCore2[Agent Core<br/>实例 2]
        AgentCoreN[Agent Core<br/>实例 N]
    end

    subgraph "共享模块 Shared Modules"
        Entity[harnax-entity<br/>实体类 + Mapper<br/>MyBatis XML]
        Common[harnax-common<br/>通用错误码<br/>异常定义]
    end

    subgraph "数据存储层 Data Storage"
        MySQL[(MySQL<br/>业务数据<br/>会话记录)]
        MysqlSession1[(MySQL<br/>Session 记录<br/>实例 1)]
        MysqlSession2[(MySQL<br/>Session 记录<br/>实例 2)]
        MysqlSessionN[(MySQL<br/>Session 记录<br/>实例 N)]
        SessionMapping[(MySQL<br/>session_mapping<br/>agent_instance)]
    end

    subgraph "外部服务 External Services"
        AIModels[AI模型服务商<br/>DashScope/OpenAI/Ollama]
        MCP[MCP Servers<br/>Model Context Protocol]
        FeishuAPI[飞书 Open API]
        WechatAPI[微信机器人API]
    end

    WebUI -->|HTTP/REST| Admin
    WebUI -->|SSE 流式| SessionRouter
    SessionRouter -->|SSE 代理| AgentService1
    SessionRouter -->|SSE 代理| AgentService2
    SessionRouter -->|SSE 代理| AgentServiceN
    
    Admin -->|依赖| Entity
    Admin -->|依赖| Common
    Admin -->|依赖| AgentCore1
    Admin -->|依赖| ChannelSDK
    
    SessionRouter -->|依赖| Entity
    SessionRouter -->|依赖| Common
    SessionRouter -->|路由管理| SessionMapping
    
    AgentService1 -->|依赖| Entity
    AgentService1 -->|依赖| Common
    AgentService1 -->|依赖| AgentCore1
    AgentService1 -->|心跳| SessionRouter
    
    AgentService2 -->|依赖| Entity
    AgentService2 -->|依赖| Common
    AgentService2 -->|依赖| AgentCore2
    AgentService2 -->|心跳| SessionRouter
    
    AgentServiceN -->|依赖| Entity
    AgentServiceN -->|依赖| Common
    AgentServiceN -->|依赖| AgentCoreN
    AgentServiceN -->|心跳| SessionRouter
    
    ChannelSDK --> ChannelFeishu
    ChannelSDK --> ChannelWechat
    
    ChannelFeishu -->|WebSocket/Webhook| FeishuAPI
    ChannelWechat -->|HTTP| WechatAPI
    
    ChannelSDK -->|SSE 流式| SessionRouter
    
    AgentCore1 -->|调用| AIModels
    AgentCore1 -->|集成| MCP
    AgentCore1 -->|会话存储| MysqlSession1
    
    AgentCore2 -->|调用| AIModels
    AgentCore2 -->|集成| MCP
    AgentCore2 -->|会话存储| MysqlSession2
    
    AgentCoreN -->|调用| AIModels
    AgentCoreN -->|集成| MCP
    AgentCoreN -->|会话存储| MysqlSessionN
    
    Entity -->|数据访问| MySQL
    
    Admin -->|管理通道| ChannelSDK
    Admin -->|管理实例| SessionRouter
    
    classDef frontend fill:#e1f5ff,stroke:#0288d1,stroke-width:2px
    classDef backend fill:#fff3e0,stroke:#f57c00,stroke-width:2px
    classDef router fill:#fce4ec,stroke:#c2185b,stroke-width:3px
    classDef channel fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef core fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef shared fill:#fff9c4,stroke:#fbc02d,stroke-width:2px
    classDef storage fill:#eceff1,stroke:#455a64,stroke-width:2px
    classDef external fill:#ffebee,stroke:#c62828,stroke-width:2px
    
    class WebUI frontend
    class Admin,AgentService1,AgentService2,AgentServiceN backend
    class SessionRouter router
    class ChannelSDK,ChannelFeishu,ChannelWechat channel
    class AgentCore1,AgentCore2,AgentCoreN core
    class Entity,Common shared
    class MySQL,MysqlSession1,MysqlSession2,MysqlSessionN,SessionMapping storage
    class AIModels,MCP,FeishuAPI,WechatAPI external
```

## 🔄 核心数据流

### 1. 管理后台流程
```
WebUI → HTTP/REST → harnax-admin → Entity/Mapper → MySQL
                              ↓
                         ChannelSDK（管理通道）
                              ↓
                         SessionRouter（管理实例）
```

**典型场景**: 用户在管理后台创建 Agent
1. 前端提交 Agent 配置表单
2. harnax-admin 接收请求
3. 验证权限（JWT）
4. 调用 AgentMapper 插入数据库
5. 返回创建结果

### 2. AI 对话流程 - 通过 Session Router（生产环境）
```
WebUI/Channel → SSE → Session Router (8081)
                        ↓
                  查询 Session 绑定
                        ↓
                  选择健康实例
                        ↓
                  转发到 Agent Service (8082/8083/...)
                        ↓
                  ChatService.chat()
                        ↓
                  AscopeAgentLauncher.createSingleAgent()
                        ↓
                  ReActAgent.callStream()
                        ↓
                  AgentScope → AI模型（DashScope/OpenAI）
                        ↓
                  Flux<ChatEvent> 流式返回
                        ↓
                  SSE → Session Router → WebUI/Channel 实时显示
```

**详细步骤**:
1. 用户在前端或通道输入消息
2. 请求发送到 Session Router
3. Router 查询 session_mapping 表，找到绑定的实例
4. 检查实例健康状态（心跳）
5. 转发请求到健康的 Agent Service 实例
6. Agent Service 创建 ReActAgent
7. Agent 调用 AI 模型生成响应
8. ChatEvent 通过 SSE 流式传输
9. Router 代理流式响应回客户端
10. 客户端实时显示 AI 回复

### 3. AI 对话流程 - 直连 Agent Service（开发环境）
```
WebUI → SSE → Agent Service (8082)
                  ↓
            ChatService.chat()
                  ↓
            AscopeAgentLauncher.createSingleAgent()
                  ↓
            ReActAgent.callStream()
                  ↓
            AgentScope → AI模型
                  ↓
            Flux<ChatEvent> 流式返回
                  ↓
            SSE → WebUI 实时显示
```

### 4. 通道消息流程
```
飞书/微信 → Channel Module → Channel SDK
                                ↓
                          创建 AgentContext
                                ↓
                          调用 Session Router
                                ↓
                    POST /api/router/agent/chat/stream
                                ↓
                          Router 路由到 Agent Service
                                ↓
                          AgentAdaptor.streamProcess()
                                ↓
                          AgentCore → AI响应
                                ↓
                          Flow<AgentStreamEvent>
                                ↓
                          通道回复用户
```

**典型场景**: 用户在飞书群 @机器人
1. 飞书推送消息到 WebSocket
2. harnax-channel-feishu 接收消息
3. 通过 Channel SDK 创建 AgentContext
4. 调用 Session Router 的流式接口
5. Router 路由到健康的 Agent Service
6. Agent 生成 AI 响应
7. 流式响应通过 Router 返回通道
8. 通过飞书 API 回复消息

### 5. 会话路由流程
```
Agent Service 启动 → 注册实例 → Session Router
                                    ↓
                              存储到 agent_instance 表
                                    ↓
                          定期发送心跳（每 10s）
                                    ↓
                          Router 更新 last_heartbeat
                                    ↓
                    收到请求时检查健康状态
                                    ↓
                    健康？ → 路由到该实例
                    不健康？ → 选择其他实例
```

### 6. 故障转移流程
```
Agent Service 实例故障
        ↓
Router 心跳检测超时（30s）
        ↓
标记实例为不健康
        ↓
查询该实例绑定的所有 Session
        ↓
重新路由到其他健康实例
        ↓
更新 session_mapping 表
        ↓
用户无感知，服务继续
```

## 🏗️ 部署架构

### 单实例部署（开发/测试）
```
┌─────────────┐
│   Web UI    │
│  (Port 3000)│
└──────┬──────┘
       │
       ├──────────────┐
       ↓              ↓
┌─────────────┐ ┌─────────────┐
│ harnax-admin│ │   Agent     │
│  (Port 8080)│ │  Service    │
└─────────────┘ │  (Port 8082)│
                └──────┬──────┘
                       │
                ┌──────┴──────┐
                │   MySQL     │
                │  (Port 3306)│
                └─────────────┘
```

### 多实例部署（生产环境）
```
                    ┌─────────────┐
                    │   Web UI    │
                    │  (Port 3000)│
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ↓            ↓            ↓
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │ harnax-admin│ │  Session    │ │   Nginx     │
    │  (Port 8080)│ │  Router     │ │  (Port 80)  │
    └─────────────┘ │  (Port 8081)│ └──────┬──────┘
                    └──────┬──────┘        │
                           │               │
              ┌────────────┼────────────┐  │
              ↓            ↓            ↓  │
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │   Agent     │ │   Agent     │ │   Agent     │
    │  Service 1  │ │  Service 2  │ │  Service N  │
    │  (Port 8082)│ │  (Port 8083)│ │  (Port ...) │
    └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
           │               │               │
           └───────────────┼───────────────┘
                           │
                    ┌──────┴──────┐
                    │   MySQL     │
                    │  (Port 3306)│
                    └─────────────┘
```

## 🎯 Session Router 核心价值

### 解决的问题

1. **负载均衡**
   - 多个 Agent Service 实例如何分配请求？
   - Router 根据 Session 绑定 + 健康检查智能路由

2. **故障转移**
   - 某个 Agent Service 实例挂了怎么办？
   - Router 自动检测并重新路由到其他实例

3. **Session 亲和性**
   - 同一个 Session 的多次请求如何保证一致性？
   - Router 维护 Session → Instance 的绑定关系

4. **水平扩展**
   - 如何支持更多并发用户？
   - 增加 Agent Service 实例，Router 自动发现

### 关键特性

- ✅ **自动注册**: Agent Service 启动时自动注册
- ✅ **心跳检测**: 定期检测实例健康状态
- ✅ **智能路由**: 基于绑定关系 + 健康检查
- ✅ **故障转移**: 自动 Failover，用户无感知
- ✅ **会话亲和**: Session 绑定到固定实例
- ✅ **水平扩展**: 支持无限实例扩展

## 📊 数据库设计

### Session Router 的存储

Router 侧只有一张表：`api_call_log`（Flyway `V1__create_session_router_tables.sql`；local 模式由
`SqliteInitConfig` 执行 `db/sqlite-init.sql` 建同一张表）。**没有** `session_mapping`、`agent_instance`
这两张表——路由状态不进关系库，而是按部署模式放在 Redis 或进程内存里：

| 模式 | 开关 | 实例注册 / 会话绑定的存放处 |
|------|------|------------------------------|
| local | `CACHE_TYPE=local` | 进程内存（`LocalInstanceRegistry` / `LocalSessionMappingService`），不共享、重启即丢 |
| cluster | `CACHE_TYPE=redis` | Redis，键布局见下表 |

两种模式的绑定生命周期相同：都用 `RedisSessionMappingService.SESSION_TTL`（24h），
同一个 session「记住」它所属实例的时间长度不会因为换个模式就变。

集群模式的 Redis 键：

| 键 | 类型 / TTL | 用途 |
|----|-----------|------|
| `router:instance:{id}` | Hash / 24h | 实例注册信息（host、port、status、lastHeartbeat…） |
| `router:instances:all` / `router:instances:healthy` | Set | 全部活跃注册 / 可接新会话的实例 |
| `router:session:{id}` | String / 24h | 会话 → 实例绑定 |
| `router:instance_sessions:{id}` | Set | 反向索引，故障转移时按它批量迁移绑定 |
| `router:circuit:{id}` | Hash | 熔断计数，只由 Lua 脚本改写 |
| `router:idempotency:{requestId}` | String / 60s | 代理请求按 requestId 去重 |
| `router:lock:session:{id}` | 10s | 会话重绑期间的跨节点互斥 |
| `router:lock:index_reconcile` | 4min | 反向索引对账（`SessionIndexReconciler`） |

### 数据流

```
Agent Service 启动
    ↓
POST /api/router/instance/register（内部 JWT，注册参数过 SSRF 校验）
    ↓
写 router:instance:{id} + 加入 router:instances:all（可接新会话则同时进 :healthy）
    ↓
定期 POST /api/router/instance/heartbeat → 刷新 lastHeartbeat 与键的 TTL
    ↓
收到聊天请求（Bearer JWT 或 X-Api-Key）
    ↓
查会话绑定：命中且该实例仍健康且不在 DRAINING → 沿用（会话亲和）
    ↓ 未命中
在健康实例里选一个负载最低的（排除熔断跳开的实例），原子写入绑定 + 反向索引
    ↓
WebClient 经该实例专属连接池转发，落 api_call_log
    ↓
HeartbeatHealthChecker 周期扫描：心跳超时的实例标记 DOWN，
把它名下的绑定整体重绑到落点最少的健康实例，并通知 admin 驱逐旧沙箱
```

## 🔐 安全考虑

### 认证机制

1. **Web UI → Admin**：JWT Token 认证
2. **Web UI → Router**：`Authorization: Bearer <jwt>` 或 `X-Api-Key` 二选一。`harnax.auth.skip-paths` 只放行监控面板的静态资源（`/ui`、`/index.html`、`/static/`、`/favicon.ico`、`/style.css`、`/app.js`），面板数据 API 仍需凭证
3. **Channel → Router**：带 `X-Api-Key`（channel 启动时向 admin 取 key）。不是「内网即可访问」——`/api/router/agent/**` 无凭证直接 401
4. **Router → Agent Service**：带内部 JWT（`InternalTokenProvider` 签发的短期 token，`Authorization: Bearer` + `X-Caller-Id`）。agent-service 的 `skip-paths` 为空，无凭证会被 `UnifiedAuthFilter` 拒掉
5. **Agent → Router（注册 / 心跳）**：只接受内部 JWT。相关控制器标了 `@InternalOnly`，外部 API Key 会被 `InternalAuthorizationInterceptor` 拒绝

> 网络隔离是纵深防御的一层，不是凭证的替代品：Router 与 Agent 之间的互信由共享 HMAC 密钥
> （`HARNAX_AUTH_SECRET`，出厂占位值必须覆盖）建立的短期 token 承载。

### 网络安全

- 生产环境所有服务在内网
- Nginx 反向代理暴露到外网
- 防火墙限制端口访问

## 🚀 性能优化

### Session Router 优化

1. **按实例隔离的连接池**: WebClient 连接池按 `host:port` 分，单个实例被打满不会占住其他实例的连接
2. **异步与流式**: servlet MVC + `suspend` 处理器，SSE 走 `Flux`；慢请求不占 servlet 线程。定时任务共用 `spring.task.scheduling` 池（4 线程），代价是任务自身不能长时间阻塞
3. **状态不进关系库**: 实例注册与会话绑定在 Redis（cluster）或内存（local），MySQL/SQLite 只承载 `api_call_log`
4. **降级快照**: Redis 不可达时，用最后一次已知的实例视图和影子绑定继续服务；这份视图会随时间作废，避免一直往早已死掉的实例转发

### Agent Service 优化

1. **实例预热**: 提前创建常用 Agent
2. **连接池**: 数据库连接池
3. **异步流式**: WebFlux 非阻塞 IO
4. **会话缓存**: 内存缓存热点 Session

## 📝 运维监控

### 关键指标

1. **Session Router**
   - 健康实例数
   - 路由请求 QPS
   - 平均响应时间
   - Failover 次数

2. **Agent Service**
   - 实例 CPU/内存使用率
   - 活跃 Session 数
   - AI 调用延迟
   - Token 消耗

3. **数据库**
   - 连接池使用率
   - 慢查询日志
   - 表空间使用

### 告警规则

- 健康实例数 < 2 → 严重告警
- 路由响应时间 > 5s → 警告
- Failover 频率 > 10/min → 警告
- 数据库连接池 > 80% → 警告

---

**文档版本**: v1.0  
**最后更新**: 2026-05-25  
**维护者**: Harnax Team

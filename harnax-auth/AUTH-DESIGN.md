# Harnax Auth - 统一认证鉴权方案

## 1. 概述

`harnax-auth` 是一个共享库模块（非独立服务），为 harnax 平台的 **channel-service**、**session-router**、**agent-service** 三个服务提供统一的认证鉴权能力。admin 模块和前端使用独立的 Spring Security 体系，不在本模块范围内。

### 设计原则

- **零侵入接入**：通过 Spring Boot AutoConfiguration 自动装配，业务服务只需引入 Maven 依赖 + 添加 YAML 配置即可启用
- **双通道认证**：内部服务间用 JWT，外部第三方用 API Key，共用一套 Filter 链路
- **声明式鉴权**：通过 `@RequireScope` 注解 + `HandlerInterceptor` 实现方法级权限控制
- **无状态**：JWT 自包含所有鉴权信息，无需会话存储

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         harnax-auth                             │
│                                                                 │
│  ┌──────────────────┐  ┌─────────────────┐  ┌───────────────┐  │
│  │UnifiedAuthFilter │  │ScopeAuthorization│  │InternalToken  │  │
│  │ (请求入口拦截)    │  │Interceptor      │  │Provider       │  │
│  │                  │  │ (方法级鉴权)      │  │ (令牌生成/验证)│  │
│  └────────┬─────────┘  └────────┬────────┘  └───────────────┘  │
│           │                     │                                │
│  ┌────────▼─────────┐  ┌───────▼──────────┐                     │
│  │InternalToken     │  │ExternalApiKey    │                     │
│  │Provider.verify() │  │Validator         │                     │
│  │(JWT 验证)        │  │(API Key 验证)    │                     │
│  └──────────────────┘  └───────┬──────────┘                     │
│                                │                                │
│                       ┌────────▼──────────┐                     │
│                       │ApiKeyStore (SPI)  │                     │
│                       │由各服务自行实现     │                     │
│                       └───────────────────┘                     │
│                                                                 │
│  ┌──────────────────┐                                           │
│  │RateLimit         │  ← 限流拦截器（仅 EXTERNAL_API 生效）       │
│  │Interceptor       │                                           │
│  └──────────────────┘                                           │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 认证双通道

### 3.1 内部通道 - 服务间 JWT

用于 channel → router → agent 之间的调用链路。

**工作方式：**

1. 调用方通过 `InternalTokenProvider` 生成 JWT，放入 `Authorization: Bearer <token>` 头
2. 接收方的 `UnifiedAuthFilter` 解析 JWT，验证签名和过期时间
3. 验证通过后构建 `AuthContext` 放入 `ThreadLocal`，供后续 `ScopeAuthorizationInterceptor` 使用

**JWT Payload 结构：**

```json
{
  "sub": "channel-0",
  "scp": "router:invoke",
  "iat": 1718611200,
  "exp": 1718611500,
  "jti": "uuid-xxx"
}
```

**令牌缓存：**

`InternalTokenProvider` 内部按 scope 缓存令牌，在令牌过期前 60 秒自动刷新。

**调用方使用方式：**

| 客户端类型 | 组件 | 说明 |
|-----------|------|------|
| WebClient | `RouterConfig.authFilter()` | ExchangeFilterFunction，自动注入 JWT 头 |
| RestTemplate | `AuthRestTemplateInterceptor` | ClientHttpRequestInterceptor，自动注入 JWT 头 |

### 3.2 外部通道 - API Key

用于第三方系统直接调用 harnax 的 HTTP 接口。

**工作方式：**

1. 客户端在请求头中携带 `X-Api-Key: hnx_xxxxxxxx`
2. `UnifiedAuthFilter` 将其交给 `ExternalApiKeyValidator` 处理
3. Validator 对原始 Key 做 SHA-256 哈希，通过 `ApiKeyStore` 查询（HTTP 调用 admin 接口）
4. 校验启用状态、过期时间，构建 `AuthContext`（callerType = EXTERNAL_API）
5. 若 API Key 配置了限流上限，`RateLimitInterceptor` 在鉴权后执行滑动窗口限流

**API Key 数据模型：**

```kotlin
data class ApiKeyInfo(
    val name: String,
    val keyHash: String,
    val scopes: Set<String>,
    val tenantId: Long?,
    val rateLimitPerMinute: Int?,
    val enabled: Boolean,
    val expiresAt: Instant?,
)
```

**API Key 存储：**

`ApiKeyStore` 是 SPI 接口，由各服务自行实现。当前 router 提供了 `RemoteApiKeyStore` 实现（HTTP 调用 admin 内部接口 + Caffeine 缓存，5 分钟过期，最多 1000 条）。

**API Key 生命周期：**

```
Admin 创建/管理 → admin MySQL 存储（唯一数据源）
                        ↓
外部请求 X-Api-Key → router RemoteApiKeyStore → HTTP POST /api/internal/api-keys/validate
                                                  → admin MySQL 查询 → 返回 ApiKeyInfo
```

## 4. 鉴权 - Scope 权限控制

### 4.1 AuthContext

认证通过后，`UnifiedAuthFilter` 将以下信息存入 ThreadLocal：

```kotlin
data class AuthContext(
    val callerId: String,
    val scopes: Set<String>,
    val callerType: CallerType,        // INTERNAL_SERVICE 或 EXTERNAL_API
    val tenantId: Long? = null,
    val rateLimitPerMinute: Int? = null, // 每分钟限流次数（仅 EXTERNAL_API）
)
```

### 4.2 @RequireScope 注解

```kotlin
// 方法级
@RequireScope("router:invoke")
@PostMapping("/agent/chat")
fun proxyChat(...)

// 类级 + internalOnly
@RequireScope("agent:invoke", internalOnly = true)
class AgentController
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `value` | `vararg String` | 所需的 scope，满足任一一个即可（OR 关系） |
| `internalOnly` | `Boolean` | 为 true 时，仅允许 `INTERNAL_SERVICE` 类型访问 |

### 4.3 ScopeAuthorizationInterceptor

在 Controller 方法执行前校验 `@RequireScope`：

1. 从 `AuthContextHolder` 获取 `AuthContext`
2. 若为 null → 401 Unauthorized
3. 若 `internalOnly = true` 且 `callerType != INTERNAL_SERVICE` → 403 Forbidden
4. 若调用方的 scopes 不包含任何要求的 scope → 403 Forbidden

### 4.4 限流 - RateLimitInterceptor

在 `ScopeAuthorizationInterceptor` 之后执行，仅对 `callerType = EXTERNAL_API` 且 `rateLimitPerMinute != null` 的请求生效。

```kotlin
interface RateLimitChecker {
    fun tryAcquire(key: String, limitPerMinute: Int): Boolean
}
```

router 模块提供 `RateLimiter` 实现（ConcurrentHashMap + 滑动窗口，每 60 秒自动清理）。

## 5. Scope 权限矩阵

| Scope | 持有方 | 可访问端点 | 限制 |
|-------|--------|-----------|------|
| `router:invoke` | channel-service、外部 API Key | Router: `/api/router/agent/*`（代理转发） | 无 |
| `router:register` | agent-service | Router: `/api/router/instance/*`（注册/心跳/列表） | internalOnly |
| `admin:apikey` | router（内部调用 admin） | Admin: `/api/internal/api-keys/validate` | internalOnly |
| `admin:session` | router（内部调用 admin） | Admin: `/api/internal/sessions/{id}/info` | internalOnly |
| `agent:invoke` | router | Agent: `/api/agent/*`（推理/对话） | internalOnly |

## 6. 请求认证鉴权完整流程

### 6.1 场景一：Channel（飞书）调用完整链路

```
飞书用户发消息 → 飞书WebSocket推给channel-service → channel-service调router → router调agent-service
```

#### 第一段：飞书 → channel-service

飞书平台通过 WebSocket 推送消息，使用飞书自身的验签机制，与 harnax-auth 无关。

#### 第二段：channel-service → router

**发送方：** WebClient 注册 `authFilter("router:invoke")`，自动注入：
```
Authorization: Bearer <JWT>      ← JWT: sub="channel-0", scp="router:invoke"
X-Caller-Id: channel-0
```

**接收方（router）逐层校验：**

```
① UnifiedAuthFilter
   → 读取 Authorization 头，提取 JWT

② InternalTokenProvider.verifyToken()
   → 验证 HMAC 签名 + 过期时间
   → 构建 AuthContext(callerId="channel-0", scopes={"router:invoke"}, INTERNAL_SERVICE)

③ AuthContextHolder.set(context)

④ ScopeAuthorizationInterceptor
   → @RequireScope("router:invoke") → 放行 ✅

⑤ RateLimitInterceptor
   → callerType=INTERNAL_SERVICE → 跳过

⑥ SessionRouterController.proxyChat() 执行
```

#### 第三段：router → agent-service

WebClient 注册 `authFilter("agent:invoke")`，注入 JWT: sub="router-0", scp="agent:invoke"。
agent-service 端 `@RequireScope("agent:invoke", internalOnly = true)` 校验通过。

### 6.2 场景二：外部 HTTP 直接调用 Router

```bash
curl -X POST http://router:8081/api/router/agent/chat/stream \
  -H "X-Api-Key: hnx_abc123def456xyz" \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "sess-001", "message": "你好"}'
```

```
① UnifiedAuthFilter
   → 无 Authorization 头，读取 X-Api-Key

② ExternalApiKeyValidator.validate()
   → SHA-256("hnx_abc123def456xyz") → "a1b2c3d4..."
   → RemoteApiKeyStore.findByKeyHash("a1b2c3d4...")
     → Caffeine 缓存查找（5 分钟，1000 条）
     → 未命中: HTTP POST http://admin:8080/api/internal/api-keys/validate
              Header: Authorization: Bearer <JWT> (scope: admin:apikey)
              Body: { "keyHash": "a1b2c3d4..." }
              → admin 查询 harnax.api_key 表 → 返回 ApiKeyInfo
   → 校验 enabled / expiresAt
   → 构建 AuthContext(callerId="third-party-app", scopes={"router:invoke"},
                      EXTERNAL_API, tenantId=1001, rateLimitPerMinute=60)

③ ScopeAuthorizationInterceptor → 放行 ✅

④ RateLimitInterceptor
   → RateLimitChecker.tryAcquire("third-party-app", 60)
   → 滑动窗口检查 → 放行 ✅ 或 429

⑤ SessionRouterController.proxyChat() 执行
```

#### 外部 API Key 被拒绝的场景

| 场景 | 拒绝阶段 | HTTP 状态 |
|------|---------|----------|
| Key 不存在 | ExternalApiKeyValidator | 401 |
| Key 被禁用 | ExternalApiKeyValidator | 401 |
| Key 已过期 | ExternalApiKeyValidator | 401 |
| 访问 internalOnly 接口 | ScopeAuthorizationInterceptor | 403 |
| Scope 不匹配 | ScopeAuthorizationInterceptor | 403 |
| 超过限流上限 | RateLimitInterceptor | 429 |

### 6.3 场景三：Agent-service 注册/心跳

RestTemplate 添加 `AuthRestTemplateInterceptor(tokenProvider, "router:register")`，自动注入 JWT。
Router 端 `@RequireScope("router:register", internalOnly = true)` 校验通过。

### 6.4 场景四：Router 调用 Admin 内部接口

| 场景 | 接口 | Scope |
|------|------|-------|
| API Key 校验 | POST /api/internal/api-keys/validate | admin:apikey |
| Session 信息查询 | GET /api/internal/sessions/{sessionId}/info | admin:session |

请求自动注入 JWT，Admin 端由 harnax-auth 的 Filter + Interceptor 校验。

## 7. 信任模型

### 内部 JWT 信任链

```
channel-service ─┐
session-router  ─┼── 共用同一个环境变量 HARNAX_AUTH_SECRET
agent-service   ─┤
admin           ─┘
```

- **没有服务注册中心**——服务身份由 `service-id` 自声明
- **没有证书分发**——所有服务部署时注入同一个密钥
- **没有动态授权**——scope 直接写死在每个服务的客户端代码里

### 外部 API Key 信任链

```
Admin 后台（唯一数据源，CRUD 管理）
    ↓ HTTP 内部接口调用（JWT 认证）
Router RemoteApiKeyStore（Caffeine 缓存，5 分钟）
    ↓ 请求时校验
外部客户端（持有明文 Key）
```

- API Key 明文只在创建时展示一次，数据库只存 SHA-256 哈希
- 管理员可随时禁用/删除单个 Key
- Router 不直接连接 admin 数据库

## 8. 服务间调用链路

```
┌──────────────┐         ┌──────────────────┐         ┌──────────────────┐
│   Channel     │  JWT    │  Session Router   │  JWT    │  Agent Service    │
│   :8083       │────────>│  :8081            │────────>│  :8082            │
│               │router:  │                   │agent:   │                   │
│               │invoke   │                   │invoke   │                   │
└──────────────┘         └────────┬──────────┘         └──────────────────┘
                                ▲                            ▲
                                │ router:register            │
                         ┌──────┴──────────┐                 │
                         │  Agent Service   │                 │
                         │  (Registrar)     │                 │
                         └─────────────────┘                 │
                                                             │
┌──────────────┐  X-Api-Key      ┌──────────────────┐        │
│  外部客户端    │────────────────>│  Session Router   │        │
└──────────────┘                 └────────┬──────────┘        │
                                          │ HTTP (JWT)        │
                                          │ admin:apikey      │
                                          │ admin:session     │
                                          ▼                   │
                                 ┌──────────────────┐         │
                                 │   Admin Service   │◄────────┘
                                 │   :8080           │
                                 └──────────────────┘
```

## 9. Router 去 MySQL 化架构

Router 模块仅保留 MySQL 用于调用日志（api_call_log），其余功能全部使用内存/Redis 或 HTTP 调用。

| 功能 | 之前 | 现在 |
|------|------|------|
| 实例注册 | MySQL + Redis/Local 缓存 | 纯内存（ConcurrentHashMap）或纯 Redis |
| Session 绑定 | MySQL + Redis/Local 缓存 | 纯内存（ConcurrentHashMap + 反向索引）或纯 Redis |
| API Key 校验 | MySQL + Caffeine 缓存 | HTTP 调用 admin + Caffeine 缓存 |
| Session 信息查询 | 直连 MySQL | HTTP 调用 admin + Caffeine 缓存 |
| 调用日志 | — | MySQL（异步批量写入） |

### 缓存模式配置

```yaml
router:
  cache:
    type: local          # 'local'（单节点）或 'redis'（多节点）
```

### 调用日志

- `ApiCallLogFilter`（OncePerRequestFilter）拦截每个请求
- `SessionInfoClient` 通过 HTTP 调用 admin 获取 session 关联的 agent/model 信息
- `ApiCallLogService` 异步批量写入 MySQL（50 条或 5 秒刷一次）

## 10. 两条通道对比

| 维度 | 内部 JWT | 外部 API Key |
|------|---------|-------------|
| **请求头** | `Authorization: Bearer <jwt>` | `X-Api-Key: hnx_xxx` |
| **凭证来源** | 服务启动时自动生成 | Admin 后台手动创建 |
| **scope 来源** | 代码写死在客户端配置中 | 数据库按 Key 粒度配置 |
| **callerType** | INTERNAL_SERVICE | EXTERNAL_API |
| **能否访问 internalOnly** | 能 | 不能 |
| **过期机制** | 自动续期（token 缓存刷新） | 管理员设过期时间 |
| **吊销方式** | 改共享密钥（影响所有服务） | 禁用/删除单个 Key |
| **多租户** | 不支持 | 支持（Key 绑定 tenantId） |
| **校验位置** | InternalTokenProvider（内存计算） | RemoteApiKeyStore（HTTP + Caffeine） |
| **限流** | 不支持 | 支持（滑动窗口） |
| **适用场景** | 服务间内部调用 | 第三方系统接入 |

## 11. 白名单路径

| 前缀 | 用途 |
|------|------|
| `/health` | 健康检查 |
| `/actuator` | Spring Actuator |
| `/ai` | agent-service 外部直连（临时保留） |

## 12. 接入指南

### 12.1 Maven 依赖

```xml
<dependency>
    <groupId>com.agnetix</groupId>
    <artifactId>harnax-auth</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 12.2 YAML 配置

```yaml
harnax:
  auth:
    enabled: true
    service-id: ${SERVICE_ID:my-service}
    internal:
      shared-secret: ${HARNAX_AUTH_SECRET:change-me-in-production-min-32-chars!!}
      token-ttl-seconds: 300
    external:
      enabled: false                       # 仅 router 设为 true
```

### 12.3 自动装配的 Bean

| Bean | 类型 | 条件 | 说明 |
|------|------|------|------|
| `internalTokenProvider` | `InternalTokenProvider` | 始终 | JWT 生成与验证 |
| `externalApiKeyValidator` | `ExternalApiKeyValidator?` | `external.enabled=true` 且存在 `ApiKeyStore` | API Key 校验 |
| `unifiedAuthFilter` | `UnifiedAuthFilter` | `enabled=true` | 统一认证过滤器 |
| `scopeAuthorizationInterceptor` | `ScopeAuthorizationInterceptor` | 始终 | Scope 拦截器 |
| `rateLimitInterceptor` | `RateLimitInterceptor?` | 存在 `RateLimitChecker` Bean | 限流拦截器 |

### 12.4 如需支持 API Key 认证

实现 `ApiKeyStore` 接口并注册为 Spring Bean。

### 12.5 如需支持限流

实现 `RateLimitChecker` 接口并注册为 Spring Bean。

### 12.6 Controller 标注权限

```kotlin
@RequireScope("my:read")
@GetMapping("/data")
fun getData() { ... }

@RequireScope("my:admin", internalOnly = true)
@PostMapping("/internal-op")
fun internalOp() { ... }
```

### 12.7 获取调用方信息

```kotlin
val context = AuthContextHolder.get()
// context.callerId / scopes / callerType / tenantId / rateLimitPerMinute
```

## 13. 模块文件清单

```
harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/
├── AuthProperties.kt
├── AuthContext.kt
├── InternalTokenProvider.kt
├── UnifiedAuthFilter.kt
├── ScopeAuthorizationInterceptor.kt
├── RateLimitInterceptor.kt
├── RateLimitChecker.kt
├── RequireScope.kt
├── ExternalApiKeyValidator.kt
├── ApiKeyStore.kt
├── ApiKeyInfo.kt
├── AuthRestTemplateInterceptor.kt
└── AuthAutoConfiguration.kt
```

**router 模块中 auth 相关文件：**

```
harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/
├── service/RemoteApiKeyStore.kt        # ApiKeyStore (HTTP + Caffeine)
├── service/RateLimiter.kt              # RateLimitChecker (滑动窗口)
├── service/SessionInfoClient.kt        # HTTP 调用 admin 获取 session 信息
├── service/ApiCallLogService.kt        # 异步批量写入调用日志
├── config/ApiCallLogFilter.kt          # 请求拦截，收集调用日志
├── entity/ApiCallLog.kt                # 调用日志实体
├── mapper/ApiCallLogMapper.kt          # MyBatis Mapper（唯一的 Mapper）
└── config/InstanceRegistrationValidationFilter.kt
```

## 14. 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `HARNAX_AUTH_SECRET` | 服务间 JWT 共享密钥 | `change-me-in-production-min-32-chars!!` |
| `SERVICE_ID` | 当前服务实例标识 | 各服务不同 |

## 15. 待完善事项

| 事项 | 说明 | 优先级 |
|------|------|--------|
| Channel webhook 白名单 | 飞书/微信回调路径加入白名单 | 中 |
| 审计日志 | 认证失败、权限拒绝时记录详细审计日志 | 中 |
| `/ai/**` 端点移除 | 统一到 router 代理入口 | 低 |

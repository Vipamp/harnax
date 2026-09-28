# API Key 双类型方案

## 1. 背景与问题

### 1.1 要解决的问题

前端与移动端调用 Router 时需要一把 API Key。如果这把 Key 随登录签发——每次登录生成一个新 `hnx_sk_live_xxx`、按 `web_router_{username}_{timestamp}` 之类的 name INSERT 一条 `api_key` 记录、把过期时间设成 JWT 的过期时间——那么 Token 过期后的每一次重新登录都会留下一行：过期的 Key 不再被使用，但 `active` 仍是 1，不会被清理，`api_key` 表随登录次数线性膨胀，查询与列表都随之变慢。

本方案的做法是把「登录态」和「Router 凭据」拆成两个生命周期：

- 每个用户持有**一把永久 Key**，随用户创建而铸造，`expires_at` 为 null，登录只读取它、不签发新的 Key。
- 登录响应里的 `routerApiKey` 就是这个永久 Key 的 rawKey，前端与移动端照常持有、照常使用。
- `api_key` 表里属于用户侧的永久 Key 永远只有一行，由 `uk_user_permanent (user_id, key_type)` 保证。

外部系统接入用的是另一类 Key：由管理员在 API Key 管理页面创建、可设过期时间、可增删改查。两类 Key 存在同一张表、走同一套校验代码，靠 `key_type` 区分职责边界。

### 1.2 调用链路与认证方式

| 调用方 | 目标 | 认证方式 | 代码位置 |
|--------|------|---------|----------|
| Web 前端（harnax-webui） | Router | `X-Api-Key`（用户的永久 Key） | `harnax-webui/src/services/ant-design-pro/workspace.ts:8`、`harnax-webui/src/services/ant-design-pro/chat.ts:5` |
| 移动端（harnax-app） | Router | `X-Api-Key`（用户的永久 Key） | `harnax-app/src/api/client.ts:72`、`harnax-app/src/api/router.ts:24` |
| CLI（harnax-cli） | Router | `X-Api-Key`（用户的永久 Key） | `harnax-cli/cmd/auth.go:52` |
| Channel Service | Router | `X-Api-Key`（`channel-service` 的 SYSTEM Key） | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/config/ChannelConfig.kt:59` |
| Scheduler Service | Router | `X-Api-Key`（`scheduler` 的 SYSTEM Key） | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt:125` |
| 外部第三方系统 | Router | `X-Api-Key`（临时 Key） | 由管理员在 API Key 管理页面创建，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt:65` |
| Router | Agent-Service | `Authorization: Bearer <内部 JWT>` | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/RouterConfig.kt:131` |
| Channel / Scheduler / Router | Admin 内部接口 | `Authorization: Bearer <共享密钥>` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt:19` |

### 1.3 认证架构

Router 的上游客户端一律用 `X-Api-Key`，Router 自己的出站调用与 Admin 的内部接口用服务间凭据：

- 入站：人的凭据（永久 Key）与服务的凭据（SYSTEM Key、临时 Key）都走 `X-Api-Key`，校验代码同一套。
- 出站：Router → Agent-Service 用 `InternalTokenProvider` 签发的内部 JWT，`X-Api-Key` 不参与。
- Admin 的 `/api/admin/internal/**` 由 `InternalApiAuthFilter` 用共享密钥守护，浏览器无法访问。

---

## 2. 方案要点

### 2.1 API Key 的三种类型

`api_key.key_type` 取三个值，各自的职责与允许的操作：

| 类型 | 生命周期 | 铸造时机 | 允许操作 | 使用场景 |
|------|---------|---------|---------|----------|
| **PERMANENT** | 跟随用户，`expires_at` 为 null | 用户创建时自动生成；启动时为缺 Key 的活跃用户补建 | 仅重置（`regenerate`） | Web / 移动端 / CLI 调用 Router |
| **TEMPORARY** | 可设过期时间 | 用户在 API Key 管理页面手动创建 | 增删改查、重置、变更有效期 | 外部系统接入认证 |
| **SYSTEM** | 跟随服务，`expires_at` 为 null | Admin 启动时按服务名铸造 | 仅重置 | Channel、Scheduler 调用 Router |

PERMANENT 关联 `user_id`，SYSTEM 关联 `service_name`，TEMPORARY 两者都不关联。PERMANENT 与 SYSTEM 的行为规则相同：永不过期、不可删除、不可修改、不可停用，只能重置；差别只在标识对象是用户还是服务。

### 2.2 权限范围（Scopes）

| Scope | 说明 | 当前状态 |
|-------|------|----------|
| `chat` | 仅支持对话相关接口 | 所有 Key 的缺省值 |
| `manager` | 可管理 sandbox 等资源 | 预留字段，路由侧未做 scope 判定 |

---

## 3. 数据库形态

### 3.1 `api_key` 表的当前形态

`api_key` 的列与索引全部写在 admin 的 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 那一段建表语句里，本方案不涉及 DDL 变更。与本文相关的形态：

| 列 | 类型与缺省 | 说明 |
|----|------------|------|
| `key_type` | `varchar(16) NOT NULL DEFAULT 'TEMPORARY'` | `PERMANENT` / `TEMPORARY` / `SYSTEM` |
| `user_id` | `bigint NULL` | 永久 Key 关联的用户 ID |
| `raw_key_encrypted` | `varchar(256) NULL` | AES 加密后的 rawKey，仅永久与 SYSTEM Key 使用（登录或服务启动时要回原始值） |
| `service_name` | `varchar(64) NULL` | SYSTEM Key 的服务名，如 `channel-service` |
| `key_hash` | `varchar(64) NOT NULL` | 原始 Key 的 SHA-256 哈希，唯一 |
| `key_prefix` | `varchar(32) NOT NULL` | 展示用前缀（如 `hnx_sk_live_xxxx`） |
| `scopes` | `varchar(512) NOT NULL` | 逗号分隔的作用域 |

键与索引：`name` 只有普通索引 `idx_name`（永久 Key 的 name 格式统一，不能唯一）；`key_hash` 唯一；`uk_user_permanent (user_id, key_type)` 保证每个用户一把永久 Key；`uk_service_system (service_name, key_type)` 保证每个服务一把 SYSTEM Key；另建 `idx_key_type`、`idx_user_id`、`idx_service_name`、`idx_enabled`、`idx_key_hash`。

### 3.2 权限范围（Scopes）简化

权限范围仅区分两类，后续可扩展：

| Scope | 说明 | 适用场景 |
|-------|------|----------|
| `chat` | 仅支持对话相关接口 | 永久 Key、Channel 系统 Key |
| `manager` | 可管理 sandbox 等资源（**暂不实现**，预留字段） | 高级管理 Key |

> **说明：** 当前阶段所有 Key 的 scopes 统一设为 `chat`。`manager` scope 作为预留字段，后续实现 sandbox 管理能力时再启用。

### 3.3 没有数据迁移这一步

`harnax-deploy` 环境的库由基线一次建成，`api_key` 从空表开始，Key 由登录与服务启动时重新签发，因此本域不存在「把存量行改成新形态」的迁移脚本，基线里也不写这类 UPDATE。过期的临时登录 Key 由读取侧的过滤与列表口径处理，不靠一次性语句清理。

### 3.4 `ApiKeyEntity` 的字段

`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ApiKeyEntity.kt` 承载三种类型的同一张表，与本文相关的字段：

```kotlin
// harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ApiKeyEntity.kt:20-30
/** Key type: PERMANENT, TEMPORARY or SYSTEM */
var keyType: String = "TEMPORARY"

/** Associated user ID (for PERMANENT keys) */
var userId: Long? = null

/** AES-encrypted raw key (for PERMANENT/SYSTEM keys only) */
var rawKeyEncrypted: String? = null

/** Service name (for SYSTEM keys, e.g. channel-service) */
var serviceName: String? = null
```

> **设计说明：** 永久 Key 和 SYSTEM Key 的 rawKey 加密后存在 `api_key` 表自身，`sys_user` 表不持有凭据。
> 临时 Key 不需要这个字段——创建时一次性返回 rawKey，之后只靠 `key_hash` 校验。

### 3.5 `ApiKeyMapper` 的查询方法

`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ApiKeyMapper.kt` 声明，SQL 在 `harnax-entity/src/main/resources/mapper/ApiKeyMapper.xml`：

```kotlin
// harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ApiKeyMapper.kt:34-45
fun selectPermanentKeyByUserId(@Param("userId") userId: Long): ApiKeyEntity?
fun selectTemporaryKeys(
    @Param("keyword") keyword: String?,
    @Param("enabled") enabled: Int?,
    @Param("creator") creator: String?,
    @Param("tenantId") tenantId: Long?,
): List<ApiKeyEntity>
fun selectSystemKeyByServiceName(@Param("serviceName") serviceName: String): ApiKeyEntity?
```

三条语句各自带 `key_type` 条件：`selectPermanentKeyByUserId` 落在 `key_type = 'PERMANENT'`（`ApiKeyMapper.xml:118`），`selectTemporaryKeys` 落在 `key_type = 'TEMPORARY'`（`ApiKeyMapper.xml:128`），`selectSystemKeyByServiceName` 落在 `key_type = 'SYSTEM'`（`ApiKeyMapper.xml:150`）。管理页面的分页因此只会返回临时 Key。

---

## 4. 后端设计

永久 Key 的完整生命周期都收在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt`：铸造（`createPermanentKeyForUser` :190）、取回（`getPermanentRawKey` :226）、重置（`regeneratePermanentKey` :236）。`ApiKeyService` 接口对应声明在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ApiKeyService.kt:29`、`:32`、`:35`、`:38`。

> 本节与 §5 的代码片段是节选：只删去日志等不影响行为的行，判定分支与字段赋值保持原样，行号指向源文件。

### 4.1 永久 Key 的铸造

`createPermanentKeyForUser` 一次性写入 `key_hash`、`key_prefix`、`raw_key_encrypted` 三列，`expires_at` 留空，name 固定为 `permanent_{username}`：

```kotlin
// harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:189-224
@Transactional(rollbackFor = [Exception::class])
override fun createPermanentKeyForUser(userId: Long, username: String, tenantId: Long?): ApiKeyCreatedResponse {
    val rawKey = generateRawKey()
    val keyHash = sha256(rawKey)
    val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
    val encrypted = aesUtil.encrypt(rawKey)

    val entity = ApiKeyEntity().apply {
        name = "permanent_$username"
        keyType = "PERMANENT"
        this.userId = userId
        rawKeyEncrypted = encrypted
        serviceName = null
        this.keyHash = keyHash
        this.keyPrefix = keyPrefix
        scopes = "chat"
        this.tenantId = tenantId
        rateLimit = 300
        enabled = 1
        expiresAt = null
        creator = "system"
        active = 1
        createTime = LocalDateTime.now()
        updateTime = LocalDateTime.now()
    }

    apiKeyMapper.insert(entity)
    return ApiKeyCreatedResponse(id = entity.id, name = entity.name, rawKey = rawKey, keyPrefix = keyPrefix)
}
```

rawKey 的形状由 `generateRawKey()`（同文件 :330）决定：`hnx_sk_live_` 前缀加 32 字节 `SecureRandom` 的 URL-safe Base64。`sha256()`（:336）产出用于校验比对的 `key_hash`。

### 4.2 用户创建即铸造

`SysUserServiceImpl.createUser` 在 INSERT 用户成功后紧接着调用 `createPermanentKeyForUser`，位置在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImpl.kt:124-136`：

```kotlin
val success = this.sysUserMapper.insert(user) > 0
if (success) {
    // Auto-generate permanent API key for the new user
    try {
        apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId)
    } catch (e: Exception) {
        log.error("Failed to create permanent API key for user: {}", user.username, e)
    }
}
```

这里的 Key 铸造包在 try/catch 内：铸造失败只记日志，不回滚用户创建。失败的账号由登录时的自愈分支（§4.3）或启动补建（§11）兜住。

### 4.3 登录：返回永久 Key

Web 登录 `AuthServiceImpl.login` 在校验通过、生成 JWT 之后，用 `getPermanentRawKey(user.id)` 取回该用户的永久 Key，解出 rawKey 放进 `LoginResponse.routerApiKey`；取不到时走 `run { ... }` 的补建分支——当场为该用户铸造一把并用其 rawKey 继续，铸造再失败（多为与启动补建/并发登录抢同一账号）则重查一次，仍为空才抛 `BizException`。位置 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:107-128`：

```kotlin
val rawKey = apiKeyService.getPermanentRawKey(user.id) ?: run {
    log.warn("Permanent API Key missing for user: {}, creating one on the fly", user.username)
    try {
        apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId).rawKey
    } catch (e: Exception) {
        // Possibly created concurrently by another login/startup initializer: retry lookup once
        log.error("Failed to create permanent API Key for user: {}, error: {}", user.username, e.message)
        apiKeyService.getPermanentRawKey(user.id)
            ?: throw BizException("Permanent API Key not found for user: ${user.username}")
    }
}

val response = LoginResponse.builder()
    .accessToken(accessToken)
    .tokenType("Bearer")
    .expiresIn(jwtUtil.getExpirationTime() / 1000)
    .expiresAt(expiresAt)
    .userInfo(userInfo)
    .tenants(userTenants)
    .currentTenantId(defaultTenantId)
    .routerApiKey(rawKey)
    .build()
```

三条登录出口对凭据的处理各有分工：

| 出口 | 方法 | `routerApiKey` | 缺 Key 时的行为 |
|------|------|----------------|----------------|
| Web 登录 | `AuthServiceImpl.login`（:107） | 返回 | 当场补建并重试一次 |
| CLI 登录 | `AuthServiceImpl.cliLogin`（:227） | 返回 | 与 Web 登录同一段自愈逻辑 |
| 移动端登录 | `MpAuthService.login`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/mp/MpAuthService.kt:61`） | 返回 `MpLoginResponse.routerApiKey` | 直接抛 `BizException("Permanent API Key not found for user: …")`，不补建 |

`LoginResponse.routerApiKey` 字段定义在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginResponse.kt:33`（builder 于 :48、:57），`MpLoginResponse.routerApiKey` 在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/mp/MpLoginResponse.kt:11`。登录响应确实带回这把 Key，可由集成测试断言旁证：`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/AuthLoginIT.kt:109` 与 `:173` 都断言 `data["routerApiKey"]` 以 `hnx_sk_live_` 开头。

> **rawKey 存储策略：** 永久 Key 的 rawKey 经 AES 加密后存在 `api_key.raw_key_encrypted`（§7），登录时解密返回。所有 API Key 数据都集中在 `api_key` 表内，`sys_user` 表不持有凭据。

### 4.4 永久 Key 的取回

`getPermanentRawKey` 是登录路径上唯一的读取口，位置 `ApiKeyServiceImpl.kt:226-233`：

```kotlin
override fun getPermanentRawKey(userId: Long): String? {
    val entity = apiKeyMapper.selectPermanentKeyByUserId(userId) ?: return null
    if (entity.enabled != 1) {
        throw BizException("Your API Key has been disabled, please contact administrator")
    }
    val encrypted = entity.rawKeyEncrypted ?: return null
    return aesUtil.decrypt(encrypted)
}
```

三种返回形态各有含义：查不到行返回 null（交给调用方的补建分支）；行存在但 `enabled != 1` 抛 `BizException`，登录直接失败并提示联系管理员；行存在但 `raw_key_encrypted` 为空返回 null。永久 Key 由本方案自己铸造，正常不会落到最后一种，落进去说明该行是外部写入的。

### 4.5 永久 Key 的重置

`regeneratePermanentKey`（`ApiKeyServiceImpl.kt:236-259`）在同一个实体上重写 `key_hash`、`key_prefix`、`raw_key_encrypted` 三列，`enabled`、`tenant_id`、`scopes`、`rate_limit` 一律沿用原行：

```kotlin
@Transactional(rollbackFor = [Exception::class])
override fun regeneratePermanentKey(userId: Long): ApiKeyCreatedResponse {
    val entity = apiKeyMapper.selectPermanentKeyByUserId(userId)
        ?: throw RuntimeException("Permanent API Key not found for user: $userId")

    val rawKey = generateRawKey()
    entity.keyHash = sha256(rawKey)
    entity.keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
    entity.rawKeyEncrypted = aesUtil.encrypt(rawKey)
    entity.updateTime = LocalDateTime.now()
    apiKeyMapper.updateById(entity)

    return ApiKeyCreatedResponse(id = entity.id, name = entity.name, rawKey = rawKey, keyPrefix = entity.keyPrefix)
}
```

重置不产生第二行，因此 `uk_user_permanent` 不会被触碰；重置前的 rawKey 在 admin 侧的哈希被覆盖后不再匹配，Router 侧的放行还会持续到该缓存条目过期（§8.3）。

### 4.6 类型边界：受保护的 Key

`protectedKeyTypes = setOf("PERMANENT", "SYSTEM")`（`ApiKeyServiceImpl.kt:40`）是三个写入口共用的判据，命中即拒绝，并指明用重置代替：

| 方法 | 位置 | 对 PERMANENT / SYSTEM 的行为 |
|------|------|------------------------------|
| `updateApiKey` | `ApiKeyServiceImpl.kt:116-118` | 抛 `${keyType} API Key cannot be modified, use regenerate instead` |
| `deleteApiKey` | `ApiKeyServiceImpl.kt:142-144` | 抛 `${keyType} API Key cannot be deleted, use regenerate instead` |
| `toggleEnabled` | `ApiKeyServiceImpl.kt:153-155` | 抛 `${keyType} API Key cannot be disabled` |

三个方法都先经 `loadAndCheckAccess(id)`（:295）读取行并做租户/创建人归属校验，再看 `key_type`。手动创建的 Key 一律是临时 Key，`createApiKey` 在构造实体时写定这三个字段（`ApiKeyServiceImpl.kt:84-91`）：`keyType = "TEMPORARY"`、`userId = null`、`serviceName = null`，scopes 取请求值或落回 `chat`。

### 4.7 分页与管理接口

分页只查临时 Key，`ApiKeyServiceImpl.kt:52-53`：

```kotlin
PageHelper.startPage<ApiKeyEntity>(safePageNum, safePageSize)
return Page.fromPageInfo(apiKeyMapper.selectTemporaryKeys(keyword, enabled, creator, tenantId))
```

永久 Key 的查看与重置挂在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt`，基址 `/api/admin/api-keys`：

| 接口 | 位置 | 行为 |
|------|------|------|
| `GET /my-permanent-key` | `ApiKeyController.kt:126-136` | 按当前用户 id 读永久 Key 行，经 `convertToResponse` 返回脱敏视图；无行时返回 null |
| `POST /regenerate-permanent` | `ApiKeyController.kt:138-148` | 调 `regeneratePermanentKey(userId)`，返回一次性 rawKey |

两个接口都从 `SecurityUtils.getCurrentUser()?.id` 取身份，因此任何用户只能读到与重置自己那把 Key；`GET /{id}` 与写接口走 §4.6 的归属与类型校验。

---

## 5. Channel 与 Scheduler 调用 Router

服务间的调用与用户调用在 Router 侧走同一个 `X-Api-Key` 校验口，凭据是 `key_type = SYSTEM` 的 Key：`expires_at` 为 null、`user_id` 为 null、`service_name` 标识所属服务，由 `uk_service_system (service_name, key_type)` 保证一个服务一把。

### 5.1 铸造

`ApiKeyServiceImpl.initSystemKeys()`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:261-293`）按服务名列表逐个补齐，已有行则跳过：

```kotlin
@Transactional(rollbackFor = [Exception::class])
override fun initSystemKeys() {
    val systemServices = listOf("channel-service", "scheduler")
    for (serviceName in systemServices) {
        val existing = apiKeyMapper.selectSystemKeyByServiceName(serviceName)
        if (existing == null) {
            val rawKey = generateRawKey()
            val entity = ApiKeyEntity().apply {
                name = "system_$serviceName"
                keyType = "SYSTEM"
                userId = null
                rawKeyEncrypted = aesUtil.encrypt(rawKey)
                this.serviceName = serviceName
                this.keyHash = sha256(rawKey)
                this.keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
                scopes = "chat"
                rateLimit = 600
                enabled = 1
                expiresAt = null
                creator = "system"
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }
            apiKeyMapper.insert(entity)
        }
    }
}
```

调用点是启动 Runner `PermanentKeyInitializer.run` 的第一步（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/init/PermanentKeyInitializer.kt:26`），失败只记日志、不阻断启动（§11）。SYSTEM Key 与永久 Key 一样落在 `protectedKeyTypes` 里，管理接口删不掉也改不了（§4.6）。

### 5.2 下发接口

Admin 提供一个内部接口把 SYSTEM Key 的 rawKey 交给对应服务，位置 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:238-247`，完整路径 `POST /api/admin/internal/api-keys/system-key`（类级 `@RequestMapping("/api/admin/internal")` 于 :54）：

```kotlin
@PostMapping("/api-keys/system-key")
fun getSystemKey(@RequestBody request: SystemKeyRequest): ResultVo<SystemKeyResponse?> {
    val entity = apiKeyMapper.selectSystemKeyByServiceName(request.serviceName)
    if (entity == null) {
        log.warn("System key not found for service: ${request.serviceName}")
        return ResultVo.success(null)
    }
    val rawKey = aesUtil.decrypt(entity.rawKeyEncrypted!!)
    return ResultVo.success(SystemKeyResponse(rawKey = rawKey, keyPrefix = entity.keyPrefix))
}
```

请求体 `SystemKeyRequest(serviceName)` 与响应体 `SystemKeyResponse(rawKey, keyPrefix)` 定义在同文件 :107、:109。服务名不存在时返回 `data: null`，调用方据此重试（§5.3、§5.5）。该前缀下的所有路由由 `InternalApiAuthFilter` 用 `Authorization: Bearer <admin.internal-api.secret>` 守护（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt:19`，密钥配置项见 `harnax-admin/src/main/resources/application.yml:114-115`），浏览器与用户凭据都到不了这个接口。

### 5.3 Channel 侧获取

`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/config/ChannelApiKeyInitializer.kt` 在 Spring 装配期把 Key 定下来，产出一个 `ChannelRouterApiKey` Bean（同文件 :16）：

```kotlin
// ChannelApiKeyInitializer.kt:37-52
@Bean
fun channelRouterApiKey(): ChannelRouterApiKey {
    val key = if (configuredApiKey.isNotBlank()) {
        configuredApiKey
    } else if (autoFetch) {
        fetchSystemKeyFromAdmin()
    } else {
        throw IllegalStateException("Channel router API key not configured. Set CHANNEL_API_KEY env var or enable channel.auto-fetch-system-key=true")
    }
    return ChannelRouterApiKey(key)
}
```

取值优先级是「环境变量优先，其次向 Admin 拉取」：`channel.router-api-key` 非空就直接用（`harnax-channel/harnax-channel-service/src/main/resources/application.yml:30`，值来自 `CHANNEL_API_KEY`），为空且 `channel.auto-fetch-system-key=true`（同文件 :31）时调用 §5.2 的接口。拉取走独立的 `RestClient`，带重试与退避（`ChannelApiKeyInitializer.kt:54-94`，共 3 次、间隔按次数递增），因为 Admin 与 Channel 在 compose 里同时起来，Admin 可能短暂不可达；3 次都失败则 Bean 创建失败并抛 `IllegalStateException`。请求参数：路径常量 `/api/admin/internal/api-keys/system-key`（:115）、服务名常量 `channel-service`（:116）、`Authorization: Bearer ${admin.internal-api.secret}`（:32-33、:100）。Admin 地址取 `channel.admin.url`（`application.yml:52-53`，默认 `http://localhost:8080`）。

`ADMIN_INTERNAL_API_SECRET` 由部署侧注入 Channel 容器（`harnax-deploy/docker-compose.yml:584`），`CHANNEL_API_KEY` 同样注入且默认为空（:586），所以线上走的是自动拉取这条路径。

### 5.4 Channel 侧使用

`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/config/ChannelConfig.kt` 的两个 HTTP 客户端 Bean 都注入 `ChannelRouterApiKey` 并加上 `X-Api-Key` 头：

| Bean | 位置 | 加头方式 |
|------|------|---------|
| `webClient()` | `ChannelConfig.kt:48-61` | `.filter(apiKeyFilter(channelRouterApiKey.rawKey))`，过滤器在 :109-113 |
| `restClient()` | `ChannelConfig.kt:67-81` | `requestInterceptor` 内 `request.headers.add("X-Api-Key", channelRouterApiKey.rawKey)`（:77） |

`RouterClient` 只接受这两个 Bean，自己不碰凭据（`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/client/RouterClient.kt:47-53`）：`webClient`、`restClient`、`objectMapper`、`RouterCircuitBreaker` 与两个超时配置项。凭据因此集中在 `ChannelConfig` 与 `ChannelApiKeyInitializer` 两处，调用点无需知道 Key 从哪来。

### 5.5 Scheduler 侧

Scheduler 用同一条通道，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt`：`@PostConstruct init()`（:42-50）先看 `scheduler.api-key`（:21），为空则 `fetchSystemKeyFromAdmin()`（:53）以 `serviceName = "scheduler"` 请求 §5.2 的接口（:61-64），最多 5 次（:55）、每次间隔 3 秒（:56），仍失败则抛 `IllegalStateException`（:86）。Admin 地址与密钥分别取 `scheduler.admin-url`、`scheduler.admin-secret`（:22-23，配置项见 `harnax-scheduler/src/main/resources/application.yml:194`）。解析出的 Key 存在字段 `apiKey`（:40），每次请求加 `X-Api-Key` 头（:125、:174、:224）。

---

## 6. 客户端侧

登录返回的 `routerApiKey` 就是永久 Key，客户端的使用方式与普通 Key 无差别：存下来、每次请求放进 `X-Api-Key`。

### 6.1 Web 前端（harnax-webui）

登录响应里的 `routerApiKey` 随 token 信息一并存本地：`harnax-webui/src/pages/user/login/index.tsx:433`。读出来加头的地方有两处封装——`harnax-webui/src/services/ant-design-pro/workspace.ts:8-13`（`getRouterApiKey()`，用于 workspace/session 类接口，加头见 :25、:125、:147）与 `harnax-webui/src/services/ant-design-pro/chat.ts:5-10`（对话流式接口）；聊天窗口另有一份等价逻辑，`harnax-webui/src/pages/session/components/ChatWindow.tsx:158-159` 在有 `routerApiKey` 时用 `X-Api-Key`，否则回退到 Bearer。

API Key 管理页 `harnax-webui/src/pages/api-key/index.tsx` 的数据来自 §4.7 的分页接口，列表里只有临时 Key，页面因此只提供临时 Key 的创建、编辑、启停、删除与重置（重置按钮与 `regenerateApiKey` 调用在 :16、:151-161）；创建表单 `src/pages/api-key/components/CreateForm.tsx` 提交的 Key 由后端写定为 TEMPORARY（§4.6）。

> §4.7 的 `GET /my-permanent-key` 与 `POST /regenerate-permanent` 在 webui 里没有调用点：`harnax-webui/src` 下检索不到这两个路径。用户当前拿到自己永久 Key 的唯一途径是登录响应，重置这个动作暂时只能直接调接口。

### 6.2 移动端（harnax-app）

登录返回后连同 router 地址一起存入连接 store：`harnax-app/src/components/connection/ConnectionSetup.vue:156`，字段类型见 `harnax-app/src/types/api.ts:69`、`:182`，store 里的 `routerApiKey` 见 `harnax-app/src/store/useConnectionStore.ts:12`。发请求时 `harnax-app/src/api/client.ts:65` 先检查是否已拿到 Key，:72 加 `X-Api-Key` 头；`harnax-app/src/api/router.ts:24` 是同一份加头逻辑的另一处入口。Key 缺失意味着未登录，客户端不会自己去铸造。

### 6.3 CLI（harnax-cli）

CLI 登录解析 `routerApiKey` 字段：`harnax-cli/cmd/auth.go:52`，对应服务端 `AuthServiceImpl.cliLogin`（§4.3）。

---

## 7. AES 加密

`api_key.raw_key_encrypted` 由 `AesUtil` 加解密，位置 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/AesUtil.kt`：

| 要点 | 位置 | 内容 |
|------|------|------|
| 算法与密钥长度 | `AesUtil.kt:24-25`、`:86` | `AES/GCM/NoPadding`，32 字节密钥（AES-256） |
| 加密 | `AesUtil.kt:65-71` | 每次随机 12 字节 IV，密文为 `Base64(IV + ciphertext)` |
| 解密 | `AesUtil.kt:76-83` | 前 12 字节取 IV，其余为密文 |
| 密钥来源 | `harnax-admin/src/main/resources/application.yml:140-141` | `harnax.aes.secret-key: ${HARNAX_AES_SECRET_KEY:…}` |
| 密钥形状告警 | `AesUtil.kt:36-59` | 非 32 字节的密钥会被补零或截断并在启动时告警；仍为占位默认值时另行告警 |

`ApiKeyServiceImpl` 注入的是 `AesUtil`（`ApiKeyServiceImpl.kt:13`、`:32`），永久 Key 与 SYSTEM Key 的三处加解密都走它（:194、:232、:243、:270，以及 `InternalApiController.kt:245`）。admin 里另有 `SecretFieldEncryptor`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`）负责 model / tool / MCP 等其他密文字段，与 `raw_key_encrypted` 无关。

永久 Key 与 SYSTEM Key 的 rawKey 需要可还原，是因为两个读取口都要回原始值：登录时返回给客户端（§4.4），服务启动时下发给 Channel / Scheduler（§5.2）。临时 Key 不需要，创建时一次性返回、之后只做哈希比对。换掉 `harnax.aes.secret-key` 会让已写入的密文无法解开，受影响的凭据需要重新铸造。

---

## 8. Router 侧的凭据校验

Router 自身没有认证代码，入站与出站能力都由 `harnax-auth` 提供：`harnax-session-router/pom.xml:41` 依赖该模块，`harnax-session-router/src/main/resources/application.yml:136-144` 打开 `harnax.auth.enabled` 与 `harnax.auth.external.enabled`，`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/AuthAutoConfiguration.kt:41-54` 据此注册过滤器。

### 8.1 入站分支

`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt` 按固定顺序判定，`@Order(HIGHEST_PRECEDENCE + 10)` 于 :13：

| 分支 | 位置 | 行为 |
|------|------|------|
| 白名单 | `UnifiedAuthFilter.kt:24-27`、`:40-43` | `/health`、`/actuator` 与配置项 `harnax.auth.skip-paths` 直接放行 |
| `Authorization: Bearer` | :45-71 | 交 `InternalTokenProvider.verifyToken` 校验服务间 JWT，通过则写入 `AuthContextHolder` |
| `X-Api-Key` | :73-93 | 交 `ExternalApiKeyValidator.validate`，通过则写入 `AuthContextHolder` |
| 两者皆无 | :95-103 | 401，提示需要 Bearer JWT 或 `X-Api-Key` |

Bearer 分支覆盖的是 agent-service 对 Router 内部接口的调用——心跳与实例注册这类请求要靠它校验服务身份并填充 `AuthContext`（配置项在 `application.yml:146-154` 明确警告不要把 `/api/router/` 加进 skip-paths）。人的凭据与服务的凭据一律走 `X-Api-Key` 分支。

### 8.2 X-Api-Key 的判定

`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ExternalApiKeyValidator.kt:11-32` 三步：`sha256(apiKey)`（:34-38）→ `apiKeyStore.findByKeyHash(hash)`，查不到即 `SecurityException("Invalid API key")` → 依次看 `enabled` 与 `isExpired()`。通过则产出 `AuthContext(callerId = keyInfo.name, userId, scopes, tenantId, rateLimitPerMinute, callerType = EXTERNAL_API)`，过滤器 :84-92 把 `SecurityException` 落成 401。限流与内部性判定由同模块的 `RateLimitInterceptor`、`InternalAuthorizationInterceptor` 在拦截器阶段接手（`AuthAutoConfiguration.kt:56-82`）。

### 8.3 Key 信息的来源

Router 不直连 `api_key` 表。`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/RemoteApiKeyStore.kt` 是 `ApiKeyStore` 的 `@Primary` 实现（:16-20）：

- `findByKeyHash`（:33-36）先查 Caffeine 缓存，缓存按 :28-31 配置——容量 1000、写入后 5 分钟过期；:24-27 的说明交代了取值的两个形状：查不到也写成 `Optional.empty()` 一并缓存，加载走 `cache.get(key) { loader }` 的原子形式，避免同一哈希并发回源。
- 未命中时 `fetchFromAdmin`（:38-61）调用 `AdminClientService.validateApiKey`（`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AdminClientService.kt:57`），路径 `/api/admin/internal/api-keys/validate`（:59），凭据是 `admin.internal-api.secret` 的 Bearer 头（:28、:47）。
- admin 侧的读口是 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:118-137`，按 `key_hash` 命中 `apiKeyMapper.selectByKeyHash`。

因此一次重置之后：admin 侧重置前的 rawKey 立刻不再匹配，而 Router 的放行还会持续到该哈希的缓存条目过期——上限 5 分钟。重置后的 rawKey 第一次出现必然回源。

### 8.4 三种类型共用同一条链

`key_type` 不出现在校验数据里：admin 的响应体 `ApiKeyValidateResponse`（`InternalApiController.kt:86-96`）与 Router 侧的 `ApiKeyInfo`（`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ApiKeyInfo.kt:8-18`）都没有这个字段，`ExternalApiKeyValidator` 也不读它。判定只看 `enabled` 与 `expiresAt`，而 `ApiKeyInfo.isExpired()`（:19）在 `expiresAt` 为 null 时返回 false——永久 Key 与 SYSTEM Key 的 `expires_at` 为 null（§4.1、§5.1），于是永不过期；临时 Key 到点后由这一行拒绝。

### 8.5 出站

Router → Agent-Service 用服务间 JWT：`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/RouterConfig.kt:72` 注入 `InternalTokenProvider`，`authFilter()`（:131-137）把它签发的头加到流式客户端 `streamingWebClient()`（:118-129）上。Router → Admin 用共享密钥（§8.3）。`X-Api-Key` 在 Router 的出站方向不参与。

---

## 9. 实现落点

### 后端

| 模块 | 落点 | 职责 |
|------|------|------|
| harnax-admin | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:141` | `api_key` 建表语句：`key_type` / `user_id` / `raw_key_encrypted` / `service_name` 四列与 `uk_user_permanent`、`uk_service_system` 两个唯一键 |
| harnax-entity | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ApiKeyEntity.kt:20-30` | `keyType`、`userId`、`rawKeyEncrypted`、`serviceName` 四个字段 |
| harnax-entity | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ApiKeyMapper.kt:34-45` | `selectPermanentKeyByUserId`、`selectTemporaryKeys`、`selectSystemKeyByServiceName` 三个查询声明 |
| harnax-entity | `harnax-entity/src/main/resources/mapper/ApiKeyMapper.xml:118`、`:128`、`:150` | 上述三条 SQL，各带 `key_type` 条件 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ApiKeyService.kt:29-38` | 永久 Key 与 SYSTEM Key 的四个方法契约 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:190`、`:226`、`:236`、`:262` | 永久 Key 的铸造、取回、重置；SYSTEM Key 的铸造 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:40`、`:116`、`:142`、`:153` | `protectedKeyTypes` 与三个写入口的类型判定 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ApiKeyServiceImpl.kt:52-53`、`:84-91` | 分页只取临时 Key；手动创建写定为 TEMPORARY |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt:126-148` | `GET /my-permanent-key`、`POST /regenerate-permanent` |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:118-137`、`:238-247` | Key 校验读口；SYSTEM Key 的 rawKey 下发接口 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt:19` | `/api/admin/internal/**` 的共享密钥校验 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:107-128`、`:227-243` | Web 登录与 CLI 登录返回永久 Key，缺 Key 时当场补建 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/mp/MpAuthService.kt:61-62`、`:77` | 移动端登录返回永久 Key，缺 Key 时拒绝 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginResponse.kt:33`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/mp/MpLoginResponse.kt:11` | `routerApiKey` 字段 |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImpl.kt:124-136` | 用户创建成功后铸造永久 Key |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/init/PermanentKeyInitializer.kt:23-54` | 启动 Runner：SYSTEM Key 铸造 + 活跃用户的永久 Key 补建（§11） |
| harnax-admin | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/AesUtil.kt:65-83` | `raw_key_encrypted` 的 AES-256-GCM 加解密 |
| harnax-admin | `harnax-admin/src/main/resources/application.yml:114-115`、`:140-141` | `admin.internal-api.secret`、`harnax.aes.secret-key` |
| harnax-channel | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/config/ChannelApiKeyInitializer.kt:37-112` | SYSTEM Key 的取值与拉取重试，产出 `ChannelRouterApiKey` |
| harnax-channel | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/config/ChannelConfig.kt:48-81`、`:109-113` | 两个 HTTP 客户端的 `X-Api-Key` 头 |
| harnax-channel | `harnax-channel/harnax-channel-service/src/main/resources/application.yml:30-31`、`:52-53` | `channel.router-api-key`、`channel.auto-fetch-system-key`、`channel.admin.url` |
| harnax-scheduler | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt:40-89` | SYSTEM Key 的取值与拉取重试，请求头 `X-Api-Key`（:125、:174、:224） |
| harnax-auth | `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt:45-103` | Bearer JWT 与 `X-Api-Key` 两条入站分支 |
| harnax-auth | `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ExternalApiKeyValidator.kt:11-38` | 哈希比对、`enabled`、过期判定 |
| harnax-auth | `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ApiKeyInfo.kt:19` | `isExpired()`：`expiresAt` 为 null 即不过期 |
| harnax-session-router | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/RemoteApiKeyStore.kt:28-61` | `ApiKeyStore` 实现：5 分钟缓存 + 回源 admin |
| harnax-session-router | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/RouterConfig.kt:131-137` | Router → Agent-Service 的内部 JWT 头 |

### 客户端

| 模块 | 落点 | 职责 |
|------|------|------|
| harnax-webui | `harnax-webui/src/pages/user/login/index.tsx:433` | 登录响应中的 `routerApiKey` 存入本地 |
| harnax-webui | `harnax-webui/src/services/ant-design-pro/workspace.ts:8-25`、`chat.ts:5-22` | 读出 Key 并加 `X-Api-Key` 头 |
| harnax-webui | `harnax-webui/src/pages/session/components/ChatWindow.tsx:158-159` | 有 Key 用 `X-Api-Key`，否则回退 Bearer |
| harnax-webui | `harnax-webui/src/pages/api-key/index.tsx` | 临时 Key 的增删改查与重置 |
| harnax-app | `harnax-app/src/api/client.ts:65-72`、`harnax-app/src/api/router.ts:24` | 加 `X-Api-Key` 头 |
| harnax-cli | `harnax-cli/cmd/auth.go:52` | 登录解析 `routerApiKey` |

### 不参与凭据判定的部分

| 模块 | 事实 |
|------|------|
| harnax-session-router | `RemoteApiKeyStore` 只按 `key_hash` 取信息，`key_type` 不在判定字段里（§8.4） |
| harnax-channel | `RouterClient` 只注入 `WebClient` / `RestClient`，加头在 `ChannelConfig`（§5.4） |
| harnax-auth | `InternalTokenProvider` 服务 Router → Agent-Service 与内部接口的调用（§8.5），与 `X-Api-Key` 无交集 |
| harnax-app / harnax-webui | 只使用登录响应里的 Key，不自行签发 |

---

## 10. 落点关系

各落点之间的支撑关系，自下而上：

- **schema 基线**承载 `api_key` 的四列与两个唯一键（§3.1），是实体与 Mapper 的字段来源；`uk_user_permanent`、`uk_service_system` 分别是一用户一 Key、一服务一 Key 的最终约束。
- **`ApiKeyEntity` 与 `ApiKeyMapper`**（§3.4、§3.5）为 admin 侧所有读写提供形状，三条带 `key_type` 条件的查询决定了「列表只有临时 Key」这一可见性。
- **`ApiKeyServiceImpl`**（§4.1-§4.6）是唯一持有 AES 与铸造逻辑的地方，被三类调用方依赖：`SysUserServiceImpl.createUser`（§4.2）、两个登录出口（§4.3）、`PermanentKeyInitializer`（§11）。
- **`InternalApiController`**（§5.2）把 admin 库里的两条信息交出去：SYSTEM Key 的 rawKey 与按 hash 的校验结果，二者都在 `InternalApiAuthFilter` 之后。
- **Channel 与 Scheduler**（§5.3-§5.5）依赖该下发接口，也依赖启动 Runner 已把 SYSTEM Key 铸好；两者的重试参数不同，Admin 未就绪时的等待时间也不同。
- **Router 的校验链**（§8）依赖 admin 的 hash 读口，通过 `RemoteApiKeyStore` 的 5 分钟缓存间接读库；`key_type` 到这一层不再参与判定。
- **客户端**（§6）依赖登录响应的 `routerApiKey` 字段；除移动端登录外的分支都自带补建，前端不需要预置凭据。

---

## 11. 永久 Key 的启动补建

启动 Runner 一次做两件事，源码 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/init/PermanentKeyInitializer.kt`（`@Component` 于 :14，构造依赖 `SysUserMapper`、`ApiKeyService`、`ApiKeyMapper` 于 :15-19）：

```kotlin
override fun run(vararg args: String) {
    // 1. Initialize SYSTEM keys (e.g. channel-service)
    try {
        apiKeyService.initSystemKeys()
    } catch (e: Exception) {
        log.error("Failed to initialize system API keys: {}", e.message, e)
    }

    // 2. Initialize permanent keys for existing users who don't have one
    try {
        val allUsers = sysUserMapper.selectAllActive()
        for (user in allUsers) {
            val existing = apiKeyMapper.selectPermanentKeyByUserId(user.id)
            if (existing == null) {
                try {
                    apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId)
                } catch (e: Exception) { /* 记日志，继续下一个用户 */ }
            }
        }
    } catch (e: Exception) {
        log.error("Failed to initialize permanent API keys for existing users: {}", e.message, e)
    }
}
```

分工与位置：

- SYSTEM Key 的铸造在 :25-30，即 `initSystemKeys()` 那一圈服务名列表（§5.1）；这一步失败只记日志，Admin 照常启动。
- 永久 Key 的补建在 :33-53：`sysUserMapper.selectAllActive()`（:34）取活跃用户，逐个用 `selectPermanentKeyByUserId`（:37）判断有无，没有才铸造（:40），单个用户失败只影响该用户（:43-45），最后统计补建条数（:48-50）。
- 两步都包在 try/catch 里，任何一步的异常都不阻断启动；补建漏掉的账号由登录时的自愈分支兜住（§4.3），移动端登录除外（§4.3 表格第三行）。
- 幂等：已有永久 Key 的用户被 :37 的查询跳过，重复启动不会第二把 Key；`uk_user_permanent` 也会在并发补建时兜住。

---

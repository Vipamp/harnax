# API Key 双类型改造方案

## 1. 背景与问题

### 1.1 当前问题

当前系统中，**每次用户登录**（Web 端 `AuthServiceImpl.login` / 移动端 `MpAuthService.login`）都会：

1. 生成一个新的 `hnx_sk_live_xxx` 格式的 API Key
2. 以 `web_router_{username}_{timestamp}` / `mp_router_{username}_{timestamp}` 为 name
3. 设置过期时间 = JWT 过期时间（通常几小时）
4. **INSERT 一条新记录到 `api_key` 表**

导致：
- 用户频繁登录（Token 过期后重新登录）会在 `api_key` 表中积累大量记录
- 旧 key 虽然过期但 `active` 仍为 1，不会被清理
- `api_key` 表数据膨胀，影响查询性能

### 1.2 当前调用链路认证方式

| 调用方 | 目标 | 认证方式 | 代码位置 |
|--------|------|---------|----------|
| Web 前端 (webui) | Router | `X-Api-Key` 头（登录时生成的 key） | `workspace.ts` → `getRouterApiKey()` |
| 移动端 (harnax-app) | Router | `X-Api-Key` 头（登录时生成的 key） | `client.ts` → `connection.routerApiKey` |
| Channel Service | Router | `Authorization: Bearer JWT`（InternalTokenProvider 共享密钥） | `RouterClient.kt` → `tokenProvider.authHeaders()` |
| Router | Agent-Service | `Authorization: Bearer JWT`（InternalTokenProvider 共享密钥） | Router 内部调用 |
| 外部第三方系统 | Router | `X-Api-Key` 头（管理员手动创建的 key） | 手动在 API Key 管理页面创建 |

### 1.3 目标认证架构

改造后，**Router 的所有上游客户端统一使用 `X-Api-Key` 认证**，仅 Router → Agent-Service 保留内部 JWT：

| 调用方 | 目标 | 认证方式（改造后） |
|--------|------|------------------|
| Web 前端 (webui) | Router | `X-Api-Key`（用户的永久 key） |
| 移动端 (harnax-app) | Router | `X-Api-Key`（用户的永久 key） |
| Channel Service | Router | `X-Api-Key`（Channel 系统级永久 key） |
| 外部第三方系统 | Router | `X-Api-Key`（临时 key） |
| **Router** | **Agent-Service** | **`Authorization: Bearer JWT`（InternalTokenProvider，不变）** |

---

## 2. 改造目标

### 2.1 API Key 类型

将 API Key 分为两类：

| 类型 | 生命周期 | 创建时机 | 允许操作 | 使用场景 |
|------|---------|---------|---------|----------|
| **永久 Key（PERMANENT）** | 跟随用户生命周期，永不过期 | 用户创建时自动生成 | 仅允许**重置**（regenerate） | 前端/移动端调用 Router、Channel 调用 Router |
| **临时 Key（TEMPORARY）** | 可设置过期时间 | 用户在前端页面手动创建 | 增删改查、重置、变更有效期 | 外部系统接入认证 |

### 2.2 权限范围（Scopes）

| Scope | 说明 | 当前状态 |
|-------|------|----------|
| `chat` | 仅支持对话相关接口 | ✅ 已实现，所有 Key 默认为此权限 |
| `manager` | 可管理 sandbox 等资源 | ❗ 暂不实现，预留字段 |

---

## 3. 数据库变更

## 3.1 `api_key` 表新增字段

```sql
-- Flyway migration: V{N}__api_key_dual_type.sql

-- 新增 key_type 字段：PERMANENT / TEMPORARY
ALTER TABLE `api_key`
    ADD COLUMN `key_type` VARCHAR(16) NOT NULL DEFAULT 'TEMPORARY' COMMENT 'Key type: PERMANENT or TEMPORARY'
    AFTER `name`;

-- 新增 user_id 字段：永久 key 关联的用户 ID
ALTER TABLE `api_key`
    ADD COLUMN `user_id` BIGINT NULL COMMENT 'Associated user ID (for PERMANENT keys)'
    AFTER `key_type`;

-- 新增 raw_key_encrypted 字段：AES 加密后的 rawKey（仅永久 key 使用）
-- 永久 key 需要在登录时返回 rawKey 给前端，因此需要加密存储原始值
ALTER TABLE `api_key`
    ADD COLUMN `raw_key_encrypted` VARCHAR(256) NULL COMMENT 'AES-encrypted raw key (for PERMANENT keys only)'
    AFTER `user_id`;

-- 为 key_type 和 user_id 添加索引
ALTER TABLE `api_key` ADD INDEX idx_key_type (`key_type`);
ALTER TABLE `api_key` ADD INDEX idx_user_id (`user_id`);

-- 唯一约束：每个用户只能有一个永久 key
ALTER TABLE `api_key` ADD UNIQUE INDEX uk_user_permanent (`user_id`, `key_type`);

-- 将现有 name 字段的 UNIQUE 约束去掉（永久 key 的 name 格式统一，可能冲突）
-- 注：原表 name 是 UNIQUE 的，这里改为普通索引
ALTER TABLE `api_key` DROP INDEX `name`;
ALTER TABLE `api_key` ADD INDEX idx_name (`name`);
```

### 3.2 权限范围（Scopes）简化

权限范围仅区分两类，后续可扩展：

| Scope | 说明 | 适用场景 |
|-------|------|----------|
| `chat` | 仅支持对话相关接口 | 永久 Key、Channel 系统 Key |
| `manager` | 可管理 sandbox 等资源（**暂不实现**，预留字段） | 高级管理 Key |

> **说明：** 当前阶段所有 Key 的 scopes 统一设为 `chat`。`manager` scope 作为预留字段，后续实现 sandbox 管理能力时再启用。

### 3.3 历史数据迁移

```sql
-- 将现有的登录生成 key 标记为 TEMPORARY 并设为 active=0（历史数据清理）
-- 这些 key 都已过期或即将过期，不再有效
UPDATE `api_key` SET `key_type` = 'TEMPORARY' WHERE `key_type` IS NULL OR `key_type` = '';

-- 可选：清理已过期的历史登录 key
UPDATE `api_key` SET `active` = 0 WHERE `expires_at` < NOW() AND `active` = 1;
```

### 3.4 `ApiKeyEntity` 实体变更

```kotlin
// harnax-entity: ApiKeyEntity.kt 新增字段

/** Key type: PERMANENT or TEMPORARY or SYSTEM */
var keyType: String = "TEMPORARY"

/** Associated user ID (for PERMANENT keys) */
var userId: Long? = null

/** AES-encrypted raw key (for PERMANENT/SYSTEM keys, used to return rawKey at login or service startup) */
var rawKeyEncrypted: String? = null

/** Service name (for SYSTEM keys, e.g. channel-service) */
var serviceName: String? = null
```

> **设计说明：** 永久 key 和系统 key 的 rawKey 加密后存在 `api_key` 表自身，不污染 `sys_user` 表。
> 临时 key 不需要存储 rawKey（创建时一次性返回，之后只用 hash 验证）。

### 3.5 `ApiKeyMapper` 新增方法

```kotlin
// ApiKeyMapper.kt 新增

/** 根据用户 ID 查询永久 key */
fun selectPermanentKeyByUserId(@Param("userId") userId: Long): ApiKeyEntity?

/** 查询用户的所有临时 key（分页列表用） */
fun selectTemporaryKeys(
    @Param("keyword") keyword: String?,
    @Param("enabled") enabled: Int?,
    @Param("creator") creator: String?,
    @Param("tenantId") tenantId: Long?,
): List<ApiKeyEntity>

/** 根据服务名查询系统级 key */
fun selectSystemKeyByServiceName(@Param("serviceName") serviceName: String): ApiKeyEntity?
```

---

## 4. 后端改造

### 4.1 永久 Key 生成：用户创建时自动生成

**改造文件：** `SysUserServiceImpl.kt`

```kotlin
// 在 createUser() 方法中，用户创建成功后自动生成永久 API Key

@Transactional(rollbackFor = [Exception::class])
override fun createUser(request: SysUserCreateRequest): Boolean {
    // ... 现有的用户创建逻辑 ...
    val success = sysUserMapper.insert(user) > 0

    if (success) {
        // 自动为新用户生成永久 API Key
        apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId)
    }

    return success
}
```

**改造文件：** `ApiKeyServiceImpl.kt` 新增方法

```kotlin
/**
 * 为用户创建永久 API Key。
 * 每个用户有且仅有一个永久 key，跟随用户生命周期。
 */
@Transactional(rollbackFor = [Exception::class])
fun createPermanentKeyForUser(userId: Long, username: String, tenantId: Long?): ApiKeyCreatedResponse {
    val rawKey = generateRawKey()
    val keyHash = sha256(rawKey)
    val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
    val encrypted = aesUtil.encrypt(rawKey)

    val entity = ApiKeyEntity().apply {
        name = "permanent_${username}"
        keyType = "PERMANENT"
        this.userId = userId
        this.keyHash = keyHash
        this.keyPrefix = keyPrefix
        scopes = "chat"
        this.tenantId = tenantId
        rateLimit = 300
        enabled = 1
        expiresAt = null  // 永不过期
        rawKeyEncrypted = encrypted  // AES 加密的 rawKey，登录时解密返回前端
        creator = "system"
        active = 1
        createTime = LocalDateTime.now()
        updateTime = LocalDateTime.now()
    }

    apiKeyMapper.insert(entity)
    log.info("Permanent API Key created for user: userId={}, username={}", userId, username)

    return ApiKeyCreatedResponse(
        id = entity.id,
        name = entity.name,
        rawKey = rawKey,
        keyPrefix = keyPrefix,
    )
}
```

### 4.2 登录流程改造：返回永久 Key，不再新建

**改造文件：** `AuthServiceImpl.kt`（Web 登录）

```kotlin
// 替换原有的 "每次登录生成新 key" 逻辑
// 旧代码（删除）：
//   val rawKey = generateRawKey()
//   val keyHash = sha256(rawKey)
//   ... apiKeyMapper.insert(apiKey)

// 新代码：查询用户的永久 key，解密 rawKey 并返回
val rawKey = apiKeyService.getPermanentRawKey(user.id)
    ?: throw BizException("Permanent API Key not found for user: ${user.username}")

val response = LoginResponse.builder()
    .accessToken(accessToken)
    .tokenType("Bearer")
    .expiresIn(jwtUtil.getExpirationTime() / 1000)
    .expiresAt(expiresAt)
    .userInfo(userInfo)
    .tenants(userTenants)
    .currentTenantId(defaultTenantId)
    .routerApiKey(rawKey)  // 返回永久 key 的 rawKey
    .build()
```

> **rawKey 存储策略：** 永久 key 的 rawKey 经 AES-256 加密后存在 `api_key.raw_key_encrypted` 字段。
> 登录时从该字段解密取出 rawKey 返回给前端。临时 key 不需要此字段。
> 这样所有 API Key 相关数据都集中在 `api_key` 表中，不污染 `sys_user` 表。

### 4.3 登录改造详细实现

**改造文件：** `AuthServiceImpl.kt`（Web 登录）、`MpAuthService.kt`（移动端登录）

```kotlin
// login() 方法中，删除旧代码块：
// val rawKey = generateRawKey()
// val keyHash = sha256(rawKey)
// val apiKey = ApiKeyEntity().apply { ... }
// apiKeyMapper.insert(apiKey)

// 替换为：
val rawKey = apiKeyService.getPermanentRawKey(user.id)
    ?: throw BizException("Permanent API Key not found for user: ${user.username}")

// LoginResponse / MpLoginResponse 中的 routerApiKey 直接赋值 rawKey
```

**`ApiKeyServiceImpl` 新增方法：**

```kotlin
/**
 * 获取用户永久 key 的 rawKey（从 api_key 表解密返回）。
 * 仅在登录时调用。
 */
fun getPermanentRawKey(userId: Long): String? {
    val entity = apiKeyMapper.selectPermanentKeyByUserId(userId) ?: return null
    if (entity.enabled != 1) {
        throw BizException("Your API Key has been disabled, please contact administrator")
    }
    val encrypted = entity.rawKeyEncrypted ?: return null
    return aesUtil.decrypt(encrypted)
}
```

**同理改造 `MpAuthService.kt` 的 `login()` 方法。**

### 4.4 永久 Key 重置

**改造文件：** `ApiKeyServiceImpl.kt`

```kotlin
/**
 * 重置用户的永久 API Key。
 * 生成新的 rawKey，更新 api_key 表中的 keyHash 和 rawKeyEncrypted。
 * 所有改动集中在 api_key 表内，不涉及 sys_user 表。
 */
@Transactional(rollbackFor = [Exception::class])
fun regeneratePermanentKey(userId: Long): ApiKeyCreatedResponse {
    val entity = apiKeyMapper.selectPermanentKeyByUserId(userId)
        ?: throw RuntimeException("Permanent API Key not found for user: $userId")

    val rawKey = generateRawKey()
    val keyHash = sha256(rawKey)
    val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
    val encrypted = aesUtil.encrypt(rawKey)

    // 更新 api_key 表（keyHash + rawKeyEncrypted 一起更新）
    entity.keyHash = keyHash
    entity.keyPrefix = keyPrefix
    entity.rawKeyEncrypted = encrypted
    entity.updateTime = LocalDateTime.now()
    apiKeyMapper.updateById(entity)

    log.info("Permanent API Key regenerated for userId={}", userId)

    return ApiKeyCreatedResponse(
        id = entity.id,
        name = entity.name,
        rawKey = rawKey,
        keyPrefix = keyPrefix,
    )
}
```

### 4.5 临时 Key 管理：限制操作范围

**改造文件：** `ApiKeyServiceImpl.kt`

对现有的 CRUD 方法添加 `keyType` 校验（PERMANENT 和 SYSTEM 类型的 key 都不允许通过普通接口操作）：

```kotlin
private val PROTECTED_KEY_TYPES = setOf("PERMANENT", "SYSTEM")

override fun createApiKey(request: ApiKeyCreateRequest): ApiKeyCreatedResponse {
    // ... 现有逻辑 ...
    val entity = ApiKeyEntity().apply {
        // ... 其他字段 ...
        keyType = "TEMPORARY"  // 手动创建的 key 都是临时的
        userId = null           // 临时 key 不关联用户
        serviceName = null      // 临时 key 不关联服务
        scopes = "chat"         // 当前只支持 chat 权限，manager 暂不实现
    }
    // ...
}

override fun deleteApiKey(id: Long): Boolean {
    val entity = loadAndCheckAccess(id)
    if (entity.keyType in PROTECTED_KEY_TYPES) {
        throw RuntimeException("${entity.keyType} API Key cannot be deleted, use regenerate instead")
    }
    // ... 现有删除逻辑 ...
}

override fun updateApiKey(id: Long, request: ApiKeyUpdateRequest): Boolean {
    val entity = loadAndCheckAccess(id)
    if (entity.keyType in PROTECTED_KEY_TYPES) {
        throw RuntimeException("${entity.keyType} API Key cannot be modified, use regenerate instead")
    }
    // ... 现有更新逻辑 ...
}

override fun toggleEnabled(id: Long, enabled: Int): Boolean {
    val entity = loadAndCheckAccess(id)
    if (entity.keyType in PROTECTED_KEY_TYPES) {
        throw RuntimeException("${entity.keyType} API Key cannot be disabled")
    }
    // ... 现有逻辑 ...
}
```

### 4.6 API Key 列表接口改造

**改造文件：** `ApiKeyController.kt` / `ApiKeyServiceImpl.kt`

API Key 管理页面**只展示临时 key**（PERMANENT key 不在列表中管理）：

```kotlin
override fun page(...): Page<ApiKeyEntity> {
    PageHelper.startPage<ApiKeyEntity>(pageNum, pageSize)
    // 只查询临时 key
    return Page.fromPageInfo(apiKeyMapper.selectTemporaryKeys(keyword, enabled, creator, tenantId))
}
```

新增永久 key 查看/重置接口：

```kotlin
// ApiKeyController.kt 新增

@GetMapping("/my-permanent-key")
@Operation(summary = "Get my permanent API Key info")
fun getMyPermanentKey(): ResultVo<ApiKeyResponse?> = try {
    val userId = SecurityUtils.getCurrentUser()?.id
        ?: throw RuntimeException("Not authenticated")
    val entity = apiKeyMapper.selectPermanentKeyByUserId(userId)
    ResultVo.success(entity?.let { apiKeyService.convertToResponse(it) })
} catch (e: Exception) {
    ResultVo.error(e.message ?: "Failed to get permanent key")
}

@PostMapping("/regenerate-permanent")
@Operation(summary = "Regenerate my permanent API Key")
fun regenerateMyPermanentKey(): ResultVo<ApiKeyCreatedResponse> = try {
    val userId = SecurityUtils.getCurrentUser()?.id
        ?: throw RuntimeException("Not authenticated")
    val result = apiKeyService.regeneratePermanentKey(userId)
    ResultVo.success(result)
} catch (e: Exception) {
    ResultVo.error(e.message ?: "Failed to regenerate permanent key")
}
```

---

## 5. Channel 调用 Router 改造

### 5.1 当前方式

Channel Service 通过 `InternalTokenProvider` 使用**共享密钥 JWT** 认证调用 Router：

```kotlin
// RouterClient.kt - 当前
private val tokenProvider: InternalTokenProvider
// WebClient / RestClient 请求头：Authorization: Bearer <JWT>
```

`ChannelConfig.kt` 中 `webClient()` 和 `restClient()` 都注入了 `InternalTokenProvider` 并自动添加 JWT 头。

### 5.2 改造方案：使用系统级永久 Key

**设计原则：** Router 的所有上游客户端（Web、Mobile、Channel）统一使用 `X-Api-Key` 认证。仅 Router → Agent-Service 保留内部 JWT。

**方案：** 为 Channel 服务创建一个**系统级永久 Key**，通过配置文件注入。

#### 5.2.1 系统级永久 Key 设计

新增 `key_type = SYSTEM` 类型，与普通永久 key 区分：

| key_type | 说明 | 关联对象 |
|----------|------|----------|
| `PERMANENT` | 用户永久 key | 关联 `user_id` |
| `TEMPORARY` | 临时 key（外部系统） | 不关联用户 |
| `SYSTEM` | 系统级永久 key（Channel 等服务） | 不关联用户 |

> `SYSTEM` key 与 `PERMANENT` key 规则相同：永不过期、不可删除/修改、仅允许重置。区别在于它不关联用户，而是标识一个服务。

#### 5.2.2 数据库新增

```sql
-- api_key 表新增 service_name 字段，标识系统级 key 所属服务
ALTER TABLE `api_key`
    ADD COLUMN `service_name` VARCHAR(64) NULL COMMENT 'Service name (for SYSTEM keys, e.g. channel-service)'
    AFTER `raw_key_encrypted`;

ALTER TABLE `api_key` ADD INDEX idx_service_name (`service_name`);

-- 唯一约束：每个服务只能有一个 SYSTEM key
ALTER TABLE `api_key` ADD UNIQUE INDEX uk_service_system (`service_name`, `key_type`);
```

#### 5.2.3 初始化系统 Key

在 Admin 启动时自动创建 Channel 的系统级 key：

```kotlin
// PermanentKeyInitializer.kt 扩展
fun initSystemKeys() {
    val systemServices = listOf("channel-service")
    for (serviceName in systemServices) {
        val existing = apiKeyMapper.selectSystemKeyByServiceName(serviceName)
        if (existing == null) {
            val rawKey = generateRawKey()
            val keyHash = sha256(rawKey)
            val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.takeLast(4)
            val encrypted = aesUtil.encrypt(rawKey)

            val entity = ApiKeyEntity().apply {
                name = "system_${serviceName}"
                keyType = "SYSTEM"
                this.serviceName = serviceName
                this.keyHash = keyHash
                this.keyPrefix = keyPrefix
                scopes = "chat"  // Channel 只需要对话权限
                rateLimit = 600
                enabled = 1
                expiresAt = null
                rawKeyEncrypted = encrypted
                creator = "system"
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }
            apiKeyMapper.insert(entity)
            log.info("System API Key created for service: {}", serviceName)
        }
    }
}
```

#### 5.2.4 系统 Key 下发到 Channel 服务

系统级 key 创建后，需要将 rawKey 配置到 Channel 服务中。有两种方式：

**方式 A：通过 Admin 管理接口获取（推荐）**

```kotlin
// InternalApiController.kt 新增内部接口
@PostMapping("/internal/api-keys/system-key")
fun getSystemKey(@RequestBody request: SystemKeyRequest): ResultVo<SystemKeyResponse> {
    val entity = apiKeyMapper.selectSystemKeyByServiceName(request.serviceName)
        ?: throw RuntimeException("System key not found for: ${request.serviceName}")
    val rawKey = aesUtil.decrypt(entity.rawKeyEncrypted!!)
    return ResultVo.success(SystemKeyResponse(rawKey = rawKey, keyPrefix = entity.keyPrefix))
}
```

Channel 启动时调用此接口获取 rawKey 并缓存。

**方式 B：手动配置（简单）**

管理员从 Admin 后台查看系统 key 的 rawKey，手动写入 Channel 的环境变量 `CHANNEL_API_KEY`。

#### 5.2.5 Channel 端改造

**`application.yml` 新增配置：**

```yaml
channel:
  router-api-key: ${CHANNEL_API_KEY:}
  # 如果为空，启动时从 Admin 获取
  auto-fetch-system-key: true
  admin:
    url: ${ADMIN_SERVICE_URL:http://localhost:8080}
    secret: ${ADMIN_INTERNAL_API_SECRET:}
```

**`ChannelConfig.kt` 改造：**

```kotlin
@Configuration
class ChannelConfig(
    @Value("\${channel.router-api-key:}")
    private val routerApiKey: String,
    @Value("\${channel.proxy.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Int,
    @Value("\${channel.proxy.response-timeout-ms:120000}")
    private val responseTimeoutMs: Int,
) {
    // 移除 InternalTokenProvider 依赖

    @Bean
    fun webClient(): WebClient {
        // ...
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .filter(apiKeyFilter())  // 改用 X-Api-Key
            .build()
    }

    @Bean
    fun restClient(): RestClient {
        // ...
        return RestClient.builder()
            .requestFactory(factory)
            .requestInterceptor { request, body, execution ->
                request.headers.add("X-Api-Key", resolvedApiKey)  // 改用 X-Api-Key
                execution.execute(request, body)
            }
            .build()
    }

    private fun apiKeyFilter(): ExchangeFilterFunction = ExchangeFilterFunction { request, next ->
        val mutated = ClientRequest.from(request)
        mutated.header("X-Api-Key", resolvedApiKey)
        next.exchange(mutated.build())
    }
}
```

**`RouterClient.kt` 改造：**

```kotlin
@Service
class RouterClient(
    private val webClient: WebClient,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    // 移除 InternalTokenProvider 依赖
    @Value("\${router.service.url}") private val routerUrl: String,
) {
    // buildCurl() 中移除 JWT auth headers 日志
}
```

#### 5.2.6 Channel 获取系统 Key 的启动逻辑

```kotlin
@Component
class ChannelApiKeyInitializer(
    @Value("\${channel.router-api-key:}")
    private val configuredApiKey: String,
    @Value("\${channel.auto-fetch-system-key:true}")
    private val autoFetch: Boolean,
    @Value("\${channel.admin.url:http://localhost:8080}")
    private val adminUrl: String,
    @Value("\${channel.admin.secret:}")
    private val adminSecret: String,
) {
    private val log = LoggerFactory.getLogger(ChannelApiKeyInitializer::class.java)

    @Bean
    fun channelRouterApiKey(): ChannelRouterApiKey {
        val key = if (configuredApiKey.isNotBlank()) {
            log.info("Using configured channel router API key")
            configuredApiKey
        } else if (autoFetch) {
            log.info("Auto-fetching system API key from admin")
            fetchSystemKeyFromAdmin()
        } else {
            throw IllegalStateException("Channel router API key not configured")
        }
        return ChannelRouterApiKey(key)
    }

    private fun fetchSystemKeyFromAdmin(): String {
        // 调用 admin POST /api/admin/internal/api-keys/system-key
        // 返回 rawKey
        // ...
    }
}

/** 持有 Channel 调用 Router 时使用的 API Key */
class ChannelRouterApiKey(val rawKey: String)
```

---

## 6. 前端改造

### 6.1 Web UI（harnax-webui）

**登录流程无需改动**：登录接口返回的 `routerApiKey` 现在是永久 key，前端照常存入 `localStorage`。

**API Key 管理页面改造：**

- 列表只展示**临时 key**
- 新增 "我的永久 Key" 区域：
  - 展示 keyPrefix（脱敏展示）
  - 提供"重置永久 Key"按钮
  - 重置后弹窗展示新的 rawKey，提示用户保存
- 创建 key 时自动标记为临时类型

**涉及文件：**
- `src/pages/api-key/index.tsx` — 列表改造
- `src/pages/api-key/components/CreateForm.tsx` — 无需改动（创建的 key 后端自动标记为 TEMPORARY）

### 6.2 移动端（harnax-app）

**登录流程无需改动**：与 Web UI 同理，登录返回的 `routerApiKey` 变为永久 key。

---

## 7. 加密工具类

新增 AES 加密工具，用于 `api_key.raw_key_encrypted` 的加密/解密（仅永久 key 使用）：

```kotlin
// harnax-admin: AesUtil.kt

@Component
class AesUtil(
    @Value("\${harnax.aes.secret-key:change-me-32-chars-secret-key!!}")
    private val secretKey: String,
) {
    private val cipher = "AES/GCM/NoPadding"
    private val keySpec = SecretKeySpec(secretKey.toByteArray().copyOf(32), "AES")

    fun encrypt(plainText: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(cipher)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray())
        // iv + encrypted 拼接后 Base64 编码
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(encryptedBase64: String): String {
        val decoded = Base64.getDecoder().decode(encryptedBase64)
        val iv = decoded.copyOfRange(0, 12)
        val data = decoded.copyOfRange(12, decoded.size)
        val cipher = Cipher.getInstance(cipher)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data))
    }
}
```

---

## 8. Router 端改造

Router 的 `RemoteApiKeyStore` **无需改动**。

- `RemoteApiKeyStore` 通过 `keyHash` 查询 `api_key` 表验证 key 有效性，不区分 key 类型
- 永久 key、系统 key、临时 key 的验证逻辑完全一致（查 hash → 检查 enabled 和 expiresAt）
- 永久 key / 系统 key 的 `expiresAt = null`，`isExpired()` 永远返回 `false`

**`UnifiedAuthFilter` 调整：**

由于 Channel 不再使用 JWT 调用 Router，Router 的 `UnifiedAuthFilter` 中 `Authorization: Bearer JWT` 仅用于 Router → Agent-Service 的内部调用（这是 Router 作为客户端的出站调用，不经过自身的 Filter）。

因此，Router 的入站请求认证变为：
- `X-Api-Key` 头 → 所有上游客户端（Web、Mobile、Channel、外部第三方）
- `Authorization: Bearer JWT` 保留兼容（如有其他内部服务调用 Router 的场景）

---

## 9. 改造文件清单

### 后端

| 模块 | 文件 | 改动说明 |
|------|------|---------|
| harnax-entity | `ApiKeyEntity.kt` | 新增 `keyType`、`userId`、`rawKeyEncrypted`、`serviceName` 字段 |
| harnax-entity | `ApiKeyMapper.kt` | 新增 `selectPermanentKeyByUserId`、`selectTemporaryKeys`、`selectSystemKeyByServiceName` |
| harnax-entity | `ApiKeyMapper.xml` | 新增对应 SQL |
| harnax-admin | Flyway migration | 新增 migration 文件 |
| harnax-admin | `ApiKeyServiceImpl.kt` | 新增 `createPermanentKeyForUser`、`getPermanentRawKey`、`regeneratePermanentKey`、`initSystemKeys`；CRUD 方法添加 keyType 校验 |
| harnax-admin | `ApiKeyService.kt` | 接口新增方法声明 |
| harnax-admin | `ApiKeyController.kt` | 新增永久 key 查看/重置接口 |
| harnax-admin | `InternalApiController.kt` | 新增 `POST /internal/api-keys/system-key` 接口供 Channel 启动时获取系统 key |
| harnax-admin | `AuthServiceImpl.kt` | 登录不再新建 key，改为查询永久 key |
| harnax-admin | `MpAuthService.kt` | 同上 |
| harnax-admin | `SysUserServiceImpl.kt` | `createUser` 时自动生成永久 key |
| harnax-admin | `PermanentKeyInitializer.kt`（新建） | 启动时为存量用户补建永久 key + 初始化系统 key |
| harnax-admin | `AesUtil.kt`（新建） | AES 加密工具类 |
| harnax-admin | `application.yml` | 新增 `harnax.aes.secret-key` 配置 |
| harnax-channel-service | `ChannelConfig.kt` | 移除 `InternalTokenProvider`，改用 `X-Api-Key` 头 |
| harnax-channel-service | `RouterClient.kt` | 移除 `InternalTokenProvider` 依赖和 JWT 日志 |
| harnax-channel-service | `ChannelApiKeyInitializer.kt`（新建） | 启动时获取系统 key |
| harnax-channel-service | `application.yml` | 新增 `channel.router-api-key` 等配置 |

### 前端

| 模块 | 文件 | 改动说明 |
|------|------|---------|
| harnax-webui | `api-key/index.tsx` | 列表只展示临时 key；新增"我的永久 Key"区域 |
| harnax-webui | API service 文件 | 新增永久 key 查看/重置 API 调用 |

### 不需要改动

| 模块 | 说明 |
|------|------|
| harnax-session-router | `RemoteApiKeyStore` 无需改动（统一用 keyHash 验证） |
| harnax-session-router | Router → Agent-Service 的 `InternalTokenProvider` JWT 认证保持不变 |
| harnax-auth | `UnifiedAuthFilter`、`InternalTokenProvider` 无需改动 |
| harnax-app | 登录流程无变化，`routerApiKey` 字段照常使用 |

---

## 10. 实施步骤

1. **数据库 Migration** — 新增 Flyway 脚本，在 `api_key` 表添加 `key_type`、`user_id`、`raw_key_encrypted`、`service_name` 字段
2. **Entity / Mapper 层** — 更新 `ApiKeyEntity`（新增 4 个字段）、对应 Mapper XML
3. **加密工具** — 新建 `AesUtil`
4. **永久 Key 生成逻辑** — `ApiKeyServiceImpl` 新增方法，`SysUserServiceImpl.createUser` 中调用
5. **系统 Key 初始化** — `PermanentKeyInitializer` 初始化系统级 key（channel-service）
6. **登录流程改造** — `AuthServiceImpl` 和 `MpAuthService` 改为查询永久 key
7. **Channel 认证改造** — `ChannelConfig` / `RouterClient` 移除 `InternalTokenProvider`，改用 `X-Api-Key`
8. **临时 Key CRUD 限制** — `ApiKeyServiceImpl` 各方法添加 keyType 校验
9. **Controller 层** — 新增永久 key 管理接口、系统 key 获取接口，列表接口只展示临时 key
10. **前端改造** — API Key 管理页面适配
11. **历史数据迁移** — 清理历史登录 key，为存量用户生成永久 key
12. **测试验证** — 登录、Channel→Router、Web→Router、Key 管理等全链路验证

---

## 11. 存量用户永久 Key 初始化

对于已存在的用户（改造前创建的用户），需要通过数据迁移脚本或启动时 Runner 为其补建永久 key：

```kotlin
// 新增 CommandLineRunner：PermanentKeyInitializer
@Component
class PermanentKeyInitializer(
    private val sysUserMapper: SysUserMapper,
    private val apiKeyService: ApiKeyServiceImpl,
    private val apiKeyMapper: ApiKeyMapper,
) : CommandLineRunner {

    override fun run(vararg args: String) {
        // 查询所有没有永久 key 的活跃用户
        val allUsers = sysUserMapper.selectAllActive()
        for (user in allUsers) {
            val existing = apiKeyMapper.selectPermanentKeyByUserId(user.id)
            if (existing == null) {
                apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId)
                log.info("Permanent key initialized for user: {}", user.username)
            }
        }
    }
}
```

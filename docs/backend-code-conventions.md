# Harnax 后端代码规范

本文档基于 `harnax-admin` 模块现有代码与开发实践整理，适用于 Harnax 平台所有基于 Spring Boot + Kotlin 的后端模块。

**文档版本**: v1.0
**适用范围**: `harnax-admin`、`harnax-entity`、`harnax-common` 等后端模块
**相关文档**: [数据库设计规范](./database-design-conventions.md)

## 基本原则

| 项目 | 要求 |
|------|------|
| 代码注释 | 全部使用**英文**（KDoc、行内注释、XML 注释） |
| 日志内容 | 全部使用**英文** |
| API 注解 | `@Schema`、`@Operation`、`@Parameter` 等描述使用**英文** |
| 用户可见消息 | 错误提示、成功提示必须支持**国际化（i18n）** |
| 开发语言 | Kotlin，充分利用空安全、data class、扩展函数等特性 |

## 一、模块与包结构

### 1.1 模块划分

后端代码按职责分布在三个 Maven 模块中：

| 模块 | 职责 | 主要内容 |
|------|------|----------|
| `harnax-admin` | Web 应用 | Controller、Service、DTO、配置、拦截器、工具类 |
| `harnax-entity` | 数据访问 | Entity、Mapper 接口、Mapper XML |
| `harnax-common` | 公共组件 | `ResultVo` 统一响应、公共异常、公共工具 |

### 1.2 包结构

```
harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/
├── controller/          # 控制器（管理端接口统一在此，移动端在 controller/mp）
├── service/             # Service 接口
│   └── impl/            # Service 实现
├── dto/                 # 数据传输对象（Request/Response 平铺，移动端在 dto/mp）
│   ├── request/         # 请求 DTO（新增模块可分子包）
│   └── response/        # 响应 DTO（新增模块可分子包）
├── exception/           # BizException、GlobalExceptionHandler
├── interceptor/         # 拦截器（JWT、租户）
├── context/             # 上下文（TenantContext 等）
├── i18n/                # 国际化（MessageUtil）
├── config/              # 配置类
├── security/            # 安全相关
├── util/                # 工具类
└── constant/            # 常量

harnax-entity/src/main/kotlin/com/agnetix/harnax/
├── entity/              # 实体类（与数据表一一对应）
└── mapper/              # Mapper 接口

harnax-entity/src/main/resources/mapper/
└── XXXMapper.xml        # Mapper XML（与 Mapper 接口同名）

harnax-common/src/main/kotlin/com/agnetix/harnax/common/
└── dto/                 # ResultVo 等公共 DTO
```

## 二、分层架构与职责

```
Controller 层（接收请求、参数校验、封装响应）
    ↓
Service 层（业务逻辑、事务、业务校验）
    ↓
Mapper 层（数据库 CRUD、SQL 映射）
    ↓
MySQL
```

### 2.1 Controller 层

- 接收 HTTP 请求，解析参数，使用 `@Valid` 触发参数校验
- 调用 Service 处理业务，将结果封装为 `ResultVo` 返回
- 使用 try-catch 捕获异常并记录英文日志
- **禁止**：编写业务逻辑、直接调用 Mapper、返回裸对象

### 2.2 Service 层

- 处理核心业务逻辑与业务校验（唯一性、关联存在性、字段联动）
- 使用 `@Transactional(rollbackFor = [Exception::class])` 管理事务
- 调用 Mapper 进行数据操作
- **禁止**：处理 HTTP 细节、返回 `ResultVo`

### 2.3 Mapper 层

- 仅负责数据库 CRUD 与 SQL 映射（XML 优先）
- **禁止**：包含业务逻辑、声明事务

## 三、命名规范

### 3.1 通用命名

| 对象 | 规则 | 示例 |
|------|------|------|
| 包名 | 全小写点分隔 | `com.agnetix.harnax.admin` |
| 类名 | PascalCase | `AgentServiceImpl` |
| 方法名 | camelCase，动词开头 | `getAgentById` |
| 变量名 | camelCase | `currentUsername` |
| 常量 | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |

### 3.2 类命名约定

| 类型 | 命名模式 | 示例 |
|------|----------|------|
| 实体类 | 业务名词（与表名对应），系统表可加 `Entity` 后缀 | `Agent`、`McpServer`、`TenantEntity` |
| Mapper | `XXXMapper` | `AgentMapper` |
| Service 接口 | `XXXService` | `AgentService` |
| Service 实现 | `XXXServiceImpl` | `AgentServiceImpl` |
| Controller | `XXXController` | `AgentController` |
| 创建请求 | `XXXCreateRequest` | `McpServerCreateRequest` |
| 更新请求 | `XXXUpdateRequest` | `McpServerUpdateRequest` |
| 响应 | `XXXResponse` | `McpServerResponse` |
| 单元测试 | `XXXServiceImplTest` / `XXXMapperTest` | `AgentServiceImplTest` |

## 四、实体（Entity）规范

- 位于 `harnax-entity` 模块 `com.agnetix.harnax.entity` 包
- 普通 `class` 实现 `Serializable`，声明 `serialVersionUID`
- 可变字段使用 `var`，带业务默认值（`status = 1`、`active = 1` 等）
- 每个字段加 KDoc 注释 + `@Schema(description = ...)`
- 必须包含通用字段：`id`、`status`、`active`、`createTime`、`updateTime`
- 业务表必须包含 `tenantId`；需要数据权限的表包含 `isPublic`、`creator`

```kotlin
package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * MCP server entity
 */
@Schema(description = "MCP server entity")
class McpServer : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * MCP ID
     */
    @Schema(description = "MCP ID")
    var id: Long = 0

    /**
     * Tenant ID
     */
    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    /**
     * Status (0:disabled, 1:enabled)
     */
    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    /**
     * Active status (0:deleted, 1:active)
     */
    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    /**
     * Creation time
     */
    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    /**
     * Update time
     */
    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()

    // ... business fields
}
```

## 五、DTO 规范

DTO 均为 `data class`，每个字段必须有 `@Schema` 描述（必填字段加 `requiredMode`）与 `example`。

### 5.1 创建请求（XXXCreateRequest）

- **不包含** `id`、`active`、`createTime`、`updateTime`（由后台生成）
- 必填字段使用 `@NotBlank` / `@NotNull`，长度限制使用 `@Size`
- 校验 `message` 使用英文

```kotlin
@Schema(description = "MCP server creation request object")
data class McpServerCreateRequest(
    @Schema(description = "MCP name", example = "my-mcp-server", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MCP name cannot be empty")
    @Size(max = 100, message = "MCP name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "MCP description", example = "This is an MCP server")
    val description: String? = null,
)
```

### 5.2 更新请求（XXXUpdateRequest）

- **不包含** `status`、`active`、`createTime`、`updateTime`
- 所有业务字段可空（`val xxx: Type? = null`），支持部分更新
- 长度/格式校验只约束「有值」的字段

### 5.3 响应（XXXResponse）

- 包含除敏感字段（`password`、原始 `apiKey`、密钥值等）外的所有字段
- 提供 `companion object { fun fromEntity(...) }` 转换方法；需要解密/反序列化时由 Service 注入协作者并传入
- 敏感配置字段（如 headers、envParams）在响应中需做脱敏处理（`secret: true` 的条目不返回明文）

### 5.4 常用校验注解

| 注解 | 用途 |
|------|------|
| `@NotNull` | 不能为 null |
| `@NotBlank` | 非 null 且非空白字符串 |
| `@NotEmpty` | 非 null 且非空集合 |
| `@Size(max = n)` | 长度/大小上限 |
| `@Min` / `@Max` | 数值范围 |
| `@Email` / `@Pattern` | 格式校验 |

## 六、Controller 层规范

### 6.1 类级注解

```kotlin
@RestController
@RequestMapping("/api/admin/mcp")
@Tag(name = "MCP Server Management", description = "MCP server related APIs")
class McpServerController(
    private val mcpServerService: McpServerService,
) {
    private val log = LoggerFactory.getLogger(McpServerController::class.java)
}
```

- 必须使用构造器注入依赖
- 必须声明类级 `log`
- 管理端接口统一使用 `/api/admin/{资源}` 前缀，资源名使用**复数小写短横线**风格（`agents`、`agent-tasks`、`api-keys`、`model-providers`）

### 6.2 方法与参数注解

- 每个接口必须有 `@Operation(summary, description)`
- 每个参数必须有 `@Parameter(description)`，并给出 `example`（适用时）
- 请求体使用 `@Valid @RequestBody`；可选查询参数必须 `required = false` 并给默认值

### 6.3 接口 URL 规范

| 操作 | HTTP 方法 | URL 模式 | 示例 |
|------|-----------|----------|------|
| 分页查询 | GET | `/page` | `GET /api/admin/agents/page` |
| 获取详情 | GET | `/{id}` | `GET /api/admin/agents/1` |
| 创建 | POST | `/` | `POST /api/admin/agents` |
| 更新 | PUT | `/update/{id}` | `PUT /api/admin/agents/update/1` |
| 切换状态 | PUT | `/toggle/{id}` | `PUT /api/admin/agents/toggle/1` |
| 删除（逻辑） | DELETE | `/{id}` | `DELETE /api/admin/agents/1` |
| 动作类接口 | POST | `/{id}/{动作}` | `POST /api/admin/mcp/{id}/connectivity-test` |

### 6.4 统一响应与异常处理

所有接口返回 `com.agnetix.harnax.common.dto.ResultVo<T>`，方法体使用 try-catch 包裹：

```kotlin
@GetMapping("/page")
@Operation(summary = "Get MCP server list with pagination", description = "Paginated query for MCP server information")
fun pageMcpServer(
    @Parameter(description = "Page number", example = "1")
    @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
    @Parameter(description = "Page size", example = "10")
    @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
): ResultVo<Page<McpServerResponse>> = try {
    val page = mcpServerService.page(keyword, status, type, pageNum ?: 1, pageSize ?: 10)
    ResultVo.success(page.mapRecords { mcpServerService.convertToResponse(it) })
} catch (e: Exception) {
    log.error("Failed to get MCP server list", e)
    ResultVo.error(e.message ?: "Failed to get MCP server list")
}
```

`ResultVo` 结构：

```kotlin
data class ResultVo<T>(
    val code: Int = 200,                              // 200 success / 400 business error / 500 system error
    val message: String = "success",
    val data: T? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
// ResultVo.success() / success(data) / success(message, data)
// ResultVo.error(message)（默认 500） / error(code, message)
```

**原则**：

- 捕获所有异常，避免堆栈信息泄露给前端
- 日志记录用英文，包含操作上下文
- 未捕获异常由 `GlobalExceptionHandler` 兜底

### 6.5 分页参数约定

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `pageNum` | Int | 1 | 页码 |
| `pageSize` | Int | 10 | 每页条数 |
| `keyword` / `name` | String? | null | 模糊搜索，为 null 不作为筛选条件 |
| `status` | Int? | null | 状态筛选，为 null 不作为筛选条件 |
| `type` | String? | null | 类型筛选，为 null 不作为筛选条件 |

## 七、Service 层规范

### 7.1 接口与实现分离

接口位于 `service/`，实现位于 `service/impl/`，实现类使用 `@Service` 与构造器注入：

```kotlin
@Service
class McpServerServiceImpl(
    private val jwtUtil: JwtUtil,
    private val mcpServerMapper: McpServerMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
) : McpServerService {
    private val log = LoggerFactory.getLogger(McpServerServiceImpl::class.java)
}
```

标准 CRUD 接口签名：

```kotlin
interface XXXService {
    fun page(keyword: String?, status: Int?, pageNum: Int, pageSize: Int): Page<XXX>
    fun getXXX(id: Long): XXX?
    fun createXXX(request: XXXCreateRequest): Boolean
    fun updateXXX(id: Long, request: XXXUpdateRequest): Boolean
    fun toggleXXXStatus(id: Long, status: Int): Boolean
    fun deleteXXX(id: Long): Boolean
    fun convertToResponse(entity: XXX): XXXResponse
}
```

### 7.2 分页查询

使用 PageHelper 分页，返回自定义 `Page<T>`（`com.agnetix.harnax.admin.dto.Page`）：

```kotlin
override fun page(keyword: String?, status: Int?, pageNum: Int, pageSize: Int): Page<McpServer> {
    val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
    val safePageNum = pageNum.coerceAtLeast(1)
    val safePageSize = pageSize.coerceIn(1, 1000)
    PageHelper.startPage<McpServer>(safePageNum, safePageSize)
    return Page.fromPageInfo(mcpServerMapper.selectMcpServerList(keyword, status, currentUsername))
}
```

**要求**：

- `PageHelper.startPage()` 必须紧贴 Mapper 查询调用
- 对 `pageNum`、`pageSize` 做边界保护（`coerceAtLeast` / `coerceIn`）
- 需要数据权限的查询必须传入当前用户名
- Controller 中用 `page.mapRecords { service.convertToResponse(it) }` 转换为响应对象

### 7.3 业务校验

**创建流程**：

1. 唯一性校验（如名称已存在 → `throw BizException(...)`）
2. 关联项存在性校验
3. 字段联动校验（如类型与必填字段的对应关系）
4. 敏感字段加密（`SecretFieldEncryptor`、BCrypt）
5. 设置 `tenantId`（`TenantContext.getTenantId() ?: 1`）与 `creator`（`UserContextUtil.getCurrentUsername(jwtUtil)`）
6. 插入并返回 `insert(...) > 0`

**更新流程**：

1. `selectById` 校验实体存在，不存在抛 `BizException`
2. 修改了唯一字段时校验新值不冲突
3. 按「有值字段」部分更新
4. 敏感字段重新加密
5. `updateTime` 由实体默认值或数据库自动维护

**删除流程**：

1. 校验实体存在
2. 校验无下游关联（有依赖时抛异常，禁止删除）
3. 逻辑删除（`active = 0`），**禁止物理删除**

### 7.4 Entity → Response 转换

二选一，保持模块内一致：

```kotlin
// 方式一：Response 提供静态转换方法（推荐，涉及解密时传入协作者）
override fun convertToResponse(mcpServer: McpServer): McpServerResponse =
    McpServerResponse.fromEntity(mcpServer, objectMapper, secretFieldEncryptor)

// 方式二：Service 提供 convertToResponse 内联转换
override fun convertToResponse(entity: XXX): XXXResponse = XXXResponse(
    id = entity.id,
    name = entity.name,
    // ...
)
```

## 八、Mapper 层规范

### 8.1 Mapper 接口

```kotlin
@Mapper
interface McpServerMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): McpServer?

    fun insert(mcpserver: McpServer): Int

    fun updateById(mcpserver: McpServer): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================

    fun selectMcpServerList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<McpServer>

    fun selectByName(@Param("name") name: String): McpServer?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
```

**方法命名**：

| 方法 | 含义 |
|------|------|
| `selectById` | 按 ID 查询（过滤 `active = 1`） |
| `selectByName` | 按名称查询（唯一性校验用） |
| `selectXXXList` | 条件列表查询（分页由 PageHelper 接管） |
| `countByXXX` | 统计数量 |
| `insert` | 插入（回填自增主键） |
| `updateById` | 按 ID 更新 |
| `updateStatus` | 更新状态 |
| `deleteById` | 逻辑删除 |

### 8.2 Mapper XML

XML 位于 `harnax-entity/src/main/resources/mapper/`，与接口同名。文件组织：

1. `resultMap`（`XXXResultMap`，主键用 `<id>`，全字段映射）
2. 基础 CRUD（注释分隔线 `<!-- ==== Basic CRUD Methods ==== -->`）
3. 自定义查询（注释分隔线 `<!-- ==== Custom Query Methods ==== -->`）

```xml
<select id="selectById" resultMap="McpServerResultMap">
    SELECT * FROM mcp_server WHERE id = #{id} AND active = 1 LIMIT 1
</select>

<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO mcp_server (name, description, ..., create_time, update_time)
    VALUES (#{name}, #{description}, ..., #{createTime}, #{updateTime})
</insert>

<!-- Logical delete -->
<update id="deleteById">
    UPDATE mcp_server SET active = 0, update_time = NOW() WHERE id = #{id} AND active = 1
</update>

<!-- Conditional query with data permission -->
<select id="selectMcpServerList" resultMap="McpServerResultMap">
    SELECT * FROM mcp_server
    WHERE active = 1
    AND (is_public = 1 OR creator = #{currentUsername})
    <if test='keyword != null and keyword != ""'>
        AND (name LIKE CONCAT('%', #{keyword}, '%')
        OR description LIKE CONCAT('%', #{keyword}, '%'))
    </if>
    <if test='status != null'>
        AND status = #{status}
    </if>
    ORDER BY status DESC, update_time DESC
</select>
```

**要求**：

- 所有查询必须过滤 `active = 1`
- 业务列表查询包含数据权限条件 `(is_public = 1 OR creator = #{currentUsername})`
- 动态条件使用 `<if>`，模糊查询使用 `LIKE CONCAT('%', #{param}, '%')`
- 批量插入使用 `<foreach collection="list" ...>` + `useGeneratedKeys`
- 禁止使用 `${}` 拼接用户输入（防 SQL 注入）

## 九、异常处理规范

### 9.1 BizException

业务异常统一使用 `BizException`（默认 code = 400）：

```kotlin
throw BizException("MCP name already exists")            // 默认 400
throw BizException(404, "Agent not found")               // 自定义 code
throw BizException(messageUtil.getMessage("error.user.notfound"))  // i18n 消息
throw BizException("Failed to create agent", cause)      // 携带原因
```

### 9.2 GlobalExceptionHandler

`@RestControllerAdvice` 全局兜底，处理顺序与返回约定：

| 异常 | HTTP 状态 | 业务 code | 日志级别 |
|------|-----------|-----------|----------|
| `BizException` | 200 | 异常自带（默认 400） | warn |
| `MethodArgumentNotValidException` | 400 | 400（首个字段错误） | warn |
| `IllegalArgumentException` | 400 | 400 | warn |
| `HttpMessageNotReadableException` | 400 | 400 | warn |
| `MissingServletRequestParameterException` | 400 | 400 | warn |
| `MaxUploadSizeExceededException` | 400 | 400 | warn |
| `NoHandlerFoundException` | 404 | 404 | warn |
| `HttpRequestMethodNotSupportedException` | 405 | 405 | warn |
| `DataAccessException` / `SQLException` | 500 | 500（隐藏细节） | error |
| `RuntimeException` / `Exception` | 500 | 500（兜底） | error |

**原则**：数据库与系统异常对外只返回通用提示，不暴露堆栈与内部细节。

## 十、国际化（i18n）规范

### 10.1 消息文件

位于 `harnax-admin/src/main/resources/i18n/`：

```
messages.properties               # 普通消息（默认英文）
messages_en.properties            # 英文
messages_zh_CN.properties         # 简体中文
messages_error.properties         # 错误消息（默认英文）
messages_error_en.properties      # 错误消息英文
messages_error_zh_CN.properties   # 错误消息简体中文
```

### 10.2 消息 key 命名

```properties
# error.{module}.{case}
error.user.notfound=User not found
error.user.username_exists=Username already exists

# 占位符使用 {0}、{1}
error.user.is_tenant_admin=User is an admin of tenant [{0}], please change the tenant admin first

# success.{module}.{action}
success.xxx.created=XXX created successfully
```

### 10.3 使用方式

通过 `MessageUtil` 获取消息（按请求头 `Accept-Language` 解析，`zh*` → 简体中文，其余默认英文）：

```kotlin
throw BizException(messageUtil.getMessage("error.user.notfound"))
throw BizException(messageUtil.getMessage("error.validation.required", "username"))
```

**现状与要求**：

- 用户、租户、认证等核心模块必须走 i18n key
- 新模块的业务异常消息也应优先使用 i18n key；至少保证消息为英文、语义清晰
- 测试中断言异常消息时使用**消息 key**，不断言翻译后的中文

## 十一、多租户数据隔离规范

### 11.1 租户字段

业务表必须包含 `tenant_id BIGINT NOT NULL DEFAULT 1`，实体包含 `var tenantId: Long = 1`。

### 11.2 拦截器自动隔离

`MybatisTenantInterceptor` 在 MyBatis 层自动处理：

- SELECT：追加 `tenant_id = #{tenantId}` 过滤
- INSERT：自动填充 `tenant_id`
- UPDATE / DELETE：追加 `tenant_id` 条件

### 11.3 例外与豁免

- **排除表**（`EXCLUDED_TABLES`）：`tenant`、`user_tenant`、`sys_user`、`sys_token_blacklist`、`plan_note`、`tool_call_log`；新增系统级表如需豁免，须在此集合中显式添加并注释原因
- **方法级豁免**：Mapper 方法标注 `@SkipTenantFilter` 可跳过租户过滤（仅限内部统计、跨租户查询等场景，需评审）

### 11.4 租户上下文

```kotlin
val tenantId = TenantContext.getTenantId() ?: 1
```

- 请求进入时由 `TenantInterceptor` 从 JWT 解析并写入 `TenantContext`
- 请求结束自动清理，禁止在线程池/异步任务中直接透传

## 十二、安全规范

| 项目 | 要求 |
|------|------|
| 身份认证 | 除登录、验证码、健康检查外，所有接口经 JWT 拦截器校验；用户信息从 Token 解析（userId、username、tenantId、isAdmin） |
| 密码存储 | 使用 BCrypt（`BCrypt.hashpw` / `BCrypt.checkpw`），禁止明文与可逆加密 |
| 敏感配置 | headers、envParams 等含 `secret` 标记的 JSON 使用 `SecretFieldEncryptor`（AES）加密落库 |
| API Key | 存储 SHA-256 哈希（`key_hash`）+ 前缀（`key_prefix`），原始 Key 仅创建时展示一次；需要可逆场景才用 AES 加密存储 |
| 响应脱敏 | 密码、原始密钥、解密后的明文配置禁止出现在响应 DTO 中 |
| 删除策略 | 一律逻辑删除（`active = 0`），禁止物理删除 |
| SQL 注入 | 一律使用 `#{}` 参数绑定 |
| 管理员操作 | 高危操作（删租户、删管理员）须校验 `isAdmin` 并给出明确业务异常 |

## 十三、日志规范

### 13.1 声明方式

```kotlin
private val log = LoggerFactory.getLogger(XXXServiceImpl::class.java)
```

### 13.2 级别使用

| 级别 | 场景 |
|------|------|
| ERROR | 系统异常、数据库异常、未知错误（必须带堆栈） |
| WARN | 业务异常、参数校验失败等预期异常 |
| INFO | 关键业务操作（创建/更新/删除/状态切换的开始与结果） |
| DEBUG | 调试细节（查询参数、拦截器跳过原因） |

### 13.3 内容要求

- **必须英文**；使用占位符 `log.info("Creating MCP server, name: {}", request.name)`
- 记录关键上下文：ID、名称、操作结果（successful/failed）
- ERROR / WARN 必须携带异常对象
- **禁止**记录密码、Token、API Key、解密后的敏感配置

```kotlin
log.info("Creating MCP server, name: {}", request.name)
log.info("MCP server creation {}, id: {}", if (success) "successful" else "failed", mcpServer.id)
log.error("Failed to get MCP server list", e)
```

## 十四、事务管理规范

```kotlin
@Transactional(rollbackFor = [Exception::class])
override fun createXXX(request: XXXCreateRequest): Boolean { ... }
```

- 只在 Service 层使用，必须声明 `rollbackFor = [Exception::class]`
- **必须使用**：多表写操作、创建 + 关联数据创建
- **不需要**：单一查询、只读操作
- 避免大事务：远程调用（HTTP、MCP 连接测试等）不得放在事务内，拆分为「事务方法 + 事务外调用」
- 默认传播行为 `REQUIRED`

## 十五、单元测试规范

### 15.1 分层测试策略

| 层 | 方式 | 位置 | 命名 |
|----|------|------|------|
| Service | Mockito Mock（JUnit 5 + mockito-kotlin） | `harnax-admin/src/test` | `XXXServiceImplTest` |
| Mapper | Testcontainers + `@MybatisTest` + MySQL 8.0 容器 | `harnax-entity/src/test` | `XXXMapperTest` |
| Controller | MockMvc 接口测试（按需） | `harnax-admin/src/test` | `XXXControllerTest` |

### 15.2 Service 测试要点

```kotlin
@ExtendWith(MockitoExtension::class)
class McpServerServiceImplTest {

    @Mock
    private lateinit var mcpServerMapper: McpServerMapper

    @Mock
    private lateinit var messageUtil: MessageUtil   // 依赖国际化时必须 mock

    @InjectMocks
    private lateinit var mcpServerService: McpServerServiceImpl

    @BeforeEach
    fun setUp() {
        // Mock messageUtil to return the key as message
        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }
    }
}
```

- 使用 `@Nested` + `@DisplayName` 分组，方法名反引号格式 `` `method should expected behavior` ``
- 每个测试遵循 AAA（Arrange / Act / Assert），用 `// Given` `// When` `// Then` 注释分段
- 构造器中的**每个依赖**都必须声明 `@Mock`，否则 `@InjectMocks` 失败
- `argThat` 的 lambda 必须显式声明参数类型并判空，避免 NPE 污染匹配器状态
- `setUp()` 中的共享 stub 若部分测试用不到，使用 `@MockitoSettings(strictness = Strictness.LENIENT)`
- 断言异常消息使用 i18n **消息 key**，不断言中文
- PageHelper 依赖 ThreadLocal，单测中只断言 `pageSize > 0`，不断言精确值

### 15.3 Mapper 测试要点

```kotlin
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class McpServerMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }
}
```

- 测试类必须声明为 `open`（Spring 代理需要）
- 使用 `kotlin.test` 断言（`assertEquals`、`assertNotNull`、`assertNull`、`assertTrue`）
- 数据由 `schema-test.sql` 预置：包含正常（`active=1, status=1`）、已删除（`active=0`）、已禁用（`status=0`）三类数据
- **insert / update 测试必须逐字段断言**，确保 SQL 无遗漏字段
- 覆盖：基础 CRUD、逻辑删除后查询为 null、`active` 过滤、条件查询、状态更新

### 15.4 覆盖率要求

| 层 | 目标 |
|----|------|
| Service | ≥ 80% |
| Mapper | ≥ 90% |
| Controller | ≥ 70%（可选） |
| 整体 | ≥ 80% |

```bash
mvn test                                   # 运行所有测试
mvn test -Dtest=AgentServiceImplTest       # 运行特定类
mvn test jacoco:report                     # 生成覆盖率报告（target/site/jacoco/index.html）
```

## 十六、Kotlin 编码最佳实践

- 使用空安全操作符：`?.`、`?:`、`let`；谨慎使用 `!!`（仅在校验过的必填字段上）
- 构建实体使用 `apply`，条件执行使用 `let`，多分支使用 `when`
- 方法建议 < 50 行，嵌套 < 3 层
- 纯数据对象用 `data class`，单例用 `object`
- 字符串判空统一使用 `org.springframework.util.StringUtils.hasText`
- 分页边界保护使用 `coerceAtLeast` / `coerceIn`

## 十七、版本控制与功能开关（规划中）

> 说明：`@RequiresEdition` 注解与 `EditionUtil` 工具类目前尚未在代码中落地，本节为设计规范，实现后生效。

- 版本划分：`personal`（个人版）、`enterprise`（企业版）、`public`（公网版）
- Controller 使用 `@RequiresEdition(...)` 控制接口可见性；版本不匹配返回 404（而非 403）
- Service 使用 `EditionUtil.isEnterprise()` / `isFeatureEnabled("feature-key")` 做功能开关
- 功能开关定义在 `application.yml` 的 `harnax.features` 下，默认关闭
- 现有代码中版本相关的临时判断（如 stdio 模式限制）集中在 Controller/Service 入口处，落地后需迁移到注解

## 十八、开发流程与代码审查

### 18.1 新模块开发顺序

1. 设计：PRD → 数据库表（参考 [数据库设计规范](./database-design-conventions.md)）→ API 接口
2. 开发：Flyway 迁移脚本 → Entity → Mapper + XML → DTO → Service → Controller
3. 测试：Mapper 集成测试 + Service 单元测试，覆盖率达标
4. 自测：Swagger（`http://localhost:8080/admin/swagger-ui/index.html`）手工验证
5. 审查：按 18.2 清单执行

### 18.2 代码审查清单

**代码规范**：命名、英文注释、英文日志、分层是否越界

**功能完整性**：

- [ ] CRUD 六接口完整（分页、详情、创建、更新、状态切换、删除）
- [ ] 参数校验（`@Valid` + Jakarta Validation）与业务校验（唯一性、关联存在性）齐备
- [ ] 查询过滤 `active = 1` 与数据权限条件
- [ ] 删除前检查下游依赖

**安全**：敏感字段加密/脱敏、JWT 鉴权、管理员权限校验、无 `${}` 拼接

**测试**：单测覆盖正常/异常/边界，insert/update 逐字段断言，覆盖率达标

**性能**：索引合理、无 N+1 查询、分页保护

### 18.3 Git 提交规范

```
<type>(<scope>): <subject>

<body>

<footer>
```

type：`feat` / `fix` / `docs` / `style` / `refactor` / `test` / `chore`

```
feat(agent): add agent sharing feature

- Add agent sharing API endpoints
- Implement permission control
- Add unit tests

Closes #123
```

---

**文档版本**: v1.0
**整理依据**: `openspec/vipclaw-admin-rules.md`（v2.1）+ `harnax-admin` / `harnax-entity` 现有代码实现
**维护者**: Harnax 团队

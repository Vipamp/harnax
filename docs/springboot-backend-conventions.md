# Spring Boot 后端开发规范

本规范适用于基于 **Spring Boot + Kotlin + MyBatis + MySQL** 的后端项目，涵盖代码分层、命名、接口设计、异常处理、测试与数据库设计。新模块开发、代码审查均以本文档为准。

**文档版本**: v1.0

## 基本原则

| 项目 | 要求 |
|------|------|
| 代码注释 | 全部使用**英文**（KDoc、行内注释、XML 注释） |
| 日志内容 | 全部使用**英文** |
| API 注解 | `@Schema`、`@Operation`、`@Parameter` 等描述使用**英文** |
| 用户可见消息 | 错误提示、成功提示必须支持**国际化（i18n）** |
| 开发语言 | Kotlin，充分利用空安全、data class、扩展函数等特性 |

---

# 第一部分：后端代码规范

## 一、项目结构与分层

### 1.1 模块划分

单模块项目按包分层即可；多模块项目建议按职责拆分：

| 模块 | 职责 | 主要内容 |
|------|------|----------|
| `{app}-common` | 公共组件 | 统一响应体、公共异常、公共工具 |
| `{app}-entity` | 数据访问 | Entity、Mapper 接口、Mapper XML |
| `{app}-web` / 主模块 | Web 应用 | Controller、Service、DTO、配置、拦截器、工具类 |

### 1.2 包结构

```
com.example.project
├── controller/          # 控制器（可按端分子包，如 controller/app）
├── service/             # Service 接口
│   └── impl/            # Service 实现
├── dto/                 # 数据传输对象
│   ├── request/         # 请求 DTO
│   └── response/        # 响应 DTO
├── entity/              # 实体类（与数据表一一对应）
├── mapper/              # Mapper 接口
├── exception/           # 业务异常、全局异常处理器
├── interceptor/         # 拦截器（认证、租户等）
├── context/             # 请求上下文（如租户上下文）
├── i18n/                # 国际化（消息工具类）
├── config/              # 配置类
├── security/            # 安全相关
├── util/                # 工具类
└── constant/            # 常量

resources/
├── mapper/              # Mapper XML（与 Mapper 接口同名）
├── db/migration/        # Flyway 迁移脚本
└── i18n/                # 国际化消息文件
```

### 1.3 分层职责

```
Controller 层（接收请求、参数校验、封装响应）
    ↓
Service 层（业务逻辑、事务、业务校验）
    ↓
Mapper 层（数据库 CRUD、SQL 映射）
    ↓
MySQL
```

**Controller 层**：

- 接收 HTTP 请求，解析参数，使用 `@Valid` 触发参数校验
- 调用 Service 处理业务，将结果封装为统一响应体返回
- 使用 try-catch 捕获异常并记录英文日志
- **禁止**：编写业务逻辑、直接调用 Mapper、返回裸对象

**Service 层**：

- 处理核心业务逻辑与业务校验（唯一性、关联存在性、字段联动）
- 使用 `@Transactional(rollbackFor = [Exception::class])` 管理事务
- 调用 Mapper 进行数据操作
- **禁止**：处理 HTTP 细节、返回统一响应体

**Mapper 层**：

- 仅负责数据库 CRUD 与 SQL 映射（XML 优先）
- **禁止**：包含业务逻辑、声明事务

## 二、命名规范

### 2.1 通用命名

| 对象 | 规则 | 示例 |
|------|------|------|
| 包名 | 全小写点分隔 | `com.example.project` |
| 类名 | PascalCase | `OrderServiceImpl` |
| 方法名 | camelCase，动词开头 | `getOrderById` |
| 变量名 | camelCase | `currentUsername` |
| 常量 | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |

### 2.2 类命名约定

| 类型 | 命名模式 | 示例 |
|------|----------|------|
| 实体类 | 业务名词（与表名对应），通用表可加 `Entity` 后缀 | `Order`、`UserEntity` |
| Mapper | `XXXMapper` | `OrderMapper` |
| Service 接口 | `XXXService` | `OrderService` |
| Service 实现 | `XXXServiceImpl` | `OrderServiceImpl` |
| Controller | `XXXController` | `OrderController` |
| 创建请求 | `XXXCreateRequest` | `OrderCreateRequest` |
| 更新请求 | `XXXUpdateRequest` | `OrderUpdateRequest` |
| 响应 | `XXXResponse` | `OrderResponse` |
| 单元测试 | `XXXServiceImplTest` / `XXXMapperTest` | `OrderServiceImplTest` |

## 三、实体（Entity）规范

- 普通 `class` 实现 `Serializable`，声明 `serialVersionUID`
- 可变字段使用 `var`，带业务默认值（`status = 1`、`active = 1` 等）
- 每个字段加 KDoc 注释 + `@Schema(description = ...)`
- 必须包含通用字段：`id`、`status`、`active`、`createTime`、`updateTime`
- 需要数据权限的表包含 `isPublic`、`creator`；多租户项目包含 `tenantId`

```kotlin
package com.example.project.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Order entity
 */
@Schema(description = "Order entity")
class Order : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * Order ID
     */
    @Schema(description = "Order ID")
    var id: Long = 0

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

## 四、DTO 规范

DTO 均为 `data class`，每个字段必须有 `@Schema` 描述（必填字段加 `requiredMode`）与 `example`。

### 4.1 创建请求（XXXCreateRequest）

- **不包含** `id`、`active`、`createTime`、`updateTime`（由后台生成）
- 必填字段使用 `@NotBlank` / `@NotNull`，长度限制使用 `@Size`
- 校验 `message` 使用英文

```kotlin
@Schema(description = "Order creation request object")
data class OrderCreateRequest(
    @Schema(description = "Order name", example = "my-order", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Order name cannot be empty")
    @Size(max = 100, message = "Order name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "Order description", example = "This is an order")
    val description: String? = null,
)
```

### 4.2 更新请求（XXXUpdateRequest）

- **不包含** `status`、`active`、`createTime`、`updateTime`
- 所有业务字段可空（`val xxx: Type? = null`），支持部分更新
- 长度/格式校验只约束「有值」的字段

### 4.3 响应（XXXResponse）

- 包含除敏感字段（密码、原始密钥等）外的所有字段
- 提供 `companion object { fun fromEntity(...) }` 转换方法；涉及解密/反序列化时由 Service 注入协作者并传入
- 敏感配置字段在响应中需脱敏（如标记为机密的条目不返回明文）

### 4.4 常用校验注解

| 注解 | 用途 |
|------|------|
| `@NotNull` | 不能为 null |
| `@NotBlank` | 非 null 且非空白字符串 |
| `@NotEmpty` | 非 null 且非空集合 |
| `@Size(max = n)` | 长度/大小上限 |
| `@Min` / `@Max` | 数值范围 |
| `@Email` / `@Pattern` | 格式校验 |

## 五、Controller 层规范

### 5.1 类级注解

```kotlin
@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "Order Management", description = "Order related APIs")
class OrderController(
    private val orderService: OrderService,
) {
    private val log = LoggerFactory.getLogger(OrderController::class.java)
}
```

- 必须使用构造器注入依赖
- 必须声明类级 `log`
- 接口统一使用 `/api/{端}/{资源}` 前缀（如 `/api/admin/orders`），资源名使用**复数小写短横线**风格（`orders`、`user-groups`、`api-keys`）

### 5.2 方法与参数注解

- 每个接口必须有 `@Operation(summary, description)`
- 每个参数必须有 `@Parameter(description)`，并给出 `example`（适用时）
- 请求体使用 `@Valid @RequestBody`；可选查询参数必须 `required = false` 并给默认值

### 5.3 接口 URL 规范

| 操作 | HTTP 方法 | URL 模式 | 示例 |
|------|-----------|----------|------|
| 分页查询 | GET | `/page` | `GET /api/admin/orders/page` |
| 获取详情 | GET | `/{id}` | `GET /api/admin/orders/1` |
| 创建 | POST | `/` | `POST /api/admin/orders` |
| 更新 | PUT | `/update/{id}` | `PUT /api/admin/orders/update/1` |
| 切换状态 | PUT | `/toggle/{id}` | `PUT /api/admin/orders/toggle/1` |
| 删除（逻辑） | DELETE | `/{id}` | `DELETE /api/admin/orders/1` |
| 动作类接口 | POST | `/{id}/{动作}` | `POST /api/admin/orders/{id}/cancel` |

### 5.4 统一响应体

项目须在公共模块定义统一响应体 `Result<T>`，所有接口统一返回：

```kotlin
@Schema(description = "Unified response result")
data class Result<T>(
    @Schema(description = "Status code", example = "200")
    val code: Int = 200,                              // 200 success / 400 business error / 500 system error

    @Schema(description = "Response message", example = "success")
    val message: String = "success",

    @Schema(description = "Response data")
    val data: T? = null,

    @Schema(description = "Timestamp", example = "1704067200000")
    val timestamp: Long = System.currentTimeMillis(),
) : Serializable {
    companion object {
        @JvmStatic fun <T> success(): Result<T> = Result(200, "success", null)
        @JvmStatic fun <T> success(data: T): Result<T> = Result(200, "success", data)
        @JvmStatic fun <T> success(message: String, data: T): Result<T> = Result(200, message, data)
        @JvmStatic fun <T> error(message: String): Result<T> = Result(500, message, null)
        @JvmStatic fun <T> error(code: Int, message: String): Result<T> = Result(code, message, null)
    }

    fun isSuccess(): Boolean = code == 200
}
```

### 5.5 方法实现模板（try-catch + 日志）

```kotlin
@GetMapping("/page")
@Operation(summary = "Get order list with pagination", description = "Paginated query for order information")
fun pageOrder(
    @Parameter(description = "Page number", example = "1")
    @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
    @Parameter(description = "Page size", example = "10")
    @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
    @Parameter(description = "Keyword (name/description)")
    @RequestParam(name = "keyword", required = false) keyword: String?,
    @Parameter(description = "Status filter (0: disabled 1: enabled)")
    @RequestParam(name = "status", required = false) status: Int?,
): Result<Page<OrderResponse>> = try {
    val page = orderService.page(keyword, status, pageNum ?: 1, pageSize ?: 10)
    Result.success(page.mapRecords { orderService.convertToResponse(it) })
} catch (e: Exception) {
    log.error("Failed to get order list", e)
    Result.error(e.message ?: "Failed to get order list")
}
```

**原则**：

- 捕获所有异常，避免堆栈信息泄露给前端
- 日志记录用英文，包含操作上下文
- 未捕获异常由全局异常处理器兜底

### 5.6 分页参数约定

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `pageNum` | Int | 1 | 页码 |
| `pageSize` | Int | 10 | 每页条数 |
| `keyword` / `name` | String? | null | 模糊搜索，为 null 不作为筛选条件 |
| `status` | Int? | null | 状态筛选，为 null 不作为筛选条件 |
| `type` | String? | null | 类型筛选，为 null 不作为筛选条件 |

## 六、Service 层规范

### 6.1 接口与实现分离

接口位于 `service/`，实现位于 `service/impl/`，实现类使用 `@Service` 与构造器注入：

```kotlin
@Service
class OrderServiceImpl(
    private val orderMapper: OrderMapper,
    private val messageUtil: MessageUtil,
) : OrderService {
    private val log = LoggerFactory.getLogger(OrderServiceImpl::class.java)
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

### 6.2 分页查询

使用 PageHelper 分页，并定义项目内统一的分页对象 `Page<T>`：

```kotlin
/**
 * Custom pagination class based on PageHelper's PageInfo
 */
data class Page<T>(
    var pageNum: Long = 1,
    var pageSize: Long = 10,
    var total: Long = 0,
    var records: List<T> = emptyList(),
) {
    val pages: Long
        get() = if (pageSize > 0) (total + pageSize - 1) / pageSize else 0

    companion object {
        fun <T> fromPageInfo(list: List<T>): Page<T> {
            val pageInfo = PageInfo(list)
            return Page(pageNum = pageInfo.pageNum.toLong(), pageSize = pageInfo.pageSize.toLong(), total = pageInfo.total, records = pageInfo.list)
        }
    }
}

/**
 * Converts Page<Entity> to Page<Response>
 */
fun <T, R> Page<T>.mapRecords(transform: (T) -> R): Page<R> = Page<R>(
    pageNum = this.pageNum,
    pageSize = this.pageSize,
    total = this.total,
    records = this.records.map(transform),
)
```

分页实现要求：

```kotlin
override fun page(keyword: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Order> {
    val currentUsername = UserContextUtil.getCurrentUsername()
    val safePageNum = pageNum.coerceAtLeast(1)
    val safePageSize = pageSize.coerceIn(1, 1000)
    PageHelper.startPage<Order>(safePageNum, safePageSize)
    return Page.fromPageInfo(orderMapper.selectOrderList(keyword, status, currentUsername))
}
```

- `PageHelper.startPage()` 必须紧贴 Mapper 查询调用
- 对 `pageNum`、`pageSize` 做边界保护（`coerceAtLeast` / `coerceIn`）
- 需要数据权限的查询必须传入当前用户名
- Controller 中用 `page.mapRecords { service.convertToResponse(it) }` 转换为响应对象

### 6.3 业务校验

**创建流程**：

1. 唯一性校验（如名称已存在 → `throw BizException(...)`）
2. 关联项存在性校验
3. 字段联动校验（如类型与必填字段的对应关系）
4. 敏感字段加密（密码 BCrypt、配置项 AES）
5. 设置归属信息（创建人、租户等）
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

```kotlin
@Transactional(rollbackFor = [Exception::class])
override fun createOrder(request: OrderCreateRequest): Boolean {
    log.info("Creating order, name: {}", request.name)

    // Validate name uniqueness
    val existing = orderMapper.selectByName(request.name!!)
    if (existing != null) {
        throw BizException(messageUtil.getMessage("error.order.name_exists"))
    }

    val order = Order().apply {
        name = request.name
        description = request.description ?: ""
        status = request.status ?: 1
        active = 1
        creator = UserContextUtil.getCurrentUsername()
    }

    val success = orderMapper.insert(order) > 0
    log.info("Order creation {}, id: {}", if (success) "successful" else "failed", order.id)
    return success
}
```

### 6.4 Entity → Response 转换

二选一，保持模块内一致：

```kotlin
// 方式一：Response 提供静态转换方法（推荐，涉及解密时传入协作者）
override fun convertToResponse(order: Order): OrderResponse =
    OrderResponse.fromEntity(order)

// 方式二：Service 提供 convertToResponse 内联转换
override fun convertToResponse(entity: XXX): XXXResponse = XXXResponse(
    id = entity.id,
    name = entity.name,
    // ...
)
```

## 七、Mapper 层规范

### 7.1 Mapper 接口

```kotlin
@Mapper
interface OrderMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): Order?

    fun insert(order: Order): Int

    fun updateById(order: Order): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================

    fun selectOrderList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<Order>

    fun selectByName(@Param("name") name: String): Order?

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

### 7.2 Mapper XML

XML 位于 `resources/mapper/`，与接口同名。文件组织：

1. `resultMap`（`XXXResultMap`，主键用 `<id>`，全字段映射）
2. 基础 CRUD（注释分隔线 `<!-- ==== Basic CRUD Methods ==== -->`）
3. 自定义查询（注释分隔线 `<!-- ==== Custom Query Methods ==== -->`）

```xml
<select id="selectById" resultMap="OrderResultMap">
    SELECT * FROM `order` WHERE id = #{id} AND active = 1 LIMIT 1
</select>

<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO `order` (name, description, ..., create_time, update_time)
    VALUES (#{name}, #{description}, ..., #{createTime}, #{updateTime})
</insert>

<!-- Logical delete -->
<update id="deleteById">
    UPDATE `order` SET active = 0, update_time = NOW() WHERE id = #{id} AND active = 1
</update>

<!-- Conditional query with data permission -->
<select id="selectOrderList" resultMap="OrderResultMap">
    SELECT * FROM `order`
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

## 八、异常处理规范

### 8.1 业务异常

项目定义统一的 `BizException`（默认 code = 400）：

```kotlin
class BizException : RuntimeException {
    val code: Int

    constructor(message: String) : super(message) { this.code = 400 }
    constructor(code: Int, message: String) : super(message) { this.code = code }
    constructor(message: String, cause: Throwable) : super(message, cause) { this.code = 400 }
    constructor(code: Int, message: String, cause: Throwable) : super(message, cause) { this.code = code }
}
```

使用方式：

```kotlin
throw BizException("Order not found")                                // 默认 400
throw BizException(404, "Order not found")                           // 自定义 code
throw BizException(messageUtil.getMessage("error.order.notfound"))   // i18n 消息（推荐）
throw BizException("Failed to create order", cause)                  // 携带原因
```

### 8.2 全局异常处理器

使用 `@RestControllerAdvice` 全局兜底，返回约定：

| 异常 | HTTP 状态 | 业务 code | 日志级别 |
|------|-----------|-----------|----------|
| `BizException` | 200 | 异常自带（默认 400） | warn |
| `MethodArgumentNotValidException` | 400 | 400（返回首个字段错误） | warn |
| `IllegalArgumentException` | 400 | 400 | warn |
| `HttpMessageNotReadableException` | 400 | 400 | warn |
| `MissingServletRequestParameterException` | 400 | 400 | warn |
| `MaxUploadSizeExceededException` | 400 | 400 | warn |
| `NoHandlerFoundException` | 404 | 404 | warn |
| `HttpRequestMethodNotSupportedException` | 405 | 405 | warn |
| `DataAccessException` / `SQLException` | 500 | 500（隐藏细节） | error |
| `RuntimeException` / `Exception` | 500 | 500（兜底） | error |

**原则**：数据库与系统异常对外只返回通用提示，不暴露堆栈与内部细节；业务异常属预期异常，仅记 warn。

## 九、国际化（i18n）规范

### 9.1 消息文件

位于 `resources/i18n/`：

```
messages.properties               # 普通消息（默认英文）
messages_zh_CN.properties         # 简体中文
messages_error.properties         # 错误消息（默认英文）
messages_error_zh_CN.properties   # 错误消息简体中文
```

### 9.2 消息 key 命名

```properties
# error.{module}.{case}
error.order.notfound=Order not found
error.order.name_exists=Order name already exists

# 占位符使用 {0}、{1}
error.validation.required={0} is required

# success.{module}.{action}
success.order.created=Order created successfully
```

### 9.3 使用方式

提供统一的消息工具类，按请求头 `Accept-Language` 解析语言（`zh*` → 简体中文，其余默认英文）：

```kotlin
throw BizException(messageUtil.getMessage("error.order.notfound"))
throw BizException(messageUtil.getMessage("error.validation.required", "name"))
```

**要求**：

- 所有面向用户的错误消息走 i18n key，禁止在代码中硬编码中文提示
- 消息找不到时回退返回 key 本身，避免 NPE
- 测试中断言异常消息时使用**消息 key**，不断言翻译后的中文

## 十、多租户数据隔离（多租户项目适用）

### 10.1 租户字段

业务表必须包含 `tenant_id BIGINT NOT NULL DEFAULT 1`，实体包含 `var tenantId: Long = 1`。

### 10.2 拦截器自动隔离

实现 MyBatis Interceptor 在 SQL 层自动处理：

- SELECT：追加 `tenant_id = #{tenantId}` 过滤
- INSERT：自动填充 `tenant_id`
- UPDATE / DELETE：追加 `tenant_id` 条件

### 10.3 例外与豁免

- **排除表**：系统级表（租户表、用户表、登录凭证表等）不需要租户过滤，在拦截器排除列表中显式声明并注释原因
- **方法级豁免**：提供 `@SkipTenantFilter` 注解跳过单个 Mapper 方法的租户过滤（仅限内部统计、跨租户查询等场景，需评审）

### 10.4 租户上下文

```kotlin
val tenantId = TenantContext.getTenantId() ?: 1
```

- 请求进入时由拦截器从 JWT 解析并写入上下文（ThreadLocal）
- 请求结束自动清理，禁止在线程池/异步任务中直接透传

## 十一、安全规范

| 项目 | 要求 |
|------|------|
| 身份认证 | 除登录、验证码、健康检查外，所有接口经 JWT 拦截器校验；用户信息从 Token 解析（userId、username、tenantId、角色） |
| 密码存储 | 使用 BCrypt（`BCrypt.hashpw` / `BCrypt.checkpw`），禁止明文与可逆加密 |
| 敏感配置 | 含机密标记的 JSON 配置使用 AES 加密工具统一加密落库 |
| API Key / Token | 存储哈希（如 SHA-256）+ 展示前缀，原始值仅创建时返回一次；需要可逆场景才用 AES 加密存储 |
| 响应脱敏 | 密码、原始密钥、解密后的明文配置禁止出现在响应 DTO 中 |
| 删除策略 | 一律逻辑删除（`active = 0`），禁止物理删除 |
| SQL 注入 | 一律使用 `#{}` 参数绑定 |
| 管理员操作 | 高危操作（删除管理员、越权访问）须校验角色并给出明确业务异常 |

## 十二、日志规范

### 12.1 声明方式

```kotlin
private val log = LoggerFactory.getLogger(XXXServiceImpl::class.java)
```

### 12.2 级别使用

| 级别 | 场景 |
|------|------|
| ERROR | 系统异常、数据库异常、未知错误（必须带堆栈） |
| WARN | 业务异常、参数校验失败等预期异常 |
| INFO | 关键业务操作（创建/更新/删除/状态切换的开始与结果） |
| DEBUG | 调试细节（查询参数、拦截器跳过原因） |

### 12.3 内容要求

- **必须英文**；使用占位符 `log.info("Creating order, name: {}", request.name)`
- 记录关键上下文：ID、名称、操作结果（successful/failed）
- ERROR / WARN 必须携带异常对象
- **禁止**记录密码、Token、API Key、解密后的敏感配置

```kotlin
log.info("Creating order, name: {}", request.name)
log.info("Order creation {}, id: {}", if (success) "successful" else "failed", order.id)
log.error("Failed to get order list", e)
```

## 十三、事务管理规范

```kotlin
@Transactional(rollbackFor = [Exception::class])
override fun createXXX(request: XXXCreateRequest): Boolean { ... }
```

- 只在 Service 层使用，必须声明 `rollbackFor = [Exception::class]`
- **必须使用**：多表写操作、创建 + 关联数据创建
- **不需要**：单一查询、只读操作
- 避免大事务：远程调用（HTTP、RPC、第三方 SDK）不得放在事务内，拆分为「事务方法 + 事务外调用」
- 默认传播行为 `REQUIRED`

## 十四、单元测试规范

### 14.1 分层测试策略

| 层 | 方式 | 命名 |
|----|------|------|
| Service | Mockito Mock（JUnit 5 + mockito-kotlin） | `XXXServiceImplTest` |
| Mapper | Testcontainers + `@MybatisTest` + MySQL 8.0 容器 | `XXXMapperTest` |
| Controller | MockMvc 接口测试（按需） | `XXXControllerTest` |

### 14.2 Service 测试要点

```kotlin
@ExtendWith(MockitoExtension::class)
class OrderServiceImplTest {

    @Mock
    private lateinit var orderMapper: OrderMapper

    @Mock
    private lateinit var messageUtil: MessageUtil   // 依赖国际化时必须 mock

    @InjectMocks
    private lateinit var orderService: OrderServiceImpl

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
- 覆盖：正常流程、异常流程（各种 `BizException`）、边界条件（空值、空列表）、操作失败（返回 false）

### 14.3 Mapper 测试要点

```kotlin
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class OrderMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("app_test")
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

### 14.4 覆盖率要求

| 层 | 目标 |
|----|------|
| Service | ≥ 80% |
| Mapper | ≥ 90% |
| Controller | ≥ 70%（可选） |
| 整体 | ≥ 80% |

```bash
mvn test                              # 运行所有测试
mvn test -Dtest=OrderServiceImplTest  # 运行特定类
mvn test jacoco:report                # 生成覆盖率报告（target/site/jacoco/index.html）
```

## 十五、Kotlin 编码最佳实践

- 使用空安全操作符：`?.`、`?:`、`let`；谨慎使用 `!!`（仅在校验过的必填字段上）
- 构建实体使用 `apply`，条件执行使用 `let`，多分支使用 `when`
- 方法建议 < 50 行，嵌套 < 3 层
- 纯数据对象用 `data class`，单例用 `object`
- 字符串判空统一使用 `org.springframework.util.StringUtils.hasText`
- 分页边界保护使用 `coerceAtLeast` / `coerceIn`
- 避免 N+1 查询：关联数据用批量查询 + `associateBy` 映射

## 十六、开发流程与代码审查

### 16.1 新模块开发顺序

1. 设计：PRD → 数据库表（参考第二部分）→ API 接口
2. 开发：Flyway 迁移脚本 → Entity → Mapper + XML → DTO → Service → Controller
3. 测试：Mapper 集成测试 + Service 单元测试，覆盖率达标
4. 自测：Swagger（`/swagger-ui/index.html`）手工验证
5. 审查：按 16.2 清单执行

### 16.2 代码审查清单

**代码规范**：命名、英文注释、英文日志、分层是否越界

**功能完整性**：

- [ ] CRUD 六接口完整（分页、详情、创建、更新、状态切换、删除）
- [ ] 参数校验（`@Valid` + Jakarta Validation）与业务校验（唯一性、关联存在性）齐备
- [ ] 查询过滤 `active = 1` 与数据权限条件
- [ ] 删除前检查下游依赖

**安全**：敏感字段加密/脱敏、JWT 鉴权、角色权限校验、无 `${}` 拼接

**测试**：单测覆盖正常/异常/边界，insert/update 逐字段断言，覆盖率达标

**性能**：索引合理、无 N+1 查询、分页保护

### 16.3 Git 提交规范

```
<type>(<scope>): <subject>

<body>

<footer>
```

type：`feat` / `fix` / `docs` / `style` / `refactor` / `test` / `chore`

```
feat(order): add order export feature

- Add order export API endpoint
- Implement permission control
- Add unit tests

Closes #123
```


---

# 第二部分：数据库设计规范

## 十七、基础约定

| 项目 | 要求 |
|------|------|
| 数据库 | MySQL 8.0 |
| 存储引擎 | 统一 `InnoDB`（支持事务、行级锁） |
| 字符集 | `utf8mb4`（支持 emoji 与特殊字符） |
| 排序规则 | `utf8mb4_0900_ai_ci`（MySQL 8.0 默认，不区分大小写） |
| 外键 | **不使用物理外键**，关联关系由应用层校验，用普通索引 + 字段注释表达 |
| 删除策略 | 一律逻辑删除（`active = 0`），禁止物理删除 |
| 表注释 | 每张表必须有英文 `COMMENT` |
| 字段注释 | 每个字段必须有英文 `COMMENT`，枚举值含义写在注释里，格式 `(0: X, 1: Y)` |

## 十八、命名规范

### 18.1 表命名

- 全小写 + 下划线（snake_case），使用单数业务名词：`order`、`product`、`file_repository`
- 系统表使用 `sys_` 前缀：`sys_user`、`sys_token_blacklist`
- 关联表使用 `A_B_binding` / `user_tenant` 风格：`order_item_binding`、`user_tenant`
- 日志/统计表使用 `_log` / `_stats` 后缀：`process_log`、`token_stats`

### 18.2 字段命名

- 全小写 + 下划线（snake_case），业务含义明确
- 布尔/标志字段使用 `is_` 前缀或语义化名称：`is_public`、`is_admin`、`enable_search`
- 时间字段使用 `_time` 后缀：`create_time`、`update_time`、`last_login_time`
- 外键引用字段使用 `{关联表}_id`：`user_id`、`provider_id`、`tenant_id`
- JSON 配置字段按内容命名：`config_json`、`env_bindings`、`item_list`

### 18.3 索引命名

| 类型 | 前缀 | 示例 |
|------|------|------|
| 唯一索引 | `uk_` | `uk_callback_key`、`uk_tenant_name` |
| 普通索引 | `idx_` | `idx_tenant_id`、`idx_user_id`、`idx_create_time` |
| 组合索引 | `idx_字段1_字段2`（按选择性/查询顺序排列） | `idx_type_status_active` |

## 十九、通用字段规范

### 19.1 业务表必备字段

所有可管理的业务表必须包含以下通用字段：

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|------|------|------|--------|------|
| `id` | BIGINT | PRIMARY KEY, AUTO_INCREMENT | - | 主键 |
| `tenant_id` | BIGINT | NOT NULL | `1` | 所属租户 ID（多租户项目必备，见 19.3） |
| `status` | TINYINT(1) | NOT NULL | `1` | 状态（0: Disabled, 1: Enabled） |
| `is_public` | TINYINT(1) | NOT NULL | `1` 或 `0` | 数据可见性（0: Private, 1: Public），需要数据权限的表必加 |
| `creator` | VARCHAR(100) | - | NULL | 创建人用户名（数据权限过滤依据） |
| `active` | TINYINT(1) | NOT NULL | `1` | 逻辑删除标记（0: Deleted, 1: Active） |
| `create_time` | DATETIME | NOT NULL | `CURRENT_TIMESTAMP` | 创建时间 |
| `update_time` | DATETIME | NOT NULL | `CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | 更新时间 |

```sql
`id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'Order ID',
`tenant_id`   bigint       NOT NULL DEFAULT '1'    COMMENT 'Tenant ID',
`status`      tinyint(1)            DEFAULT '1'    COMMENT 'Status (0: Disabled, 1: Enabled)',
`is_public`   tinyint(1)            DEFAULT '1'    COMMENT 'Public visibility (0: Private, 1: Public)',
`creator`     varchar(100)          DEFAULT NULL   COMMENT 'Creator',
`active`      tinyint(1)            DEFAULT '1'    COMMENT 'Active status (0: Deleted, 1: Active)',
`create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
`update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
```

### 19.2 日志/流水表的精简字段

只增不改的日志、统计表可省略 `status`、`active`、`update_time`，但必须保留：

- `id` 自增主键
- 关联检索字段（业务主键、会话标识等）并建索引
- 时间字段（`ts` 或 `create_time`）

### 19.3 租户字段与拦截器（多租户项目）

- 业务表 `tenant_id` 必须 `NOT NULL DEFAULT 1`，由 MyBatis 租户拦截器在 INSERT 时自动填充、在 SELECT/UPDATE/DELETE 时自动过滤
- 系统级表（租户表、用户表、登录凭证表等）不需要租户过滤，需加入拦截器排除列表并注释原因

### 19.4 唯一约束与逻辑删除的配合

带逻辑删除的唯一字段，唯一键应把 `active` 纳入，避免已删除记录阻塞新建：

```sql
UNIQUE KEY `uk_tenant_name` (`tenant_id`, `name`, `active`),
UNIQUE KEY `uk_tenant_key_active` (`tenant_id`, `env_key`, `active`)
```

## 二十、数据类型规范

| 场景 | 类型 | 说明 |
|------|------|------|
| 主键 / 外键引用 | `BIGINT` | 自增主键统一 `BIGINT AUTO_INCREMENT` |
| 状态 / 标志位 | `TINYINT(1)` | 取值 0/1，注释写明含义 |
| 短字符串（名称、类型、角色） | `VARCHAR(20~200)` | 按实际业务上限设置 |
| 长字符串（URL、命令、Key） | `VARCHAR(500)` | 如 `url`、`command`、`api_key` |
| 大文本（描述、正文、JSON） | `TEXT` | `description`、`system_prompt`、各类 JSON 列表 |
| 超大文本（消息内容） | `MEDIUMTEXT` | 聊天消息等大内容 |
| 金额 / 价格 | `DECIMAL(10, 4)` | 禁止使用浮点类型 |
| 时间 | `DATETIME` | 统一使用 `DATETIME`，不使用 `TIMESTAMP` |
| 计数 | `BIGINT` | token/次数等计数用 `BIGINT DEFAULT '0'` |

**其他要求**：

- NOT NULL 字段必须有默认值或明确的写入路径；可空字段注释中说明用途
- 枚举值用 `VARCHAR`（如类型、来源、模式）或 `TINYINT`（如状态），取值含义全部写进注释
- 布尔语义字段禁止使用 `CHAR(1)`/字符串，统一 `TINYINT(1)`

## 二十一、JSON 字段存储

列表型/配置型数据使用 JSON 字符串存储于 `TEXT` 字段，字段注释必须给出 JSON 结构示例：

```sql
`headers`  text DEFAULT NULL COMMENT 'HTTP headers JSON: [{"key":"Authorization","value":"Bearer xxx","secret":true}]',
`envs`     text DEFAULT NULL COMMENT 'Env vars JSON: [{"key":"API_KEY","value":"sk-xxx","secret":true}]',
`items`    text COMMENT 'Item list (JSON format)',
```

**使用原则**：

- 不需要按元素检索、整体读写的配置 → JSON 字段
- 需要按元素关联、检索、级联维护的关系 → 拆独立关联表（见第二十二节绑定表模式）
- 含敏感值的 JSON 条目使用 `secret: true` 标记，落库前由加密工具统一加密

## 二十二、关联表（绑定表）设计

多对多关系拆独立绑定表：

```sql
CREATE TABLE IF NOT EXISTS order_product_binding (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT NOT NULL,
    product_id   BIGINT NOT NULL,
    quantity     INT      DEFAULT 1,
    extra_config TEXT     DEFAULT NULL COMMENT 'JSON array of extra configuration snapshots',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_order_product_binding_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**要求**：

- 主表引用字段必须 `NOT NULL` 并建索引（索引名含表名前缀，避免跨表重名）
- 绑定属性（开关、快照、个性化配置）放在绑定表上，不污染主表
- 两端主键之外如需唯一约束，建组合唯一键

## 二十三、索引设计规范

**必须建索引**：

- 所有 `tenant_id` 字段（多租户项目）
- 所有外键引用字段（`user_id`、`order_id` 等）
- 列表页常用筛选字段（`status`、`enabled`、`creator`、时间字段）
- 唯一业务字段（`uk_` 唯一键）

**示例**：

```sql
KEY `idx_tenant_id` (`tenant_id`),
KEY `idx_user_id` (`user_id`),
KEY `idx_create_time` (`create_time`),
KEY `idx_type_status_active` (`type`, `status`, `active`),
UNIQUE KEY `uk_callback_key` (`callback_key`)
```

**注意事项**：

- 组合索引字段顺序遵循最左前缀原则，等值条件在前、范围/排序字段在后
- 单表索引数量建议不超过 5 个，避免写入放大
- 模糊查询 `LIKE '%keyword%'` 不走索引，大表搜索需另行方案

## 二十四、建表语句模板

```sql
CREATE TABLE IF NOT EXISTS `example` (
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`        varchar(100) NOT NULL COMMENT 'Name',
    `description` text COMMENT 'Description',
    `type`        varchar(20)  NOT NULL COMMENT 'Type (typeA/typeB)',
    `status`      tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`   tinyint(1) DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`     varchar(100)          DEFAULT NULL COMMENT 'Creator',
    `active`      tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_name` (`tenant_id`, `name`, `active`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Example table';
```

## 二十五、Flyway 迁移规范

### 25.1 目录与命名

- 所有脚本位于 `resources/db/migration/`
- 命名格式：`V{version}__{description}.sql`，全小写短横线描述
  - 结构变更：`V2__add_example_table.sql`
  - 数据初始化：`V3__seed_default_data.sql`（`seed` 前缀）
- 版本号单调递增，**禁止跳号复用、禁止修改已执行脚本、禁止删除历史脚本**

### 25.2 脚本编写规则

- 建表一律 `CREATE TABLE IF NOT EXISTS`
- 结构变更使用 `ALTER TABLE ADD COLUMN ... AFTER ...`，**禁止 DROP + CREATE 重建表**
- 一个脚本只做一类变更，文件头部注释说明变更目的
- 初始化数据使用 `INSERT IGNORE`，保证幂等可重放
- 逻辑删除优先于删除数据；确需清理数据须单独脚本并评审

```sql
-- V3: Add i18n support columns
ALTER TABLE `product`
    ADD COLUMN `display_name_zh` varchar(200) DEFAULT NULL COMMENT 'Display name (Chinese, for i18n zh-CN locale)'
    AFTER `display_name`;
```

### 25.3 配置与验证

`application.yml` 关键配置：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    validate-on-migrate: true
    clean-disabled: true
```

- 部署后通过 `flyway_schema_history` 表确认版本
- 社区版不支持自动回滚：回滚需新写反向迁移脚本
- 生产执行迁移前必须备份；先在数据副本上验证

## 二十六、测试库规范（schema-test.sql）

Mapper 集成测试（Testcontainers）使用 `src/test/resources/schema-test.sql` 初始化：

- 包含被测表的完整结构（与生产表结构保持一致）
- 每表预置 3~5 条数据，覆盖三类场景：
  - 正常数据（`active = 1, status = 1`）
  - 已删除数据（`active = 0`，验证逻辑删除过滤）
  - 已禁用数据（`status = 0`，验证状态筛选）
- 生产表结构变更（新增迁移脚本）时，必须同步更新 `schema-test.sql`

## 二十七、敏感数据存储

| 数据 | 存储方式 |
|------|----------|
| 用户密码 | BCrypt 哈希（`varchar(100)`），禁止明文 |
| API Key | SHA-256 哈希（唯一索引）+ 展示前缀；原始 Key 仅创建时返回一次 |
| 需回显的密钥 | AES 加密存储（`varchar(500)`） |
| JSON 中的敏感条目 | `secret: true` 标记 + 加密工具加密落库 |
| Token 黑名单 | 存储哈希 + 过期时间，配合组合索引 `(token_hash, expire_time)` |

## 二十八、数据库设计检查清单

新增/修改表前逐项确认：

**结构**：

- [ ] 表名、字段名符合 snake_case 命名，表有英文注释
- [ ] 每个字段都有英文 COMMENT，枚举值含义齐全
- [ ] 业务表包含通用字段（`id`、`status`、`active`、`create_time`、`update_time`；多租户加 `tenant_id`）
- [ ] 需要数据权限的表包含 `is_public` + `creator`
- [ ] 唯一键考虑了逻辑删除（纳入 `active`）

**索引**：

- [ ] `tenant_id`、外键引用字段、常用筛选字段均有索引
- [ ] 索引命名 `uk_` / `idx_` 规范

**迁移**：

- [ ] 新增 Flyway 脚本，版本号递增，命名清晰
- [ ] `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE`，无破坏性操作
- [ ] 种子数据 `INSERT IGNORE` 幂等
- [ ] 同步更新 `schema-test.sql` 并补充 Mapper 集成测试

---

**文档版本**: v1.0
**技术栈**: Spring Boot 3.x + Kotlin + MyBatis + PageHelper + MySQL 8.0 + Flyway + Testcontainers
**适用范围**: 所有采用该技术栈的后端项目

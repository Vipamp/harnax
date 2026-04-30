# 项目上下文规范 (Project Context)

> **生成日期**: 2026-04-28  
> **最后更新**: 2026-04-28  
> **维护者**: AI Assistant  
> **用途**: 记录 vipclaw 项目的完整技术栈、架构约定、开发规范，为 AI 代理和开发者提供项目上下文

---

## 1. 技术栈及版本

### 1.1 后端技术栈

#### 核心框架

| 组件              | 版本     | 说明              |
|-----------------|--------|-----------------|
| **Spring Boot** | 3.5.8  | 核心框架，通过父 POM 管理 |
| **Java**        | 21     | JDK 版本          |
| **Kotlin**      | 2.2.20 | 主要开发语言          |
| **Maven**       | 3.6+   | 构建工具            |

#### 数据库相关

| 组件                              | 版本                                  | 说明       |
|---------------------------------|-------------------------------------|----------|
| **MyBatis Spring Boot Starter** | 3.0.4                               | ORM 框架   |
| **MySQL Connector/J**           | (Spring Boot 管理)                    | MySQL 驱动 |
| **HikariCP**                    | 5.0.1                               | 数据库连接池   |
| **PageHelper**                  | 2.1.0 (Spring Boot集成) / 6.1.0 (核心库) | 分页插件     |

#### 安全与认证

| 组件                  | 版本               | 说明       |
|---------------------|------------------|----------|
| **Spring Security** | (Spring Boot 管理) | 安全框架     |
| **JJWT (Java JWT)** | 0.12.3           | JWT 令牌处理 |
| **jBCrypt**         | 0.4              | 密码加密     |

#### API 文档

| 组件                    | 版本    | 说明                         |
|-----------------------|-------|----------------------------|
| **Springdoc OpenAPI** | 2.3.0 | Swagger 3 / OpenAPI 3 文档生成 |

#### AI 智能体

| 组件               | 版本                  | 说明       |
|------------------|---------------------|----------|
| **AgentScope**   | 1.0.10              | AI 智能体框架 |
| **Eclipse JGit** | (AgentScope BOM 管理) | Git 操作库  |

#### Web 与 HTTP

| 组件                 | 版本               | 说明       |
|--------------------|------------------|----------|
| **Spring Web MVC** | (Spring Boot 管理) | REST API |
| **Spring WebFlux** | (Spring Boot 管理) | 响应式编程    |

#### 定时任务

| 组件                | 版本               | 说明     |
|-------------------|------------------|--------|
| **Spring Quartz** | (Spring Boot 管理) | 定时任务调度 |

#### JSON 处理

| 组件                         | 版本               | 说明        |
|----------------------------|------------------|-----------|
| **Jackson Databind**       | (Spring Boot 管理) | JSON 序列化  |
| **Jackson Module Kotlin**  | (Spring Boot 管理) | Kotlin 支持 |
| **Jackson Dataformat XML** | (Spring Boot 管理) | XML 处理    |

#### 测试框架

| 组件                         | 版本               | 说明             |
|----------------------------|------------------|----------------|
| **Spring Boot Test**       | (Spring Boot 管理) | 集成测试           |
| **Mockito Kotlin**         | 5.4.0            | Kotlin Mock 框架 |
| **Mockito JUnit Jupiter**  | 5.10.0           | Mock 测试        |
| **TestContainers (MySQL)** | 1.19.3           | 容器化测试          |
| **TestContainers JUnit**   | 1.19.3           | 容器测试集成         |
| **MyBatis Test**           | 3.0.3            | MyBatis 测试     |

#### 构建插件

| 插件                           | 版本     | 说明               |
|------------------------------|--------|------------------|
| **Spring Boot Maven Plugin** | 3.5.8  | Spring Boot 打包   |
| **Kotlin Maven Plugin**      | 2.2.20 | Kotlin 编译        |
| **Maven Compiler Plugin**    | 3.11.0 | Java 编译          |
| **Kotlin All-Open**          | 2.2.20 | Kotlin Spring 支持 |

### 1.2 前端技术栈

#### 核心框架

| 组件             | 版本       | 说明        |
|----------------|----------|-----------|
| **React**      | 18.3.1   | UI 框架     |
| **TypeScript** | 5.6.3    | 类型系统      |
| **Umi Max**    | 4.0.7    | 企业级前端应用框架 |
| **Node.js**    | >=20.0.0 | 运行环境      |

#### UI 组件库

| 组件                                   | 版本     | 说明            |
|--------------------------------------|--------|---------------|
| **Ant Design**                       | 5.25.4 | 企业级 UI 组件库    |
| **Ant Design Pro Components**        | 2.8.10 | 高级业务组件        |
| **Ant Design Icons**                 | 5.6.1  | 图标库           |
| **Ant Design Charts**                | 2.6.7  | 图表组件          |
| **Ant Design Plots**                 | 2.6.8  | 可视化图表         |
| **Antd Style**                       | 3.7.0  | 样式方案          |
| **Ant Design v5 Patch for React 19** | 1.0.3  | React 19 兼容补丁 |

#### 工具库

| 组件                           | 版本      | 说明                          |
|------------------------------|---------|-----------------------------|
| **Day.js**                   | 1.11.13 | 日期处理库                       |
| **Classnames**               | 2.5.1   | CSS 类名处理                    |
| **Crypto-JS**                | 4.2.0   | 加密算法库                       |
| **React Markdown**           | 10.1.0  | Markdown 渲染                 |
| **React Syntax Highlighter** | 16.1.1  | 代码语法高亮                      |
| **Remark GFM**               | 4.0.1   | GitHub Flavored Markdown 支持 |

#### 开发工具

| 组件                  | 版本              | 说明             |
|---------------------|-----------------|----------------|
| **Biome**           | 2.0.6           | 代码格式化和 linting |
| **Jest**            | 30.0.4          | 单元测试框架         |
| **Testing Library** | 10.4.0 / 16.0.1 | 测试工具库          |
| **Husky**           | 9.1.7           | Git hooks 管理   |
| **Lint-staged**     | 16.1.2          | 暂存文件 linting   |
| **Commitlint**      | 19.5.0          | Git 提交规范       |
| **Cross-env**       | 7.0.3           | 跨平台环境变量设置      |

---

## 2. 分层架构约定

### 2.1 模块结构和依赖关系

```
vipclaw (父 POM)
├── vipclaw-common          # 公共工具模块（无依赖）
├── vipclaw-agent           # 智能体模块（父模块）
│   └── vipclaw-agent-core  # 智能体核心实现（依赖 vipclaw-common）
├── vipclaw-channel         # 多渠道集成模块（依赖 vipclaw-agent-core）
└── vipclaw-admin           # 管理后台服务（主应用，依赖所有模块）
```

**依赖关系**：

```
vipclaw-admin → vipclaw-channel → vipclaw-agent-core → vipclaw-common
vipclaw-admin → vipclaw-agent-core → vipclaw-common
vipclaw-admin → vipclaw-common
```

### 2.2 各模块职责

#### vipclaw-common

- **职责**：提供公共工具类、通用 DTO、分页对象、错误码定义
- **包结构**：
  ```
  com.vipamp.vipclaw.common
  ├── error/        # 错误码和异常处理
  ├── log/          # 日志工具
  └── page/         # 分页相关（PageInfo 等）
  ```
- **依赖**：无内部依赖

#### vipclaw-agent-core

- **职责**：AI 智能体核心逻辑，Agent 管理、会话管理、PlanNote 管理
- **包结构**：
  ```
  com.vipamp.vipclaw.agent
  ├── adaptor/      # 适配器接口和实现
  ├── message/      # 消息处理
  ├── session/      # 会话管理
  └── [Agent相关类]
  ```
- **依赖**：vipclaw-common、AgentScope

#### vipclaw-channel

- **职责**：多渠道集成（钉钉、飞书、企业微信、HTTP 通用通道）
- **包结构**：
  ```
  com.vipamp.vipclaw.channel
  ├── adaptor/      # 渠道适配器
  ├── demo/         # 示例代码
  ├── message/      # 消息处理
  └── session/      # 会话管理
  ```
- **依赖**：vipclaw-agent-core

#### vipclaw-admin（主应用模块）

- **职责**：管理后台主应用，提供 REST API、Web 界面后端支持、安全认证、定时任务
- **包结构**：
  ```
  com.vipamp.vipclaw
  ├── VipclawAdminApplication.kt    # Spring Boot 启动类
  ├── admin/                        # 管理后台核心代码
  │   ├── common/       # 通用组件（统一响应、错误码等）
  │   ├── config/       # 配置类（Security、MyBatis、OpenAPI、WebMvc 等）
  │   ├── controller/   # REST 控制器（13 个 Controller）
  │   ├── service/      # 业务逻辑层（16 个 Service）
  │   ├── mapper/       # MyBatis Mapper 接口（16 个 Mapper）
  │   ├── entity/       # 数据库实体（16 个 Entity）
  │   ├── dto/          # 数据传输对象（38 个 DTO）
  │   ├── exception/    # 异常处理
  │   ├── job/          # 定时任务
  │   ├── runner/       # 启动运行器
  │   └── util/         # 工具类
  └── ascopagent/       # AgentScope 智能体集成（6 个子模块）
  ```
- **资源配置**：
  ```
  src/main/resources/
  ├── application.yml           # 主配置文件
  ├── edition.properties        # 版本配置（personal/enterprise/public）
  ├── db/
  │   ├── schema.sql            # 数据库初始化脚本
  │   ├── test-data.sql         # 测试数据
  │   └── migration/            # 数据库迁移脚本（V2-V7）
  └── mapper/                   # MyBatis XML 映射文件（16 个）
  ```
- **依赖**：vipclaw-agent-core、vipclaw-channel、vipclaw-common、Spring Boot 全家桶
- **Profile 配置**：
    - personal（默认）：个人版
    - enterprise：企业版
    - public：公网版
- **启动类**：`VipclawAdminApplication.kt`
- **端口**：8080
- **API 文档**：http://localhost:8080/swagger-ui.html

#### vipclaw-webui（前端）

- **职责**：管理后台前端界面
- **技术栈**：React + TypeScript + Umi Max + Ant Design Pro
- **目录结构**：
  ```
  src/
  ├── pages/        # 页面组件
  ├── services/     # API 服务
  ├── components/   # 通用组件
  ├── utils/        # 工具函数
  └── locales/      # 国际化
  ```

### 2.3 分层架构（后端）

**标准分层**：

```
Controller → Service → Mapper → Entity
    ↓          ↓         ↓
   DTO      DTO/Entity   XML
```

**职责划分**：

- **Controller**：接收 HTTP 请求、参数校验、调用 Service、返回响应
- **Service**：业务逻辑处理、事务管理、调用 Mapper
- **Mapper**：数据库操作（CRUD）、SQL 映射
- **Entity**：数据库表映射对象
- **DTO**：数据传输对象（请求/响应）

---

## 3. 数据库实体规范

### 3.1 表 Schema 规范

#### 命名规范

- **表名**：小写字母 + 下划线，业务前缀（如 `sys_user`、`mcp_server`）
- **字段名**：小写字母 + 下划线
- **主键**：统一使用 `id BIGINT(20) NOT NULL AUTO_INCREMENT`
- **索引**：`idx_字段名`（普通索引）、`uk_字段名`（唯一索引）

#### 通用字段（审计字段）

```sql
`id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
`status` TINYINT(1) DEFAULT 1 COMMENT '是否激活(0:否,1:是)',
`create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
`active` TINYINT(1) DEFAULT 1 COMMENT '是否逻辑删除',
PRIMARY KEY (`id`)
```

#### 示例表结构（实际项目表）

```sql
-- 用户表
CREATE TABLE `sys_user`
(
    `id`              BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    `username`        VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`        VARCHAR(100) NOT NULL COMMENT '密码',
    `nickname`        VARCHAR(50)  NOT NULL COMMENT '昵称',
    `email`           VARCHAR(100) NOT NULL COMMENT '邮箱',
    `phone`           VARCHAR(20)  NOT NULL COMMENT '手机号',
    `gender`          TINYINT(2) DEFAULT 2 COMMENT '性别 (0:女 1:男 2:未知)',
    `avatar`          VARCHAR(255) DEFAULT '' COMMENT '头像 URL',
    `status`          TINYINT(2) DEFAULT 1 COMMENT '状态 (0:禁用 1:使用)',
    `is_admin`        TINYINT(2) DEFAULT 0 COMMENT '是否是管理员（0:否，1:是）',
    `active`          TINYINT(2) DEFAULT 1 COMMENT '状态 (0:已删除 1:未删除)',
    `last_login_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次登陆时间',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- MCP 服务表
CREATE TABLE `mcp_server`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'MCP ID',
    `name`        VARCHAR(100) NOT NULL COMMENT 'MCP 名称',
    `description` TEXT         DEFAULT NULL COMMENT 'MCP 描述',
    `type`        VARCHAR(20)  NOT NULL COMMENT 'MCP 类型（stdio/sse/streamablehttp）',
    `command`     VARCHAR(500) DEFAULT NULL COMMENT '执行命令（仅 stdio 类型生效）',
    `url`         VARCHAR(500) DEFAULT NULL COMMENT '服务地址（sse/streamablehttp 类型生效）',
    `status`      TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`      TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务表';
```

#### 约束规范

- 所有表使用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci`
- 必须有 `PRIMARY KEY`
- 外键查询字段必须添加索引
- 使用 `COMMENT` 注释所有字段
- 唯一约束使用 `UNIQUE KEY uk_字段名`

### 3.2 Entity 类规范（实际项目示例）

**Kotlin Entity 示例**：

```kotlin
package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "用户实体")
class SysUserEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "用户 ID")
    var id: Long = 0

    @Schema(description = "用户名")
    var username: String = ""

    @Schema(description = "密码（BCrypt 加密）")
    var password: String = ""

    @Schema(description = "昵称")
    var nickname: String = ""

    @Schema(description = "邮箱")
    var email: String = ""

    @Schema(description = "手机号")
    var phone: String = ""

    @Schema(description = "是否管理员（0:否，1:是）")
    var isAdmin: Int = 0

    @Schema(description = "状态（0:已删除 1:未删除）")
    var active: Int = 1

    @Schema(description = "创建时间")
    var createTime: LocalDateTime? = null

    @Schema(description = "更新时间")
    var updateTime: LocalDateTime? = null
}
```

**命名规范**：

- Entity 类名：`{TableName}Entity`（如 `SysUserEntity`、`McpServerEntity`）
- 包路径：`com.vipamp.vipclaw.admin.entity`
- 使用 `@Schema` 注解描述字段
- 所有属性必须有默认值（Kotlin 空安全）

### 3.3 Controller 接口规范

#### RESTful API 设计

- **路径**：`/admin/{resource}`（复数形式）
- **HTTP 方法**：
    - `GET /admin/users` - 获取列表
    - `GET /admin/users/{id}` - 获取单个
    - `POST /admin/users` - 创建
    - `PUT /admin/users/{id}` - 更新
    - `DELETE /admin/users/{id}` - 删除（逻辑删除）

#### 统一响应格式

```kotlin
data class Result<T>(
    val code: Int,
    val message: String,
    val data: T?
)
```

**成功响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    ...
  }
}
```

**错误响应**：

```json
{
  "code": 500,
  "message": "错误描述",
  "data": null
}
```

#### 分页响应

```kotlin
data class PageResult<T>(
    val total: Long,
    val list: List<T>,
    val pageNum: Int,
    val pageSize: Int
)
```

#### OpenAPI 注解

```kotlin
@RestController
@RequestMapping("/admin/users")
@Tag(name = "用户管理", description = "用户管理相关接口")
class UserController {

    @Operation(summary = "获取用户列表", description = "分页获取用户列表")
    @GetMapping
    fun list(
        @Parameter(description = "页码") @RequestParam pageNum: Int = 1,
        @Parameter(description = "每页数量") @RequestParam pageSize: Int = 10
    ): Result<PageResult<UserResponse>> {
        // ...
    }
}
```

---

## 4. 业务域划分

### 4.1 用户管理 (User Management)

- **路径**：`/system/user`
- **功能**：
    - 用户 CRUD
    - 角色权限管理
    - 登录认证（JWT）
    - 密码加密（BCrypt）
- **数据库表**：`sys_user`
- **前端页面**：`src/pages/user/management/`

### 4.2 智能体管理 (Agent Management)

- **路径**：`/agent/manager`
- **功能**：
    - 智能体 CRUD
    - 智能体配置
    - 智能体测试
- **模块**：vipclaw-agent-core
- **前端页面**：`src/pages/agent/`

### 4.3 会话管理 (Session Management)

- **路径**：`/agent/session`
- **功能**：
    - 会话创建和管理
    - 消息流式传输（SSE）
    - 工具调用和确认
    - 计划管理（PlanNote）
- **数据库表**：`plan_note`
- **前端页面**：`src/pages/session/`

### 4.4 MCP 服务管理 (MCP Server Management)

- **路径**：`/context/mcp`
- **功能**：
    - MCP 服务 CRUD
    - 连通性测试
    - 工具列表查看
    - 服务详情
- **前端页面**：`src/pages/mcp/`

### 4.5 模型管理 (Model Management)

- **路径**：`/context/model`
- **功能**：
    - 模型供应商管理
    - 对话模型管理
    - 嵌入模型管理
    - 价格配置
- **前端页面**：`src/pages/model/`

### 4.6 技能管理 (Skill Management)

- **路径**：`/context/skill`
- **功能**：
    - 技能仓库管理
    - 技能同步（Git）
    - 技能详情（SKILL.md 渲染）
    - 资源文件管理
- **前端页面**：`src/pages/skill/`

### 4.7 通道管理 (Channel Management)

- **路径**：`/context/channel`
- **功能**：
    - 渠道配置（钉钉、飞书、企业微信、HTTP）
    - 渠道状态管理
- **模块**：vipclaw-channel
- **前端页面**：`src/pages/channel/`

### 4.8 定时任务管理 (Job Management)

- **路径**：`/job/manager`
- **功能**：
    - 定时任务配置
    - 任务日志查询
    - 任务执行控制
- **技术栈**：Spring Quartz
- **前端页面**：`src/pages/job/`

### 4.9 Token 监控 (Token Monitor)

- **路径**：`/system/token-monitor`
- **功能**：
    - Token 用量统计
    - 价格趋势分析
    - 多维度图表展示
- **数据库表**：`token_stats`
- **前端页面**：`src/pages/token-monitor/`

---

## 5. 单元测试开发规范

### 5.1 测试框架

**后端测试**：

- JUnit 5（通过 Spring Boot Test 管理）
- Mockito Kotlin 5.4.0
- TestContainers 1.19.3（MySQL 容器）
- MyBatis Test 3.0.3

**前端测试**：

- Jest 30.0.4
- Testing Library 10.4.0 / 16.0.1

### 5.2 测试分层

#### 单元测试（Unit Test）

- **目标**：测试单个类/方法的功能
- **范围**：Service 层、工具类
- **Mock**：使用 Mockito Kotlin Mock 依赖
- **位置**：`src/test/kotlin/com/vipamp/vipclaw/admin/service/`

**示例**：

```kotlin
@Test
fun `should create user successfully`() {
    // Arrange
    val request = CreateUserRequest(...)
    whenever(userMapper.insert(any())).thenReturn(1)

    // Act
    val result = userService.createUser(request)

    // Assert
    assertNotNull(result)
    assertEquals("success", result.message)
    verify(userMapper).insert(any())
}
```

#### 集成测试（Integration Test）

- **目标**：测试多个组件的协作
- **范围**：Controller 层、完整流程
- **注解**：`@SpringBootTest`
- **位置**：`src/test/kotlin/com/vipamp/vipclaw/admin/controller/`

**示例**：

```kotlin
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `should return user list`() {
        mockMvc.perform(get("/api/users"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
    }
}
```

#### Mapper 测试

- **目标**：测试数据库操作
- **注解**：`@MybatisTest`
- **数据库**：TestContainers MySQL 容器
- **位置**：`src/test/kotlin/com/vipamp/vipclaw/admin/mapper/`

**示例**：

```kotlin
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")
    }

    @Autowired
    lateinit var userMapper: UserMapper

    @Test
    fun `should insert and select user`() {
        val user = UserEntity(...)
        userMapper.insert(user)

        val found = userMapper.selectById(user.id)
        assertNotNull(found)
        assertEquals(user.email, found?.email)
    }
}
```

### 5.3 测试命名规范

**测试类命名**：

- `{ClassName}Test`（如 `UserServiceTest`）

**测试方法命名**：

- 使用反引号 + 描述性文本（Kotlin 特性）
- 格式：`should [expected behavior] when [condition]`
- 示例：
    - `should create user successfully`
    - `should throw exception when user not found`
    - `should return empty list when no users exist`

### 5.4 测试配置

**测试 Profile**：

- 使用 `application-test.yml`
- 配置 TestContainers 数据源
- 关闭不必要的安全配置

**TestContainers 配置**：

```kotlin
@Testcontainers
@ActiveProfiles("test")
class XxxTest {
    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
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

### 5.5 测试覆盖率要求

- **Service 层**：≥ 80%
- **Mapper 层**：≥ 90%
- **Controller 层**：≥ 70%
- **工具类**：≥ 90%

### 5.6 前端测试规范

**组件测试**：

```typescript
import { render, screen } from '@testing-library/react';
import UserManagement from './index';

describe('UserManagement', () => {
  it('should render user list', () => {
    render(<UserManagement />);
    expect(screen.getByText('用户管理')).toBeInTheDocument();
  });
});
```

**运行测试**：

```bash
npm run test              # 运行所有测试
npm run test:coverage     # 生成覆盖率报告
```

---

## 6. 多 Profile 配置

### 6.1 后端 Profile

| Profile        | 环境   | 日志级别  | 数据库            | 激活方式                                  |
|----------------|------|-------|----------------|---------------------------------------|
| **personal**   | 个人开发 | DEBUG | 本地 MySQL       | 默认                                    |
| **enterprise** | 企业内部 | INFO  | 内部数据库          | `--spring.profiles.active=enterprise` |
| **public**     | 公网部署 | WARN  | 公网数据库          | `--spring.profiles.active=public`     |
| **test**       | 测试环境 | DEBUG | TestContainers | 自动（测试时）                               |

### 6.2 前端环境

| 环境             | 变量                             | 说明   |
|----------------|--------------------------------|------|
| **dev**        | `REACT_APP_ENV=dev`            | 开发环境 |
| **personal**   | `REACT_APP_EDITION=personal`   | 个人版  |
| **enterprise** | `REACT_APP_EDITION=enterprise` | 企业版  |
| **public**     | `REACT_APP_EDITION=public`     | 公网版  |

---

## 7. 代码规范

### 7.1 Kotlin 编码规范

- 使用 `data class` 表示 DTO
- 使用 `var` 声明可变属性，`val` 声明只读属性
- 优先使用 `val`（不可变性）
- 使用 `?` 表示可空类型
- 使用 `!!` 仅在确定非空时
- 使用 `?.` 安全调用

### 7.2 Git 提交规范

**提交格式**：

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Type 类型**：

- `feat`: 新功能
- `fix`: 修复 bug
- `docs`: 文档更新
- `style`: 代码格式调整
- `refactor`: 重构
- `test`: 测试相关
- `chore`: 构建/工具相关

**示例**：

```
feat(user): add user management module

- Add user CRUD operations
- Add JWT authentication
- Add unit tests

Closes #123
```

### 7.3 前端代码规范

- 使用 Biome 进行代码格式化和 Lint
- 使用 TypeScript 严格模式
- 组件使用函数式组件 + Hooks
- 使用 Ant Design Pro Components

---

## 8. 数据库迁移规范

### 8.1 SQL 文件管理

**位置**：

- `vipclaw-admin/src/main/resources/db/schema.sql` - 数据库初始化脚本
- `vipclaw-admin/src/main/resources/db/migration/` - Flyway 风格迁移脚本
- `sql/` - 项目根目录的独立 SQL 脚本

**命名规范**：

- 迁移脚本：`V{version}__{description}.sql`（如 `V2__create_token_blacklist_table.sql`）
- 独立脚本：`{feature}_migration.sql` 或 `{table_name}.sql`

**当前迁移版本**：

- V2: 创建 token_blacklist 表
- V3: 添加 sys_user.is_admin 字段
- V4: 添加 is_public 和 creator 字段
- V5: 创建 session 表
- V6: 添加 session.description 字段
- V7: 添加 session.chat_options 字段

### 8.2 迁移脚本示例

```sql
-- 创建表
CREATE TABLE `table_name`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    -- 字段定义
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表描述';

-- 添加字段
ALTER TABLE `table_name`
    ADD COLUMN `column_name` VARCHAR(100) DEFAULT NULL COMMENT '字段描述';

-- 添加索引
ALTER TABLE `table_name`
    ADD UNIQUE KEY `uk_column_name` (`column_name`);
```

---

## 9. vipclaw-admin 模块详细说明

### 9.1 项目配置

**pom.xml 关键配置**：

```xml

<parent>
    <groupId>com.vipamp</groupId>
    <artifactId>vipclaw</artifactId>
    <version>${revision}</version>
</parent>

<artifactId>vipclaw-admin</artifactId>
<packaging>jar</packaging>

        <!-- 关键依赖 -->
<dependencies>
<!-- Spring Boot Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- MyBatis -->
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>3.0.4</version>
</dependency>

<!-- Security + JWT -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.3</version>
</dependency>

<!-- 内部模块 -->
<dependency>
    <groupId>com.vipamp</groupId>
    <artifactId>vipclaw-agent-core</artifactId>
    <version>${revision}</version>
</dependency>
</dependencies>
```

**Kotlin 编译配置**：

```xml

<plugin>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-maven-plugin</artifactId>
    <version>2.2.20</version>
    <configuration>
        <jvmTarget>21</jvmTarget>
        <args>
            <arg>-Xjsr305=strict</arg>
            <arg>-Xannotation-default-target=param-property</arg>
        </args>
        <compilerPlugins>
            <plugin>spring</plugin>
        </compilerPlugins>
    </configuration>
</plugin>
```

**Profile 配置**：

```xml

<profiles>
    <profile>
        <id>personal</id>
        <properties>
            <edition.current>personal</edition.current>
        </properties>
        <activation>
            <activeByDefault>true</activeByDefault>
        </activation>
    </profile>
    <profile>
        <id>enterprise</id>
        <properties>
            <edition.current>enterprise</edition.current>
        </properties>
    </profile>
    <profile>
        <id>public</id>
        <properties>
            <edition.current>public</edition.current>
        </properties>
    </profile>
</profiles>
```

### 9.2 应用配置（application.yml）

**核心配置**：

```yaml
server:
  port: 8080

spring:
  application:
    name: vipclaw-admin

  datasource:
    url: jdbc:mysql://localhost:3306/vipclaw?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      minimum-idle: 5
      maximum-pool-size: 20

  quartz:
    job-store-type: memory

mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.vipamp.vipclaw.admin.entity
  configuration:
    map-underscore-to-camel-case: true

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html

jwt:
  secret: vipclaw-secret-key-2026-vipclaw-admin-backend-jwt-token-authentication
  expiration: 7200000 # 2 小时

edition:
  current: personal
```

### 9.3 数据库表清单

根据 `schema.sql`，当前项目包含以下数据表：

| 表名                | 说明         | 关键字段                                                    |
|-------------------|------------|---------------------------------------------------------|
| `sys_user`        | 用户表        | username, password, nickname, email, phone, is_admin    |
| `mcp_server`      | MCP 服务表    | name, type, command, url, status                        |
| `model_provider`  | 模型服务商表     | name, display_name, api_key, base_url                   |
| `model`           | 模型表        | name, model_name, provider_id, model_type, price        |
| `agent`           | 智能体表       | name, description, model_id, status                     |
| `skill`           | 技能表        | name, repository_url, description, status               |
| `channel`         | 通道表        | name, type, config, status                              |
| `session`         | 会话表        | session_id, agent_id, status, description, chat_options |
| `plan_note`       | 计划表        | session_id, plan_id, name, status                       |
| `token_stats`     | Token 统计表  | date, model_id, input_tokens, output_tokens, cost       |
| `tool_call_log`   | 工具调用日志表    | session_id, tool_name, input, output                    |
| `process_log`     | 流程日志表      | session_id, level, message                              |
| `token_blacklist` | Token 黑名单表 | token, expiry_date                                      |

**数据库初始化脚本位置**：

- 主脚本：`vipclaw-admin/src/main/resources/db/schema.sql`
- 测试数据：`vipclaw-admin/src/main/resources/db/test-data.sql`
- 迁移脚本：`vipclaw-admin/src/main/resources/db/migration/V2-V7`

### 9.4 Controller 清单

当前项目包含 **13 个 Controller**，覆盖以下业务域：

| Controller                | 路径                    | 说明            |
|---------------------------|-----------------------|---------------|
| `UserController`          | `/api/user`           | 用户管理（CRUD、登录） |
| `AuthController`          | `/api/auth`           | 认证相关（登录、登出）   |
| `McpServerController`     | `/api/mcp`            | MCP 服务管理      |
| `ModelProviderController` | `/api/model/provider` | 模型服务商管理       |
| `ModelController`         | `/api/model`          | 模型管理          |
| `AgentController`         | `/api/agent`          | 智能体管理         |
| `SkillController`         | `/api/skill`          | 技能管理          |
| `ChannelController`       | `/api/channel`        | 通道管理          |
| `SessionController`       | `/api/session`        | 会话管理          |
| `PlanNoteController`      | `/api/plan`           | 计划管理          |
| `TokenStatsController`    | `/api/token/stats`    | Token 统计      |
| `ToolCallLogController`   | `/api/tool/log`       | 工具调用日志        |
| `JobController`           | `/api/job`            | 定时任务管理        |

### 9.5 Service 和 Mapper 统计

- **Service 数量**：16 个
- **Mapper 数量**：16 个
- **Entity 数量**：16 个
- **DTO 数量**：38 个
- **Mapper XML**：16 个

### 9.6 测试结构

**测试目录**：

```
src/test/
├── kotlin/com/vipamp/vipclaw/admin/
│   ├── controller/   # Controller 集成测试
│   ├── service/      # Service 单元测试
│   ├── mapper/       # Mapper 测试（TestContainers）
│   └── config/       # 测试配置
└── resources/
    ├── application-test.yml  # 测试配置
    └── schema-test.sql       # 测试数据库初始化
```

**测试框架**：

- JUnit 5
- Mockito Kotlin 5.4.0
- TestContainers MySQL 1.19.3
- MyBatis Test 3.0.3

### 9.7 启动方式

**开发模式**：

```bash
cd vipclaw-admin
mvn spring-boot:run -Dspring-boot.run.profiles=personal
```

**打包运行**：

```bash
mvn clean package -Ppersonal
java -jar target/vipclaw-admin-1.0.0-SNAPSHOT.jar --spring.profiles.active=personal
```

**访问地址**：

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html
- API Docs：http://localhost:8080/v3/api-docs

---

## 10. 文档规范

### 9.1 PRD 文档

- **位置**：`PRD/` 目录
- **格式**：Markdown
- **内容**：需求描述、功能列表、架构设计、API 设计

### 9.2 README 文档

- 每个模块必须有 README.md
- 包含：模块介绍、启动方式、API 文档链接

---

## 11. 常见问题

### 10.1 依赖版本管理

- 所有依赖版本必须在父 POM 或 `doc/rules.md` 中定义
- 不得在子模块中硬编码版本
- 使用 Spring Boot BOM 管理 Spring 相关依赖

### 10.2 数据库连接

- 开发环境使用本地 MySQL
- 测试环境使用 TestContainers
- 生产环境使用配置的数据源

### 10.3 安全注意事项

- 所有密码必须 BCrypt 加密
- JWT 令牌必须设置过期时间
- 敏感配置使用环境变量或加密存储

---

## 附录

### A. 相关文档

- [技术栈规范](../rules.md)
- [架构设计](../../PRD/architecture.md)
- [功能列表](../../PRD/feature_list.md)
- [vipclaw-admin README](../../vipclaw-admin/README.md)

### B. 工具链接

- [Spring Boot 文档](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [MyBatis 文档](https://mybatis.org/mybatis-3/)
- [Kotlin 文档](https://kotlinlang.org/docs/home.html)
- [Ant Design Pro 文档](https://pro.ant.design/)

### C. 快速索引

**模块位置**：

- vipclaw-common：`/vipclaw-common/`
- vipclaw-agent-core：`/vipclaw-agent/vipclaw-agent-core/`
- vipclaw-channel：`/vipclaw-channel/`
- vipclaw-admin：`/vipclaw-admin/`
- vipclaw-webui：`/vipclaw-webui/`

**配置文件**：

- 父 POM：`/pom.xml`
- admin 配置：`/vipclaw-admin/src/main/resources/application.yml`
- 前端配置：`/vipclaw-webui/config/config.ts`

**启动类**：

- `com.vipamp.vipclaw.VipclawAdminApplication`

**数据库脚本**：

- 主脚本：`/vipclaw-admin/src/main/resources/db/schema.sql`
- 迁移：`/vipclaw-admin/src/main/resources/db/migration/`

---

**文档版本**: 2.0.0  
**最后更新**: 2026-04-28  
**维护者**: AI Assistant  
**更新说明**: 新增 vipclaw-admin 模块完整信息，包括包结构、配置、数据库表、Controller 清单等

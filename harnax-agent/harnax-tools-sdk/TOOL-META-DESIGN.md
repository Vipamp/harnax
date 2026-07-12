# @ToolMeta 注解自动注册方案设计

## 核心问题

当前 ToolBox 代码实现与 `agent_tool` 数据库记录完全割裂：
- 新增一个内置工具，既要写 ToolBox 代码，又要手动在 Admin 中创建工具记录
- 修改工具描述/属性后，需要同步修改代码和数据库，容易不一致
- 维护成本高，容易遗漏

## 解决方案：@ToolMeta 注解驱动的自动注册

### 整体思路

- **方法级**：`@Tool`（agentscope）+ `@ToolMeta` 声明每个工具的配置
- 每个 `@Tool` 方法 = 一条 `agent_tool` 记录，可独立启用/禁用
- `@ToolMeta` 标注在方法上，包含 displayName、envParamDefs、needConfirm 等

```kotlin
@Component("email-tool-box")
class EmailToolBox : ToolBox() {
    @Tool(name = "sendEmail", description = "发送邮件")
    @ToolMeta(
        displayName = "Send Email",
        displayNameZh = "发送邮件",
        envParamDefs = [
            ToolEnvParamDef(key = "SMTP_HOST", description = "SMTP 服务器地址"),
            ToolEnvParamDef(key = "SMTP_PASSWORD", description = "SMTP 密码", secret = true),
        ],
        needConfirm = true,
    )
    fun sendEmail(to: String, subject: String, body: String): String = execute { ... }

    @Tool(name = "checkEmail", description = "检查邮件", readOnly = true)
    @ToolMeta(displayName = "Check Email", displayNameZh = "检查邮件")
    fun checkEmail(): String = execute { ... }
}
```

### 架构图

```
+------------------+     @Tool + @ToolMeta      +------------------+
|  ToolBox 实现类   | ─────────────────────────> |   ToolRegistry   |
| (tools-buildin)  |    （方法级注解）            |   (tools-sdk)    |
+------------------+                              +--------+---------+
                                                           |
                                              extractToolMeta()
                                                           |
                                                           v
                                                +----------+----------+
                                                | ToolMetaDescriptor  |
                                                |   （内存模型）        |
                                                +----------+----------+
                                                           |
                                      BuiltinToolAutoRegistrar
                                      （admin 模块，启动时执行）
                                                           |
                                              upsertBuiltinTool()
                                              (每个 @Tool 方法一条记录)
                                                           |
                                                           v
                                                +----------+----------+
                                                |   agent_tool 表      |
                                                |   （MySQL 数据库）   |
                                                +----------+----------+
```

## 组件说明

### 1. `@ToolMeta`（方法级注解，tools-sdk）

应用于每个 `@Tool` 方法，声明该工具的元数据，启动时同步到数据库。

| 属性             | 类型              | 默认值   | 说明                                      |
|------------------|-------------------|----------|-------------------------------------------|
| `displayName`    | String            | `""`     | 英文显示名称                               |
| `displayNameZh`  | String            | `""`     | 中文显示名称                               |
| `envParamDefs`   | ToolEnvParamDef[] | `[]`     | 环境参数定义（key、description、required 等）|
| `timeoutSeconds` | Int               | `0`      | 执行超时时间（秒），0=使用系统默认           |
| `isPublic`       | Boolean           | `true`   | 是否对所有用户公开                          |
| `needConfirm`    | Boolean           | `false`  | 是否需要用户确认                            |

### 2. `@Tool`（方法级注解，来自 agentscope）

| 属性          | 类型    | 默认值   | 说明                              |
|---------------|---------|----------|-----------------------------------|
| `name`        | String  | `""`     | 工具名（默认使用方法名）           |
| `description` | String  | `""`     | 工具描述，发送给 LLM               |
| `readOnly`    | Boolean | `false`  | 该工具是否为只读                   |

### 3. `ToolMetaDescriptor`（tools-sdk）

运行时数据类，由 `ToolRegistry` 通过反射构建：
- 扫描所有 `@Tool` 注解的方法
- 从方法读取 `@ToolMeta`（displayName/displayNameZh/envParamDefs/needConfirm/timeoutSeconds/isPublic）
- 从方法读取 `@NeedConfirmed`（与 @ToolMeta.needConfirm 取或）
- 组合为 `ToolMethodDescriptor` 列表

关键数据结构：
- `ToolMethodDescriptor`：methodName, toolName, displayName, displayNameZh, description, readOnly, needConfirm, envParamDescriptors, timeoutSeconds, isPublic
- `ToolEnvParamDescriptor`：key, description, required, secret, defaultValue
- `ToolMetaDescriptor`：beanName, toolName, **methods**

### 4. `BuiltinToolAutoRegistrar`（admin 模块）

Spring `@Component`，监听 `ApplicationReadyEvent`：
- 从 `ToolRegistry.getAllToolMeta()` 读取所有元数据
- 为每个 `@Tool` **方法**构建一条 `AgentTool` 实体，调用 `upsertBuiltinTool()`
- 每个方法的 `envParamDefs` 独立同步到 `agent_tool_env_param` 表

### 5. `AgentToolMapper.upsertBuiltinTool()`（harnax-entity）

MyBatis Mapper 方法，使用 MySQL `INSERT ... ON DUPLICATE KEY UPDATE`：
- 新记录：插入 `status=1, active=1, creator='SYSTEM'`
- 已有记录：更新 `name, displayName, displayNameZh, description, readOnly, needConfirm, requiredEnvParamKeys, timeoutSeconds, isPublic`
- **不覆盖 `status`**（保留管理员手动禁用的状态）
- 唯一键：`(tenant_id, bean_name, method_name, active)`

## 工具粒度：每个 @Tool 方法 = 一条 agent_tool 记录

- `bean_name` + `method_name` 共同标识一条记录
- 同一个 ToolBox 类的不同方法，对应不同的 agent_tool 记录
- 每条记录可独立启用/禁用、独立设置 needConfirm
- 每条记录可以有独立的环境参数（envParamDefs）
- `HarnessAgentLauncher` 中同一 beanName 只 `addTool()` 一次（去重）

## 术语说明

- **环境参数（env param）**：工具运行前需要配置的外部参数（如 API Key、服务器地址），在 Admin UI 中配置
- **动态参数**：工具运行时由 LLM 提供的参数（如搜索关键词），暂不实现
- 环境参数和动态参数共同构成工具的完整参数列表

## 数据流

```
开发者编写 ToolBox + @Tool + @ToolMeta 注解（方法级）
         │
         ▼
[Admin 启动] ToolRegistry.init() 扫描所有 ToolBox Bean
         │
         ▼
ToolRegistry 解析每个方法的 @Tool + @ToolMeta → ToolMetaDescriptor
         │
         ▼
BuiltinToolAutoRegistrar 监听 ApplicationReadyEvent
         │
         ▼
遍历每个 meta.methods → 构建 AgentTool 实体 → upsertBuiltinTool() → 写入数据库
         │
         ▼
Admin UI 自动展示新工具，无需手动操作
```

## 同步策略与边界

- **只同步 BUILTIN 类型**：CUSTOM 和 HTTP 类型仍由 Admin UI 手动管理
- **Upsert 策略**：以 `(tenant_id, bean_name, method_name, active)` 唯一索引为键
- **不覆盖 status**：管理员可以禁用工具，代码变更不会覆盖禁用状态
- **不自动删除**：代码中移除的 ToolBox 不会自动从 DB 删除（安全考虑）
- **envParamDefs 独立**：每个方法的 envParamDefs 独立同步到 agent_tool_env_param

## 模块依赖关系

```
harnax-tools-sdk        （定义 @ToolMeta、@ToolEnvParamDef、ToolMetaDescriptor、ToolRegistry）
        ↑
harnax-tools-buildin    （ToolBox 实现类，使用 @Tool + @ToolMeta 注解）
        ↑
harnax-admin            （BuiltinToolAutoRegistrar 同步到数据库）
```

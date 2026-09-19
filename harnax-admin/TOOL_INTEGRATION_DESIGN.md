# Harnax 工具（Tools）集成设计方案

> 本文为早期设计方案，仅作历史归档，其中的字段与流程已被实现取代：工具绑定的 `enable_skip` 已由 `V17__drop_tool_binding_enable_skip.sql` 删除，内置工具的新增 / 更新 / 删除统一由 admin 启动时的代码注册收敛。
> 自定义工具（CUSTOM）与 HTTP 工具两类已整体下线，工具只剩内置一类：`agent_tool` 不再有 `type`、`is_public`、`http_url`、`http_method`、`http_headers`、`input_schema`、`output_schema` 这些列，admin 也不对外提供任何工具写接口。当前设计见 `prod_doc/tool-integration-design.zh-CN.md`（英文 `prod_doc/tool-integration-design.en-US.md`），使用口径见 `prod_doc/tool-capability.zh-CN.md`（英文 `prod_doc/tool-capability.en-US.md`）。

## 一、背景与现状分析

### 1.1 agentscope-java 的工具体系

agentscope-java 提供了完善的工具管理框架：

- **核心接口**：`AgentTool`（接口）→ `ToolBase`（抽象基类，含权限）→ 具体实现
- **注册机制**：`@Tool` 注解驱动方法级注册，`Toolkit` 作为中央门面统一管理
- **高级特性**：工具分组、动态激活/停用、MCP 协议集成、外部工具（Schema-only）、子Agent工具

### 1.2 harnax 当前的工具现状

harnax 目前已实现：

- `ToolBox` 抽象类（`harnax-harness-core`）：自定义工具基类，封装日志记录、确认机制
- `TOOL_SET`（`ProviderConsts.kt:19`）：硬编码全局工具集，目前只有 `TimeToolBox`
- `HarnessAgentLauncher.createAgentBase()` 第 159 行：`TOOL_SET.forEach { agentBuilder.addTool(toolBox) }` —— 所有 Agent 共享同一组工具
- `Agent` 实体：有 `mcpList`、`skillList`，**没有 `toolList`**
- `AgentSpec`：有 `externalTools: List<ToolBox>` 和 `mcpServices: List<McpSpec>`，但 `AgentSpecResolver` 只解析了 MCP 和 Skill，**未解析 Tool**

**核心问题：**

1. 工具无法动态扩展 —— 新增工具必须修改 `TOOL_SET` 并重新部署
2. Agent 无法选择工具 —— 所有 Agent 强制使用全部工具
3. 没有 Tool 实体和管理界面 —— 与 MCP Server、Skill 的管理能力不对等

---

## 二、工具分类

| 类型 | 说明 | 实现位置 | 示例 |
|------|------|----------|------|
| **内置工具（BUILTIN）** | 框架自带，代码实现 | `ToolBox` 子类，Spring Bean | `TimeToolBox`、未来可加的 `WeatherToolBox` 等 |
| **MCP 工具（MCP）** | 通过 MCP 协议连接 | 已有，保持不变 | 各类 MCP Server |

> MCP 工具已有完整管理体系（`McpServer` 实体 + Admin CRUD），本方案不重复，只关注内置工具一类。
> 工具只有一类：全部为代码实现的内置工具，由 admin 启动时按 `@Tool` / `@ToolMeta` 注解自动同步写库。

---

## 三、数据库设计

### 3.1 新增 `agent_tool` 表（工具定义）

```sql
CREATE TABLE agent_tool (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '工具 ID',
    tenant_id       BIGINT DEFAULT 1 COMMENT '租户 ID',
    name            VARCHAR(100) NOT NULL COMMENT '工具标识名（snake_case，唯一）',
    display_name    VARCHAR(200) COMMENT '显示名称',
    description     TEXT COMMENT '工具描述（发送给 LLM）',
    bean_name       VARCHAR(200) COMMENT 'Spring Bean 名称',

    -- 通用字段 --
    read_only       TINYINT DEFAULT 0 COMMENT '是否只读工具（0:否, 1:是）',
    need_confirm    TINYINT DEFAULT 0 COMMENT '是否需要人工确认执行（0:不需要, 1:需要）',
    timeout_seconds INT DEFAULT 30 COMMENT '超时时间（秒）',
    status          INT DEFAULT 1 COMMENT '状态（0:禁用, 1:启用）',
    creator         VARCHAR(100) COMMENT '创建者',
    active          INT DEFAULT 1 COMMENT '活跃状态（0:删除, 1:活跃）',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_tenant_name (tenant_id, name)
) COMMENT='Agent 工具定义表';
```

**字段说明：**

- `need_confirm`：工具级别的确认标记
  - `0`（默认）：工具可以直接执行，无需人工确认
  - `1`：工具执行前需要用户确认（如危险操作、写操作等）

### 3.2 Agent 实体新增 `tool_list` 字段

```sql
ALTER TABLE agent
ADD COLUMN tool_list TEXT COMMENT '工具列表 JSON: [{"id":1,"enable_skip":"true","need_confirm":false},...]'
AFTER skill_list;
```

**JSON 结构示例：**

```json
[
  {
    "id": 1,
    "enable_skip": "true",
    "need_confirm": true
  },
  {
    "id": 2,
    "enable_skip": "false",
    "need_confirm": false
  }
]
```

**`needConfirm` 的取值规则：**

| 工具实体 `need_confirm` | Agent 配置时行为 | `tool_list` 中存储值 |
|---|---|---|
| `1`（需要确认） | 展示开关，用户可选择 true 或 false | 用户选择的值 |
| `0`（不需要确认） | 隐藏开关，强制为 false，直接执行 | `false` |

核心原则：**Agent 级别的确认要求不能高于工具实体的设定**。工具实体标记为「安全」的工具，Agent 不能升级为需要确认；工具实体标记为「危险」的工具，Agent 可以选择降级为不需要确认。

---

## 四、模块设计

### 4.1 实体层（harnax-entity）

**新增文件：**

- `entity/AgentTool.kt` —— 工具实体类
- `mapper/AgentToolMapper.kt` —— MyBatis Mapper
- `mapper/xml/AgentToolMapper.xml` —— SQL 映射

**修改文件：**

- `entity/Agent.kt` —— 新增 `toolList: String` 字段
- `entity/dto/AgentSpecInfoResponse.kt` —— 新增 `toolList: String` 字段

### 4.2 工具注册层（harnax-harness-core）

**核心思想：** 借鉴 agentscope 的 `Toolkit` 注册机制，在 harnax 中建立 **ToolRegistry（工具注册表）**，将所有 ToolBox 实现类自动注册并可通过 bean name 查找。

**新增文件：**

```
com.agnetix.harnax.agent.provider.tool/
├── ToolRegistry.kt          # 工具注册表，管理所有 ToolBox 实例
└── ToolSpec.kt              # 工具规格定义（从 AgentSpec 传入）
```

**`ToolRegistry` 设计：**

```kotlin
@Component
class ToolRegistry(
    // Spring 自动注入所有 ToolBox 实现
    private val toolBoxes: List<ToolBox>,
) {
    // bean name → ToolBox 实例的映射
    private val registry: Map<String, ToolBox>

    init {
        registry = toolBoxes.associateBy { it.getBeanName() }
    }

    fun getToolBox(beanName: String): ToolBox? = registry[beanName]
    fun getAllToolBoxes(): List<ToolBox> = toolBoxes
    fun getToolBoxNames(): List<String> = registry.keys.toList()
}
```

每个 `ToolBox` 实现类需要添加 `@Component("beanName")` 注解，由 Spring 自动管理：

```kotlin
@Component("time-tool-box")
class TimeToolBox : ToolBox() {
    // ...
}
```

### 4.3 Agent 规格层

**修改 `AgentSpec.kt`：**

```kotlin
data class AgentSpec(
    // ... 现有字段 ...
    val toolSpecs: List<ToolSpec>,  // 新增：工具配置列表
)

data class ToolSpec(
    val toolId: Long,
    val toolName: String,
    val skipIfMissing: Boolean = true,
    val needConfirm: Boolean = false,  // 是否需要人工确认
)
```

**修改 `AgentSpecResolver.kt`：**

在 `buildAgentSpec()` 中新增 toolList 解析逻辑（与 mcpList 解析保持一致的模式）：

```kotlin
// 解析 toolList（JSON 格式）
val toolListStr = specInfo.toolList
if (toolListStr.isNotEmpty() && toolListStr != "[]") {
    val toolConfigs: List<Map<String, Any>> = objectMapper.readValue(
        toolListStr,
        object : TypeReference<List<Map<String, Any>>>() {},
    )
    for (config in toolConfigs) {
        val toolId = (config["id"] as Number).toLong()
        val enableSkip = config["enable_skip"] as? String
        val needConfirm = config["need_confirm"] as? Boolean ?: false
        builder.addToolSpec(ToolSpec(
            toolId = toolId,
            toolName = "",  // 将在运行时从 DB 获取
            skipIfMissing = enableSkip == "true",
            needConfirm = needConfirm,
        ))
    }
}
```

### 4.4 工具装配层（HarnessAgentLauncher）

**修改 `HarnessAgentLauncher.createAgentBase()` 中的 Tools 部分（第 157-169 行）：**

```kotlin
// 之前（硬编码）:
TOOL_SET.forEach { toolBox ->
    toolBox.init(...)
    agentBuilder.addTool(toolBox)
}

// 之后（动态装配）:
// 1. 遍历 agentSpec.toolSpecs
agentSpec.toolSpecs.forEach { toolSpec ->
    val agentTool = toolConfigAdaptor.resolveTool(toolSpec.toolId)
    if (agentTool != null) {
        // 初始化并注册
        if (agentTool is ToolBox) {
            agentTool.init(
                toolCallLogAdaptor,
                SessionMetaContext(agentSpec.id, sessionId),
                userIdentifier,
            )
        }
        agentBuilder.addTool(agentTool)

        // 如果需要确认，注册到确认工具集合
        if (toolSpec.needConfirm) {
            val toolName = when (agentTool) {
                is ToolBox -> agentTool.name()
                is AgentTool -> agentTool.getName()
                else -> "unknown"
            }
            needConfirmedTools.add(toolName)
        }
    } else if (!toolSpec.skipIfMissing) {
        log.error("Tool config with id `${toolSpec.toolId}` not found.")
        throw HarnaxErrorCode.AGENT_TOOL_NOT_FOUND.format(toolSpec.toolId)
    } else {
        log.warn("Tool config with id `${toolSpec.toolId}` not found.")
    }
}

// 2. 如果 agent 未配置任何工具（兼容旧数据），使用默认工具集
if (agentSpec.toolSpecs.isEmpty()) {
    TOOL_SET.forEach { toolBox ->
        toolBox.init(
            toolCallLogAdaptor,
            SessionMetaContext(agentSpec.id, sessionId),
            userIdentifier,
        )
        agentBuilder.addTool(toolBox)
        if (chatSpec.permission == Permission.NeedConfirmed) {
            needConfirmedTools.addAll(toolBox.needConfirmedTools())
        }
    }
}
```

**新增 `ToolConfigAdaptor` 接口（在 harness-core）和实现（在 agent-service）：**

```kotlin
// harness-core 中定义接口
interface ToolConfigAdaptor {
    fun resolveTool(toolId: Long): Any?  // 返回 ToolBox 或 AgentTool
}

// agent-service 中实现
@Component
class ToolConfigAdaptorImpl(
    private val agentToolMapper: AgentToolMapper,
    private val toolRegistry: ToolRegistry,
) : ToolConfigAdaptor {

    override fun resolveTool(toolId: Long): Any? {
        val agentTool = agentToolMapper.selectById(toolId) ?: return null
        // 从注册表中获取 ToolBox 实例
        return toolRegistry.getToolBox(agentTool.beanName ?: return null)
    }
}
```

### 4.5 Admin 管理层（harnax-admin）

**新增文件：**

```
controller/AgentToolController.kt       # 工具只读查询 API
service/AgentToolService.kt             # 工具查询逻辑
dto/
└── AgentToolResponse.kt                # 响应 DTO
mapper/AgentToolMapper.kt               # Admin 专用 Mapper（如有额外查询）
```

工具元数据（名称、描述、`beanName`、`@ToolMeta` 属性）全部由 admin 启动期的 `BuiltinToolAutoRegistrar` 从代码同步写库，
因此**不存在**新增 / 更新 / 删除 / 启停接口，也**不存在** HTTP 工具的测试接口与对应的 `AgentToolCreateRequest`、
`AgentToolUpdateRequest`、`AgentToolTestRequest` DTO。

**API 端点设计：**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/tools/page` | 分页查询工具列表（`pageNum,pageSize,keyword,status`） |
| GET | `/api/admin/tools/{id}` | 获取工具详情 |
| GET | `/api/admin/tools/available` | 获取可用工具列表（供 Agent 配置页调用） |
| GET | `/api/admin/tools/builtin` | 获取系统内置工具列表（从 ToolRegistry 读取） |
| GET | `/api/admin/tools/{id}/required-env-params` | 获取工具必填环境参数 key |

**Agent 配置接口修改：**

修改 `AgentCreateRequest` / `AgentUpdateRequest`，新增 `toolList` 字段：

```kotlin
data class AgentToolConfig(
    val id: Long,
    val enableSkip: String = "true",
    val needConfirm: Boolean = false,  // 用户选择（受实体 needConfirm 约束）
)

data class AgentCreateRequest(
    // ... 现有字段 ...
    val toolList: List<AgentToolConfig>? = null,  // 新增
)
```

修改 `InternalApiController.getAgentSpec()`，在返回的 `AgentSpecInfoResponse` 中包含 `toolList`。

---

## 五、Agent 配置中动态选择工具

### 5.1 配置流程

```
Admin UI
  ├── 工具管理页面（只读查询）
  │     ├── 分页查看工具列表（名称、状态、只读标记）
  │     └── 记录由 admin 启动时从代码同步，页面不支持新增 / 修改 / 删除 / 启停
  │
  └── Agent 配置页面
        └── 工具选择区域
              ├── 从可用工具列表中选择（勾选）
              ├── 每个工具可设置：
              │     ├── enable_skip（缺失时是否跳过）
              │     └── needConfirm（是否需要确认，受工具实体约束）
              └── 保存为 Agent.tool_list JSON
```

### 5.2 Agent 工具选择 API

**获取可用工具列表（供 Agent 配置页面调用）：**

```
GET /api/admin/tools/available
```

返回所有启用的内置工具，包含：
- 工具基本信息（id, name, displayName, description）
- 工具的 `needConfirm` 标记（用于前端展示开关）

**保存 Agent 的工具配置：**

```json
// PUT /api/admin/agents/update/{agentId}
{
  "toolList": [
    {"id": 1, "enable_skip": "true", "needConfirm": true},
    {"id": 3, "enable_skip": "false", "needConfirm": false}
  ]
}
```

### 5.3 后端校验逻辑（AgentService 中）

```kotlin
// 保存 Agent 工具配置时校验
fun validateToolList(toolList: List<AgentToolConfig>): List<AgentToolConfig> {
    return toolList.map { config ->
        val toolEntity = agentToolMapper.selectById(config.id)
            ?: throw HarnaxException("Tool not found: ${config.id}")

        val finalNeedConfirm = when (toolEntity.needConfirm) {
            0 -> false   // 实体不需要确认 → 强制 false，忽略用户传入值
            1 -> config.needConfirm  // 实体需要确认 → 使用用户选择的值
            else -> false
        }

        config.copy(needConfirm = finalNeedConfirm)
    }
}
```

### 5.4 运行时工具装配流程

```
agent-service 收到请求
  │
  ├── 1. AgentSpecResolver.resolve(sessionId)
  │       └── 调用 Admin Internal API → 获取 AgentSpecInfoResponse（含 toolList）
  │
  ├── 2. buildAgentSpec()
  │       └── 解析 toolList JSON → List<ToolSpec>
  │
  ├── 3. HarnessAgentLauncher.createAgentBase()
  │       └── 遍历 toolSpecs:
  │             ├── ToolRegistry.getToolBox(beanName) → 获取 ToolBox 实例
  │             └── 如果 toolSpec.needConfirm == true → 加入 needConfirmedTools 集合
  │
  └── 4. HarnessAgentBuilder.addTool(tool)
          └── toolkit.registerTool(tool) → agentscope 注册
```

---

## 六、动态扩展工具的方式

### 6.1 代码级扩展（唯一方式）

开发者只需：

1. 创建 `ToolBox` 子类，使用 `@Tool` 注解标注方法，用 `@ToolMeta` 声明元数据
2. 添加 `@Component("bean-name")` 注解
3. 无需在 Admin 手工建记录：admin 启动时 `BuiltinToolAutoRegistrar` 会扫描注册表并写入 / 更新 `agent_tool`
4. Agent 配置中即可选择该工具

```kotlin
@Component("weather-tool-box")
class WeatherToolBox : ToolBox() {
    @Tool(description = "查询城市天气信息")
    fun getWeather(
        @ToolParam(name = "city", description = "城市名称")
        city: String,
    ): String {
        return execute("city" to city) {
            // 调用天气 API
        }
    }

    override fun name() = "weather-tool-box"
}
```

### 6.2 自动发现机制

实现形式：admin 启动时（`ApplicationReadyEvent`）扫描本进程 `ToolRegistry` 中带 `@Tool` 注解的 `ToolBox` Bean，
与 `agent_tool` 表对账后新增 / 更新记录，并把注解上的元数据写进对应字段。
早期设想的是通过 Internal API 从 agent-service 拉取已注册的 `ToolBox` Bean 名称和元信息，再由 Admin 同步为工具记录。

---

## 七、改动文件清单

| 模块 | 操作 | 文件 | 说明 |
|------|------|------|------|
| **harnax-entity** | 新增 | `entity/AgentTool.kt` | 工具实体类 |
| | 新增 | `mapper/AgentToolMapper.kt` | MyBatis Mapper |
| | 新增 | `mapper/xml/AgentToolMapper.xml` | SQL 映射 |
| | 修改 | `entity/Agent.kt` | 新增 `toolList` 字段 |
| | 修改 | `entity/dto/AgentSpecInfoResponse.kt` | 新增 `toolList` 字段 |
| **harnax-harness-core** | 新增 | `provider/tool/ToolRegistry.kt` | 工具注册表 |
| | 新增 | `provider/tool/ToolSpec.kt` | 工具规格 |
| | 新增 | `adaptor/ToolConfigAdaptor.kt` | 工具配置适配器接口 |
| | 修改 | `AgentSpec.kt` | 新增 `toolSpecs` 字段 |
| | 修改 | `harness/HarnessAgentLauncher.kt` | 动态工具装配逻辑 |
| **harnax-agent-service** | 新增 | `adaptor/ToolConfigAdaptorImpl.kt` | 工具配置适配器实现 |
| | 修改 | `runner/AgentSpecResolver.kt` | 解析 toolList |
| | 修改 | `ProviderConsts.kt` | TimeToolBox 加 `@Component` |
| **harnax-admin** | 新增 | `controller/AgentToolController.kt` | 工具只读查询 API |
| | 新增 | `service/AgentToolService.kt` | 工具查询逻辑 |
| | 新增 | `dto/AgentToolResponse.kt` | 响应 DTO |
| | 新增 | 启动期代码同步 | `BuiltinToolAutoRegistrar` 扫描注解写库 |
| | 修改 | `controller/AgentController.kt` | Agent 创建/更新支持 toolList |
| | 修改 | `controller/InternalApiController.kt` | 返回 toolList |
| | 新增 | DB migration | `agent_tool` 建表 + `agent` 表加 `tool_list` |

---

## 八、与现有架构的一致性

| 维度 | MCP Server（已有） | Skill（已有） | Tool（新增） |
|------|-------------------|--------------|-------------|
| 实体 | `McpServer` | `Skill` | `AgentTool` |
| Agent 关联 | `agent.mcpList` JSON | `agent.skillList` 逗号分隔 | `agent.toolList` JSON |
| Admin CRUD | `McpServerController` | `SkillController` | `AgentToolController`（只读查询） |
| 运行时解析 | `McpConfigAdaptor` | `SkillAdaptor` | `ToolConfigAdaptor` |
| 连通性测试 | 有 | 无 | 无 |
| Spec 传递 | `McpSpec` | `SkillSpec` | `ToolSpec` |

新增的 Tool 体系完全对齐现有的 MCP 和 Skill 管理模式，保持架构一致性。

---

## 九、实施步骤

1. **Phase 1：基础实体与数据库**
   - 创建 `agent_tool` 表
   - 修改 `agent` 表添加 `tool_list` 字段
   - 实现 `AgentTool` 实体和 Mapper

2. **Phase 2：Admin 管理功能**
   - 实现工具只读查询 API
   - 实现启动期代码同步（`BuiltinToolAutoRegistrar`）
   - 修改 Agent 创建/更新接口支持 toolList

3. **Phase 3：运行时集成**
   - 实现 `ToolRegistry` 工具注册表
   - 实现 `ToolConfigAdaptor` 工具配置适配器
   - 修改 `HarnessAgentLauncher` 动态装配逻辑

4. **Phase 4：测试与优化**
   - 单元测试
   - 集成测试
   - 性能优化

---

## 十、总结

本方案通过引入 `AgentTool` 实体和 `toolList` 配置，实现了：

1. **代码即来源**：工具只有内置一类，扩展方式是写 `ToolBox` 子类，admin 启动时自动同步入库
2. **动态选择**：Agent 配置时可从可用工具列表中选择，并设置确认策略
3. **架构一致**：与现有 MCP Server、Skill 管理模式完全对齐
4. **向后兼容**：保留默认工具集，未配置 toolList 的 Agent 行为不变

该方案为 Harnax 提供了完整的工具管理能力，使 Agent 的工具配置更加灵活和可控。

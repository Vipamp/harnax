# Harnax 工具能力说明（中文）

> 英文版本见 [tool-capability.en-US.md](./tool-capability.en-US.md)
>
> 本文基于当前代码库调研整理，覆盖工具 SDK（`harnax-tools-sdk`）、内置工具（`harnax-tools-buildin`）与 Agent 运行时工具装配的完整链路。
>
> 分类模型、数据模型分层、关键设计决策与取舍、演进时间线见 [tool-integration-design.zh-CN.md](./tool-integration-design.zh-CN.md)。

## 1. 概述

Harnax 的工具体系为 Agent 提供可调用的外部能力，整体设计遵循「SDK 定义规范、内置模块提供实现、Admin 管理元数据、Agent 运行时动态装配」的分层架构：

- **harnax-tools-sdk**：工具 SDK 层，定义工具抽象（`ToolBox`）、注解（`@ToolMeta`、`ToolEnvParamDef`）、注册中心（`ToolRegistry`）与适配器接口，不依赖具体业务。
- **harnax-tools-buildin**：内置工具模块（位于 `harnax-tools-external` 下），基于 SDK 实现开箱即用的工具（时间、邮件等）。
- **harnax-harness-core**：Agent 运行时，负责在构建 Agent 时按配置动态装配工具、注入环境参数、配置权限规则。
- **harnax-admin**：工具元数据管理端，负责启动时同步内置工具到数据库、提供工具管理 API。

底层框架为 agentscope 2.0.2（`io.agentscope`），工具最终以 `AgentTool` 形式注册进 HarnessAgent 的 Toolkit。

### 模块依赖关系

```
harnax-tools-sdk  ←── harnax-tools-buildin（内置工具实现）
        ↑                      ↑
        │                      │
harnax-harness-core     harnax-admin（启动同步 + 管理 API）
        ↑
harnax-agent-service（运行时装配、适配器实现）
```

## 2. 工具分类

### 2.1 只有一类：内置工具

工具全部是内置工具：随代码发布，由 admin 启动时按 `@Tool` / `@ToolMeta` 注解同步入库，面向全平台所有用户。不存在用户自建的工具，也没有 `type` 与「是否公开（`is_public`）」这两层概念——`agent_tool` 表里的每一行都由 `BuiltinToolAutoRegistrar` 写入，任何接口和页面都不能新增、修改、启停或删除工具。

> 所有工具在运行时走同一条装配路径（`HarnessAgentLauncher.createAgentBase()`）：按 `beanName` 反射创建 ToolBox，再按方法粒度授权。

### 2.2 内置工具再分：必须工具与非必须工具

| 子类 | `agent_tool.is_required` | 谁来选中 | 运行时来源 |
|------|--------------------------|----------|------------|
| **必须工具** | `1` | 无人可选，也不需要选 | Admin 下发 AgentSpec 时自动追加（见 6.1），**不落 `agent_tool_binding`** |
| **非必须内置工具** | `0` | 用户在智能体配置向导中勾选 | `agent_tool_binding` 绑定记录 |

对应的前端口径：

- **工具管理页面**（`/api/admin/tools/builtin`）：工具全量展示，必须与非必须都可见，用「必须 / 可选」标签区分。
- **智能体配置向导**（`/api/admin/tools/available`）：只列出可勾选的工具，即 `is_required = 0` 的启用工具，必须工具不出现在候选列表中。
- 必须工具同样不受理绑定：`agent_tool.is_required` 由 `@ToolMeta(isRequired)` 同步而来，UI 不提供开关。

> MCP 服务的实体管理、加密存储与运行时装配全链路，见 [mcp-management.zh-CN.md](./mcp-management.zh-CN.md)。
>
> 技能（Skill）是与工具并列的另一类能力来源（SKILL.md + 附属资源，不进入 `agent_tool` 表），其仓库管理、同步落库与运行时装配见 [skill-management.zh-CN.md](./skill-management.zh-CN.md)。

## 3. SDK 核心概念（harnax-tools-sdk）

### 3.1 ToolBox 抽象基类

路径：`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`

- 所有工具都必须继承 `ToolBox` 并实现 `name()`（工具组逻辑名）。
- 运行时通过 `init(toolCallLogAdaptor, sessionMetaContext, userIdentifier)` 注入会话上下文，**每个会话创建独立实例**，避免单例共享导致的上下文串扰。
- 提供 `execute { ... }` / `execute(vararg args) { ... }` 模板方法：工具方法体包裹其中后，自动完成调用计时、成功/失败日志上报（通过 `ToolCallLogAdaptor`），异常原样抛出。
- 通过 `userIdentifier()` 可获取当前调用用户标识。

### 3.2 注解体系

工具方法上需要组合使用三个注解：

| 注解 | 来源 | 职责 |
|------|------|------|
| `@Tool` | agentscope | 定义工具名（`name`）、描述（`description`，发送给 LLM）、`readOnly` |
| `@ToolParam` | agentscope | 声明 LLM 可传参数（`name` + `description`）；**未加该注解的参数不会进入 JSON Schema**，视为框架注入参数 |
| `@ToolMeta` | harnax SDK | 方法级元数据，启动时同步到数据库（详见 3.3） |

> ⚠️ 常见陷阱：
> - `@ToolMeta` 只能标注在方法上，每个 `@Tool` 方法对应一个独立工具实例。
> - 所有需要 LLM 传入的参数必须加 `@ToolParam(name, description)`；框架注入参数（如 `ToolEnvContext`）**不能**加 `@ToolParam`。
> - 参数名建议 snake_case；可选参数设置 `required = false`。

### 3.3 @ToolMeta 属性说明

路径：`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `displayName` | `""` | Admin UI 英文展示名；留空时同步入库时回退为工具名（`@Tool.name`），也作为 i18n 缺省文案 |
| `displayNameZh` | `""` | Admin UI 中文展示名（i18n zh-CN 语言环境使用），留空时前端回退英文名 |
| `envParamDefs` | `[]` | 环境参数定义数组（`ToolEnvParamDef`），启动时同步到 `agent_tool_env_param` 表，作为 Admin UI 绑定工具时环境参数表单的渲染依据；每项含 `key`（参数名）、`description`（UI 说明）、`required`（是否必填）、`secret`（是否密钥，UI 脱敏）、`defaultValue`（默认值，仅限非密钥——同步路径把注解值原样入库、不过加密器，给 `secret = true` 的参数配默认值等于往表里写明文密钥），详见 3.4 节 |
| `needConfirm` | `false` | **执行前是否需要用户确认**。为 `true` 时运行时生成 ASK 权限规则，每次调用都会暂停并等待用户确认；不检查入参内容，与调用参数无关；在 `BYPASS` 权限模式下会被跳过。该字段由代码注解决定，页面上改不了；不改代码想给某个智能体加严，只能针对绑定项设置 `agent_tool_binding.needConfirm`——运行时取两者之或，绑定层只能追加确认、不能取消工具自带的确认（`agent_tool.needConfirm` 没有任何写入口），详见 6.4 节 |
| `dangerousInput` | `false` | **是否对字符串入参做危险模式扫描**（危险命令如 `rm -rf`、敏感路径如 `.env`/`.ssh`）。仅当入参命中危险模式时才触发确认，命中后的确认不可被 `BYPASS` 跳过（bypass-immune）；正常入参直接放行；与 `needConfirm` 同时标注时输入扫描会被自动跳过（确认规则先生效），详见 6.3 / 6.4 节 |
| `isRequired` | `false` | **是否为必须工具**：`true` 时随所有 Agent 生效——Admin 下发 AgentSpec 时自动追加该工具，无需绑定记录、也无需用户勾选；不出现在智能体配置向导的候选列表中，但仍展示在工具管理页面（标「必须」）；取值只由代码注解决定，UI 不提供开关，详见 2.2 / 6.1 节 |

`needConfirm` 与 `dangerousInput` 选型速查：

| 诉求 | 用哪个 |
|------|--------|
| 工具有副作用，**每次调用**都要人确认 | `needConfirm = true` |
| 工具中立，仅拦截入参中的危险命令 / 路径 | `dangerousInput = true` |
| 两者都需要 | 只标 `needConfirm = true`（已覆盖全部调用，叠加时扫描会被自动跳过） |

### 3.4 环境参数体系

- **`ToolEnvParamDef`**：注解内嵌定义，声明单个环境参数的 `key`、`description`、`required`、`secret`（密钥类参数在 UI 中脱敏）、`defaultValue`。
- **`ToolEnvContext`**：运行时环境绑定容器（`bindings: Map<String, String>`），在 Agent 构建时注册进 agentscope 的 `ToolExecutionContext`，工具方法只要声明该类型参数即自动注入，通过 `get(key)` / `require(key)` 取值。

### 3.5 ToolRegistry 注册中心

路径：`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt`

Spring `@Component`，`@PostConstruct` 时：

1. 扫描容器中所有 `ToolBox` 类型 Bean，按 beanName 注册；
2. 反射读取每个 Bean 的 `@Tool` + `@ToolMeta` 注解，提取 `ToolMetaDescriptor`（含每个方法的 `ToolMethodDescriptor`）；
3. 无 `@Tool` 方法的 ToolBox 会被跳过。

关键方法：

- `createToolBoxInstance(beanName)`：通过无参构造创建**会话级新实例**（失败时回退到单例模板）；
- `getAllToolMeta()`：供 Admin 启动同步使用。

### 3.6 适配器接口（SPI）

| 接口 | 职责 | 实现方 |
|------|------|--------|
| `ToolCallLogAdaptor` | 上报工具调用日志（`ToolCallInfo`：agentId、sessionId、toolName、args、result、success、耗时） | `ToolCallLogAdaptorImpl`（agent-service，落库 `tool_call_log`） |
| `ToolConfigAdaptor` | 按 `toolId` 查询工具配置（`AgentTool` 实体） | `ToolConfigAdaptorImpl`（agent-service，优先读 admin 预解析上下文，回退查库） |

### 3.7 其他数据类

- `ToolSpec`：Agent 配置中的工具引用（`toolId`、`toolName`、`needConfirm` 覆盖）。
- `SessionMetaContext` / `UserIdentifier`：会话与用户上下文，实现 `ToolCallContext` 标记接口。

## 4. 内置工具现状（harnax-tools-buildin）

路径：`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`

### 4.1 TimeToolBox（bean `time-tool-box`）

| `@Tool` 方法名 | `methodName` | 说明 | readOnly | needConfirm | isRequired | 环境参数 |
|----------------|--------------|------|----------|-------------|------------|----------|
| `getDate` | `getDate` | 获取当前日期（`yyyy-MM-dd`） | 是 | 否 | 否 | 无 |
| `getDatetime` | `getDatetime` | 获取当前时间（`yyyy-MM-dd HH:mm:ss`） | 是 | 否 | 否 | 无 |

### 4.2 EmailToolBox（bean `email-tool-box`）

| `@Tool` 方法名 | `methodName` | 说明 | readOnly | needConfirm | isRequired | 环境参数 |
|----------------|--------------|------|----------|-------------|------------|----------|
| `sendEmail` | `sendEmail` | 通过 SMTP 发送邮件，支持纯文本 / HTML 正文 | 否 | **是** | 否 | 5 项（见下） |

- 直接使用 Jakarta Mail，不依赖 Spring。
- LLM 参数：`to`、`subject`、`body`、`is_html`（可选）。
- 环境参数：`SMTP_HOST`（必填）、`SMTP_PORT`（可选，默认 587）、`SMTP_USER`（必填）、`SMTP_PASSWORD`（必填，密钥）、`SMTP_FROM`（必填）。
- 端口策略：465 走隐式 SSL，25 不加密，其余（含 587）强制 STARTTLS。

> 当前代码库里**没有 `isRequired = true` 的内置工具**——「必须工具」这条分支（下发时自动追加、不落绑定表、启动矛盾告警）已实现并有用例覆盖，但线上暂时没有工具使用它。

## 5. 内置工具注册机制（唯一的生命周期入口）

实现：

- 扫描：`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt`（`@PostConstruct`）
- 入库：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt`（`ApplicationReadyEvent`）

**约定**：工具的注册、更新与删除**只有这一条路径**。代码里的 `@Tool` / `@ToolMeta` 是唯一的事实来源，`agent_tool` 表的每一行都由本机制写入，页面和接口都不存在任何写入口。

### 5.1 扫描

`ToolRegistry` 启动时取 `getBeansOfType(ToolBox::class.java)`，逐 bean、逐方法读取 `@Tool` + `@ToolMeta`，产出 `ToolMetaDescriptor`（`beanName` 下挂每个方法的 `toolName`、`methodName`、`displayName` / `displayNameZh`、`description`、`readOnly`、`needConfirm`、`isRequired`、`envParamDescriptors`）。没有 `@Tool` 方法的 bean 不产出元数据，也就不会入库。

工具级超时不是注解决定的：`@ToolMeta` 没有 `timeoutSeconds`（V40 随 `agent_tool.timeout_seconds` 一起移除），整轮预算在装配侧由 `HarnessConfig.turnTimeoutSeconds` 设定，见 6.5。

### 5.2 同步（每次 admin 启动做一次全量收敛）

1. **新增**：声明里有、库里没有这个工具名 → 插入一条 `agent_tool`（`status=1`、`active=1`、`creator='SYSTEM'`）。
2. **更新**：库里已有同名行时**逐列对比**，只在存在差异时才发 UPDATE，并把差异写进启动日志（哪个工具、哪些列、从什么改到什么）。`name` 不参与更新：它是查找依据。`status` 与 `active` 同样收敛为 1，工具不存在「人工停用」这种状态。
3. **不删除**：库里存在、本次启动没有声明的行**原样保留**，行和它的 `agent_tool_binding` 都不动。这类行的名字不在本次声明的名字集合里，因此**不会被下发**（见 6.1）——它只对运维可见。
4. **环境参数定义同步**：`@ToolMeta.envParamDefs` → `agent_tool_env_param`，采用「就地更新 + 新增插入 + 过期删除」策略，保留记录 ID。删的是参数定义而不是工具，属于「更新工具定义」。
5. **同名冲突直接报错**：两个 `@Tool` 方法声明同一个 `@Tool.name` 时，整个同步在任何写入之前抛 `IllegalStateException`，异常信息逐个列出冲突的 `bean::method`。名字就是身份，任选一个生效等于让 bean 顺序决定工具实际执行哪个方法。
6. **配置矛盾告警**：某个方法同时标了 `isRequired = true` 与必填 `envParamDefs` 时启动日志告警——必须工具没有绑定行、取不到环境参数，这种组合运行期必然失败（详见 6.1）。

> 改名的代价分两种：
> - 只改 `@Tool(name = ...)`：**等于换了一个工具**。身份就是 `name`，所以新名会插入一条新行，旧名的行保留（但不再被下发），旧行上的 `agent_tool_binding` 不会迁移——已经在用这个工具的智能体会静默失去它，需要在配置里对新工具重新勾选。
> - 改 Java 方法名（`methodName`）或改 bean 名：**还是同一个工具**。两者只是反射实例化用的参数，不参与身份判定，行原地刷新（差异会出现在启动日志里），`id` 与绑定全部保留。

> 工具是**平台级资产**：`agent_tool` 表上没有 `tenant_id`（V40 删除），全平台共享一批记录，不按租户各存一份。

> 因此「必须 / 非必须」的划分、工具是否存在、字段取值都只能改代码重新发布，运营侧不可调整。

### 5.3 外部写入口：接口本身不存在

| 入口 | 现状 |
|------|------|
| `AgentToolController` | 只有 GET：`/page`、`/{id}`、`/available`、`/builtin`、`/{id}/required-env-params`。`PUT /update/{id}`、`PUT /toggle/{id}`、`DELETE /{id}` 已整体删除，配套的 `AgentToolCreateRequest` / `AgentToolUpdateRequest` 一并删除 |
| `AgentToolService` | 只声明查询与 `convertToResponse`，没有 create / update / toggle / delete 方法 |
| 前端（webui 工具管理页、小程序工具列表） | 只读展示，无新增 / 编辑 / 删除 / 停用；`services/ant-design-pro/tool.ts` 只保留 `getAvailableTools` / `getBuiltinTools` |
| `harnax-cli` | 只有 `tool list / get / available / builtin / env-params` 五个查询命令，没有 `update / delete / toggle` |

### 5.4 幂等性与排障锚点

- **触发时机**：`BuiltinToolAutoRegistrar` 挂在 `ApplicationReadyEvent`，不是 `@PostConstruct`——`ToolRegistry` 的扫描是 `@PostConstruct`，但入库要等 Flyway 迁移与数据源就绪，用启动完成事件才能保证 `agent_tool` 表已经存在。
- **幂等**：每次启动全量重放一遍。身份是 `name`（唯一键 `uk_agent_tool_name`），库中已有的行只在存在差异时更新，重复启动不产生新行、除 `update_time` 外没有任何字段值会变化；环境参数定义同样就地收敛，保留记录 ID。
- **故障隔离**：每个 bean 一个 `try/catch`，某个工具组写入失败只影响该组（其余照常写入）；失败组的工具名字仍计入「已声明」，避免一次写失败变成所有智能体都缺这个工具。
- **日志锚点**（`grep` admin 启动日志即可定位）：

| 日志片段 | 含义 |
|----------|------|
| `Syncing N builtin tool group(s), M declared tool(s)` | 扫到 N 个 ToolBox、M 个声明，开始同步 |
| `Required tool '...' declares required env params ...` | WARN：`isRequired` 与必填环境参数矛盾（见 6.1） |
| `Registered new tool '<name>' (<bean>::<method>)` | 新声明的工具已入库 |
| `Updated tool '<name>' (id=N): <columns>` | 已有行存在差异并被刷新，冒号后是变化的列名 |
| `Synced tool group: xxx [N methods]` | 该组写入成功 |
| `Synced env params for tool 'bean::name': N total, M stale removed` | 环境参数定义收敛完成；`M > 0` 说明代码里删掉了参数定义（`agent_tool_env_param` 已同步清理） |
| `Failed to sync tool group: xxx` | 该组异常，其余组不受影响 |
| `Sync complete: X succeeded, Y failed; N tool name(s) declared` | 总览；`N` 是本次声明的名字总数 |
| `Duplicate @Tool name(s) on the classpath: ...` | 异常：同名冲突，启动失败，括号里是全部冲突的 `bean::method` |
| `No @Tool annotated methods found, skipping sync` | 一个方法都没扫到，整个同步跳过 |

- **单元用例**：收敛规则的行为覆盖在 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrarTest.kt`（9 例：新增、无差异不写、有差异刷新、停用收敛回启用、未声明的行不动、同名冲突拒绝、空注册表跳过、单组失败隔离、环境参数收敛），编号见 `docs/unit-test-cases.md` §5.2；mapper 层的写入与唯一键行为在 `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/AgentToolMapperTest.kt`。工具服务只剩查询方法，原先那组「写入口拒绝 BUILTIN」的守卫用例已随写接口一起删除，`docs/unit-test-cases.md` §5.1 现在登记的是查询与响应装配用例。

## 6. Agent 运行时工具装配

核心实现：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` 的 `createAgentBase()`。

### 6.1 装配流程

```
InternalApiController.buildAgentSpecResponse（Admin 下发阶段）
        ├─ toolDetails = agent_tool_binding 中的绑定工具
        │                + is_required=1 且启用的内置工具（按 id 去重追加，不写绑定表）
        │                − 名字不在本次启动声明集合里的行（见下方要点）
        └─ toolList（旧版 JSON）仅含绑定工具的环境参数快照
        ▼
AgentSpecResolver（admin 响应 → AgentSpec）
        │  toolSpecs + contextForTools(ToolEnvContext)
        ▼
HarnessAgentLauncher.createAgentBase()
        │
        ├─ 遍历 agentSpec.toolSpecs
        │    ├─ ToolConfigAdaptor.getToolConfig(toolId) 取配置
        │    ├─ status=0（禁用）→ 跳过，不记入授权清单
        │    ├─ ToolRegistry.createToolBoxInstance(beanName)
        │    │      → init(日志适配器, SessionMetaContext, UserIdentifier)
        │    │      → agentBuilder.addTool(toolBox)（注册该 ToolBox 的全部 @Tool 方法）
        │    ├─ 配置或 ToolBox 取不到 → 告警跳过（无开关，不阻断会话构建）
        │    └─ needConfirm（实体值或绑定值，取或）→ 收集进 needConfirmedTools
        │
        ├─ 收尾剔除：按 ToolMetaDescriptor 取出各 ToolBox 的全部 @Tool 方法，
        │    不在本次授权清单里的（未勾选的同箱方法、被禁用的方法）逐个 removeTool
        │
        ├─ toolSpecs 为空 → 不装配任何工具（已无「注册全部 ToolBox」的兜底）
        │
        ├─ contextForTools → ToolExecutionContext（注入 ToolEnvContext）
        │
        ├─ 权限规则（PermissionContextState）
        │    ├─ 框架工具白名单 ALLOW：plan_enter / plan_write / plan_exit /
        │    │   todo_write / agent_spawn / agent_send / agent_list / task_output / task_list
        │    └─ needConfirmedTools → ASK 规则
        │
        └─ 危险输入包装：@ToolMeta(dangerousInput=true) 的方法
             → agentBuilder.wrapWithDangerousInputCheck(toolName)
             （已有 needConfirm ASK 规则的工具跳过，避免重复）
```

要点：

- **未声明的行不下发**：同步不删除任何 `agent_tool` 行（见 5.2），所以库里可能留着「代码里已经没有」的工具——它的方法不存在，运行时装配必然失败。`buildAgentSpecResponse` 按 `BuiltinToolAutoRegistrar.registeredToolNames()`（本次启动声明的名字集合）过滤，并把被挡下的 id 记进 WARN 日志；该集合为空表示同步没跑，此时不做过滤而不是把工具全部挡下。
- **必须工具在下发阶段注入**：`is_required = 1` 的内置工具由 `InternalApiController` 追加进 `toolDetails`（与绑定工具按 id 去重），Agent 配置里勾不到、也关不掉；要去掉它只能改代码——取消 `isRequired` 或删除该 `@Tool` 方法，重新发布后旧行会保留但不再下发（见 5.2）。
- **`status` 随下发透传**：`ToolDetailDto.status` → `ToolConfigAdaptorImpl` 还原实体 → 运行时 `status == 0` 跳过。这条链路缺任一环会让「停用工具」静默失效。工具的 `status` 由启动同步强制收敛为 1，也不存在可把它改成 0 的写入口，所以这条跳过判断目前只剩防御作用。
- **必须工具没有绑定行**，因此没有 `agent_tool_binding.envBindings` 快照可取：它拿不到按 Agent 配置的环境参数。需要环境参数的工具不要标 `isRequired`，否则运行期 `require()` 必然报「参数未配置」；Admin 启动同步时会对这种组合打告警。
- **按 beanName 去重、按方法粒度授权**：一个 ToolBox 内的多个 `@Tool` 方法对应多条 `agent_tool` 记录，`addTool` 只执行一次；由于 `addTool` 会把该 ToolBox 的全部方法都注册进来，装配结束后要用 `ToolRegistry.getToolMeta(beanName)` 的方法全集减去本次授权的方法集，把差额 `removeTool` 掉——否则勾选同箱的一个工具就等于放出整箱工具。
- **配置来源**：`ToolConfigAdaptorImpl` 优先读取 admin 预解析好的 `toolDetails`（随 AgentSpec 下发），未命中时回退直查数据库。
- **环境参数注入**：`AgentSpecResolver` 将 tool / MCP 绑定的环境变量合并为扁平 Map，封装成 `ToolEnvContext` 挂到 `agentSpec.contextForTools`，构建时注册进 `ToolExecutionContext`，工具方法自动获得注入。即使一个绑定都没有，`ToolEnvContext` 也会以空容器注册，保证工具方法的 `envContext` 参数始终可注入、报错可控。

### 6.2 权限模式

会话支持 5 种工具执行权限模式（`PERMISSION <mode>` 命令切换）：

| 模式 | 行为 |
|------|------|
| `DEFAULT` | 默认，命中规则的工具需确认 |
| `BYPASS` | 自动执行（但 `safety` 类 ASK 不可跳过） |
| `ACCEPT_EDITS` | 自动批准文件编辑 |
| `EXPLORE` | 只读探索 |
| `DONT_ASK` | 自动拒绝危险工具 |

### 6.3 危险输入拦截（bypass-immune）

实现：`harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt`

对标注 `@ToolMeta(dangerousInput = true)` 的工具，用 `DangerousInputCheckingTool` 包装，重写 `checkPermissions()`：

1. **危险命令扫描**：对长度 ≥ 3 的字符串入参做子串匹配（`rm -rf`、`sudo rm`、`chmod 777`、`kill -9` 等）；
2. **危险路径扫描**：复用 `ToolBase.isDangerousPath`，检查 `.env`、`.bashrc`、`.ssh/config` 等敏感文件与 `.git`、`.ssh` 等敏感目录，并解析符号链接防止绕过。

命中后返回带 `safety` 原因的 ASK 决策——按 PermissionEngine 契约，此类决策即使在 `BYPASS` 模式下也不得跳过。

### 6.4 如何标注危险工具（开发者视角）

危险工具有两个层级的标注方式，可叠加使用：

**方式 1：`needConfirm = true` —— 执行前强制用户确认**

```kotlin
@Tool(name = "sendEmail", description = "Send an email via SMTP")
@ToolMeta(needConfirm = true)   // 每次执行前需用户确认
fun sendEmail(...)
```

- 不看入参内容，只要调用就会生成 ASK 权限规则，向用户发起确认；
- 在 `BYPASS` 模式下可被跳过（普通 ASK）；
- 不改代码也能调整的方向只有一个：`agent_tool.needConfirm` 没有任何写入口（见 5.3），要加严只能给某个智能体的绑定设置 `agent_tool_binding.needConfirm` 追加确认（运行时取两者之或，取消不掉工具自带的确认）。

**方式 2：`dangerousInput = true` —— 危险入参扫描（bypass-immune）**

适用于入参可能包含危险命令 / 敏感路径的工具（如执行 shell、写文件）：

```kotlin
@Tool(name = "execute_command", description = "Run a shell command")
@ToolMeta(dangerousInput = true)   // 入参危险模式扫描，BYPASS 模式也不可跳过
fun executeCommand(
    @ToolParam(name = "command", description = "The shell command to run")
    command: String?,
): String = execute("command" to command) { ... }
```

**选型对照**：

| 场景 | 推荐标注 |
|------|---------|
| 工具有副作用（发邮件、下单、写外部系统），一律要人确认 | `needConfirm = true` |
| 工具本身中立，但入参可能夹带危险命令 / 路径 | `dangerousInput = true` |
| 副作用 + 危险入参 | 只标 `needConfirm = true` 即可（已覆盖所有调用） |

> 叠加注意：两者同时标注时，运行时会跳过 `dangerousInput` 包装——`needConfirm` 的 ASK 规则在权限引擎中先生效，输入扫描变多余。

### 6.5 整轮超时（装配侧，不是工具属性）

超时不属于工具的属性：`@ToolMeta` 没有 `timeoutSeconds`，`agent_tool` 也没有同名列（V40 一并移除——旧值进了实体却没有任何读侧，从来只是装饰）。

真正生效的是**整轮预算**，由 `HarnessConfig.turnTimeoutSeconds` 决定：

| 项 | 位置 | 说明 |
|---|---|---|
| 配置 | `harness.turn-timeout-seconds` / `HARNAX_TURN_TIMEOUT_SECONDS` | 默认 300 秒，由 `HarnessProperties` 绑定进 `HarnessConfig` |
| 批量路径 | `HarnessAgentWrapper.call` | 作用在整次 `harnessAgent.call` 上 |
| 流式路径 | `HarnessAgentWrapper.callStreamInternal` | 同一个值；刻意放在输出文件探测之前，让探测这一步不占用预算。超时与其它流错误一样落到 `onErrorResume`，转成 `ErrorChatEvent` |

> 单次工具调用没有独立超时：一批工具连着跑，共享这一份整轮预算。团队场景里主管在委派之间的等待可能很长，需要时调大 `turn-timeout-seconds`（团队自己的 `memberTurnTimeoutSeconds` / `confirmTimeoutSeconds` 是另一组预算，见 `multi-agent-team-design`）。

## 7. 数据模型

### 7.1 agent_tool（工具主表）

对应实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`

| 字段 | 说明 |
|------|------|
| `name` / `displayName` / `displayNameZh` | 工具标识名与中英文展示名 |
| `description` | 工具描述（发送给 LLM） |
| `beanName` / `methodName` | 反射实例化 ToolBox Bean 与方法用的参数；**不参与身份判定** |
| `requiredEnvParamKeys` | 必填环境参数 key 列表（JSON）；参数定义本身在 `agent_tool_env_param`（见 7.3）。该列只服务管理端展示与保存时校验，运行时不读 |
| `readOnly` / `needConfirm` / `isRequired` | 只读、需确认、必须工具标记（0/1） |
| `status` / `active` | 启用状态与逻辑删除标记；`status` 由启动同步强制为 1，`active` 恒为 1——同步不删除任何行（见 5.2） |

> 约束与删除语义：
> - 唯一键 `uk_agent_tool_name (name)`（V40 起）。`name` 就是身份：代码里两个 `@Tool` 方法声明同名时，注册期直接报错而不是让数据库去拦（见 5.2）。
> - 这张表**没有删除语句**：启动同步只插入与更新，`agent_tool_binding` / `agent_tool_env_param` 也随工具一起保留。环境参数定义仍会随注解收敛（那属于更新工具定义）。
> - 表上没有 `tenant_id`：工具是平台级资产，全平台共享一批记录。

### 7.2 agent_tool_binding（Agent-工具绑定表）

对应实体：`AgentToolBinding.kt`

- `agentId` / `toolId`：绑定关系，`(agent_id, tool_id)` 唯一（见下方 V18 说明），保存时按 toolId 去重。去重是兜底而不是校验：后果从「报唯一键」变成「加两张一样的卡、第二张填的环境变量静默丢掉」，所以界面先挡——webui 的下拉按行过滤掉别的行已选过的工具，小程序选中时直接拒绝并 toast（它每张卡共用一个候选 range，按下标过滤会让已选卡片错位）；
- `needConfirm`：绑定级确认，与 `agent_tool.needConfirm` **取或**——只能给某个智能体追加确认，不能取消工具自带的确认；
- `envBindings`：环境变量绑定 JSON 快照（按 Agent 粒度配置工具环境参数）。引用型条目只存 `envVarId` 指针、不存值，读取与下发都按 id 现取（见步骤 3）。

> 历史上还有一列 `enable_skip`（工具缺失时是否跳过），语义只是「报错还是告警跳过」，不构成任何运行时容错能力，已由 `V17__drop_tool_binding_enable_skip.sql` 删除。MCP 绑定表上的同名列也已由 `V20__drop_mcp_binding_enable_skip.sql` 一并删除：它只覆盖「查不到 `mcp_server` 记录」，服务连不上时照样抛错，留着只会误导（见 `mcp-management` 第 7 节）。
>
> `V18__add_tool_binding_unique_key.sql` 先清理同一智能体重复绑定同一工具的历史行（保留最新一条），再为 `(agent_id, tool_id)` 建唯一键，并去掉被其左前缀覆盖的 `idx_agent_tool_binding_agent_id`。
>
> `is_required = 1` 的必须工具**不会写入本表**，见 2.2 / 6.1 节。

### 7.3 agent_tool_env_param（工具环境参数定义表）

对应实体：`AgentToolEnvParam.kt`

`toolId`、`envParamName`、`description`、`required`、`secret`、`defaultValue` —— 由 `@ToolMeta.envParamDefs` 自动同步，供 Admin UI 渲染配置表单。

### 7.4 tool_call_log（工具调用日志表）

`ToolCallLogAdaptorImpl` 将每次 `ToolBox.execute` 的调用结果落库：agentId、sessionId、toolName（`工具组::方法名` 格式）、args（JSON）、result、success、startTime / endTime / duration。日志失败不影响主流程。

## 8. 管理 API（harnax-admin）

实现：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt`，前缀 `/api/admin/tools`。

| 接口 | 方法 | 说明 |
|------|------|------|
| `/page` | GET | 分页查询，参数只有 `pageNum` / `pageSize` / `keyword` / `status` |
| `/{id}` | GET | 工具详情 |
| `/available` | GET | 智能体配置向导候选列表：`status=1 AND active=1 AND is_required = 0`，即可勾选的非必须工具，无过滤参数 |
| `/builtin` | GET | 工具管理页列表：全部工具，**含必须与非必须**（`active=1`） |
| `/{id}/required-env-params` | GET | 查询工具必填环境参数 key |

> 备注：
> - 这是纯粹的只读 API：`PUT /update/{id}`、`PUT /toggle/{id}`、`DELETE /{id}` 已删除，也没有 POST 创建接口（见 5.3）。
> - `AgentToolResponse` 不再返回 `type` / `httpUrl` / `httpMethod` / `httpHeaders` / `inputSchema` / `outputSchema`，环境参数以 `envParams`（取自 `agent_tool_env_param`）与 `requiredEnvParamKeys` 两项给出。
> - 工具是全平台共享的一批记录，`/page` 不做创建者或公开性过滤。

前端管理页面：`harnax-webui/src/pages/tool/`（只读列表）。

## 9. 新工具开发：一步一步操作指南

本节以虚构的 `WeatherToolBox`（天气查询工具）为例，完整走一遍「写代码 → 支持环境变量 → 注册入库 → 绑定智能体 → 验证可用」的全流程。

### 步骤 1：确定工具代码的承载模块

两种方式任选：

**方式 A（推荐）：加入现有 `harnax-tools-buildin` 模块**

直接在 `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/` 下新建类。`harnax-admin` 与 `harnax-agent-service` 均已依赖该模块，**无需修改任何 pom**。

**方式 B：新建独立模块**（适合体量较大、希望独立发布的工具箱）

1. 在 `harnax-tools-external/` 下创建 Maven 子模块（如 `harnax-tools-weather`），依赖 `harnax-tools-sdk`；
2. 在 `harnax-tools-external/pom.xml` 的 `<modules>` 中注册该子模块；
3. 在 **两处** 添加该模块依赖，缺一不可：
   - `harnax-admin/pom.xml`：Admin 启动时需要扫描新 ToolBox 并同步元数据入库；
   - `harnax-agent/harnax-agent-service/pom.xml`：工具真正执行发生在 agent-service 运行时。

### 步骤 2：编写 ToolBox 类

```kotlin
@Component("weather-tool-box")
class WeatherToolBox : ToolBox() {

    @Tool(name = "getWeather", description = "查询指定城市的实时天气")
    @ToolMeta(
        displayName = "Get Weather",
        displayNameZh = "查询天气",
        envParamDefs = [
            ToolEnvParamDef(key = "WEATHER_API_KEY", description = "天气服务 API Key", required = true, secret = true),
            ToolEnvParamDef(key = "WEATHER_BASE_URL", description = "天气服务地址", required = false, defaultValue = "https://api.example.com"),
        ],
        needConfirm = false,
    )
    fun getWeather(
        @ToolParam(name = "city", description = "城市名称，例如：杭州")
        city: String?,
        envContext: ToolEnvContext,
    ): String = execute("city" to city) {
        // LLM 可能传 null，必须自行校验
        require(!city.isNullOrBlank()) { "Parameter 'city' is required" }
        val apiKey = envContext.require("WEATHER_API_KEY")
        val baseUrl = envContext.get("WEATHER_BASE_URL") ?: "https://api.example.com"
        // TODO：调用天气服务 HTTP 接口并返回结果文本
        "weather of $city: ..."
    }

    override fun name(): String = NAME

    companion object {
        const val NAME = "weather-tool-box"
    }
}
```

编写要点清单：

| 要点 | 说明 |
|------|------|
| `@Component` bean 名 | 建议 `xxx-tool-box` 格式，且与 `name()` 返回值一致 |
| `@Tool` | `description` 是模型理解工具用途的唯一依据，写清楚能力与适用场景 |
| `@ToolParam` | 所有需要 LLM 传入的参数**必须**标注（含 `name`、`description`），否则不会进入 JSON Schema，模型无法传值 |
| `ToolEnvContext` 参数 | 框架自动注入，**不要**加 `@ToolParam` |
| `execute {}` | 方法体必须包裹其中，并把关键入参以 `"key" to value` 列出，用于调用日志 |
| 空值防御 | LLM 可能传 `null`，方法体内必须校验 |
| `secret = true` | 密钥类环境参数务必标记，Admin UI 会脱敏展示 |
| `dangerousInput = true` | 入参可能包含危险命令 / 敏感路径时启用（bypass-immune 拦截） |
| `isRequired = true` | 所有 Agent 必备的基础工具：下发时自动追加，不进智能体配置向导候选列表（工具管理页仍可见，且不能配环境参数） |

### 步骤 3：支持环境变量（环境参数）

**声明**：在 `@ToolMeta.envParamDefs` 中逐项声明（见步骤 2 代码），每项包含 `key`、`description`、`required`、`secret`、`defaultValue`。

**赋值**：工具绑定到智能体时，Admin UI 会按声明渲染环境参数表单，操作者二选一：

1. **引用全局环境变量**：先在 Admin「环境变量管理」（`/api/admin/env-variables`，值加密存储）中创建变量，绑定时选择关联（存 `envVarId`）。运行时 Admin 会解析为**最新**的解密值注入——改全局变量即可对所有引用方生效。**快照里不存这个值**，只存指针：客户端回填的是展示值（敏感项即 `******`），存下来等于把一串星号当密钥；在服务端解密后再写则会把明文密钥落进 `env_bindings` 列（AES 密钥只在 admin，见 `mcp-management` §7.16）。指针要落得下去，保存时就得先验一次：`assertEnvBindingsBindable` 检查这批 `envVarId` **解析得到、属于当前租户、且没被停用**，工具 / MCP / CLI 三条绑定路径共用这一道（CLI 也走它，因为 `mergeCliEnvBindings` 同样把这列下发）。两条「停用」的口径配套：`getDecryptedValue` 现在对 `enabled = 0` 返回 null，所以**停用变量等于从所有引用方收回**；而引用停用变量的绑定根本存不进去，否则表单里选得到、运行时是空的。反向的约束是：**被引用的变量删不掉**，`deleteEnvVariable` 会先查三张绑定表的 `envVarId`，命中就报「被 N 个 agent 绑着：…，先改绑再删」；
2. **自定义值**：直接填写字面量（存 `customValue`），以快照形式保存。

`secret = true` 的参数**不预填默认值**：读接口对密钥项的默认值给的也是掩码，预填会把 `abc****wxyz` 这串字面量填进表单并落库。要覆盖它只能自己填一个真值，或者引用一个全局变量。

**完整数据流**：

```
@ToolMeta.envParamDefs（代码声明）
    → admin 启动：BuiltinToolAutoRegistrar 同步到 agent_tool_env_param 表（UI 表单渲染依据）
    → Admin UI：为智能体绑定工具时按表单填写每个 envKey（引用全局变量或自定义值）
    → agent_tool_binding.envBindings（JSON 快照：envKey + envVarId / customValue；引用只有指针，没有值）
    → agent 启动：InternalApiController 将 envVarId 解析为最新解密值（解析不到时回退快照值——新写入没有快照值可回退，只剩一句 warn）
    → AgentSpecResolver 合并全部绑定为扁平 Map，封装 ToolEnvContext
    → HarnessAgentLauncher 注册进 ToolExecutionContext
    → 工具方法的 envContext 参数自动注入，envContext.require("KEY") 取值
```

必填参数留空**保存就会被挡**：`assertRequiredEnvParamsFilled` 按 `agent_tool_env_param.required = 1` 逐条问「运行时拿得到值吗」——有 `envVarId` 引用算拿到，自填值要非空且不含 `****`，而工具自己的 `default_value` **不算**（`ToolConfigAdaptorImpl` 把参数定义装进了运行时的 `AgentTool`，但全仓没有任何一处读它，默认值到不了 `ToolEnvContext`）。两个前端也各有一道提交校验，报参数名，但真正拦得住的是服务端这条。

运行期那句 `Environment parameter 'XXX' is required but not configured`（`require()` 抛出、错误信息返回给模型）因此只剩两种来路：改动之前存下的脏行，以及 `is_required = 1` 的必须工具（它没有绑定行，见 6.1）。

### 步骤 4：编写单元测试

参考 `harnax-tools-buildin/src/test/kotlin/com/agnetix/harnax/tools/buildin/` 下的 `TimeToolBoxTest`、`EmailToolBoxTest`。最低覆盖：

- 正常入参的返回结果；
- 参数缺失 / 非法时的校验异常；
- 必填环境参数缺失时的异常（构造空 `ToolEnvContext` 注入）。

### 步骤 5：注册工具到数据库（全自动，无需手工插入）

1. 构建：`mvn clean install` 构建承载模块；
2. **重启 harnax-admin**：`BuiltinToolAutoRegistrar` 在启动时扫描 `ToolRegistry`，按 5.2 做全量收敛——库里没有的工具名插入、已有同名行逐列对比后有差异才更新、库里多出来的行保持不动；环境参数定义同步到 `agent_tool_env_param`；
3. 验证注册结果：Admin UI「工具管理」页面能看到新工具，或查询 `agent_tool` 表确认记录；
4. **重启 harnax-agent-service**：工具实际执行在 agent-service，未重启时 `ToolRegistry` 中没有新 ToolBox，运行时会找不到该工具并打告警日志后跳过（该行为无开关可配）。

> 同步策略提醒：内置工具的新增与修改都以代码为准，重启即生效；工具管理页只读，没有编辑 / 删除 / 停用的入口。改 Java 方法名或 bean 名**还是同一个工具**，行原地刷新，`id` 与绑定（含用户填的环境参数值）全部保留；改 `@Tool(name = ...)` 则是**换了一个工具**——新名插一条新行，旧行保留但不再下发，旧行上的智能体绑定不会迁移，需要在配置里对新工具重新勾选。库里多余的行不会被自动清理（同步不删除任何行，见 5.2）。

### 步骤 6：为智能体绑定工具并配置环境变量

1. Admin UI 进入智能体配置（创建或编辑），在工具选择步骤勾选新工具（向导候选列表只含 `is_required = 0` 的工具；标了 `isRequired = true` 的工具不在列表中，也无需勾选，下发时自动追加）；
2. 按表单为必填环境参数赋值（引用全局变量或自定义值）——留空保存不了：服务端会报出缺哪几个参数名，跨租户或已删除的 `envVarId` 同样在这一步被挡；
3. 按需设置 `needConfirm`（执行前二次确认）——该开关只能加严：打开后本智能体每次调用都确认，工具本身已要求确认的无法在此取消；
4. 保存，绑定写入 `agent_tool_binding`。

### 步骤 7：验证工具可用

1. 与该智能体发起会话，引导模型调用新工具（如「查一下杭州的天气」）；
2. 观察前端会话页的工具调用卡片（SSE 工具事件流）；
3. 检查 `tool_call_log` 表 / 服务日志，确认出现 `weather-tool-box::getWeather` 的调用记录（含入参、结果、耗时）；
4. `needConfirm=true` 的工具验证 ASK 确认交互；
5. 故意留空一个必填环境参数，确认保存被挡下且报出参数名（运行期那句「参数未配置」已经拿不到这种输入了，它只会出现在改动之前存下的脏行和 `is_required = 1` 的必须工具上）。

### 常见陷阱速查

| 现象 | 原因与处理 |
|------|-----------|
| 工具在模型侧「不存在」，参数传不进来 | 参数未加 `@ToolParam`，重新检查注解 |
| 工具管理页面看不到新工具 | admin 未重启（未同步），或该 ToolBox 没有扫到 `@Tool` 方法（`ToolRegistry` 里没有它的元数据） |
| 智能体配置向导里选不到 | 该工具 `is_required = 1`（必须工具不进候选列表，下发时自动带上） |
| 运行时日志出现 `Tool ... not found, skipping` | `agent_tool` 记录缺失（admin 未重启同步）、`beanName` 为空，或 agent-service 未重启导致 `ToolRegistry` 中没有该 ToolBox；该工具会被跳过，不会兜底注册 |
| 环境参数取不到值 | 引用型条目在快照里不存值，只存 `envVarId`：变量还在就一定按最新值解析，所以取不到通常是 envKey 与代码声明不一致，或者 `agent_tool_binding.envBindings` 里根本没有这个 key（保存时的必填与引用校验现在会先挡一道）。剩下一种静默情况是改动之前存下的历史行：里面可能带着一串掩码当值，变量又已被删除，才会兜出星号 |
| 表单里看着填好了，工具拿到一串星号 | 那是掩码不是值。两处来源：`secret = true` 的参数曾把默认值掩码预填进绑定框，以及引用型快照曾把 `displayValue`（敏感项即 `******`）当值存下——本轮都改了（敏感项一律留空、引用不落值）。判定口径是「含 `****` 的不算已填」，历史脏行需要重新填一次 |
| 必须工具运行期报「环境参数未配置」 | `isRequired = true` 的工具没有绑定行，拿不到任何 envBindings 快照。必须工具不要声明必填环境参数；确实需要外部配置，改为在非必须工具上声明，或让代码用 `ToolEnvContext.get(key)` 自行兜默认值，避免 `require` |
| 想停用 / 改名 / 删除某个工具 | 没有这种入口：工具由代码同步独占管理（见 5.3），写接口本身不存在，页面也没有开关。要停用或删除就在代码里去掉该 `@Tool` 方法重新发布——库里那行会保留但不再下发（见 5.2）；手工改库会在下次 admin 重启时被收敛回代码状态 |
| 改了 `@Tool(name = ...)` 后智能体说「找不到工具」 | 身份就是 `name`，改名等于换了一个工具：新名入库为新行，旧行保留但不再下发，挂在旧行上的 `agent_tool_binding` 不迁移——去智能体配置里对新工具重新勾选 |
| 改了 Java 方法名或 bean 名后智能体说「找不到工具」 | 这两者不参与身份判定，正常情况不会出现：行会原地刷新，`id` 与绑定都保留。若确实出现，先查启动日志有没有该工具的 `Updated tool ... (id=N)` |
| 代码里删掉的工具在表里还在 | 这是预期行为：同步不删除任何行（见 5.2），旧行原样保留、也不再下发。要真正清掉只能手工处理（先确认没有任何绑定再删行） |
| 多会话上下文串扰 | 不要缓存单例状态；运行时已按会话创建 ToolBox 新实例，方法内避免依赖可变成员变量 |


## 10. 关键文件索引

| 模块 | 文件 |
|------|------|
| SDK 基类 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt` |
| 元数据注解 | 同目录 `ToolMeta.kt`、`ToolEnvParamDef.kt`、`ToolMetaDescriptor.kt` |
| 环境上下文 | 同目录 `ToolEnvContext.kt`、`ToolCallContext.kt` |
| 注册中心 | 同目录 `registry/ToolRegistry.kt` |
| 适配器接口 | 同目录 `adaptor/ToolCallLogAdaptor.kt`、`adaptor/ToolConfigAdaptor.kt` |
| 内置工具 | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt`、`EmailToolBox.kt` |
| 运行时装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` |
| 危险输入包装 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt` |
| Spec 解析 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` |
| 适配器实现 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt`、`ToolCallLogAdaptorImpl.kt` |
| 启动同步 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` |
| AgentSpec 下发（必须工具在此追加） | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| 管理 API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt` |
| 实体 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`、`AgentToolBinding.kt`、`AgentToolEnvParam.kt` |
| 整轮超时 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt`、`HarnessAgentWrapper.kt` |
| 迁移脚本 | `harnax-admin/src/main/resources/db/migration/V4__refactor_tool_granularity.sql`、`V17__drop_tool_binding_enable_skip.sql`、`V18__add_tool_binding_unique_key.sql`、`V29__drop_custom_and_http_tool.sql`、`V40__tool_registry_platform_scoped.sql`（平台级 + 身份 = `name` + 去工具级超时） |

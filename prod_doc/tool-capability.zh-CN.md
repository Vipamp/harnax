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

### 2.1 两大类：内置工具与自定义工具

| 类别 | `agent_tool.type` | 可见范围 | 落地状态 |
|------|-------------------|----------|----------|
| **内置工具** | `BUILTIN` | 全平台所有用户可用（随代码发布，启动时自动同步入库） | 已上线 |
| **自定义工具** | `CUSTOM` / `HTTP` | 仅创建者本人可用（靠 `creator` + `is_public` 过滤） | **暂未开放**：装配、加密、DTO 等链路代码全部保留，Admin 前端不展示，仅通过直接写库或调用 Service 才能产生记录 |

> 两类工具在运行时走同一条装配路径（`HarnessAgentLauncher.createAgentBase()`），差异只在 `type` 分支：`BUILTIN`/`CUSTOM` 按 `beanName` 反射创建 ToolBox，`HTTP` 实例化 `HttpProxyToolBox`。

### 2.2 内置工具再分：必须工具与非必须工具

| 子类 | `agent_tool.is_required` | 谁来选中 | 运行时来源 |
|------|--------------------------|----------|------------|
| **必须工具** | `1` | 无人可选，也不需要选 | Admin 下发 AgentSpec 时自动追加（见 6.1），**不落 `agent_tool_binding`** |
| **非必须内置工具** | `0` | 用户在智能体配置向导中勾选 | `agent_tool_binding` 绑定记录 |
| 自定义工具 | `0` | 同上（暂不开放） | 同上 |

对应的前端口径：

- **工具管理页面**（`/api/admin/tools/builtin`）：内置工具全量展示，必须与非必须都可见，用「必须 / 可选」标签区分；自定义工具不展示。
- **智能体配置向导**（`/api/admin/tools/available`）：只列出可勾选的工具，即 `is_required = 0` 的启用工具，必须工具不出现在候选列表中。
- 必须工具同样不受理绑定：`agent_tool.is_required` 由 `@ToolMeta(isRequired)` 同步而来，UI 不提供开关。

> MCP 服务的实体管理、加密存储与运行时装配全链路，见 [mcp-management.zh-CN.md](./mcp-management.zh-CN.md)。
>
> 技能（Skill）是与工具并列的另一类能力来源（SKILL.md + 附属资源，不进入 `agent_tool` 表），其仓库管理、同步落库与运行时装配见 [skill-management.zh-CN.md](./skill-management.zh-CN.md)。

## 3. SDK 核心概念（harnax-tools-sdk）

### 3.1 ToolBox 抽象基类

路径：`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`

- 所有内置/自定义工具必须继承 `ToolBox` 并实现 `name()`（工具组逻辑名）。
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
| `envParamDefs` | `[]` | 环境参数定义数组（`ToolEnvParamDef`），启动时同步到 `agent_tool_env_param` 表，作为 Admin UI 绑定工具时环境参数表单的渲染依据；每项含 `key`（参数名）、`description`（UI 说明）、`required`（是否必填）、`secret`（是否密钥，UI 脱敏）、`defaultValue`（默认值，仅限非密钥），详见 3.4 节 |
| `timeoutSeconds` | `0` | 执行超时秒数；`0` 表示使用系统默认值（同步入库时写为 30 秒） |
| `isPublic` | `true` | 是否公开可用（面向所有用户可见） |
| `needConfirm` | `false` | **执行前是否需要用户确认**。为 `true` 时运行时生成 ASK 权限规则，每次调用都会暂停并等待用户确认；不检查入参内容，与调用参数无关；在 `BYPASS` 权限模式下会被跳过。内置工具的该字段由代码注解决定，页面上改不了；不改代码想给某个智能体加严，只能针对绑定项设置 `agent_tool_binding.needConfirm`——运行时取两者之或，绑定层只能追加确认、不能取消工具自带的确认（`agent_tool.needConfirm` 本身只有自定义 / HTTP 工具可在 UI 修改），详见 6.4 节 |
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

### 3.6 HttpProxyToolBox（HTTP 代理工具）

路径：`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/HttpProxyToolBox.kt`

直接实现 agentscope 的 `AgentTool` 接口（不继承 `ToolBox`），按数据库配置实例化：

- 构造参数：`toolName`、`toolDescription`、`httpUrl`、`httpMethod`（默认 POST）、`httpHeaders`（加密存储，运行时经 `McpConfigDecryptor` 解密）、`inputSchemaJson`（LLM 参数 Schema）、`timeoutSeconds`；
- 调用时把模型入参序列化为 JSON body 发送，2xx 返回响应体，非 2xx 或异常返回错误文本（不会抛出中断会话）。

### 3.7 适配器接口（SPI）

| 接口 | 职责 | 实现方 |
|------|------|--------|
| `ToolCallLogAdaptor` | 上报工具调用日志（`ToolCallInfo`：agentId、sessionId、toolName、args、result、success、耗时） | `ToolCallLogAdaptorImpl`（agent-service，落库 `tool_call_log`） |
| `ToolConfigAdaptor` | 按 `toolId` 查询工具配置（`AgentTool` 实体） | `ToolConfigAdaptorImpl`（agent-service，优先读 admin 预解析上下文，回退查库） |

### 3.8 其他数据类

- `ToolSpec`：Agent 配置中的工具引用（`toolId`、`toolName`、`needConfirm` 覆盖）。
- `SessionMetaContext` / `UserIdentifier`：会话与用户上下文，实现 `ToolCallContext` 标记接口。

## 4. 内置工具现状（harnax-tools-buildin）

路径：`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`

### 4.1 TimeToolBox（bean `time-tool-box`）

| `@Tool` 方法名 | `methodName` | 说明 | readOnly | needConfirm | isRequired | 超时 | 环境参数 |
|----------------|--------------|------|----------|-------------|------------|------|----------|
| `getDate` | `getDate` | 获取当前日期（`yyyy-MM-dd`） | 是 | 否 | 否 | 默认 30s | 无 |
| `getDatetime` | `getDatetime` | 获取当前时间（`yyyy-MM-dd HH:mm:ss`） | 是 | 否 | 否 | 默认 30s | 无 |

### 4.2 EmailToolBox（bean `email-tool-box`）

| `@Tool` 方法名 | `methodName` | 说明 | readOnly | needConfirm | isRequired | 超时 | 环境参数 |
|----------------|--------------|------|----------|-------------|------------|------|----------|
| `sendEmail` | `sendEmail` | 通过 SMTP 发送邮件，支持纯文本 / HTML 正文 | 否 | **是** | 否 | 默认 30s | 5 项（见下） |

- 直接使用 Jakarta Mail，不依赖 Spring。
- LLM 参数：`to`、`subject`、`body`、`is_html`（可选）。
- 环境参数：`SMTP_HOST`（必填）、`SMTP_PORT`（可选，默认 587）、`SMTP_USER`（必填）、`SMTP_PASSWORD`（必填，密钥）、`SMTP_FROM`（必填）。
- 端口策略：465 走隐式 SSL，25 不加密，其余（含 587）强制 STARTTLS。

> 三个方法的 `isPublic` 都取注解默认值 `true`，因此同步后 `agent_tool.is_public = 1`，对所有用户可见。当前代码库里**没有 `isRequired = true` 的内置工具**——「必须工具」这条分支（下发时自动追加、不落绑定表、启动矛盾告警）已实现并有用例覆盖，但线上暂时没有工具使用它。

## 5. 内置工具注册机制（唯一的生命周期入口）

实现：

- 扫描：`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt`（`@PostConstruct`）
- 入库：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt`（`ApplicationReadyEvent`）

**约定**：内置工具（`type = 'BUILTIN'`）的新增、更新、删除**只有这一条路径**。代码里的 `@Tool` / `@ToolMeta` 是它唯一的事实来源，页面和接口都不提供对内置工具的写操作。

### 5.1 扫描

`ToolRegistry` 启动时取 `getBeansOfType(ToolBox::class.java)`，逐 bean、逐方法读取 `@Tool` + `@ToolMeta`，产出 `ToolMetaDescriptor`（`beanName` 下挂每个方法的 `toolName`、`methodName`、`displayName` / `displayNameZh`、`description`、`readOnly`、`needConfirm`、`isRequired`、`isPublic`、`timeoutSeconds`、`envParamDescriptors`）。没有 `@Tool` 方法的 bean 不产出元数据，也就不会入库。

### 5.2 同步（每次 admin 启动做一次全量收敛）

1. **新增**：代码里有、库里没有的方法 → 插入一条 `agent_tool`（`type='BUILTIN'`、`status=1`、`active=1`、`creator='SYSTEM'`、`tenant_id=1`）。
2. **更新**：代码里改了工具名、描述、展示名、`needConfirm`、`isRequired`、超时、环境参数定义等 → 逐字段覆盖写回数据库，`name` 也在覆盖之列。**`status` 同样收敛为 1**，内置工具不存在「人工停用」这种状态。
3. **删除**：以 `beanName + methodName + toolName` 为身份键。库里 `type='BUILTIN'` 的记录（含 `active=0` 的历史行）只要不在这次代码声明的方法全集里，就**硬删除**，并级联清理它的 `agent_tool_env_param` 定义行和 `agent_tool_binding` 绑定行。覆盖两类残留：ToolBox 类被删 / 某个 `@Tool` 方法的 Java 方法名被改或被删。
4. **环境参数定义同步**：`@ToolMeta.envParamDefs` → `agent_tool_env_param`，采用「就地更新 + 新增插入 + 过期删除」策略，保留记录 ID。
5. **删除的三道保险**（删除是这个同步唯一不能出错的动作）：
   - `ToolRegistry` 一个 `@Tool` 方法都没扫到时（例如工具模块没被扫进容器），整个同步直接跳过，不删任何东西；
   - 只要有任意一个工具组同步失败（`failCount > 0`），当次不做删除——代码声明不完整时，「库里多出来的行」不可信；
   - 待删行数 ≥ 代码声明的方法数时，判定为扫描范围出了问题而非代码删了工具，跳过删除并打 ERROR 日志（日志里列出全部待删记录），需人工核对代码后重新发布。
6. **配置矛盾告警**：某个方法同时标了 `isRequired = true` 与必填 `envParamDefs` 时启动日志告警——必须工具没有绑定行、取不到环境参数，这种组合运行期必然失败（详见 6.1）。

> 改名的代价分两种：
> - 只改 `@Tool(name = ...)`：**原地收敛**。唯一键是 `(tenant_id, bean_name, method_name, active)`，改的又是 `name` 字段，因此 `id` 不变、挂在它上面的 `agent_tool_binding`（用户填的环境参数值、确认开关）全部保留，无需重新勾选。
> - 改 Java 方法名（`methodName`）或改 bean 名：身份键变了，等价于「删旧 + 建新」，`id` 变化，旧行上的绑定随级联清理删除，需要在智能体配置里重新勾选并补环境参数。

> 内置工具固定写 `tenant_id = 1`，且 `MybatisTenantInterceptor` 的租户过滤当前未启用：内置工具是全平台共享的一批记录，不按租户各存一份。

> 因此「必须 / 非必须」的划分、工具是否存在、字段取值都只能改代码重新发布，运营侧不可调整。

### 5.3 外部写入口：全部关闭

| 入口 | 对内置工具 |
|------|-----------|
| 前端（webui 工具管理页、小程序工具列表） | 只读展示，无新增 / 编辑 / 删除 / 停用；`services/ant-design-pro/tool.ts` 里的 update / toggle / delete 请求封装已删除 |
| `AgentToolService.createAgentTool` | 服务层拒绝 `type = 'BUILTIN'`；Controller 本来也没有创建接口 |
| `/update/{id}`、`/toggle/{id}`、`DELETE /{id}` | 服务层查到目标是 `BUILTIN` 即抛 `BizException`，消息为「Builtin tools are owned by the code sync (BuiltinToolAutoRegistrar): ... is not allowed」；Controller 统一包成 `ResultVo.error`，前端收到非 200 |

`/update/{id}` 会**两头都查**：库里这行是 `BUILTIN` 拒绝，请求把 `type` 改成 `BUILTIN` 也拒绝——后者拦的是「把一条自定义工具改成内置、从此交给代码同步」这条路。

这三条写接口只为 `CUSTOM` / `HTTP`（自定义工具）保留。

### 5.4 幂等性与排障锚点

- **触发时机**：`BuiltinToolAutoRegistrar` 挂在 `ApplicationReadyEvent`，不是 `@PostConstruct`——`ToolRegistry` 的扫描是 `@PostConstruct`，但入库要等 Flyway 迁移与数据源就绪，用启动完成事件才能保证 `agent_tool` 表已经存在。
- **幂等**：每次启动全量重放一遍。靠唯一键 + `ON DUPLICATE KEY UPDATE`，重复启动不会产生新行，除 `update_time` 外没有任何字段值会变化；环境参数定义同样就地收敛，保留记录 ID。
- **故障隔离**：每个 bean 一个 `try/catch`，某个工具组同步失败只影响该组（其余照常写入），代价是当次不做删除。
- **日志锚点**（`grep` admin 启动日志即可定位）：

| 日志片段 | 含义 |
|----------|------|
| `Syncing N builtin tool groups to database` | 扫到 N 个 ToolBox，开始同步 |
| `Required tool '...' declares required env params ...` | WARN：`isRequired` 与必填环境参数矛盾（见 6.1） |
| `Synced tool group: xxx [N methods]` | 该组写入成功 |
| `Synced env params for tool 'bean::name': N total, M stale removed` | 环境参数定义收敛完成；`M > 0` 说明代码里删掉了参数定义（`agent_tool_env_param` 已同步清理） |
| `Failed to sync tool group: xxx` | 该组异常，当次删除已被取消 |
| `Sync complete: X succeeded, Y failed` | 总览；`Y > 0` 时删除一定没执行 |
| `Skipping prune: ...` | ERROR：删除被三道保险之一拦住，多余记录仍在库里 |
| `Removed N builtin tool record(s) no longer declared by the code: [...]` | 实际删除发生，括号里是 `beanName::methodName(id=…)` 清单 |
| `No @Tool annotated methods found, skipping sync` | 一个方法都没扫到，整个同步跳过 |

- **单元用例**：收敛规则的行为覆盖在 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrarTest.kt`（8 例）与 `AgentToolServiceImplTest`（写入口拒绝 5 例），编号见 `docs/unit-test-cases.md` §5.1 / §5.2。

## 6. Agent 运行时工具装配

核心实现：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` 的 `createAgentBase()`。

### 6.1 装配流程

```
InternalApiController.buildAgentSpecResponse（Admin 下发阶段）
        ├─ toolDetails = agent_tool_binding 中的绑定工具
        │                + is_required=1 且启用的内置工具（按 id 去重追加，不写绑定表）
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
        │    ├─ BUILTIN/CUSTOM → ToolRegistry.createToolBoxInstance(beanName)
        │    │      → init(日志适配器, SessionMetaContext, UserIdentifier)
        │    │      → agentBuilder.addTool(toolBox)（注册该 ToolBox 的全部 @Tool 方法）
        │    ├─ HTTP → new HttpProxyToolBox(...)（解密 headers）
        │    │      → agentBuilder.registerAgentTool(...)
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

- **必须工具在下发阶段注入**：`is_required = 1` 的内置工具由 `InternalApiController` 追加进 `toolDetails`（与绑定工具按 id 去重），Agent 配置里勾不到、也关不掉；要去掉它只能改代码——取消 `isRequired` 或删除该 `@Tool` 方法，重新发布后由同步机制收敛（见 5.2）。
- **`status` 随下发透传**：`ToolDetailDto.status` → `ToolConfigAdaptorImpl` 还原实体 → 运行时 `status == 0` 跳过。这条链路缺任一环都会让「停用工具」静默失效。注意内置工具的 `status` 由同步强制为 1，该分支实际只对自定义 / HTTP 工具生效。
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
- 不改代码也能调整：内置工具的 `agent_tool.needConfirm` 在页面上改不了（见 5.3），只能给某个智能体的绑定设置 `agent_tool_binding.needConfirm` 追加确认（运行时取两者之或，取消不掉工具自带的确认）；自定义 / HTTP 工具的 `agent_tool.needConfirm` 仍可通过工具管理接口修改。

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

## 7. 数据模型

### 7.1 agent_tool（工具主表）

对应实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`

| 字段 | 说明 |
|------|------|
| `name` / `displayName` / `displayNameZh` | 工具标识名与中英文展示名 |
| `description` | 工具描述（发送给 LLM） |
| `type` | `BUILTIN` / `CUSTOM` / `HTTP` |
| `beanName` / `methodName` | BUILTIN/CUSTOM 类型定位 ToolBox Bean 与方法 |
| `httpUrl` / `httpMethod` / `httpHeaders` | HTTP 类型的请求配置（headers 加密存储） |
| `inputSchema` / `outputSchema` | HTTP 类型的 JSON Schema |
| `envParams` / `requiredEnvParamKeys` | 环境参数配置与必填 key 列表（JSON） |
| `readOnly` / `needConfirm` / `isRequired` | 只读、需确认、必须工具标记（0/1） |
| `timeoutSeconds` | 超时（默认 30 秒） |
| `status` / `isPublic` / `active` | 启用状态、公开状态、逻辑删除 |

> 约束与删除语义：
> - 唯一键 `uk_tenant_bean_method (tenant_id, bean_name, method_name, active)`（V4 起）。`name` **不在唯一键里**，所以同步能在同一条记录上原地改 `name`（见 5.2）；代价是代码里两个方法标了同名 `@Tool(name)` 时数据库不会拦。
> - `deleteById` 是软删（置 `active = 0`），只对自定义 / HTTP 工具生效；内置行的移除走 `deleteBuiltinByIds` 硬删，SQL 里带 `type = 'BUILTIN'` 守卫，碰不到用户自建记录，且只由注册机制调用。

### 7.2 agent_tool_binding（Agent-工具绑定表）

对应实体：`AgentToolBinding.kt`

- `agentId` / `toolId`：绑定关系，`(agent_id, tool_id)` 唯一（见下方 V18 说明），保存时按 toolId 去重；
- `needConfirm`：绑定级确认，与 `agent_tool.needConfirm` **取或**——只能给某个智能体追加确认，不能取消工具自带的确认；
- `envBindings`：环境变量绑定 JSON 快照（按 Agent 粒度配置工具环境参数）。

> 历史上还有一列 `enable_skip`（工具缺失时是否跳过），语义只是「报错还是告警跳过」，不构成任何运行时容错能力，已由 `V17__drop_tool_binding_enable_skip.sql` 删除。MCP 绑定表 `agent_mcp_binding.enable_skip` 保留。
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
| `/page` | GET | 分页查询（支持 keyword / status / type 过滤） |
| `/{id}` | GET | 工具详情 |
| `/update/{id}` | PUT | 更新工具（**仅 `CUSTOM` / `HTTP`**，目标是内置工具时拒绝） |
| `/toggle/{id}` | PUT | 启用 / 禁用 status 0/1（**仅 `CUSTOM` / `HTTP`**，内置工具恒为启用） |
| `/{id}` | DELETE | 逻辑删除（**仅 `CUSTOM` / `HTTP`**，内置工具的删除由注册机制负责） |
| `/available` | GET | 智能体配置向导候选列表：`status=1 AND active=1 AND is_required = 0`，即可勾选的非必须工具（可按 type 过滤） |
| `/builtin` | GET | 工具管理页列表：全部内置工具，**含必须与非必须**（`type='BUILTIN' AND active=1`） |
| `/{id}/required-env-params` | GET | 查询工具必填环境参数 key |

> 备注：
> - 内置工具没有任何写入口（见 5.3）：写接口在 service 层按 `type` 拦截，前端工具页面只做展示。
> - `AgentToolService.createAgentTool` 已实现（支持创建 HTTP 等自定义工具，含密钥字段加密），但 Controller 未暴露 POST 创建接口，且服务层拒绝 `type = 'BUILTIN'`；自定义工具（`CUSTOM` / `HTTP`）整体暂未开放，前端工具管理页只展示内置工具，相关代码保留。
> - `/page` 走 `is_public = 1 OR creator = 当前用户` 的可见性过滤，`/builtin` 与 `/available` 不做该过滤（内置工具面向全平台）。

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
        timeoutSeconds = 15,
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

1. **引用全局环境变量**：先在 Admin「环境变量管理」（`/api/admin/env-variables`，值加密存储）中创建变量，绑定时选择关联（存 `envVarId`）。运行时 Admin 会解析为**最新**的解密值注入——改全局变量即可对所有引用方生效；
2. **自定义值**：直接填写字面量（存 `customValue`），以快照形式保存。

**完整数据流**：

```
@ToolMeta.envParamDefs（代码声明）
    → admin 启动：BuiltinToolAutoRegistrar 同步到 agent_tool_env_param 表（UI 表单渲染依据）
    → Admin UI：为智能体绑定工具时按表单填写每个 envKey（引用全局变量或自定义值）
    → agent_tool_binding.envBindings（JSON 快照：envKey + envVarId / customValue）
    → agent 启动：InternalApiController 将 envVarId 解析为最新解密值（失败回退快照值）
    → AgentSpecResolver 合并全部绑定为扁平 Map，封装 ToolEnvContext
    → HarnessAgentLauncher 注册进 ToolExecutionContext
    → 工具方法的 envContext 参数自动注入，envContext.require("KEY") 取值
```

必填参数未配置时，`require()` 抛出 `Environment parameter 'XXX' is required but not configured`，错误信息会返回给模型。

### 步骤 4：编写单元测试

参考 `harnax-tools-buildin/src/test/kotlin/com/agnetix/harnax/tools/buildin/` 下的 `TimeToolBoxTest`、`EmailToolBoxTest`。最低覆盖：

- 正常入参的返回结果；
- 参数缺失 / 非法时的校验异常；
- 必填环境参数缺失时的异常（构造空 `ToolEnvContext` 注入）。

### 步骤 5：注册工具到数据库（全自动，无需手工插入）

1. 构建：`mvn clean install` 构建承载模块；
2. **重启 harnax-admin**：`BuiltinToolAutoRegistrar` 在启动时扫描 `ToolRegistry`，按 5.2 做全量收敛——新方法插入、改动的方法覆盖更新、代码里已不存在的方法连绑定一起删除；环境参数定义同步到 `agent_tool_env_param`；
3. 验证注册结果：Admin UI「工具管理」页面能看到新工具，或查询 `agent_tool` 表确认记录；
4. **重启 harnax-agent-service**：工具实际执行在 agent-service，未重启时 `ToolRegistry` 中没有新 ToolBox，运行时会找不到该工具并打告警日志后跳过（该行为无开关可配）。

> 同步策略提醒：内置工具的新增、修改、删除都以代码为准，重启即生效；工具管理页只读，没有编辑 / 删除 / 停用的入口。改 `@Tool(name = ...)` 会原地更新这条记录（`id` 与绑定不动）；改 Java 方法名或 bean 名才是删旧建新，旧工具上的智能体绑定（含用户填的环境参数值）会一并删除，需要重新勾选。删除被保险拦住时（某组同步失败、或待删条数不少于代码声明条数）启动日志会打 ERROR 并列出待删记录，此时库里会留下暂时多余的记录。

### 步骤 6：为智能体绑定工具并配置环境变量

1. Admin UI 进入智能体配置（创建或编辑），在工具选择步骤勾选新工具（向导候选列表只含 `is_required = 0` 的工具；标了 `isRequired = true` 的工具不在列表中，也无需勾选，下发时自动追加）；
2. 按表单为必填环境参数赋值（引用全局变量或自定义值）；
3. 按需设置 `needConfirm`（执行前二次确认）——该开关只能加严：打开后本智能体每次调用都确认，工具本身已要求确认的无法在此取消；
4. 保存，绑定写入 `agent_tool_binding`。

### 步骤 7：验证工具可用

1. 与该智能体发起会话，引导模型调用新工具（如「查一下杭州的天气」）；
2. 观察前端会话页的工具调用卡片（SSE 工具事件流）；
3. 检查 `tool_call_log` 表 / 服务日志，确认出现 `weather-tool-box::getWeather` 的调用记录（含入参、结果、耗时）；
4. `needConfirm=true` 的工具验证 ASK 确认交互；
5. 故意不配置必填环境参数，验证工具把「参数未配置」错误返回给模型而不是静默失败。

### 常见陷阱速查

| 现象 | 原因与处理 |
|------|-----------|
| 工具在模型侧「不存在」，参数传不进来 | 参数未加 `@ToolParam`，重新检查注解 |
| 工具管理页面看不到新工具 | admin 未重启（未同步），或该 ToolBox 没有扫到 `@Tool` 方法（`ToolRegistry` 里没有它的元数据），或该记录 `type` 不是 `BUILTIN`（自定义工具暂不在前端展示） |
| 智能体配置向导里选不到 | 该工具 `is_required = 1`（必须工具不进候选列表，下发时自动带上），或它是自定义 / HTTP 工具且 `status = 0` |
| 运行时日志出现 `Tool ... not found, skipping` | `agent_tool` 记录缺失（admin 未重启同步）、`beanName` 为空，或 agent-service 未重启导致 `ToolRegistry` 中没有该 ToolBox；该工具会被跳过，不会兜底注册 |
| 环境参数取不到值 | 绑定时未赋值；确认 `agent_tool_binding.envBindings` 中 envKey 与代码声明一致 |
| 必须工具运行期报「环境参数未配置」 | `isRequired = true` 的工具没有绑定行，拿不到任何 envBindings 快照。必须工具不要声明必填环境参数；确实需要外部配置，改为在非必须工具上声明，或让代码用 `ToolEnvContext.get(key)` 自行兜默认值，避免 `require` |
| 想停用 / 改名 / 删除某个内置工具 | 没有这种入口：内置工具由代码同步独占管理（见 5.3），写接口对 `BUILTIN` 直接拒绝，页面也没有开关。要去掉或改名就改注解重新发布；手工改库里的记录会在下次 admin 重启时被收敛回代码状态 |
| 改了 Java 方法名或 bean 名后智能体说「找不到工具」 | 身份键是 `beanName + methodName + toolName`，改这两个之一等于删旧建新，`id` 变了，挂在旧 id 上的 `agent_tool_binding` 已随级联清理删除——去智能体配置里重新勾选该工具并补环境参数。只改 `@Tool(name = ...)` 不会有这个问题，记录会原地更新 |
| 代码里删掉的工具在表里还在 | 删除被保险拦住了：某组同步失败、或待删条数不少于代码声明条数。查 admin 启动日志里的 `Skipping prune` ERROR，确认代码无误后重新发布 |
| 多会话上下文串扰 | 不要缓存单例状态；运行时已按会话创建 ToolBox 新实例，方法内避免依赖可变成员变量 |


## 10. 关键文件索引

| 模块 | 文件 |
|------|------|
| SDK 基类 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt` |
| 元数据注解 | 同目录 `ToolMeta.kt`、`ToolEnvParamDef.kt`、`ToolMetaDescriptor.kt` |
| 环境上下文 | 同目录 `ToolEnvContext.kt`、`ToolCallContext.kt` |
| 注册中心 | 同目录 `registry/ToolRegistry.kt` |
| HTTP 代理工具 | 同目录 `HttpProxyToolBox.kt` |
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

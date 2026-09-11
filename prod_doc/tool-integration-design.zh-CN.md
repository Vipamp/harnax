# Harnax 工具集成设计（中文）

> 英文版本见 [tool-integration-design.en-US.md](./tool-integration-design.en-US.md)
>
> 本文回答「工具体系为什么长成这样」：分类模型、数据模型、分层职责、关键设计决策与演进时间线。
> 「怎么用」——注解写法、注册收敛规则、API 清单、新工具开发步骤、排障——见 [tool-capability.zh-CN.md](./tool-capability.zh-CN.md)。
> MCP 服务见 [mcp-management.zh-CN.md](./mcp-management.zh-CN.md)，技能见 [skill-management.zh-CN.md](./skill-management.zh-CN.md)。
>
> 早期方案稿 `harnax-admin/TOOL_INTEGRATION_DESIGN.md` 保留作历史归档，其中与本文冲突的部分（`enable_skip`、工具创建接口、按 ToolBox 一条记录、运行时默认工具集兜底）一律以本文为准。

## 1. 设计目标

| 目标 | 落地方式 |
|------|----------|
| 工具可动态扩展，不必改硬编码工具集 | `ToolRegistry` 扫描容器内 `ToolBox`，`BuiltinToolAutoRegistrar` 启动时收敛入库 |
| Agent 按配置选工具，而不是共享全部工具 | `agent_tool_binding` 绑定表 + 下发 `toolDetails` + 运行时按绑定装配 |
| 与 MCP / Skill 管理形态对齐 | 同样是「实体表 + Admin 管理 + 绑定表 + Spec 下发 + 运行时适配器」五段式 |
| 内置工具不被运营侧改坏 | 生命周期由代码同步独占，页面与 API 一律拒写 `type='BUILTIN'` |
| 工具级差异化策略（确认、环境参数、必填） | 粒度下沉到 `@Tool` 方法：一个方法一条 `agent_tool` 记录 |

不在这套体系里的：MCP 工具（独立表 `mcp_server`，见 mcp-management）、技能（`skill`，不进入 `agent_tool`）、CLI 插件（`cli` + `cli_skill_binding`）。

## 2. 分类模型

### 2.1 按实现方式分（`agent_tool.type`）

| 类型 | 事实来源 | 运行时载体 | 生命周期归谁 |
|------|----------|------------|--------------|
| `BUILTIN` | 代码注解 `@Tool` + `@ToolMeta` | `ToolRegistry.createToolBoxInstance(beanName)` 反射创建会话级实例 | **代码同步独占**（注册机制，无外部写入口） |
| `CUSTOM` | 数据库记录 + 用户代码里的 ToolBox Bean | 同上 | Admin 写接口（暂未开放） |
| `HTTP` | 纯数据库记录（URL / method / headers / inputSchema） | `HttpProxyToolBox` | Admin 写接口（暂未开放） |

`BUILTIN` 与 `CUSTOM` 运行时走同一条分支（都按 `beanName` 取 ToolBox），差别只在谁被允许写这条记录。

### 2.2 按是否可关分（`agent_tool.is_required`）

| 子类 | 谁选中 | 绑定行 | 环境参数 |
|------|--------|--------|----------|
| 必须工具（`is_required=1`） | 无人可选，下发时自动追加 | 无 | 取不到（见 6.4） |
| 非必须内置工具 | 用户在智能体配置向导勾选 | `agent_tool_binding` | 按 Agent 配置 |

这两个维度是**正交**的：`is_required` 只对 `BUILTIN` 有意义，由 `@ToolMeta(isRequired)` 同步，UI 无开关。

## 3. 数据模型

```
agent_tool (工具主表，一行 = 一个 @Tool 方法)
  ├── tenant_id + bean_name + method_name + active  ← 唯一键 uk_tenant_bean_method
  ├── agent_tool_env_param   (工具环境参数的「定义」，1:N)
  └── agent_tool_binding     (Agent 与工具的绑定 + 该 Agent 的参数「值」快照，1:N)
agent 表.tool_list            ← legacy 列，当前不再由业务写入
tool_call_log                (工具调用日志)
```

四张表的字段口径逐条见 [tool-capability.zh-CN.md](./tool-capability.zh-CN.md) 第 7 节。这里只强调三点：

- **`agent_tool` 一行 = 一个方法**，不是「一个 ToolBox」。同一个 bean 的 `getDate` / `getDatetime` 是两条记录，可以分别授权、分别配确认。
- **定义与值分离**：`agent_tool_env_param` 存代码声明的参数定义（供 UI 渲染表单），`agent_tool_binding.envBindings` 存某个 Agent 填的值。前者由代码同步覆盖，后者由用户填。
- **`agent.tool_list` 是历史遗留**：V7 把绑定关系规范化到独立表之后，业务写入路径已切到 `agent_tool_binding`。下发接口 `AgentSpecInfoResponse.toolList` 仍在，但内容是**由绑定表现场重建的 JSON**（`id` / `need_confirm` / `env_bindings`），只作为 `AgentSpecResolver` 解析环境参数的兼容入口。

## 4. 分层职责

| 模块 | 职责 | 关键类 |
|------|------|--------|
| `harnax-tools-sdk` | 工具抽象、注解、注册表、HTTP 代理、适配器接口（SPI） | `ToolBox`、`@ToolMeta`、`ToolRegistry`、`HttpProxyToolBox`、`ToolConfigAdaptor` |
| `harnax-tools-buildin` | 内置工具实现（时间、邮件） | `TimeToolBox`、`EmailToolBox` |
| `harnax-entity` | `agent_tool` / `agent_tool_binding` / `agent_tool_env_param` / `tool_call_log` 实体与 Mapper | `AgentTool`、`AgentToolMapper` |
| `harnax-admin` | 启动同步、管理 API、AgentSpec 下发 | `BuiltinToolAutoRegistrar`、`AgentToolController`、`InternalApiController` |
| `harnax-harness-core` | Agent 构建期的工具装配、权限规则、危险输入包装 | `HarnessAgentLauncher`、`DangerousInputCheckingTool` |
| `harnax-agent-service` | 拉取配置、按 toolId 解析工具配置、落调用日志 | `AgentSpecResolver`、`ToolConfigAdaptorImpl`、`ToolCallLogAdaptorImpl` |

依赖方向：`sdk` 不依赖业务；`harness-core` 只认 `sdk` 的接口，具体实现由 `agent-service` 注入；`admin` 与 `agent-service` 都依赖 `sdk` 才能扫到同一批 ToolBox——这是内置工具「注册端与执行端看到同一份代码」的前提。

## 5. 端到端链路

```
代码注解 @Tool / @ToolMeta
   ▼  admin 启动（ApplicationReadyEvent）
ToolRegistry 扫描 → BuiltinToolAutoRegistrar 收敛 → agent_tool / agent_tool_env_param
   ▼  会话/任务/渠道触发，agent-service 拉配置
InternalApiController.buildAgentSpecResponse
   = 绑定工具 ∪ 必须工具（按 id 去重） → toolDetails + toolList(legacy JSON，含环境参数值)
   ▼
AgentSpecResolver → AgentSpec.toolSpecs + ToolEnvContext
   ▼
HarnessAgentLauncher.createAgentBase()
   BUILTIN/CUSTOM → ToolBox 实例；HTTP → HttpProxyToolBox
   → addTool → 按方法粒度剔除未授权方法 → 权限规则（ALLOW / ASK）→ 危险输入包装
```

装配阶段的两条硬约束：**没有任何兜底注册**（`toolSpecs` 为空就是零工具），**取不到配置只告警跳过**（不阻断会话构建）。

## 6. 关键设计决策

### 6.1 每方法一条记录（V4）

- **决策**：`agent_tool` 从「一个 ToolBox 一行」改为「一个 `@Tool` 方法一行」，唯一键由 `uk_tenant_name` 换成 `uk_tenant_bean_method`。
- **原因**：一个 ToolBox 里往往既有读操作也有写操作。按 bean 授权意味着勾 `getDate` 就同时放出了 `sendEmail`；`needConfirm`、环境参数、超时也只能整套共享。
- **代价**：`addTool(toolBox)` 是 agentscope 的 bean 级注册，一次注册全部方法。因此装配收尾必须做一次**减法**：用 `ToolRegistry` 的方法全集减去本次授权集合，把差额 `removeTool`。这条剔除逻辑是「整箱泄露」的唯一防线，改装配代码时不能省。

### 6.2 内置工具生命周期由代码同步独占

- **决策**：`type='BUILTIN'` 记录的新增 / 更新 / 删除只有 `BuiltinToolAutoRegistrar` 一个入口，其余写路径全部封死（服务层按 `type` 拒绝、Controller 无创建接口、前端只读且请求封装已删除）。
- **原因**：数据库记录与代码注解不一致时，运行期一定以代码为准（`beanName` / `methodName` 要能反射到真实方法）。允许运营侧改内置工具，只会造出一批「库里存在、代码里跑不到」或者「字段与代码相反」的记录。
- **代价**：改注解 + 重新发布 + 重启 admin 才能调整内置工具；运营侧没有应急开关。「必须 / 非必须」同理。

### 6.3 身份键与改名语义

- **决策**：同步以 `beanName + methodName + toolName` 为身份键比对；`upsertBuiltinTool` 的 `ON DUPLICATE KEY UPDATE` 覆盖 `name`。
- **原因**：唯一键是 `(tenant_id, bean_name, method_name, active)`，改 `@Tool(name = ...)` 命中的是同一条记录，覆盖 `name` 就能原地收敛，`id` 和其上的绑定都不受影响。
- **结论**：**只有 Java 方法名或 bean 名变了才会删旧建新**（此时 `id` 变化，级联删绑定，需要在智能体配置里重新勾选）；单纯改工具名不会丢绑定。

### 6.4 必须工具不落绑定表

- **决策**：`is_required=1` 的内置工具在下发阶段由 Admin 追加进 `toolDetails`，不写 `agent_tool_binding`。
- **原因**：这类工具的语义是「所有 Agent 都要有」，落成绑定行需要给每个 Agent 插一条记录，新增工具时要回填全量 Agent；而它在配置向导里又不可取消，写进去只是一条冗余记录。
- **代价**：**没有绑定行就没有 `envBindings` 快照**，即必须工具取不到任何环境参数。所以 `isRequired` 与必填 `envParamDefs` 是矛盾组合，Admin 启动同步时会打告警。

### 6.5 去掉运行时工具兜底

- **决策**：删除「Agent 未配置工具时注册 `TOOL_SET` 全部工具」的兜底分支。
- **原因**：兜底让「未配置」和「配置为空」两种状态在运行期不可区分，也让 6.1 的方法粒度授权形同虚设——未勾选的整箱工具会从兜底路径漏出来。
- **代价**：升级前依赖兜底的 Agent 需要显式勾选工具。

### 6.6 删掉 `enable_skip`

- **决策**：`agent_tool_binding.enable_skip` 与 `ToolSpec.skipIfMissing` 一并删除（V17），工具取不到时统一「告警 + 跳过」。
- **原因**：这个开关只决定报错还是告警，不构成任何容错能力，UI 上的「缺失时跳过」被理解成运行时容忍度，是误导。
- **对照**：同样的论证在 MCP 侧也成立——`agent_mcp_binding.enable_skip` 与 `McpSpec.skipIfMissing`、`AGENT_MCP_NOT_FOUND` 错误码已一并删除（V20），配置缺失同样统一「告警 + 跳过」，不再提供「缺失即失败」这个选项。连接失败（地址不可达、鉴权不通过）仍然外抛：这个开关从来管不到它，而把异常吞掉只会让一个连不上任何工具的 Agent 静默上线，比失败更难排查。

### 6.7 绑定级 `needConfirm` 只能加严

- **决策**：运行期取 `agent_tool.needConfirm || agent_tool_binding.needConfirm`（或关系）。
- **原因**：早期设计是「实体不需要确认则强制 false」，结果是运营侧无法对某个高风险智能体临时加确认。改成或关系后，绑定层可以追加确认，但不能取消代码声明的确认——安全属性只允许单向往更严走。

### 6.8 ToolBox 会话级实例

- **决策**：`ToolRegistry.createToolBoxInstance(beanName)` 用无参构造新建实例并 `init(日志适配器, SessionMetaContext, UserIdentifier)`，而不是复用 Spring 单例。
- **原因**：`ToolBox` 持有会话与用户上下文，单例共享会造成多会话串扰。反射创建失败时才回退到单例模板。

### 6.9 环境参数：引用与快照并存

- **决策**：Agent 填值时可选「引用全局环境变量」（存 `envVarId`，下发时解析为最新解密值）或「自定义值」（存 `customValue` 快照）。
- **原因**：前者让一次改全局变量对所有引用方生效，后者允许个别 Agent 固定用自己的配置。密钥类参数（`secret=true`）加密存储、UI 脱敏回显。

## 7. 与 MCP / Skill 的一致性

| 维度 | Tool | MCP | Skill |
|------|------|-----|-------|
| 主表 | `agent_tool` | `mcp_server` | `skill`（+ `skill_repository`） |
| 绑定表 | `agent_tool_binding` | `agent_mcp_binding` | `agent_skill_binding` |
| Spec 传递 | `ToolDetailDto` → `ToolSpec` | `McpDetailDto` → `McpSpec` | `SkillDetailDto` → `SkillSpec` |
| 运行时适配器 | `ToolConfigAdaptor` | `McpConfigAdaptor` | `SkillAdaptor` |
| 元数据来源 | 代码注解同步（内置）/ DB（自定义） | DB | 远端仓库同步落库 |
| 运营可写 | 内置否、自定义是（未开放） | 是 | 是 |
| 密钥处理 | headers / env 值加密 | headers / env 值加密（下发前解密） | 无 |
| 缺失时行为 | 告警跳过（无开关） | 告警跳过（无开关，V20 起与 Tool 一致） | 缓存兜底 |
| 停用时行为 | 下发带 `status`，运行侧跳过 | 同 Tool（`status=0` 跳过） | Admin 下发时过滤，不进 spec |

## 8. 已知边界

| 边界 | 说明 |
|------|------|
| 内置工具只写 `tenant_id = 1` | 同步固定用租户 1；`MybatisTenantInterceptor` 的租户过滤逻辑当前未启用，因此内置工具是全平台共享资源，不是按租户各存一份 |
| 自定义工具链路保留但不可达 | 装配、加密、DTO 全在；无创建接口，前端不展示，只有直接写库才会产生记录 |
| HTTP 工具无 UI 入口 | `HttpProxyToolBox` 与字段齐备，但工具管理页不提供创建 |
| 删除残留需人工确认 | 熔断保护命中时（待删条数 ≥ 代码声明条数，或某个工具组同步失败）跳过删除并打 ERROR，需要人工核对代码后重新发布 |
| `name` 不参与唯一键 | `uk_tenant_bean_method` 不含 `name`，因此代码里两个方法标了同名 `@Tool(name)` 不会被数据库拦下；同步按 `name` 检索记录挂环境参数，这种重名会让参数定义落到错误的行上 |
| 租户过滤能力不齐 | `mcp_server` 列表查询已按 `tenant_id` 过滤（`mcp-management` 第 7 节第三轮），`agent` 还没有：`AgentMapper.xml` 既不映射也不插入 `agent.tenant_id`，而 `AgentServiceImpl` 会写 `agent.tenantId`、`MpSessionService` 会读它——写了不存、读了不真 |

## 9. 演进时间线

| 版本 | 变更 | 动机 |
|------|------|------|
| V1 | 建 `agent_tool`，`agent` 表加 `tool_list` JSON | 工具从硬编码 `TOOL_SET` 中解绑 |
| V2 | 建 `agent_tool_env` | 工具需要外部配置（SMTP、API Key） |
| V3 | 加 `display_name_zh`、参数 `description` | 中英文展示 |
| V4 | 表更名 `agent_tool_env_param`、加 `method_name`、唯一键换成 `uk_tenant_bean_method` | 粒度下沉到方法（6.1） |
| V5 | 加 `is_required` | 必须有「所有 Agent 都该带上」的工具 |
| V7 | 建 `agent_tool_binding` 等规范化绑定表 | 取代 JSON 列，支持绑定级参数与确认 |
| — | 注册机制接管内置工具的增 / 改 / 删 | 消灭「库与代码不一致」（6.2） |
| — | API 与前端关闭内置工具写入口 | 单一事实来源（6.2） |
| V17 | 删 `agent_tool_binding.enable_skip` | 该开关无语义（6.6） |
| V18 | `agent_tool_binding (agent_id, tool_id)` 唯一键 | 让「绑定行是该 Agent-工具对的唯一事实」成立 |
| V19-V22 | MCP 侧对齐工具口径：绑定唯一键、删 `enable_skip`、删 `agent` / `session` 上的能力残留列、`tenant_id` 过滤与 `is_public` 缺省 | 同一套判据在 MCP 上逐条复现（详见 `mcp-management` 第 7 节） |
| — | 装配去掉 `TOOL_SET` 兜底 | 方法粒度授权闭环（6.5） |
| — | `upsert` 覆盖 `name` + 删除双保险 | 改名不再丢绑定、误删有刹车（6.3） |

## 10. 关键文件索引

| 关注点 | 文件 |
|--------|------|
| 注解与描述符 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`、`ToolMetaDescriptor.kt` |
| 扫描注册 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt` |
| 启动收敛 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` |
| 写入口守卫 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt`（`requireManageableTool`） |
| SQL 与唯一键行为 | `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml`（`upsertBuiltinTool` / `deleteBuiltinByIds`） |
| 配置下发 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`（`buildAgentSpecResponse`） |
| 运行时装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` |
| 迁移脚本 | `harnax-admin/src/main/resources/db/migration/V4__refactor_tool_granularity.sql`、`V17__drop_tool_binding_enable_skip.sql`、`V18__add_tool_binding_unique_key.sql`；MCP 侧对应 `V19__add_mcp_binding_unique_key.sql`、`V20__drop_mcp_binding_enable_skip.sql`、`V21__drop_stale_capability_list_columns.sql`、`V22__mcp_public_default_and_tenant_backfill.sql`、`V23__add_mcp_server_name_unique_key.sql`（MCP 服务名租户内唯一，对应工具侧的名称唯一键） |

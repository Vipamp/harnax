# Harnax 工具能力与开发指南（中文）

本文是「工具」域的能力文档：工具从哪里来、由哪些注解与抽象描述、平台如何注册与装配、数据落在哪几张表、管理员和开发者分别能做什么。架构层面的取舍与不变量放在同域的《Harnax 工具接入设计》一份里，两份各自成文，互不依赖对方的章节编号。

## 1. 概述

### 1.1 事实来源

工具的全部对外行为由代码里的注解声明决定：一个 `@Tool` 方法就是一个工具，`@ToolMeta` 给它补上展示名、环境参数定义、确认与危险输入标记。数据库表 `agent_tool` 是这些声明的镜像，由 admin 进程启动时的一次同步写入；页面和 API 都不提供写入通路。

### 1.2 模块与依赖

| 模块 | Maven artifactId | 职责 |
| --- | --- | --- |
| `harnax-agent/harnax-tools-sdk` | `harnax-tools-sdk` | SDK：`ToolBox` 基类、注解、上下文、描述符、`ToolRegistry`、两个 SPI 接口 |
| `harnax-tools-external/harnax-tools-buildin` | `harnax-tools-buildin` | 内置工具实现，当前是时间工具与邮件工具两组 |
| `harnax-agent/harnax-harness-core` | `harnax-harness-core` | 运行时装配：`HarnessAgentLauncher`、`HarnessAgentBuilder`、`DangerousInputCheckingTool`、团队工具组 |
| `harnax-admin` | `harnax-admin` | 启动同步 `BuiltinToolAutoRegistrar`、只读管理 API、spec 下发 |
| `harnax-agent/harnax-agent-service` | `harnax-agent-service` | `ToolConfigAdaptorImpl`、`ToolInvocationAdaptorImpl`、`AgentSpecResolver` |

依赖方向（取自各模块 `pom.xml`）：

- `harnax-tools-sdk` → `harnax-common`、`harnax-entity`、`io.agentscope:agentscope`、`spring-boot-autoconfigure`、`spring-context`
- `harnax-tools-buildin` → `harnax-tools-sdk`、`io.agentscope:agentscope`、`spring-context`、`jakarta.mail-api` + `angus-mail`
- `harnax-harness-core` → `harnax-common`、`harnax-entity`、`harnax-protocol`、`harnax-agent-utils`、`harnax-tools-sdk`
- `harnax-admin` → `harnax-tools-sdk` + `harnax-tools-buildin`（POM 注释写明理由：让 `BuiltinToolAutoRegistrar` 能发现 `ToolBox` bean）
- `harnax-agent-service` → `harnax-tools-buildin`

两个进程各自扫描 `com.agnetix.harnax.tools` 包：`HarnaxAdminApplication` 的 `scanBasePackages` 是 `com.agnetix.harnax.admin` 与 `com.agnetix.harnax.tools`，`AgentServiceApplication` 的是 `com.agnetix.harnax.agent.service`、`com.agnetix.harnax.agent.skill` 与 `com.agnetix.harnax.tools`。因此 `ToolRegistry` 在两侧都是本地 bean 容器，注册表内容等于该进程 classpath 上的 `ToolBox` bean 集合——这也是新增工具模块必须同时进 admin 与 agent-service classpath 的原因。

底层框架是 `io.agentscope`（版本由根 `pom.xml` 的 `agent-scope.version` 属性给出，当前 `2.0.2`）。`@Tool`、`@ToolParam`、`Toolkit`、权限引擎都来自它，harnax 只加自己的注解与包装。

## 2. 工具分类

### 2.1 只有一类：代码内置工具

平台的工具全部由 classpath 上的 `@Tool` 方法提供。`agent_tool` 表没有类型列、没有可见性列、没有 URL 或入参 schema 列，`AgentToolService` 只有读方法。管理员能做的选择是「哪个 agent 绑哪个工具、绑定时给哪些环境变量值」，不能定义新工具。

### 2.2 必须工具与非必须工具

`@ToolMeta(isRequired = true)` 声明必须工具，落到 `agent_tool.is_required = 1`。三条行为由它派生：

- 必须工具不进 agent 配置候选集：`AgentToolMapper.selectAvailableTools` 的 SQL 带 `is_required = 0`。
- 必须工具不需要绑定行：交付时由 `InternalApiController.buildAgentSpecResponse` 的 `requiredToolIds`（来自 `selectRequiredTools()`，条件是 `is_required = 1 AND status = 1 AND active = 1`）追加进待交付 id 集合，已经绑定的不重复追加。
- 必须工具带不了环境参数：它没有绑定行，也就没有 `agent_tool_binding.env_bindings` 可写；同步在 `isRequired` 与 `required = true` 的环境参数同时出现时打一条 WARN，提示去掉其中一边。

团队的 lead 一侧 `requiredToolIds` 为空：lead 由 `team` 行配置，不挂业务工具。

### 2.3 装配期直接构造的工具组

`harnax-harness-core` 里的 `TeamLeadToolBox` / `TeamMemberToolBox` 是普通类而非 Spring bean（构造函数需要 `TeamOrchestrator`），由 `HarnessAgentLauncher` 在装配团队角色时直接构造并注册进 builder。它们不在 `ToolRegistry` 的 bean 扫描结果里，因此不出现在 `agent_tool` 表、不出现在工具页面、也不参与 agent 绑定；它们的名字由 `TeamLeadToolBox.TOOL_NAMES` 这类常量给出，并被加进权限引擎的框架 ALLOW 集合。lead 的装配还会显式关闭 meta tool、文件系统工具与 shell 工具。

## 3. SDK 核心概念（harnax-tools-sdk）

### 3.1 ToolBox 抽象基类

`ToolBox`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`）是工具组的基类，成员只有一个：

- `abstract fun name(): String`：组名，用作 `ToolMetaDescriptor.toolName`。

基类不携带状态：没有会话上下文，也没有留给装配侧的初始化缝隙。一次调用的计时、来源判定与落库都由 `harnax-harness-core` 的 `ToolInvocationMiddleware` 在 acting 步骤外围完成（见「调用指标」），它看得见模型能调的每一个工具——MCP 服务的工具与 shell 命令也算在内，两者都不是 `ToolBox`。需要以终端用户身份行动的工具，把那个值作为自己的参数取。

### 3.2 注解体系

| 注解 | 定义位置 | 作用目标 | 提供什么 |
| --- | --- | --- | --- |
| `@Tool` | `io.agentscope.core.tool.Tool`（agentscope 2.0.2） | 方法（及注解类型） | `name`（留空则用方法名）、`description`（发给模型）、`readOnly`、`strict`、`concurrencySafe`（默认 true）、`externalTool`、`stateInjected`、`dangerousFiles`、`dangerousDirectories`、`converter` |
| `@ToolParam` | `io.agentscope.core.tool.ToolParam` | 参数（及字段、注解类型） | `name`（无默认值，必须写）、`description`、`required`（默认 true） |
| `@ToolMeta` | `com.agnetix.harnax.tools.sdk.ToolMeta` | 方法（`AnnotationTarget.FUNCTION`） | `displayName`、`displayNameZh`、`envParamDefs`、`needConfirm`、`dangerousInput`、`isRequired` |
| `@ToolEnvParamDef` | `com.agnetix.harnax.tools.sdk.ToolEnvParamDef` | 注解类型（只作 `envParamDefs` 的元素） | `key`、`description`、`required`（默认 true）、`secret`、`defaultValue` |

两条硬约束直接决定工具能不能被模型看到：

1. **参数必须带 `@ToolParam` 才会进 JSON Schema。** agentscope 的 `ToolSchemaGenerator` 只遍历带 `@ToolParam` 的参数；`ToolMethodInvoker` 把不带该注解的非基本类型参数当作待注入对象，从 `ToolExecutionContext` 里按类型解析。`ToolEnvContext` 参数因此正好是「自动注入、不暴露给模型」。
2. **`@ToolMeta` 只能作用于方法。** 它的 `@Target` 是 `AnnotationTarget.FUNCTION`，写在类上不会被读到：`ToolRegistry.extractToolMeta` 用 `method.getAnnotation(ToolMeta::class.java)` 逐方法取注解，取不到时 `displayName` / `displayNameZh` 落空串、`needConfirm` 落 false、`envParamDescriptors` 落空列表、`isRequired` 落 false。

字段来源分工：`name` / `description` / `readOnly` 只认 `@Tool`；`needConfirm` 只认 `@ToolMeta`（`ToolRegistry` 注释写明 "only from @ToolMeta.needConfirm"，`@Tool` 上没有对应属性）；展示名与环境参数定义只认 `@ToolMeta`。

### 3.3 @ToolMeta 属性说明

| 属性 | 默认 | 落库列 | 运行时效果 |
| --- | --- | --- | --- |
| `displayName` | `""` | `agent_tool.display_name`，留空时同步写 `@Tool.name` | 管理页与 agent 配置面板的英文名 |
| `displayNameZh` | `""` | `agent_tool.display_name_zh`，留空时写 NULL | 中文 locale 下的展示名 |
| `envParamDefs` | `[]` | `agent_tool_env_param` 若干行；`required = true` 的 key 汇总成 `agent_tool.required_env_param_keys`（JSON 数组字符串） | 决定配置面板要填哪些参数、哪些必填、哪些按密文掩码 |
| `needConfirm` | `false` | `agent_tool.need_confirm` | 装配时生成该工具的 ASK 规则 |
| `dangerousInput` | `false` | 不落库 | 装配时用 `DangerousInputCheckingTool` 包装该方法对应的工具 |
| `isRequired` | `false` | `agent_tool.is_required` | 见「必须工具与非必须工具」 |

`dangerousInput` 不落库，原因是它的消费方在进程内：`HarnessAgentLauncher.collectDangerousInputTools` 直接反射 ToolBox 类的方法注解，不查表。

### 3.4 环境参数体系

`ToolEnvContext(bindings: Map<String, String>)` 是工具方法看到的唯一环境变量入口：

- `get(key)` 返回 null 表示未配置。
- `require(key)` 在缺失或空白时抛 `IllegalArgumentException("Environment parameter '<key>' is required but not configured")`。
- `bindings` 是一个 agent 的扁平合并表：该 agent 所有工具绑定与所有 MCP 绑定解析出的 envKey/envValue 都进同一个 map，按 key 回答，不区分是谁声明的。合并顺序是先工具后 MCP，同名 key 后写入者生效。
- 这个 map 整体交给该 agent 的每一个工具，所以一个工具能读到别的绑定声明的同名 key。
- 即使 map 为空也会注册进 `ToolExecutionContext`：否则带 `ToolEnvContext` 参数的方法会在注入阶段失败，`require()` 也就无法给出「未配置」这条可读错误。
- 环境参数自带的 `defaultValue` 不会在运行时兜底：交付只带绑定值，`saveToolBindings` 的必填校验对内置工具传的 `defaultValueCounts = false`。

### 3.5 ToolRegistry 注册中心

`com.agnetix.harnax.tools.sdk.registry.ToolRegistry` 是 `@Component`，`@PostConstruct` 里做两件事：

1. `applicationContext.getBeansOfType(ToolBox::class.java)` 收集全部 ToolBox bean，按 Spring bean 名存进 `registry`。
2. 对每个 bean 反射 `clazz.methods`，把带 `@Tool` 的方法折成 `ToolMethodDescriptor`，汇总为 `ToolMetaDescriptor(beanName, toolName = toolBox.name(), methods)` 存进 `metaRegistry`。一个 `@Tool` 方法都没有的 bean 记 INFO 后跳过，不进 `metaRegistry`（但仍在 `registry` 里，`getToolBox` 拿得到）。

对外方法：`getToolBox(beanName)`、`getAllToolBoxes()`、`getToolBoxNames()`、`contains(beanName)`、`getToolMeta(beanName)`、`getAllToolMeta()`，以及 `createToolBoxInstance(beanName)`。

`createToolBoxInstance` 用 `template::class.java.getDeclaredConstructor().newInstance()` 造新实例，让每个会话持有自己的 ToolBox；实例化失败时记 ERROR 并退回单例。由此要求 ToolBox 保留一个可无参构造的构造函数，且不要把会话态放进字段。

### 3.6 适配器接口（SPI）

| 接口 | 定义 | harnax 实现 | 作用 |
| --- | --- | --- | --- |
| `ToolInvocationAdaptor`（`fun interface`，`emit(ToolInvocationEvent)`） | tools-sdk `adaptor` 包 | `harnax-agent-service` 的 `ToolInvocationAdaptorImpl`，带界队列 + 批量落 `tool_invocation_log` | 把一次调用记成指标行 |
| `ToolConfigAdaptor`（`getToolConfig(toolId): AgentTool?`） | tools-sdk `adaptor` 包 | `harnax-agent-service` 的 `ToolConfigAdaptorImpl` | 按 toolId 取工具配置：先查 `AgentSpecContextHolder` 里已下发的 `toolDetails`，命中则 `ToolDetailDto` 转实体；否则回落到 `AgentToolMapper.selectById`。`toolId <= 0` 直接返回 null |

harness 侧的 `ToolInvocationAdaptor` 由 `HarnessAutoConfiguration` 经 `ObjectProvider` 注入（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt:332` 是形参，`:368` 是 `toolInvocationAdaptorProvider.ifAvailable?.takeIf { invocationMetricsEnabled }`），`harness.metrics.invocation.enabled=false` 把它折成 null；null 即不装中间件——这是那枚开关的唯一落点（挂载判据见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:647`，同一文件 `:140` 的 KDoc 记的就是这条不变量）。纯嵌入式跑 harness 时没有适配器，调用指标自然不记。

### 3.7 描述符与其他数据类

- `ToolMetaDescriptor(beanName, toolName, methods)`：一个 ToolBox 的全部声明，同步的输入。
- `ToolMethodDescriptor(methodName, toolName, displayName, displayNameZh, description, readOnly, needConfirm, envParamDescriptors, isRequired)`：一个 `@Tool` 方法，等于一行 `agent_tool`。
- `ToolEnvParamDescriptor(key, description, required, secret, defaultValue)`：一个环境参数定义。
- `UserIdentifier(userId: Long? = null)`：空标记接口 `ToolCallContext` 的唯一实现，`ToolCallContext` 不声明任何成员。一次调用的会话 / 智能体 / 租户归属由 `ToolInvocationMiddleware` 的构造参数带进来（`tenantId`、`agentId`、`sessionId`、`userId`），不从工具侧取。
- `ToolSpec(toolId, toolName = "", needConfirm = false)`：装配输入。`toolName` 是 `kind = builtin` 的判定名单来源——装配把它收进 `builtinToolNames` 交给 `ToolInvocationMiddleware`（`HarnessAgentLauncher.kt:657`）；确认位由 `needConfirm` 承载。

## 4. 内置工具现状（harnax-tools-buildin）

模块目录是 `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`，文件里声明的包名是 `com.agnetix.harnax.tools.builtin`：目录名与包名不同，按包名 import。

当前 classpath 上的 `@Tool` 方法共 3 个，来自两组 ToolBox：

| bean 名 | 组名 `name()` | 工具名 `@Tool.name` | 描述 | readOnly | needConfirm | 环境参数 |
| --- | --- | --- | --- | --- | --- | --- |
| `time-tool-box` | `time-tool-box` | `getDate` | 获取当前日期 | 是 | 否 | 无 |
| `time-tool-box` | `time-tool-box` | `getDatetime` | 获取当前时间 | 是 | 否 | 无 |
| `email-tool-box` | `email-tool-box` | `sendEmail` | Send an email via SMTP. Supports plain text and HTML body. | 否 | 是 | `SMTP_HOST`、`SMTP_PORT`（可选，默认 587）、`SMTP_USER`、`SMTP_PASSWORD`（secret）、`SMTP_FROM` |

补充行为：

- `getDate` / `getDatetime` 用 `SimpleDateFormat("yyyy-MM-dd")` 与 `"yyyy-MM-dd HH:mm:ss"`，走 JVM 默认时区，无入参。
- `sendEmail` 的四个模型可见参数都带 `@ToolParam`：`to`、`subject`、`body`（三者 required）、`is_html`（`required = false`，类型 `Boolean?`）；第五个参数 `envContext: ToolEnvContext` 不带注解，由框架注入。方法体开头用 `require` 再校验 `to` / `subject` / `body` 非空白，因为模型可以传 null。
- `sendEmail` 直连 Jakarta Mail：端口取自 `SMTP_PORT`，绑定缺值时按代码里的 587 兜底；465 走隐式 SSL，25 明文，其余强制 STARTTLS（`starttls.required = true`）；连接与读超时各 10 秒；正文类型按 `isHtml ?: false` 决定，即 `is_html` 为 null 时发 `text/plain`，为 true 时发 `text/html`；主题与正文均按 UTF-8。`is_html` 的 `@ToolParam` 描述写的是 `Whether the body is HTML format (default: true)`，与同一方法体的 `isHtml ?: false` 不一致：描述只是喂给模型的文本，默认行为以 `?:` 兜底表达式为准。
- `harnax-tools-buildin` 的 `pom.xml` 因此带 `jakarta.mail-api` 与 `angus-mail` 两个依赖。

## 5. 注册机制：一次启动同步

### 5.1 触发点

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` 是 `@Component`，同步方法 `syncBuiltinTools()` 挂在 `@EventListener(ApplicationReadyEvent::class)`。`getAllToolMeta()` 为空时整次同步跳过并记 INFO，`declaredNames` 保持原值。

### 5.2 收敛规则

身份是 `@Tool.name`，落库前的第一步是 `requireUniqueNames(declared)`：同一个名字被两个 `@Tool` 方法声明时抛 `IllegalStateException`，消息里逐个列出 `bean::method`，此时一行都未写。

之后按 ToolBox 分组处理，每组先 `saveTool` 再同步该组的环境参数定义：

- 表里没有这个名字：`insert`。SQL 里 `status` / `active` 写字面量 1，`creator` 写 `'SYSTEM'`。
- 表里有这个名字：把代码拥有的列逐个比较（`display_name`、`display_name_zh`、`description`、`bean_name`、`method_name`、`read_only`、`need_confirm`、`is_required`、`required_env_param_keys`、`status`、`active`），有差异才 `updateById`，并把差异列名写进 INFO 日志；`id`、`create_time`、`creator` 保留原行的值。`name` 不参与比较，也不在 UPDATE 的 SET 列表里。
- 表里有、代码没声明：原样留着，不删。

查找用的 `AgentToolMapper.selectByName` 不带 `active` 条件，所以 `active = 0` 的行仍然占着名字，同步会把它更新回 `active = 1`，而不是撞 `uk_agent_tool_name`。

单个 ToolBox 组保存失败只记 ERROR 并计入 `failCount`，其他组继续。

### 5.3 环境参数定义的同步

`syncToolEnvParams` 按同一次 `saveTool` 解析出的行 id 操作 `agent_tool_env_param`：同名参数比对 `description` / `required` / `secret` / `default_value`，有变化才 UPDATE；新 key INSERT；当前声明集合里没有的 key DELETE（删参数定义属于更新工具，删工具是另一件事）。同组里没保存成功的工具跳过并记 WARN。

### 5.4 声明集与交付过滤

同步末尾把 `declaredNames` 置为「本次声明的名字集合」——注意是声明的，不是写入成功的，否则一次写库失败会让这些工具在所有 agent 上消失。

`InternalApiController` 交付时用它过滤：`agentToolMapper.selectByIds(ids).filter { declaredNames.isEmpty() || it.name in declaredNames }`。表里可能留着方法已不在 classpath 上的行，交给运行时装配会失败，所以留表不见；`declaredNames` 为空等于「注册表不知情」，此时不过滤而不是屏蔽全部工具。被过滤掉的 id 记一条 WARN。

### 5.5 排障锚点

日志按前缀 `[BuiltinToolAutoRegistrar]` 检索，关键行依次是：`Syncing N builtin tool group(s), M declared tool(s)`、`Registered new tool '<name>' (<bean>::<method>)`、`Updated tool '<name>' (id=..): <列名列表>`、`Synced env params for tool '<bean>::<method>': N total, M stale removed`、`Synced tool group: <bean> [N methods]`、最后一行 `Sync complete: N succeeded, M failed; K tool name(s) declared`。`ToolRegistry` 侧对应 `[ToolRegistry] Registered ToolBox bean: ...` 与 `Total N ToolBox beans registered, M with @Tool methods`。

## 6. Agent 运行时工具装配

### 6.1 交付：admin 侧

`InternalApiController.buildAgentSpecResponse` 拿到 `agent_tool_binding` 行与 `selectRequiredTools()` 的必须工具 id，合成去重后的待交付 id 集合，按「声明集与交付过滤」一节的规则过滤后产出两份数据：

- `toolDetails`：`ToolDetailDto` 列表，带 `name` / `beanName` / `methodName` / `readOnly` / `needConfirm` / `requiredEnvParamKeys` / `status`，以及这条绑定自己的 `bindingNeedConfirm`。
- `toolList`：JSON 字符串，每项含 `id`、`need_confirm`（来自绑定行）与 `env_bindings`——后者由 `resolveEnvBindingsJson` 现解：引用 `envVarId` 时优先取 `envVariableService.getDecryptedValue(envVarId, tenantId)` 的最新值，取不到时退回行里存的快照 `envValue`；引用解析不出值且无快照时什么都不交付（工具侧看到「未配置」）；否则用 `customValue`，最后才是快照。

### 6.2 解析：agent-service 侧

`AgentSpecResolver` 把 `toolDetails` 折成 `ToolSpec(toolId, toolName = tool.name, needConfirm = bindingNeedConfirm)` 列表，把 `toolList` 与 `mcpList` 里的 `env_bindings` 合并成一个扁平 map，作为 `ToolEnvContext` 注册进 `AgentSpec.contextForTools`。

### 6.3 装配：harness-core 侧

`HarnessAgentLauncher.createAgentBase` 的工具段按顺序做这些事：

1. 非 lead 且 `agentSpec.toolSpecs` 非空且 `toolConfigAdaptor` 存在才进入装配；只有 `ToolConfigAdaptor` 缺失时会有那条 WARN，工具整体不装。
2. 逐个 `toolSpec` 用 `toolConfigAdaptor.getToolConfig(toolSpec.toolId)` 取配置；`status == 0` 记 INFO 跳过。
3. 按 `beanName` 去重：一个 ToolBox 只实例化一次。`toolRegistry.createToolBoxInstance(beanName)` 造会话级实例，随后 `agentBuilder.addTool(toolBox)`。实例不带会话态：一次调用的会话 / 智能体 / 租户归属由 `ToolInvocationMiddleware` 的构造参数携带（见「调用指标」）。
4. `Toolkit.registerTool` 是整组注册的，所以随后做一次收敛：把该 bean 在 `getToolMeta(bean).methods` 里、但未被本 agent 授予的工具名逐个 `agentBuilder.removeTool(name)` 撤下。未授予的来源有两个——同组里未选中的兄弟方法，和被停用的方法。
5. 确认位取并集：`toolConfig.needConfirm == 1 || toolSpec.needConfirm`。绑定级只能加严。命中的工具名进 `needConfirmedTools`。
6. 每个 ToolBox 类只反射扫描一次 `@ToolMeta(dangerousInput = true)`，命中名进 `dangerousInputTools`。
7. `contextForTools` 注册进 `ToolExecutionContext`（见「环境参数体系」）。
8. 团队工具在收敛扫描之后注册，因此不会被第 4 步撤下。
9. 权限上下文：一组框架工具名（`plan_enter`、`plan_write`、`plan_exit`、`todo_write`、`agent_spawn`、`agent_send`、`agent_list`、`task_output`、`task_list`）加上团队工具名进 ALLOW；`needConfirmedTools` 进 ASK，规则来源标 `harnax`。
10. 危险输入包装：`dangerousInputTools` 逐个 `agentBuilder.wrapWithDangerousInputCheck(toolName)`；已有 ASK 规则的工具跳过包装（ASK 在 `checkPermissions` 之前命中，扫描冗余）。

### 6.4 权限模式

模式字符串由 `PermissionMode.fromString` 解析，取值 `DEFAULT`、`ACCEPT_EDITS`、`EXPLORE`、`BYPASS`、`DONT_ASK`：DEFAULT 下所有操作都需要显式 ALLOW 规则；ACCEPT_EDITS 自动放行工作目录内编辑；EXPLORE 是只读模式，写类工具被拒；BYPASS 跳过规则评估；DONT_ASK 把 ASK 降级为 DENY，供无人值守运行。`@Tool(readOnly = true)` 的工具在 EXPLORE 与 ACCEPT_EDITS 下自动放行。

### 6.5 危险输入拦截

`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt` 是一个 `ToolBase` 装饰器，包住原工具（通常是 `ReflectiveFunctionTool`），name / description / inputSchema 复制自被包对象，`concurrencySafe(true)`，`readOnly` 从被包对象读取；`callAsync` 直接委托。

`checkPermissions` 遍历入参里的字符串值（长度阈值 3）：

- 先对 `ToolDangerousPathConstants.DANGEROUS_COMMANDS` 做小写子串匹配，命中即 ASK，消息带命中的片段与参数名。
- 再用 `ToolBase.isDangerousPath` 检查路径，命中即 ASK；该方法对危险文件名与危险目录段做匹配，并解析符号链接以防绕。
- 都没命中返回 `PermissionDecision.passthrough(name)`，交给引擎的规则表与模式默认。

两条 ASK 的 `decisionReason` 都以 `safety:` 开头，按 PermissionEngine 的约定属于 bypass-immune：BYPASS 模式也跳不过。

### 6.6 执行超时

超时是装配侧属性，不是工具属性。`HarnessAgentWrapper` 的构造参数 `turnTimeoutSeconds`（默认 300）在整轮调用上施加 `.timeout(Duration.ofSeconds(...))`，非正值表示不加超时。取值来自 `harness.turn-timeout-seconds`（`harnax-agent/harnax-agent-service/src/main/resources/application.yml`，默认 300）；团队轮次走 `turnBudget(teamRole)`，改用 `harness.team.turn-timeout-seconds`（默认 1800），并在 team 预算不大于成员轮次预算时记 WARN。单个工具没有独立超时预算，慢工具由整轮预算兜住。

### 6.7 调用指标

一次调用由 `harnax-harness-core` 的 `ToolInvocationMiddleware`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt`）记录——它在 `onActing` 记下起点，在 `TOOL_RESULT_END` 收尾时组一行 `ToolInvocationEvent`（来源 `kind`、`tool_name`、终态 `outcome`、起止毫秒、截断后的入参与结果），交 `ToolInvocationAdaptor`；正文两列可由 `capture-payload` 整体关闭，队列满则丢弃并计数。中间件每次装配新建一个实例，挂载点是 `HarnessAgentLauncher.kt:647-662`，外层判据只有 `if (toolInvocationAdaptor != null)`：归属（租户 / 智能体 / 会话 / 用户）与三份判定输入（`mcpIdsByTool`、`cliIdsByCommand`、`builtinToolNames`）都装进它的构造参数，所以它是运行时的私有状态而不是共享单例。

`kind` 取五个值 `builtin` / `mcp` / `cli` / `shell` / `framework`，判定顺序是契约（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationClassifier.kt:40-63`）：MCP 注册表命中 → shell 工具名（`execute` / `execute_shell_command`，命中后再分「命令是已下发 CLI 包」→ `cli`，否则 `shell`）→ 在下发工具名单里 → `builtin` → 三者都不是才 `framework`。

`outcome` 只取四个终态 `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`：非终态不产生事件，结果流提前结束（异常或取消）时还开着的起点一律记成 `INTERRUPTED`。

写入不占用回合：`ToolInvocationAdaptorImpl` 把事件放进带界队列（`harness.metrics.invocation.queue-capacity` 默认 512），每 `flush-interval-ms`（默认 200 毫秒）一趟、每批 `batch-size`（默认 64）行批量落库；队列满或写线程已停即丢弃并计数。入参与结果正文在写入侧按 `harness.metrics.invocation.capture-max-chars`（默认 2000）截断，`harness.metrics.invocation.capture-payload=false` 时 `args_json` 与 `result_excerpt` 两列留 NULL；总开关 `harness.metrics.invocation.enabled` 的落点见「适配器接口（SPI）」。

折算与清理在 admin 侧：`ToolInvocationRollupService` 每小时第 5 分钟（`@Scheduled(cron = "0 5 * * * ?")`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt` 的 `rollUpHourly`）把「聚合表里还没有的那些小时」逐小时 `upsertHour` 进 `tool_invocation_stats`，每次再带上当前小时与上一小时，然后 `deleteRolledOut` 释放保留窗口之外且该小时已折算的行。窗口是 `harnax.metrics.retention-days`（默认 90 天），构造期夹进合法区间；`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml` 的 `deleteRolledOut` 里 `tenant_id IS NULL` 的行只按窗口释放。

## 7. 数据模型

列集合与索引以 admin 的 `harnax-admin/src/main/resources/db/migration/` 为准：`V1__init_schema.sql` 是全部旧表的建表与列定义，其上另叠前向增量，新建的库按版本号依次重放到同一个形状。`agent_tool` 的形态是平台作用域、按名字标识（`uk_agent_tool_name`）、只增不删；调用指标的两张表写在前向增量 `V3__tool_invocation_metrics.sql` 里——明细表 `tool_invocation_log`（`:9`）与小时聚合 `tool_invocation_stats`（`:35`），各自的索引名写在自己的建表语句里，明细表那条租户组合索引叫 `idx_tool_invocation_log_tenant_ts`；聚合表的列名与两条键再由 `V5__tool_invocation_stats_hourly.sql` 升到小时一档（`stat_hour`、`uk_tool_invocation_stats_hour`、`idx_tool_invocation_stats_tenant_hour`）。

### 7.1 agent_tool

工具主表，一行 = 一个 `@Tool` 方法。

| 列 | 类型 | 写入方 | 含义 |
| --- | --- | --- | --- |
| `id` | bigint PK AUTO_INCREMENT | 数据库 | 绑定与交付都用它寻址 |
| `name` | varchar(100) NOT NULL，`UNIQUE uk_agent_tool_name` | 同步 | 工具身份，即 `@Tool.name` |
| `display_name` / `display_name_zh` | varchar(200) | 同步 | 展示名；英文留空时写 name，中文留空时写 NULL |
| `description` | text | 同步 | 发给模型的说明，取 `@Tool.description` |
| `bean_name` / `method_name` | varchar(200) / varchar(100) | 同步 | 实例化用；属性，不是身份 |
| `read_only` | tinyint(1) | 同步 | 来自 `@Tool.readOnly` |
| `need_confirm` | tinyint(1) | 同步 | 来自 `@ToolMeta.needConfirm` |
| `is_required` | tinyint(1) NOT NULL DEFAULT 0 | 同步 | 来自 `@ToolMeta.isRequired` |
| `required_env_param_keys` | varchar(1000) | 同步 | `required = true` 的环境参数 key 的 JSON 数组，如 `["SMTP_HOST","SMTP_USER"]` |
| `status` / `active` | tinyint(1) | 同步 | 恒为 1：同步是唯一写入方，且从不删行 |
| `creator` | varchar(100) | 同步 | 固定 `SYSTEM` |
| `create_time` / `update_time` | datetime | 数据库 | 更新时只有 `update_time` 变 |

表上没有租户列、没有类型列、没有 HTTP 列、没有 schema 列、没有超时列。

### 7.2 agent_tool_binding

| 列 | 含义 |
| --- | --- |
| `agent_id`, `tool_id` | 组合唯一键 `uk_agent_tool_binding_agent_id_tool_id`，一个 agent 对一个工具最多一行 |
| `need_confirm` | 绑定级确认，运行时与 `agent_tool.need_confirm` 取或 |
| `env_bindings` | JSON 数组快照，元素含 `envKey`、`envValue`、`envVarId`、`envVarName`、`customValue` |
| `create_time` / `update_time` | 保存时写 |

保存由 `AgentServiceImpl.saveToolBindings` 完成，策略是「按 agent 整组删除后重插」，插入前先 `distinctBy { toolId }`。解析不出的工具 id 抛 `BizException("Tool is missing or deleted: ...")`；必填环境参数未填会被 `assertRequiredEnvParamsFilled` 拒绝，且内置工具的声明默认值不计入已填。绑定行上没有「缺失时跳过」开关，交付按名字过滤这一行为由声明集决定。

### 7.3 agent_tool_env_param

工具环境参数定义表：`tool_id` 指向 `agent_tool.id`，`(tool_id, env_param_name)` 唯一，另有 `description`、`required`、`secret`、`default_value`。它描述「这个工具要什么」，具体值在绑定行里。

### 7.4 tool_invocation_log 与 tool_invocation_stats

明细表一次调用一行，保留 `harnax.metrics.retention-days` 天：`tenant_id`（可空，spec 未命名归属即 NULL）、`agent_id`（可空，团队主管无 `agent` 行）、`session_id`、`user_id`、`kind`、`tool_name`、`mcp_id` / `cli_id`（只在对应来源上填）、`outcome`、`error_message`、`args_json` / `result_excerpt`（正文可整体关闭）、`duration_ms`、`start_time` / `end_time` / `ts`（三者都是 `datetime(3)`）。索引按「租户 + 时间」「租户 + 来源 + 时间」「mcp_id + 时间」「cli_id + 时间」「session」「tool_name」六条铺。

小时聚合永久保留，唯一键 `(stat_hour, tenant_id, kind, subject_id, tool_name)`：`stat_hour` 是 `datetime`，一行代表一个自然小时；`subject_id` 在 `kind=mcp` 时是 MCP 服务行、`kind=cli` 时是 CLI 包行、其余为 `0`（不能留 NULL，唯一索引不把 NULL 视为相等，NULL 会让同一个小时插进两行）；计数列 `calls` / 四终态 / `sum_duration_ms` / `max_duration_ms`；六个**半开区间**的耗时桶（`le_100ms`、`le_500ms` = `(100,500]`、`le_2s`、`le_10s`、`le_30s`、`gt_30s`），闭右开左是为了让「正好 500ms」只被一个桶认领，桶和与 `calls` 才恒等。只存小时一档，日 / 周 / 月都是读侧求和。

一条不变量决定了两张表的读法：`tenant_id` 在聚合表上是 `NOT NULL`，所以无归属的明细行不进任何聚合，它们只看保留窗口。按工具 / MCP / CLI 统计读小时聚合（可答超过保留期），按智能体 / 会话统计读明细（受保留窗口限制）。

### 7.5 与 env_variable 的关系

`agent_tool_binding.env_bindings` 的元素可以引用 `env_variable` 行（`envVarId` + `envVarName` 快照），也可以自带 `customValue`，两者互斥。引用型在交付时按当前租户取最新解密值，取不到退回快照。

## 8. 管理 API 与页面

### 8.1 只读 API

`/api/admin/tools`（`AgentToolController`）只有 GET：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/admin/tools/page` | 分页查工具，参数 `pageNum`、`pageSize`（服务端限制到 1..1000）、`keyword`（匹配 name / display_name / description）、`status`；排序 `status DESC, update_time DESC` |
| GET | `/api/admin/tools/{id}` | 单条详情，取不到返回 `error.tool.notfound` |
| GET | `/api/admin/tools/available` | 可绑定候选：`status = 1 AND active = 1 AND is_required = 0`，按 name 排序 |
| GET | `/api/admin/tools/builtin` | 全部 code-owned 工具：`active = 1`，按 name 排序，不带 status 条件 |
| GET | `/api/admin/tools/{id}/required-env-params` | 该工具的必填环境参数 key 列表 |

响应体是 `AgentToolResponse`，含 `envParams`（`ToolEnvParamEntry` 列表）与解析后的 `requiredEnvParamKeys`。没有 POST/PUT/DELETE：工具的增删改只在代码里发生。

### 8.2 工具配置页面

`harnax-webui/src/pages/tool/index.tsx` 是一张只读表格：数据来自 `getBuiltinTools()`，列是名称（按 locale 取 `displayNameZh` → `displayName` → `name`，展示名与 `name` 不同时附一行等宽 `name`）、描述、环境参数（`EnvParamsPopover`，兜底计数取 `requiredEnvParamKeys.length`）、需要确认（`needConfirm === 1` 显示橙色 Tag）、必须（`isRequired === 1` 显示红色 Tag）。搜索在前端做，命中范围是 name 与三个展示字段；不分页。`harnax-webui/src/pages/tool/components/ToolEnvEntriesEditor.tsx` 提供环境变量编辑，供 agent 侧配置复用。

### 8.3 agent 侧配置

`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx` 是工具真正被选择与配置的地方：每项可切 `needConfirm` 开关（写进绑定行），并通过环境变量编辑器产出 `envBindings`。服务层入口在 `harnax-webui/src/services/ant-design-pro/tool.ts`：`getAvailableTools()` 与 `getBuiltinTools()`。

### 8.4 调用指标 API 与页面

读侧是 `ToolMetricsController`（基路径 `/api/admin/tool-metrics` 声明在类上）三个只读 GET：`getSummary`（`/summary`）、`getTimeSeries`（`/time-series`）、`getInvocations`（`/invocations`）。三个端点都没有租户形参，租户取自调用方自己的令牌；窗口都由 `start` / `end` 两个形参给，取值到小时（`yyyy-MM-dd HH:mm`，也接受整日的 `yyyy-MM-dd` 并按当天零时取），两端都含；缺省 `end` 是当前小时、`start` 是它之前第 720 小时，上限 8760 小时（超出保留 `end`、把 `start` 往前推）。`summary` 的 `groupBy` 取 `tool`（默认）/ `mcp` / `cli` / `agent` / `session`，前三档读 `tool_invocation_stats`，后两档读 `tool_invocation_log`，因此只有后两档受保留窗口限制。`time-series` 的 `granularity` 取 `auto`（默认）/ `hour` / `day` / `week` / `month`，`auto` 按跨度选桶——48 小时内按小时、92 天内按天、更长按周；空档补零，安静的一小时不会让折线跳格。

页面是 `harnax-webui`「监控与治理」分组下的「调用监控」，路由 `/monitor/call-metrics`（`harnax-webui/config/routes.ts:143`，页面 `harnax-webui/src/pages/call-metrics/index.tsx`）：三个 tab（工具 / MCP / CLI）共用一套形状，各自给出本 tab 的分组档位——工具 tab 是工具 / 智能体 / 会话，MCP tab 是 MCP / 智能体 / 会话，CLI tab 是 CLI / 智能体 / 会话（`harnax-webui/src/pages/call-metrics/dimensions.ts`）；表格首列按当前档位实名（工具 / MCP / CLI / 智能体 / 会话），不再笼统写作「主体」。`shell` 与 `framework` 在工具 tab 里按 `kind` 可达，单次调用明细在抽屉里给出终态、耗时、失败原因、入参与结果摘要。

## 9. 新工具开发：一步一步

### 步骤 1：确定承载模块

在 `harnax-tools-external/` 下新建兄弟模块（内置工具是 `harnax-tools-buildin`），POM 至少依赖 `harnax-tools-sdk`、`io.agentscope:agentscope`（版本走 `agent-scope.version`）与 `spring-context`。新模块要同时加进 `harnax-admin/pom.xml` 与 `harnax-agent/harnax-agent-service/pom.xml`：前者决定工具能否注册进表，后者决定运行时能否实例化。两边都通过 `com.agnetix.harnax.tools` 包扫描发现 bean，Spring bean 名默认取类名首字母小写，需要稳定 bean 名时写 `@Component("xxx-tool-box")`。

### 步骤 2：编写 ToolBox 类

```kotlin
@Component("order-tool-box")
class OrderToolBox : ToolBox() {

    @Tool(name = "queryOrder", description = "按订单号查询订单状态", readOnly = true)
    @ToolMeta(
        displayName = "Query Order",
        displayNameZh = "查询订单",
        needConfirm = false,
    )
    fun queryOrder(
        @ToolParam(name = "order_no", description = "订单号") orderNo: String?,
    ): String {
        require(!orderNo.isNullOrBlank()) { "Parameter 'order_no' is required" }
        return "order $orderNo: PAID"
    }

    override fun name(): String = NAME

    companion object {
        const val NAME = "order-tool-box"
    }
}
```

要点：继承 `ToolBox` 并实现 `name()`；保留无参构造（会话级实例靠 `getDeclaredConstructor()`）；方法体是普通代码，工具侧没有记录钩子——调用指标由装配侧挂上的 `ToolInvocationMiddleware` 记录（见「调用指标」）；一个方法一个工具名，名字全局唯一。

### 步骤 3：把参数暴露给模型

每个模型可见参数都要 `@ToolParam(name = ...)`，`name` 没有默认值，必须写；可选参数写 `required = false`。类型用基本类型与 String；需要框架注入的对象（如 `ToolEnvContext`）不要加 `@ToolParam`。Kotlin 参数用可空类型 + 方法体内 `require` 是内置工具的现行写法，因为模型可以省略任何参数、传 null。`description` 只进 schema 文本、不参与求值：缺省行为由方法体的兜底表达式给出，描述里的默认值与 `?:` 后面的值不一致时，运行的是后者。

### 步骤 4：环境参数

需要配置项时在 `@ToolMeta(envParamDefs = [...])` 里逐个 `ToolEnvParamDef(key = ..., description = ..., required = ..., secret = ..., defaultValue = ...)`，方法签名加一个不带 `@ToolParam` 的 `envContext: ToolEnvContext`，读值用 `envContext.require("KEY")`（缺失即抛）或 `envContext.get("KEY") ?: fallback`。

注意三点：`required` 只影响配置页标注与保存校验，运行时判据是 `require()`；同名 key 在整个 agent 内共享，工具读到的是别人也可能声明过的值；`defaultValue` 不会在运行时兜底，别依赖它。

### 步骤 5：确认与危险输入

`needConfirm = true` 让该工具每次调用都要用户确认。`dangerousInput = true` 让装配侧给它套 `DangerousInputCheckingTool`，扫描字符串入参里的危险命令片段与危险路径，命中即产生 bypass-immune 的 ASK。两者同时声明时，装配跳过包装（ASK 规则先生效）。字符串参数可能承载命令或路径的工具应该声明 `dangerousInput`；纯只读、无自由文本入参的工具不需要。

### 步骤 6：单元测试

`harnax-tools-buildin` 下每个 ToolBox 都有对应测试（`TimeToolBoxTest`、`EmailToolBoxTest`、`EmailToolBoxIntegrationTest`）。SDK 侧的 `ToolRegistryTest` 覆盖 bean 扫描与描述符提取，可对照写注册断言：给定一个 ToolBox 类，每个 `@Tool` 方法产出一条 `ToolMethodDescriptor`。

### 步骤 7：启动即注册

不需要手工插表、不需要页面操作、不需要 SQL 脚本：admin 启动完成时同步把 `@Tool` 方法写进 `agent_tool`，环境参数写进 `agent_tool_env_param`。启动日志按 `[BuiltinToolAutoRegistrar]` 前缀确认；如果启动失败且消息是 `Duplicate @Tool name(s) on the classpath`，说明两个方法用了同一个工具名，改名即可（此时库里一行都没写）。

### 步骤 8：绑定与验证

在 agent 配置面板勾选工具、填环境变量值，保存后走一次会话，看「调用监控」页的工具 tab 是否出现这个工具名。若 agent 看不到这个工具，按顺序核对：bean 是否在 admin 与 agent-service 的 classpath 上（`[ToolRegistry] Registered ToolBox bean` 日志）、`declaredNames` 是否包含该名字（工具页面能看到但 agent 拿不到，通常是 agent-service 一侧缺 bean）、绑定行是否存在、`status` 是否为 1、工具名是否与他人冲突导致启动失败。

### 常见陷阱速查

| 现象 | 判据 |
| --- | --- |
| 工具页面看不到 | 类未进组件扫描包；类没有 `@Component`；`ToolBox` 没有任何 `@Tool` 方法（`ToolRegistry` 记 INFO 后跳过）；bean 不在 admin classpath |
| 页面有、agent 装不上 | 交付按 `registeredToolNames()` 过滤后为空，说明 agent-service 侧缺 bean；或 `bean_name` 为空 |
| 模型看不到某个参数 | 该参数没有 `@ToolParam`，schema 生成时被跳过 |
| 参数缺省行为与描述不符 | `@ToolParam` 的描述文本不参与求值，缺省行为只看方法体的兜底表达式：`EmailToolBox.sendEmail` 的 `is_html` 描述写着 `Whether the body is HTML format (default: true)`，实现是 `val htmlMode = isHtml ?: false`，模型省略时发 `text/plain` |
| 环境参数永远报未配置 | 值写在 `agent_tool_env_param.default_value`（运行时不使用）；或必须工具想带参数（无绑定行） |
| 同组兄弟方法意外可用 | 装配收敛按「本 agent 授予的名字」保留，检查绑定行是否真只有那一个 `tool_id` |
| 调用指标里的工具名与预期不符 | `tool_name` 记的是模型看到的名字：`builtin` 那行是 `@Tool.name`，`cli` 那行是命中的那段命令名，`mcp` 那行是 MCP 服务的工具名——与 bean 名、方法名都无关 |
| `@ToolMeta` 写了没效果 | 写在了类上；注解目标是 `FUNCTION` |

## 10. 明确不做与已知边界

- 不支持在线定义工具：没有自定义脚本型、HTTP 调用型工具，没有入参 schema 录入界面。`agent_tool` 无对应列，管理 API 无写入方法。
- 不支持停用或卸载工具：表里没有人工停用通路，`status` 与 `active` 恒为 1；代码里删掉一个 `@Tool` 方法，表里的行会留着，只是被交付过滤挡住。
- 工具没有租户维度：`agent_tool` 无 `tenant_id`，一行对所有租户可见。隔离发生在绑定层——`agent` 属于某租户，绑定行随 agent 走。
- 工具级超时、重试、并发上限没有配置位：整轮超时由装配侧施加，并发序列化是 `@Tool.concurrencySafe` 的代码属性，不落库、不可按 agent 配。
- 必须工具与必填环境参数互斥，同步只 WARN 不拒绝启动。
- 工具没有独立的权限模型：可见性等于「被这个 agent 绑定」，读写性质与确认由 `readOnly` / `needConfirm` / `dangerousInput` 三个声明加会话权限模式决定。
- 团队 lead 不装配业务工具与必须工具，只有团队工具组；lead 的 meta tool、文件系统工具、shell 工具被显式关闭。
- 密文环境参数的 `defaultValue` 在响应里先解密再掩码（前 3 后 4，长度不足 7 全掩），解密失败统一显示 `******`，因此页面看到的长度不代表明文长度。

## 11. 关键文件索引

| 主题 | 路径 |
| --- | --- |
| ToolBox 基类 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt` |
| `@ToolMeta` 与 `@ToolEnvParamDef` | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvParamDef.kt` |
| 描述符 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMetaDescriptor.kt` |
| 环境上下文与调用上下文 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt` |
| SPI | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolInvocationAdaptor.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolConfigAdaptor.kt` |
| 注册中心 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt` |
| 装配输入 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolSpec.kt` |
| 内置工具 | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt`、`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt` |
| 启动同步 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` |
| 只读管理 API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt` |
| 绑定保存 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt` |
| spec 交付 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| spec 解析 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` |
| SPI 实现 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImpl.kt` |
| 运行时装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt` |
| 危险输入装饰器 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt` |
| 团队工具组 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt` |
| 整轮超时 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`、`harnax-agent/harnax-agent-service/src/main/resources/application.yml` |
| 调用指标 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationClassifier.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt`、`harnax-webui/src/pages/call-metrics/index.tsx` |
| 实体与 Mapper | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolBinding.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolEnvParam.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationLog.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationStats.kt`、`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml`、`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml`、`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml` |
| DDL | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`（`agent_tool`、`agent_tool_binding`、`agent_tool_env_param` 的列、键与缺省）、`harnax-admin/src/main/resources/db/migration/V3__tool_invocation_metrics.sql`（`tool_invocation_log`、`tool_invocation_stats` 的列、键与缺省）、`harnax-admin/src/main/resources/db/migration/V4__drop_tool_call_log.sql`（旧 `tool_call_log` 整表下线）、`harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql`（聚合升到小时档：`stat_hour` 与小时的两条键） |
| 前端 | `harnax-webui/src/pages/tool/index.tsx`、`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx`、`harnax-webui/src/services/ant-design-pro/tool.ts`、`harnax-webui/src/pages/call-metrics/dimensions.ts`（tab → 档位矩阵与「这一档读哪张表」） |
| 进程装配 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/HarnaxAdminApplication.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/AgentServiceApplication.kt` |

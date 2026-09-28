# Harnax Tool Capability and Developer Guide (English)

This is the capability document of the tool domain: where tools come from, which annotations and abstractions describe them, how the platform registers and assembles them, which tables hold the data, and what an operator and a developer can each do. Architectural decisions and invariants live in the sibling document "Harnax Tool Integration Design". Each document stands on its own; neither refers to the other's section numbers.

## 1. Overview

### 1.1 Where the facts come from

Every externally visible property of a tool is decided by annotations in the code: one `@Tool` method is one tool, and `@ToolMeta` adds its display names, environment parameter definitions, confirmation flag and dangerous-input flag. The `agent_tool` table is a mirror of those declarations, written by one sync at admin process startup; neither the pages nor the API offer a write path.

### 1.2 Modules and dependencies

| Module | Maven artifactId | Responsibility |
| --- | --- | --- |
| `harnax-agent/harnax-tools-sdk` | `harnax-tools-sdk` | SDK: `ToolBox` base class, annotations, contexts, descriptors, `ToolRegistry`, the two SPI interfaces |
| `harnax-tools-external/harnax-tools-buildin` | `harnax-tools-buildin` | Built-in tool implementations; today two groups: time and email |
| `harnax-agent/harnax-harness-core` | `harnax-harness-core` | Runtime assembly: `HarnessAgentLauncher`, `HarnessAgentBuilder`, `DangerousInputCheckingTool`, team tool groups |
| `harnax-admin` | `harnax-admin` | Startup sync `BuiltinToolAutoRegistrar`, read-only management API, spec delivery |
| `harnax-agent/harnax-agent-service` | `harnax-agent-service` | `ToolConfigAdaptorImpl`, `ToolCallLogAdaptorImpl`, `AgentSpecResolver` |

Dependency directions (taken from each module's `pom.xml`):

- `harnax-tools-sdk` → `harnax-common`, `harnax-entity`, `io.agentscope:agentscope`, `spring-boot-autoconfigure`, `spring-context`
- `harnax-tools-buildin` → `harnax-tools-sdk`, `io.agentscope:agentscope`, `spring-context`, `jakarta.mail-api` + `angus-mail`
- `harnax-harness-core` → `harnax-common`, `harnax-entity`, `harnax-protocol`, `harnax-agent-utils`, `harnax-tools-sdk`
- `harnax-admin` → `harnax-tools-sdk` + `harnax-tools-buildin` (the POM comment states the reason: so `BuiltinToolAutoRegistrar` can discover `ToolBox` beans)
- `harnax-agent-service` → `harnax-tools-buildin`

Both processes scan the `com.agnetix.harnax.tools` package: `HarnaxAdminApplication`'s `scanBasePackages` are `com.agnetix.harnax.admin` and `com.agnetix.harnax.tools`; `AgentServiceApplication`'s are `com.agnetix.harnax.agent.service`, `com.agnetix.harnax.agent.skill` and `com.agnetix.harnax.tools`. `ToolRegistry` is therefore a local bean container in each process, and its content equals the set of `ToolBox` beans on that process's classpath - which is why a new tool module has to join both the admin and the agent-service classpath.

The underlying framework is `io.agentscope` (version from the root `pom.xml` property `agent-scope.version`, currently `2.0.2`). `@Tool`, `@ToolParam`, `Toolkit` and the permission engine all come from it; harnax adds only its own annotations and wrappers.

## 2. Tool Classification

### 2.1 One category only: code-built tools

Every tool on the platform comes from a `@Tool` method on the classpath. `agent_tool` has no type column, no visibility column, no URL or input-schema column, and `AgentToolService` exposes read methods only. What an operator can choose is "which agent binds which tool, and which environment variable values that binding carries" - not how to define a tool.

### 2.2 Required versus optional tools

`@ToolMeta(isRequired = true)` declares a required tool, landing as `agent_tool.is_required = 1`. Three behaviours follow:

- A required tool never enters the agent configuration candidate set: the SQL of `AgentToolMapper.selectAvailableTools` carries `is_required = 0`.
- A required tool needs no binding row: at delivery time `InternalApiController.buildAgentSpecResponse` appends `requiredToolIds` (from `selectRequiredTools()`, whose predicate is `is_required = 1 AND status = 1 AND active = 1`) to the id set, skipping ids already bound.
- A required tool cannot carry environment parameters: with no binding row there is no `agent_tool_binding.env_bindings` to write into, so the sync logs a WARN whenever `isRequired` and a `required = true` env parameter appear together, telling the author to drop one of the two.

For a team lead, `requiredToolIds` is empty: a lead is configured by the `team` row and carries no business tools.

### 2.3 Tool groups constructed at assembly time

`TeamLeadToolBox` / `TeamMemberToolBox` in `harnax-harness-core` are plain classes, not Spring beans (their constructors need a `TeamOrchestrator`), and `HarnessAgentLauncher` builds them directly when assembling a team role. They are absent from `ToolRegistry`'s bean scan, so they appear in no `agent_tool` row, on no tool page, and in no agent binding; their names come from constants such as `TeamLeadToolBox.TOOL_NAMES` and are added to the framework ALLOW set of the permission engine. Assembling a lead also explicitly turns off the meta tool, the filesystem tools and the shell tool.

## 3. SDK Core Concepts (harnax-tools-sdk)

### 3.1 The ToolBox base class

`ToolBox` (`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`) is the base class of a tool group:

- `abstract fun name(): String`: the group name, used as the prefix of `tool_call_log.tool_name` and as `ToolMetaDescriptor.toolName`.
- `init(toolCallLogAdaptor, sessionMetaContext, userIdentifier)`: called by the assembly side before handing the instance to the `Toolkit`; pins the internal `name` field to the result of `name()`.
- `userIdentifier()`: throws `IllegalStateException("ToolBox not initialized: userIdentifier is null")` when `init` has not run.
- `protected fun <T> execute(vararg args: Pair<String, Any?>, action: () -> T)` and `execute(action: () -> T)`: wrap the method body. The method name comes from `Throwable().stackTrace[1].methodName`, so `execute` must be the direct expression of the tool method; a nested call records the enclosing method name instead.
- On success it logs `ToolCallInfo(success = true, result = the action's return value via toString, null as an empty string)`; on an exception it logs `success = false, result = "ERROR: ${message}"` and rethrows unchanged.
- The logged name format is `"<group>::<method>"`, e.g. `email-tool-box::sendEmail`.
- Without `init` (null `sessionMetaContext` or adaptor) it logs one WARN and skips recording; an exception from the adaptor itself is swallowed and logged at ERROR, leaving the tool result untouched.

### 3.2 The annotation set

| Annotation | Declared in | Targets | Provides |
| --- | --- | --- | --- |
| `@Tool` | `io.agentscope.core.tool.Tool` (agentscope 2.0.2) | method (and annotation type) | `name` (falls back to the method name), `description` (sent to the model), `readOnly`, `strict`, `concurrencySafe` (default true), `externalTool`, `stateInjected`, `dangerousFiles`, `dangerousDirectories`, `converter` |
| `@ToolParam` | `io.agentscope.core.tool.ToolParam` | parameter (and field, annotation type) | `name` (no default, mandatory), `description`, `required` (default true) |
| `@ToolMeta` | `com.agnetix.harnax.tools.sdk.ToolMeta` | method (`AnnotationTarget.FUNCTION`) | `displayName`, `displayNameZh`, `envParamDefs`, `needConfirm`, `dangerousInput`, `isRequired` |
| `@ToolEnvParamDef` | `com.agnetix.harnax.tools.sdk.ToolEnvParamDef` | annotation type (only as an `envParamDefs` element) | `key`, `description`, `required` (default true), `secret`, `defaultValue` |

Two hard constraints decide whether the model can see a tool at all:

1. **A parameter enters the JSON schema only when it carries `@ToolParam`.** agentscope's `ToolSchemaGenerator` iterates over annotated parameters only; `ToolMethodInvoker` treats a non-annotated, non-primitive parameter as an object to inject, resolving it by type from the `ToolExecutionContext`. The `ToolEnvContext` parameter is therefore exactly "auto-injected, hidden from the model".
2. **`@ToolMeta` applies to methods only.** Its `@Target` is `AnnotationTarget.FUNCTION`, so putting it on a class is never read: `ToolRegistry.extractToolMeta` calls `method.getAnnotation(ToolMeta::class.java)` per method, and a missing annotation yields empty display names, `needConfirm = false`, an empty env-parameter list and `isRequired = false`.

Division of fields: `name` / `description` / `readOnly` come from `@Tool` only; `needConfirm` comes from `@ToolMeta` only (`ToolRegistry` states "only from @ToolMeta.needConfirm", and `@Tool` has no such attribute); display names and env parameter definitions come from `@ToolMeta` only.

### 3.3 @ToolMeta attributes

| Attribute | Default | Column written | Runtime effect |
| --- | --- | --- | --- |
| `displayName` | `""` | `agent_tool.display_name`; the sync writes `@Tool.name` when blank | English label on the management page and the agent panel |
| `displayNameZh` | `""` | `agent_tool.display_name_zh`; NULL when blank | Label under the zh-CN locale |
| `envParamDefs` | `[]` | rows in `agent_tool_env_param`; keys with `required = true` are summarised into `agent_tool.required_env_param_keys` (a JSON array string) | Which fields the configuration panel shows, which are mandatory, which are masked as secrets |
| `needConfirm` | `false` | `agent_tool.need_confirm` | Assembly creates an ASK rule for that tool |
| `dangerousInput` | `false` | not stored | Assembly wraps the tool with `DangerousInputCheckingTool` |
| `isRequired` | `false` | `agent_tool.is_required` | See "Required versus optional tools" |

`dangerousInput` is not stored because its consumer lives in-process: `HarnessAgentLauncher.collectDangerousInputTools` reflects over the ToolBox class's method annotations and never queries a table.

### 3.4 The environment parameter system

`ToolEnvContext(bindings: Map<String, String>)` is the only entry point a tool method has to environment configuration:

- `get(key)` returns null when the key is unconfigured.
- `require(key)` throws `IllegalArgumentException("Environment parameter '<key>' is required but not configured")` when the value is missing or blank.
- `bindings` is one flat, per-agent merge: every env key/value resolved from the agent's tool bindings and MCP bindings lands in the same map, answered by name, with no record of which binding declared it. The merge order is tools first, then MCP, so a duplicate key keeps the later value.
- The whole map is handed to every tool of the agent, so a tool can read a key another binding declared.
- The map is registered into the `ToolExecutionContext` even when empty: otherwise a method declaring a `ToolEnvContext` parameter would fail at injection, and `require()` could not produce its readable "not configured" message.
- A declared `defaultValue` never fills a value at runtime: delivery carries binding values only, and `saveToolBindings` passes `defaultValueCounts = false` for built-in tools in its required-parameter check.

### 3.5 The ToolRegistry

`com.agnetix.harnax.tools.sdk.registry.ToolRegistry` is a `@Component`; its `@PostConstruct` does two things:

1. `applicationContext.getBeansOfType(ToolBox::class.java)` collects every ToolBox bean into `registry`, keyed by Spring bean name.
2. For each bean it reflects over `clazz.methods`, folding every `@Tool` method into a `ToolMethodDescriptor` and collecting them into `ToolMetaDescriptor(beanName, toolName = toolBox.name(), methods)` stored in `metaRegistry`. A bean with no `@Tool` method is logged at INFO and skipped: it stays out of `metaRegistry` but remains in `registry`, where `getToolBox` can find it.

Public methods: `getToolBox(beanName)`, `getAllToolBoxes()`, `getToolBoxNames()`, `contains(beanName)`, `getToolMeta(beanName)`, `getAllToolMeta()`, and `createToolBoxInstance(beanName)`.

`createToolBoxInstance` calls `template::class.java.getDeclaredConstructor().newInstance()` so each session owns its ToolBox; on failure it logs ERROR and falls back to the singleton. A ToolBox therefore keeps a no-argument constructor and holds no session state in fields.

### 3.6 Adaptor interfaces (SPI)

| Interface | Declared in | harnax implementation | Purpose |
| --- | --- | --- | --- |
| `ToolCallLogAdaptor` (`fun interface`, `emit(ToolCallInfo)`) | tools-sdk `adaptor` package | `ToolCallLogAdaptorImpl` in `harnax-agent-service`, converting to `ToolCallLogEntity` then `ToolCallLogMapper.insert` | Persists the call into `tool_call_log`; falls back to `{}` when args serialisation fails; swallows every exception into a log line |
| `ToolConfigAdaptor` (`getToolConfig(toolId): AgentTool?`) | tools-sdk `adaptor` package | `ToolConfigAdaptorImpl` in `harnax-agent-service` | Resolves a tool by id: first the delivered `toolDetails` in `AgentSpecContextHolder` (a `ToolDetailDto` converted to the entity), otherwise `AgentToolMapper.selectById`; `toolId <= 0` returns null |

On the harness side, `HarnessAutoConfiguration` obtains the `ToolCallLogAdaptor` through an `ObjectProvider` and uses a no-op when absent - running the harness embedded simply loses the call records, and assembly is unaffected.

### 3.7 Descriptors and remaining data classes

- `ToolMetaDescriptor(beanName, toolName, methods)`: everything one ToolBox declares; the sync's input.
- `ToolMethodDescriptor(methodName, toolName, displayName, displayNameZh, description, readOnly, needConfirm, envParamDescriptors, isRequired)`: one `@Tool` method, i.e. one `agent_tool` row.
- `ToolEnvParamDescriptor(key, description, required, secret, defaultValue)`: one env parameter definition.
- `SessionMetaContext(agentId: Long?, sessionId, tenantId: Long? = null)` and `UserIdentifier(userId: Long? = null)`: both implement the marker interface `ToolCallContext`. A null `agentId` means no `agent` row stands behind the run (a team lead); a null `tenantId` means the delivery named no tenant. Both are written as-is into `tool_call_log`, never guessed.
- `ToolSpec(toolId, toolName = "", needConfirm = false)`: assembly input. `toolName` is unused on the assembly path; the confirmation bit travels in `needConfirm`.

## 4. Built-in Tools Today (harnax-tools-buildin)

The module directory is `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`, while the files declare the package `com.agnetix.harnax.tools.builtin`: the directory name and the package name differ, and imports follow the package name.

Three `@Tool` methods are on the classpath, in two groups:

| Bean name | Group `name()` | Tool `@Tool.name` | Description | readOnly | needConfirm | Env params |
| --- | --- | --- | --- | --- | --- | --- |
| `time-tool-box` | `time-tool-box` | `getDate` | 获取当前日期 | yes | no | none |
| `time-tool-box` | `time-tool-box` | `getDatetime` | 获取当前时间 | yes | no | none |
| `email-tool-box` | `email-tool-box` | `sendEmail` | Send an email via SMTP. Supports plain text and HTML body. | no | yes | `SMTP_HOST`, `SMTP_PORT` (optional, default 587), `SMTP_USER`, `SMTP_PASSWORD` (secret), `SMTP_FROM` |

Additional behaviour:

- `getDate` / `getDatetime` format with `SimpleDateFormat("yyyy-MM-dd")` and `"yyyy-MM-dd HH:mm:ss"` in the JVM default time zone, with no inputs.
- All four model-visible `sendEmail` parameters carry `@ToolParam`: `to`, `subject`, `body` (all required) and `is_html` (`required = false`, type `Boolean?`); the fifth parameter `envContext: ToolEnvContext` is unannotated and injected. The body re-validates `to` / `subject` / `body` with `require` because the model can send null.
- `sendEmail` talks to Jakarta Mail directly: the port comes from `SMTP_PORT`, with a code-level 587 fallback when the binding carries no value; 465 uses implicit SSL, 25 is plaintext, anything else forces STARTTLS (`starttls.required = true`); connection and read timeouts are 10 seconds each; the body type follows `isHtml ?: false`, so a null `is_html` sends `text/plain` and true sends `text/html`, with subject and body encoded UTF-8. The `@ToolParam` description of `is_html` reads `Whether the body is HTML format (default: true)`, which disagrees with that same method body's `isHtml ?: false`: the description is model-facing text, and the `?:` expression is what decides the default behaviour.
- That is why `harnax-tools-buildin`'s `pom.xml` carries `jakarta.mail-api` and `angus-mail`.

## 5. Registration: One Sync at Startup

### 5.1 Trigger

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` is a `@Component` whose `syncBuiltinTools()` is bound to `@EventListener(ApplicationReadyEvent::class)`. When `getAllToolMeta()` is empty the whole sync is skipped with an INFO line and `declaredNames` keeps its previous value.

### 5.2 Convergence rules

The identity is `@Tool.name`, and the first step before any write is `requireUniqueNames(declared)`: one name claimed by two `@Tool` methods raises `IllegalStateException`, listing every `bean::method`, with no row written yet.

Groups are then processed one by one, each saving its rows before syncing its env parameter definitions:

- Name absent from the table: `insert`. The SQL writes `status` and `active` as the literal 1 and `creator` as `'SYSTEM'`.
- Name present in the table: the code-owned columns are compared one at a time (`display_name`, `display_name_zh`, `description`, `bean_name`, `method_name`, `read_only`, `need_confirm`, `is_required`, `required_env_param_keys`, `status`, `active`), and `updateById` runs only on a difference, which is written into the INFO log; `id`, `create_time` and `creator` keep the stored values. `name` takes part neither in the comparison nor in the UPDATE SET list.
- Row present, declaration absent: left alone. Nothing is deleted.

The lookup `AgentToolMapper.selectByName` carries no `active` condition, so a row sitting at `active = 0` still holds the name and gets updated back to `active = 1` instead of inserting a second row that would collide with `uk_agent_tool_name`.

A failed group is logged at ERROR, counted in `failCount`, and does not stop the other groups.

### 5.3 Env parameter definition sync

`syncToolEnvParams` works on `agent_tool_env_param` through the row ids resolved by the same `saveTool` pass: for a matching key it compares `description` / `required` / `secret` / `default_value` and updates on a difference; new keys are inserted; keys absent from the current declaration set are deleted (removing a parameter definition belongs to updating a tool; removing a tool is a different thing). A tool of the group that failed to save is skipped with a WARN.

### 5.4 Declared set and delivery filter

At the end of the sync, `declaredNames` becomes "the names declared in this pass" - declared, not successfully written; otherwise one write failure would remove those tools from every agent.

`InternalApiController` filters with it at delivery: `agentToolMapper.selectByIds(ids).filter { declaredNames.isEmpty() || it.name in declaredNames }`. The table may hold rows whose method is off the classpath; handing one to the runtime would fail at assembly, so the row stays visible and stops travelling. An empty `declaredNames` means the sync knows nothing, which disables the filter rather than blocking every tool. Dropped ids are logged at WARN.

### 5.5 Troubleshooting anchors

Search the logs for `[BuiltinToolAutoRegistrar]`. The lines in order are: `Syncing N builtin tool group(s), M declared tool(s)`, `Registered new tool '<name>' (<bean>::<method>)`, `Updated tool '<name>' (id=..): <column list>`, `Synced env params for tool '<bean>::<method>': N total, M stale removed`, `Synced tool group: <bean> [N methods]`, and the last one `Sync complete: N succeeded, M failed; K tool name(s) declared`. On the `ToolRegistry` side: `[ToolRegistry] Registered ToolBox bean: ...` and `Total N ToolBox beans registered, M with @Tool methods`.

## 6. Runtime Tool Assembly

### 6.1 Delivery: the admin side

`InternalApiController.buildAgentSpecResponse` reads the `agent_tool_binding` rows and `selectRequiredTools()`, builds the de-duplicated id set, filters it as described under "Declared set and delivery filter", and produces two structures:

- `toolDetails`: a `ToolDetailDto` list carrying `name` / `beanName` / `methodName` / `readOnly` / `needConfirm` / `requiredEnvParamKeys` / `status`, plus that binding's own `bindingNeedConfirm`.
- `toolList`: a JSON string whose items carry `id`, `need_confirm` (from the binding row) and `env_bindings`, the latter resolved on the spot by `resolveEnvBindingsJson`: an `envVarId` reference prefers the current decrypted value via `envVariableService.getDecryptedValue(envVarId, tenantId)` and falls back to the stored snapshot `envValue`; a reference that resolves to nothing with no snapshot delivers nothing (the tool sees the parameter as unconfigured); otherwise `customValue`, and the snapshot last.

### 6.2 Resolution: the agent-service side

`AgentSpecResolver` folds `toolDetails` into `ToolSpec(toolId, needConfirm = bindingNeedConfirm)` entries, merges the `env_bindings` found in `toolList` and `mcpList` into one flat map, and registers that map as a `ToolEnvContext` in `AgentSpec.contextForTools`.

### 6.3 Assembly: the harness-core side

The tool section of `HarnessAgentLauncher.createAgentBase` does, in order:

1. Assembly runs only for a non-lead with a non-empty `agentSpec.toolSpecs` and a present `toolConfigAdaptor`; a missing `ToolConfigAdaptor` is the one case that logs a WARN and installs no tools at all.
2. Each `toolSpec` is resolved with `toolConfigAdaptor.getToolConfig(toolSpec.toolId)`; `status == 0` logs INFO and skips.
3. De-duplicated by `beanName`: a ToolBox is instantiated once. `toolRegistry.createToolBoxInstance(beanName)` builds a session-level instance, `init(toolCallLogAdaptor, SessionMetaContext(agentSpec.attributableAgentId, sessionId, agentSpec.tenantId), userIdentifier)` primes it, and `agentBuilder.addTool(toolBox)` registers the group.
4. `Toolkit.registerTool` registers the whole group, so a sweep follows: every tool name in `getToolMeta(bean).methods` that this agent was not granted is withdrawn with `agentBuilder.removeTool(name)`. Two sources produce ungranted names - unselected siblings inside the same group, and disabled methods.
5. Confirmation is a union: `toolConfig.needConfirm == 1 || toolSpec.needConfirm`. The binding level can only tighten. Hits go into `needConfirmedTools`.
6. Each ToolBox class is reflected once for `@ToolMeta(dangerousInput = true)`; hits go into `dangerousInputTools`.
7. `contextForTools` is registered into the `ToolExecutionContext` (see "The environment parameter system").
8. Team tools are registered after the sweep, so step 4 cannot withdraw them.
9. Permission context: a set of framework tool names (`plan_enter`, `plan_write`, `plan_exit`, `todo_write`, `agent_spawn`, `agent_send`, `agent_list`, `task_output`, `task_list`) plus the team tool names go to ALLOW; `needConfirmedTools` go to ASK, rules sourced as `harnax`.
10. Dangerous-input wrapping: each `dangerousInputTools` name is wrapped with `agentBuilder.wrapWithDangerousInputCheck(toolName)`; a tool that already has an ASK rule skips wrapping (the ASK fires before `checkPermissions`, so the scan would be redundant).

### 6.4 Permission modes

Mode strings go through `PermissionMode.fromString`, accepting `DEFAULT`, `ACCEPT_EDITS`, `EXPLORE`, `BYPASS`, `DONT_ASK`: DEFAULT needs an explicit ALLOW rule for every operation; ACCEPT_EDITS auto-allows edits inside working directories; EXPLORE is read-only and denies mutating tools; BYPASS skips rule evaluation; DONT_ASK demotes ASK to DENY for unattended runs. A `@Tool(readOnly = true)` tool is auto-allowed under EXPLORE and ACCEPT_EDITS.

### 6.5 Dangerous-input interception

`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt` is a `ToolBase` decorator wrapping the original tool (typically a `ReflectiveFunctionTool`): name, description and input schema are copied, `concurrencySafe(true)`, `readOnly` read from the delegate, and `callAsync` delegates directly.

`checkPermissions` walks the string values of the input (length threshold 3):

- First a case-insensitive substring match against `ToolDangerousPathConstants.DANGEROUS_COMMANDS`; a hit returns ASK naming the fragment and the parameter.
- Then `ToolBase.isDangerousPath`; a hit returns ASK. That check matches dangerous file names and directory segments and resolves symlinks so redirection cannot bypass it.
- With no hit it returns `PermissionDecision.passthrough(name)`, leaving the decision to the engine's rule tables and mode defaults.

Both ASK reasons start with `safety:`, which the PermissionEngine contract treats as bypass-immune: BYPASS cannot skip them.

### 6.6 Execution timeout

Timeout is an assembly-side property, not a tool property. `HarnessAgentWrapper`'s constructor parameter `turnTimeoutSeconds` (default 300) puts `.timeout(Duration.ofSeconds(...))` on the whole turn, and a non-positive value skips it. The value comes from `harness.turn-timeout-seconds` (`harnax-agent/harnax-agent-service/src/main/resources/application.yml`, default 300); team turns go through `turnBudget(teamRole)` and use `harness.team.turn-timeout-seconds` (default 1800), with a WARN when that budget is not above the member-turn budget. A single tool has no timeout budget of its own; a slow tool is bounded by the turn budget.

### 6.7 Call logging

`ToolBox.execute` produces the `ToolCallInfo` and `ToolCallLogAdaptorImpl` stores it: `toolName` is `<group>::<method>`, `args` is the map serialised to JSON (each value via `toString`, null as the string `"null"`), `result` is the return value as a string or `ERROR: <message>`, `success` is 0/1, timestamps are converted from milliseconds in the system time zone, and `ts` takes the end time.

## 7. Data Model

Columns and indexes come from admin's schema baseline `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`: that one script holds every table and column definition, with no later version to stack on top of it. `agent_tool` is platform-scoped, identified by name (`uk_agent_tool_name`) and additive; `tool_call_log`'s `tenant_id` is a nullable column declared, together with the `idx_tenant_ts (tenant_id, ts)` index, inside that table's own create statement.

### 7.1 agent_tool

The tool table: one row = one `@Tool` method.

| Column | Type | Written by | Meaning |
| --- | --- | --- | --- |
| `id` | bigint PK AUTO_INCREMENT | database | Addressed by bindings and by delivery |
| `name` | varchar(100) NOT NULL, `UNIQUE uk_agent_tool_name` | sync | The tool identity, i.e. `@Tool.name` |
| `display_name` / `display_name_zh` | varchar(200) | sync | Display names; English falls back to `name`, Chinese to NULL |
| `description` | text | sync | Sent to the model, from `@Tool.description` |
| `bean_name` / `method_name` | varchar(200) / varchar(100) | sync | For instantiation and invocation; attributes, not identity |
| `read_only` | tinyint(1) | sync | From `@Tool.readOnly` |
| `need_confirm` | tinyint(1) | sync | From `@ToolMeta.needConfirm` |
| `is_required` | tinyint(1) NOT NULL DEFAULT 0 | sync | From `@ToolMeta.isRequired` |
| `required_env_param_keys` | varchar(1000) | sync | JSON array of the `required = true` keys, e.g. `["SMTP_HOST","SMTP_USER"]` |
| `status` / `active` | tinyint(1) | sync | Always 1: the sync is the only writer and never deletes |
| `creator` | varchar(100) | sync | Constant `SYSTEM` |
| `create_time` / `update_time` | datetime | database | Only `update_time` moves on an update |

There is no tenant column, no type column, no HTTP columns, no schema columns and no timeout column.

### 7.2 agent_tool_binding

| Column | Meaning |
| --- | --- |
| `agent_id`, `tool_id` | Composite unique key `uk_agent_tool_binding_agent_id_tool_id`: at most one row per agent-tool pair |
| `need_confirm` | Binding-level confirmation, OR-ed with `agent_tool.need_confirm` at runtime |
| `env_bindings` | JSON array snapshot whose elements carry `envKey`, `envValue`, `envVarId`, `envVarName`, `customValue` |
| `create_time` / `update_time` | Written on save |

`AgentServiceImpl.saveToolBindings` saves by deleting the agent's whole set and re-inserting, with `distinctBy { toolId }` first. An unresolvable tool id raises `BizException("Tool is missing or deleted: ...")`; an unfilled required env parameter is refused by `assertRequiredEnvParamsFilled`, where a built-in tool's declared default does not count as filled. The binding row has no "skip when missing" switch: whether a tool travels is decided by the declared-name set.

### 7.3 agent_tool_env_param

The tool's env parameter definition table: `tool_id` points at `agent_tool.id`, `(tool_id, env_param_name)` is unique, and `description`, `required`, `secret`, `default_value` complete it. It describes what a tool needs; the values live on the binding row.

### 7.4 tool_call_log

`agent_id` (nullable; NULL for a lead run), `tenant_id` (nullable; NULL when unattributed), `session_id`, `tool_name` (`<group>::<method>`), `args` (JSON), `result`, `success`, `start_time`, `end_time`, `duration`, `ts`. `ToolCallLogMapper` has `insert` only: a row is written once, never read back, and never pruned together with its session or agent. Indexes: `idx_agent_id`, `idx_session_id`, `idx_tool_name`, `idx_ts` and `idx_tenant_ts (tenant_id, ts)`.

### 7.5 Relation to env_variable

An element of `agent_tool_binding.env_bindings` either references an `env_variable` row (`envVarId` plus an `envVarName` snapshot) or carries its own `customValue`; the two are mutually exclusive. A reference is resolved at delivery time against the agent's tenant for the latest decrypted value, falling back to the snapshot.

## 8. Management API and Pages

### 8.1 Read-only API

`/api/admin/tools` (`AgentToolController`), GET only:

| Method | Path | Behaviour |
| --- | --- | --- |
| GET | `/api/admin/tools/page` | Paged listing; `pageNum`, `pageSize` (clamped to 1..1000), `keyword` (matches name / display_name / description), `status`; ordered `status DESC, update_time DESC` |
| GET | `/api/admin/tools/{id}` | Single detail; missing row returns `error.tool.notfound` |
| GET | `/api/admin/tools/available` | Bindable candidates: `status = 1 AND active = 1 AND is_required = 0`, ordered by name |
| GET | `/api/admin/tools/builtin` | All code-owned tools: `active = 1`, ordered by name, no status predicate |
| GET | `/api/admin/tools/{id}/required-env-params` | The tool's required env parameter keys |

Responses are `AgentToolResponse`, carrying `envParams` (a `ToolEnvParamEntry` list) and the parsed `requiredEnvParamKeys`. There is no POST/PUT/DELETE: tools are created, changed and removed in code.

### 8.2 The tool page

`harnax-webui/src/pages/tool/index.tsx` is a read-only table fed by `getBuiltinTools()`. Columns: name (locale-resolved `displayNameZh` → `displayName` → `name`, with a monospace `name` line when the label differs), description, env params (`EnvParamsPopover`, whose fallback count reads `requiredEnvParamKeys.length`), need confirm (an orange Tag when `needConfirm === 1`), required (a red Tag when `isRequired === 1`). Searching happens in the browser over `name` plus the three display fields; there is no pagination. `harnax-webui/src/pages/tool/components/ToolEnvEntriesEditor.tsx` provides the env-variable editor reused by the agent side.

### 8.3 Agent-side configuration

`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx` is where tools are actually selected and configured: each entry can toggle `needConfirm` (written to the binding row) and produces `envBindings` through the env editor. The service layer is `harnax-webui/src/services/ant-design-pro/tool.ts`: `getAvailableTools()` and `getBuiltinTools()`.

## 9. Developing a New Tool, Step by Step

### Step 1: pick the module

Create a sibling module under `harnax-tools-external/` (built-in tools are `harnax-tools-buildin`) whose POM depends at least on `harnax-tools-sdk`, `io.agentscope:agentscope` (version via `agent-scope.version`) and `spring-context`. Add it to both `harnax-admin/pom.xml` and `harnax-agent/harnax-agent-service/pom.xml`: the first decides whether the tool can be registered into the table, the second whether the runtime can instantiate it. Both discover beans through the `com.agnetix.harnax.tools` package scan; the Spring bean name defaults to the decapitalised class name, so write `@Component("xxx-tool-box")` when the name must be stable.

### Step 2: write the ToolBox class

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
    ): String = execute("order_no" to orderNo) {
        require(!orderNo.isNullOrBlank()) { "Parameter 'order_no' is required" }
        "order $orderNo: PAID"
    }

    override fun name(): String = NAME

    companion object {
        const val NAME = "order-tool-box"
    }
}
```

Points: extend `ToolBox` and implement `name()`; keep a no-argument constructor (session-level instances come from `getDeclaredConstructor()`); wrap the body in `execute`, otherwise there is no call log; one method, one globally unique tool name.

### Step 3: expose parameters to the model

Every model-visible parameter needs `@ToolParam(name = ...)`; `name` has no default and must be written. Optional parameters need `required = false`. Stick to primitives and String; framework-injected objects such as `ToolEnvContext` must not carry `@ToolParam`. Nullable Kotlin parameters plus a `require` in the body are the built-in style, because the model may omit any argument and send null. A `description` only reaches the schema text and takes no part in evaluation: the default behaviour comes from the fallback expression in the method body, so where the description's default and the value after `?:` differ, the latter is what runs.

### Step 4: environment parameters

Declare configuration with `@ToolMeta(envParamDefs = [...])` of `ToolEnvParamDef(key = ..., description = ..., required = ..., secret = ..., defaultValue = ...)`, add an unannotated `envContext: ToolEnvContext` parameter, and read with `envContext.require("KEY")` (throws when missing) or `envContext.get("KEY") ?: fallback`.

Three notes: `required` only drives the panel's labelling and the save-time check, while the runtime judgement is `require()`; keys are shared across the whole agent, so a tool can read a value another binding declared; `defaultValue` does not fill anything at runtime.

### Step 5: confirmation and dangerous input

`needConfirm = true` makes every call ask the user. `dangerousInput = true` makes assembly wrap the tool with `DangerousInputCheckingTool`, scanning string arguments for dangerous command fragments and paths, producing a bypass-immune ASK on a hit. Declaring both means wrapping is skipped (the ASK rule fires first). Any tool whose string parameters may carry a command or a path should declare `dangerousInput`; a read-only tool with no free-text input needs neither.

### Step 6: unit tests

Every built-in ToolBox has a matching test (`TimeToolBoxTest`, `EmailToolBoxTest`, `EmailToolBoxIntegrationTest`). `ToolRegistryTest` on the SDK side covers bean scanning and descriptor extraction and is the model for a registration assertion: given a ToolBox class, each `@Tool` method yields one `ToolMethodDescriptor`.

### Step 7: it registers itself at startup

No manual insert, no page action, no SQL script: when admin finishes starting, the sync writes the `@Tool` methods into `agent_tool` and their env parameters into `agent_tool_env_param`. Confirm via the `[BuiltinToolAutoRegistrar]` log lines. A startup failure whose message is `Duplicate @Tool name(s) on the classpath` means two methods claim one name; rename one - no row had been written.

### Step 8: bind and verify

Select the tool in the agent panel, fill the env values, run one turn, and check that `tool_call_log` contains a `<group>::<method>` row. If the agent cannot see the tool, check in order: whether the bean is on the admin and agent-service classpaths (the `[ToolRegistry] Registered ToolBox bean` line), whether `declaredNames` contains the name (visible on the page but missing for the agent usually means the bean is absent from agent-service), whether the binding row exists, whether `status` is 1, and whether a name clash failed the startup.

### Quick pitfall reference

| Symptom | Judgement |
| --- | --- |
| Missing from the tool page | Class outside the scanned package; class without `@Component`; ToolBox with no `@Tool` method (`ToolRegistry` logs INFO and skips); bean absent from the admin classpath |
| On the page but not assembled | The delivery filter on `registeredToolNames()` empties the list, meaning the bean is missing from agent-service; or `bean_name` is empty |
| The model cannot see a parameter | The parameter lacks `@ToolParam`, so schema generation skips it |
| A parameter's default differs from its description | The `@ToolParam` description takes no part in evaluation; the fallback expression in the method body decides it: `EmailToolBox.sendEmail`'s `is_html` is described as `Whether the body is HTML format (default: true)` while the body computes `val htmlMode = isHtml ?: false`, so an omitted argument sends `text/plain` |
| An env parameter always reports unconfigured | The value sits in `agent_tool_env_param.default_value` (unused at runtime); or a required tool tries to carry parameters (no binding row) |
| Unselected sibling methods are available | The assembly sweep keeps exactly the granted names, so check the binding rows really hold one `tool_id` each |
| Wrong method name in the log | `execute` is not the tool method's direct expression, so the stack-frame walk picked up the outer method |
| `@ToolMeta` seems to have no effect | It was written on the class; its target is `FUNCTION` |

## 10. Explicit Non-goals and Known Boundaries

- No tool definition at runtime: no scripted or HTTP tool kind, no input-schema form. `agent_tool` has no such columns and the management API has no write method.
- No disable or uninstall: there is no human disable path, `status` and `active` stay 1; deleting a `@Tool` method from code leaves its row in the table, held back by the delivery filter.
- Tools carry no tenant: `agent_tool` has no `tenant_id`, so a row is visible to every tenant. Isolation lives in the binding layer - an `agent` belongs to a tenant and its binding rows follow it.
- No configuration slot for per-tool timeout, retry or concurrency limits: the turn timeout is applied by the assembly side, and concurrency serialisation is the code attribute `@Tool.concurrencySafe`, neither stored nor adjustable per agent.
- Required tools and required env parameters are mutually exclusive; the sync only warns, it does not refuse to start.
- Tools have no permission model of their own: visibility equals "bound to this agent", and behaviour under the permission engine comes from `readOnly` / `needConfirm` / `dangerousInput` plus the session's permission mode.
- Call logs are write-only: the product has no tool-call statistics page, and `tool_call_log` needs a direct database query.
- A team lead assembles no business or required tools, only the team tool group; its meta tool, filesystem tools and shell tool are explicitly disabled.
- A secret env parameter's `defaultValue` is decrypted and then masked in the response (first 3 and last 4 characters, fully masked below length 7), and shows `******` when decryption fails, so the rendered length is not the plaintext length.

## 11. Key File Index

| Topic | Path |
| --- | --- |
| ToolBox base class | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt` |
| `@ToolMeta` and `@ToolEnvParamDef` | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvParamDef.kt` |
| Descriptors | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMetaDescriptor.kt` |
| Env and call contexts | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt` |
| SPI | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolCallLogAdaptor.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolConfigAdaptor.kt` |
| Registry | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt` |
| Assembly input | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolSpec.kt` |
| Built-in tools | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt`, `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt` |
| Startup sync | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` |
| Read-only management API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt` |
| Binding save | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt` |
| Spec delivery | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| Spec resolution | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` |
| SPI implementations | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImpl.kt` |
| Runtime assembly | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt` |
| Dangerous-input decorator | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt` |
| Team tool groups | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt` |
| Turn timeout | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`, `harnax-agent/harnax-agent-service/src/main/resources/application.yml` |
| Entities and mappers | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolBinding.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolEnvParam.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolCallLogEntity.kt`, `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml`, `harnax-entity/src/main/resources/mapper/ToolCallLogMapper.xml` |
| DDL | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` (the columns, keys and defaults of `agent_tool`, `agent_tool_binding`, `agent_tool_env_param` and `tool_call_log` are all written in this one baseline) |
| Frontend | `harnax-webui/src/pages/tool/index.tsx`, `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx`, `harnax-webui/src/services/ant-design-pro/tool.ts` |
| Process wiring | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/HarnaxAdminApplication.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/AgentServiceApplication.kt` |

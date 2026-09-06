# Harnax MCP 服务实体管理流程（中文）

> 英文版本见 [mcp-management.en-US.md](./mcp-management.en-US.md)
>
> 工具体系整体设计见 [tool-capability.zh-CN.md](./tool-capability.zh-CN.md)，技能（Skill）体系见 [skill-management.zh-CN.md](./skill-management.zh-CN.md)。本文覆盖 MCP 服务的数据模型、Admin 管理、绑定下发与运行时装配全链路，文末附检查发现的问题与待办事项。

## 1. 概述

MCP（Model Context Protocol）服务是 Agent 的外部工具来源之一，与内置工具（`BUILTIN`）、HTTP 代理工具（`HTTP`）并列。MCP 实体管理遵循与工具体系一致的分层模式：

- **harnax-entity**：`McpServer` / `AgentMcpBinding` 实体与 Mapper；
- **harnax-admin**：MCP 服务的 CRUD、密钥加密存储、掩码回显、连通性测试、绑定管理与配置下发；
- **harnax-agent-service / harness-core**：运行时按智能体配置装配 MCP 客户端。

## 2. 数据模型

### 2.1 mcp_server（MCP 服务主表）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`

| 字段 | 说明 |
|------|------|
| `name` / `description` | 服务名（全局唯一）与描述 |
| `type` | 传输类型：`stdio` / `sse` / `streamablehttp`（默认） |
| `command` | 执行命令（仅 stdio 类型使用） |
| `url` | 服务地址（sse / streamablehttp 类型使用） |
| `headers` | HTTP 请求头 JSON，格式 `[{"key":"Authorization","value":"...","secret":true}]`；`secret=true` 条目的 value 以 **AES-256-GCM 加密**存储 |
| `envParams` | 环境参数 JSON（stdio 类型的进程环境变量），格式同 `ToolEnvParamEntry`，secret 条目加密存储 |
| `status` | 启用状态（0 禁用 / 1 启用） |
| `isPublic` | 公开状态 |
| `creator` / `tenantId` | 创建人与租户 |
| `active` | 逻辑删除标记（0 已删除 / 1 有效） |

### 2.2 agent_mcp_binding（智能体-MCP 绑定表）

实体：`AgentMcpBinding.kt`

- `agentId` / `mcpId`：绑定关系；
- `enableSkip`：MCP 不可用时是否跳过（`"true"` / `"false"` 字符串）；
- `envBindings`：环境变量绑定 JSON 快照（envKey + `envVarId` 或 `customValue`），**注意该通道只注入 `ToolEnvContext` 供 ToolBox 工具读取，不注入 MCP 客户端本身**（见第 6 节）。

### 2.3 McpDetailDto（内部 API 下发载体）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`

Admin 内部 API 下发给 agent-service 的完整 MCP 配置（含绑定级 `enableSkip`），免去 agent-service 直查 `mcp_server` 表。注意其中 `headers` / `envParams` 为**加密态原文透传**。

## 3. Admin 管理流程

实现：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt` + `service/impl/McpServerServiceImpl.kt`，前缀 `/api/admin/mcp`。

### 3.1 接口清单

| 接口 | 方法 | 说明 |
|------|------|------|
| `/page` | GET | 分页查询（keyword / status / type 过滤） |
| `/{id}` | GET | 服务详情（secret 字段掩码回显） |
| `/`（根路径） | POST | 创建 MCP 服务 |
| `/update/{id}` | PUT | 更新 MCP 服务 |
| `/toggle/{id}` | PUT | 启用 / 禁用 |
| `/{id}` | DELETE | 逻辑删除（`active=0`） |
| `/{id}/connectivity-test` | POST | 连通性测试（内部调用 listTools） |
| `/{id}/list_tools` | GET | 实时连接 MCP 服务拉取工具列表（名称 + 参数 Schema） |

### 3.2 创建 / 更新校验与加密

1. **名称唯一性**：`selectByName` 查重，重名抛 `BizException`；
2. **type 与字段联动校验**（`validateTypeAndFields`）：`stdio` 必填 `command`；`sse` / `streamablehttp` 必填 `url`；其他 type 拒绝；
3. **密钥加密**：`headers` 经 `SecretFieldEncryptor.serializeWithEncryption`、`envParams` 经 `serializeToolEnvParams` 加密后序列化落库（仅 `secret=true` 条目加密）；
4. 更新时 `headers` / `envParams` 仅在请求携带时重新加密覆盖。

### 3.3 掩码回显

`McpServerResponse.fromEntity` 解密 secret 条目后掩码：长度 > 7 显示「前 3 位 + `****` + 后 4 位」，否则显示 `******`；解密失败也显示 `******`。前端永不接触明文密钥。

### 3.4 加解密基础设施

- `SecretFieldEncryptor`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/`）：实现 `McpConfigDecryptor` 接口（定义在 `harnax-common`），基于 `AesUtil`（AES-256-GCM）；
- `McpConfigDecryptor`（`harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/`）：跨模块解密 SPI，提供 `decryptToMap`（headers）与 `decryptToolEnvParamsToMap`（envParams）两个方法。

## 4. 智能体绑定与配置下发

1. **绑定保存**：智能体配置保存时 `AgentServiceImpl.saveMcpBindings` 先删后插 `agent_mcp_binding`；`envBindings` 通过 `serializeEnvBindings` 写入快照——引用全局环境变量（`envVarId`）时解析出 `envVarName` 与解密后的 `envValue`，自定义值存 `customValue`；
2. **配置下发**：agent-service 请求智能体配置时，`InternalApiController.buildAgentSpecResponse` 下发两份数据：
   - `mcpDetails`：完整 `McpDetailDto` 列表（`headers` / `envParams` 保持加密态）；
   - legacy `mcpList` JSON：`env_bindings` 已通过 `resolveEnvBindingsJson` 解析为明文（`envVarId` 取全局变量**最新**解密值，失败回退快照值）。

## 5. 运行时装配流程（agent-service）

```
AgentSpecResolver.buildAgentSpec()
    ├─ mcpDetails → McpSpec(mcpId, skipIfMissing = enableSkip == "true")
    └─ mcpList 的 env_bindings → 并入 ToolEnvContext（供 ToolBox 工具读取）
            ▼
HarnessAgentLauncher.createAgentBase()
    遍历 agentSpec.mcpServices：
    ├─ McpConfigAdaptorImpl.getConfig(mcpId)
    │     优先：AgentSpecContextHolder 中 admin 预下发的 mcpDetails（DTO → 实体转换）
    │     回退：McpServerMapper.selectById 直查数据库
    ├─ 配置存在 → McpHelper.createMcpClient(
    │        mcpConfig, isAsync,
    │        mcpConfigDecryptor?.decryptToMap,            // headers 解密
    │        mcpConfigDecryptor?.decryptToolEnvParamsToMap // envParams 解密
    │     )
    └─ 配置缺失 → skipIfMissing=false 抛 AGENT_MCP_NOT_FOUND；true 仅告警跳过
            ▼
McpHelper.buildMcpConfig() 按 type 分派：
    ├─ stdio          → StdioMcpConfig(command, env = envResolver(envParams))
    ├─ sse            → SseHttpMcpConfig(url, headers = configResolver(headers))
    └─ streamablehttp → StreamableHttpMcpConfig(url, headers = configResolver(headers))
            ▼
McpClientBuilder 构建客户端（buildSync / buildAsync）→ agentBuilder.addMcp(...)
```

其他运行时入口：

- `McpHelper.listTools`：Admin 连通性测试 / list_tools 接口使用，`initialize()` 阻塞超时 10 秒，失败抛 `MCP_CONNECTION_FAILED`；
- resolver 为 `null` 时回退 `{ emptyMap() }`——这正是待办事项 1 的问题根源。

## 6. 环境变量与密钥的两条通道（易混淆）

| 通道 | 配置位置 | 作用对象 | 解密时机 |
|------|---------|---------|---------|
| `mcp_server.headers` / `envParams` | MCP 服务自身配置（Admin MCP 编辑页） | **MCP 客户端本身**（鉴权头 / stdio 进程环境变量） | agent-service 运行时经 `McpConfigDecryptor` 解密（当前存在问题，见待办 1） |
| `agent_mcp_binding.envBindings` | 智能体绑定 MCP 时的环境变量表单 | **ToolEnvContext**（ToolBox 工具方法读取），不注入 MCP 客户端 | Admin 下发时（`InternalApiController`）已解析为明文 |

## 7. 发现的问题与待办事项（待处理）

> 以下为 2026-09 代码检查发现，均未修复，按优先级排列。

### TODO-1（高风险）：agent-service 运行时 McpConfigDecryptor 恒为 null，加密的 headers/envParams 被静默丢弃

- **现象**：SSE / streamablehttp 型 MCP 的鉴权 headers（如 `Authorization`）与 stdio 型的环境参数在 agent-service 运行时装配中全部丢失，需要鉴权的 MCP 服务会以「无凭证」状态连接失败或行为异常，且无任何告警日志。
- **根因链**：
  1. `McpConfigDecryptor` 的唯一实现 `SecretFieldEncryptor` 位于 harnax-admin 模块；
  2. agent-service 的 pom 不依赖 harnax-admin，容器中无该 Bean；
  3. `HarnessAutoConfiguration.harnessAgentLauncher` 使用 `mcpConfigDecryptorProvider.ifAvailable` 注入，取不到即 `null`，且无兜底实现（对比 `ToolCallLogAdaptor` 有 no-op 兜底）；
  4. `HarnessAgentLauncher` 将 `null` 传给 `McpHelper.createMcpClient`，`buildMcpConfig` 的 resolver 回退为 `{ emptyMap() }`，headers / envParams 直接置空；
  5. 即使 agent-service 自带解密器实现也无济于事——Admin 下发的 `mcpDetails.headers/envParams` 是加密态原文，而 AES 密钥（`AesUtil`）只存在于 admin 侧。
- **修复方向（二选一，待评估）**：
  - 方案 A：将加解密能力（`AesUtil` + `SecretFieldEncryptor` 或等价实现）下沉到 `harnax-common`，agent-service 与 admin 共享同一密钥配置；
  - 方案 B：Admin 内部 API 下发 `mcpDetails` 前完成解密，明文仅经服务间内部认证通道传输（与 `mcpList.env_bindings` 的处理方式对齐），agent-service 不再需要解密器。
- **涉及文件**：`HarnessAutoConfiguration.kt`、`HarnessAgentLauncher.kt`、`McpHelper.kt`、`InternalApiController.kt`、`SecretFieldEncryptor.kt`、agent-service `pom.xml`。

### TODO-2（中）：创建与更新接口对 stdio 类型的校验口径不一致

- **现象**：`McpServerController.createMcpServer` 硬编码拒绝 `type == "stdio"`（提示「stdio mode is not supported in current edition」，疑似多版本机制清理后的遗留判断）；但 `updateMcpServer` 走 `validateTypeAndFields` 允许 stdio——通过更新接口可以把已有记录改成 stdio，绕过创建限制。
- **处理建议**：确认 stdio 是否为当前版本支持的目标形态；若不支持，在 update 路径补充同样拦截；若支持，删除 create 中的遗留判断。

### TODO-3（低）：McpServerController 存在未使用的 mock 死代码

- **现象**：`getMockTools()` 与 `createParameter()` 约 70 行 mock 工具数据无任何调用方。
- **处理建议**：直接删除。

### TODO-4（低，体验类）：前端 MCP 编辑页环境变量配置不完整

- **现象**：`envs` 字段仅在 stdio 类型下显示，SSE / Streamable HTTP 等网络类型缺少环境变量配置项（来源：既往问题记录，待复核当前前端代码）。
- **处理建议**：结合 TODO-1 的修复方案一并设计网络型 MCP 的凭证配置交互。

## 8. 关键文件索引

| 模块 | 文件 |
|------|------|
| 实体 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`、`AgentMcpBinding.kt`、`dto/McpDetailDto.kt` |
| Mapper | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpServerMapper.kt`（XML：`harnax-entity/src/main/resources/mapper/McpServerMapper.xml`） |
| 管理 API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt` |
| 管理服务 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImpl.kt` |
| 加密器 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt` |
| 解密 SPI | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt` |
| 绑定保存 / 下发 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`、`controller/InternalApiController.kt` |
| Spec 解析 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` |
| 配置适配器 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt` |
| 客户端构建 | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`、`McpConfig.kt` |
| 运行时装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`、`spring/HarnessAutoConfiguration.kt` |

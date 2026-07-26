# Sandbox CLI Plugin 设计规格

## 概述

将 CLI 工具作为可选插件嵌入 agent 沙箱镜像，使每个会话内的 agent 能通过 shell 调用 CLI 管理平台资源。设计为通用框架，harnax-cli 作为第一个实现。

## 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 认证方式 | Internal Secret（服务级） | 不过期、稳定，agent-service 已有此凭证 |
| 镜像策略 | 单镜像 + 选择性初始化 | 无需重构 KeepAliveSandboxManager 单例架构 |
| SKILL.md 注入 | 复用现有平台 Skill 机制 | 零代码改动，利用 agentBuilder.addSkill() 链路 |
| CLI 认证接口 | credentials.json mode 字段 | 与现有凭证管理一致，agent 无需额外参数 |
| 实现路径 | 最小可用 + 渐进抽象 | 先验证链路，再扩展 per-agent 配置 |

## 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  构建时                                                      │
│                                                              │
│  sandbox-plugins/build.sh                                   │
│    1. 交叉编译 harnax-cli → bin/harnax (linux/amd64)        │
│    2. docker build → harnax-sandbox:latest                  │
│       - /usr/local/bin/harnax                               │
│       - /opt/plugins/harnax-cli/init.sh                     │
│       - /opt/plugins/harnax-cli/SKILL.md                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  运行时                                                      │
│                                                              │
│  HarnessAgentWrapper.buildRuntimeContext()                   │
│    1. keepAliveSandboxManager.getOrCreate(sessionId)        │
│    2. IF cliPluginsEnabled:                                 │
│         sandbox.exec("/opt/plugins/harnax-cli/init.sh       │
│           '<adminUrl>' '<internalSecret>'")                 │
│    3. 注入 SandboxContext → agent 使用沙箱执行命令           │
│                                                              │
│  Agent 感知 CLI:                                             │
│    - SKILL.md 注册为平台 Skill → agent 配置时绑定            │
│    - Agent 通过 shell tool 执行 `harnax <command>`          │
└─────────────────────────────────────────────────────────────┘
```

## Section 1：沙箱镜像构建

### 目录结构

```
sandbox-plugins/                         # 项目根目录下新建
├── Dockerfile.sandbox                   # 沙箱镜像定义
├── build.sh                             # 构建脚本
├── harnax-cli/                          # harnax-cli 插件目录
│   ├── init.sh                          # 初始化脚本（认证注入）
│   ├── SKILL.md                         # 技能文档（构建时从 harnax-cli/ 复制）
│   └── bin/                             # 交叉编译输出（gitignore）
│       └── .gitkeep
```

### Dockerfile.sandbox

```dockerfile
FROM python:3.11-slim

# 基础工具（所有插件共享）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl jq git \
    && rm -rf /var/lib/apt/lists/*

# === Plugin: harnax-cli ===
COPY harnax-cli/bin/harnax /usr/local/bin/harnax
RUN chmod +x /usr/local/bin/harnax
COPY harnax-cli/init.sh /opt/plugins/harnax-cli/init.sh
COPY harnax-cli/SKILL.md /opt/plugins/harnax-cli/SKILL.md
RUN chmod +x /opt/plugins/harnax-cli/init.sh

RUN mkdir -p /workspace
WORKDIR /workspace
```

### build.sh

```bash
#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== Cross-compiling harnax-cli ==="
(cd "$PROJECT_ROOT/harnax-cli" && \
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags "-s -w" \
  -o "$SCRIPT_DIR/harnax-cli/bin/harnax" .)

echo "=== Copying SKILL.md ==="
cp "$PROJECT_ROOT/harnax-cli/SKILL.md" "$SCRIPT_DIR/harnax-cli/SKILL.md"

echo "=== Building sandbox image ==="
docker build -t harnax-sandbox:latest -f "$SCRIPT_DIR/Dockerfile.sandbox" "$SCRIPT_DIR"

echo "=== Done: harnax-sandbox:latest ==="
```

### 路径约定

| 内容 | 容器内路径 |
|------|-----------|
| CLI 二进制 | `/usr/local/bin/<binary-name>` |
| 插件初始化脚本 | `/opt/plugins/<name>/init.sh` |
| 插件技能文档 | `/opt/plugins/<name>/SKILL.md` |
| 工作目录 | `/workspace` |

## Section 2：harnax-cli 认证改造

### credentials.json 双模式

现有格式（JWT 模式）：
```json
{
  "accessToken": "eyJ...",
  "username": "admin",
  "tenantId": 1
}
```

新增 Internal Secret 模式：
```json
{
  "mode": "internal",
  "internalSecret": "xxx",
  "serverUrl": "http://harnax-admin:8080"
}
```

### CLI 代码改动

`internal/client/admin.go` — 请求构建逻辑：

```go
func (c *AdminClient) doRequest(ctx context.Context, method, path string, body any) (*Response, error) {

    // Auth header: support both JWT and Internal Secret modes
    if c.InternalSecret != "" {
        req.Header.Set("X-Internal-Secret", c.InternalSecret)
    } else if c.Token != "" {
        req.Header.Set("Authorization", "Bearer "+c.Token)
    }

}
```

`internal/config/config.go` — Credentials 结构扩展：

```go
type Credentials struct {
    // JWT mode (existing)
    AccessToken  string `json:"accessToken,omitempty"`
    RefreshToken string `json:"refreshToken,omitempty"`
    ExpiresAt    int64  `json:"expiresAt,omitempty"`
    TenantID     int64  `json:"tenantId,omitempty"`
    Username     string `json:"username,omitempty"`

    // Internal Secret mode (new)
    Mode           string `json:"mode,omitempty"`           // "" or "jwt" = JWT mode; "internal" = internal secret
    InternalSecret string `json:"internalSecret,omitempty"`
    ServerURL      string `json:"serverUrl,omitempty"`      // used in internal mode
}
```

`cmd/root.go` — newAdminClient 适配：

```go
func newAdminClient() (*client.AdminClient, error) {
    creds, err := config.LoadCredentials()
    if err != nil {
        return nil, err
    }

    var url string
    var c *client.AdminClient

    if creds.Mode == "internal" {
        // Internal secret mode: serverUrl from credentials
        url = creds.ServerURL
        if serverURL != "" { url = serverURL }  // --server-url flag override
        c = client.NewAdminClientWithSecret(url, creds.InternalSecret)
    } else {
        // JWT mode (existing behavior)
        url = serverURL
        if url == "" { url, err = config.GetServerURL(profile) }
        c = client.NewAdminClient(url, creds.AccessToken)
    }

    c.Verbose = verbose
    return c, nil
}
```

### init.sh（沙箱内执行）

```bash
#!/bin/sh
# Sandbox CLI Plugin init script
# Called by agent-service after sandbox creation
# Args: $1=adminUrl $2=internalSecret
set -e

HARNAX_DIR="$HOME/.harnax"
mkdir -p "$HARNAX_DIR"

cat > "$HARNAX_DIR/credentials.json" <<EOF
{"mode":"internal","internalSecret":"$2","serverUrl":"$1"}
EOF

chmod 600 "$HARNAX_DIR/credentials.json"
echo "[harnax-cli] initialized (internal secret mode)"
```

## Section 3：运行时注入机制

### 接口定义（harnax-harness-core）

```kotlin
package com.agnetix.harnax.harness.sandbox.plugin

import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox

/**
 * Initializes CLI plugins inside a sandbox container.
 * Called after sandbox creation to inject authentication credentials.
 */
interface SandboxPluginInitializer {

    /** Plugin name for logging */
    val pluginName: String

    /**
     * Initialize the plugin inside the sandbox.
     * Implementations should be idempotent (safe to call multiple times).
     *
     * @param sandbox the running Docker sandbox
     * @param adminUrl admin service URL accessible from within the container
     * @param internalSecret service-level authentication secret
     * @return true if initialization succeeded
     */
    fun initialize(sandbox: DockerSandbox, adminUrl: String, internalSecret: String): Boolean
}
```

### 默认实现

```kotlin
package com.agnetix.harnax.harness.sandbox.plugin

class HarnaxCliPluginInitializer : SandboxPluginInitializer {

    private val log = LoggerFactory.getLogger(HarnaxCliPluginInitializer::class.java)

    override val pluginName: String = "harnax-cli"

    override fun initialize(sandbox: DockerSandbox, adminUrl: String, internalSecret: String): Boolean {
        val cmd = "/opt/plugins/harnax-cli/init.sh '$adminUrl' '$internalSecret'"
        return try {
            val result = sandbox.exec(null, cmd, 10)
            if (result.exitCode() == 0) {
                log.info("[CliPlugin] harnax-cli initialized successfully")
                true
            } else {
                log.warn("[CliPlugin] harnax-cli init failed: exit={}, stderr={}", result.exitCode(), result.stderr())
                false
            }
        } catch (e: Exception) {
            log.warn("[CliPlugin] harnax-cli init exception: {}", e.message)
            false
        }
    }
}
```

### 注入点（HarnessAgentWrapper）

在 `buildRuntimeContext()` 中，sandbox 创建后注入：

```kotlin
private fun buildRuntimeContext(): RuntimeContextResult {
    val ctxBuilder = RuntimeContext.builder()
        .sessionId(sessionId)
        .userId(userId ?: "")

    var keepAliveSandbox: Sandbox? = null
    if (keepAliveSandboxManager != null) {
        val sandbox = keepAliveSandboxManager.getOrCreate(sessionId, WorkspaceSpec(), keepAliveSnapshotSpec)
        keepAliveSandbox = sandbox

        // === CLI Plugin initialization ===
        if (pluginInitializers.isNotEmpty()) {
            pluginInitializers.forEach { initializer ->
                initializer.initialize(
                    sandbox as DockerSandbox,
                    pluginAdminUrl,        // from SandboxConfig or application.yml
                    pluginInternalSecret,  // from application.yml (admin.internal-api.secret)
                )
            }
        }

        // ... existing SandboxContext injection ...
    }

    return RuntimeContextResult(ctxBuilder.build(), keepAliveSandbox)
}
```

### HarnessAgentWrapper 构造参数扩展

```kotlin
data class HarnessAgentWrapper(
    // ... existing fields ...
    val pluginInitializers: List<SandboxPluginInitializer> = emptyList(),
    val pluginAdminUrl: String = "",
    val pluginInternalSecret: String = "",
)
```

### 幂等性保证

`init.sh` 是幂等的（覆盖写入 credentials.json），因此即使 `buildRuntimeContext()` 在每次 call 时都执行 init，也不会产生副作用。开销仅为一次 `docker exec`（~100ms），可接受。

后期优化：可在 wrapper 中加 `@Volatile private var pluginsInitialized = false` 标记，仅首次执行。

## Section 4：配置与开关

### SandboxConfig 扩展

```kotlin
data class SandboxConfig(
    val enabled: Boolean = false,
    val image: String = "python:3.11-slim",
    val workspaceRoot: String = "/workspace",
    val isolationScope: IsolationScope = IsolationScope.SESSION,
    val keepAlive: Boolean = false,
    val network: String? = null,
    // === New: CLI Plugin config ===
    val pluginImage: String = "harnax-sandbox:latest",
    val cliPluginsEnabled: Boolean = false,
)
```

### application.yml 配置

```yaml
harness:
  sandbox:
    enabled: true
    image: python:3.11-slim
    plugin-image: harnax-sandbox:latest
    cli-plugins-enabled: true          # 全局开关
    keep-alive: true
    network: docker-new_harnax-network

admin:
  service:
    url: http://harnax-admin:8080      # 容器内可达地址
  internal-api:
    secret: ${ADMIN_INTERNAL_SECRET}   # 已有配置，复用
```

### 镜像选择逻辑

在 `HarnessAgentLauncher` 或 `HarnessAutoConfiguration` 中：

```kotlin
// 当 cliPluginsEnabled=true 时，使用含插件的镜像
val effectiveImage = if (harnessConfig.sandbox.cliPluginsEnabled) {
    harnessConfig.sandbox.pluginImage
} else {
    harnessConfig.sandbox.image
}
```

KeepAliveSandboxManager 使用 `effectiveImage` 构造。

## Section 5：Admin 侧 CLI 插件管理

### 数据模型

```sql
-- V8__add_cli_plugin_tables.sql

CREATE TABLE IF NOT EXISTS cli_plugin (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT       DEFAULT 1,
    name            VARCHAR(100) NOT NULL COMMENT 'Plugin identifier (e.g. harnax-cli)',
    display_name    VARCHAR(200) DEFAULT NULL COMMENT 'Display name (EN)',
    display_name_zh VARCHAR(200) DEFAULT NULL COMMENT 'Display name (ZH)',
    description     TEXT         DEFAULT NULL COMMENT 'Plugin description',
    version         VARCHAR(50)  DEFAULT NULL COMMENT 'CLI binary version',
    type            VARCHAR(20)  DEFAULT 'SYSTEM' COMMENT 'SYSTEM / CUSTOM',
    binary_path     VARCHAR(500) DEFAULT NULL COMMENT 'Container binary path',
    init_script     VARCHAR(500) DEFAULT NULL COMMENT 'Container init script path',
    skill_doc_path  VARCHAR(500) DEFAULT NULL COMMENT 'Container SKILL.md path',
    health_check    VARCHAR(500) DEFAULT NULL COMMENT 'Health check command',
    status          TINYINT      DEFAULT 1 COMMENT '0:disabled 1:enabled',
    creator         VARCHAR(50)  DEFAULT 'SYSTEM',
    active          TINYINT      DEFAULT 1,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cli_plugin_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_cli_plugin_binding (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT NOT NULL,
    plugin_id   BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_cli_binding_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 自动注册（CliPluginAutoRegistrar）

类似 `BuiltinToolAutoRegistrar`，admin 启动时自动 upsert 系统集成 CLI 插件：

```kotlin
@Component
class CliPluginAutoRegistrar(
    private val cliPluginMapper: CliPluginMapper,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun syncSystemCliPlugins() {
        // harnax-cli: 系统内置
        upsertPlugin(
            name = "harnax-cli",
            displayName = "Harnax CLI",
            displayNameZh = "Harnax 命令行",
            description = "Platform management CLI for agent/session/model/tool operations",
            binaryPath = "/usr/local/bin/harnax",
            initScript = "/opt/plugins/harnax-cli/init.sh",
            skillDocPath = "/opt/plugins/harnax-cli/SKILL.md",
            healthCheck = "harnax --version",
        )
    }
}
```

同步策略（与 BuiltinToolAutoRegistrar 一致）：
- 新插件：insert，status=1
- 已有：仅更新元数据（description、version、paths）
- status 不覆盖（admin 可手动禁用）
- 不自动删除

### Admin API

```
GET  /api/admin/cli-plugins           # 列表（支持 type 过滤）
GET  /api/admin/cli-plugins/{id}      # 详情
PUT  /api/admin/cli-plugins/toggle/{id}?status=0|1  # 启用/禁用
```

### Agent 绑定

Agent 创建/编辑时可选择绑定 CLI 插件（类似工具绑定）。
`InternalApiController.getAgentSpec()` 返回 `cliPluginDetails` 字段。

### SKILL.md 自动关联

当 agent 绑定了 CLI 插件时，agent-service 在构建 agent 时自动从沙箱镜像中读取对应的 SKILL.md 并注入为 Skill。
初期简化：SKILL.md 仍通过平台 Skill 机制手动注册并绑定。

## Section 6：前端 CLI 管理页面

### 路由

```
/context/cli-plugin   →   ./cli-plugin
```

放在 `context` 菜单组下（与 tool、mcp、skill 并列）。

### 页面结构（仿 tool/index.tsx）

```
PageContainer: "CLI 插件管理"
└── Tabs
    ├── Tab "系统集成" (icon: BuildOutlined)
    │   ├── Input.Search (搜索)
    │   └── Table
    │       ├── 名称 (displayName + name)
    │       ├── 描述
    │       ├── 版本
    │       ├── 健康检查
    │       └── 状态 (Switch 启用/禁用)
    └── Tab "自定义" (icon: KeyOutlined)
        └── Result: "Coming soon"
```

### 前端 API

```typescript
// services/ant-design-pro/cliPlugin.ts
export async function getCliPlugins(params?: { type?: string }) {
  return request('/api/admin/cli-plugins', { method: 'GET', params });
}
export async function toggleCliPlugin(id: number, status: number) {
  return request(`/api/admin/cli-plugins/toggle/${id}`, { method: 'PUT', params: { status } });
}
```

## Section 7：后期扩展路径

### Phase 2：Per-Agent 选择性启用

```sql
ALTER TABLE agent ADD COLUMN cli_plugins text DEFAULT NULL
  COMMENT 'CLI plugins JSON: [{"name":"harnax-cli","enabled":true}]';
```

`AgentSpecInfoResponse` 新增 `cliPlugins` 字段，`AgentSpecResolver` 解析后传递给 wrapper。

### Phase 3：通用 Plugin Descriptor

引入 `plugin.yaml` 规范，`SandboxPluginInitializer` 改为从 manifest 动态发现：

```kotlin
class DynamicCliPluginInitializer(
    private val descriptor: CliPluginDescriptor,  // parsed from plugin.yaml
) : SandboxPluginInitializer { ... }
```

### Phase 4：Session 级覆盖

Session config 中增加 `cliPlugins` 字段，允许同一 Agent 的不同会话禁用/启用特定插件。

## 涉及模块

| 模块 | 改动内容 |
|------|----------|
| `sandbox-plugins/`（新建） | Dockerfile、build.sh、init.sh、SKILL.md |
| `harnax-cli/` | credentials 双模式、client 认证头适配 |
| `harnax-harness-core` | SandboxPluginInitializer 接口 + 实现、SandboxConfig 扩展、HarnessAgentWrapper 注入 |
| `harnax-agent-service` | HarnessAutoConfiguration 配置绑定、镜像选择逻辑 |
| `harnax-admin` | CliPlugin 实体、Mapper、Service、Controller、AutoRegistrar、Flyway V8 |
| `harnax-entity` | CliPlugin 实体类、CliPluginMapper |
| `harnax-webui` | CLI 插件管理页面、路由、国际化、API 服务 |

## 约束与注意事项

1. 沙箱内 shell 为 `sh`（dash），init.sh 必须 POSIX 兼容，不使用 bash 特性
2. `admin.service.url` 必须是容器网络内可达地址（如 Docker 服务名），非 localhost
3. Internal Secret 与 admin 侧的 `InternalAuthorizationInterceptor` 校验逻辑一致
4. 插件二进制为静态编译（CGO_ENABLED=0），无 glibc 依赖，兼容 debian-slim 基础镜像

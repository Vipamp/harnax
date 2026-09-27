# Harnax MCP 服务管理设计

> 本文是 MCP 管理域的完整设计文档：数据模型、Admin 管理面（CRUD、密钥、授权服务器发现、按用户授权、令牌换发）、智能体绑定与配置下发、运行侧装配，以及这个域认可的边界。
>
> 英文版本见 [mcp-management.en-US.md](./mcp-management.en-US.md)。

## 1. 概述与分层

MCP（Model Context Protocol）服务是 Agent 的外部工具来源之一，与内置工具并列。两者只共享分层方式，不共享生命周期：MCP 服务全部由管理面（页面与 API）维护，增删改查就是它的完整生命周期，没有任何启动期的自动收敛。

| 模块 | 在这个域里负责什么 |
|------|------------------|
| `harnax-entity` | `McpServer` / `AgentMcpBinding` / 三张 OAuth 表的实体、Mapper 与 XML，以及跨服务线格式 `McpDetailDto`、`McpAccessTokenResponse` |
| `harnax-admin` | MCP 服务 CRUD、密钥加密与掩码、认证方式校验、授权服务器发现与客户端登记、按用户授权与令牌换发、连通性探测、绑定解析与配置下发 |
| `harnax-common` | 跨模块解密 SPI `McpConfigDecryptor` 的定义 |
| `harnax-agent/harnax-harness-core` | 运行时装配：按智能体配置逐台建 MCP 客户端、stdio 二次防御、客户端随 agent 一起释放 |
| `harnax-agent/harnax-agent-utils` | 客户端构建 `McpHelper` 与三种传输配置 `McpConfig`，令牌回调接口 `McpAccessTokenSource` |
| `harnax-agent/harnax-agent-service` | 配置适配器（从 admin 下发的 spec 取 MCP 配置）与令牌源实现（向 admin 换 token 并缓存） |
| `harnax-webui` | MCP 列表 / 详情 / 表单，OAuth 面板与授权落地页 |

两条链路构成这个域的主线：

- **按用户授权**：授权服务器发现与客户端登记由管理员各做一次（每个 (租户, 授权服务器) 一条注册），同意由每个用户对自己的每台 OAuth 类服务各做一次；access / refresh token 只以 AES 密文存在 admin 侧的 `mcp_user_credential`。
- **运行侧注入**：运行侧（agent-service / harness-core）不持有 AES 密钥、不持有任何 refresh token，只知道「这台服务走 OAuth」；每次出站请求按 (会话, 服务) 向 admin 换一枚 access token。令牌身份在建 agent 实例时确定并绑在令牌源上，此后这个实例服务的任何调用都只用这一种身份。

## 2. 数据模型

表结构以 `harnax-admin/src/main/resources/db/migration/` 下的迁移脚本为准，叠加到该目录下编号最高的那一个为止。列名与缺省值取迁移叠加后的最终形态。

### 2.1 mcp_server（MCP 服务主表）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`

| 列 | 形态与含义 |
|----|-----------|
| `id` | 自增主键 |
| `tenant_id` | 归属租户，缺省 1；创建时由请求上下文写入（`TenantResolver`） |
| `name` | 服务名，`varchar(100)`，租户内唯一（见 `active_name`） |
| `description` | 描述，`text` |
| `type` | 传输类型：`stdio` / `sse` / `streamablehttp`，列是 `varchar(20) NOT NULL` 且**没有 DEFAULT**，`streamablehttp` 只作为实体属性的默认值存在 |
| `command` | 执行命令，`varchar(500)`，只有 stdio 类型使用 |
| `url` | 服务地址，`varchar(500)`，`sse` / `streamablehttp` 使用 |
| `auth_type` | 上游认证方式：`NONE` / `STATIC_HEADER` / `BASIC` / `OAUTH2`，`NOT NULL DEFAULT 'NONE'` |
| `oauth_config` | OAuth 非敏感配置 JSON，`text`，可空；形态由 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt` 决定 |
| `status` | 启用状态：0 禁用 / 1 启用 |
| `is_public` | 可见性：0 私有 / 1 公开，列缺省 1 |
| `creator` | 创建人（用户名） |
| `active` | 逻辑删除：0 已删除 / 1 有效 |
| `headers` | HTTP 请求头条目 JSON 数组，`secret=true` 条目的 value 以 AES-256-GCM 密文存储 |
| `env_params` | 环境参数条目 JSON 数组，stdio 进程的环境变量，secret 条目同样密文存储 |
| `active_name` | `VARCHAR(100) GENERATED ALWAYS AS (IF(active = 1, name, NULL)) VIRTUAL` |
| `create_time` / `update_time` | 时间戳；`update_time` 带 `ON UPDATE CURRENT_TIMESTAMP`，更新路径另外显式刷新 |

约束与口径：

- 主键 `PRIMARY KEY (id)`；`UNIQUE KEY uk_mcp_server_tenant_active_name (tenant_id, active_name)`。唯一性只在租户内、只覆盖有效行——生成列在 `active = 0` 时变 NULL，MySQL 的唯一索引忽略 NULL，因此删掉一台服务后同名可以再用。
- `headers` / `oauth_config` / `env_params` 这类 TEXT 列没有单列索引。
- `auth_type` 有意不回填：`NONE` 与 `STATIC_HEADER` 走同一条代码路径（都读 `headers`），差别只是给管理员一个可读标注，因此一行带着 `headers` 值并不构成任一意图的证据（`headers` 也放路由类头），标成哪个由管理员显式决定。
- `McpServer` 实体的属性全部可空带默认值（`type` 默认 `streamablehttp`、`authType` 默认 `NONE`、`status` / `isPublic` / `active` 默认 1）。其中 `type` 的缺省**只存在于实体属性**：那一列是 `NOT NULL` 而没有 DEFAULT，所以值一定要由走实体的写入路径给出；`authType` 的 `NONE` 则与 `V25` 给的列缺省同义，两边任一都读得出同一个意思。

### 2.2 agent_mcp_binding（智能体-MCP 绑定）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentMcpBinding.kt`

| 列 | 含义 |
|----|------|
| `id` | 自增主键 |
| `agent_id` | 指向 `agent.id` |
| `mcp_id` | 指向 `mcp_server.id` |
| `env_bindings` | 环境变量绑定 JSON 快照（`envKey` + `envVarId` 或 `customValue`） |
| `create_time` / `update_time` | 时间戳 |

约束：`UNIQUE KEY uk_agent_mcp_binding_agent_id_mcp_id (agent_id, mcp_id)`，`agent_id` 是它的最左前缀。应用层 `saveMcpBindings` 在写入前按 `mcpId` 去重，一次保存重写全集。

这张表上没有「跳过缺失配置」之类的开关：配置解析不到时运行侧一律告警并跳过，与工具侧同口径；`env_bindings` 是这个域里唯一的按绑定配置项（见「环境参数与密钥的两条通道」）。

### 2.3 mcp_oauth_client（租户 × 授权服务器的客户端注册）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpOauthClient.kt`

一行代表「这个租户在这台授权服务器上的客户端身份」，同一授权服务器后面的多台 MCP 服务共用它。

| 列 | 含义 |
|----|------|
| `id` / `tenant_id` | 主键与租户 |
| `issuer` | 授权服务器标识，`varchar(255) COLLATE utf8mb4_bin` |
| `client_id` | `varchar(255) NOT NULL`；发现落库的新行写空串，含义是「端点已知、客户端未登记」 |
| `client_secret_enc` | 客户端密钥密文，`text`；公开客户端（仅 PKCE）为 NULL |
| `registration_source` | `MANUAL` / `DCR` / `ID_METADATA`，当前写入路径只有 `MANUAL` |
| `authorization_endpoint` / `token_endpoint` / `registration_endpoint` / `revocation_endpoint` | 发现快照，`varchar(500)`；`registration_endpoint` 为 NULL 表示不支持 DCR，`revocation_endpoint` 为 NULL 表示撤销只清本地 |
| `scopes_supported` | 发现快照，逗号分隔 |
| `callback_url` | 登记在授权服务器上的精确 `redirect_uri`，`varchar(500) COLLATE utf8mb4_bin` |
| `active` + `active_client_id` | `active_client_id VARCHAR(255) GENERATED ALWAYS AS (IF(active = 1, client_id, NULL)) VIRTUAL` |
| `creator` / `create_time` / `update_time` | 常规列 |

约束：`UNIQUE KEY uk_mcp_oauth_client_tenant_issuer_client (tenant_id, issuer, active_client_id)`、`KEY idx_mcp_oauth_client_tenant_issuer (tenant_id, issuer)`。两个 URL 身份列用 `utf8mb4_bin`，因为 `iss` 与 `redirect_uri` 按规范是逐字节比较的字符串；大小写不敏感的比较会让一台服务器复用另一台的注册。

`selectByTenantAndIssuer` 带 `ORDER BY id LIMIT 1`，让并发发现落在同一行上，而不是为同一授权服务器注册两个客户端。这张表不随 MCP 服务删除而清理（见「停用与删除是两件事」）。

### 2.4 mcp_user_credential（用户 × MCP 服务的授权结果）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpUserCredential.kt`

| 列 | 含义 |
|----|------|
| `id` / `tenant_id` | 主键与租户 |
| `user_id` | `sys_user.id`，不是用户名：改名既不会孤立授权，也不会把它转交给接手这个名字的人 |
| `mcp_id` | `mcp_server.id` |
| `access_token_enc` / `refresh_token_enc` | 两个 token 的 AES 密文；撤销时两列写回 NULL，refresh 密文从不离开 admin 进程 |
| `access_expires_at` | access token 过期时间；过期视作「没有可用令牌」 |
| `scopes` | 实际授予的 scope，可以窄于请求的 |
| `status` | `ACTIVE` / `NEEDS_CONSENT` / `REVOKED` |
| `last_error` | 抹掉敏感片段后的失败原因，不含 token 片段 |
| `last_refreshed_at` | 最近一次刷新成功时间 |
| `create_time` / `update_time` | 时间戳 |

约束：`UNIQUE KEY uk_mcp_user_credential_tenant_user_mcp (tenant_id, user_id, mcp_id)`、`KEY idx_mcp_user_credential_mcp (mcp_id)`。

这张表没有逻辑删除列：授权行就地更新（撤销清密文并置 `REVOKED`，用户再次同意时同一行复用）。就地改写之外只有两条按外部键整批物理删的路：删除 MCP 服务时按 `mcp_id` 清掉这台服务的全部授权（`deleteByMcpId`），删除用户账号时按 `user_id` 清掉他在任何服务上的授权（`deleteByUserId`，调用点是 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImpl.kt` 的删除路径——那一边的 `sys_user` 行只是逻辑删除，而这些密文的归属人已签不进去、也没有页面点得到撤销）。软删除的行会继续占住 `(tenant_id, user_id, mcp_id)`，把重新授权的插入挡掉。

### 2.5 mcp_call_log（只追加的审计）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt`

`action` 取 `ISSUE` / `REFRESH` / `REVOKE` / `CALL`，`outcome` 取 `OK` / `AUTH_FAILED` / `NEEDS_CONSENT` / `ERROR`；行上有租户、用户（会话属主解析不出来时为 NULL）、MCP 服务、会话、工具名（令牌签发时为 NULL）、耗时。

这张表刻意不记请求体、响应体与 `Authorization`：读它的人不一定是令牌属主。`McpCallLogMapper` 只提供 `insert`——append-only 的口径就体现在没有别的方法可调。

写入方是 `McpOAuthUserServiceImpl` 的一个私有 `audit(...)`，每次授权决定落一行：换发拿到可用令牌（`ISSUE` / `OK`）、判定需要重新同意（`ISSUE` / `NEEDS_CONSENT`）、刷新成功（`REFRESH` / `OK`）、刷新遇到可重试故障（`REFRESH` / `ERROR`）、用户撤销（`REVOKE` / `OK`）。从管理页做出的决定（撤销）`session_id` 为 NULL，会话换发的带上那个会话。写审计失败只记一条 warn，不会让那次换发或撤销失败。

### 2.6 跨服务线格式

- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`：admin 内部 API 下发的完整 MCP 配置，字段为 `id` / `name` / `description` / `type` / `command` / `url` / `authType` / `headers` / `envParams` / `status`。`headers` 与 `envParams` 是**下发前解密的明文 JSON 对象**（`{"KEY":"value"}` 形态）。`authType` 必须随之下发，否则运行时分不清 OAuth 服务与静态头服务；`oauthConfig` 不下发——其中的 `authorizationServer` / `scopes` 只服务于管理面，运行时只需要知道自己该走 OAuth 这条分支。
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpAccessTokenResponse.kt`：`accessToken` / `tokenType` / `expiresAtEpochSecond`。放在 `harnax-entity` 而不是 admin 的 dto 包，因为它是两个服务之间的线格式。这是唯一一个携带令牌明文出 admin 的响应体，只回答内部 API；`expiresAtEpochSecond` 用 Unix 秒而不是日期时间，因为两侧各按自己的时钟判断且不一定同一时区；为 NULL 表示授权服务器没说有效期。

## 3. Admin 管理面

实现入口：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt`，两者共用前缀 `/api/admin/mcp`、同一套 `ResultVo` 返回体与 `ApiErrors` 错误口径，全部接口都要过 JWT。

### 3.1 MCP 服务 CRUD 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/admin/mcp/page` | GET | 分页查询，支持 `keyword` / `status` / `type` 过滤，`pageSize` 取 1..1000 |
| `/api/admin/mcp/{id}` | GET | 详情，secret 字段掩码回显 |
| `/api/admin/mcp` | POST | 创建 |
| `/api/admin/mcp/update/{id}` | PUT | 更新，省略的字段保持原值 |
| `/api/admin/mcp/toggle/{id}` | PUT | 启用 / 禁用 |
| `/api/admin/mcp/{id}/related-agents` | GET | 引用该服务的智能体列表 |
| `/api/admin/mcp/{id}` | DELETE | 逻辑删除并级联清理绑定与用户授权 |
| `/api/admin/mcp/{id}/connectivity-test` | POST | 连通性测试 |
| `/api/admin/mcp/{id}/list_tools` | GET | 实时连接拉取工具列表 |

### 3.2 OAuth 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/admin/mcp/{id}/oauth/discover` | POST | 发现授权服务器并落库端点 |
| `/api/admin/mcp/{id}/oauth/client` | POST | 登记 / 修改该授权服务器上的客户端凭据 |
| `/api/admin/mcp/{id}/oauth/authorize-url` | GET | 为当前用户生成授权跳转地址，可带 `scope` 覆盖 |
| `/api/admin/mcp/oauth/exchange` | POST | 换票：接收前端落地页送来的 `code` / `state` / `error`，凭据写给调用者本人 |
| `/api/admin/mcp/{id}/oauth/status` | GET | 当前用户在这台服务上的授权状态 |
| `/api/admin/mcp/{id}/oauth/revoke` | POST | 撤销当前用户的授权 |
| `/api/admin/internal/mcp/access-token` | POST | 运行侧换发：入参只有 `sessionId` + `mcpId` |

内部换发接口挂在 `InternalApiController`，在 `InternalApiAuthFilter` 之后，浏览器打不到。它的入参**没有用户字段**：会话是运行侧唯一持有的身份，属主由 admin 反查，因此调用方无法点名要花谁的授权。

### 3.3 单行访问的租户与可见性守卫

`McpServerServiceImpl` 提供两个读取口：

- `getMcpServer(id)`：取行后比对当前租户（`TenantResolver.resolve(jwtUtil)`），不属于当前租户就当不存在。列表查询按租户过滤，如果单行读不校验，这个过滤就成了装饰——猜到自增 id 就能打开、改删别租户的行。仓内不设 MyBatis 租户拦截器，`selectById` 的 SQL 里也没有租户条件，所以这一层必须写在服务里。
- `getVisibleMcpServer(id)`：在租户守卫之上再加一条 `is_public = 1 OR creator = 当前用户名`，与列表的可见性口径一致。写路径与探测路径（更新、启停、删除、`list_tools` / 连通性测试、以及全部 OAuth 入口）都从它进入，拒绝时统一回答「MCP server not found」。

只做名字解析的读路径（智能体表单渲染绑定上的服务名、会话能力列表）留在 `getMcpServer`：那里展示的是「这个智能体已经配了什么」，在页面上藏掉别人的私有服务只会让一条有效绑定从表单里静默消失，而不是拒绝一次操作。

列表查询 `selectMcpServerList` 同时接受当前用户名与租户 id：`is_public = 1 OR creator = #{creator}`，再叠加 `tenant_id` 过滤。也就是说公开含义是**租户内共享**。

### 3.4 创建与更新

创建（`createMcpServer`）按顺序做：

1. 在当前租户内按名查重（`selectByName(name, tenantId)`），命中抛 `BizException`；数据库侧由 `uk_mcp_server_tenant_active_name` 兜底，并发撞索引时 `ApiErrors` 按索引名映射成人话。
2. `type` 相关：stdio 准入闸门（见「配置开关与部署参数」）、`validateTypeAndFields`（stdio 必填 `command`，`sse` / `streamablehttp` 必填 `url`，其他取值拒收）。
3. 认证方式：`resolveAuthType`（缺省 `NONE`，只收 `McpAuthTypes.SUPPORTED`）、`validateAuthType`（`OAUTH2` 不许配 stdio）、`writeOAuthConfig`（见「认证方式与 OAuth 配置列」）。
4. 密钥序列化：`headers` 走 `SecretFieldEncryptor.serializeWithEncryption`；`envParams` 只有在 `type` 是 stdio 时才写，否则写 NULL——只有会拉起进程的传输有东西放进进程环境，给网络行存一份只会留下谁也读不到的值。
5. `tenantId` 取当前租户，`creator` 取当前用户名，`status` / `isPublic` 请求缺省为 1。

更新（`updateMcpServer`）的语义点：

- 先过 `requireVisibleServer(id)`，并在任何字段被覆盖之前记下这行原本是不是 `OAUTH2`——方法末尾要判断「这次请求是否把 OAuth 关掉了」，而那时 `auth_type` 已经被改写过。
- `name` / `description` / `type` / `command` / `url` / `authType` / `isPublic` / `status` / `oauthConfig` / `headers` / `envParams` 全部可空，**省略即保持原值**；`name` 给了空串会被拒。
- 改 `type` 时清掉上一传输留下的参数：切到 stdio 会把 `url` 写成空串、`headers` 置 NULL；切到网络传输会把 `command` 写成空串、`envParams` 置 NULL。`updateById` 写整行，所以这些值确实落库。留着会让详情页显示一个该传输根本不会读到的字段。
- 换入 `oauthConfig` 时，请求没带 `authorizationServer` 而库里已有（发现写回的）就保留库里那个；只改一次 scope 不应该注销掉整次发现结果。显式给了一个不可用的值仍然拒。
- `authType` 一旦不是 `OAUTH2`，`oauthConfig` 清成 NULL，不给一个不做 OAuth 的服务留下会被继续渲染的配置。
- 校验跑在**合并后的行**上、且在 `updateById` 之前：把 `type` 改成 stdio 会撞同一道 `validateAuthType` 与 stdio 准入闸门，`BASIC` 这类值同样进不来。
- `update_time` 显式刷新（`updateById` 写实体上的这一列，留在载入值会让列表的更新时间排序冻结）。
- 更新成功后按情况清理用户授权，见「停用与删除是两件事」。

### 3.5 密钥存储与掩码回写

加密基础设施：

- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`：基于 `AesUtil`（AES-256-GCM），实现 `harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt` 定义的跨模块解密 SPI。
- 条目形态：`headers` 是 `McpConfigEntry`（`key` / `value` / `secret`），`envParams` 是 `ToolEnvParamEntry`（`envParamName` / `description` / `required` / `secret` / `defaultValue`）。后者沿用工具参数条目的形状，值写在 `defaultValue` 里，因此 `secret` 条目的加密、掩码回显与回写识别都作用在 `defaultValue` 这一项上（`SecretFieldEncryptor.storedEnvSecrets` 按 `envParamName` 建索引取回密文）。两种条目都只有 `secret = true` 的项加密。

回显（`McpServerResponse.fromEntity`）：解密 secret 条目后掩码——长度大于 7 显示「前 3 位 + `****` + 后 4 位」，其余以及解密失败显示 `******`。前端不接触明文密钥。

掩码是双向约定。既然前端只看到掩码，未改动的字段原样提交回来时也是掩码，写库侧必须认得：`serializeWithEncryption` / `serializeToolEnvParams` 用 `value.contains("****")` 判定（`******` 本身含 `****`，两种形态一起覆盖），命中就按 `key` / `envParamName` 从**本行现有的那列 JSON** 里取回密文原样保留；取不到（条目被改名，或库里就没有）抛 `BizException` 要求重填，绝不把掩码当明文加密。

单列密钥（`mcp_oauth_client.client_secret_enc`）同样要回显掩码，走同一套判定的提公版本 `SecretFieldEncryptor.resolveSecret(provided, storedEncrypted)`，三态各有含义：`null` 保持库中原值；掩码沿用库中密文（库里没有密文就要求重填）；空白串显式清空（公开客户端 + PKCE 的正常形态）。

### 3.6 认证方式与 OAuth 配置列

`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt` 是常量真源，两个进程都读它：admin 决定存什么、下发什么，运行侧按它分支选择静态头还是按用户令牌。

- `NONE`：无上游凭证，行为等同「静态头为空」。
- `STATIC_HEADER`：`headers` 那一列，一份整租户共用的静态凭证，只是多一个可读标注。
- `BASIC`：有列、有常量，运行时没有对应分支，因此管理侧拒收，报错文案直说它「stored by V25 but not wired into the runtime yet」。
- `OAUTH2`：按用户的授权，运行时按 (服务, 人) 换令牌。

拒收未知值而不当作 `NONE` 处理，理由是**入库即下发**：运行时收到认不得的分支比收到 `NONE` 更难查。

`oauthConfig` 由 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt` 序列化，字段为 `authorizationServer`（留空则由 MCP 服务自身的 RFC 9728 元数据发现）、`scopes`（默认空列表）、`audience`（为要求该参数的授权服务器保留）、`resourceIndicator`（默认 true，决定是否按 RFC 8707 带 `resource`，让令牌绑到这台 MCP 服务）。它做成类型化 DTO 而不是自由 JSON：客户端密钥与 token 在这上面没有字段可写，才不会被误写进这列——这列会以明文回给前端表单。`SecretFieldEncryptor` 不碰这一列，因此写入路径对「非 OAuth 服务却带配置」是报错而不是静默丢弃；`authorizationServer` 给了值就必须是可请求的 http(s) 地址（`URI.create(trim())` 解析、scheme 为 http/https、host 非空），因为发现流程会去请求它。

`OAUTH2` 与 `type = stdio` 互斥：OAuth 的令牌挂在 HTTP 请求头上，stdio 没有请求可挂，配错了只会静默地以未认证方式连出去。

### 3.7 授权服务器发现与客户端登记

实现：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt`，出站请求集中在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/RemoteJsonFetcher.kt`。这一层做的是「每个 (租户, 授权服务器) 一次」的配置动作，不换取任何 token。

1. **准入门槛**：两个接口都先经 `McpServerService.getVisibleMcpServer(id)`（租户与可见性守卫都在那里），再要求 `authType == OAUTH2` 且 `url` 是非空 http(s)。把 `authType` 选成 OAuth 只是让这两个接口可用，服务本身仍然按 `headers` 连接。
2. **issuer 解析顺序**（全部失败才报错）：① `oauthConfig.authorizationServer`（管理员手填，来源标 `CONFIG`）；② 从 MCP 服务地址推导 protected-resource 元数据，候选包括路径插入式 `origin/.well-known/oauth-protected-resource/<path>`、路径后缀式 `origin/<path>/.well-known/oauth-protected-resource`、主机根；③ 直接 GET 一次 MCP 地址，从响应的 `WWW-Authenticate` 里取 `resource_metadata` 指针（带引号与不带引号都读，多个头会拼回来）。取 `authorization_servers` 的第一个，多于一个时记日志。
3. **issuer 的严格校验**：先 `trim()` 再判，比一般 URL 更严——不许带 userinfo、query、fragment（这三段不会进元数据地址，存下来就是一个谁也确认不了的值）；结尾斜杠由 `normalizeIssuer` 去掉，因为注册按字节匹配，留斜杠等于凭空多出一台授权服务器。手填值与文档值过同一道校验，并且都对目标列宽度判长（`issuer` 255、其余 URL 500）。
4. **AS 元数据候选**：`.well-known/oauth-authorization-server` 与 `.well-known/openid-configuration` 各按插入式 / 后缀式试（OIDC 式的那一个放最后试，它是授权服务器与 OIDC 库实际提供的文档地址）。必须同时有 `authorization_endpoint` 与 `token_endpoint` 才算命中，且这两个值本身也要过 http(s) 与列宽校验——它们是要跳转、也要 POST `code` 与 `client_secret` 的地址。`registration_endpoint` / `revocation_endpoint` 这类可选端点遇到不可用值丢弃并记 warn，缺一个可选能力不算失败。
5. **issuer 一致性**：文档声明的 `issuer` 与被查的 issuer 精确不等就拒绝（RFC 8414 要求相等，否则一切换 token 就失败在 issuer 校验上）。文档不声明 issuer 时按来源区分：手填的接受（有人背书，也是唯一逃生口），文档广告来的拒绝。
6. **失败必须响**：所有候选的失败原因拼进一条 `BizException` 消息并截断长度，报错里的地址一律先经 `redactUrl` 抹掉 userinfo。没有任何一条路会降级成静态头继续跑——半发现的服务器如果看起来算「配好了」，运行时就可能拿未认证的请求替某个用户去调外部系统。
7. **一个 (租户, issuer) 一行**：`selectByTenantAndIssuer` 命中就只刷新端点，`client_id` / `client_secret_enc` / `callback_url` 从载入的那行带过去（`updateById` 的 SET 是无条件的，不携带等于下次发现顺手把客户端注销掉）。没命中才插入，此时 `client_id` 写空串。可选端点发现到 null 也照写 null——那正是「授权服务器撤掉了 DCR」需要被记下来的时刻。
8. **issuer 回写只动一列**：issuer 不是从 `oauthConfig` 来的，发现成功后用 `McpServerMapper.updateOAuthConfig(id, json)` 把结果写回 `mcp_server.oauth_config`（外加 `update_time`）。这里不写整行——整行覆盖会拿几分钟前读出的值盖掉别人正在做的编辑。
9. **callback_url 缺省**：新行写 `${app.frontend-base-url}/mcp/oauth/callback`，那是**前端路由**（授权落地页）；`app.frontend-base-url` 未配置时退回 `app.base-url`。授权服务器按字节比对的就是这一列存着的值，改环境变量不会让已有行跟着变，登记过的那条要重新保存一次。`McpOAuthClientRequest.callbackUrl` 可显式覆盖，覆盖前过 http(s) 与列宽校验。
10. **登记客户端**：`client_id` 在任何写入之前校验非空（不留半行）；`clientSecret` 走 `resolveSecret` 三态；`registration_source` 写 `MANUAL`。库里那行还没有端点时补一次发现——端点是真正要跳过去的地址，不允许空着；这条路径会对库里存的 issuer 重跑一遍校验，因为它就是要往那个地址挂 `client_secret`。
11. **`oauth_config` 读不出来就中止**：空列是「还没配」，非空却解析不出来是数据漂移，而发现会重写这一列。按默认值继续的后果是把管理员填的 scopes 覆盖成空列表还报「发现成功」。
12. **回显不带密钥**：`McpOAuthDiscoveryResponse` 只回 `clientSecretPresent` 布尔，另回 `issuerSource`（说清 issuer 是发现的还是填的）与 `unknownScopes`（请求 scopes 里授权服务器不认识的部分——拼错否则要等用户登完才被拒）。这个 DTO 被两个接口共用。
13. **出站护栏（`RemoteJsonFetcher`）**：目标地址来自管理员输入或上游文档的请求只能从这里出去——只接 http(s) 且必须有 host；拒掉云元数据目标（link-local、任意本地、组播与 `metadata.google.internal` 这类主机名，在建连接之前）；环回与私网有意放行（自建授权服务器就住在那里面）；`Redirect.NEVER`；连接 5s / 请求 10s 超时；响应体 64KB 上限加一条读取截止；body 读失败也包成 `RemoteFetchException`（否则一个断连接会让整个发现崩掉而不是试下一个候选）；只有 JSON 对象才算元数据。`postForm` 让换 token 与撤销的表单 POST 走同一道护栏而不是另起一个客户端。这些护栏是地板不是全套策略：地址在这里判过一次，连接时还会被重新解析，DNS 重绑定不在能力范围内。

### 3.8 按用户授权：发起、换票、状态、撤销

实现：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt`，待授权状态在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt`，出站走 `RemoteJsonFetcher.postForm`。

1. **准入门槛**：`authorize-url` / `status` / `revoke` 都先经可见性与租户守卫并要求 `authType == OAUTH2`，再要求取得出当前用户——授权是逐人的，拿不到 userId 就报错，不回落到「租户 1」。配置解析不出 issuer、注册行不存在、或那行的 `client_id` 还是发现留下的空串占位，发起与换 token 两处都拒：带着 `client_id=` 去撞授权服务器只会让人家的错误页替我们说话。
2. **`state` 钉住这次同意的全部前提**：一条 `PendingAuthorization` 存 (租户, 用户, mcpId, issuer, `code_verifier`, `redirect_uri`, resource, 请求的 scopes)，TTL 5 分钟、全局上限 500 条、每人上限 5 条（`countFor(userId)` 在生成 verifier 与 state 之前先数，被拒的那次什么材料都不留），`consume` 取走即失效（先摘再判过期，空串与不存在同答案）。存内存而不存表，因为 code 本身就是一次性、分钟级的东西。两条后果：admin 重启会打断正在同意的那一次；多副本需要粘性路由，否则换票落到没收过这次请求的副本上只会得到「请求未知或已过期」。两种上限都是显式拒绝并各回一句原因，不是静默丢弃——500 条是所有人共用的预算，没有每人一条挡在前面，一个登录用户就能把它填满。
3. **换票的身份来自调用者的 JWT**：`POST /oauth/exchange` 带 JWT，凭据写给调用者本人。判定顺序是固定的：先 `consume(state)`（无论结果如何 `state` 只用一次），再比归属——`state` 里记的 userId 与调用者不符时**拒绝并上报**，因为那正是同意钓鱼的形状（一个拿到了别人 `state` 的调用者本来可以用自己的 `error` 报文取消那次进行中的授权）；`state` 不属于当前用户时它不是身份来源，而是一次要记下来的事件。授权服务器带 `error` 时，即使 `state` 已经过期也要把对方给的原因回答给页面——那时什么都没存、也没东西可烧，而上游的原因比「未知请求」有价值。`code` 为空同样明确拒绝。
4. **换 token 前的复核**（集中在一个私有助手里）：服务行仍对当前用户可见且仍属于发起时的租户、`url` 仍等于 `state` 钉住的 resource（这一步只在 pending 带了 resource 时才判——`pending.resource != null` 是它的前置条件，`resourceIndicator` 关着或当时没拼出 resource 就整条跳过；同意是对那个地址给的，中途改过地址的 code 存下来就是一枚用不上的凭据）、按发起时的 issuer 找注册行（不是按现在的 `oauth_config`）、`client_id` 非空、token endpoint 已知。token 请求以表单 POST 发出，`grant_type=authorization_code` + `code` + `redirect_uri` + `client_id` + `code_verifier`，带得上客户端密钥就一并放 body（RFC 6749 允许 body 或 basic auth，选 body 因为所有实现都吃、且不需要第二条代码路径）；解不开存储的客户端密钥时明确报错要求重新保存，而不是裸奔去撞端点换回一个 `invalid_client`。响应要有 `access_token`，并按 `validateAgainstIssuer` 校 `iss` 与实际授予的 scope。授予的 scope 串宽过 `scopes` 列（512）时**整串不记**，只在 `last_error` 里说明被截了什么——截断的列表读起来像「这就是授予的范围」，而最后那一项是谁也没授予过的片段。
5. **授权请求参数**：`response_type=code`、`client_id`、`redirect_uri`（注册行里那一条）、`state`、`code_challenge` 与 `code_challenge_method=S256`、非空时 `scope`（空格连接）、按需 RFC 8707 的 `resource`（= 服务 `url`，受 `resourceIndicator` 控制）与 `audience`。请求的 scope 宽过列上限时在生成任何材料之前拒绝；`scopes_supported` 里不认识的项给一条 warn。响应回 `authorizeUrl` / `issuer` / `scopes` / `expiresIn`（= pending 的 TTL 秒数）。
6. **落地页**：授权服务器把浏览器送回前端路由 `/mcp/oauth/callback`（`harnax-webui/src/pages/mcp/oauth-callback.tsx`，`layout: false`，排在通配 404 之前），页面接 query、调用带 JWT 的换票接口、只回一句话。未登录跳转对落地页丢掉 search，避免 `code` 与 `state` 进登录页地址栏。
7. **状态**：`status` 读 (租户, 用户, 服务) 那一行，回答 `authorized` 的条件是 `status == ACTIVE` 且 `access_expires_at` 未过期（列没值时视为未过期），带出 `status`、过期时间与 `last_error`，不回任何令牌材料。
8. **撤销**：没有授权行时直接回答「已撤销、无上游动作」。有行时总是清本地（`clearLocally`），并在注册行有 `revocation_endpoint` 时按 RFC 7009 向上 POST——优先呈递 refresh token（access token 几分钟内自己就死了，refresh token 才会持续 mint 新的，合规服务器撤掉 refresh 即撤掉整个授权）。解不开密文时当作「没有可呈递的令牌」处理，本地那一份照样清掉：这个调用是用户唯一的出路，而密文解不开意味着密钥换过、不是同意失效。回答由 `when` 的六个分支各给一句话：解析不出注册行 / 注册登记里没有撤销端点 / 有密文但解不开 / 行里两个密文都是 NULL / 上游接受 / 上游拒绝（`revoked` 恒为 true，`upstreamRevoked` 说明上游那半）。授权设计文档里撤销那一节列的就是这六条。

### 3.9 令牌换发（内部接口）

`POST /api/admin/internal/mcp/access-token`，入参 `McpAccessTokenRequest(sessionId, mcpId)`，返回 `McpAccessTokenResponse`。`sessionId` 空白直接 400。

`McpOAuthUserService.accessToken(sessionId, mcpId)` 的规则：

1. 服务行按 id 直读 mapper，而不是经 `McpServerService`：那个守卫比的是**请求头里的租户**，而 agent-service 的内部调用没有这样的头；给这个答案定范围的是服务行自己的租户，也正是授权存进去时用的那个键。`authType != OAUTH2` 直接报错（这台服务没有按用户的令牌可发）。
2. 用 `McpSessionOwnerResolver` 把会话反查成 `sys_user.id` + 租户。反查不到就是没有身份：记 `NEEDS_CONSENT` 审计并回 401。
3. 属主租户与服务租户不一致时回 403。下发只会把智能体同租户的服务发给它，因此这一条正常情况下不可达，但它必须留着：授权行按**服务**的租户为键，缺了这道判断，租户 B 的会话就能花掉租户 A 对某台服务的授权。
4. 授权行不存在回 401；`status == REVOKED` 也回 401，但**不把状态改写成 `NEEDS_CONSENT`**——那会抹掉这一行唯一还记录着的事实：这个人是主动撤销的。两种情况的密文都保持原样：`revoke` 还需要存储的内容去通知授权服务器。
5. 存储的 access token 仍在有效期内（且密文解得开）就原样给出，并把有效期换算成 epoch 秒——admin 内部的比较都是本地语义，而调用者是另一个进程、不保证同一时区。过期时间落在提前量（`REFRESH_SKEW_SECONDS`）之内即视为需要刷新：一次在长运行中途因令牌过期而失败的调用读起来像「这台 MCP 服务坏了」，用户没法把它和真的故障区分开。没有有效期记录的行会一直用着，直到某处拒绝它。
6. 刷新在按 `credential.id` 取的条带锁里做，锁内**重读**那一行：可能刚有人刷过同一枚授权，刷第二次会烧掉那次轮换回来的 refresh token。表单是 `grant_type=refresh_token` + `refresh_token` + `client_id`（+ 可解开的 `client_secret`）。
7. 两类失败分开：授权已经没了、只有用户能救的（没有 refresh token、密文解不开、上游拒收）走 `NEEDS_CONSENT`——写状态、记审计、回 **401** 并附上「请重新授权」；这一次请求里任何关于授权的事实都没变、只是重试问题（网络不可达、密钥换过导致客户端密钥解不开）走 `transientFailure`——保持原状态、只写 `last_error`、记审计、回 **503** 并把原因带上。两个码不能混：混了就会因为网络问题把用户送去同意页。
8. 每次决定向 `mcp_call_log` 追加一行（`ISSUE` / `REFRESH` + 结果 + 耗时），不落令牌内容。

### 3.10 连通性测试与工具列表

`listTools(id)` 是 admin 里唯一一条会用库里配置真去连接的路径（`connectivityTest` 也走它），因此它的门槛最严：

1. `requireVisibleServer(id)`——租户与可见性；
2. `status == 0` 直接拒绝，提示先启用；一个被运维关掉的行在这里也不能被打通；
3. stdio 闸门：这一条会真的在 admin 容器里 spawn 存储的命令，所以闸门必须同时落在这里，否则开关的含义只是「不下发」而不是「不运行」；
4. `authType == OAUTH2` 明确拒绝：这道检查需要某个人的令牌，而管理侧探测没有「用户」可花——授权属于会话属主，不属于点击测试的人；用点击者自己的授权列工具还会让工具集随人而变，那不是连通性检查声称要显示的东西。要端到端验证 OAuth 走详情页的授权面板。

通过后调 `McpHelper.listTools(mcpServer, decryptToMap, decryptToolEnvParamsToMap)`，`initialize()` 与 `tools/list` 各 10 秒上限，客户端在 finally 里关闭（一次点击一个客户端，stdio 那种还是一个进程）。

### 3.11 停用与删除是两件事

| 动作 | 数据效果 | 运行效果 |
|------|---------|---------|
| 停用（`status = 0`） | 行、绑定、授权全部留着；详情页仍可编辑 | 下发时整行被扣下，运行侧再挡一道，因此**一次出网都没有**；`list_tools` / 连通性测试也拒绝 |
| 删除（`active = 0`） | 行逻辑删除，同事务内物理删该服务的 `agent_mcp_binding` 行与 `mcp_user_credential` 行 | 绑定消失，下发解析不到，按缺失处理 |

停用的语义是「不碰密文、不碰授权」：管理员关掉一台服务不该让它的用户重新授权一轮。删除的语义是「没有可呈现的对象」：行没了授权就没有对应物，留着密文纯属多余的爆炸半径。两条路径都**不动** `mcp_oauth_client`——那一行代表的是这个租户在那台授权服务器上的客户端身份，同一台服务器后面的别的 MCP 服务还在用它。

删除与授权清理都只在写命中之后执行：如果这一行刚被别人删掉、`updateById` / `deleteById` 没匹配到任何东西，就不能按「本次请求关掉了 OAuth」去删与它无关的授权行。

两处会连带清授权，都在更新路径，且按下面这个先后顺序判：

- 服务仍然是 `OAUTH2` 但请求带了一个不同的 `url`：这是更新路径上的第一个 `if`（`oauthResourceMoved`），命中就走不到下一条。授权是按 resource 领的（RFC 8707），换地址后每个已存令牌都会在上游被拒，而 `status` 列还写着「已授权」。清掉它们把用户推回重新授权，比留一个永久的、说不出原因的故障好。
- 这次请求把 `authType` 从 `OAUTH2` 改走：上一条没命中时才判（挂在它的 `else if` 上）。`oauth_config` 清 NULL，并删掉这台服务的全部用户授权行（读状态与撤销都要过 `authType == OAUTH2` 这道门槛，一台已离开 OAuth 的服务会被它们拒掉，留下的密文对持有人既看不见也撤不掉）。

上游那份授权不去呈递：各按自己的有效期自然过期，与删除服务时同一口径。

## 4. 绑定与配置下发

### 4.1 绑定保存

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt` 的 `saveMcpBindings` 是「整批重写」：先 `deleteByAgentId`，再把这次表单里的 `mcpList` 按 `mcpId` 去重后批量插入，空列表就是把绑定清空。一条绑定要落库，得过三道判断：

| 判断 | 规则 | 拒绝话术 |
|------|------|---------|
| 服务行可解析 | `McpServerMapper.selectByIds`（已排除 `active = 0`）后再按当前请求租户过滤，解析不到的 id 收集齐了一次性拒绝 | `MCP server is missing, deleted, or outside your tenant: …` |
| 服务行启用中 | `status != 1` 的行收集齐了一次性拒绝 | `MCP server is disabled, enable it before binding: …` |
| 声明的必填参数有值 | 该服务 `env_params` 中每一项 `required = true` 都要有绑定值 | `MCP server '…' requires env params that are left without a value: …` |

保存时拒绝而不是静默收下，是因为下发自带静默逻辑：解析不到的绑定只留一行日志，智能体就此少一个工具而无人察觉。这一列需要服务行的 `env_params` 才能查必填项，所以 `resolveBindableMcpServers` 返回行而不是 id。停用的服务因此根本绑不上——下发也会扣住它，绑上只是存一个永远到不了运行时的对象。

环境参数引用的检查与工具 / CLI 两条绑定共用 `assertEnvBindingsBindable`，它只在保存这条路上跑，拒两种形状：

- 同一个 `envKey` 绑到多个来源（指针与字面值混用，或指向两个不同变量）。下发按绑定行逐行产出 `{envKey, envValue}`，运行侧把这些对折进一个以名字为键的 map，因此最终哪个凭据到达工具取决于行序。键的唯一性按 (租户, 创建人) 划分，两个人各持有一个 `OPENAI_KEY` 是常态，一个被共享的智能体就会碰上这件事。同名且同源的重复被保留：参数表把同一个名字声明两遍的服务产出的就是这两行，两行解析到同一件事。
- 引用取不到值。指针用 `EnvVariableService.getRowWithinTenant(id)` 校验，比较的是**租户**作用域而不是控制台的创建人作用域，否则共同编辑一个智能体的协作者会拒掉真能送到运行时的指针。

引用真正取值在下发，那道闸是 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt` 的 `getDecryptedValue(id, agentTenantId)`，它把四种情况统一回答成 null：行不存在、租户不符、`enabled != 1`、解密抛异常（这一种另记一条 warn）。也就是说「停用一个变量」是在这里生效的，保存侧对前三种状态先拒一次，为的是让表单上看到的「已填」与运行时拿到的「有值」是同一件事。

`env_bindings` 快照对引用只存指针：`envVarId` 加服务端解析出的 `envVarName`，不存任何值。请求体在这个字段上带回来的是读接口的展示值（敏感变量是掩码串），存进快照就等于把掩码变成变量消失后工具收到的回退值；由服务端把值解出来再存则是把明文密钥写进这一列。字面输入按 `customValue` 原样存。

必填判断里「参数自带的默认值算不算已填」取决于这个默认值在运行时是否真的会到达工具：MCP 与 CLI 传 `defaultValueCounts = true`，内置工具传 `false`（内置工具的下发只带绑定值，工具自己声明的默认值进不了 `ToolEnvContext`）。

### 4.2 下发解析与三道扣留

`InternalApiController` 构造智能体 spec 时的 MCP 段：

1. 绑定行来自 `AgentMcpBindingMapper.selectByAgentId(agentId)`，`mcpId` 去重；
2. 服务行用 `McpServerMapper.selectByIds` 批量取，随后按**智能体所属租户**过滤：`selectByIds` 没有租户条件，内部调用也没有可信的租户头，因此智能体自己的租户是唯一可比的基准；
3. 三道扣留：解析不到（已删或跨租户）、`stdio` 且 `harnax.mcp.stdio-enabled = false`、`status = 0`。被扣下的服务**同时从 `mcpDetails` 与 `mcpList` 两半里消失**——这是「禁用行真的不出网」的关键：`mcpList` 的 `env_bindings` 会解析进 `ToolEnvContext`，留着它，被禁用服务解析出的值仍会到达每个工具，同名键还会静默覆盖启用工具绑定的值；
4. 扣留各记一条日志（缺失 warn、stdio warn、禁用 info），三类 mcpId 分得很清；
5. `mcpDetails` 由通过扣留的那些行构造，`headers` / `envParams` 经 `plainConfigJson` / `plainToolEnvJson` **解密成明文 JSON 对象**再下发；解析结果为空但存储列不是空数组时另记一条 warn，避免把「解不出来」显示成「没配」。

`mcpList` 的形态是 `[{id, env_bindings:[{envKey, envValue}]}]`，由通过扣留的那批绑定重建；它承载的是环境参数解析结果，不是服务配置。

### 4.3 明文下发的边界

AES 密钥只在 admin 侧（`SecretFieldEncryptor`），agent-service 不持有它，因此解密必须发生在出口。接收端 `PlaintextMcpConfigDecryptor`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt`）只做解析，不碰任何密钥：正常进来的是平文 JSON 对象，它按 `Map<String,String>` 读出来交给客户端。存储形态的那个数组（`[{"key","value","secret"}]` 与 `[{"envParamName","defaultValue","secret"}]`）它也照样收下而不是拒掉——库里那一行就是这个形状，收到它说明某条下发路径没有做解密那一步，于是带 `secret` 的条目会以谁也变不回明文的密文交出去，它就记一条点名到键的 warn 说这件事：除此之外「一个 header 都没有」和「没配任何 header」在界面上长得一模一样。解析抛异常的载荷同样只 warn 后回空 map，且 warn 里只有载荷长度与首字符，没有内容本身。运行侧的配置唯一来源就是这份下发内容（`AgentSpecContextHolder`），没有查库回退：库里那行是密文，本服务没有密钥，直查数据库只会把解不开的密文喂给客户端。

## 5. 运行侧装配

### 5.1 流程

```
AgentSpecResolver.buildAgentSpec()
    ├─ mcpDetails → AgentSpec.mcpServices（每台一个 McpSpec(mcpId, isAsync = true)）
    └─ mcpList 的 env_bindings → 并入 ToolEnvContext（供 ToolBox 工具读取）
            ▼
HarnessAgentLauncher.createAgentBase()
    遍历 agentSpec.mcpServices（team lead 拿到空列表：MCP 是成员侧关注点）：
    ├─ McpConfigAdaptorImpl.getConfig(mcpId)
    │     唯一来源 = 本次下发的 mcpDetails；DTO → 实体转换（含 authType 与 status）
    │     取不到 → warn 后跳过这一台
    ├─ status == 0 → info 后跳过（不建客户端，也不报缺失）
    ├─ type == stdio 且 harness.mcp-stdio-enabled == false → warn 后跳过
    ├─ authType == OAUTH2 → mcpTokenSourceFactory.forUser(authSessionId, userId)
    │     返回 null（会话没有用户身份 / 没有令牌源实现）→ warn 后跳过这一台
    │     拿到源就先预热一次令牌，在正在建 agent 的这个线程上
    ├─ McpHelper.createMcpClient(mcpConfig, isAsync, decryptToMap, decryptToolEnvParamsToMap, tokenSource)
    │     └─ 建客户端或注册失败 → warn、关掉半成品、继续装配其余
    └─ agentBuilder.addMcp(client)，客户端同时收进 HarnessAgentWrapper.mcpClients
            ▼
装载数少于绑定数时汇总一条 warn：外部只看得见「这个 agent 没有 MCP 工具」
```

`AgentSpec.mcpServices` 里每台服务只放一个 id 和 `isAsync`。后者是构建方式的选择（异步构建走 `buildAsync()` 再限时等待），当前唯一的赋值点就是它的声明默认值 `true`，没有任何下发路径去改它。只带 id 是有意的：这台服务的传输、地址、请求头、认证方式全部经本次下发的 `mcpDetails` 取，运行侧因此没有第二处能保存一条与 admin 的决定不同的记录。

预热那一步存在的原因是：per-request 的回调会在 MCP 客户端发送所在的线程上读令牌源，而异步握手跑在公共 fork-join 池上；冷启动的一次换发会占住它少数几个线程整一个 admin 往返。预热的失败不致命也不上报——用户可能在会话中途才授权，真正面向用户的是 per-request 回调。

`McpConfigAdaptorImpl`（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt`）在下发的 `authType` 为空时记一条 warn 并让实体保持缺省 `NONE`：那等于把一台 OAuth 服务当作静态头服务连出去，错误会指向那台服务而不是指向缺字段的下发方。

### 5.2 传输构建与请求头

`McpHelper.buildMcpConfig`（`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`）按 `type` 分派：

| type | 配置 | 取值来源 |
|------|------|---------|
| `stdio` | `StdioMcpConfig(name, command, args, env)` | `env = envResolver(envParams)` |
| `sse` | `SseHttpMcpConfig(name, url, headers, queryParam)` | `headers = networkHeaders(headers)` |
| `streamablehttp` | `StreamableHttpMcpConfig(name, url, headers, queryParam)` | 同上 |

未识别的 `type` 返回 null，由 `createMcpClient` 抛 `MCP_CLIENT_CREATE_FAILED`。两个 resolver 为 null 时回退 `{ emptyMap() }`；`envResolver` 缺省时复用 `configResolver`。

`networkHeaders` 会为一台 `OAUTH2` 服务剔掉 `headers` 里同名的静态 `Authorization`（大小写不敏感）并记一条 warn：两者写的是同一个头，谁赢由传输层的顺序决定而不是用户能看见的东西；带身份的是按用户令牌，所以留下它。

超时口径：`initialize()` / `tools/list` 各 10 秒；客户端构建 60 秒（stdio 可能要先拉取外部命令）；`OAUTH2` 服务的握手预算放宽到 30 秒（`initializationTimeout`），因为第一次回答令牌回调要多走一跳 admin、再经它走一跳授权服务器。异步构建走 `buildAsync().block(60s)`，返回 null 时抛 `MCP_CLIENT_CREATE_FAILED` 而不是裸 NPE。

### 5.3 OAuth 令牌注入

`McpHelper.createMcpClient` 收到 `authType == OAUTH2` 且 `tokenSource == null` 时直接抛 `MCP_CLIENT_CREATE_FAILED`——照样建客户端会以未认证方式连出去、在第一次工具调用上失败、错误指向那台服务，而不是指向缺失的授权。

有令牌源时通过 `httpRequestCustomizer` 注入，而不是 `headers(...)`：令牌会过期会轮换，建连接时冻结的头会让长对话死在 access token 到期那一刻（5 分钟令牌的对话里那只是一次回答的中途）。回调每次请求都问一次（含握手），实现方负责缓存，因此常见路径只是一次 map 查找；它跑在客户端发送所在的线程上。

`McpAccessTokenSource` 是 `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpAccessTokenSource.kt` 里的回调接口，拿不到令牌时抛 `McpAuthRequiredException`：这次调用失败并把原因写进日志，而不是让一次未认证的请求出门。

### 5.4 身份在建实例时绑定，以及令牌缓存

`McpAccessTokenSourceFactory.forUser(sessionId, userId)`（接口在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpAccessTokenSourceFactory.kt`）把身份绑在返回的源上。之所以是工厂而不是一个全局源：MCP 客户端随 agent 实例创建、之后被该实例服务的每一次调用复用，而它出示的 bearer token 属于一个具体的人——在建好之后按请求读「当前用户」，会在缓存的 agent 被共享的那一刻把 A 的令牌交给 B。`userId` 为空（服务密钥、渠道会话）时返回 null，这是正确答案而不是错误。

实现 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`：

| 机制 | 口径 |
|------|------|
| 缓存键 | (sessionId, mcpId) 二元组：同一台服务从两个会话访问是两个令牌 |
| 提前续期 | 过期前 30 秒即视为不可用，避免长调用跨过期点 |
| 无有效期时 | 给 300 秒租约，授权服务器没说也要到点再问 |
| single-flight | 64 条固定条带锁，锁内先重读；两个线程同时换同一枚令牌会让 admin 刷两次，而轮换过的 refresh token 会让第二次失败 |
| 被拒冷却 | 15 秒，冷却期内直接抛 `McpAuthRequiredException`；缺失的授权靠人去点授权，不靠重试；同时把该键的缓存条目清掉，否则会再挂一枚 admin 已经标记为需同意的令牌 |
| 容量 | 令牌与拒绝两张表各 2048 上限，触顶后先清过期项、再逐个淘汰到 1024；不整体清空，否则每个丢令牌会话都会在 MCP 请求线程里重新换发，一次缓存满就变成一批阻塞往返 |

向 admin 只带 `sessionId`：admin 自己解析属主，因此这里的 `userId` 只是「有没有身份」的判断，从不过线，一个错值或空值都无法让 admin 签发别人的令牌。

### 5.5 会话身份与 OAuth 类 MCP 的加载条件

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` 按会话前缀解析 `sys_user.id` + 租户：

| 前缀 | 解析方式 | 结果 |
|------|---------|------|
| `web-` / `mp-` | `SessionMapper.selectBySessionIdAndStatus(sessionId, 1)` 取 `creator` + `tenant_id`，再落 `sys_user` | 有身份 |
| `task-` | 取 `task-` 后的第一段数字作为 taskId，经 `SchedulerClient.taskOwner(taskId)` 读创建人与租户 | 有身份（任务创建人） |
| `chn-` | 渠道会话没有用户身份，直接返回 null（debug 日志） | 无身份 |
| 其他 | 前缀未知，warn 后 null | 无身份 |

返回 null 不是要上报的错误：渠道会话是钉钉 / 飞书的一个对话，`creator` 里存的是渠道侧的发送者 id；把某个人的授权呈给一条渠道消息，就是别人的身份伸进了外部系统。定时任务 `task-` 以任务创建人的身份使用其授权——这一读的创建人与租户来自 scheduler 的 HTTP 接口（任务这个域的数据在 scheduler 侧），按设计是冷路径：它只在建 OAuth MCP 客户端时跑，不在每条消息都走的 agent-spec 查询上。读失败的每一种后果都是「这次运行没有这枚身份」，各带一句 warn，异常不逃逸进执行流程。

`creator` 到用户的解析：web 会话存用户名、小程序会话存数字 id，因此两种都试（先 `selectByUsername`，只有它没命中且值是个纯数字才 `selectById`，一个真叫 "12345" 的登录名仍然以它自己优先）。解析结果还要过两道：`username` 命中的行若属于别的租户会被拒（同名跨租户是真实可能，落到那行就是花别人的授权）；`status != 1`（账号被停）也不给身份——会话开在开关拨下之前，不能继续花它的授权。

装配侧的三条后果连在一起：`McpConfigAdaptorImpl` 不在意身份，它只管给出配置；`HarnessAgentLauncher` 在 `authType == OAUTH2` 且拿不到令牌源时跳过这台服务；`HarnessAutoConfiguration` 用 `ObjectProvider` 可选注入令牌源工厂，没有实现时 OAuth 服务连不上，而不是带着空身份连。团队场景里成员用**根会话 id** 去要令牌（`authSessionId = rootSessionId`）：授权属于开这个会话的人，子会话是 admin 从没听说过的内部键。

### 5.6 客户端生命周期

`HarnessAgentLauncher` 把本次装配建出的客户端列表收进 `HarnessAgentWrapper.mcpClients`；wrapper 被丢弃时调 `release()`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`），触发点是缓存淘汰、`/refresh` 这类显式失效、能力开关变更以及服务关停。顺序是三件事：先用 `released.compareAndSet(false, true)` 占位（关停扫描与缓存自身的移除监听器会同时伸手拿同一个 agent，第二次必须是空操作），再关 `teamOrchestrator?.releaseAll()`（成员只活在编排器里，除了这里没人会关它们），然后逐个 `client.close()`，最后 `harnessAgent.close()`。每一个关闭都自己套 try/catch 记一条 warn 后继续，因此一台关不掉的服务不会让剩下的客户端留着不关。

`HarnessAgent.close()` 只解绑状态存储、清状态缓存，不碰 toolkit 的 MCP 客户端，而 stdio 客户端是一个操作系统进程——智能体每 30 分钟重建一次，少了这一步每次重建都会留下一个进程。

`release()` 不在请求在飞时调用：那会把工具从正在执行的那一步底下抽走，这个判断归调用方。

装配单台失败不外抛：一台连不上的服务不该让整个 agent 丢掉它所有工具，与 admin 把解析不到的绑定从 spec 里丢掉同一个形状。`addMcp` 有自己的等待超时，注册线程可能还在跑，因此失败路径上会把已建出的半成品关掉。

## 6. 环境参数与密钥的两条通道

| 通道 | 配置位置 | 作用对象 | 解密时机 |
|------|---------|---------|---------|
| `mcp_server.headers` / `env_params` | MCP 服务自身配置（Admin MCP 编辑页） | **MCP 客户端本身**：请求头 / stdio 进程环境 | admin 出口（下发前解密） |
| `agent_mcp_binding.env_bindings` | 智能体绑定 MCP 时的环境变量表单 | **`ToolEnvContext`**（ToolBox 工具读取） | admin 出口（解析成明文值写进 `mcpList`） |

第二条通道不注入 MCP 客户端本身。它解析出的值进入的是这个 agent 的环境上下文，因此被扣下的服务必须整条从 `mcpList` 消失，否则它的值仍会到达每个工具。

`ToolEnvContext` 是一张以键为索引的扁平表，全 agent 共用一份，工具与 MCP 两条绑定都往里合：`AgentSpecResolver` 先并 `toolList` 再并 `mcpList`，所以一个两边都出现的键由这个顺序决定，而不是由谁的配置更贴近意图决定。这也是 `assertEnvBindingsBindable` 在保存侧就拒绝「同一个键绑到两个来源」的原因——那条判断只看单个绑定对象的表单，而真正决定结果的是这张表的合并顺序。

变量的值只有一张表：`env_variable`（实体 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/EnvVariable.kt`）。键的唯一性按 (租户, 创建人) 划分——`uk_env_tenant_creator_active_key (tenant_id, creator, active_env_key)`，生成列在 `active = 0` 时变 NULL，因此删掉的键可以再用。下发时 `resolveEnvBindingsJson` 对指针调 `getDecryptedValue(envVarId, 智能体租户)` 现取，取不到就**不为这个键交付任何东西**并留一条 warn：引用行自身不带值（`serializeEnvBindings` 对引用只写 `envKey` / `envVarId` / `envVarName`），于是工具看到的是「这个参数没配」，这是一个可以照着判断的状态，比交一个空串更准确——很多工具按键是否存在来决定自己是否已配置。

MCP 服务自身的 `env_params` 用的是与内置工具同一种条目形状 `ToolEnvParamEntry`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt`：`envParamName` / `description` / `required` / `secret` / `defaultValue`），值写在 `defaultValue` 里，`secret = true` 的条目由 `SecretFieldEncryptor` 加密存这一列、`decryptToolEnvParamsToMap` 解密成 `envParamName → 明文值`。内置工具的参数定义另有 `agent_tool_env_param` 一表逐列存放，MCP 与 CLI 则把这串 JSON 放在自己行上——它们没有代码侧的参数声明可读。

这一列只喂 stdio 分支：`McpHelper` 把它解析成子进程环境。网络类型的 MCP 用它自己的 `headers` 出网，不读 `env_params`。

MCP 的下发不做「把声明的默认值补进环境上下文」这件事：`mcpList` 交付的就是绑定行解析出的那些键值，一台服务声明了 `required` 参数而智能体没填，工具就拿不到它（保存时那三道判断已经先拒过一轮）。CLI 的下发做了这件事，`mergeCliEnvBindings` 先取绑定值再按键补上包自己声明的默认值，缺了这一步一个已安装的 CLI 会带着完全没有凭据的沙箱环境落地——`check_command` 通过、每次调用都失败在「未登录」。同一个差别决定了 `defaultValueCounts` 的取值。内置工具传 `false`：它的下发自带绑定值，工具自己声明的默认值进不了 `ToolEnvContext`，因此不能拿来充当一个必填项的答复。MCP 与 CLI 传 `true`：必填判断读的就是这台服务 / 这个包自己声明的条目，MCP 那一列对 stdio 行确实随进程环境到达运行时，CLI 那一列由 `mergeCliEnvBindings` 补进沙箱环境，两边的默认值都真的能回答一个必填项。

## 7. 配置开关与部署参数

| 配置 | 默认 | 作用 |
|------|------|------|
| `harnax.mcp.stdio-enabled`（admin，`McpStdioPolicy`） | false | stdio 的准入闸门：关掉时创建 stdio 与切进 stdio 被拒、已有 stdio 行不下发、`list_tools` / 连通性测试也拒 |
| `harness.mcp-stdio-enabled`（agent，`HarnessAutoConfiguration`） | false | 运行侧的二次防御：关掉时装配跳过 stdio 服务，即使有一行被送到它面前。两侧都为 true 才会真起进程 |
| `app.base-url` | 分两层：`harnax-admin/src/main/resources/application.yml` 写的是 `${APP_BASE_URL:http://localhost:8080}`，`docker-new/docker-compose.yml` 传给容器的是 `${APP_BASE_URL:-http://localhost:28080}`（容器里没设这个变量时取后者），`docker-new/.env.example` 把它示例成 `http://localhost` | admin 自身地址，`app.frontend-base-url` 为空时作为回调地址的取值；容器里读到的是 compose 那一层，不是 yml 里的 8080 |
| `app.frontend-base-url` | 空 | 浏览器侧地址：OAuth 的 `redirect_uri` 由它拼上 `/mcp/oauth/callback`。生产由 nginx 用同一个域名代理两者，开发下 SPA 在 `:8000` 而 admin 在 `:8080`，此时必须显式配置 |

两个 stdio 开关由同一个环境变量 `HARNAX_MCP_STDIO_ENABLED` 驱动：`harnax-admin/src/main/resources/application.yml` 与 `harnax-agent/harnax-agent-service/src/main/resources/application.yml` 各自把它绑到自己的键上，`docker-new/docker-compose.yml` 给 admin 容器和 agent-service 容器都传这一个值，`docker-new/.env.example` 里也是这一行注释说明取舍。控制台的类型下拉只提供 `sse` 与 `streamablehttp`（`harnax-webui/src/pages/mcp/components/CreateForm.tsx`），表单里判断 `stdio` 的分支留着，是为了某个部署打开开关时不必回来补前端逻辑。

stdio 关掉的理由写在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt` 里：一条 stdio 记录不是连接而是进程，agent-service 会把它启动起来，而该容器以 root 运行并挂载了宿主 Docker socket（`docker-new/docker-compose.yml`）——能保存这样一行，等于能在那里执行命令。在执行侧被隔离之前 stdio 不支持。存储仍然开放，因为把已有行改成不可编辑会让它们既无法维护也无法停用，比被防住的那种状态更糟；被闸的是所有会真去启动它的路径。已存在的 stdio 行可以继续改名、写描述、停用，只是永不下发，`refusalReason()` 给出的就是这段理由，而不是一个笼统的失败。

## 8. 明确不做与边界

- **stdio 类型的 MCP 在当前部署上不可用**：两个开关默认都关，要跑在已隔离运行时的部署上由运维显式打开两侧。
- **`BASIC` 认证不可用**：列与常量都在，运行时没有分支，管理侧拒收。
- **没有全局 / 服务级的 OAuth 令牌**：OAuth 授权只有「某个用户对某台服务」这一种形态，租户级共用一份令牌的路不存在。
- **无用户身份的会话不加载 OAuth 类 MCP**：渠道 `chn-` 会话与不带用户的服务密钥换不到令牌，装配时跳过该服务并留一条 warn，不会退化成未认证连接。
- **不做动态客户端注册（DCR）**：`registration_endpoint` 会被发现并记录，但没有代码走它；客户端凭据由管理员在 `/oauth/client` 登记。
- **`CALL` 级审计不记工具调用内容**：`mcp_call_log` 承载的是授权侧的签发与刷新，且不含令牌与请求体。
- **不在 MCP 客户端上应用按绑定的环境参数**：`env_bindings` 只服务 `ToolEnvContext`。
- **不在 admin 侧代发授权**：管理面的连通性测试与 `list_tools` 拒绝 OAuth 服务，因为这道检查没有用户可花。
- **不做令牌的下发与持久化**：`oauthConfig` 不进 `McpDetailDto`，access token 不进任何管理面响应体，refresh token 密文不出 admin 进程。
- **`mcp_oauth_client` 不随服务删除清理**：一行代表一个租户在一台授权服务器上的客户端身份，可能被别的 MCP 服务共用。
- **不做待授权状态的跨进程共享**：pending 存内存，因此 admin 重启打断正在进行的同意，多副本要求粘性路由。
- **不跟随重定向**：发现与换票的出站 HTTP 一律 `Redirect.NEVER`；也不宣称能防 DNS 重绑定。
- **MCP 的绑定下发不补服务自己声明的默认值**：`mcpList` 交付的就是绑定行解析出的那些键值，声明的 `env_params` 只喂 stdio 分支的进程环境（CLI 的下发会按键补齐包声明的默认值，这是两个域的差别）。环境参数键的唯一性归 `env_variable` 一处。

## 9. 关键文件索引

| 分类 | 路径 |
|------|------|
| 实体 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentMcpBinding.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpOauthClient.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpUserCredential.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/EnvVariable.kt` |
| 线格式 DTO | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpAccessTokenResponse.kt` |
| Mapper | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpServerMapper.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentMcpBindingMapper.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpOauthClientMapper.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpUserCredentialMapper.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpCallLogMapper.kt`，XML 在 `harnax-entity/src/main/resources/mapper/` 下同名 |
| 迁移 | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`、`harnax-admin/src/main/resources/db/migration/V7__normalize_agent_bindings.sql`、`harnax-admin/src/main/resources/db/migration/V19__add_mcp_binding_unique_key.sql`、`harnax-admin/src/main/resources/db/migration/V20__drop_mcp_binding_enable_skip.sql`、`harnax-admin/src/main/resources/db/migration/V22__mcp_public_default_and_tenant_backfill.sql`、`harnax-admin/src/main/resources/db/migration/V23__add_mcp_server_name_unique_key.sql`、`harnax-admin/src/main/resources/db/migration/V25__add_mcp_oauth_columns.sql`、`harnax-admin/src/main/resources/db/migration/V26__add_mcp_oauth_tables.sql`、`harnax-admin/src/main/resources/db/migration/V45__add_env_key_unique_key.sql`、`harnax-admin/src/main/resources/db/migration/V46__scope_env_key_unique_to_creator.sql` |
| 管理 API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| 管理服务 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpServerService.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpOAuthService.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpOAuthUserService.kt` |
| 策略与身份 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` |
| 请求与响应 DTO | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerUpdateRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpConfigEntry.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthClientRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthDiscoveryResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthAuthorizeResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthExchangeRequest.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthExchangeResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthStatusResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthRevokeResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpToolResponse.kt` |
| 加密与出站 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/RemoteJsonFetcher.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/ApiErrors.kt`、`harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt` |
| 绑定与下发 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt` |
| 运行侧配置读取 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpConfigAdaptor.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt` |
| 令牌源 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpAccessTokenSourceFactory.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`、`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpAccessTokenSource.kt` |
| 客户端构建 | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`、`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpConfig.kt`、`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpErrorCode.kt` |
| 装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` |
| 配置与部署 | `harnax-admin/src/main/resources/application.yml`、`harnax-agent/harnax-agent-service/src/main/resources/application.yml`、`docker-new/docker-compose.yml`、`docker-new/.env.example` |
| 前端 | `harnax-webui/src/typings.d.ts`、`harnax-webui/src/services/ant-design-pro/mcp.ts`、`harnax-webui/src/pages/mcp/index.tsx`、`harnax-webui/src/pages/mcp/detail.tsx`、`harnax-webui/src/pages/mcp/oauth-callback.tsx`、`harnax-webui/src/pages/mcp/components/CreateForm.tsx`、`harnax-webui/src/pages/mcp/components/UpdateForm.tsx`、`harnax-webui/src/pages/mcp/components/OAuthFields.tsx`、`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx`、`harnax-webui/config/routes.ts`、`harnax-webui/src/app.tsx` |

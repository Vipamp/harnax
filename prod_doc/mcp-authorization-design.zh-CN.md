# Harnax MCP 出站授权设计（中文）

> 英文版本见 [mcp-authorization-design.en-US.md](./mcp-authorization-design.en-US.md)。
> 本文只讲**harnax 作为 MCP 客户端时，如何认证与授权到上游 MCP 服务**；`mcp_server` 的 CRUD、加密掩码、配置下发见 [mcp-management.zh-CN.md](./mcp-management.zh-CN.md)，工具侧的授权口径见 [tool-integration-design.zh-CN.md](./tool-integration-design.zh-CN.md)。
> 入站认证（谁在调 harnax：JWT / API Key / 内部密钥）不在本文范围，但第 5 节会复用 `api_key.user_id`。
> **本文是设计文档**：与其他 prod_doc 不同，它描述尚未实现的部分，实施进度以第 10 节的状态表为准。

## 1. 结论先行

1. harnax 只做 OAuth 2.1 的**客户端**和**凭据库**，不做 IdP，不做 MCP server 端。
2. 刷新令牌（refresh token）**只存在 admin**，永不下发给 agent 运行时，也永不进入模型上下文；运行时每次调用上游前向 admin 换一个短期访问令牌。
3. 运行侧注入点是 agentscope 的 `McpClientBuilder.httpRequestCustomizer`，它是**每请求回调**，所以令牌轮换不需要重建 MCP 客户端。
4. 阻断性前置是**身份贯通**：运行时装配 Agent 时曾把 `UserIdentifier(0)` 硬编码，「哪个用户在用这个会话」在到达 MCP 客户端之前就丢了。**这一条已由 P1 关闭**（见 §2.2）。没有它，per-user 授权无从谈起。
5. 分期：P0 安全止血 → P1 身份贯通 → P2 OAuth 客户端 → P3 运行侧注入 → P4 其余方式。P0 与 P1 已完成，P2 已完成 P2-1 / P2-2 / P2-3 / P2-5（P2-2 含管理面前端，P2-5 含用户侧授权入口与带 JWT 的换票接口，状态见第 12 节），只剩 P2-4 换发。

## 2. 现状

### 2.1 已支持的三种上游认证

| 方式 | 载体 | 代码位置 | 粒度 |
|------|------|----------|------|
| 无认证 | — | `mcp_server.headers` / `env_params` 留空即可 | 服务级 |
| 静态凭证（`Authorization: Bearer x`、任意自定义头、stdio 环境变量） | `headers` / `env_params` JSON 条目，`secret=true` 走 AES-256-GCM | 写入 `McpServerServiceImpl.kt:102-103`，下发前解密 `InternalApiController.kt:379-380`，装配 `McpHelper.kt:142-148` | **服务级共享**，所有人用同一份 |
| 手工把 key 塞进 `command` | stdio 命令行 | 不推荐，等于明文入库 | 服务级 |

能力边界：只支持「静态、租户内共享、管理员手填」。上游一旦要求 OAuth 2.1（授权码 + PKCE）、要求按用户鉴权、或令牌会过期，现状无法覆盖。

### 2.2 身份链路的三处断点

```
浏览器 ──JWT──▶ admin（/api/admin/**）              ✅ 知道是谁：JwtAuthenticationFilter.kt:80-90
      └─X-Api-Key─▶ router（/api/router/**）        ✅ 断点 1 已关闭（P1）：ApiKeyInfo / AuthContext 带 userId
                       └─ChatAgentRequest─▶ agent-service
                                                ✅ 断点 2 已关闭（P1）：AgentRequest.kt:27-32 带 userId
                                                     └─DefaultAgentRunner.kt:74,95,210
                                                        ✅ 断点 3 已关闭（P1）：UserIdentifier(request.userId)
```

- 断点 1：`api_key` 表**本来就有** `user_id`（`V1__init_schema.sql:357-388`，`uk_user_permanent (user_id, key_type)`），只是校验链把它丢了：admin 的 `POST /api/admin/internal/api-keys/validate` 响应不含 `userId` → `RemoteApiKeyStore.kt:38-41` 构造的 `ApiKeyInfo` 没有该字段 → `AuthContext.kt:8-15` 也只有 `callerId = keyInfo.name`（`ExternalApiKeyValidator.kt:24-30`）。
- 断点 2：`ChatAgentRequest(sessionId, message, imageUrls, requestId)` 无用户字段；`AgentSpecInfoResponse`（`InternalApiController.kt:79-87`）也不返回用户，只到 `tenantId`。
- 断点 3：`UserIdentifier` 是 `data class UserIdentifier(val userId: Long)`（`ToolCallContext.kt:16-18`），三处调用点全传 0。
- **P1 关闭方式**：`api-keys/validate` 响应带 `userId` → `ApiKeyInfo.userId` → `AuthContext.userId` → `AgentRequest.userId`（基类 `abstract val userId: Long?`）→ `UserIdentifier(request.userId)`；`userId` 为空表示「没有端用户」，不再用 0 顶替。router 侧 `AgentProxyController.resolveUserId` 定下口径：**认证用户的 userId 覆盖请求体自报值**，只有服务级调用（自解析用户）才保留自带值。
- 其余入口同样无人可用（P1 只贯通了 web / router 的 API-key 入口，以下三处仍未接）：飞书用 `openChatId` 当 sessionId（`FeishuAdaptor.kt:77`），无用户绑定表；定时任务是 `task-<id>-<uuid>` + 服务级 key（`SchedulerServiceImpl.kt:239`、`RouterClient.kt:101`）；CLI 走内部密钥，principal 是 `internal-service`。
- 好消息：`session.creator` 是**用户名**（web 写 `currentUsername`，`SessionServiceImpl.kt:165`；小程序写数字 id，`MpSessionService.kt:64`），所以 `sessionId → username → sys_user.id` 在服务端可反查，不必相信客户端自报。**这是第 6 节选定的路径。**

### 2.3 运行时的密钥边界

- `harnax.aes.secret-key` 只存在于 `harnax-admin/src/main/resources/application.yml:126-127`；agent/router/channel/scheduler 的 yml 里没有，`AesUtil` 也只在 admin 源码树内（其他模块 grep 零命中）。
- 因此**配置下发时 admin 已解密成明文**（`InternalApiController.kt:609-631`），运行时实现是 `PlaintextMcpConfigDecryptor`（只做 JSON 解析，发现库里的 `[{...secret:true}]` 形态漏下来就打 warn）。
- 这条边界对授权方案是决定性的：凭据库必须在 admin，运行时只能拿**已经换好的、短期的**访问令牌。

## 3. 方式清单与选型

| 上游认证方式 | 是否纳入 | 阶段 | 依据 |
|--------------|----------|------|------|
| 无认证 / 静态 API Key / 自定义 Header / stdio env | ✅ 已有 | — | 现状够用，但需补掩码回写保护（P0） |
| Basic Auth（用户名口令） | ✅ | P4（0.5d） | 就是固定 Header，归到 `auth_type` 枚举里统一处理 |
| **OAuth 2.1 授权码 + PKCE（用户级）** | ✅ 主线 | P2 + P3 | MCP 规范对 HTTP 传输的强制方向；企业侧（Auth0 Token Vault / Arcade / Composio / Scalekit / MCP gateway）同构 |
| OAuth2 `client_credentials`（服务级 M2M） | ✅ | P4（2–3d） | 复用 P2 的 client 注册 + P3 的注入通道，去掉人工同意与回调 |
| RFC 9728 受保护资源发现 + AS metadata + Client ID Metadata Documents | ✅ 发现部分 | P2 | 少填一半表单；`authorization_servers` 自动定位 AS |
| 动态客户端注册 DCR（RFC 7591） | ⚠️ 可选 | P2 末 | 规范优先级低于预注册与 CIMD，按上游要求再开 |
| OIDC `id_token` 直传 / JWT assertion（RFC 8693 token exchange） | ❌ 暂缓 | — | 需要自有 IdP 与 AS 配合，当前无场景 |
| mTLS（每租户客户端证书） | ⚠️ 可做 | P4（5–8d） | **已验证可行**：`customizeStreamableHttpClient` / `customizeSseClient` 直接交出 `java.net.http.HttpClient.Builder`，`sslContext` / `proxy` / `authenticator` 都在；缺的是证书签发与轮转运维 |
| 云厂商签名（SigV4 / GCP SA / Azure MI） | ❌ | — | 每个 2–4d 且要引 SDK，按需求单点突破 |
| 外部凭据库 / 第三方 MCP gateway 代持 | ❌ | — | 决策级变更，引入外部依赖；本方案自建的是它的最小可用版本 |
| 把 harnax 暴露成 MCP server | ❌ | — | 全仓无 MCP server transport，另立方案 |

## 4. 总体架构

```
                    ┌──────────────────────── admin（唯一持密者）────────────────────────┐
浏览器 ──授权码流程──▶│ /api/admin/mcp/{id}/oauth/*  ── OAuthClientRegistry ──┐           │
   （跳转 + 回调）    │                                                      ▼           │
                    │                              mcp_oauth_client ┐  ┌─▼───────────┐  │
                    │  mcp_user_credential（refresh 密文，AES-GCM）──┘  │ TokenService │  │
                    │  mcp_call_log（调用级审计）  ◀─────────────────────│  换发/刷新    │  │
                    └───────────────▲──────────────────────┬───────────┴─────────────┴──┘
                                    │ 共享密钥             │ POST /internal/mcp/access-token
                                    │ (InternalApiAuthFilter)│ body: sessionId + mcpId
                                                             ▼
                          agent-service：装配 MCP 客户端（HarnessAgentLauncher.kt:174-188）
                                    │  McpClientBuilder.httpRequestCustomizer（每请求回调）
                                    │  进程内缓存 key=(mcpId,userId)，TTL=expiresAt-60s
                                    ▼
                              上游 MCP 服务   Authorization: Bearer <access token>
```

要点：
- 用户在浏览器完成一次授权（P2），之后所有会话都用 admin 里存的 refresh token 换 access token，用户无感（P3）。
- 运行时**不持有任何长期凭据**，只持有一个进程内、有 TTL、按 (mcpId, userId) 分片的访问令牌缓存。
- 换发接口只接受 `sessionId`，用户由 admin 侧 `session.creator` 反查，不接受客户端自报 `userId`（防伪造）。

## 5. 数据模型（迁移 V25 / V26；V23 是名称唯一键，V24 已被 agent / session 的租户回填占用）

### 5.1 `mcp_server` 增列（V25）

| 字段 | 说明 |
|------|------|
| `auth_type` | `NONE`（默认，等价现状）/ `STATIC_HEADER`（现状的 `headers`，显式化）/ `BASIC` / `OAUTH2`。`NONE`+`STATIC_HEADER`+`BASIC` 都不需要 §6 的流程 |
| `oauth_config` | **仅非敏感**配置 JSON：`authorizationServer`（可空，留空则走发现）、`scopes`、`audience`、`resourceIndicator`（是否带 RFC 8707 `resource`，默认 true）。`client_id`/`client_secret` 不在此列——它们属于 `mcp_oauth_client` |

生成列与唯一键：`auth_type` 不参与任何索引；`oauth_config` 与 `headers` 同性质（文本 JSON，不加密，因为里面不允许出现密钥）。

白名单由**类型**保证而不是过滤代码保证：`oauth_config` 的载体是 `McpOAuthConfig` 这个 DTO，字段集就是上表四个，反序列化时多出来的键（`client_secret`、`refresh_token` 等）落不进这个类型，也就写不进这一列。回调Origin白名单（`callback_allowed_origins`）首版不做：回调地址由 §5.2 的 `callback_url` 精确匹配承担，再加一层可配置白名单只会多一个配错的地方。

`McpServerServiceImpl` 在 create / update 上强制这些规则（命中即 `BizException`，不静默纠正）：

- `auth_type` 只能是 `NONE` / `STATIC_HEADER` / `OAUTH2`；缺省按 `NONE` 处理。`BASIC` 列已建但运行侧还没接，先拒——存一个运行时完全不认的值等于告诉管理员「配好了」而请求仍是裸的。
- `type=stdio` 不接受 `OAUTH2`：stdio 没有 HTTP 请求可挂令牌。
- `oauth_config` 只在 `auth_type=OAUTH2` 时允许；`auth_type` 从 `OAUTH2` 切走时清空该列（更新语义是「给了就整体替换，不给就保持原值」，所以清空必须显式做）。
- `authorizationServer` 非空时必须是 `http(s)://` 开头：发现阶段 admin 会真的去请求这个地址。
- `oauth_config` 读出来解析失败时当作未配置并打 warn，不让一行坏数据把列表接口打挂。

V25 **不做数据回填**：`NONE` 与 `STATIC_HEADER` 走同一条代码路径，而 `headers` 里除了凭据还混着路由用的普通头，回填出来的标签和真正的决策分不开。所以历史行统一留 `NONE`，要区分 `STATIC_HEADER` 由管理员显式选。

### 5.2 `mcp_oauth_client`（V26）—— AS 侧的客户端身份，按 (租户, AS) 共享

| 字段 | 说明 |
|------|------|
| `tenant_id` / `issuer` | 归属租户与 AS 的 `issuer`（严格字符串匹配校验，见 §9）；`issuer` 为 `COLLATE utf8mb4_bin` |
| `client_id` / `client_secret_enc` | 预注册或 CIMD 得到的 client_id；secret 走 `AesUtil` 加密，与 `headers` 同口径掩码回显 |
| `registration_source` | `MANUAL` / `DCR` / `ID_METADATA`，标识来源便于轮转 |
| `authorization_endpoint` / `token_endpoint` / `registration_endpoint` / `revocation_endpoint` / `scopes_supported` | 发现结果快照，避免每次请求都打 AS |
| `callback_url` | 精确值（`COLLATE utf8mb4_bin`），AS 侧要求白名单匹配，不做前缀匹配 |
| `active_client_id`（生成列）+ `uk_mcp_oauth_client_tenant_issuer_client` | 沿用 V15 / V23 的软删除唯一键写法：`active=0` 时生成列转 NULL，唯一索引忽略 NULL，删掉后可重新登记 |

`issuer` / `callback_url` 用二进制排序规则，其余列保持表默认：MySQL 默认排序规则不区分大小写，而 RFC 8414 的 `iss` 与 RFC 6749 的 `redirect_uri` 都是精确字符串比较，折叠大小写会把另一个授权服务器的注册悄悄复用过来。代价是管理员手输 issuer 时大小写写错会得到「没有注册」而不是自动命中，宁可重来一次也不合并两个 AS。

复用查询 `selectByTenantAndIssuer(tenantId, issuer)` 带 `ORDER BY id LIMIT 1`：唯一键允许同一 issuer 挂不同 `client_id`，此时必须有确定答案，否则同一租户会在一个 AS 上来回注册第二个客户端。Mapper 只有 `selectByTenantAndIssuer` / `insert` / `updateById` 三个方法；`updateById` 的 SET 列表不做判空，因为「AS 不再支持 DCR」要把 `registration_endpoint` 写成 NULL，判空写法会把一个已经失效的端点留在库里继续被调用。行身份列（`tenant_id`、`issuer`）不出现在 SET 里，与 `mcp_server` 一致。

### 5.3 `mcp_user_credential`（V26）—— 用户 × MCP 服务的授权结果

| 字段 | 说明 |
|------|------|
| `tenant_id` / `user_id` / `mcp_id` | 三元唯一（`uk` 建在 `(tenant_id, user_id, mcp_id)`）；`user_id` 是 `sys_user.id`，不是用户名 |
| `refresh_token_enc` | AES-GCM 密文，**只存 admin**，不出现在任何响应 DTO |
| `access_token_enc` / `access_expires_at` | 快照，用于跨进程重启复用与提前刷新判断；过期即当作缺失 |
| `scopes` | 实际授予的 scope（可能小于请求的，上游可裁剪） |
| `status` | `ACTIVE` / `NEEDS_CONSENT`（refresh 失效或被 403 `insufficient_scope` 拒绝）/ `REVOKED` |
| `last_error` / `last_refreshed_at` | 排障用；`last_error` 不得含令牌片段 |

实现口径（V26 已按此建表）：

- **没有 `active` 列，也不做软删除**。撤销是在原行上把 `access_token_enc` / `refresh_token_enc` 写成 NULL 并置 `status = REVOKED`，用户重新授权时覆盖同一行；软删除会留一行继续占住 `(tenant_id, user_id, mcp_id)`，把重新授权的 insert 顶掉。硬删除只发生在删除 MCP 服务时（`deleteByMcpId`，与 `agent_mcp_binding` 一起在同一事务里清）。
- `updateById` 的 SET 列表**全部无条件**：判空写法永远写不回 NULL，撤销就会在库里留下一把「仍可用但管理员以为已撤销」的令牌。
- `selectByUserAndMcp(tenantId, userId, mcpId)` 把租户放在查询条件里而不是事后过滤，与 §4 的租户口径一致。
- Mapper 只有 `selectByUserAndMcp` / `insert` / `updateById` / `deleteByMcpId` 四个方法，没有列表方法：这张表的行只按「谁对哪个服务」定位，不存在需要翻页的场景，多一个入口只会多一条绕过租户条件的路。

### 5.4 `mcp_call_log`（V26）—— 调用级审计

`tenant_id` / `user_id` / `mcp_id` / `session_id` / `tool_name` / `action`（`ISSUE` 换发 / `REFRESH` 刷新 / `REVOKE` 撤销 / `CALL` 工具调用）/ `outcome`（`OK` / `AUTH_FAILED` / `NEEDS_CONSENT` / `ERROR`）/ `latency_ms` / `create_time`。只记结果与耗时，**不记请求体、不记 Authorization 头**。首版允许 `McpHelper` 侧异步批量写，不阻塞工具调用。

`action` 是方案定稿之后补的一列：只有 `outcome` 的话，「换发失败」和「调用失败」在审计里长得一样，而前者要查授权链路、后者要查工具本身。Mapper 只有 `insert`，没有 update / delete——能被应用改写的审计表就不是审计表。`user_id` 允许 NULL：解析不到会话归属的运行侧调用也要留得下账。

## 6. 认证流程

### 6.1 服务接入与发现（管理员，一次性）

```
1. 建 MCP 服务，auth_type=OAUTH2，只填 url
2. POST /api/admin/mcp/{id}/oauth/discover
     ├─ 无 authorization_server：GET {url}/.well-known/oauth-protected-resource（先子路径后根）
     │  或读上游 401 的 WWW-Authenticate: resource_metadata="..."
     ├─ 拿到 authorization_servers[0] → 该 AS 的 issuer
     └─ AS metadata（OIDC 时退到 /.well-known/openid-configuration）→ 端点 + scopes_supported
3. client 来源，按优先级：
     预注册（管理员手填 client_id/secret）> CIMD > DCR（上游支持才自动注册）
4. 结果写 mcp_oauth_client（按 (tenant, issuer) 复用，不重复注册）
```

失败口径：发现不通就报错让管理员看到，**不降级成静态 header**。

上面这条已随 **P2-2 落地**（实现与逐条口径见 `mcp-management` §3.5）：两个接口、四类 AS 元数据候选、`mcp_oauth_client` 按 (租户, issuer) 复用、发现到的 issuer 回写 `oauth_config`。两点与图上不同：第 3 步今天只有「管理员手填」一条路（CIMD 与 DCR 在 P4），以及发现成功但还没登记 client 时，那行的 `client_id` 是空串——它是一个显式状态「端点已知、client 未登记」，第 6.2 节那条路必须对它报错。

### 6.2 用户首次授权（授权码 + PKCE）

```
用户点「授权」 → GET .../oauth/authorize-url
   admin 生成 state(随机) + code_verifier(随机)，二者与租户/用户/mcp/issuer/pkce/resource/请求 scope 一起存内存态（短 TTL），拼：
     authorize?response_type=code&client_id=..&redirect_uri=..&scope=..
              &code_challenge=BASE64URL(SHA256(verifier))&code_challenge_method=S256
              &resource=<MCP url>          ← RFC 8707，明确 audience，禁止把 token 透传给 MCP
              &state=..
   → 浏览器跳转 AS → 用户登录同意 → AS 重定向到 redirect_uri = 前端路由 /mcp/oauth/callback?code=..&state=..
   → 落地页先 history.replace 抹掉 query，再带着浏览器已有的 JWT 调 POST /api/admin/mcp/oauth/exchange
   → admin 取调用者身份 → 先烧 state → 依次判：上游报了 error / pending 不存在 / 归属不是调用者 / 没有 code
   → 都不成立才用 code+verifier 换 token（带 redirect_uri、resource）
   → 严格校验响应里的 iss == §6.1 的 issuer、aud 含 resource → 加密存 mcp_user_credential(ACTIVE)
   → 回一句人话 + 实际授予的 scopes + 过期时间（这个响应里没有任何字段装得下 token）
```

约束：`redirect_uri` 与注册值精确匹配（注册的是**前端页面**的地址，不是本服务的接口）；`state` 一次性、无论哪一支都先烧掉，且归属与调用者不符就拒绝；落地页不显示任何令牌、不把 `code`/`state` 写进任何持久化存储；scope 只请求该 MCP 工具面需要的最小集。

上面这条已随 **P2-3 落地**。实现口径：

- 内存态而非数据表：`McpOAuthStateStore` 里一条 `PendingAuthorization` 存 (租户, 用户, mcp, issuer, verifier, redirect_uri, resource, 请求 scope)，TTL 5 分钟、全局上限 500 条、每人另有 5 条上限（第十五轮加的，在生成 verifier 与 state **之前**先 `countFor(userId)` 数一遍）、`consume` 取走即失效。选内存是因为 code 本身就是一次性、分钟级的东西，落表等于把「防重放」变成「防重启后重放」——代价说清楚：**admin 重启会打断正在同意的那一次授权**，用户得重新点。两条上限都是显式拒绝、各回一句实话（每人那条把上限与最长等待时间都说了），不是静默丢弃；500 条是所有人共用的预算，没有每人一条挡在前面，一个带脚本的登录用户就能把它填满。
- 换票带 JWT，`state` 不再承担身份：AS 的重定向落到**前端一条路由**，由落地页带 JWT 调 `POST /api/admin/mcp/oauth/exchange`，身份取自 Spring Security 的认证上下文。`pending.userId` 因此从「身份」降级为「这次同意该记到谁名下」的一条断言：与调用者不符就拒绝并 warn（只记 mcpId 与两个 userId，不记 code 与 verifier）。这道比对排在**读请求体任何字段之前**（第十五轮修的）——否则知道别人 `state` 的人 POST 一个自造的 `error` 就能替对方取消那次在途授权，而那条 warn 一个字都不会说；反过来 AS 真报了 `error` 时，即使 pending 已经过期也照样把上游的理由回给页面，因为那种情形下什么都没存、也没什么可烧。本服务不再有免鉴权的 OAuth 入口，`SecurityConfig` 与 `JwtAuthenticationFilter` 为旧回调开的两处放行已删除。这一条就是 F6 的闭合方式，见 §8 第 7 条。
- PKCE 无条件 `S256`（§12.1 里预定的那个决定）：发现阶段没有快照 AS 的 `code_challenge_methods_supported`，因为 OAuth 2.1 与 MCP 都把 PKCE 定为强制项，AS 不支持 `S256` 就在它自己那侧报错，admin 不做降级。
- 落库前只认两件事：响应级 `iss` 与 JWT 声明 `iss` 都跟发现的 issuer **字节相等**（`utf8mb4_bin` 同规则），`aud` 必须含本次的 `resource`。
- 「端点已知、client 未登记」那个空 `client_id` 状态（§6.1）在发起授权时就拒，不让用户带着 `client_id=` 去撞 AS 的错误页。
- 三道中途变更的守卫：请求带的 `scope` 宽过 `mcp_user_credential.scopes`（VARCHAR 512）就拒（截断等于替用户记下一份没人同意过的 scope 清单）；换 token 时服务行按 `pending.mcpId` 经带租户守卫的 `McpServerService.getMcpServer` 重读，行没了、已被挪出原租户、不再是 `OAUTH2`、或 `url` 已与 `state` 里钉住的 `resource` 不一致，都不换（code 是给旧地址换的，存下来是一把开不了门的钥匙）。
- 换票回给前端的是一段 JSON，被拒时也是一句人话而不是一个 HTTP 状态（`authorized=false` + 下一步该怎么办）：`code` 已经花掉，页面重试不了。上游那句 `error_description` 原样拼进这句话（截 200 字符、抹 userinfo），由 React 当文本节点渲染——这与「先 `HtmlUtils.htmlEscape` 再上 HTML」是同一道防护换了实现层，和 `listTools` 带回来的上游字符串同一口径。成功时回答里的 `scopes` 是**落库那一列记下的清单**，不是 token 响应里那个原始 `scope` 字段（RFC 6749 §5.1 让它可选，AS 授的就是请求的那些时可以不回；照那份空清单回答会让落地页显示零条、几秒后详情页的 `status` 读同一列却显示出两条——第十五轮修的）。整段话不含任何令牌。

### 6.3 运行时取用（每个会话）

```
Agent 装配（HarnessAgentLauncher.kt:174-188）
  auth_type=OAUTH2 → McpClientBuilder.httpRequestCustomizer { rb, _, _, _ ->
      rb.header("Authorization", "Bearer " + tokenCache.get(mcpId, userId))   ← 闭包捕获，见 §7
  }
每次工具调用发起 HTTP 请求 → customizer 触发 → 命中缓存则直接注入
  未命中/临近过期 → POST /api/admin/internal/mcp/access-token {sessionId, mcpId}
      admin：解析 sessionId.creator → sys_user.id → 校验 (user,mcp) 有 ACTIVE 凭据
             → access 未过期就回，过期就 refresh（旋转后覆盖密文）→ 返回 token+expiresIn+scope
             → 写 mcp_call_log
      无凭据/refresh 失效 → 返回 401 + code=NEEDS_CONSENT → 凭据置 NEEDS_CONSENT，前端提示重新授权
```

### 6.4 失效、撤销与删除

- 撤销（已随 **P2-3 落地**，`POST .../oauth/revoke`）：**本地一定清干净**（置 `REVOKED`、两个密文写回 NULL、`last_error` 也清），上游只在「这行注册登记过 `revocation_endpoint`」时才发一次 RFC 7009 请求，`token_type_hint` 优先 `refresh_token`——access token 几分钟后自己就死了，refresh token 才是能一直 mint 新令牌的那个，合规的 AS 撤销它会连带整个 grant。**六种情况各回一句实话，且互不顶包**：无撤销端点（「上游那份仍然可用，直到它自己过期」）、库里已无令牌、密文解不开（换过 AES 密钥——「解不开」既不是「没有令牌」也不是「上游拒了」，且**不能因此不清本地**：撤销是用户唯一的出路）、注册行已删（说不出这条授权属于哪家 AS，同时给出重新发现的路径）、上游接受、上游拒绝（「本地已清，上游未清」）。上游拒绝时不重试也不改成 `NEEDS_CONSENT`：那需要一次真实的调用才能观测到，属 §6.3 的换发侧（P2-4）。这六句话的形状是第十三轮补的，理由见 `mcp-management` §7.12。
- 删除 MCP 服务：`deleteMcpServer`（`McpServerServiceImpl.kt:209-233`）在删行的同一事务里清掉 `agent_mcp_binding` 与 `mcp_user_credential`；`mcp_oauth_client` 只按 (tenant, issuer) 复用，别的服务可能还在用，故不动。
- 改认证方式（第十五轮补的）：`updateMcpServer` 把 `authType` 从 `OAUTH2` 改成别的时，同样清掉这台服务的全部 `mcp_user_credential`。不清就成死数据——凭据的读与撤都要先过 `requireOAuthServer`（`authType != OAUTH2` 直接拒），所以改完之后**用户既看不到也撤不掉自己那条授权**，剩下的是一串没人管得着的密文。口径与删除路径一致：本地清行 + `warn` 出清了几条，上游那侧的副本不呈递撤销、靠自己过期（要真撤上游，得在改认证方式之前由用户逐条点撤销）。判断「这次请求是不是关掉了 OAuth」用的是**覆盖任何字段之前**读出来的 `wasOAuth`；从来不是 OAuth 的服务（含 V25 之前 `authType` 为 null 的老行）一次都不查这张表。
- 用户被禁用/退出租户：`mcp_user_credential` 保留但换发接口拒绝（状态不变），避免误删用户凭据。
- 公开 MCP（`is_public=1`）在同一租户内共享的是**服务配置**，不是**别人的凭据**：换发接口永远按当前会话的 user 查凭据。

## 7. 运行侧注入（代码级）

1. `McpDetailDto`（`harnax-entity/.../dto/McpDetailDto.kt:10-37`）与运行时 `McpConfig` 增加 `authType`、`oauthScopes`；`OAUTH2` 时上游不再注入 `headers` 里的 `Authorization`（二者互斥，装配时校验并 warn）。
2. `McpHelper.createMcpClient`（`McpHelper.kt:100-148`）内按 `authType` 分支挂 `httpRequestCustomizer`，同时补 `timeout(...)` / `initializationTimeout(...)`。
3. **令牌的用户身份必须来自闭包，不能来自 `McpTransportContext`。** 实测：`McpClientWrapper.callTool(name, args, meta)` 只把 `McpMeta` 放进 JSON-RPC 体，**没有**把 `McpTransportContext.KEY` 写进 Reactor context，于是 `customize(...)` 收到的 context 恒为 `EMPTY`。结论是「谁的用户」只能在**建客户端时**确定 —— 因此 `DefaultAgentRunner` 的 Agent 缓存（`:58-64` Caffeine，30 分钟）必须能判定归属：实现上仍以 `sessionId` 为键，但缓存值 `CachedAgent` 记录建它时用的 `userId`，请求携带的 `userId` 与缓存归属不一致时直接失效重建，否则不同用户会共用一个客户端与其令牌。
4. 401 不做「刷新后重试一次」：customizer 无法重试（`McpSyncHttpClientRequestCustomizer.customize` 返回 `void`，无重试钩子）。改为**提前刷新**（`expiresAt - 60s`）+ 缓存 single-flight，避免同会话并发打多次换发。上游提前吊销时，本次调用失败并把该条目判过期，下一次请求自愈 —— 与「静默吞掉鉴权失败」比，宁可让工具调用可见地报错。
5. stdio 无 HTTP 头可注入，不支持 OAuth；继续走 `env_params`，并在 UI 上对手工填的密钥提示「值被掩码回显时请勿原样保存」（P0 修复已让后端兜住）。
6. SSE 传输同样可行（`customizeSseClient`），首版与 `streamablehttp` 共用一个 customizer。

## 8. 安全约束（逐条对应规范/事故）

1. **不得通过 MCP 协议本身透传 token**（confused deputy）：token 走 HTTP `Authorization` 头，且 `resource` 参数绑定 audience（OAuth 2.1 for MCP）。
2. refresh token 只在 admin 库内加密存在；`McpDetailDto`、`McpServerResponse`、`AgentSpecInfoResponse` 一律不出现。
3. 令牌只比对、不消费：`iss` 与发现到的 `issuer` **精确字符串**相等，`aud` 含目标资源（RFC 8707）。这两项比较要求读一下 JWT 声明，所以 P2-3 的实现是**解出不验签**——本地不校验签名，因为没有任何代码路径使用 token 的内容，它只被原样转发给 MCP 服务，而校验它是 MCP 服务的职责。opaque token 根本解不出来，这种情况**记日志而不是当成通过**：此时资源绑定只剩「AS 遵守了 `resource` 参数」这一条假设。
4. scope 最小化；请求 scope 与实际授予 scope 不一致时记录差值。
5. **绝不静默提权**：403 `insufficient_scope` 只置 `NEEDS_CONSENT` 并提示用户重走同意，不自动重授权。
6. 公共客户端（无法安全保存 secret）必须 PKCE + refresh token 轮转，旋转失败即判 `NEEDS_CONSENT`。
7. `state`、`code_verifier` 一次性、短 TTL、绑定发起人。**第十四轮起这条绑的是凭据归属，不再是身份**：换票走 `POST /api/admin/mcp/oauth/exchange`，调用者由 JWT 认证，`state` 认出的那条 pending 只回答「这次同意该记到谁名下」，两者不一致就拒绝（`state` 无论哪一支都先烧掉，所以钓到的 `state` 一次也试不成）。原来那个「无 JWT 的回调 + 发起方自造的 `state` = 可以把别人的同意记到自己名下」的缺口（**F6**）随之闭合，实现细节与没验证的部分见 `mcp-management` §7.13；第十五轮又修了这条链上的判定次序（归属比对必须排在读请求体之前），见 §7.14。**残留要说清**：这挡的是「把 A 的授权链接拿去骗 B 同意，结果记到 A 名下」，挡不住「登录用户自己完成一次授权」——后者本来就是这条链路的能力。至于**在 AS 那个页面上是谁登录着点的同意**，由 AS 自己的会话决定，本服务看不见也管不着：`state` 只保证这次同意是给这台服务的（`resource`），不保证点同意的人是谁。
8. 日志与 `last_error` 复用 `redact()` 口径（见 skill-management R2-3 的凭据泄漏教训），禁止输出 `Bearer` 后的内容。
9. 换发接口需 `InternalApiAuthFilter` 的共享密钥（`InternalApiAuthFilter.kt:37-49`），并且只接受 `sessionId` 反查用户；下一步应改挂 `InternalTokenProvider` 的短时效 JWT（`InternalTokenProvider.kt:14-76`），把静态密钥下掉。
10. 审计（`mcp_call_log`）不可关闭；`tool_call_log` 目前不在 `MybatisTenantInterceptor.EXCLUDED_TABLES` 的豁免名单之外（该拦截器整体被注释掉，未生效），所以租户隔离要在 SQL 里显式写。

## 9. 接口清单

### 9.1 管理端（浏览器，JWT）

| 方法 | 路径 | 作用 | 状态 |
|------|------|------|------|
| POST | `/api/admin/mcp/{id}/oauth/discover` | 跑 §6.1 的发现，回显 AS 元数据 | **已实现**（P2-2） |
| POST | `/api/admin/mcp/{id}/oauth/client` | 手填/更新 client_id+secret（或触发 DCR） | **已实现**（P2-2，只到手填；DCR 属 P4） |
| GET | `/api/admin/mcp/{id}/oauth/authorize-url` | 给当前用户生成授权跳转地址（可带 `scope` 覆盖） | **已实现**（P2-3） |
| POST | `/api/admin/mcp/oauth/exchange` | 换票：接落地页送来的 `code` / `state` / `error`，**带 JWT**，凭据写给调用者本人且要求这次 `state` 就是他发起的；路径里没有 `{id}`，目标服务只能由 pending 说 | **已实现**（第十四轮，取代原先免 JWT 的 `GET /oauth/callback`） |
| GET | `/api/admin/mcp/{id}/oauth/status` | 当前用户是否已授权、scope、过期、`NEEDS_CONSENT` | **已实现**（P2-3） |
| POST | `/api/admin/mcp/{id}/oauth/revoke` | 撤销当前用户授权 | **已实现**（P2-3） |

六个 JSON 接口都带 JWT；五个带 `{id}` 的先过 `McpServerService.getMcpServer`（租户守卫）再要求 `authType=OAUTH2`，不带 id 的那个由 pending 认服务；发现类回显只带 `clientSecretPresent` 布尔，不带密钥；`authorize-url` / `exchange` / `status` / `revoke` 都要求当前请求带得出用户身份（授权是逐人的，取不到就报错而不是按租户 1 处理）。原先还有一个 `GET /api/admin/mcp/oauth/callback`——免 JWT、回 HTML——已在第十四轮删除；删掉之后，admin 里还需要鉴权判断的 `permitAll` 业务路径只剩 `/api/admin/internal/**` 一条（其余 `permitAll` 是登录 / 验证码 / `mp/auth` / swagger，本来就是公开的）。

授权状态在**详情页**可见（第十四轮随 P2-5 落地）：`OAuthPanel` 挂载即读 `status`，三态徽标（`未授权 / 已授权 / 需重新授权`）+「去授权」+「撤销」。列表页那个徽标**还没做**，随 P3 一起——`NEEDS_CONSENT` 要到 P2-4 的换发侧才有代码会写，今天批量加一个接口只会显示得出两种状态，而详情页已经能看到同一行数据。i18n 键走 `pages.mcp.oauth.*`（`src/locales/{zh-CN,en-US}/pages.ts`）。

### 9.2 内部端（agent-service，共享密钥）

| 方法 | 路径 | 入参 | 出参 |
|------|------|------|------|
| POST | `/api/admin/internal/mcp/access-token` | `sessionId`, `mcpId` | `accessToken`, `expiresIn`, `scope`；无授权时 401 + `code=NEEDS_CONSENT` |

不加 `X-User-Id` 之类的客户端自报字段。`GET /api/admin/internal/agent-spec/{sessionId}`（`InternalApiController.kt:184`）保持不变，用户身份在换发时由 admin 反查，避免把用户信息塞进所有下游响应。

## 10. 分期、工作量与验收

| 阶段 | 内容 | 人日 | 验收 |
|------|------|------|------|
| P0 安全止血 | 掩码回写不再销毁凭据；单行读写补租户归属；`update_*_by_id` 的 `update_time`；名称唯一键迁移；绑定 id 校验 | 1（**已完成**） | 已验证：secret 条目传掩码值时沿用库中密文（`SecretFieldEncryptorTest`）；跨租户 by-id 读 / 删被拒（`McpServerServiceImplTest`）；`V23` 唯一键 + `selectByName(name, tenantId)`（`schema-test.sql` 与 `McpServerMapperTest`）；绑定 id 批量校验（`AgentServiceImplTest`） |
| P1 身份贯通 | `api-keys/validate` 响应 + `ApiKeyInfo` + `AuthContext` 带 `userId`；`ChatAgentRequest`/`AuthContext` → `UserIdentifier`；Agent 缓存值记录归属 userId（跨用户即重建）；`agent.tenant_id`、`session.tenant_id` 真正落库并按租户过滤列表与单行读写；下发侧按 agent 租户过滤 MCP；`V24` 回填历史行归属 | 3–4（**已完成**） | 已验证：`DefaultAgentRunner` 不再有 `UserIdentifier(0)`，请求带的 userId 一路透传到 `UserIdentifier`（`DefaultAgentRunnerTest`）；router 用认证用户覆盖请求体的 userId（`AgentProxyControllerTest`）；缓存归属不一致即重建（`DefaultAgentRunnerTest`）；`AgentMapper.xml` 的 resultMap / insert / `selectAgentList` 补齐 `tenant_id`（`AgentMapperTest`）；建会话时打租户标、`selectSessionList` 带租户条件（`SessionServiceImplTest`、`SessionMapperTest`）；mp 侧跨租户取 Agent 详情、建会话被拒（`MpAgentServiceTest`、`MpAgentControllerTest`）；`agent` 单行读按租户、启停与删除都不绕过这道守卫（`AgentServiceImplTest`）；下发侧别租户的 MCP 两半答案都不出现、也不解密（`InternalApiControllerTest`）。`V24` 是数据回填：它随 `harnax-admin` 的 `*IT`（failsafe，全新 MySQL 8 + Flyway）跑通了「按序执行不报错」——`V1..V26` 全链路成功；但空库里回填不出任何东西，**上线前仍需拿一份生产数据副本先跑一遍**核对回填条数。 |
| P2 OAuth 客户端 | `V25`/`V26`；发现 + client 注册；授权码 + PKCE 全流程；admin 6 个接口 + 前端授权入口；`mcp_call_log` | 5–7（**P2-1、P2-2、P2-3、P2-5 已完成**：数据模型 + 管理侧校验、发现 + 客户端登记、授权码 + PKCE + 凭据存取与撤销、用户侧授权入口与换票接口（第十四轮，同时闭合 F6），见 §12.1；只剩 P2-4 换发） | 用 Keycloak/AS 测试实例走通：发现 → 授权 → 存 → 撤销；刷新令牌不出 admin。**仍未跑过真实 AS**，且第十四轮把 `redirect_uri` 的形状改成指向前端路由——AS 侧的登记要人手改一次，改之前第一次授权必然被 AS 拒 |
| P3 运行侧注入 | `authType`/`scopes` 下发；`McpHelper` 挂 customizer；进程内缓存 + 提前刷新 + single-flight；超时补齐 | 3–5 | 上游令牌 5 分钟过期场景下连续调用工具不掉线；无授权用户得到明确「需授权」提示 |
| P4 其余方式 | `BASIC`、`client_credentials`、mTLS（`customizeStreamableHttpClient`）、DCR | 3–8 | 每种至少一条集成用例 |

总计约 15–25 人日，其中 P0 + P1 已完成（约 4–5），P2 已完成四条（P2-1 数据模型与管理侧校验、P2-2 发现与客户端登记、P2-3 授权码 + PKCE + 凭据存取与撤销，合计约 4；P2-5 用户侧入口与换票接口约 1.5），只剩 P2-4（约 1–1.5）；加上 P3/P4 后剩余约 5–16。P1 是 P2/P3 的硬前置，现已就位。
回归面：`McpServerServiceImplTest`、`McpOAuthServiceImplTest`、`McpOAuthUserServiceImplTest`、`McpOAuthStateStoreTest`、`McpOAuthControllerTest`、`SecretFieldEncryptorTest`、`RemoteJsonFetcherTest`、`InternalApiController*`、`harnax-auth` 全套、`DefaultAgentRunnerTest`、`AgentProxyControllerTest`、`McpServerMapperTest` / `AgentMapperTest` / `SessionMapperTest`（`harnax-entity` 那批 Mapper 集成测试需要 Docker；本机 Docker 可用时是全绿的，之前记成「环境失败」的其实是 §11 里那条 mapper XML 解析缺陷）。当前实测数：`harnax-admin` 单测 1768 全绿（P2-2 新增 `McpOAuthServiceImplTest` 30、`RemoteJsonFetcherTest` 9、`SecretFieldEncryptorTest` 的 `ResolveSecretTests` 6，第八轮再加 `McpOAuthServiceImplTest` 17、`RemoteJsonFetcherTest` 5、`AuthTypeTests` 2，第十轮再加 7（掩码回写那一批：`SecretFieldEncryptorTest` 2、`CliServiceImplTest` 1、`EnvVariableServiceImplTest` 1、`AgentToolServiceImplTest` 3），第十一轮再加 `McpOAuthControllerTest` 6——守的是两个 OAuth 接口对外的错误语义，不是发现逻辑；第十二轮 P2-3 再加 64（`McpOAuthUserServiceImplTest` 47、`McpOAuthStateStoreTest` 6、`McpOAuthCallbackControllerTest` 4，以及 `McpOAuthControllerTest` 新增 7 条覆盖按人四个接口），第十三轮再加 6（`RemoteJsonFetcherTest` 14→16 两条真出站用例：失败响应的体要能解析、表单 POST 的参数要按 URL 编码带出去；`McpOAuthUserServiceImplTest` 47→51 四条：宽授予清单、撞唯一键、解不开的密文、没了的注册行），第十四轮净加 8（`McpOAuthUserServiceImplTest` 51→58：回调那 22 条改写成换票 29 条；`McpOAuthControllerTest` 13→16；`McpOAuthServiceImplTest` 47→49 默认回调地址两条；`McpOAuthCallbackControllerTest` 随接口删除 −4））、`harnax-entity` 232 全绿（含第八轮 `McpServerMapperTest` 的两条 `updateOAuthConfig`），`V1..V26` 由一条 `harnax-admin` 的 failsafe IT 在全新 MySQL 8 上按序执行成功；跑法与两个坑（`-am`、`TESTCONTAINERS_RYUK_DISABLED=true`）见 `docs/unit-test-cases.md` §17。

## 11. 与现有文档/已知缺陷的关系

- 名称唯一键 `V23` 与 `selectByName(name, tenantId)` 属 P0，已落地；§2.1（`mcp-management.zh-CN.md`）的「服务名全局唯一」已改成「租户内唯一」，并在 §7.3 记录了实现口径。
- 本方案不改 `agent_mcp_binding` 的语义：绑定仍是「Agent 用哪些 MCP + 环境参数快照」，用户授权是**运行时按人**解析的，不进绑定表。
- 原「`AgentMapper.xml` 不映射 / 不插入 `agent.tenant_id`」的已知遗留已随 P1 关闭：resultMap 与 insert 现在带这一列，`selectAgentList` 有租户条件，单行读 `AgentServiceImpl.getAgent` 按当前租户判归属（跨租户按「不存在」回答），`updateAgent` / `toggleAgentStatus` / `deleteAgent` 与 `AgentController` 的单行读都走这一道；mp 侧 `MpAgentService.getAgentDetail`、`MpSessionService.createSession` 用 `sys_user.tenant_id` 拒跨租户。`session` 侧建会话时写当前租户（不再靠 DDL 缺省值），`selectSessionList` 补了租户条件。下发侧同一口径：`selectByIds` 没有租户条件也不该有（内部调用没有可信的 `X-Tenant-ID` 可注入），故 `InternalApiController.buildAgentSpecResponse` 拿 `agent.tenant_id` 比一刀，别租户的服务行在两半答案里都不出现、连解密都不发生。历史行由 `V24__backfill_agent_session_tenant.sql` 按创建人主租户回填（与 `V22` 回填 `mcp_server` 同规则），否则新加的过滤会先挡住租户 2 用户自己的 agent 和会话。测试库的 `agent` 建表语句此前既无 `tenant_id` 又多了 prod 没有的 `UNIQUE KEY uk_name`，已按 `V1__init_schema.sql:195-215` 对齐。测试 schema 现在只有 `harnax-entity/src/test/resources/schema-test.sql` 一份：`MySQLContainer.withInitScript("schema-test.sql")` 加载的就是它，`harnax-admin` 下那份同名文件不在任何 classpath 引用上、且已经漂移到「还写着 `uk_name`、没有 V23 生成列」，维护两份只会让漂移继续，所以删掉了 admin 那份。
- P1 未闭合的两处，留给后续：其一，`HarnessAgentWrapper.userId` 至今没有被 `HarnessAgentLauncher` 赋值，状态存储的键里仍不含用户——改它会把已持久化的会话状态键挪位，故本次不动；其二，飞书 / 定时任务 / CLI 三个入口带下来的 `userId` 仍是 null（见 §2.2），P2 的换发接口对这三处只能报 `NEEDS_CONSENT` 之前先要求它们接上用户身份。
- P2-2 期间查出并修掉一个**已上线但失效的配置**：`harnax-admin/application.yml` 把 `base-url` 挂在 `admin:` 下，而读它的是 `@Value("\${app.base-url:...}")`（`ChannelServiceImpl` 与部署文档都用这个 key），于是 `APP_BASE_URL` 从未生效，渠道回调一直退回 `http://localhost:8080`。已移到顶层 `app.base-url`。这条与本方案直接相关：OAuth 的 `redirect_uri` 必须精确匹配注册值（§6.2），照同一个缺陷读出来，就是每个真实部署的第一次授权都被 AS 拒绝。修到 `application.yml` 只算半程——第十一轮才把部署侧接上：`docker-new/docker-compose.yml` 的 admin 服务显式传 `APP_BASE_URL`，`.env.example` 列出这一项并写明 `redirect_uri` 按精确字符串比对，`docs/deploy-harnax-admin.md` 把它从「不支持环境变量覆盖」的表挪进环境变量表。部署里不传这一项，每个环境的 `redirect_uri` 仍然照旧是 `http://localhost:8080`。第十四轮把这两个用途拆开：新增 `app.frontend-base-url`（`APP_FRONTEND_BASE_URL`，留空即退回 `app.base-url`）只管**浏览器要回到的那个 origin**，OAuth 的缺省 `redirect_uri` 由它拼，渠道回调继续用 `app.base-url`——生产同域名代理下不配也是对的，开发下 SPA 在 :8000 而 admin 在 :8080，不配就把用户送进一个没有页面的端口。
- MCP 连接失败目前的口径是「warn 后继续装配，status=0 直接跳过」（见 mcp-management §7.2 第三轮）。**授权失败不属于「可跳过」**：无凭据要显式落到 `NEEDS_CONSENT` 并让用户看到，不能静默少装一个工具。
- P2-3 的验证口径要说清边界：整条链路（发起 → state → 换票 → token → 落库 → 撤销）是**单测**，出站 HTTP 在 `RemoteJsonFetcher` 那一层被打桩。§10 那行验收写的「用 Keycloak/AS 测试实例走通：发现 → 授权 → 存 → 撤销」**还没跑过真实 AS**，尤其没验过三家实现对 `resource` 参数的实际反应。接一个 AS 实例之前，这一项不该被当成已完成。第十四轮之后这条更硬：`redirect_uri` 现在指向前端路由，那个形状从未被任何 AS 接受过，而且管理员得先把新地址手工登进 AS，否则第一次授权在 AS 那侧就断。
- 待授权状态存在**一个 JVM 的内存**里（§6.2）。当前部署是单实例，成立；一旦 admin 起多副本，**换票**可能落在没见过这次 `authorize-url` 的副本上，用户看到的是「请求未知或已过期」。要多副本，就得先把路由做成粘性、或把 `McpOAuthStateStore` 换成共享存储（Redis 之类），二选一，不能靠重试。第十四轮把「回调落错副本」换成「换票落错副本」，**这条约束没有变严**，只是主语换了。第十五轮加的每人 5 条上限也在同一份内存里，因此同样是每副本各数各的：多副本下一个人实际能占的额度是 5 × 副本数，这不是新问题，是同一条前提的另一面。
- 本轮之后仍然存在的口子，分「有意留」与「堵不住」记清楚，别让人以为已经闭合：
    - **loopback 与 RFC1918 可达是设计决定**，不是遗漏：自建 AS 就住在这两段地址里，本项目自己的 docker-compose 也是。底线只拒链路本地 / 任意本地 / 组播与 `metadata.google.internal`。代价说明白：admin 若部署在一块能直连云元数据服务的网卡上，这道底线挡不住「先跳一跳再回来」的第二步。
    - **DNS rebinding 不在声明的能力内**：地址在这里解析一次做判断，真正建连时 `HttpClient` 会再解析一次，中间换指向就绕过了。要闭合得自定义 `SocketFactory` 或在网络上隔离，那是网络层的事，不是这个 fetcher 的事。
    - **没有角色层**：`SecurityConfig.kt:50-51` 到 `.authenticated()` 为止，任何登录用户都能对自己租户的行发起发现，也就是让服务端去请求他填进来的地址。「admin 只有管理员在用」这个假设目前兜着它，接上角色体系后要回头收紧。同一处口径还有 `McpServerServiceImpl.currentTenantId()`（`:82`）在上下文缺失时回落到租户 1。
    - **Spring Security 上只剩一条 `permitAll()` 业务路径**：`/api/admin/internal/**`，实际把关的是 `InternalApiAuthFilter` 的共享密钥——密钥校验通过就等于「已认证」，P2-4 的换发接口挂上去之前这条要重新看一遍（§8 第 9 条已记）。另一条 `/api/admin/mcp/oauth/callback` 已在第十四轮随 F6 一起删掉（其余 `permitAll` 只有登录 / 验证码 / `mp/auth` / swagger 这些本来就是公开的）。它曾经是个**天然可被外部撞的入口**：撞错的代价只有一句提示页加一条 warn 日志，`McpOAuthStateStore` 的 500 条上限是内存被撑的边界。现在这一面墙搬到了带 JWT 的 `POST /oauth/exchange` 上——没有身份连判断都进不去，500 条上限仍在，但它兜的已不再是陌生人可撞的资源；第十五轮又给「自家用户狂点」这一面加了每人 5 条的上限，于是一个登录用户填不满所有人共用的那 500 条。
- 本轮补 V26 时顺带查出并修掉一个**启动级**缺陷：`PlanNoteMapper.xml` 的注释体里写了 `A -- B`，而 XML 注释不允许出现连续连字符，MyBatis 解析该文件直接失败。三个服务的 `mybatis.mapper-locations` 都是 `classpath*:mapper/*.xml`，一个文件解析不了 = `SqlSessionFactory` 建不起来 = admin / scheduler / router **全都起不来**，不是「计划待办那一个功能坏了」。它同时解释了 `harnax-entity` 那批 Mapper 集成测试为什么整片红：不是环境（Docker）问题，是同一个解析失败，修完之后这些用例在本机 Docker 下全绿。为此在 `harnax-entity` 加了不依赖 Docker 的 `MapperXmlParseTest`：把 classpath 上所有 mapper XML 过一遍解析 + 单独检查注释体里的 `--`（解析器只报「not well-formed」，不指出是注释），让这类问题回到测试阶段就炸，而不是等到启动。

## 12. 实施状态

| 阶段 | 状态 | 已落地的迁移 / 文件 |
|------|------|---------------------|
| P0 | **已完成**（代码 + 迁移 + 测试） | `SecretFieldEncryptor.kt`、`McpServerServiceImpl.kt`、`McpServerMapper.(kt|xml)`、`McpServerUpdateRequest.kt`、`ApiErrors.kt`、`V23__add_mcp_server_name_unique_key.sql`、`AgentServiceImpl.saveMcpBindings`；测试 `SecretFieldEncryptorTest` / `McpServerServiceImplTest` / `McpServerMapperTest` / `AgentServiceImplTest` |
| P1 | **已完成**（代码 + 迁移 + 测试；`agent.tenant_id` / `session.tenant_id` 在 `V1` 就有列，`V24` 补的是历史行的归属） | 身份链路：`harnax-auth/.../{ApiKeyInfo,AuthContext,ExternalApiKeyValidator,InternalTokenProvider}.kt`、`InternalApiController.kt`（含下发侧按 `agent.tenant_id` 过滤 MCP）、`harnax-session-router/.../{RemoteApiKeyStore,controller/AgentProxyController}.kt`、`harnax-protocol/.../AgentRequest.kt`、`harnax-tools-sdk/.../ToolCallContext.kt`、`DefaultAgentRunner.kt`；租户：`AgentMapper.(kt|xml)`、`SessionMapper.(kt|xml)`、`AgentServiceImpl.kt`、`SessionServiceImpl.kt`、`MpAgentService.kt`、`MpAgentController.kt`、`MpSessionService.kt`、`harnax-entity/src/test/resources/schema-test.sql`、`V24__backfill_agent_session_tenant.sql`；测试 `DefaultAgentRunnerTest` / `AgentProxyControllerTest`（新增）/ `AgentServiceImplTest` / `SessionServiceImplTest` / `MpAgentServiceTest` / `MpAgentControllerTest` / `InternalApiControllerTest` / `AgentMapperTest` / `SessionMapperTest` |
| P2 | **P2-1、P2-2、P2-3、P2-5 已完成**（数据模型 + 管理侧校验；AS 发现 + 客户端登记 + 管理面前端；授权码 + PKCE + 凭据存取与撤销；用户侧授权入口 + 带 JWT 的换票接口，后者同时闭合 F6），只剩 P2-4 换发 | 见下表逐条 |
| P3 | 未开始 | — |
| P4 | 未开始 | — |

### 12.1 P2 逐条状态

| 子项 | 状态 | 已落地的迁移 / 文件 |
|------|------|---------------------|
| P2-1 数据模型与管理侧校验 | **已完成**（迁移 + 实体 + Mapper + DTO + Service + 测试） | 迁移 `V25__add_mcp_oauth_columns.sql`（`mcp_server.auth_type` / `oauth_config`，不回填）、`V26__add_mcp_oauth_tables.sql`（`mcp_oauth_client` / `mcp_user_credential` / `mcp_call_log`）；实体 `McpServer.kt`（两列）、`McpAuthTypes.kt`、`McpOauthClient.kt`、`McpUserCredential.kt`、`McpCallLog.kt`；Mapper `McpServerMapper.(kt|xml)`（resultMap / insert / updateById 带新列）+ 三个新 Mapper 的 `(kt|xml)`；DTO `McpOAuthConfig.kt`、`McpServerResponse.kt`（回显 `authType` + 解析 `oauthConfig`）、`McpServerCreateRequest.kt` / `McpServerUpdateRequest.kt`；服务 `McpServerServiceImpl.kt`（`resolveAuthType` / `validateAuthType` / `writeOAuthConfig`，删除时一并 `deleteByMcpId`，第十五轮起改认证方式离开 `OAUTH2` 时也清同一张表——见 §6.4）；测试 `McpServerServiceImplTest.AuthTypeTests`（12 项，第八轮加的是「编辑不擦掉发现的 issuer」与「手填地址没有 host 就拒」，第十五轮加的两条钉「只有离开 `OAUTH2` 才清凭据，没离开或从来不是 OAuth 的服务一次都不查这张表」）、`McpOauthClientMapperTest` / `McpUserCredentialMapperTest` / `McpCallLogMapperTest`（需 Docker）、`MapperXmlParseTest`（不需 Docker）；`harnax-entity/src/test/resources/schema-test.sql` 同步 V25 / V26 |
| P2-2 AS 发现 + 客户端登记 | **已完成**（接口 + 发现逻辑 + 出站护栏 + 测试 + 管理面前端；不碰运行时） | `admin/controller/McpOAuthController.kt`（`POST /{id}/oauth/discover`、`POST /{id}/oauth/client`）、`admin/service/McpOAuthService.kt` + `impl/McpOAuthServiceImpl.kt`（`resolveIssuer` 三级发现、`fetchMetadata` 四个候选 + `iss` 精确相等、`storeEndpoints` 按 (租户, issuer) 复用并保留已登记 client、issuer 用 `updateOAuthConfig` **定点回写一列**）、`admin/util/RemoteJsonFetcher.kt`（管理员输入或上游文档给出的地址的唯一出口——admin 别处的出站如 `listTools` / channel / registry 打的是自己配的地址，不归它管：只接 http(s)、元数据地址底线、不跟重定向、5s 连接 / 10s 请求 + 读取截止、64KB 上限、报错抹掉 userinfo）、`admin/dto/{McpOAuthDiscoveryResponse,McpOAuthClientRequest}.kt`；改造既有：`SecretFieldEncryptor.resolveSecret`（单列掩码三态）、`McpServerResponse.parseOAuthConfig` 由 private 转公开（读同一列就必须用同一条解析规则）、`ApiErrors` 注册 `uk_mcp_oauth_client_tenant_issuer_client`、`application.yml` 的 `app.base-url` 归位（见 §11）；第八轮又给「发现回来的数据」加了一道信任边界（端点与 issuer 的协议 / 宽度 / userinfo / 无 `issuer` 文档的差别对待、`oauth_config` 读不通就中止、候选失败原因截断与脱敏），规则逐条在 `mcp-management` §3.5；测试 `McpOAuthServiceImplTest`（49，第十四轮为默认回调地址加了 2 条：配了 `app.frontend-base-url` 就以它为准、没配就退回 `app.base-url`）、`RemoteJsonFetcherTest`（16，第十三轮补的 2 条真出站用例守的是「失败响应的体也要能解析」与「表单参数按 URL 编码带出」——见 `mcp-management` §7.12 第 1 条）、`SecretFieldEncryptorTest.ResolveSecretTests`（6）、`McpServerMapperTest` 两条 `updateOAuthConfig`；上下文启动与新 `@Value` 由 `HealthInfoIT` 覆盖；**第九轮补了管理面前端**（`harnax-webui/src/pages/mcp/components/{CreateForm,UpdateForm,OAuthFields,OAuthPanel}.tsx` 配 `authType` / `oauthConfig` 与两个动作、`pages/mcp/{index,detail}.tsx` 的标签与回显、`services/ant-design-pro/mcp.ts` 两个调用、`typings.d.ts` 类型、`locales/{zh-CN,en-US}/pages.ts` 的 52 个 `pages.mcp.oauth.*` 键，取舍逐条见 `mcp-management` §7.8） |
| P2-3 授权码 + PKCE + 凭据存取与撤销 | **已完成**（服务 + 换票接口 + 测试；不碰运行时，也不含换发接口）；第十四轮把「谁在换票」从 `state` 换成 JWT，见 §8 第 7 条；第十五轮修了次序与两处口径，见 `mcp-management` §7.14 | 服务 `service/McpOAuthUserService.kt` + `service/impl/McpOAuthUserServiceImpl.kt`（`authorizeUrl` / `exchange` / `status` / `revoke`；`exchange` 先烧 `state`，再在**读请求体任何字段之前**比归属，换 token 前复核服务行是否还在发起时的租户、是否还是 `OAUTH2`、行 `url` 是否还等于 `state` 里钉的 `resource`，请求的 `scope` 宽过列宽 512 就拒、**授予回来的清单宽过列宽则整份不记**（半份清单比空清单更容易骗人），成功时回答里的 `scopes` 取的是落库那一列而不是 token 响应的原始字段，空 `client_id` 不发起也不换，并发插撞 `uk_mcp_user_credential_tenant_user_mcp` 时转到先落库的那行，而不是让一次真实发生的授权报成数据库失败）；撤销分**六种情况各回一句实话**（无端点 / 无令牌 / 解不开的密文 / 没了的注册行 / 上游接受 / 上游拒绝），见 §6.4；状态 `util/McpOAuthStateStore.kt`（`PendingAuthorization`，TTL 5 分钟、全局上限 500、每人另有 5 条上限（第十五轮加的 `MAX_PER_USER` + `countFor`）、`consume` 取走即失效）；DTO `dto/{McpOAuthAuthorizeResponse,McpOAuthStatusResponse,McpOAuthRevokeResponse,McpOAuthExchangeRequest,McpOAuthExchangeResponse}.kt`，四个接口都在 `controller/McpOAuthController.kt`；**安全侧本轮是删不是加**——原 P2-3 为回调开的 `config/SecurityConfig.kt` `permitAll()` 与 `config/JwtAuthenticationFilter.kt` 路径跳过、以及 `controller/McpOAuthCallbackController.kt` 与 `dto/McpOAuthCallbackResult.kt` 都已移除；出站侧 `util/RemoteJsonFetcher.kt` 补 `postForm`（带 code / secret / token 的 POST 走同一道护栏）与顶层 `normalizeIssuer`（发现与授权共用同一条规范化，管理员多打一个斜杠不会变成「配了一行、找不到」），`McpOAuthServiceImpl` 删掉自己那份私有实现；**PKCE 无条件 S256**——§12.1 原先留的「动手前先定」已定：不快照 AS 的 `code_challenge_methods_supported`，不支持就让它在 AS 那侧报错，admin 不降级到 `plain`；测试 `McpOAuthUserServiceImplTest`（62，其中换票那 32 条由第十四轮重排、第十五轮再加 4 条，含归属不符、无登录上下文、响应体不含令牌材料、每人占不满共用预算等分支）、`McpOAuthStateStoreTest`（7）、`McpOAuthControllerTest` 由 6 → 13 → 16；原 `McpOAuthCallbackControllerTest`（4）随接口删除 |
| P2-4 内部换发 + 审计 | 未开始 | `POST /api/admin/internal/mcp/access-token`、`mcp_call_log` 写入 |
| P2-5 用户侧授权入口 + 换票接口 | **已完成**（第十四轮，与闭合 F6 同一轮：换票接口是这一项的前提，没有它「点一下授权」无处可去） | 落地页 `harnax-webui/src/pages/mcp/oauth-callback.tsx`（`useEffect` 第一句读 query 再 `history.replace` 抹掉，`code` 与 `state` 只停在 effect 里的一个局部对象上、立刻发出去，不进 `useState` 也不落 `localStorage`；成功回显授予的 scope 与过期时间，失败原样显示后端那句话——重新发起要去点详情页那个按钮，这里重发同一个 code 不会有别的下场，所以页面上只有「返回 MCP」一个动作）；路由 `harnax-webui/config/routes.ts`（`/mcp/oauth/callback`、`layout: false`、排在 `path: '*'` 之前）；`harnax-webui/src/app.tsx` 的 `loginRedirect`——未登录时跳去 `/login?redirect=` 会把 query 丢掉，否则 `code` 会出现在登录页地址栏与登录后的回跳参数里；按人授权块 `pages/mcp/components/OAuthPanel.tsx`（挂载即读 `status`，三态徽标 + 「去授权」同页跳转 + `Modal.confirm` 包住的「撤销」，并回显 scopes / 过期时间 / `last_error`；`callbackUrl` 与登记值逐字符比对的提醒也在这；第十五轮起**读不到 `status` 就不画徽标**——`grantUnknown` 为真时三态一个都不选，「还没读到」不能替用户断言「未授权」，撤销成功后重读失败更是如此）；服务调用 `services/ant-design-pro/mcp.ts` 四个（`getMcpOAuthAuthorizeUrl` / `exchangeMcpOAuthCode` / `getMcpOAuthStatus` / `revokeMcpOAuth`）+ `src/typings.d.ts` 对应类型；文案 `locales/{zh-CN,en-US}/pages.ts` 的 `pages.mcp.oauth.*` 由 52 键扩到 74 键（两份键数与顺序一致）。**没做的**：列表页徽标（随 P3，理由见 §9.1）、`scope` 覆盖参数（后端支持，UI 不给入口） |

## 13. 关键文件索引

| 模块 | 文件 |
|------|------|
| 上游认证现状 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`、`harnax-entity/src/main/resources/mapper/McpServerMapper.xml`（`headers` / `env_params` 加密列） |
| OAuth 数据模型（P2-1） | `harnax-entity/.../entity/{McpAuthTypes,McpOauthClient,McpUserCredential,McpCallLog}.kt`、`harnax-entity/.../mapper/{McpOauthClient,McpUserCredential,McpCallLog}Mapper.kt` 与 `harnax-entity/src/main/resources/mapper/` 下同名 XML、`harnax-admin/.../dto/McpOAuthConfig.kt`、`harnax-entity/src/test/resources/schema-test.sql`（唯一一份测试 schema）、`harnax-entity/src/test/kotlin/com/agnetix/harnax/MapperXmlParseTest.kt` |
| 加解密 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/AesUtil.kt`、`util/SecretFieldEncryptor.kt`（实现 `harnax-common` 的 `McpConfigDecryptor`；单列密钥的掩码三态在 `resolveSecret`） |
| OAuth 发现与客户端登记（P2-2） | `harnax-admin/.../controller/McpOAuthController.kt`、`.../service/{McpOAuthService.kt,impl/McpOAuthServiceImpl.kt}`、`.../util/RemoteJsonFetcher.kt`（`RemoteFetch` 带 `status` / `wwwAuthenticate` / `json`，JSON 取值 helper `optString` / `optStringList` 同文件）、`.../dto/{McpOAuthDiscoveryResponse.kt,McpOAuthClientRequest.kt}` |
| 授权码 + PKCE（P2-3） | `harnax-admin/.../service/{McpOAuthUserService.kt,impl/McpOAuthUserServiceImpl.kt}`、`.../util/McpOAuthStateStore.kt`（`PendingAuthorization` 与一次性 `consume` 同文件）、`.../controller/McpOAuthController.kt`（四个接口全在这一个文件里，都要 JWT）、`.../dto/{McpOAuthAuthorizeResponse,McpOAuthStatusResponse,McpOAuthRevokeResponse,McpOAuthExchangeRequest,McpOAuthExchangeResponse}.kt`。**已无免鉴权路径**：原 `controller/McpOAuthCallbackController.kt`、`dto/McpOAuthCallbackResult.kt` 与 `config/SecurityConfig.kt` / `config/JwtAuthenticationFilter.kt` 为回调开的那两处放行，都在第十四轮随 F6 一起删掉 |
| 用户侧授权入口（P2-5） | 落地页 `harnax-webui/src/pages/mcp/oauth-callback.tsx`、路由 `harnax-webui/config/routes.ts`、未登录跳转的 query 处理 `harnax-webui/src/app.tsx`、按人授权块 `harnax-webui/src/pages/mcp/components/OAuthPanel.tsx`、调用与类型 `services/ant-design-pro/{mcp.ts,typings.d.ts}`、文案 `src/locales/{zh-CN,en-US}/pages.ts` |
| 管理面前端（P2-2） | `harnax-webui/src/pages/mcp/components/{CreateForm,UpdateForm}.tsx`（认证方式下拉、stdio 联动）、`OAuthFields.tsx`（`authorizationServer` / `scopes` / `audience` / `resourceIndicator`）、`OAuthPanel.tsx`（发现回显 + 登记客户端弹窗）、`harnax-webui/src/pages/mcp/{index,detail}.tsx`、`harnax-webui/src/services/ant-design-pro/mcp.ts`、`harnax-webui/src/typings.d.ts`、`harnax-webui/src/locales/{zh-CN,en-US}/pages.ts` |
| 配置下发 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`（`agent-spec`、`plainConfigJson`、`api-keys/validate`）、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt` |
| 运行侧装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`、`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt` |
| 客户端构造能力 | `~/.m2/repository/io/agentscope/agentscope-core/2.0.2/agentscope-core-2.0.2.jar` 内 `io.agentscope.core.tool.mcp.McpClientBuilder`（`httpRequestCustomizer`、`customizeStreamableHttpClient`、`customizeSseClient`、`timeout`、`initializationTimeout`）与 `McpSyncHttpClientRequestCustomizer` |
| 身份链路 | `harnax-admin/.../util/JwtUtil.kt`、`config/JwtAuthenticationFilter.kt`、`util/UserContextUtil.kt`、`harnax-auth/.../AuthContext.kt`、`ApiKeyInfo.kt`、`ExternalApiKeyValidator.kt`、`harnax-session-router/.../service/RemoteApiKeyStore.kt`、`harnax-protocol/.../AgentRequest.kt`、`harnax-agent/harnax-agent-service/.../runner/impl/DefaultAgentRunner.kt`、`harnax-agent/harnax-tools-sdk/.../ToolCallContext.kt` |
| 内部鉴权 | `harnax-admin/.../config/InternalApiAuthFilter.kt`、`harnax-auth/.../InternalTokenProvider.kt` |
| 前端 | `harnax-webui/src/pages/mcp/index.tsx`、`pages/mcp/components/UpdateForm.tsx`、`components/EntityCard/index.tsx`、`services/ant-design-pro/mcp.ts`、`src/locales/{zh-CN,en-US}/pages.ts` |
| 迁移 | `harnax-admin/src/main/resources/db/migration/V4__refactor_tool_granularity.sql`（`envs`→`env_params`）、`V15__skill_source_integrity.sql`（生成列 + 唯一键写法）、`V22__mcp_public_default_and_tenant_backfill.sql`、`V23__add_mcp_server_name_unique_key.sql`（租户内名称唯一键，沿用它前面那两条的写法）、`V24__backfill_agent_session_tenant.sql`（P1 的 agent / session 租户回填，同款写法）、`V25__add_mcp_oauth_columns.sql`（`mcp_server` 的 `auth_type` / `oauth_config`）、`V26__add_mcp_oauth_tables.sql`（三张 OAuth 表） |

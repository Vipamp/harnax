# Harnax MCP 出站授权设计（中文）

> 英文版本见 [mcp-authorization-design.en-US.md](./mcp-authorization-design.en-US.md)。
> 本文讲**用户侧的 OAuth 授权链路**：授权码换令牌、令牌如何加密存储、运行时如何按人注入、哪些会话类型带不了用户身份。`mcp_server` 的增删改查、字段掩码、配置下发与运行侧装配的其余部分不在本文范围内；`mcp-management.zh-CN.md` 讲那半边。两份文档各自完整、都不依赖对方才能读懂，同一件事出现在两边时口径一致。
> 入站认证（谁在调 harnax）不在本文范围，但换发接口复用「会话反查归属」这条既有能力。

## 1. 范围与口径

1. harnax 在这条链路里只做 OAuth 2.1 的**客户端**与**凭据库**：不做 IdP，不做 MCP server 端，不代理上游的工具面。
2. refresh token 只存在于 `harnax-admin` 的数据库里，加密、永不出现在任何响应 DTO，也永不进入模型上下文。运行时每次发出上游 HTTP 请求前，向 admin 要一个短期 access token。
3. 上游怎么认证由 `mcp_server.auth_type` 这一列显式说出：`NONE` / `STATIC_HEADER` / `BASIC` / `OAUTH2`。运行侧认三种（`NONE`、`STATIC_HEADER`、`OAUTH2`），`BASIC` 由 `McpAuthTypes.SUPPORTED` 挡在写入侧——存一个运行时完全不认的值，等于让管理员以为配好了。
4. **不支持 stdio 类型的 MCP**：`HARNAX_MCP_STDIO_ENABLED` 默认为 false，admin 与运行侧各有一道同名开关，两侧都为 true 才会真的起进程。stdio 也没有 HTTP 请求可挂令牌，因此 `validateAuthType` 直接拒绝 stdio + `OAUTH2` 的组合。OAuth 这条链路只作用于 `sse` 与 `streamablehttp`。
5. **渠道会话（`chn-`）没有用户身份**，因此不加载 OAuth 类 MCP：`McpSessionOwnerResolver.resolve` 对 `chn-` 前缀返回 null，装配侧遇到 null 就把这台服务留在工具箱之外。
6. **定时任务会话（`task-`）以任务创建人的身份**使用其授权：归属从 scheduler 读回任务行的 `creator` 与 `tenant_id`，花的是那个人自己的 grant。
7. 令牌注入点是 agentscope `McpClientBuilder.httpRequestCustomizer`：它是每请求回调，所以令牌轮换不需要重建 MCP 客户端。
8. 换发接口只接受 `sessionId`，没有任何形式的用户字段。admin 自己反查归属，agent-service 无法指名要谁的凭据。

## 2. 授权数据模型

建表 DDL 在 admin 的 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 这一个脚本里：本域用到的四张表——`mcp_server`（`auth_type` 与 `oauth_config` 两列就在它的建表语句中）、`mcp_oauth_client`、`mcp_user_credential`、`mcp_call_log`——都由它一次写完，没有需要往上叠加的后续版本，列集合以它给出的形态为准。`harnax-entity/src/test/resources/schema-test.sql` 里另有一份 mapper 集成测试基线（这四张表各一段），它的建表段落正是 admin 这份基线的表定义块复制过去的，两边因此不会各写各的；admin 基线一改就重新生成那一段。生产初数据的 INSERT 刻意不进这份测试基线：它自带的夹具要自己分配用户、租户、智能体与会话，带固定 id 的播种行会和这些夹具相撞。列集合以下按 admin 基线里真实的 DDL 列出。

### 2.1 `mcp_server` 上的两个授权列

| 列 | DDL | 语义 |
|----|-----|------|
| `auth_type` | `VARCHAR(20) NOT NULL DEFAULT 'NONE'` | 上游认证方式，`McpAuthTypes` 的四个取值之一；空白或未给按 `NONE` 处理 |
| `oauth_config` | `TEXT DEFAULT NULL` | **仅非敏感**配置的 JSON，形状由 `McpOAuthConfig` 这个 DTO 决定：`authorizationServer`、`scopes`、`audience`、`resourceIndicator` |

- `oauth_config` 与 `headers` 同性质：文本 JSON、不加密、明文回显给前端。因此里面**不允许**出现任何密钥——`client_id`/`client_secret` 属于 `mcp_oauth_client`，令牌属于 `mcp_user_credential`。白名单由类型本身保证：反序列化时多出来的键落不进 `McpOAuthConfig`，也就写不进这一列。
- `oauth_config` 只在 `auth_type=OAUTH2` 时允许写入（`writeOAuthConfig` 对非 OAuth 服务带配置是报错）；行的 `auth_type` 不是 `OAUTH2` 时这一列被清成 NULL，界面于是不会继续显示一份它用不到的 scope 清单；`authorizationServer` 非空时必须是 `http(s)` 且有 host，因为发现阶段 admin 会真的去请求这个地址。
- 读这一列的授权路径（`McpOAuthUserServiceImpl.readConfig`）解析失败时报错「重新保存该服务的 OAuth 设置」，而不是当作未配置——一行坏 JSON 静默当成没配，用户会看到「授权服务器未知」而配了东西的人完全不知道为什么。
- `auth_type` 不参与任何索引，schema 与代码也不按别的列推导它的取值：基线给的是 `NOT NULL DEFAULT 'NONE'`，落成其他标签一定是管理员显式选的。原因是 `NONE` 与 `STATIC_HEADER` 走同一条代码路径，`headers` 里除了凭据还混着路由用的普通头，推导出来的标签和真正的决策分不开。

### 2.2 `mcp_oauth_client`：AS 侧的客户端身份，按 (租户, issuer) 共享

一台授权服务器后面的多个 MCP 服务共用一条注册，所以它不挂在 `mcp_server` 上，删除一台 MCP 服务也不删它。

| 列 | DDL | 语义 |
|----|-----|------|
| `id` | `BIGINT AUTO_INCREMENT PRIMARY KEY` | 行标识 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 1` | 归属租户 |
| `issuer` | `VARCHAR(255) COLLATE utf8mb4_bin NOT NULL` | AS 的 `issuer`，与令牌里的 `iss` 按字节比较 |
| `client_id` | `VARCHAR(255) NOT NULL` | 发现的 `client_id`；空串是一个显式状态「端点已知、client 未登记」 |
| `client_secret_enc` | `TEXT DEFAULT NULL` | AES 密文；NULL 表示公共客户端（仅 PKCE） |
| `registration_source` | `VARCHAR(20) NOT NULL DEFAULT 'MANUAL'` | `MANUAL` / `DCR` / `ID_METADATA`，代码里写进去的取值只有 `MANUAL` |
| `authorization_endpoint` / `token_endpoint` / `registration_endpoint` / `revocation_endpoint` | `VARCHAR(500)` | 发现结果快照；`registration_endpoint` 为 NULL 表示不支持 DCR，`revocation_endpoint` 为 NULL 表示撤销只清本地 |
| `scopes_supported` | `TEXT` | 发现快照，逗号分隔 |
| `callback_url` | `VARCHAR(500) COLLATE utf8mb4_bin NOT NULL` | 登记在 AS 侧的精确 `redirect_uri`，不做前缀匹配 |
| `creator` | `VARCHAR(100) DEFAULT ''` | 创建人 |
| `active` | `TINYINT NOT NULL DEFAULT 1` | 软删除位 |
| `create_time` / `update_time` | `DATETIME` | 时间戳，`update_time` 由数据库自动维护 |
| `active_client_id` | `VARCHAR(255) GENERATED ALWAYS AS (IF(active = 1, client_id, NULL)) VIRTUAL` | 生成列，配合唯一键 |
| 键 | `UNIQUE KEY uk_mcp_oauth_client_tenant_issuer_client (tenant_id, issuer, active_client_id)`、`KEY idx_mcp_oauth_client_tenant_issuer (tenant_id, issuer)` | 删掉的注册不占住名字，可以重新登记 |

- `issuer` 与 `callback_url` 用二进制排序规则，其余列保持表默认。MySQL 默认排序规则不区分大小写，而 RFC 8414 的 `iss` 与 RFC 6749 的 `redirect_uri` 都是精确字符串比较，折叠大小写会把另一家授权服务器的注册悄悄复用过来。管理员手输 issuer 时大小写写错会得到「没有注册」而不是自动命中。
- 复用查询 `selectByTenantAndIssuer(tenantId, issuer)` 带 `ORDER BY id LIMIT 1`：唯一键允许同一 issuer 挂不同 `client_id`，此时必须有确定答案，否则同一租户会在一家 AS 上来回注册第二个客户端。
- `updateById` 的 SET 列表不做判空：「AS 把 DCR 支持撤掉了」要把 `registration_endpoint` 写成 NULL，判空写法会把一个已经失效的端点留在库里继续被调用。行身份列（`tenant_id`、`issuer`）不出现在 SET 里。

### 2.3 `mcp_user_credential`：用户 × MCP 服务的授权结果

| 列 | DDL | 语义 |
|----|-----|------|
| `id` | `BIGINT AUTO_INCREMENT PRIMARY KEY` | 行标识 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 1` | 与 `mcp_server.tenant_id` 一致，凭据按服务行的租户归档 |
| `user_id` | `BIGINT NOT NULL` | `sys_user.id`，不是用户名 |
| `mcp_id` | `BIGINT NOT NULL` | `mcp_server.id` |
| `access_token_enc` | `TEXT DEFAULT NULL` | AES 密文；撤销时清成 NULL |
| `refresh_token_enc` | `TEXT DEFAULT NULL` | AES 密文；永不出 admin 进程 |
| `access_expires_at` | `DATETIME DEFAULT NULL` | 访问令牌到期时刻；已过即当作缺失 |
| `scopes` | `VARCHAR(512) DEFAULT NULL` | 实际授予的 scope，可能小于请求的 |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` | `ACTIVE` / `NEEDS_CONSENT` / `REVOKED` |
| `last_error` | `VARCHAR(512) DEFAULT NULL` | 脱敏后的失败原因，写入前截到 500 字符 |
| `last_refreshed_at` | `DATETIME DEFAULT NULL` | 最近一次成功刷新 |
| `create_time` / `update_time` | `DATETIME` | 时间戳 |
| 键 | `UNIQUE KEY uk_mcp_user_credential_tenant_user_mcp (tenant_id, user_id, mcp_id)`、`KEY idx_mcp_user_credential_mcp (mcp_id)` | 三元唯一 |

- **这张表没有 `active` 列，也不做软删除**。撤销是在原行上把两个密文与 `access_expires_at`、`scopes` 写成 NULL、`last_error` 清空并置 `status = REVOKED`（`clearLocally`）；用户重新授权时覆盖同一行。软删除会留一行继续占住 `(tenant_id, user_id, mcp_id)`，把重新授权的 insert 顶掉。硬删除发生在四个场合：删除 MCP 服务、把 `auth_type` 从 `OAUTH2` 改走、改 `url` 使 RFC 8707 的 resource 变了，这三条都是 `deleteByMcpId`；第四条是删除用户账号，走 `deleteByUserId` 一次清掉他在任何服务上的 grant（调用点 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImpl.kt`）——`sys_user` 那一行只是逻辑删除，此后没有任何入口再读这些授权行，而它们的主人已经没有了可以点撤销的页面。
- `updateById` 的 SET 列表**全部无条件**：判空写法永远写不回 NULL，撤销就会在库里留下一把「仍可用但管理员以为已撤销」的令牌。
- `selectByUserAndMcp(tenantId, userId, mcpId)` 把租户放在查询条件里，而不是取回来再过滤。
- Mapper 只有 `selectByUserAndMcp` / `insert` / `updateById` / `deleteByMcpId` / `deleteByUserId` 五个方法，没有列表方法：这张表的行只按「谁对哪个服务」定位，不存在需要翻页的场景，多一个入口只会多一条绕过租户条件的路。两个 delete 都是按外部键整批清除（一台服务、一个人），SQL 里不带租户条件——`mcp_id` 与 `user_id` 各自已经圈定了范围。

### 2.4 `mcp_call_log`：授权决策审计

| 列 | DDL | 语义 |
|----|-----|------|
| `id` | `BIGINT AUTO_INCREMENT PRIMARY KEY` | 行标识 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 1` | 取服务行的租户 |
| `user_id` | `BIGINT DEFAULT NULL` | 解析不到会话归属时的调用也要留得下账 |
| `mcp_id` | `BIGINT NOT NULL` | 服务 |
| `session_id` | `VARCHAR(255) DEFAULT NULL` | 运行侧调用来自哪个会话；从管理页面发起的决策为 NULL |
| `tool_name` | `VARCHAR(255) DEFAULT NULL` | 调用级审计用；令牌换发与刷新为 NULL |
| `action` | `VARCHAR(20) NOT NULL DEFAULT 'ISSUE'` | `ISSUE` / `REFRESH` / `REVOKE` / `CALL` |
| `outcome` | `VARCHAR(20) NOT NULL` | `OK` / `AUTH_FAILED` / `NEEDS_CONSENT` / `ERROR` |
| `latency_ms` | `BIGINT DEFAULT 0` | 本次决策耗时 |
| `create_time` | `DATETIME DEFAULT CURRENT_TIMESTAMP` | 写入时刻；这张表没有 `update_time` |
| 键 | `KEY idx_mcp_call_log_tenant_mcp_time (tenant_id, mcp_id, create_time)`、`KEY idx_mcp_call_log_session (session_id)` | 按服务与时间、按会话查 |

- 只记结果与耗时：**不记请求体、不记响应体、不记 Authorization 头**。这张表对令牌归属人之外的管理员可读，任何形似凭据的内容都会把审计日志变成泄漏点。
- `action` 与 `outcome` 分开是必要的：只有 `outcome` 时「换发失败」和「调用失败」在审计里长得一样，而前者要查授权链路、后者要查工具本身。写这张表的入口只有一处——`McpOAuthUserServiceImpl` 的 `audit()`，它写入的组合是 `ISSUE` × {`OK`, `NEEDS_CONSENT`}、`REFRESH` × {`OK`, `ERROR`}、`REVOKE` × `OK`；`CALL` 与 `AUTH_FAILED` 是枚举里定义好、但生产代码里没有写入调用点的取值。
- 审计写失败不影响调用：`audit()` 把 insert 整个包在 try 里，只 warn 一句「审计行写不下去」。
- Mapper 只有 `insert`。能被应用改写的审计表就不是审计表。

### 2.5 凭据的加密与解密

- 算法是 AES-256-GCM：`AesUtil.encrypt` 每次生成 12 字节随机 IV，输出 Base64(`IV + 密文`)；`decrypt` 反向拆 IV。密钥来自 `harnax.aes.secret-key`，也就是环境变量 `HARNAX_AES_SECRET_KEY`。
- 密钥不是 32 字节时 `normalizedKey()` 会零填充或截断，并且**在启动时 WARN 说清楚**：填短了的密钥比看上去弱，改回正确长度会让已写入的密文一律打不开。密钥仍是 `application.yml` 里那个公开占位值时同样 WARN——占位值等于所有密文对读得到源码的人敞开。
- **这条边界是决定性的**：`harnax.aes.secret-key` 只配在 `harnax-admin`，agent/router/channel/scheduler 的 yml 里没有这一项，`AesUtil` 也只存在于 admin 源码树。所以配置下发时 admin 已把 `secret = true` 的条目解密成明文（`InternalApiController.plainConfigJson`），运行时的 `PlaintextMcpConfigDecryptor` 只做 JSON 解析。凭据库因此必须在 admin，运行时只能拿**已经换好的、短期的**访问令牌。
- **按条解密**：`SecretFieldEncryptor.decryptToMap` 对每个 `secret = true` 条目单独 `runCatching`。单条打不开只跳过那一条并记名 warn，最后再补一行「N/M 条打不开、正在下发 K 条」的整行结论；把整批包在一个 try 里会让一行解不开的密文把整个服务的请求头全丢掉，前端表现为「没配任何 header」。`decryptToolEnvParamsToMap` 对 `ToolEnvParamEntry` 同一条规则。
- **掩码回写保护**（`is_public` 类公开字段之外，所有密钥列共用一套三态语义）：详情页对 secret 条目做掩码回显，编辑表单原样提交时会把掩码送回后端。`serializeWithEncryption` 与 `resolveSecret` 的判定是——未给这个字段 = 沿用库中密文；值是掩码形态（含连续四个 `*`）= 沿用库中密文（单列没有可沿用时报错，要求重填）；空串（仅单列路径 `resolveSecret`）= 显式清除，这是「这个客户端没有 secret」唯一的说法；其余 = 新值，加密后写入。`secret = false` 的条目带着掩码回来会被**拒绝**而不是入库，因为非密钥条目没有可沿用的密文，写进去就是一个「长得像 key、认证不了任何东西」的请求头。
- 授权域用到这套语义的两处：`mcp_oauth_client.client_secret_enc` 走 `resolveSecret`（单列），`mcp_server.headers` / `env_params` 走 `serializeWithEncryption` / `serializeToolEnvParams`（JSON 条目）。
- 令牌与 secret 只在需要时才解密：`openToken` 解不开返回 null，调用方按「这份凭据不存在」处理并记一条不含密文与明文的 warn，绝不把材料写进日志。

## 3. 授权链路：首次授权

前置条件是这台服务已经走完发现与客户端登记：`oauth_config.authorizationServer` 指向一个 issuer，`mcp_oauth_client` 里有按 (租户, issuer) 复用的那一行，且它的 `client_id` 非空。`client_id` 为空串是发现故意留下的显式状态「端点已知、client 未登记」，`requireClientRegistration` 对它在**发起授权时**就拒绝，不让用户带着 `client_id=` 去撞 AS 的错误页；`authorization_endpoint` 或 `callback_url` 缺失同样当场报错并指出该重跑哪个接口。

### 3.1 发起：`GET /api/admin/mcp/{id}/oauth/authorize-url`

```
1. currentUserId()：取不到用户身份直接报错——授权是逐人的，没有身份就没有归属可记
2. requireOAuthServer(id)：经 getVisibleMcpServer 读行，租户与可见性守卫都过一遍，再要求 authType=OAUTH2
3. scope 参数可覆盖服务上配的清单，去重后拼成逗号串；宽过 512 直接拒绝（截断等于替用户记下一份没人同意过的清单）
4. 与 mcp_oauth_client.scopes_supported 比对，出现 AS 没列出的 scope 只 warn，不拒
5. resource = 服务行的 url（RFC 8707），由 oauth_config.resourceIndicator 决定带不带
6. 名额检查：stateStore.countFor(userId) >= 5 就拒绝，且发生在生成 verifier 与 state 之前
7. 生成 code_verifier（32 字节随机、base64url 无填充）与 state（24 字节随机）
8. PendingAuthorization(tenantId, userId, mcpId, issuer, codeVerifier, redirectUri, resource, requestedScopes, TTL) 存入内存态；put 返回 false 即全局已满 500，回一句「稍后再试」
9. 拼 authorization_endpoint：response_type=code、client_id、redirect_uri、state、
   code_challenge=BASE64URL(SHA256(verifier))、code_challenge_method=S256，可选 scope / resource / audience
```

- `redirect_uri` 取的是注册行上的 `callback_url`，它是**前端一条路由的地址**，不是本服务的接口。
- PKCE 无条件 `S256`：不快照 AS 的 `code_challenge_methods_supported`。OAuth 2.1 与 MCP 都把 PKCE 定为强制项，AS 不支持 `S256` 就在它自己那侧报错，admin 不降级到 `plain`。
- `resource` 是「不把令牌透传给别的 MCP」的那道绑定；只有对明确拒绝该参数的 AS 才通过 `resourceIndicator=false` 关掉。

### 3.2 换票：`POST /api/admin/mcp/oauth/exchange`

AS 重定向落到前端路由，落地页先抹掉地址栏 query，再带着浏览器已有的 JWT 调这个接口。路径里**没有** `{id}`：目标服务只能由 pending 说，带着一个 id 来等于允许一个被钓到的 `state` 指向调用者挑的任意服务。

判定次序（每一条都是显式回答，`ResultVo` 恒 200，`authorized=false` + 下一步该做什么）：

```
1. stateStore.consume(state) —— 无论哪一支，state 先烧掉
2. pending != null 且 pending.userId != 调用者 → 记一条 warn（只记 mcpId 与两个 userId）并拒
3. 请求体带了 error → 把 error 与 error_description 原样拼进回答（截 200 字符）
4. pending == null → 「这次授权请求未知或已过期」
5. 没有 code → 「授权服务器没有返回 code」
6. 才进 redeem(code)
```

- 第 2 步排在**读请求体任何字段之前**。否则知道别人 `state` 的人 POST 一个自造的 `error` 就能替对方取消那次在途授权，而那条 warn 一个字都不会说。这一条就是这条链路对「授权钓鱼」的抵抗力：`state` 不作为身份来源，它只回答「这次同意该记到谁名下」。
- 第 3 步在 pending 已经过期时也照样回答：那种情形下什么都没存、也没什么可烧，而上游那句理由是页面上唯一有用的信息。
- 整段回答里没有任何字段装得下令牌材料。

### 3.3 落库前的校验与写入

`redeem` 做的事，按顺序：

- 服务行经 `requireOAuthServer(pending.mcpId)` 重读（带租户与可见性守卫），并且：行的 `tenantId` 必须仍等于 `pending.tenantId`（服务被挪出原租户就不换）；**这条检查有前置条件**——只在 `pending.resource != null` 时才要求行的 `url` 仍等于它（code 是为改之前的那个地址换的，存下来是一把开不了门的钥匙），pending 当初没带 resource（`resourceIndicator` 关着，或那次没拼出 resource）时整条比对跳过。
- 注册行按 `pending.issuer` 查，不按「`oauth_config` 现在写着什么」查——issuer 是这次 pending 建立所依据的身份。行没了、`client_id` 是空串、`token_endpoint` 为空，都各自报错。
- 表单 `grant_type=authorization_code` + `code` + `redirect_uri` + `client_id` + `code_verifier`，有 secret 就带上（解不开时报「重新保存 OAuth 客户端」，而不是不带 secret 去撞一个看起来像注册坏了的 `invalid_client`），有 `resource` 就带上。凭据放 body 不放 Basic 头，因为所有实现都收 body 且不需要第二条代码路径。
- 出站走 `RemoteJsonFetcher.postForm`，与发现同一个护栏；回答里的 `error` / `error_description` 会被截断后原样说给页面。
- 校验回来的令牌（`validateAgainstIssuer`）：响应级 `iss` 与 JWT 声明 `iss` 都要跟发现的 issuer **字节相等**（`assertIssuer`），`aud` 必须包含本次的 `resource`。JWT 是**解出不验签**——没有任何代码路径消费令牌内容，它只被原样转发给 MCP 服务，校验它是 MCP 服务的职责。opaque 令牌根本解不出来、或 JWT 压根没有 `aud`，都是**记一条日志而不是当成通过**：此时资源绑定只剩「AS 遵守了 `resource` 参数」这一条假设。
- `scope` 以响应为准，响应没给（RFC 6749 §5.1 让它可选）就用请求的那份；拼成逗号串超过 512 就**整份不记**，并把这件事写进 `last_error`——半份清单比空清单更容易骗人。
- 写入是覆盖式的：`access_token_enc`、`refresh_token_enc`、`access_expires_at`、`scopes`、`status=ACTIVE`、`last_refreshed_at`、`last_error` 一次全量落。上一次留下的 refresh token **不保留**——留着等于留一条能铸出没人刚同意过的 scope 的路。
- `expires_in` 缺失时 `access_expires_at` 为 NULL，读作「到被拒之前都可复用」。
- 并发插入撞 `uk_mcp_user_credential_tenant_user_mcp` 时（`DuplicateKeyException`）转到先落库的那行做 `updateById`：一次真实发生了的授权不该报成数据库失败。
- 回答里的 `scopes` 是**落库那一列记下的清单**，不是 token 响应里的原始 `scope` 字段，也不是没记下的那份：`status` 读的是同一列，两个答案不一致会让落地页显示零条、几秒后详情页显示两条。
- 请求的 scope 宽于实际授予的，warn 出差集。

### 3.4 状态查询：`GET /api/admin/mcp/{id}/oauth/status`

`authorized` 为真要求 `status == ACTIVE` 且 `access_expires_at` 为空或晚于当前时间；这个判定**不触发刷新**，所以一条已经到期但刷新得动的凭据在页面上显示为「未授权」，而运行时向它要令牌时会先刷新再交出。其余回答字段是 `status`、`scopes`（按逗号拆开）、`accessExpiresAt`、`lastRefreshedAt`、`lastError`。库里没有这个用户这一行的回答是 `authorized=false`，不带其他字段。

### 3.5 撤销：`POST /api/admin/mcp/{id}/oauth/revoke`

**本地一定清干净**，上游只在「这台服务的注册登记过 `revocation_endpoint`」时才发一次 RFC 7009 请求。`token_type_hint` 优先 `refresh_token`、其次 `access_token`：access token 几分钟后自己就死了，refresh token 才是能一直铸出新令牌的那个，合规的 AS 撤销它会连带整个 grant。解不开的密文（AES 密钥换过）当作「没有令牌」处理，且**不因此跳过清本地**——撤销是用户唯一的出路，「解不开」既不是「没有令牌」也不是「上游拒了」。

回答里的六种情况互不顶包（`revoked` 恒为 true，`upstreamRevoked` 说明上游那半）：

| 情形 | 回答要点 |
|------|----------|
| 库里没有这个用户的行 | 本地没有任何授权，无东西可撤；不写审计 |
| 注册行已解析不出（issuer 未配或行被删） | 本地已清；说不出这条授权属于哪家 AS，因此无法向上游呈递，并给出重跑发现的路径 |
| 注册登记了端点但 `revocation_endpoint` 为空 | 本地已清；上游那份仍然可用，直到它自己过期 |
| 有密文但解不开 | 本地已清；上游无法呈递，原因是密钥变了 |
| 行里两个密文都是 NULL | 本地已清；没有可呈递的令牌 |
| 上游接受 / 拒绝 | 接受则两边都清；拒绝则明说「本地已清，上游未清」 |

拒绝时不重试，也不改写成 `NEEDS_CONSENT`——那需要一次真实调用才观测得到。撤销成功与否都写一行 `REVOKE` × `OK` 审计（库里没有行时不写）。

## 4. 令牌换发与刷新

### 4.1 内部换发接口

`POST /api/admin/internal/mcp/access-token`，请求体只有 `{sessionId, mcpId}`：

- 挂在 `InternalApiAuthFilter` 之后（`/api/admin/internal/**` 整条前缀），这是浏览器够不到的路径。
- `sessionId` 空白直接 400。
- 服务行经 `mcpServerMapper.selectById` 按 id 读，**不**走 `McpServerService`：那道守卫拿行去比**请求的**租户头，而来自 agent-service 的内部调用没有这个头。给这次回答划范围的是服务行自己的租户，也正是凭据当初存进去时用的那个键。
- 回答是 `McpAccessTokenResponse(accessToken, tokenType, expiresAtEpochSecond)`，定义在 `harnax-entity` 的 dto 包（跨服务契约放公共模块）。过期用 epoch 秒而不是日期时间：每个服务拿它跟自己本地的时钟比，两侧时区可能不同。
- 不加 `X-User-Id` 之类的客户端自报字段。`userId` 在运行侧只用于「有没有身份」的判断，从不上线，所以填错或填不上传都不可能导致 admin 交出别人的令牌。

### 4.2 会话归属：谁的身份可用

`McpSessionOwnerResolver.resolve(sessionId)` 按前缀分派，返回 `McpSessionOwner(userId, tenantId)` 或 null：

| 前缀 | 归属来源 | 结论 |
|------|----------|------|
| `web-` / `mp-` | `session` 行（`selectBySessionIdAndStatus(..., 1)`，只认活跃会话）的 `creator` + `tenant_id` | 有身份 |
| `task-` | 取 `task-` 后第一段作为 taskId，经 `SchedulerClient.taskOwner` 从 scheduler 读回任务行的 `creator` 与 `tenant_id` | 以**任务创建人**身份使用其授权 |
| `chn-` | 无 | **没有用户身份**，OAuth 类 MCP 不加载（debug 一行） |
| 其他前缀 | 无 | null，并 warn 一句前缀未知 |

`creator` 的解析规则：

- `web-` 写的是用户名、`mp-` 写的是数字用户 id，两种都要试：先 `selectByUsername`，只有它没命中且这一串是纯数字时才 `selectById`——一个真叫「12345」的登录名仍然按名字赢。
- `creator` 空白或解析不出用户 → null。
- 解出的用户必须落在会话自己的租户里：`sys_user.username` 上参与唯一键的是 `active_username` 这个按活跃过滤的生成列，但同名跨租户仍然可能出现，落到别人名下就是花掉别人的 grant；租户对不上就 null 并 warn。用户行没有租户值时按原样取。
- 用户 `status != 1` → null：会话是在账号被停用之前建的，也不能继续花他的授权。

任务归属这一路是冷路径：只在装配 OAuth MCP 客户端时走一次，不在每条消息都要过的 agent-spec 查询上。scheduler 拒了、没答、任务不存在、以及客户端本身抛异常，全都是 null 并且**每种都单独 warn**——下游看不到失败，「scheduler 从 03:00 起就联系不上」不能读成「这个任务没有 OAuth MCP」。

### 4.3 可用令牌的判定与刷新

`accessToken(sessionId, mcpId)` 的完整判定：

```
authType != OAUTH2            → 报错：这台服务没有按人的令牌可换
owner == null                 → 401 NEEDS_CONSENT「这个会话没有可授权的用户身份」
owner.tenantId != server.tenantId → 403，且不查任何凭据
无凭据行                       → 401 NEEDS_CONSENT
status == REVOKED             → 401，状态保持 REVOKED（改成 NEEDS_CONSENT 会抹掉「这个人主动撤过」这唯一的事实）
usableToken() 命中            → 审计 ISSUE × OK，交出
否则                          → refreshGrant()
```

- `usableToken`：`status == ACTIVE` 且有密文且 `access_expires_at` 不早于 `now + 60s`（`REFRESH_SKEW_SECONDS`）。提前刷新而不是到点再刷：一次工具调用因为令牌在长跑中途老掉，读起来像 MCP 服务坏了。`access_expires_at` 为 NULL 的行保持它的令牌直到被拒。解不开的密文在这里等同缺失，交给刷新路径去说。
- `refreshGrant` 用 64 条带锁（`credential.id % 64` 定条）串行化同一条 grant 的并发刷新，**拿到锁后先重读**再判一次可用性——持锁者刚刚刷过这条时，再刷一次会把那次轮换烧掉，看起来就像一个需要重新授权的用户。
- 刷新请求：`grant_type=refresh_token` + `refresh_token` + `client_id`（+ `client_secret`，+ `resource`）。RFC 8707 §2.3 要求刷新回来的令牌重新绑定到资源，否则 AS 可能按其默认 audience 交出一个 MCP 服务拒收的令牌。
- 刷新成功后：`access_token` 必写，`refresh_token` **只在回答里有时才换**（RFC 6749 §6 允许 AS 不发新的，把库里那一条丢掉会终结一份仍然有效的 grant）；`expires_in` 缺失时沿用库里尚未到期的那个值，一个都没有则为 NULL；授予清单宽过 512 则保留原值并 warn；`status` 回 `ACTIVE`、`last_error` 清空、`last_refreshed_at` 置当前；审计 `REFRESH` × `OK`。
- `validateAgainstIssuer` 同样跑一遍，但**失败在刷新路径上是 503 而不是待授权**：一份 issuer 或 audience 不对的令牌说明不了这个人的同意出了什么问题。

### 4.4 失败语义

回答的 HTTP 传输层始终是 200 加 `ResultVo.code`，代码里的 `BizException.code` 决定语义：

| 情形 | 状态 | 库里做什么 | 审计 |
|------|------|------------|------|
| 无凭据行 / 会话无身份 / `invalid_grant` / 「4xx 且响应里没有 error」 / 无 refresh token / refresh 密文解不开 | 401 | `status = NEEDS_CONSENT`，`last_error` 写原因（截 500） | `ISSUE` × `NEEDS_CONSENT` |
| 会话与服务不同租户 | 403 | 不查凭据、不动任何状态 | 不写 |
| 已撤销的行 | 401 | 状态保持 `REVOKED` | `ISSUE` × `NEEDS_CONSENT` |
| 打不通 AS / 非 `invalid_grant` 的拒绝 / 200 但不是令牌回答 / 没有 `access_token` / 令牌与 issuer 或 audience 不符 / client_secret 解不开 | 503 | **状态不变**，只写 `last_error` | `REFRESH` × `ERROR` |
| 刷新时发现没有 token endpoint / 没有客户端注册行 / `client_id` 为空 | 400 | 抛裸 `BizException`（构造器给的就是 400）：不动 `status`、**不写** `last_error` | 不写 |

这三条与 503 那一档分得开：它们说的是这台服务的配置缺了一块、要管理员去补（重跑发现或重存客户端），而不是这份授权出了可重试的故障，所以既不写 `last_error` 也不留审计行——写了就是拿一个人的凭据行去记管理侧的缺失。

`needsConsent` 与 `transientFailure` 分得开是这套设计的核心：`invalid_grant` 意味着授权已经没了、只有用户能取回来；而 5xx 或连不上意味着关于这份授权什么都没变，重试就够了。把后者标成待授权，会因为一次网络抖动把人推过一遍授权页；为了做这件事而清掉一份仍然可用的 grant 比这次抖动更糟。

`markStatus` 自己失败只 warn：写不下去的行不该挡住调用方需要的那个回答，下一次尝试会读到库里仍是的那个状态并得出同样结论。

## 5. 运行侧注入

### 5.1 下发

`McpDetailDto` 带 `authType`，由 `InternalApiController` 从服务行直接带出；`headers` / `envParams` 出 admin 前已解密（`plainConfigJson` / `plainToolEnvJson`）。下发前扣掉四类行：解析不出来的、租户与 agent 不符的、`status == 0` 的，以及 stdio 开关关闭时的 stdio 行。`McpConfigAdaptorImpl` 把 `authType` 落到运行时实体上：空串与缺席同样处理，实体的默认值 `NONE` 保留、不被空串覆盖，并 warn 说明这台服务将被当作无认证对待——一台 OAuth 服务少了这个字段看起来就是普通服务，不会挂上按人令牌，在第一次工具调用上失败，而错误指向服务端。

### 5.2 装配与令牌源绑定

`HarnessAgentLauncher` 的 MCP 装配循环按顺序过：配置取不到 → 跳过；`status == 0` → 跳过；stdio 且 `harness.mcp-stdio-enabled` 为 false → 跳过（第二道防线，两侧开关不一致时才走到这里）；`authType == OAUTH2` 时取令牌源：

- `mcpTokenSourceFactory.forUser(authSessionId, userIdentifier.userId)`，工厂缺席或返回 null → **跳过这台服务**并 warn。没有身份就没有可花的授权，宁可少装一台，也不带着未认证的连接进工具箱。
- `authSessionId` 默认就是本次装配的 sessionId，团队场景下传的是 rootSessionId——授权归属跟着会话主人走，不跟着子代理走。
- 拿到源之后先在这儿（正在构建 agent 的线程上）预热一次 `accessToken(mcpId)`。customizer 会在 MCP 客户端所在线程池里读同一个源，异步握手跑在 common fork-join 池上，一次冷取在那里会占住池里为数不多的线程整个 admin 往返的时间。**预热失败不算错也不上报**：用户可能在会话中途才完成授权，把这件事告诉他的是每请求回调那一路。
- `McpHelper.createMcpClient` 在 `authType == OAUTH2` 而 `tokenSource == null` 时直接抛 `MCP_CLIENT_CREATE_FAILED`：照样建会连上、在第一次工具调用上失败，而错误指向 MCP 服务而不是指向缺失的授权。

**令牌的用户身份来自闭包，不来自传输上下文。** `McpAccessTokenSourceFactory.forUser(sessionId, userId)` 在**建实例时**绑定身份，返回的源只能问那一个会话的令牌；MCP 客户端随 agent 实例建好之后会被它服务的后续所有调用复用，从线程本地读「当前用户」会在缓存的 agent 被共享的那一刻把 A 的令牌交给 B。`AdminMcpAccessTokenSourceFactory` 的注释同样写明：向 admin 只递 sessionId，`userId` 只是本地的一次存在性判断。

### 5.3 注入点与缓存

`McpClientBuilder.httpRequestCustomizer` 接受一个 `McpSyncHttpClientRequestCustomizer`，每个出站请求（含 `initialize` 握手）触发一次：

```kotlin
request.setHeader("Authorization", "Bearer ${source.accessToken(mcpId)}")
```

用回调而不是 `headers(...)`，因为令牌会到期会轮换：建客户端时定死的头冻在连接里，一次长对话会死在访问令牌到期的那一刻——5 分钟令牌的话，就是答到一半就死。`applyUserToken` 同时把该客户端的 `initializationTimeout` 放宽到 30 秒（`OAUTH_INITIALIZATION_TIMEOUT`）：第一次握手要经 admin 再经 AS，是两跳网络，10 秒会让一次冷的首次调用看起来坏了。

`AdminMcpAccessTokenSourceFactory` 的缓存与冷却：

| 机制 | 取值 | 作用 |
|------|------|------|
| 键 | `(sessionId, mcpId)` | 同一个服务从两个会话访问，是两个不同的令牌 |
| 命中判定 | `now + 30 < expiresAtEpochSecond`，没有过期时刻时 `now + 300` | 提前 30 秒续，无期限的令牌给一个 5 分钟的租期 |
| 冷却 | 被拒后 15 秒内不问第二次 | 缺授权靠人点一下解决，不靠重试；否则每次工具调用都是一次 admin 往返、一次对 AS 的刷新、一行同样的审计 |
| 并发 | 64 条带锁 + 锁内重读 | 同一令牌并发铸两次会让第二次刷新撞上已经轮换过的 refresh token |
| 容量 | 两个 map 各 2048，超了先清过期项再清到 1024 | 清空不是选项：丢掉活动令牌的所有会话都会在 MCP 请求回调里重新铸一个，一次缓存满就变成一群阻塞的往返 |

缓存与冷却项的 `toString` 都只输出过期时刻，不输出令牌。

### 5.4 静态头的互斥

`OAUTH2` 的服务在装配前会把它 `headers` 里那条 `Authorization` 摘掉（`networkHeaders`）并 warn 说明「配置里的静态 Authorization 被忽略，改用按人令牌」。两者都会写同一个头，谁赢由传输自己的内部顺序决定，用户在界面上看不到任何依据。带身份的是按人令牌，所以留下它。

`auth_type=OAUTH2` 的服务在 admin 侧也有一条对应口径：`McpServerServiceImpl.listTools`（连通性测试走的同一条路）**直接拒绝**探这种服务——这个检查需要某个人的令牌，而管理侧的探测没有可花的人；用点「测试」那个人的 grant 去列工具，会让工具集取决于谁在问，那不是连通性检查声称要显示的东西。回答里指向详情页的 OAuth 面板。

## 6. 授权终止

### 6.1 服务侧的三种清除

`mcp_user_credential` 的整台服务级清除都走 `deleteByMcpId`，且都发生在 `updateById` 命中之后（没命中说明这一行已经不属于本次事务，为它删掉授权等于为一个仍然是 OAuth 的服务销毁凭据）：

| 场合 | 条件 | 结果 |
|------|------|------|
| 删除 MCP 服务 | `deleteMcpServer` | 与 `agent_mcp_binding` 同一事务清掉这台服务的全部凭据；`mcp_oauth_client` 按 (租户, issuer) 复用，别的服务可能还在用，不动 |
| 服务地址改变 | `wasOAuth && oauthResourceMoved`（请求带了一个不同的 `url`） | 更新路径上**先判这一条**：它是 `if`，`authType` 那条挂在它的 `else if` 上，所以这条命中时走不到下一行。每份授权都是为改之前那个 resource 签发的，令牌校验拿 `aud` 去比配置里的 url，于是一把把全部失效而 `status` 还在读「已授权」。清掉它逼一次重新授权，好过永久且无法解释的坏 |
| 认证方式改走 | `else if (wasOAuth && authType != OAUTH2)` | 上一条没命中时才判：清掉全部凭据并 warn 条数。凭据的读与撤都要先过 `requireOAuthServer`（不是 `OAUTH2` 直接拒），不清就留下一串它的归属人既看不到也撤不掉的密文 |

「这次请求是不是关掉了 OAuth」用的是**覆盖任何字段之前**读出来的 `wasOAuth`；从来不是 OAuth 的服务（含 `auth_type` 为默认值 `NONE` 的行）一次都不查这张表。上游那侧的副本在这三条里都不呈递撤销，靠自己过期；要真撤上游，得在改之前由用户逐条点撤销。

### 6.2 人这一侧

- **账号停用**：`McpSessionOwnerResolver` 对 `status != 1` 的归属人返回 null，换发接口于是报「这个会话没有可授权的用户身份」。`mcp_user_credential` 的行**保留**——误删一个人的凭据比让他暂时用不了更糟，重新启用后授权还在。
- **删除用户账号**：走的是另一条路——`SysUserServiceImpl` 的删除路径调 `deleteByUserId` 一次清掉这个人在任何服务上的 grant。`sys_user` 那一行同样只是逻辑删除，但这些密文的归属人已签不进去、也没有页面点得到撤销，留着纯属无人可管的爆炸半径。
- **用户退出租户 / 租户不符**：同样只在解析处挡住，不动库。
- **公开 MCP（`is_public = 1`）**：在同一租户内共享的是**服务配置**，不是**别人的凭据**。`visibleToCurrentUser` 决定谁看得到这台服务，而 `accessToken` 永远按当前会话归属人的 `(tenant_id, user_id, mcp_id)` 查凭据，没有任何一条路径能花别人的 grant。
- **管理面可见性**：`getMcpServer` 比 `tenant_id`，`getVisibleMcpServer` 再比 `is_public` 与创建人。授权链路里所有按 `{id}` 进来的接口都走后者——这些接口会拿一行去拼跳转地址、写一行凭据，读不到不该读的行才是正确答案。

## 7. 安全不变量

1. **不通过 MCP 协议本身透传令牌**（confused deputy）：令牌只走 HTTP `Authorization` 头，且 `resource` 参数把 audience 绑到这台 MCP 服务（RFC 8707）。
2. refresh token 只在 admin 库内加密存在；`McpDetailDto`、`McpServerResponse`、`McpOAuthStatusResponse`、`AgentSpecInfoResponse` 里都没有能装下它的字段。`McpAccessTokenResponse` 是唯一带访问令牌的 DTO，它只回答内部 API。
3. 令牌只比对、不消费：`iss` 与发现的 issuer 精确字符串相等，`aud` 含目标资源；比对要求读 JWT 声明，因此实现是解出不验签，opaque 令牌记日志而不算通过。
4. scope 最小化；请求 scope 与实际授予 scope 不一致时把差集记进日志。
5. **绝不静默提权**：`NEEDS_CONSENT` 只是把状态与原因写下来并让人重走同意，任何路径都不会自己发起一次新的授权。
6. 公共客户端（`client_secret_enc` 为 NULL）靠 PKCE `S256` 保护，refresh token 轮换后覆盖密文；轮换被并发烧掉的后果由 64 条带锁 + 锁内重读避免。
7. `state` 与 `code_verifier` 一次性、5 分钟 TTL、绑定归属人：`consume` 在尝试换票**之前**取出并删除，因此换票失败不能用同一个 `state` 重试，并发的第二个同 `state` 请求必输；归属与调用者不符就拒绝并 warn，且无论哪一支都先烧掉。
8. 日志与 `last_error` 不带令牌材料：`openToken` 只记行的类别与异常名，`ApiErrors.message` 负责把数据库错误压成一句对外可说的话，上游的 `error_description` 截 200 字符后当作文本给前端。
9. 内存态 pending 的上限是显式拒绝而不是静默丢弃：全局 500 条是所有人共用的预算，每人 5 条挡在它前面，否则一个带脚本的登录用户就能把别人挡在授权页外直到条目老化。
10. 审计不可关闭（写失败只 warn 一次），且不落任何形似凭据的内容。
11. 待授权状态存在**一个 JVM 的内存**里，因此换票必须落在处理过这次 `authorize-url` 的那个进程上。admin 一旦起多副本而路由不粘，换票就会落到没见过这次请求的副本上，用户看到「请求未知或已过期」。要多副本，得先把路由做成粘性或把 `McpOAuthStateStore` 换成共享存储；每人 5 条同样是每副本各数各的。
12. 内部 API 只有 `/api/admin/internal/**` 这一条业务路径是 `permitAll`，实际把关的是 `InternalApiAuthFilter` 的共享密钥。`JwtAuthenticationFilter` 里「把共享密钥当 JWT 递到 `/api/admin/**` 就按内部服务放行」那支不接受 `application.yml` 的占位默认值——那串字符在仓库里公开着；后果是沙箱里的 `harnax-cli` 需要换一个真值进 `ADMIN_INTERNAL_API_SECRET`，否则它的调用一律 401，admin 启动时有一条 WARN 说这件事。

## 8. 接口清单

### 8.1 管理端（浏览器，JWT）

| 方法 | 路径 | 作用 |
|------|------|------|
| POST | `/api/admin/mcp/{id}/oauth/discover` | 解析 AS、读元数据、按 (租户, issuer) 存成本租户的注册 |
| POST | `/api/admin/mcp/{id}/oauth/client` | 存 `client_id` 与可选 `client_secret`（secret 走掩码三态与单列解析） |
| GET | `/api/admin/mcp/{id}/oauth/authorize-url` | 为**当前用户**生成授权跳转地址，可带 `scope` 覆盖 |
| POST | `/api/admin/mcp/oauth/exchange` | 换票：接落地页送来的 `code` / `state` / `error`；路径里没有 `{id}`，目标服务只能由 pending 说 |
| GET | `/api/admin/mcp/{id}/oauth/status` | 当前用户是否已授权、scope、过期、`last_error` |
| POST | `/api/admin/mcp/{id}/oauth/revoke` | 撤销当前用户授权 |

六个接口全部带 JWT，没有免鉴权的 OAuth 入口：`SecurityConfig` 不为这条链路放行任何路径。五个带 `{id}` 的先过 `getVisibleMcpServer`（租户 + 可见性）再要求 `auth_type = OAUTH2`，`exchange` 由 pending 认服务。`authorize-url` / `exchange` / `status` / `revoke` 都要求当前请求带得出用户身份，取不到就报错而不是按租户默认值处理。发现与客户端登记的回答只带 `clientSecretPresent` 布尔，不带密钥本身。

前端三处：落地页 `harnax-webui/src/pages/mcp/oauth-callback.tsx`（`useEffect` 第一句读 query 再 `history.replace` 抹掉，`code` 与 `state` 只停在 effect 里的一个局部对象上、立刻发出去，不进 `useState` 也不落 `localStorage`），路由 `/mcp/oauth/callback` 且 `layout: false`、排在通配路径之前，未登录跳转由 `harnax-webui/src/app.tsx` 的 `loginRedirect` 给出，它对授权落地页（就是 `oauthCallbackPath` 这一个地址）**丢掉 search**、把 `redirect` 指回 `/context/mcp`，只有其他路径才把 `location.search` 一起带上——`code` 与 `state` 因此不会出现在登录页地址栏与回跳参数里，而登录本身已经打断了这次授权，回来的人从 MCP 列表重新发起就是了。按人授权块 `harnax-webui/src/pages/mcp/components/OAuthPanel.tsx`：挂载即读 `status`，三态徽标 + 「去授权」同页跳转 + `Modal.confirm` 包住的「撤销」，并回显 scopes / 过期时间 / `last_error` 与 `callbackUrl` 精确匹配的提醒；读不到 `status` 时不画徽标——「还没读到」不能替用户断言「未授权」。

### 8.2 内部端（agent-service，共享密钥）

| 方法 | 路径 | 入参 | 出参 |
|------|------|------|------|
| POST | `/api/admin/internal/mcp/access-token` | `sessionId`、`mcpId`；**没有 user 字段**，身份由 admin 按会话反查 | `accessToken`、`tokenType`、`expiresAtEpochSecond`；无可用授权 401、跨租户 403、上游不可达 503，都走 `ResultVo.code`，HTTP 恒 200 |

`GET /api/admin/internal/agent-spec/{sessionId}` 不带用户身份：用户信息不进所有下游响应，只在换发那一刻由 admin 反查。

## 9. 边界与不做

| 项 | 状态 | 依据 |
|----|------|------|
| 无认证 / 静态 API Key / 自定义 Header | 已有，与 OAuth 同存一台服务上时 Authorization 头被摘除 | `networkHeaders` |
| OAuth 2.1 授权码 + PKCE（用户级） | 本域的主体 | `McpOAuthUserServiceImpl` |
| RFC 9728 受保护资源发现 + AS 元数据 | 已有，issuer 精确相等才认 | `McpOAuthServiceImpl` |
| `BASIC` | 列已建，写入侧拒绝 | `McpAuthTypes.SUPPORTED`、`resolveAuthType` |
| OAuth2 `client_credentials`（服务级 M2M） | 不做 | 需要第二套凭据归档口径，与「按人」这条链路不同形 |
| 动态客户端注册 DCR（RFC 7009 的 `registration_endpoint` 已存） | 不做 | 规范优先级低于预注册；`registration_source` 只有 `MANUAL` 有写入者 |
| Client ID Metadata Documents（CIMD） | 不做 | 同上 |
| OIDC `id_token` 直传 / JWT assertion（RFC 8693 token exchange） | 不做 | 需要自有 IdP 与 AS 配合，无场景 |
| mTLS（每租户客户端证书） | 不做 | 缺的是证书签发与轮转运维，不是注入点 |
| 云厂商签名（SigV4 / GCP SA / Azure MI） | 不做 | 每个都要引 SDK |
| 外部凭据库 / 第三方 MCP gateway 代持 | 不做 | 决策级变更；这里自建的是它的最小可用版本 |
| 把 harnax 暴露成 MCP server | 不做 | 全仓无 MCP server transport |
| stdio 类型 MCP | 默认关闭 | `McpStdioPolicy`、`harness.mcp-stdio-enabled` |
| 上游 401 的回执（把「这台服务拒了令牌」送回 admin） | 无这条通路 | 一条过期又刷不动的凭据由 `status` 与审计说话，用户从详情页重授 |
| 列表页的授权状态徽标 | 无 | 详情页的 `OAuthPanel` 是唯一入口 |

## 10. 关键文件索引

| 主题 | 文件 |
|------|------|
| 授权数据模型 | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpOauthClient.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpUserCredential.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt` |
| Mapper 与 SQL | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpOauthClientMapper.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpUserCredentialMapper.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpCallLogMapper.kt`、`harnax-entity/src/main/resources/mapper/McpOauthClientMapper.xml`、`harnax-entity/src/main/resources/mapper/McpUserCredentialMapper.xml`、`harnax-entity/src/main/resources/mapper/McpCallLogMapper.xml` |
| 授权码 + PKCE + 换发与刷新 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpOAuthUserService.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt` |
| 发现与客户端登记 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/RemoteJsonFetcher.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt` |
| 会话归属 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/SchedulerClient.kt`、`harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/AgentTaskOwner.kt` |
| 加解密与掩码 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/AesUtil.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`、`harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt` |
| 管理面规则（认证方式、stdio、可见性、清除） | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt` |
| 下发与内部 API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpAccessTokenResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt` |
| 运行侧注入 | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`、`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpAccessTokenSource.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpAccessTokenSourceFactory.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt` |
| 身份链路 | `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/AuthContext.kt`、`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ApiKeyInfo.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt` |
| 用户侧前端 | `harnax-webui/src/pages/mcp/oauth-callback.tsx`、`harnax-webui/config/routes.ts`、`harnax-webui/src/app.tsx`、`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx`、`harnax-webui/src/services/ant-design-pro/mcp.ts`、`harnax-webui/src/locales/zh-CN/pages.ts` |
| 测试 | `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImplTest.kt`、`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolverTest.kt`、`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStoreTest.kt`、`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/McpOAuthControllerTest.kt`、`harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactoryTest.kt`、`harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/McpUserCredentialMapperTest.kt`、`harnax-entity/src/test/resources/schema-test.sql` |
| 客户端构造能力 | agentscope 的 `io.agentscope.core.tool.mcp.McpClientBuilder`（`httpRequestCustomizer`、`initializationTimeout`、`timeout`）与 `io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer` |

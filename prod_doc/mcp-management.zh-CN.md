# Harnax MCP 服务实体管理流程（中文）

> 英文版本见 [mcp-management.en-US.md](./mcp-management.en-US.md)
>
> 工具体系整体设计见 [tool-integration-design.zh-CN.md](./tool-integration-design.zh-CN.md)，工具注解与注册机制见 [tool-capability.zh-CN.md](./tool-capability.zh-CN.md)，技能（Skill）体系见 [skill-management.zh-CN.md](./skill-management.zh-CN.md)。本文覆盖 MCP 服务的数据模型、Admin 管理、绑定下发与运行时装配全链路，文末附历次代码检查的修复记录。
>
> 对上游 MCP 服务的认证，本文的**运行时装配**仍然只有服务级静态凭证这一种生效方式；按用户的 OAuth 2.1 授权是独立设计，见 [mcp-authorization-design.zh-CN.md](./mcp-authorization-design.zh-CN.md)（未实施部分以该文档第 12 节状态表为准）。该设计的 P2-1 已落地（数据模型与管理侧校验：`mcp_server` 多了 `auth_type` / `oauth_config` 两列，创建与更新会校验、详情会回显，见 §7.5），P2-2 也已落地（授权服务器发现与客户端登记两个管理接口，见 §3.5），这一层的管理面前端同样已补齐（表单配 `authType` / `oauthConfig`、详情页的发现与登记客户端，见 §7.8）。`McpDetailDto` 尚未带出这两个字段，运行时也就还没有任何一条按人换 token 的链路。

## 1. 概述

MCP（Model Context Protocol）服务是 Agent 的外部工具来源之一，与内置工具（`BUILTIN`）、HTTP 代理工具（`HTTP`）并列。MCP 实体管理与工具体系**只共享同一套分层**，不共享生命周期：内置工具由 `BuiltinToolAutoRegistrar` 在启动时按代码收敛，MCP 服务则全部由页面与 API 手工维护，增删改查就是它的完整生命周期。分层如下：

- **harnax-entity**：`McpServer` / `AgentMcpBinding` 实体与 Mapper；
- **harnax-admin**：MCP 服务的 CRUD、密钥加密存储、掩码回显、连通性测试、绑定管理与配置下发；
- **harnax-agent-service / harness-core**：运行时按智能体配置装配 MCP 客户端。

## 2. 数据模型

### 2.1 mcp_server（MCP 服务主表）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`

| 字段 | 说明 |
|------|------|
| `name` / `description` | 服务名与描述。服务名**租户内唯一**（迁移 `V23__add_mcp_server_name_unique_key.sql`：生成列 `active_name` + `uk_mcp_server_tenant_active_name (tenant_id, active_name)`）。V23 之前生产库里 `mcp_server` 根本没有名称唯一键（只有 `KEY idx_tenant_id`），查重全指望应用层的**全局** `selectByName`：并发能建出两条同名行，别租户占用的名字自己又不能用 |
| `type` | 传输类型：`stdio` / `sse` / `streamablehttp`（默认） |
| `command` | 执行命令（仅 stdio 类型使用） |
| `url` | 服务地址（sse / streamablehttp 类型使用） |
| `headers` | HTTP 请求头 JSON，格式 `[{"key":"Authorization","value":"...","secret":true}]`；`secret=true` 条目的 value 以 **AES-256-GCM 加密**存储。只能承载**服务级静态凭证**（整租户共用一份、不过期），按用户的授权见 mcp-authorization-design |
| `envParams` | 环境参数 JSON（stdio 类型的进程环境变量），格式同 `ToolEnvParamEntry`，secret 条目加密存储；与 `headers` 共用同一套掩码回写规则（见 3.3） |
| `authType` | 上游认证方式（迁移 `V25`）：`NONE` / `STATIC_HEADER` / `BASIC` / `OAUTH2`，常量表在 `McpAuthTypes`。管理侧只收 `SUPPORTED = NONE / STATIC_HEADER / OAUTH2`——`BASIC` 有列没运行时（枚举值留着是给 P4 用，现在传就报错「not wired into the runtime yet」），未知值同样拒收，理由是**入库即下发**，运行时收到认不得的分支比收到 `NONE` 更难查。`NONE` 与 `STATIC_HEADER` 走同一代码路径（都是读 `headers`），区别只在给管理员一个可读的标注 |
| `oauthConfig` | OAuth 非敏感配置 JSON（`V25`），由 `admin/dto/McpOAuthConfig.kt` 序列化：`authorizationServer`（留空则由 MCP 服务自身的 RFC 9728 元数据发现，见 3.5）、`scopes`、`audience`、`resourceIndicator`（是否发 RFC 8707 `resource` 参数）。**故意做成类型化 DTO 而不是自由 JSON**：客户端密钥与 token 在这上面没有字段可写，才不会被误写进这列——这列会以明文回给前端表单。仅 `authType=OAUTH2` 合法，切走 OAuth 时管理侧把它清成 null |
| `status` | 启用状态（0 禁用 / 1 启用）。随 `McpDetailDto` 下发，运行时会话装配时 `status=0` 的服务直接跳过，不再创建 MCP 客户端 |
| `isPublic` | 公开状态（0 私有 / 1 公开），实体、创建请求与列缺省（迁移 `V22`）均为 1。列表可见性口径为 `is_public = 1 OR creator = 当前用户`，再叠加租户过滤（见下） |
| `creator` / `tenantId` | 创建人与租户。`tenantId` 由创建时的 `TenantContext` 写入并出现在 insert / resultMap 中；列表查询在传入 `tenantId` 时追加 `AND tenant_id = #{tenantId}`，与 CLI、技能同口径——`is_public` 只在租户内共享，跨租户的公开服务不再出现在别人列表里。**单行读写同样受租户约束**：`getMcpServer(id)` 取到行后比对当前租户，不属于自己就当不存在，`updateMcpServer` / `toggleMcpServerStatus` / `deleteMcpServer` 都从它进入。这一层是必须的——`MybatisTenantInterceptor` 的 `intercept` 整体是注释状态（空转），`selectById` 的 SQL 里也没有租户条件，只靠列过滤的话猜到自增 id 就能改删别租户的服务 |
| `active` | 逻辑删除标记（0 已删除 / 1 有效） |

### 2.2 agent_mcp_binding（智能体-MCP 绑定表）

实体：`AgentMcpBinding.kt`

- `agentId` / `mcpId`：绑定关系，`(agent_id, mcp_id)` 为唯一键（迁移 `V19__add_mcp_binding_unique_key.sql`，建键前先折叠历史重复行、保留最后写入的那条）；应用层 `saveMcpBindings` 也会 `distinctBy { mcpId }` 去重，与工具侧同构；
- `envBindings`：环境变量绑定 JSON 快照（envKey + `envVarId` 或 `customValue`），**注意该通道只注入 `ToolEnvContext` 供 ToolBox 工具读取，不注入 MCP 客户端本身**（见第 6 节）。

表上曾有 `enable_skip`（`"true"` / `"false"` 字符串），含义是「查不到 `mcp_server` 行时要不要跳过」。它名不副实：服务连不上时 `McpHelper.createMcpClient` 与 `registerMcpClient().block()` 直接外抛，开关救不回来；而它真正覆盖的那一路（配置记录缺失）与工具侧一样，跳过与否只是「打 warn 还是抛错」的差别，没有用户真正需要的选择。因此整条链路已删除该列（迁移 `V20__drop_mcp_binding_enable_skip.sql`）、`McpSpec.skipIfMissing` 与 `AGENT_MCP_NOT_FOUND` 错误码：配置缺失一律告警跳过，与工具侧 V17 后的行为一致。

### 2.3 McpDetailDto（内部 API 下发载体）

实体：`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`

Admin 内部 API 下发给 agent-service 的完整 MCP 配置（含服务级 `status`），免去 agent-service 直查 `mcp_server` 表。其中 `headers` / `envParams` 为**下发前解密的明文 JSON 对象**（`{"KEY":"value"}` 形态），因为 AES 密钥只在 admin 侧，接收端 `PlaintextMcpConfigDecryptor` 只做解析不再解密。

这一层目前**没有** `authType` / `oauthConfig` 字段：V25 的两列还停在 admin 侧，把认证方式交给运行时是 OAuth 方案 P3 要补的第一步。

### 2.4 V26 的三张 OAuth 表（客户端与凭据已接入，审计待接入）

迁移 `V26__add_mcp_oauth_tables.sql` 建了三张表，字段与取舍逐条记在 mcp-authorization-design §5.2–5.4，这里只登记它们各自接到了哪一步：

- `mcp_oauth_client`：租户 × 授权服务器的客户端注册（一行一个 `client_id`，同一 AS 后的多个 MCP 服务共用），`client_secret_enc` 存密文。**已由 P2-2 接入**：发现与登记两个接口写这张表，规则见 §3.5；
- `mcp_user_credential`：用户 × MCP 服务的授权结果，access / refresh token 的唯一藏身处。**已由 P2-3 接入**：换发成功写密文并置 `ACTIVE`，撤销把两个密文列写回 NULL 并置 `REVOKED`，规则见 §3.6；
- `mcp_call_log`：只追加的审计，记结果与耗时，不记请求体与 `Authorization`；写入方是 P2-4 的换发接口，**还没有任何代码写它**。

前两张的实体与 Mapper 已就位并被 `McpOauthClientMapperTest` / `McpUserCredentialMapperTest` 覆盖，`mcp_call_log` 的 Mapper 只有 `insert`（append-only 的口径就体现在「没有别的方法可调」）。删除服务时这条级联也在：`deleteMcpServer` 连带删掉那张表里属于该服务的行——行没了就没有可呈现的对象，留着密文纯属多余的爆炸半径。`mcp_oauth_client` 在这条路上**有意不动**：一行代表的是「这个租户在这个授权服务器上的客户端身份」，同一 AS 后面的其他 MCP 服务还在用它。

## 3. Admin 管理流程

实现：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt` + `service/impl/McpServerServiceImpl.kt`，前缀 `/api/admin/mcp`。

### 3.1 接口清单

| 接口 | 方法 | 说明 |
|------|------|------|
| `/page` | GET | 分页查询（keyword / status / type 过滤，并带当前租户的 `tenant_id` 过滤） |
| `/{id}` | GET | 服务详情（secret 字段掩码回显） |
| `/`（根路径） | POST | 创建 MCP 服务 |
| `/update/{id}` | PUT | 更新 MCP 服务 |
| `/toggle/{id}` | PUT | 启用 / 禁用 |
| `/{id}` | DELETE | 逻辑删除（`active=0`），并在同一事务内物理清理该服务的全部 `agent_mcp_binding` 绑定 |
| `/{id}/connectivity-test` | POST | 连通性测试（内部调用 listTools） |
| `/{id}/list_tools` | GET | 实时连接 MCP 服务拉取工具列表（名称 + 参数 Schema） |
| `/{id}/oauth/discover` | POST | 发现授权服务器并落库端点（见 3.5） |
| `/{id}/oauth/client` | POST | 手工登记 / 修改该授权服务器上的客户端凭据（见 3.5） |
| `/{id}/oauth/authorize-url` | GET | 给当前用户生成授权跳转地址，可带 `scope` 覆盖（见 3.6） |
| `/oauth/exchange` | POST | 换票：接前端落地页送来的 `code` / `state` / `error`，**带 JWT**，凭据写给调用者本人（见 3.6） |
| `/{id}/oauth/status` | GET | 当前用户在这台服务上的授权状态（见 3.6） |
| `/{id}/oauth/revoke` | POST | 撤销当前用户的授权（见 3.6） |

OAuth 接口全在 `McpOAuthController`，与 MCP 服务 CRUD 共用 `/api/admin/mcp` 前缀和同一套 `ResultVo` + `ApiErrors` 错误口径，也就是**六个接口都要过 JWT**。第十四轮之前这里还挂着第七个 `McpOAuthCallbackController`（`GET /oauth/callback`，回 HTML、免鉴权），它已被换票接口取代并删除，理由见 §7.13。

### 3.2 创建 / 更新校验与加密

1. **名称唯一性**：`selectByName(name, tenantId)` 在**当前租户内**查重，重名抛 `BizException`；数据库侧由 `V23` 的 `uk_mcp_server_tenant_active_name (tenant_id, active_name)` 兜底（并发创建撞的是索引，`ApiErrors` 把该索引名映射成人话而不是吐 SQL）；
2. **type 与字段联动校验**（`validateTypeAndFields`）：`stdio` 必填 `command`；`sse` / `streamablehttp` 必填 `url`；其他 type 拒绝；
3. **密钥加密**：`headers` 经 `SecretFieldEncryptor.serializeWithEncryption`、`envParams` 经 `serializeToolEnvParams` 加密后序列化落库（仅 `secret=true` 条目加密）；
4. **掩码回写**：更新时 `headers` / `envParams` 仅在请求携带时重新序列化，且都会把**本行现有的那列 JSON**一并传进去——详情接口回显的是掩码，表单原样提交回来时必须沿用库里密文而不是把掩码加密一遍（见 3.3 与 7.3）；
5. **缺省值**：创建时 `type` 缺省 `streamablehttp`（与实体、DDL 一致），`status` / `isPublic` 请求缺省取 1，`is_public` 的列缺省也已由 `V22` 改为 1，绕过应用直接 INSERT 时行为一致；
6. **更新语义**：`McpServerUpdateRequest` 的 `name` / `description` / `type` / `command` / `url` / `status` / `isPublic` 全部可空，**省略即保持原值**（`updateMcpServer` 逐字段 `?.let` 覆盖），非空时由 `updateMcpServer` 写入，`updateById` 的 SET 列表包含 `status` 列并显式刷新 `update_time`。列表页的启停开关走 `/toggle/{id}`，由独立的 `updateStatus` 语句完成；
7. **租户**：创建时写入 `TenantContext` 的当前租户（无请求上下文时取默认租户 1）；此前 insert 语句根本不写 `tenant_id`，所有行都留在缺省租户，因此启用列表过滤的同一批迁移（`V22`）先按创建人归属回填了一次 `tenant_id`。`/{id}` 系列接口（详情 / 更新 / 启停 / 删除）先经 `getMcpServer` 取行并比对当前租户，别租户的 id 一律按「不存在」处理。
8. **认证方式**（`resolveAuthType` / `validateAuthType` / `writeOAuthConfig`，随 P2-1 落地）：`authType` 缺省 `NONE`，取值必须在 `McpAuthTypes.SUPPORTED` 内，`BASIC` 与未知值都拒（前者是「列有、运行时不认」的假开关）；`OAUTH2` 不许配 `type=stdio`；`oauthConfig` 只在 `OAUTH2` 下合法，非 OAuth 却带配置是报错而不是静默丢弃，`authorizationServer` 必须是 http(s)；更新时这几道校验跑在**合并后的行**上（请求没带的字段按库中原值算），且在 `updateById` 之前，改 `type` 把服务改成 stdio 同样会被拒；离开 `OAUTH2` 时 `oauth_config` 清成 null，**并连带删掉 `mcp_user_credential` 里这台服务的全部行**——读状态与撤销都要经 `requireOAuthServer`，而它拒绝一台不再是 `OAUTH2` 的服务，留下的密文对它的持有人来说既看不见也撤不掉（与 §3.6 第 8 条「撤销是用户唯一的出路」是同一件事的两头）；上游那份不去呈递，按各自的有效期自然过期，与删除服务时那一刀同口径（`deleteMcpServer` 也删这张表，`mcp_oauth_client` 两处都不动，因为同租户可能还有别的服务共用那条注册）。清理的条件是「这次请求把 OAuth 关掉了」，不是「这行现在是 OAuth」：早于 `V25` 的行 `authType` 是 null，把它当成「刚离开」就会去删一张与此无关的表。逐条断言在 `McpServerServiceImplTest.AuthTypeTests`（12 个，编号 MCS-13～MCS-24，见 `docs/unit-test-cases.md` §5.3）。

### 3.3 掩码回显

`McpServerResponse.fromEntity` 解密 secret 条目后掩码：长度 > 7 显示「前 3 位 + `****` + 后 4 位」，否则显示 `******`；解密失败也显示 `******`。前端永不接触明文密钥。

掩码是**双向约定**：既然前端看到的只有掩码，未改动的字段原样提交回来时也是掩码，写库侧必须认得它。`serializeWithEncryption` / `serializeToolEnvParams` 用 `value.contains("****")` 判定掩码（`******` 本身含 `****`，两种形态一起覆盖），命中即按 `key` / `envParamName` 从本行现有 JSON 里取回密文原样保留；取不到（条目被改名、或库里就没有）就抛 `BizException` 要求重填，绝不把掩码当明文加密。

这条约定原本是「JSON 条目列表」专用的。`mcp_oauth_client.client_secret_enc` 是**单列**密钥、也要在页面上回显掩码，于是同一套判定被提成 `SecretFieldEncryptor.resolveSecret(provided, storedEncrypted)`：`null` 即保持库中原值，掩码即沿用库中密文（库里没有密文就报「请重填」而不是把掩码加密），空白串即显式清空（公开客户端 + PKCE 的正常形态），其余按新值加密。三种「不改」的形态各有意。

### 3.4 加解密基础设施

- `SecretFieldEncryptor`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/`）：实现 `McpConfigDecryptor` 接口（定义在 `harnax-common`），基于 `AesUtil`（AES-256-GCM）；
- `McpConfigDecryptor`（`harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/`）：跨模块解密 SPI，提供 `decryptToMap`（headers）与 `decryptToolEnvParamsToMap`（envParams）两个方法。

### 3.5 OAuth 发现与客户端登记（P2-2）

实现：`admin/controller/McpOAuthController.kt` + `service/impl/McpOAuthServiceImpl.kt`，出站请求集中在 `admin/util/RemoteJsonFetcher.kt`。这一层只做「管理员每个 (租户, 授权服务器) 做一次」的配置动作：发现端点、落一条 `mcp_oauth_client`、登记客户端凭据。**它不换取任何 token，运行时也还没有一条按人注入的路**（那是 P3）。规则：

1. **准入门槛**：两个接口都先经 `McpServerService.getMcpServer(id)` 取行——租户守卫在那里（§7.4 的第四轮），别租户的 id 按「不存在」回答；再要求 `authType == OAUTH2` 且 `url` 是非空 http(s)。所有地址先 `trim()` 再判（库里存了带空格的值也不能让 `URI.create` 抛原生异常），并对着目标列的宽度判长（`issuer` 255、其余 URL 500）：MySQL 对超宽值要么抛 1406 要么在非严格模式静默截断，而截断一个身份列的结果是「一行再也对不上 token 的注册」。把 `authType` 选成 OAuth 只是让这两个接口可用，服务本身照旧按 `headers` 连接。
2. **issuer 的解析顺序**（RFC 9728 优先，全部失败才报错）：① `oauthConfig.authorizationServer`（管理员手填，来源标 `CONFIG`）；② MCP 服务地址推导出的 protected-resource 元数据，三个候选：路径插入式 `origin/.well-known/oauth-protected-resource/mcp/path`、路径后缀式 `origin/mcp/path/.well-known/oauth-protected-resource`、主机根；③ 直接 GET 一次 MCP 地址，从响应的 `WWW-Authenticate` 里正则取 `resource_metadata` 指针（带引号与不带引号都读，引号是惯例不是规范；来源标 `RESOURCE_METADATA`）——有些服务器只在挑战里说。取 `authorization_servers` 的**第一个**（多于一个时打 info 日志）。手填与「文档说的」过同一道校验，且 issuer 比一般 URL 更严：不许带 userinfo、query、fragment（这三段都不会进元数据 URL，存下来就是谁也确认不了的一个值），结尾斜杠先去掉（复用注册按字节匹配，留斜杠等于凭空多出一个授权服务器）。文档是不可信输入，不允许把用户导向 `file://`。
3. **AS 元数据四个候选**：`.well-known/oauth-authorization-server` 与 `.well-known/openid-configuration` 各按插入式 / 后缀式试一遍（后者最后，因为是老服务器和多数 OIDC 库实际提供的那个）。必须同时有 `authorization_endpoint` 与 `token_endpoint` 才算命中，且这两个值本身也要过 http(s) + 宽度校验——它们是 P2-3 要跳转、也要 POST `code` + `client_secret` 的地址（P2-4 起还要 POST 刷新请求），存进去就等于把攻击面搬进库里。`registration_endpoint` / `revocation_endpoint` 这类可选端点遇到不可用的值则**丢弃并记 warn**（缺一个可选能力不是失败，留一个坏地址才是）。文档自己声明的 `issuer` 与所查 issuer **精确不等**就拒绝（RFC 8414 要求相等，否则一切换 token 都在 issuer 校验上失败，而没人会想到是这一行存错）；文档**根本不声明 issuer** 时按来源区分：issuer 是管理员手填的就接受（有人背书，这也是唯一的逃生口），是文档广告来的就拒绝——那条链路上没有任何人确认过这个地址。
4. **失败必须响**：所有候选的失败原因用 `;` 拼进一条 `BizException` 消息（`Cannot locate the authorization server for <url>: ...` / `No usable authorization server metadata for <issuer>: ...`），总长截到 600 字符——这些串会原样出现在 API 响应体里，而每条都可能裹一句上游自己的消息。报错里的地址一律先抹掉 userinfo。没有任何一条路会「降级成静态头继续跑」——半发现的服务器如果看起来算「配好了」，运行时就可能拿未认证的请求替某个用户去调外部系统。
5. **一个 (tenant, issuer) 一行**：`selectByTenantAndIssuer` 命中就只刷新端点，`client_id` / `client_secret_enc` / `callback_url` 一律从载入的那行带过去——`updateById` 的 SET 列表无条件（§7.5 第 2 条），不携带等于下次发现顺手把客户端注销掉。没命中才 insert，此时 `client_id` 留**空串**，含义是「端点已知、客户端还没登记」，authorize-url 那条路对它报错（P2-3 已实现，发起与换 token 两处都拒，见 §3.6 第 1 条）。`registration_endpoint` / `revocation_endpoint` 发现到 null 也照写 null：那正是「AS 撤掉了 DCR」需要被记下来的时刻。
6. **issuer 回写只动一列**：只要 issuer 不是从 `oauthConfig` 来的（②③），发现成功后用 `McpServerMapper.updateOAuthConfig(id, json)` 把结果写回 `mcp_server.oauth_config`。此前这里是 `updateById(整行)`，而它的 SET 是无条件的：发现要拿着几分钟前读出的那一行去覆盖 status / headers / name，等于把别人正在做的编辑吞掉（一旦哪天读出来的行是解密视图，还会连密文一起毁）。发现只拥有 `oauth_config` 这一列，就只写这一列（外加 `update_time = NOW()`，这一行确实被改过；`McpServerMapperTest` 断言的是其余各列一动不动）。
7. **callback_url 缺省**：新行写 `${app.frontend-base-url}/mcp/oauth/callback`——那是**前端路由**（授权落地页，§3.6 第 3 条），不是本服务的接口；`app.frontend-base-url` 未配置时退回 `app.base-url`（生产由 nginx 同域名代理两者，退回就是对的；开发下 SPA 在 :8000 而 admin 在 :8080，必须显式配）。`McpOAuthClientRequest.callbackUrl` 可显式覆盖，覆盖前同样过 http(s) + 宽度校验。AS 按字节比对的就是这一列存着的值，所以**改了那两个环境变量并不会让老行跟着变**：登记时写进去的那一条要重新保存一次（页面上会给出不一致的告警）。第十四轮之前这一列的缺省是 `${app.base-url}/api/admin/mcp/oauth/callback`，指向一个已经删掉的免鉴权接口。
8. **登记客户端**：`client_id` 必填（在任何写入**之前**校验，不留半行）；`clientSecret` 走 §3.3 末尾的 `resolveSecret` 三态；`registration_source` 目前两条路都写 `MANUAL`（DCR 是 P4）。手工登记时若那行还没有端点，会补一次发现——端点是真正要跳过去的地址，不允许空着。这条路径会对库里存着的 issuer **重跑一遍校验**（那一列可能被手改过、也可能是老版本写的），因为它就是要往那个地址挂 `client_secret`。
9. **`oauth_config` 读不出来就中止**：空列是「还没配」，非空却解析不出来是数据漂移——而发现会重写这一列。按默认值继续的后果是把管理员填的 scopes 覆盖成空列表，还在响应里报「发现成功」。同一条规则也管住了 MCP 编辑：表单不带 `authorizationServer` 时更新会保住库里已发现的 issuer（否则一次改 scopes 就会注销掉那次的发现结果），但显式给了一个不可用的值仍然拒。
10. **回显不带密钥**：`McpOAuthDiscoveryResponse` 只回 `clientSecretPresent` 布尔，明文 secret 不出 admin；同时回 `issuerSource`（让页面说清「这是发现的还是你填的」）与 `unknownScopes`（请求的 scopes 里 AS 不认识的部分——拼错否则要等用户登完录被 400 才发现，这里报错是免费的）。这个 DTO 被两个接口共用，刻意不再造一个「client 响应」。
11. **出站护栏（`RemoteJsonFetcher`）**：admin 里别处也发得出 HTTP（`McpServerServiceImpl.listTools`、channel、registry），这个组件管的是**目标地址来自管理员输入或上游文档**的那一条路，因此协议白名单与地址底线都收在这里：只接 http(s) 且必须有 host、拒掉云元数据目标（link-local / 任意本地 / 组播地址与 `metadata.google.internal` 这类主机名，在建连接之前就拒；环回与私网**有意**放行，自建 AS 就住在那里面，本项目的 docker-compose 就是）、`Redirect.NEVER`（302 不跟，用例用一个计数器证明）、连接 5s / 请求 10s 超时、响应体 64KB 上限**加**一条真正的读取截止（`ofInputStream` 的排空发生在本线程，请求超时管不到；慢慢滴字节的地址要另有一道界）、body 读失败也包成 `RemoteFetchException`（否则一个断连接会让整个发现崩掉而不是试下一个候选）、只有 JSON **对象**才算元数据（数组与标量同 HTML 一样是「这里没东西」）、`WWW-Authenticate` 可能拆成多个头，全部拼回来（指针只在其中一个里时不能读丢）。这些护栏是一道地板不是一整套策略：地址在这里判过一次，连接时还会被重新解析，DNS 重绑定不在能力范围内。P2-3 给它加了 `postForm`——带着 `code` / `client_secret` / 令牌的表单 POST 也从这一个出口走，于是换 token 与撤销同样继承上面这些协议、地址、重定向、超时与体积规则；同文件顶层的 `normalizeIssuer` 是发现与授权共用的那一条 issuer 规范化，两处各写一份的话，管理员多打一个结尾斜杠就会「配了一行、授权时一行都找不到」。

顺带修掉一个**已上线但失效的配置**：`base-url` 此前挂在 `admin:` 下，而读它的是 `@Value("\${app.base-url:...}")`（`ChannelServiceImpl` 与部署文档都是这个 key），于是 `APP_BASE_URL` 从未生效过——渠道回调一直退回 `http://localhost:8080`。`application.yml` 已把它移到顶层 `app.base-url`，否则 OAuth 的 `redirect_uri` 会原样继承同一个缺陷。测试：`McpOAuthServiceImplTest` 49 个（issuer 解析 14 / 元数据 11 / 发现落库 11 / 客户端凭据 11，第八轮补的都在「地址与文档能说到什么程度才算可用」上；第十四轮多出「浏览器该回到哪」2 条，钉的是 `frontend-base-url` 配了就听它的、没配才退回 `base-url`）、`RemoteJsonFetcherTest` 16 个（真起 JDK `HttpServer`，覆盖 html 与数组 body / 超大响应 / 401 挑战（含拆成两个头）/ 重定向不跟 / 元数据地址与主机名 / userinfo 抹除 / 失败响应的体 / 表单 POST 的编码）、`SecretFieldEncryptorTest` 新增 `ResolveSecretTests` 6 个、`McpServerServiceImplTest.AuthTypeTests` 12 个（含「编辑不擦掉发现的 issuer」）、`McpServerMapperTest` 里两条 `updateOAuthConfig`（证明只动那一列、已删行不动）。

### 3.6 授权码 + PKCE 与凭据存取（P2-3）

实现：`admin/controller/McpOAuthController.kt`（四个 JSON 接口：发起、换票、状态、撤销）+ `service/impl/McpOAuthUserServiceImpl.kt`，待授权状态在 `admin/util/McpOAuthStateStore.kt`，出站仍走 `RemoteJsonFetcher.postForm`。这一层做的是「每个真人对自己的每个 MCP 服务各做一次」的动作：发起同意、把 AS 送回浏览器的那次结果换成凭据、看状态、撤销。**它不把 token 注到运行时**（P3），也不替会话换发（P2-4）。规则：

1. **准入门槛**：`authorize-url` / `status` / `revoke` 都先经 `McpServerService.getMcpServer(id)`（租户守卫在第四轮就装在那）再要求 `authType == OAUTH2`，然后要求**取得出当前用户**——授权是逐人的，`UserContextUtil` 拿不到 userId 就报错，绝不沿用 `currentTenantId()` 那种「回落到租户 1」的口径。配置解析不出 issuer、注册行不存在、或那行的 `client_id` 还是发现留下的空串占位（§3.5 第 5 条），发起与换 token 两处都拒：带着 `client_id=` 去撞 AS 只会让人家的错误页替我们说话。换票接口过的是同一道用户门槛，但它**没有 `{id}` 可挑**：要授权哪台服务只能由 `state` 认出来的那条 pending 说（第 3 条）。
2. **`state` 里钉住这次同意的全部前提**：一条 `PendingAuthorization` 存 (租户, 用户, mcpId, issuer, code_verifier, redirect_uri, resource, 请求的 scopes)，TTL 5 分钟、全局上限 500 条、每人另有 5 条上限（`countFor(userId)` 在生成 verifier 与 state **之前**先数一遍，被拒的那次什么材料都不留）、`consume` 取走即失效（先摘再判过期，空串与不存在同答案）。存内存而不是存表：code 本身就是一次性、分钟级的东西。两条后果写明白——**admin 重启会打断正在同意的那一次**，以及**多副本必须做粘性路由**（换票落到没收过这次 `authorize-url` 的副本上，用户只能看到「请求未知或已过期」）；两种上限都是显式拒绝并各回一句实话，不是静默丢弃——500 条是**所有人共用**的预算，没有每人一条挡在前面，一个带脚本的登录用户就能把它填满，让其他人在整个 TTL 里都发起不了授权（第十五轮补的这条，见 §7.14）。这里钉住的 `userId` 第十四轮起**不再用来认定身份**，它的作用是回答「这次同意该记到谁名下」，并让换票时拿它跟调用者比一次——比对不通过就拒（第 3 条），所以把 `authorize-url` 转发给别人点，别人点完也拿不到发起者的凭据。
3. **换票靠 JWT，`state` 只是防 CSRF 的一次性令牌**：AS 的重定向落到**前端页面**（`harnax-webui/src/pages/mcp/oauth-callback.tsx`，路由 `/mcp/oauth/callback`），由它带着浏览器里已有的 JWT `POST /api/admin/mcp/oauth/exchange`。于是身份来自 Spring Security 的认证上下文，`state` 被降级成「这条 pending 是不是你发起的」的凭据：先取调用者身份（取不到就报错，**pending 原样留着**——没有身份的人连烧掉别人 `state` 的能力都没有），`consume` 紧跟着在**任何分支之前**先把这枚令牌烧掉（被 AS 拒了的那次授权同样结束，一个还活着的 `state` 等于一份可以反复试的铸令牌请求），随后**在读请求体说的任何话之前**先比归属：pending 的归属与调用者不符就回一句「这是在别的会话里发起的」并**只 warn 出 mcpId 与两个 userId**——不记 code、不记 verifier，那些是要发去 token endpoint 的东西。这道比对必须排在「AS 报了什么」之前：否则知道别人 `state` 的人 POST 一个自造的 `error` 就能替对方取消那次在途的授权，而那条 warn 对这件事一句话都不会说（第十五轮修的，见 §7.14）。反过来，AS 真报了 `error` 时**即使 pending 已经过期也照样把上游的理由回给页面**：那种情形下什么都没存、也没什么可烧，上游的原话比一句「请求未知或已过期」有用。这一条比对就是钓同意的探测器：有人把链接转给别人点，受害者那边看到的是失败页，攻击者这边什么也没多出来。本服务不再接收 AS 的浏览器重定向，`SecurityConfig` 与 `JwtAuthenticationFilter` 为回调开的那两处放行已随之删除。
4. **参数怎么拼**：`redirect_uri` 取注册行里那条，不从当前请求推导（AS 按字节比对，那行才是管理员登记的地址）；`scope` 入参覆盖服务端配置并去重，宽过 `mcp_user_credential.scopes` 的 512 就**拒**（截断等于替用户记下一份没人同意过的清单）；`resource` 只在 `oauthConfig.resourceIndicator` 为真且 `url` 非空时带；`audience` 配了才带；PKCE **无条件 `S256`**，不给 AS 元数据里那句「我支持哪些 challenge」留降级路（决定与理由见 mcp-authorization-design §6.2）。
5. **换 token 与它的校验**：换票先把服务行按 `pending.mcpId` 经 `McpServerService.getMcpServer` 重读一遍——这一路是带身份的，所以走的就是别处那道租户守卫（调用者看不见这台服务直接按「不存在」回答），读完再断言 `server.tenantId` 仍等于 `state` 里钉的租户；行没了、换了租户、不再是 `OAUTH2`、或 `url` 已与 `state` 钉住的 `resource` 不一致，都不换（code 是给旧地址换的，存下来是一把开不了门的钥匙）。表单带 `grant_type` / `code` / `redirect_uri` / `client_id` / `code_verifier`，有 secret 就按 RFC 6749 §4.1.3 放体里；非 2xx 用上游的 `error` / `error_description` 拼一句话（截 200 字符、抹 userinfo），200 但不是 JSON 对象同样算失败；没有 `access_token` 就不算授权成功。**上游那句话能不能被读到，取决于出站层解不解析失败响应的体**——第十三轮之前 `RemoteJsonFetcher` 只在 2xx 时解析，这句拼话实际恒为「HTTP 400」，见 §7.12 第 1 条。落库前比两件事：`iss`（响应级与 JWT 声明级各比一次）与发现的 issuer **字节相等**，`aud` 必须含本次的 `resource`。JWT 声明是**解出不验签**的——这里不消费 token 内容，它只被原样转发给 MCP 服务；opaque token 解不出来时**记日志而不是当成通过**。
6. **存储语义**：两个令牌各走 `AesUtil` 加密成 `access_token_enc` / `refresh_token_enc`；新一次换发**整体替换** refresh token（留着上一次的就等于留一条没人刚同意过的铸令牌路子）；`scopes` 优先记实际授予的、没有才记请求的，**授予的清单宽过 512 就整份不记**（`scopes=null`，`last_error` 说明「有多长、列能存多少、所以没记」）——第 4 条那条「宁拒不断」在请求侧成立是因为调用方能改窄参数，回答侧没人能改 AS 的决定，截断只会留下一条谁都没授权的半截 scope；`status=ACTIVE`、`last_error` 除上述情形外清空、`last_refreshed_at` 打点；已有行走 `updateById`（这张表没有软删，SET 无条件，正好让撤销能把密文写回 NULL）；新行走 `insert`，撞 `uk_mcp_user_credential_tenant_user_mcp`（同一用户同一服务的两次授权并发落库）时接住 `DuplicateKeyException`，把这次的 token 更新到先落库的那一行，而不是把一次真实发生的授权报成数据库失败。**换票回答里的 `scopes` 就是这一列记下的那份**，不是 AS 回答里那个原始字段：RFC 6749 §5.1 让 `scope` 可选，AS 授的就是请求的时可以不回，照它那份空清单回答会让落地页显示零条、几秒后详情页的 `status` 读同一列却显示两条（第十五轮修的，见 §7.14）。请求的 scope 比授予的多只 warn，不失败。
7. **状态怎么读**：查询条件恒为 (租户, 用户, mcpId)，看不到别人的行；`REVOKED` 不复活；`accessExpiresAt` 过期就**不算可用**（刷新要到 P2-4，页面不能显示一份运行时花不掉的授权）；没有过期时间的行按可用读；`lastError` 原样回显（它本来就只可能是脱敏后的一句话）。
8. **撤销**：本地一定清（置 `REVOKED` + 两个密文写回 NULL，连 `last_error` 一起清），上游只在注册登记过 `revocation_endpoint` 时发一次 RFC 7009，`token_type_hint` 优先 `refresh_token`；上游连不上或被拒都不让整个操作失败。**六种情况各回一句实话，且互不顶包**（详见 mcp-authorization-design §6.4）：上游接受、上游拒绝、没有撤销端点（那句 RFC 7009 的话只能说给「AS 确实不支持撤销」）、密文解不开（换过 AES 密钥，「解不开」不能说成「没有令牌」也不能说成「上游拒了」）、注册行已删（说不出这条授权属于哪家 AS，同时给出 `POST /api/admin/mcp/{id}/oauth/discover` 这条路）、库里本来就没剩令牌。数据库异常经 `ApiErrors` 收敛，不吐表名。
9. **不出现令牌的地方**：响应 DTO、日志、`last_error` 都没有 token 材料，日志说的是「记下了哪些 scope、expiresIn 多少、有没有 refresh」——scope 名不是凭据（`status` 接口本来就把它们回给浏览器），但令牌本体一个字节都不进日志；`McpOAuthExchangeResponse` 只有 `authorized` / `message` / `scopes` / `accessExpiresAt` 四个字段，**没有任何一个字段装得下 token**，而凭据的落库位置就是那一列密文——把它回给浏览器是一次没有消费者的泄漏。换票的**拒绝也是一句人话而不是一个 HTTP 状态**：`code` 已经被花掉，页面重试不了，只能把上游的理由（`error` / `error_description` 原样拼一句、截 200 字符）连同「请重新发起」显示出来。原来那句「上游 `error_description` 要经 `HtmlUtils.htmlEscape` 才上页面」的防护换成了「走 JSON + React 文本节点」，与 `McpServerServiceImpl.listTools` 带回来的上游字符串同一口径；落地页在**发起请求之前**就把 `?code=&state=` 从地址栏抹掉（`history.replace`），值只停在 effect 里的一个局部对象上、立刻发出去，既不写 localStorage 也不进第三方脚本。
10. **测试**：`McpOAuthUserServiceImplTest` 62（发起 13 / 换票 32 / 状态 7 / 撤销 10；第十四轮把「回调」那 22 条改成「换票」组，多出来的是换票模型才有的分支：归属不符、无登录上下文、`state` 认不出来时不再查任何库、回来的 `state` 没有 `code`、上游原话按预算截断、响应体不含令牌材料、目标服务由 pending 决定；第十五轮再加 4 条——每人占不满那 500 条的共用预算、自造的 `error` 取消不了别人的在途授权、`state` 过期后上游的理由照样回显、AS 省掉可选的 `scope` 时回答里报的是记下的那份；第十三轮补的出站真 HTTP 用例仍在 `RemoteJsonFetcherTest`（16），见 §7.12 第 1 条。打桩部分：出站全在 `RemoteJsonFetcher` 打桩，`McpOAuthStateStore` 用真件——要钉的就是发出去的 challenge 与换回来的 verifier 是同一个、`state` 只够用一次）、`McpOAuthStateStoreTest` 7（多出「每人计数只数本人、且先把过期的扫掉」）、`McpServerServiceImplTest.AuthTypeTests` 12（第十五轮的两条钉「离开 `OAUTH2` 才清凭据，没离开或从来不是 OAuth 的服务一次都不查这张表」）、`McpOAuthControllerTest` 由 13 扩到 16（新增的三条守换票接口对外的错误语义）；原 `McpOAuthCallbackControllerTest` 4 条随被删的控制器一起删除。**还没跑过真实 AS**：mcp-authorization-design §10 那行「用 Keycloak 走通发现 → 授权 → 存 → 撤销」的验收没完成，尤其各家实现对 `resource` 参数的实际反应还没有过实测；新的 `redirect_uri` 形状（指向前端路由）同样一条都没实测过，见 §7.13 与 §7.14。

## 4. 智能体绑定与配置下发

1. **绑定保存**：智能体配置保存时 `AgentServiceImpl.saveMcpBindings` 先删后插 `agent_mcp_binding`，同一请求内重复的 `mcpId` 由 `distinctBy` 去重、数据库侧再靠 `(agent_id, mcp_id)` 唯一键兜底；写入前先用一次 `McpServerMapper.selectByIds` 批量校验这批 id——解析不到有效服务（已删、不存在、或属于别的租户）就抛 `BizException` 拒绝整个请求，而不是留下一条运行时被 `?: continue` 静默跳过的绑定行（那种失败只在日志里，页面看起来是「配了但工具不见了」）；`envBindings` 通过 `serializeEnvBindings` 写入快照——引用全局环境变量（`envVarId`）时解析出 `envVarName` 与解密后的 `envValue`，自定义值存 `customValue`。反向清理：删除 MCP 服务会连带删除其全部绑定，避免残留指向已删服务的行；
2. **配置下发**：agent-service 请求智能体配置时，`InternalApiController.buildAgentSpecResponse` 下发两份数据：
   - `mcpDetails`：完整 `McpDetailDto` 列表（`headers` / `envParams` 为下发前解密的明文对象，`status` 一并带出）。绑定行的 `mcpId` 先去重，再用一次 `McpServerMapper.selectByIds` 批量取服务（与工具侧同款，不再有每绑定一次的 N+1）；查不到行的绑定、以及行不属于本 agent 所在租户的绑定，都打 warn 后剔除——`selectByIds` 本身没有租户条件（内部调用没有可信的 `X-Tenant-ID` 可注入），比对基准取 `agent.tenant_id`；
   - legacy `mcpList` JSON：由**已解析出的那批绑定**重建（服务行已删除的绑定不进这里，否则它的 `env_bindings` 会跟着进 `ToolEnvContext`，与 `mcpDetails` 自相矛盾），`env_bindings` 已通过 `resolveEnvBindingsJson` 解析为明文（`envVarId` 取全局变量**最新**解密值，失败回退快照值）。

## 5. 运行时装配流程（agent-service）

```
AgentSpecResolver.buildAgentSpec()
    ├─ mcpDetails → McpSpec(mcpId)
    └─ mcpList 的 env_bindings → 并入 ToolEnvContext（供 ToolBox 工具读取）
            ▼
HarnessAgentLauncher.createAgentBase()
    遍历 agentSpec.mcpServices：
    ├─ McpConfigAdaptorImpl.getConfig(mcpId)
    │     唯一来源：AgentSpecContextHolder 中 admin 预下发的 mcpDetails（DTO → 实体转换，含 status）
    │     没有查库回退：库里那行是密文，本服务没有 AES 密钥（见 §7.15 第 4 条）
    ├─ 配置存在 + status == 0 → 打 info 日志后跳过（不创建客户端、也不报缺失）
    ├─ 配置存在 + status == 1 → McpHelper.createMcpClient(
    │        mcpConfig, isAsync,
    │        mcpConfigDecryptor?.decryptToMap,            // headers 解析/解密
    │        mcpConfigDecryptor?.decryptToolEnvParamsToMap // envParams 解析/解密
    │     )
    │        └─ 建客户端抛异常 → 打 warn 后跳过这一台，其余照常装配；已建出的半成品立即 close
    └─ 配置缺失 → 打 warn 后跳过（不再有开关，也不再抛 AGENT_MCP_NOT_FOUND）
            ▼
McpHelper.buildMcpConfig() 按 type 分派：
    ├─ stdio          → StdioMcpConfig(command, env = envResolver(envParams))
    ├─ sse            → SseHttpMcpConfig(url, headers = configResolver(headers))
    └─ streamablehttp → StreamableHttpMcpConfig(url, headers = configResolver(headers))
            ▼
McpClientBuilder 构建客户端（buildSync / buildAsync）→ agentBuilder.addMcp(...)
            ▼
这一轮建出的客户端列表随 agent 一起存进 HarnessAgentWrapper.mcpClients
缓存淘汰 / 会话销毁 → wrapper.release()：先逐个 close 客户端，再 close agent
（`HarnessAgent.close()` 不释放 MCP 客户端，见 §7.15 第 1 条）
```

其他运行时入口：

- `McpHelper.listTools`：Admin 连通性测试 / list_tools 接口使用，`initialize()` 阻塞超时 10 秒，失败抛 `MCP_CONNECTION_FAILED`；**无论成功、失败还是超时都在 finally 里 close**——它每次点「测试连接」都新建一个客户端，stdio 那条还会留下子进程；
- `McpHelper.createMcpClient`：异步那条 `buildAsync().block(60s)` 有上限，构建返回 `null` 时抛 `MCP_CLIENT_CREATE_FAILED` 并带上服务名，而不是让调用方在下一行拿到一个 NPE；
- 两个 resolver 均为 `null` 时仍会回退 `{ emptyMap() }`，但容器中已不会给 `null`：harness-core 用 `PlaintextMcpConfigDecryptor` 兜底，它只把 admin 下发的明文 JSON 对象解析成 Map，不再解密（原待办 1 已按此方案修复）。

## 6. 环境变量与密钥的两条通道（易混淆）

| 通道 | 配置位置 | 作用对象 | 解密时机 |
|------|---------|---------|---------|
| `mcp_server.headers` / `envParams` | MCP 服务自身配置（Admin MCP 编辑页） | **MCP 客户端本身**（鉴权头 / stdio 进程环境变量） | **Admin 下发前**解密（`InternalApiController.plainConfigJson` / `plainToolEnvJson`），agent-service 侧只解析明文 |
| `agent_mcp_binding.envBindings` | 智能体绑定 MCP 时的环境变量表单 | **ToolEnvContext**（ToolBox 工具方法读取），不注入 MCP 客户端 | Admin 下发时（`InternalApiController`）已解析为明文 |

## 7. 变更记录

### 7.1 已修复（第一、二轮）

**2026-09 第一轮（MCP 调研发现的问题）**

- **加密 headers / envParams 在运行侧被静默丢弃**（原 TODO-1，高风险）：采用「下发前解密」方案修复——`InternalApiController.plainConfigJson` / `plainToolEnvJson` 在组装 `mcpDetails` 时解密成明文 JSON 对象，AES 密钥只留在 admin 侧；harness-core 新增 `PlaintextMcpConfigDecryptor` 作为兜底 Bean，`mcpConfigDecryptor` 永不为 `null`。解密结果为空而原始串又不是 `[]` 时打 WARN，避免「解析失败」伪装成「没配凭证」。
- **创建与更新的 stdio 口径不一致**（原 TODO-2）：创建接口里的遗留拦截已删除，创建与更新统一由 `validateTypeAndFields` 校验（stdio 必填 `command`、sse / streamablehttp 必填 `url`），前端两个表单也都提供 STDIO 选项。
- **Controller 的 mock 死代码**（原 TODO-3）：`getMockTools()` / `createParameter()` 已删除。
- **网络型 MCP 没有环境变量表单项**（原 TODO-4）：复核后判定为设计如此——`envParams` 是 stdio 进程环境，`McpHelper.buildMcpConfig` 对网络型只消费 `headers`，因此表单按类型分派字段，不是缺陷。

**2026-09 第二轮（与工具侧对齐的口径修复）**

- **停用 MCP 对运行时无效**：`status` 此前不出现在下发链路里，把服务禁用后 Agent 照常连接。现在 `McpDetailDto` 带 `status`，`McpConfigAdaptorImpl` 透传到实体，`HarnessAgentLauncher` 在装配时 `status == 0` 直接跳过并打 info 日志（停用是主动决策，既不算缺失也不报错），与工具侧的停用处理同构。
- **编辑页 status 开关静默失效**：`McpServerUpdateRequest.status` 原为 `Int = 0`（省略即被当成 0），且 `updateById` 的 SET 列表不含 `status` 列。现在 `status` / `isPublic` 均为可空、省略即保持原值，`updateById` 也写入 `status`。
- **删除 MCP 服务不清理绑定**：`agent_mcp_binding` 的查询不过滤 `active`，残留行会让后续每次下发都出一条「MCP 找不到」告警。现在 `deleteMcpServer` 在逻辑删除成功后于同一事务内 `deleteByMcpId` 物理清理绑定；逻辑删除未命中（返回 0）时不做级联。
- **`agent_mcp_binding` 缺唯一键**：新增迁移 `V19__add_mcp_binding_unique_key.sql`，先折叠重复的 `(agent_id, mcp_id)` 再建唯一键；应用层 `saveMcpBindings` 同步 `distinctBy { it.mcpId }` 去重，与工具侧 V18 一致。
- **`is_public` 三处口径不一致**：创建请求新增可空 `isPublic`（缺省 1），更新请求改为可空即「省略不改」，`type` 缺省值从 `stdio` 改为与实体 / DDL 一致的 `streamablehttp`。

### 7.2 已修复（2026-09 第三轮：清空全部待办）

**1. `enable_skip` 开关整体删除（原 TODO-1）**

原待办给两条路：把跳过判断下移到「创建客户端 / 注册」两处捕获异常，或者改名并把口径写清。复核后选了第三条：这个开关没有值得保留的语义。它唯一覆盖的「查不到 `mcp_server` 行」，在工具侧已经被认定为「打 warn 继续构建」即可（见 `V17`）；而连接失败本来就绕过了它——把异常也吞掉会让一个连不上任何工具的 Agent 静默上线，比直接失败更难排查。因此：

- 迁移 `V20__drop_mcp_binding_enable_skip.sql` 删除 `agent_mcp_binding.enable_skip` 列；
- 实体 `AgentMcpBinding`、`McpConfig`（请求 DTO）、`McpDetailDto`、`AgentResponse.McpItem`、`SessionResponse.McpItem` 去掉对应字段；前端两处表单的「允许跳过」开关与相应 i18n 文案一并移除；
- `McpSpec.skipIfMissing` 与 `HarnaxErrorCode.AGENT_MCP_NOT_FOUND` 删除，`HarnessAgentLauncher` 缺失配置时统一 `log.warn` 后继续构建；
- `V17` 当年写的是「MCP 保留该列」，这个前提已不成立，`V20` 的注释里显式推翻了它（迁移文本不可改，只能由后一条说明）。

**2. 下发链路批量查询（原 TODO-2）**

`McpServerMapper` 新增 `selectByIds`，`InternalApiController` 组装 `mcpDetails` 时一次批量取全部绑定的服务；同一处的技能下发（直接绑定 + CLI 关联两条路）也是同形态的 N+1，一并改完。缺行只告警不抛错，因此 legacy `mcpList` 与 `skillList` 都改成按「已解析出的那批」生成，两半口径一致。

**3. 残留能力列删除（原 TODO-3）**

`agent.mcp_list` 只是这一类问题的样本：`createAgent` 从不给 `agent.mcp_list` / `agent.skill_list` / `agent.tool_list` 与 `session.mcp_list` / `session.skill_list` 赋值，所以这些列恒为建表缺省值，而读取方还在——会话详情页的 MCP / 技能列表永远为空，定时任务下发给 Agent 的 `mcpList` / `skillList` 也恒空。迁移 `V21__drop_stale_capability_list_columns.sql` 删除这 5 列，读取方改判：`SessionServiceImpl.convertToResponse` 按 `session.agentId` 读两张绑定表重建列表（会话本身没有绑定表，跟随所绑智能体），`InternalApiController.getAgentTaskSpec` 同样改为按 `agent.id` 读绑定表。注意 `AgentSpecResolver` 解析的是**响应字段** `mcpList.env_bindings`，与删掉的列无关。

**4. MCP 列表查询补租户过滤（原 TODO-4）**

选了「补过滤」而不是「写明单租户前提」，因为 CLI 与技能已有 `tenant_id` 口径，MCP 单缺。`selectMcpServerList` 增加可空 `tenantId`，非空时追加 `AND tenant_id = #{tenantId}`；`McpServerServiceImpl.page` 传入 `TenantContext.getTenantId() ?: 1`。同时补上两个前提：resultMap 与 insert 都写了 `tenant_id`（此前 insert 根本不写，所有行都落在缺省租户），迁移 `V22` 先按创建人归属回填一次 `tenant_id`，否则新过滤会把历史数据整体藏起来。口径变化：别的租户标为公开的 MCP 不再出现在自己的列表里，与 CLI / 技能一致。

**5. `is_public` 列缺省对齐（原 TODO-5）**

`V22` 将 `mcp_server.is_public` 的列缺省改为 1，与实体、创建请求、`agent_tool` 一致。

同一批里顺手清掉的还有 `McpServerServiceImpl` 中「绑定行残留」注释的措辞、`AgentServiceImplTest` 与 `SessionServiceImplTest` 里以 JSON 列为输入的旧用例（改为按绑定表构造）。

**遗留（不属 MCP，本轮未动）**：`AgentMapper.xml` 既不映射也不插入 `agent.tenant_id`，而 `AgentServiceImpl` 会写 `agent.tenantId`、`MpSessionService` 会读它——与本轮修掉的 MCP 缺陷同形。已由第五轮按同口径补上（见 §7.4）。

### 7.3 已修复（2026-09 第四轮：密钥与租户边界）

**1. 掩码回写把真凭据覆盖成掩码（P0）**

详情接口 `McpServerResponse.fromEntity` 对 secret 条目掩码，编辑页 `UpdateForm.tsx` 又把掩码原样装进表单提交，而 `serializeWithEncryption` 此前不区分「新值」与「掩码」，一律 `encrypt(...)` 落库——一次没碰密钥的编辑（只改描述也算）就把凭据换成 `encrypt("Bea****oken")`，全程无报错，直到 MCP 连不上才暴露。现在两个序列化器都多接一个参数（本行现有的那列 JSON），命中掩码时按 `key` / `envParamName` 沿用库里的密文；对不上就抛 `BizException` 要求重填——管理员改了 key 名时静默保留与静默覆盖都是丢数据，只有报错可救。掩码识别沿用既有约定 `contains("****")`（`ModelProviderServiceImpl` 同款），两种形态（`前3****后4` 与定长 `******`）一并覆盖；代价是「真值里恰好有连续四个星号」会被读成未修改，与 api key 掩码同一取舍。

**2. 单行读写补租户归属（P1）**

上一轮给列表加了 `tenant_id` 过滤，但 `selectById` 路径没有：`MybatisTenantInterceptor.intercept` 整体处于注释状态（不重写任何 SQL），于是编辑 / 启停 / 删除只要 id 猜得中就跨租户生效。现在 `getMcpServer(id)` 是唯一入口，取到行后比对当前租户，不属于自己就返回 `null`；`updateMcpServer` / `toggleMcpServerStatus` / `deleteMcpServer` 都改为先经它取行，跨租户与不存在共用一句「MCP server not found」，不区分以免变成 id 探测。历史遗留绑定行的表现与之前一致（下发时 `?: continue` 跳过），不额外报「已删除」。

**3. 服务名唯一键（P1）**

`mcp_server` 从建表起只有 `KEY idx_tenant_id` 与普通索引，名称查重全靠应用层 `selectByName`，并发下能建出两条同名行；而列表已经租户化，应用层的 `selectByName` 却仍是全局查——别租户占用的名字自己不能用。迁移 `V23__add_mcp_server_name_unique_key.sql` 先把历史同租户重名行改名（`LEFT(name,80) + '#dup-' + id`，保留 id 最小的那条），再按 `V15` 的生成列套路加 `active_name`（`IF(active = 1, name, NULL)` VIRTUAL）与 `uk_mcp_server_tenant_active_name (tenant_id, active_name)`：MySQL 唯一索引不锁 NULL，软删后的名字可复用。应用层 `selectByName` 同步补可空 `tenantId`，与技能 `SkillRepositoryMapper.selectByName` 同口径；`ApiErrors.DUPLICATE_INDEX_MESSAGES` 注册了这个索引名，撞键时回「An MCP server with this name already exists in your tenant」而不是把 SQL 甩给页面。

**4. 绑定 id 校验（P1）**

见 4 节：`saveMcpBindings` 写入前批量校验，解析不到就整个请求拒绝。

**5. `update_time` 恒为旧值**

`updateById` 的 SET 列表显式写 `update_time = #{updateTime}`，而 `updateMcpServer` 从不刷新这个字段——每次编辑都把实体里带来的旧时间戳原样写回，列上的 `ON UPDATE CURRENT_TIMESTAMP` 又因为被显式赋值而不触发，列表按更新时间排序时看着永远没动过。现在更新前显式取 `LocalDateTime.now()`。

**6. 更新请求的字段可选性**

`McpServerUpdateRequest` 的 `name` / `description` / `type` / `command` / `url` 由「非空 + 缺省值」改为可空，省略即保持原值（与上一轮的 `status` / `isPublic` 同口径），`updateMcpServer` 改为逐字段 `?.let` 覆盖；此前只想改 url 的部分提交会把 `description` 清成空串、把 `type` 顶成缺省值。`name` 只有「不传」才是保持原值，传空串是直接拒绝（`MCP name cannot be empty`），重名查重按本行自己的租户走 `selectByName(name, mcpServer.tenantId)`。

**7. 两处测试根因**

- `SecretFieldEncryptorTest` 用的是裸 `ObjectMapper()`，Jackson 绑不上 Kotlin 数据类的构造参数，`deserializeEntries` 一律返回字段为空的条目——该类 6 个失败用例（此前在 skill-management 里被记为「与 MCP 无关的既存失败」）根因在此，换成与生产 `JacksonConfig` 同款的 `jacksonObjectMapper()` 后全绿。教训是这条：测试夹具的 mapper 必须与注入给被测类的那个同源，否则「序列化能跑、反序列化全空」这种断裂正好绕过被测逻辑。
- `harnax-entity` 的 `schema-test.sql` 里 `mcp_server` 挂着 `UNIQUE KEY uk_name (name)`，而生产从来没有这条索引（V1 只有 `idx_tenant_id`）。测试库一直在断言一个生产不存在的约束，V23 之后已按生产对齐。

**遗留**：工具侧与 CLI 侧的掩码回写同形缺陷（`AgentToolServiceImpl`、`CliServiceImpl`）当时未修，共用 helper 已就位，各补一个参数即可——第十轮把这两个服务实际持有的三处一并闭合，另在环境变量里发现第四处（见 §7.9）。本轮记为「要先补 `agent.tenant_id` 才谈得上下发侧过滤」的那半，已在第五轮做完（见 §7.4）。

### 7.4 已修复（2026-09 第五轮：租户归属真正落库）

**1. `agent.tenant_id` 补列与按租户判定（OAuth 方案 P1）**

与第四轮修 MCP 缺陷同形：`AgentMapper.xml` 的 resultMap 与 insert 都不带 `tenant_id`，`AgentServiceImpl` 赋的值落不了库，所有 agent 行停在缺省租户 1。现在这一列进了 resultMap 与 insert，`selectAgentList` 补了可空 `tenantId` 条件（与 `selectMcpServerList` 同款），单行读 `AgentServiceImpl.getAgent` 比对当前租户、别租户按「不存在」回答，`updateAgent` / `toggleAgentStatus` / `deleteAgent` 都走这一道（写入口不过守卫就等于把列表过滤做成摆设）；mp 侧 `MpAgentService.getAgentDetail`、`MpSessionService.createSession` 用 `sys_user.tenant_id` 拒跨租户。`updateById` 有意不带 `tenant_id`：归属不是可编辑字段。

**2. `session.tenant_id`**

`SessionServiceImpl.createSession` 此前完全不写这一列（只有 mp 路径按 agent 归属写），建出来的会话都落在缺省租户；现在建会话打当前租户标，`selectSessionList` 补租户条件，口径与 `agent` 一致。

**3. 下发侧按 agent 租户过滤 MCP**

第四轮的遗留：`selectByIds` 没有租户条件，V23 之前存下的跨租户绑定仍会被解析并解密下发。内部调用没有可信的 `X-Tenant-ID` 可注入，比对基准只能取 agent 自己的归属，因此 `buildAgentSpecResponse` 多收一个 `agentTenantId`（三个解析入口都持有 `Agent` 实体），按它剔除别租户的服务行——两半答案都不出现，`secretFieldEncryptor` 也不被调用（用例 IAD-06）。与 `McpServerServiceImpl` 的可见性口径一致：`is_public` 只在租户内成立，不存在跨租户共享。

**4. `V24__backfill_agent_session_tenant.sql`**

上面两条过滤上线即有风险：历史行都在租户 1，租户 2 的用户会先看不见**自己**的 agent 与会话。故按 `V22` 回填 `mcp_server` 的同一条规则（创建人主租户 `sys_user.tenant_id`）回填 `agent` 与 `session`。`session.creator` 两种形态（web 存用户名、小程序存数字用户 id）分别 join，用 `REGEXP '^[0-9]+$'` 保证两条语句互不重叠。迁移不可改写，`V22` 保持原样，本次另起 `V24`；OAuth 方案原计划的 `V24` / `V25` 相应后移为 `V25` / `V26`。

### 7.5 已修复（2026-09 第六轮：OAuth 数据模型与管理侧校验）

**1. 落地的范围（OAuth 方案 P2-1）**

`V25__add_mcp_oauth_columns.sql` 给 `mcp_server` 加 `auth_type` / `oauth_config`；`V26__add_mcp_oauth_tables.sql` 建 `mcp_oauth_client` / `mcp_user_credential` / `mcp_call_log`。配套的是 `McpAuthTypes` 常量表、三个新实体与三对 Mapper（XML 同名）、`admin/dto/McpOAuthConfig.kt` 类型化 DTO、`McpServerResponse` 回显 `authType` 并解析 `oauthConfig`、`McpServerServiceImpl` 的三条校验 helper 与删除时清凭据级联。测试：`McpServerServiceImplTest.AuthTypeTests` 10 个（管理侧规则）、`McpOauthClientMapperTest` 8 个 / `McpUserCredentialMapperTest` 7 个 / `McpCallLogMapperTest` 3 个（真 MySQL，Testcontainers）、`MapperXmlParseTest` 2 个（不依赖 Docker）。

上面那批 Mapper 测试用的是手写 `schema-test.sql`，**不碰 Flyway**，所以「Mapper 测试绿」不等于「迁移可执行」。迁移另有实测：`harnax-admin` 的 `*IT`（failsafe，`-Pintegration-test`）真起应用、`spring.flyway.enabled=true` 打到一个全新 MySQL 8，跑过 `HealthInfoIT` 3/3 绿——即 `V1..V26` 按序执行成功，含 `V26` 的生成列 `active_client_id` 与两个 `utf8mb4_bin` 列。跑法与坑（必带 `-am`、必须 `TESTCONTAINERS_RYUK_DISABLED=true`）记在 `docs/unit-test-cases.md` §17。

**2. 值得留下理由的四个决定**

- `V25` **不回填**：`NONE` 与 `STATIC_HEADER` 走同一条代码路径，`headers` 里也混着纯路由头，回填出来的标注和「管理员自己选的」在库里分不开。所以历史行一律留 `NONE`，要 `STATIC_HEADER` 标签的人显式去点。
- `mcp_oauth_client.issuer` 与 `callback_url` 用 `COLLATE utf8mb4_bin`。MySQL 默认排序规则折叠大小写，而 RFC 8414 比 `iss`、RFC 6749 比 `redirect_uri` 都是精确字符串——大小写不敏感会让 `https://as.example.com` 与 `HTTPS://AS.EXAMPLE.COM` 命中同一行注册，等于把两个授权服务器的客户端身份并成一个。代价说清楚：这两列查询时也必须按二进制比，管理员手输大小写不一致就是查不到（这正是想要的行为，误匹配比查不到危险）；面向人搜索的文本列（名称、描述）保持表默认排序规则，不给管理员加负担。用例 `selectByTenantAndIssuer should match the issuer exactly` 钉住这一点。
- `mcp_user_credential` **没有软删列**：撤销是就地更新（密文写回 NULL、`status=REVOKED`），用户重新授权要能复用 `(tenant_id, user_id, mcp_id)` 这一行；若软删，旧行还占着唯一键，重新授权插不进去。要软删语义的是 `mcp_oauth_client`，用的是 `V15` / `V23` 那套生成列：唯一键里放 `active_client_id`，`active=0` 时它为 NULL，MySQL 唯一索引忽略 NULL，删掉后可重新登记。
- `updateById` 的 SET 列表是**无条件**的（不是 `<if>` 拼），所以实体里新增任何可空列都必须像 `oauth_config` 那样在应用层显式清空，否则「更新时不传」等于「保留旧值」，切走 OAuth 后前端会继续显示一组不用的 scopes。复用查询同理：`selectByTenantAndIssuer` 带 `ORDER BY id LIMIT 1`，让并发发现两次也稳定命中同一行，而不是各拿一条。

**3. 顺带查出的启动级缺陷**

`PlanNoteMapper.xml` 的注释体里写了连续连字符，XML 注释不允许，MyBatis 解析失败；三个服务的 `mybatis.mapper-locations` 都是 `classpath*:mapper/*.xml`，一个文件解析不了就建不起 `SqlSessionFactory`，**admin / scheduler / router 全都起不来**，同时也是 `harnax-entity` 那批 Mapper 集成测试整片红的原因（此前被记成「环境问题」，见 `docs/unit-test-cases.md` §17 的更正）。修完注释后加了 `MapperXmlParseTest`：不依赖 Docker，把 classpath 上所有 mapper XML 过一遍解析，并单独检查注释体里的 `--`。同一轮删掉 `harnax-admin/src/test/resources/schema-test.sql`——`MySQLContainer.withInitScript` 加载的是 `harnax-entity` 那份，admin 那份不在任何 classpath 引用上且已漂移，测试 schema 从此只有一份。

**4. 本轮没做的**

P2-2（授权服务器发现与客户端登记）已在第七轮落地（见 §7.6、§3.5）。P2-3（授权码 + PKCE + 凭据存取与撤销）、P2-4（内部换发接口与 `mcp_call_log` 写入）、P2-5（前端授权入口）、P3（`authType` / `oauthConfig` 下发与运行时按人注入）当时未开始。**运行时现在仍然只有「读 `headers` 里的服务级静态凭证」这一条路**，页面上把 `authType` 选成 `OAUTH2` 只是把意图存了下来（页面本身第九轮才有，见 §7.8；这句话指的是下游没人读它，至今仍成立）。

### 7.6 已修复（2026-09 第七轮：OAuth 发现与客户端登记）

**落地范围（OAuth 方案 P2-2）**

新增 `McpOAuthController` / `McpOAuthService(.Impl)` / `RemoteJsonFetcher` 与两个 DTO（`McpOAuthDiscoveryResponse`、`McpOAuthClientRequest`；配置形态复用 P2-1 的 `McpOAuthConfig`），规则逐条在 §3.5。改动到的既有文件：`SecretFieldEncryptor` 提出 `resolveSecret`（掩码约定从条目列表扩展到单列）、`McpServerResponse.parseOAuthConfig` 由 private 转公开（OAuth 服务读同一列，必须用同一条解析规则而不是再抄一份，抄的那份遇到坏值会静默回答 null）、`ApiErrors` 注册 `uk_mcp_oauth_client_tenant_issuer_client` 的撞键文案（并发发现撞的是索引，页面不该看到 SQL）、`application.yml` 的 `base-url` 归位到 `app.base-url`。

**三个值得留下理由的点**

- **发现失败不降级**：报错消息里带上每个候选的失败原因。宁可管理员去手填 `authorizationServer`，也不要在库里存一组「看起来能用」的端点——P3 之后运行时会拿它替真人去跳授权。
- **重发现必须携带已登记的客户端**：`updateById` 无条件写每一列，所以 `storeEndpoints` 复用旧行时显式保留 `client_id` / `client_secret_enc` / `callback_url`。这个坑在 §7.5 就记过一次，本轮是它第二次差点成真。
- **`client_id = ''` 是一种状态**：不是「还没写完」的中间值，而是「端点已知、客户端未登记」的显式表达，`McpOAuthDiscoveryResponse.clientId` 把它翻译成 null 交给前端，避免页面把空串当成一个真实的 client id。

**验证**：`harnax-admin` 单测 1608 → 1653 全绿；`HealthInfoIT` 3/3（全新 MySQL 8、`V1..V26`、含新增 `@Value` 注入与新 controller 的上下文启动）——跑法与离线 failsafe 的收尾报错说明见 `docs/unit-test-cases.md` §17。

### 7.7 已修复（2026-09 第八轮：发现数据的信任边界）

**这一轮在找什么**

P2-2 的代码功能链路是对的，问题集中在**它把不可信输入当成了自己人**：发现读回来的是别人服务器上的 JSON，落库的端点与 issuer 却会被 P2-3 拿去跳转浏览器、被 P2-4 拿去 POST `client_secret`。三遍 review 出来的东西分四类（逐条规则已并进 §3.5，这里只留「为什么会写成这样」）：

- **存下来的地址没校验过**：`authorization_endpoint` / `token_endpoint` 直接取文档字段入列，`javascript:` 与超宽值都能进来（超宽在非严格模式下是被截断一个 `varchar(500)`，截断即那行注册再也对不上）。现在必需端点过与手填地址同一道 http(s) + 宽度校验；可选端点不过关就丢弃并记 warn——发现不该因为一个可选能力坏掉而整体失败，但也不能把一个坏地址留在要往它发凭据的表里。
- **少了那一次交叉校验**：文档不声明 `issuer` 时，之前的代码当作「没有这个字段罢了」。issuer 是管理员手填的确实无所谓；是文档广告来的，就等于两份互不印证的文档各说一半。现在后者被拒，并在消息里给出路（手填 `oauthConfig.authorizationServer`）。
- **整行回写**：`rememberIssuer` 走的是 `updateById(mcpServer)`，SET 无条件，覆盖 status / headers / name——发现拿着几分钟前读的行去改别人正在编辑的东西；`McpServerMapper` 因此补了定点的 `updateOAuthConfig(id, json)`（`McpServerMapperTest` 逐列证明其余各列不动）。顺带一个真实的连带问题：普通编辑 MCP 会把发现写进去的 issuer 擦掉，让那次的注册变成孤儿，现在「请求没带这个字段」= 「不改它」。
- **读不出来的列被当成没配**：`oauth_config` 非空却解析失败时按默认值继续，后果是发现成功后把管理员填的 scopes 覆盖成空列表还报成功。现在直接中止并说明去哪儿修。
- **`RemoteJsonFetcher` 自己的护栏有洞**：body 读取阶段的异常（连接被重置、chunked 截断）逃逸在它对外承诺的 `RemoteFetchException` 之外，会让整个发现崩而不是试下一个候选；64KB 上限形同虚设（`ofInputStream` 的排空不受请求超时约束，需要一个真正的截止）；只读第一个 `WWW-Authenticate`；`resource_metadata` 只认带引号的写法；地址层面没有元数据服务底线；报错里原样回显管理员/文档给的 URL（可能带 userinfo）。逐条都补了，并把「这是一道地板不是策略」写在类注释里——连接时地址会被重新解析，DNS 重绑定不在这一层的能力范围内。

**有意没做的事**：`discover` / `saveClient` 不加 `@Transactional`（出站等待最长约 80s，包起来等于把连接池里的连接按住这么久，而所有网络 I/O 都在两次写之前完成、两次写各自幂等可自愈）；不放行环回与 RFC1918（自建授权服务器就住在那里面）；不要求端点与 issuer 同源（跨源授权服务器是规范允许的）；`code_challenge_methods_supported` 这轮不看也不存（那是 P2-3 的事，决策点记在 `mcp-authorization-design` §12.1 的 P2-3 行）。

**验证**：`harnax-admin` 单测 1653 → 1677 全绿，`harnax-entity` 230 → 232 全绿（数字口径与「为什么不能看 surefire XML 的 `tests` 属性」见 `docs/unit-test-cases.md` §17）。

### 7.8 已完成（2026-09 第九轮：OAuth 的管理面前端）

前八轮都只在后端，`authType` / `oauthConfig` 与那两个 OAuth 接口在页面上没有任何入口。本轮补齐管理员侧那一半（OAuth 方案的 P2-2 前端部分）：

- `src/typings.d.ts`：`McpServerItem` 与创建 / 更新请求补 `authType` / `oauthConfig`，并声明与后端同名的 `McpOAuthConfig` / `McpOAuthClientRequest` / `McpOAuthDiscoveryResponse`；
- `src/services/ant-design-pro/mcp.ts`：`discoverMcpOAuth(id)`、`saveMcpOAuthClient(id, data)`；
- `src/pages/mcp/components/OAuthFields.tsx`（新建）：`authorizationServer` / `scopes` / `audience` / `resourceIndicator` 四个字段，创建与编辑共用；
- `CreateForm.tsx` / `UpdateForm.tsx`：认证方式下拉 + 选到 OAUTH2 才展开上面四个字段；
- `src/pages/mcp/components/OAuthPanel.tsx`（新建）：详情页的 OAuth 面板，回显配置与一次发现的结果，两个动作（发现、登记客户端）都在这里；
- `pages/mcp/index.tsx`：卡片上给 OAuth 服务加一枚标签，详情页头部加「认证方式」一行。

**四个需要留下理由的取舍**

- **下拉里只有 `NONE` / `STATIC_HEADER` / `OAUTH2`**：运行时认账的就这三个（`McpAuthTypes.SUPPORTED`），`BASIC` 写进来会被 `resolveAuthType` 拒掉，给一个存不下的选项等于让人白填一遍。`STATIC_HEADER` 必须给：§7.5 第 2 条说历史行一律留 `NONE`、「要 `STATIC_HEADER` 标签的人显式去点」，没有这个入口那句话就落不了地。
- **stdio 选不到 OAuth**：`validateAuthType` 拒 stdio + `OAUTH2`（没有 HTTP 请求可挂 token），所以选项按类型 disable，且把类型切回 stdio 时表单里的值一并收回 `NONE`——留着就会得到一个提交即被拒的表单。
- **「留空 = 保留」和「空串 = 清除」必须在 UI 上分开**：`resolveSecret` 是三态（null 保留、掩码保留、空白清除，§3.3），弹窗里 `client_secret` 留空提交时前端干脆不带这个字段，清除要显式勾一个框。否则管理员只想改 `callback_url`，顺手清空输入框就把客户端密钥删了。
- **发现不自动跑、登记客户端在 issuer 未知时 disable**：`discover` 会发出站请求并把 AS 端点快照写库，只能管理员主动点；`saveClient` 在没有 issuer 时直接报错（它要按 issuer 找那一行注册），所以没发现过就不给登记。另外非 OAuth 提交时前端不带 `oauthConfig`：后端在 authType 离开 `OAUTH2` 时本来就会清空那列（§3.5 第 9 条），带上去只会在 `writeOAuthConfig` 处被拒。

**顺带修的页面缺陷**：这一页的中文文案有 15 个键只存在于组件的 `defaultMessage` 里（`pages.mcp.type.*`、`endpoint`、各类校验提示），zh-CN 一直在退回英文，本轮补进 `src/locales/zh-CN/pages.ts`；en-US 侧补了租户用户列表缺的 3 个列名（`role` / `status` / `joinedAt`）。新增的 52 个 `pages.mcp.oauth.*` 键中英一一对齐，两份 `pages.ts` 的键集合做过一次全量 diff。另外修掉 `pages/mcp/index.tsx` 两个既存类型错误：`status` 按 agent 卡片的写法兜 `?? 1`，`currentUser` 的可选性与 `getCurrentUserInfo()` 对齐，并把从模块 import 又当 prop 传一遍的 `hasOperationPermission` 去掉。

**验证**：`tsc --noEmit` 对本轮改动的文件零错误（仓库其他页面仍有既存类型错误，不在本轮范围）；`npm run build` Webpack 编译通过。**没做**：用户侧的授权入口与已授权状态展示（P2-5）、运行时按人注入（P3）、动态注册 DCR（P4）。

### 7.9 已修复（2026-09 第十轮：掩码回写这一类一次清完）

第四轮修的是 MCP 那一处（§7.3 第 6 条），缺陷的形状是「详情页把敏感值返成掩码 → 编辑提交原样回传 → 后端把掩码当新值加密入库，真凭据就地销毁」。本轮把剩下的同类点全部关掉，并顺手把这条规则从四份散落的判断收成一处定义。

**1. 四处站点**

- **CLI 的 `env_params`（活的前端数据丢失）**：`pages/cli/components/CliForm.tsx` 编辑时把详情返回的掩码直接填进输入框，而 `CliServiceImpl.updateCli` 调 `serializeToolEnvParams` 时不带 `storedJson`——掩码被当成新值加密入库，`KUBECONFIG` 这类值就此变成一串 `*`。补上第二个参数 `cli.envParams`，与第四轮 MCP 的写法同形。
- **工具的 `http_headers`**：`AgentToolServiceImpl.serializeHeaders` 之前只有「加密」一条路，`updateAgentTool` 也没把行里现有的 JSON 递下去。改为委托 `serializeWithEncryption(entries, storedJson)`，更新时传 `existing.httpHeaders`。
- **工具的环境参数（形状不同，要多读一次）**：`agent_tool_env_param` 是独立表，更新走「先 `deleteByToolId` 再 `batchInsert`」。等到需要判断掩码能不能承接时，那行已经没了，库里再没有第二份密文可读——所以读取必须挪到删除之前：`storedEnvSecrets(id)` 先按 `secret == 1 且 defaultValue 非空` 收一张 `envParamName → 密文`，再交给 `saveToolEnvParams` 逐条解析。这一处不是「补一个参数」能了结的，第四轮的遗留描述把工具侧当成了一处，实际是两处（headers 一列、env params 一张表）。
- **环境变量（敞口在接口，前端本来就避开了）**：`pages/env-variable/components/UpdateForm.tsx` 对敏感项不预填掩码、留空就不带 `envValue` 字段，所以页面走不到。但接口是公开的，`updateEnvVariable` 收到掩码回传仍会加密入库，因此在服务端补同一判断：命中掩码就保住当前 `envValue`（已是密文）不动。

**2. 规则收成一处**：`SecretFieldEncryptor` 新增 `resolveEnvParamValue(entry, storedSecrets)`，配一个私有的 `storedEnvSecrets(entries)` 从旧 JSON 里收出密文表（与 `AgentToolServiceImpl` 那个同名但从行表读的私有方法是两回事，各自负责一种存储形态），`serializeToolEnvParams` 改为调用它们。于是「非敏感或空白原样存 / 新值加密 / 掩码承接库里密文 / 无密文可承接就报 `BizException` 并指名参数」这一条判断只有一份，JSON 列（CLI、MCP）与行表（工具）两种存储形态共用。这条判断此前在 `SecretFieldEncryptor` 内部就有两份（`serializeWithEncryption` 一份、`serializeToolEnvParams` 内联一份），工具的 `saveToolEnvParams` 再退化出第三份「无条件 encrypt」——那第三份就是缺陷本身。现在三份并成一份，`serializeToolEnvParams` 与工具服务都走 `resolveEnvParamValue`。同一个动作里删掉了 `SecretFieldEncryptor.encrypt`（生产代码已无人调用）：留着一个「拿到值就加密」的公开方法，等于给下一次同样的缺陷留门。

**3. 报错不能被包装**：`resolveEnvParamValue` 抛的 `BizException`（`Secret value of "TOKEN" cannot be kept, please re-enter it`）会被工具 create / update 外层的 `catch (e: Exception)` 包成 `Failed to update agent tool: ...`，丢掉「请重新填一次」那半句——而它恰恰是用户唯一能照做的部分。两处各加一条 `catch (e: BizException) { throw e }`，与 `requireManageableTool` 的处理同形（create 侧目前没有 POST 路由，这条 catch 是为了与 update 保持一致，不是在修一个走得到的路径）。

**4. 可达性口径**：工具那两处只有直接调 API 才会碰到——`pages/tool` 只剩列表，没有任何写入口，服务端也没有 POST 创建路由（能写的只有 `PUT /update/{id}`、`PUT /toggle/{id}` 与删除）。CLI 那处是页面默认动作，属实际在丢数据。环境变量那处前端已避开，修的是接口。

**5. 没做**：`mcp_oauth_client.client_secret` 的同形问题第七轮已由 `resolveSecret` 三态收掉（§7.6），本轮未动；模型 provider 的 `api_key` 掩码判断仍在 `ModelProviderServiceImpl` 里自己写了一份（`value.contains("****")`），本轮没有把它并进 `SecretFieldEncryptor`——它没有 `storedJson` 可传，形状差一段，并进来只会把两边都写歪。同一趟扫到的另一处形状不同、也还没修：`BuiltinToolAutoRegistrar` 把注解里的 `defaultValue` 原样入库，遇到 `secret = true` 的参数并不加密（目前唯一的密钥型内置参数 `SMTP_PASSWORD` 没有默认值，所以库里今天没有明文密钥；读出来时 `maskValue` 解密失败会退回 `******`，不会报错）。这条是约定不是强制，要成真得让同步路径也过一遍加密器。

**测试**：`SecretFieldEncryptorTest` 环境参数组 +2（掩码无密文可承接要指名参数、空白敏感值不加密）；`CliServiceImplTest` +1（更新时把行里的 `envParams` 递进序列化）；`EnvVariableServiceImplTest` +1（敏感值掩码回写保住原密文）；`AgentToolServiceImplTest` 36 → 39，新增「删除前先读到密文」（含 `selectByToolId` 早于 `deleteByToolId` 的顺序断言）、「resolver 的 `BizException` 不被包装」、「headers 序列化收到行里的 JSON」，另有 create 侧 3 个用例改为断言「交给 resolver 的 map + resolver 返回值入库」，因为服务不再自己调 `encrypt`。

**验证**：`TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl harnax-admin -am` 一次实跑全绿，`harnax-admin` 单测 1677 → 1684；`harnax-entity` 232 个未受影响（本轮没碰它）。`docs/unit-test-cases.md` §17 的基线数字按这次实跑做了第五次校准。

### 7.10 已修复（2026-09 第十一轮：把 MCP 前后端连起来读一遍）

前十轮都是一处一处修，本轮按「一条链路从页面读到运行时」的方式重读 MCP，重点是新加的认证方式。先记修掉的四处：

- **部署侧没把 `APP_BASE_URL` 传下去**：`application.yml` 已把它放在顶层 `app.base-url` 并写成 `${APP_BASE_URL:...}`（§3.5 末尾那条「已上线但失效的配置」的修法），但 `docker-new/docker-compose.yml` 的 admin 服务不带这个变量、`.env.example` 也没有它——于是打包好的部署仍然退回 `http://localhost:8080`，通道回调与 OAuth 的 `redirect_uri` 一起是错的。现在 compose 显式给 `APP_BASE_URL: ${APP_BASE_URL:-http://localhost:28080}`（28080 是这套 compose 对外的 admin 端口），`.env.example` 补一行并写明 `redirect_uri` 按精确字符串比对。`docs/deploy-harnax-admin.md` 原先把 `app.base-url` 列在「不支持环境变量覆盖」的表里——那句话本身就是缺陷的成因，已挪进环境变量表并写清两个用途。
- **重置按钮只退表单值**：`CreateForm.tsx` 的 Reset 原先只 `form.resetFields()`，而 `mcpType` / `authType` / `isPublic` / `status` 是四个本地 state 且驱动条件渲染。结果是人按了重置，页面上 OAuthFields 还挂着、提交出去的 `type` 已退回 `sse`——显示与载荷不一致。现在 `resetFields` 之后四个 state 一并归位。
- **前端 issuer 校验比后端宽**：`OAuthFields.tsx` 的正则以 `(\/.*)?$` 收尾，query 与 fragment 都放过，而后端 `validateIssuerUrl` 拒 userinfo / query / fragment（issuer 要与 token 的 `iss` 按字节比，多一个 `?a=1` 就是一行再也对不上的注册）。放到后端只多得到一个报错，故把前端正则收到与后端同形：允许路径段，不给 `?` `#`。
- **三个 i18n 键不存在**：`McpConfigPanel.tsx` 与 `ToolConfigPanel.tsx` 引用 `pages.agent.tool.envParamName` / `envVarName` / `envVarValue`，两份 `pages.ts` 都没有——中文界面一直显示英文兜底。中英各补三个。

**测试**：新增 `controller/McpOAuthControllerTest` 6 个。此前两个 OAuth 接口只有服务层测试，控制器这一层的错误语义没人守：并发发现撞 `uk_mcp_oauth_client_tenant_issuer_client` 时 `DuplicateKeyException` 必须翻成 `ApiErrors` 的友好文案且不外泄 SQL 片段（库里长什么样不该发给浏览器）、`BizException` 的原文要一字不动地送到（「先跑一次发现」正是用户唯一能照做的动作）、异常不带 message 时退回 fallback。另断言 `saveClient` 把 `(id, request)` 原样交给服务层。`harnax-admin` 单测 1684 → 1690。

**功能完整性的结论（本轮核对，不改代码）**：配置这一半是完整的，链路停在「端点和 client 都知道了」之后。四个断点按证据列出：

1. 按人的四个接口（`authorize-url` / `callback` / `status` / `revoke`）不存在，`McpOAuthController` 的 KDoc 里就写着它们随授权码流程一起来；
2. `McpUserCredentialMapper` 的 `insert` / `updateById` / `selectByUserAndMcp` 零生产调用方（只有 `deleteByMcpId` 被 `deleteMcpServer` 用到），`McpCallLogMapper` 整个接口零引用——表建好了，没人往 `mcp_user_credential` 写一行；
3. `/api/admin/internal/mcp/access-token` 不存在，运行侧没有换发入口；
4. `McpDetailDto` 不带 `authType` / `oauthConfig`，`McpHelper` 只按 `type` 分支——一条 `authType=OAUTH2` 的记录今天在运行时的行为与 `NONE` 完全一致。

因此页面上那两处「运行时尚未接入」的提示（`CreateForm` / `UpdateForm` 里认证方式那条 `pages.mcp.oauth.authExtra` 说明、`OAuthPanel` 的 info Alert）至今属实，不能删。分期口径仍以 `mcp-authorization-design` §10 为准：P2-3 / P2-4 / P2-5 / P3 未开始。

> **第十二轮之后这段结论已部分作废**：上面四个断点里，1（按人的四个接口）与 2（`mcp_user_credential` 无人写入）已随 P2-3 闭合，3（内部换发入口）与 4（`McpDetailDto` 不带 `authType`，运行时不分支）仍在——所以那两处提示依旧属实。逐条规则见 §3.6，本轮记录见 §7.11。

**报了没修的两处，理由写清楚**：

- 详情页对 OAuth 服务仍会无条件调 `listTools` 并弹一个失败 toast：此时确实连不上（没有按人 token 可带），报错是诚实信号；把它按 `authType` 藏起来，等于让人以为工具列表坏了。
- 编辑表单里 `OAuthFields` 的 issuer 有 `allowClear`，但清空等于「留空 = 保持」：`updateMcpServer` 在 `authType` 仍是 `OAUTH2` 而请求没带 `authorizationServer` 时会拿库里的值接管，界面上并没有真正的「清除」。要给它一个与 `client_secret` 同款的显式勾选，得连着 P2-5 的用户侧界面一起重做，本轮不动。

### 7.11 已完成（2026-09 第十二轮：授权码 + PKCE 与凭据存取，P2-3）

这一轮把 §7.10 四个断点里的 1 和 2 接上：按人的四个接口存在了，`mcp_user_credential` 有生产写入方了。规则的逐条口径在 §3.6，这里只记取舍与后果。设计依据是 `mcp-authorization-design` §6.2 / §6.4。

**落地**：`service/McpOAuthUserService.kt` + `service/impl/McpOAuthUserServiceImpl.kt`（发起 / 回调 / 状态 / 撤销）、`util/McpOAuthStateStore.kt`、`controller/McpOAuthCallbackController.kt`、DTO `dto/{McpOAuthAuthorizeResponse,McpOAuthStatusResponse,McpOAuthRevokeResponse,McpOAuthCallbackResult}.kt`；三个 JSON 接口挂进既有 `McpOAuthController`（不另开控制器，鉴权口径与那两个管理接口保持同源）。**不碰运行时**：没有换发接口（P2-4），`McpDetailDto` 仍未带 `authType`（P3）——这一轮交付的是「同意与保管」，不是「使用」。

**四个值得写清的决定**：

- **PKCE 无条件 `S256`**，不在发现阶段快照 AS 的 `code_challenge_methods_supported`。这个「动手前先定」挂在 design §12.1 那张表里好几轮了，本轮定死：OAuth 2.1 与 MCP 都把 PKCE 划成强制项，元数据文档里没写这个字段不等于不支持；真不支持就在 AS 那侧报错，admin 不降级到 `plain`。降级若要做，得先给 `mcp_oauth_client` 加一列、再把「文档当时怎么说」带到换 token 那一步——两条都不值得为省一次失败预支。
- **`state` 存内存**，不建表。code 本身就是一次性、分钟级的东西，为它建表会把「重启即失效」这个正确语义伪装成「重启还能续上」。代价写在 §3.6 第 2 条：admin 重启打断正在同意的那一次，多副本必须粘性路由或换共享存储。当前部署是单实例，成立。
- **宁拒不断**：`scope` 宽过 512 直接拒（截断等于替用户记一份没人同意过的清单）、注册行 `client_id` 为空直接拒（带着 `client_id=` 撞 AS 只是让人家的错误页替我们说话）、回调期间服务 `url` 变了直接不换（code 是给旧地址换的，存下来是一把开不了门的钥匙）。三条都是「宁可让用户重做一次，也不存一个自己都不信的结果」。
- **读状态时不把花不掉的授权显示成可用**：`accessExpiresAt` 过期即不可用，因为刷新要到 P2-4——运行时此刻拿它什么也做不了，页面显示「已授权」是撒谎。

**顺带收敛的两处既有实现**：`normalizeIssuer` 从 `McpOAuthServiceImpl` 的私有方法提到 `RemoteJsonFetcher` 顶层，发现与授权共用同一条规范化（管理员多打一个斜杠不会变成「配了一行、按另一个 issuer 找不到」）；`RemoteJsonFetcher` 补 `postForm`，带 code 与 client secret 的 token POST 走与发现同一道护栏（协议白名单、地址底线、不跟重定向、读取截止、body 上限、报错抹 userinfo），而不是另起一个 `RestTemplate`。

**安全那一跳是两道门，不是一道**：`SecurityConfig` 给 `/api/admin/mcp/oauth/callback` 开 `permitAll()`，`JwtAuthenticationFilter.shouldNotFilter` 同一路径直接跳过。只加前一句不够——浏览器是被 AS 重定向回来的，它照旧带上标签页里那个可能已过期的 bearer，`JwtAuthenticationFilter` 在授权规则之前就会把请求拒成 401，用户看到的是「请求失败」而不是授权结果页。

**测试**：`harnax-admin` 单测 1690 → **1754**（+64：`McpOAuthUserServiceImplTest` 47、`McpOAuthStateStoreTest` 6、`McpOAuthCallbackControllerTest` 4、`McpOAuthControllerTest` 由 6 扩到 13）。`McpOAuthUserServiceImplTest` 出站全在 `RemoteJsonFetcher` 打桩，而 `McpOAuthStateStore` 用真件——要钉住的正是「发出去的 challenge 与换回来的 verifier 是同一个」「`state` 只够用一次」，把存储 mock 掉这两条就白测了。`harnax-entity` 232 不变（本轮没动 mapper）。全量回归 `TESTCONTAINERS_RYUK_DISABLED=true mvn -pl harnax-admin -am -o test` 退出码 0。用例清单同步：`docs/unit-test-cases.md` 新增 §5.9（MOU-01..47，按发起 / 回调换发 / 状态 / 撤销四组）、§5.10（MOS-01..06）、§5.11（MOCB-01..04），§5.8 由 6 条扩到 13 条并改题「五个 JSON 接口对外的答案」，§17 的基线数字按这次实跑做第七次校准。

**没做完与没验证的**：

- **没跑过真实 AS**。`mcp-authorization-design` §10 那行「用 Keycloak/测试 AS 走通发现 → 授权 → 存 → 撤销」仍未完成，各家实现对 RFC 8707 `resource` 参数的实际反应一次都没实测过——现在的实现是「按规范带、被拒就把上游原话回显」。
- **用户侧界面还没有**（P2-5）：这四个接口今天只能被 curl 或管理员手工调 API 碰到，前端全仓搜不到一处引用，普通用户没有任何地方点「授权」。
- **撤销不回收已发出的 access token 的运行时效力**：本地置 `REVOKED` 后 P2-4 不再换发，但运行时若已缓存该 token，回收要等 P3 的缓存层实现。这条现在不影响任何链路，因为运行时还读不到它。

### 7.12 已修复（2026-09 第十三轮：按同一套标准把 P2-3 再读一遍）

这一轮不新增功能，只做一件事：把 §3.6 那十条规则逐条对回代码，专找「单测绿着、生产不成立」的断点。修了五处，另有一处**只记录不修**——它要改的是安全模型，得先由人定方向。

**1. 上游拒绝换票的理由从来到不了页面（`RemoteJsonFetcher`）**
`exchange()` 里 `json` 只在状态是 2xx 时才解析，而 `McpOAuthUserServiceImpl.answerOrThrow` 读的恰恰是**非 2xx 分支**里的 `json.error_description`——这段代码落地那天起就是死的：RFC 6749 §5.2 把被拒原因写在 400 的体里，用户却只能看到光秃秃一句「HTTP 400」。`revokeUpstream` 那句 warn 日志里的 `response.json?.optString("error")` 同理恒为 `null`。改成「有体就解析，是 JSON 对象才留下」，`RemoteFetch.ok` 仍只由状态决定：发现侧三处消费（`McpOAuthServiceImpl` 的 173 / 213 / 267 行）都在读 `json` 之前先 `if (!fetch.ok)` 短路，把元数据文档当不存在处理，语义一点没变。
**为什么单测抓不到它**：`McpOAuthUserServiceImplTest` 的桩是直接造 `RemoteFetch(400, null, readTree(...))`，等于替被测代码把体解析好了——被测方永远不亏。所以补的是**出站层**的真 HTTP 用例（§5.7：RJF-03 改断言、新增 RJF-14 表单 POST 与 RJF-15 空体），并记下一条review 口径：**桩一旦替实现把活干了，这条断言就是在替被测方说话**。

**2. 换过 AES 密钥会把一条授权锁死在库里**
`revoke` 原来是裸调 `aesUtil.decrypt(...)`，而 `AesUtil.decrypt` 在密钥变了或密文被手工改过时会抛——用户点撤销得到一个 500，**而撤销是他唯一的出路**。现在解不开就当没有：本地照清（`REVOKED` + 两个密文写回 NULL + `last_error` 一起清），消息说清「是解不开，所以没送去上游」。

**3. 撤销的错误归因**
注册行被删（管理员重跑发现前先删了那行，或换了 issuer）时 `client == null`，原代码落到「这台 AS 不提供撤销端点（RFC 7009）」那句。两句话的差别是**下一步往哪走**：前者要的是重新发现与登记，后者是「等它自己过期」——把人指向一条改不动的路。现在单独一句，并把 `POST /api/admin/mcp/{id}/oauth/discover` 直接写进话里（与本特性其他拒绝语同源：把下一步点哪写进错误）。

**4. 授予清单宽过列宽时不再截断**
原来是 `granted.joinToString(",").take(512)` 落库：清单最后一条会变成半截 scope 名，而 `status` 接口把它当「这就是授予的 scope」原样回显。改成**整份不记**（`scopes=null`）并在 `last_error` 写清「有多长、列能存多少、所以没记」，`status` 仍是 `ACTIVE`——授权本身成立，只是这里记不下。与 §7.11 的「宁拒不断」不矛盾：那里拒的是**请求侧**，调用方能改窄参数；这里是**回答侧**，没人能改 AS 的决定，所以要么记全要么不记。

**5. 并发回调把一次真实发生的授权报成数据库失败**
同一用户对同一服务连点两次授权，两个回调同时走到 `insert`，`uk_mcp_user_credential_tenant_user_mcp` 只让一个赢，输的那个抛 `DuplicateKeyException` → 页面显示「授权失败」，而 AS 那边确实同意了，换回来的那份 token 还成了没人认领的孤儿。现在接住这个异常：重读一次拿到赢的那行，把这次的 token 走 `updateById` 写上去（SET 无条件、按 id 定位，正好够用）。**代价说清楚**：后写的那份覆盖先写的那份，前一个 token 本地不再可解，其效力要等上游自然过期或用户再点一次撤销；这与第 6 条规则里的「整体替换」是同一条取舍。

**F6：回调这条路的身份模型有一个已知缺口，本轮不修**（第十四轮已按下面的 (b) 闭合，见 §7.13）
`state` 钉住的是「谁发起了这次授权」，钉不住「谁在浏览器里点的同意」。攻击者给自己签一个 `authorize-url`（合法，走的是他自己的 JWT），把带 `state` 的完整链接发给受害者；受害者在 AS 那边同意，回调带着**受害者的 code** 回到本服务，而 `state` 里钉的是攻击者的 userId——于是攻击者账号下多了一条凭受害者身份铸出的 token。这是 §3.6 第 2、3 条那两道门槛的**固有性质**，不是实现漏了一步：回调没有 JWT，`state` 是它唯一的凭据，而 `state` 由发起方生成。
两条出路各有代价，需要先定方向再动手：**（a）** 发起 `authorize-url` 时在同一浏览器里下一次 `SameSite=Lax` + `HttpOnly` 的 Cookie，回调必须带上同一枚才能消费 `state`——把「发起者」与「点同意者的浏览器」绑在一起，代价是 AS 的跳转必须是同站顶级导航（新开标签页、`target=_blank`、跨站表单 POST 都会丢 Cookie，用户看到的是「请求未知」）；**（b）** 把换票从回调里搬出去，回调只落一个一次性跳转账号，前端（P2-5）带着 JWT 再调一次 JSON 接口换票——`state` 降级成纯重放防护，身份由 JWT 提供，代价是回调页要变成「跳回应用」而不是「告诉用户结果」，也意味着 P2-3 的这条链路要重做一半。本轮按「记录 + 不动模型」处理，因为 (b) 与 P2-5 的前端入口是同一件事，先做哪个会决定另一个的形状。

**测试**：`harnax-admin` 单测 **1754 → 1760**（+6：`RemoteJsonFetcherTest` 14 → 16，`McpOAuthUserServiceImplTest` 47 → 51——新增「授予清单宽过列宽整份不记」「撞唯一键转到先落库的那行」「密文解不开仍清本地并说清理由」「注册行没了的说法不顶包 RFC 7009」）。`harnax-entity` 232 不变（本轮没动 mapper）。全量回归 `TESTCONTAINERS_RYUK_DISABLED=true mvn -o -pl harnax-admin -am test` 退出码 0、1760 全绿；样式由 `spotless:check`（`validate` 阶段）把关。用例清单同步：`docs/unit-test-cases.md` §5.7（RJF-03 改断言 + RJF-14/15）、§5.9（MOU 由 47 重排为 51，四组变成发起 12 / 回调换发 22 / 状态 7 / 撤销 10）、§17 基线第八次校准。

**没做完与没验证的**：

- **还是没跑过真实 AS**（同 §7.11）。本轮第 1 条恰恰说明为什么这件事有代价：`error_description` 的实际形状、各家对 400 体是否真按 §5.2 写、`resource` 被拒时说什么，全都没实测过——现在只能说「理由能带回页面了」，不能说「带回的就是有用的一句话」。
- **F6 无法实测**：它要的是「两个真人、两个 AS 账号」的场景，比接一个 AS 更重；且它的答案取决于 (a)/(b) 选哪条。
- **第 5 条的并发只覆盖到单测形状**：`DuplicateKeyException` 是打桩抛的，真 MySQL 下的撞键（含 `insert` 与 `updateById` 之间的可见性）要到 P2-4 之后接 AS 实例时一并验。

### 7.13 已修复（2026-09 第十四轮：F6 按方案 (b) 闭合，顺带补上用户侧授权入口）

§7.12 那条 **F6** 是这份文档里挂了十三轮、每次都写「等方向定了再动」的唯一一个安全缺口。这一轮把它闭合了，走的就是当时记下的 (b)：**本服务不再接收授权服务器的浏览器重定向**，`redirect_uri` 改指前端一条路由，落地页带着浏览器里已有的 JWT 调一个新接口换票。身份因此来自 Spring Security 的认证上下文，而不是来自一个由发起方自己生成、又由攻击者原样带回来的字符串。顺带把 §9.1 里挂了很久、至今零前端调用的 `authorize-url` / `status` / `revoke` 接上了入口（P2-5）。

**1. 删掉的那个入口是整条链路上唯一不收 JWT 的**

`McpOAuthCallbackController`（`GET /api/admin/mcp/oauth/callback`）与它的 `McpOAuthCallbackResult` 一起删除，`SecurityConfig` 的 `permitAll()` 与 `JwtAuthenticationFilter.shouldNotFilter` 的同一路径跳过也删——admin 里还需要鉴权判断的 `permitAll` 业务路径从此只剩 `/api/admin/internal/**` 一条，那条由 `InternalApiAuthFilter` 的共享密钥实际把关（其余 `permitAll` 是登录 / 验证码 / `mp/auth` / swagger，本来就是公开的）。这不是清理冗余：那个路径上唯一能证明「你是谁」的东西就是 `state`，而 `state` 由发起方生成，所以 A 把 `authorize-url` 转给 B 去 AS 点同意，B 的同意铸出的 token 会写进 A 的凭据行。

**2. 新接口的路径里没有 `{id}`**

`POST /api/admin/mcp/oauth/exchange`，收 `McpOAuthExchangeRequest`（`code` / `state` / `error` / `error_description`，四个都可空——因为**是 AS 决定发哪几个**：同意通过带前两个，被拒带后两个，一个从没见过的 `state` 可能带着任何东西）。要授权哪台服务**只能**由 `state` 认出来的那条 pending 说；在这里放一个 id，等于给一个钓到的 `state` 再配一台由调用者挑的服务。它与 `/{id}/oauth/**` 那几条段数不同，不冲突。

**3. 判定的顺序是有意的**

取调用者身份 → `stateStore.consume(state)`（**无论后面走到哪一支，先烧掉**）→ AS 报了 `error` 就回那句话 → pending 不存在回「请求未知或已过期」→ `pending.userId != 调用者` 拒绝 → `code` 为空拒绝 → 换票。先烧后判是 §3.6 第 2 条那条不变量的延续：归属不符那一支要是把 pending 放回去，攻击者就能拿同一枚钓到的 `state` 反复试。归属不符只 warn 出 **mcpId 与两个 userId**，不记 `code`、不记 `verifier`——那些是要发去 token endpoint 的材料。

**4. 换票读服务行的方式反而变严了**

回调时代没有身份，只能拿 `state` 里钉的租户去 `selectById` 直读。现在这一路有身份，于是按 `pending.mcpId` 走 `McpServerService.getMcpServer`（调用者看不见这台服务就按「不存在」回答），读完再断言 `server.tenantId == pending.tenantId`（行被挪出原租户也不行）。`McpOAuthUserServiceImpl` 因此不再需要 `mcpServerMapper` 这个构造参数。

**5. 拒绝也是一句人话，不是一个 HTTP 状态**

`McpOAuthExchangeResponse` = `authorized` + `message` + `scopes` + `accessExpiresAt`，**没有一个字段装得下 token**。被 AS 拒、`state` 认不出、归属不符，全都回 `authorized=false` 加一句下一步该怎么办：`code` 已经花掉，页面重试不了，给 401/500 只会让人以为再点一次有用。`BizException` 的原话照说（那些句子本来就是写给人的），其余异常经 `ApiErrors.message` 收敛——数据库错误不会带着表名到浏览器。原来「上游 `error_description` 先 HTML 转义再上页面」的防护换成「走 JSON，由 React 当文本节点渲染」，与 `listTools` 带回来的上游字符串同一口径。

**6. 前端：落地页 + 详情页的按人授权块**

`/mcp/oauth/callback` 是一条 `layout: false` 的前端路由（`pages/mcp/oauth-callback.tsx`），第一句就读走 query 并 `history.replace` 抹掉它，值只停在 effect 里的一个局部对象上、立刻发出去。`src/app.tsx` 里那两处「没登录就跳 `/login?redirect=`」现在对这条路径特殊处理：把目标换成 `/context/mcp` 而不是原样带上 search——否则 `code` 与 `state` 会被写进登录页地址栏并一路跟着重定向。详情页的 `OAuthPanel` 补了按人授权块（挂载即读 `status`，三态徽标 + 去授权（同页跳转）+ 撤销（二次确认，回显后端那六句话之一）+ 已授予 scopes / 过期时间 / `lastError` 直显），并且当注册行里的 `callback_url` 与当前配置算出的缺省值不一致时显式告警——这个形状变了以后，AS 按字节比对失败是**必然**发生而不是偶发的。文案键由 52 个扩到 74 个（`pages.mcp.oauth.*`，中英同序）。

**7. 配置：回调的 origin 与 API 的 origin 分家**

新增 `app.frontend-base-url`（`APP_FRONTEND_BASE_URL`），只影响 `defaultCallbackUrl()`，未配置时退回 `app.base-url`。生产由 nginx 同域名代理两者，不配也对；开发下 SPA 在 :8000 而 admin 在 :8080，不配就会把用户送到一个没有页面的端口。**没有迁移脚本、没有兼容层、不留中转端点**：`mcp_oauth_client.callback_url` 是按 (租户, issuer) 存的登记值，老行要么在页面上重新保存一次客户端，要么由管理员把 AS 侧的 `redirect_uri` 改回旧形状——后者对本轮的新形状无效。挑这一轮改这个形状，是因为 V25/V26 从未应用到真实环境、也从没跑过真实 AS，此刻改是免费的。

**测试**：`harnax-admin` 单测 **1760 → 1768**（净 +8：`McpOAuthUserServiceImplTest` 51 → 58，回调那 22 条改写成换票 29 条；`McpOAuthControllerTest` 13 → 16；`McpOAuthServiceImplTest` 47 → 49（默认回调地址两条）；`McpOAuthCallbackControllerTest` 4 → 0）。全量回归 `TESTCONTAINERS_RYUK_DISABLED=true mvn -o -pl harnax-admin -am test` 退出码 0、1768 全绿；样式由 `spotless:check`（`validate` 阶段）把关。前端：`npx @biomejs/biome lint` 对改动的文件零 error 零 warning（本轮末尾顺手清掉两处残留：`src/app.tsx` 里 `Space`/`Footer`/`Typography` 三个从未被用到的具名导入，`OAuthPanel` 里那处只为绕过「闭包内属性不收窄」的非空断言，改成一个把值取进局部变量、为空即返回 null 的 IIFE）。`tsc` 用 `src/.umi/tsconfig.json` 这个真实工程口径跑，`pages/mcp/**`、`services/ant-design-pro/mcp.ts`、`typings.d.ts`、`config/routes.ts`、两份 `pages.ts` 全部无错；**`src/app.tsx` 另有两处既有类型报错**（`export const layout: RunTimeLayoutConfig` 的入参推断、`waterMarkProps.content` 读 `currentUser?.name` 而 `CurrentUser` 上没有 `name` 字段），两处都在本轮没碰过的代码上，本轮没修也没有假装修。用例清单同步：`docs/unit-test-cases.md` §5.8、§5.9（MOU 由 51 重排为 58，第二组由「回调」改名为「换票」）、§5.11 删除、§17 基线第九次校准。**一处偏离计划**：原打算断言「归属不符那条 warn 日志里没有 code 与 verifier」，但仓库的测试里没有任何日志捕获装置（零处 `ListAppender` / logback 用例），为一条断言引入一套装置不值；改成断言**响应体与响应 DTO 里没有令牌材料**，日志那侧靠第 3 条写下的口径约束。

**没做完与没验证的**：

- **真实 AS 仍然一次都没跑过**（同 §7.11、§7.12）。而且这一轮把这件事的权重提高了：新 `redirect_uri` 指向的是前端路由，从未被任何 AS 实测过，而且**要管理员手工把新形状输进 AS 的登记**——AS 侧不改，第一次授权就会在 `redirect_uri` 精确匹配上失败，而失败发生在人家那侧的页面上。
- **换票全链路只到单测**：能实测的是 `authorize-url` 的生成形状，以及落地页对 `?error=&state=` 的渲染（直接敲一个假 query 就能看到「上游拒绝」那句话与 query 被抹掉）。真人授权、撞键并发、解不开的密文这三类仍未验证，与前面几轮同一条。
- **`code` 会短暂出现在浏览器地址栏与历史里**：落地页第一句 `history.replace` 只是缓解，本仓库无法强制 AS 或浏览器不留痕（旧的服务端回调同样经过地址栏，这一条不比之前差）。
- **内存态 `state` 的多副本前提没变**，只是窗口从「回调落到没见过这次 `authorize-url` 的副本」变成「换票落到那个副本」；单副本部署下成立，多副本仍要粘性路由或共享存储。
- **F6 关掉的是「把别人的同意记到自己名下」**，它不关掉「攻击者完成他自己的授权」——那本来就不是这条链路的威胁模型，凭据归属由 JWT 决定之后，一个登录用户对自己租户里看得见的服务发起授权是正常能力。
- **列表页的授权徽标本轮不做**：§9.1 原来承诺的那个「列表页可见」现在改成「详情页显示，列表页随 P3」，理由写进 mcp-authorization-design §9.1（`NEEDS_CONSENT` 在 P2-4 之前没有任何代码会写，为一个显示不出第三种状态的徽标加批量接口是本末倒置）。`scope` 覆盖参数后端支持、UI 不做入口，同样是明确的非目标。

### 7.14 已修复（2026-09 第十五轮：F6 闭合之后再走一遍完整链路）

第十四轮把身份来源换掉以后，链路上的判定次序也跟着换了，而次序是有语义的。这一轮按「发起 → 同意 → 换票 → 落库 → 读状态 → 撤销 → 改配置」的顺序把代码重读一遍，找到五个真问题（三个在后端、一个在前端、一个是文档口径），全部修掉并各配了用例。它们的共同点是：**每一处都不是「功能没实现」，而是「实现了但在某个边界上说了假话或留了一条别人能走的路」**。

**1. 归属比对必须排在「AS 报了什么」之前**

第十四轮的 `exchange` 次序是：取身份 → 烧 `state` → AS 报了 `error` 就回那句话 → pending 不存在 → 归属不符拒绝。中间那条 `error` 分支于是插在了归属比对前面：**知道别人 `state` 的人只要 POST 一个自造的 `error=access_denied`，就能替对方取消那次在途的授权**，而那条本该记下钓同意行为的 warn 一个字都不会说（请求根本没走到归属比对）。修法是把归属比对提到读请求体任何字段之前——先判「这条 pending 是不是你的」，不是你的就直接拒并 warn，你的才继续看 AS 说了什么。顺带修掉一个同源的次序问题：`error` 分支现在**即使 pending 已经过期也照样回上游的理由**，因为那种情形下什么都没存、也没什么可烧，AS 的原话比一句「请求未知或已过期」对页面更有用（过期与「别人发起的」是两件事，不该被同一句话盖掉）。

**2. 换票回答里的 scopes 报的是记下的那份，不是 AS 回答里的原始字段**

`redeem` 原来把 token 响应里解析出来的 scope 清单直接回给落地页。RFC 6749 §5.1 让 `scope` 成为**可选**字段：AS 授的就是请求的那些时完全可以不回，于是落地页显示零条 scope，几秒后用户回到详情页，`status` 读同一列却显示出两条——同一个人在两处看到互相矛盾的答案。改成回**落库那一列**解析出来的清单；那条「宽过 512 就整份不记」的规则因此也自洽了（记不下就是空清单，页面看到的与库里一致，而不是页面看到一长串、库里什么都没有）。

**3. 读不到状态时不画徽标**

详情页的按人授权块原来在 `status` 请求失败时留着上一次的徽标（或初始的「未授权」）。「还没读到」与「读到了、确实没有凭据行」是两件事：前者什么都不知道，徽标不能替它断言未授权；撤销成功后重读失败更严重——库里已经变了，留着旧徽标就是说谎。加了一个 `grantUnknown` 标志，两条失败路径都置真、徽标渲染为 `null`，三态只属于真读到的答案。（后端「没有行」序列化出来是 `{"authorized": false}`，是个真值对象，所以那一支仍正常显示「未授权」，不会被误当成未知。）

**4. 离开 `OAUTH2` 时把逐人凭据一起清掉**

`updateMcpServer` 原来只清 `oauth_config`。问题是：逐人凭据的读与撤都要先过 `requireOAuthServer`（`authType != OAUTH2` 就拒），所以把一台服务的认证方式从 `OAUTH2` 改成 `NONE` 之后，**那些已经存在的 `mcp_user_credential` 行既读不出来也撤不掉**，成为一串没人管得着的密文。改成与删除路径同一口径：本地清行 + `warn` 出清了几条，同时说清上游那侧的副本并没有被呈递，它们靠自己过期。`wasOAuth` 在**任何字段被覆盖之前**读出来——覆盖 `auth_type` 之后就再也分不出这次请求是不是把 OAuth 关掉了。从来不是 OAuth 的服务一次都不查这张表——「V25 之前 `authType` 为 null 的老行」这种情形并不存在（那一列是 `NOT NULL DEFAULT 'NONE'`，实体字段也是非空 `String`），判据始终只有一个：原行的 `auth_type` 是不是 `OAUTH2`。

**5. 那 500 条的在途预算是所有人共用的**

`McpOAuthStateStore` 只有一个全局上限。一个带脚本的登录用户可以把它填满，让其他人在整个 TTL（5 分钟）里都发起不了授权——这是 §7.13 那条「多副本要粘性路由」之外唯一剩下的自伤式拒绝服务。加了每人 5 条的上限，`countFor(userId)` 在**生成 verifier 与 state 之前**先数一遍（先扫过期，被拒的那次什么材料都不留），拒绝时那句实话把上限与等待时间都说了。

**6. 一处文档口径错误（改的是文档，不是代码）**

§3.6 第 9 条原来写「日志只说有没有 refresh、expiresIn 多少」。实跑的 `log.info` 还打了记下的 scope 名。scope 名不是凭据——`status` 接口本来就把它们回给浏览器——所以代码没错，是文档说漏了一项，已按实情改写。这类「文档比代码严」的偏差比反过来更危险：读文档的人会以为有一条并不存在的保证。

**测试**：`harnax-admin` 单测 **1768 → 1775**（净 +7：`McpOAuthUserServiceImplTest` 58 → 62，`McpServerServiceImplTest` 40 → 42，`McpOAuthStateStoreTest` 6 → 7）。定向跑四个改动过的类 127 个用例全绿，全量回归 `TESTCONTAINERS_RYUK_DISABLED=true mvn -o -pl harnax-admin -am test` 退出码 0。用例清单同步：`docs/unit-test-cases.md` §5.3（MCS-20 扩写 + MCS-23/MCS-24）、§5.9（MOU-12/13/15 改写，新增 MOU-59～MOU-62——编号追加在末尾、表格放在各自分组里，所以既有编号与交叉引用一个都没动）、§5.10（MOS-07）、§17 基线第十次校准。

**没做完与没验证的**：

- 前五条**全部只到单测**。第 1 条的攻击形态（拿到别人 `state` 后 POST 自造 `error`）在真实浏览器里一次都没有复现过，也没有真实 AS；第 4 条的凭据清理只在打桩的 mapper 上验过，没在 Testcontainers 里跑过真行。
- 第 5 条的每人上限是**内存里的**，多副本下每副本各数各的，实际可用额度是 5 × 副本数；这与 §7.13 那条「多副本要粘性路由」是同一个前提，不是新问题。
- 第 4 条只清本地：上游 AS 那侧的授权副本没有被呈递撤销，它们靠自己过期。要真撤上游，得在改认证方式**之前**由用户逐条点撤销。
- 复审中确认的三处**不是缺陷**：时间戳按 ISO 原样显示是全站既有约定（`DetailPageHeader` / `EntityCard` 同样如此），仓库里没有 `StrictMode`（所以开发期 effect 双跑导致的换票重复提交不构成实际风险），`detail.tsx` 已按 `authType === 'OAUTH2'` 决定要不要挂这个面板。

### 7.15 已修复（2026-09 第十六轮：从配置页读到客户端关闭）

第十五轮读的是授权链路，这一轮把**运行侧**补齐：管理页 CRUD → 认证 → 向导绑定 → harness 装配 → 客户端生命周期。找到十条，四条在装配侧（都会造成实际损害），四条在管理面与向导面，两条在鉴权边界上。

**1. MCP 客户端从来没被关过（资源泄漏）**

`HarnessAgentLauncher` 每建一个 agent 就 `createMcpClient` 出新客户端，`DefaultAgentRunner` 按 sessionId 缓存在 Caffeine（`expireAfterWrite` 30 分钟 + 条数上限），淘汰时只是把条目从 map 里丢掉。原本以为 `HarnessAgent.close()` 会把它们带走——按 jar 反查字节码，它的 `close()` 只有 `shutdownTaskRepository` + `WorkspaceIndex.close` + `ReActAgent.close()`（解绑 state saver、清 state 缓存），**没有任何一处触及 `Toolkit` 里注册的 MCP 客户端**；`Toolkit` 自己也没有 close，只有 `registerMcpClient` / `removeMcpClient`。stdio 那条更实在：客户端不关，子进程就一直留着。
改法是把所有权拿回自己手里：launcher 边建边收集 `List<McpClientWrapper>`，随 agent 一起放进 `HarnessAgentWrapper`，新增 `release()`——先逐个 `close()`（每个单独 try，一个失败不连累其余），再 `harnessAgent.close()`，用 `AtomicBoolean` 保证幂等（缓存淘汰与会话销毁可能撞在一起）。`removalListener` 与 `destroyAgent` 都改为走 `release()`。
关闭与在途请求是另一件事：淘汰发生时流式对话可能还在跑，直接关会打断正在说话的人。`activeStreams` 里有该会话时把 wrapper 挂进 `pendingRelease`，等两处 `doFinally`（`streamProcess` / `confirm`）摘掉在途标记后再 drain。**非流式的 `process()` 没有在途标记**，仍可能被一次淘汰打断——本轮接受的残余风险，它不持有 SSE 那段时间，窗口小得多。
同一条根因的另一半是 `McpHelper.listTools`：每点一次「测试连接」建一个客户端却从不关闭，现在整段包在 try/finally 里。

**2. 一台 MCP 建不起来，整个 agent 就没了（故障放大）**

`createMcpClient` 的异常原来一路冒到 `buildAgent`，于是「绑了三台、其中一台地址写错」等于「这个会话完全不能用」，页面只有一句 agent init failed。现在这一台 `log.warn` 后跳过，其余照常装配；**已经建出来的半成品先 close**（`buildAsync` 超时返回时底层连接可能已经起来）。同处补了 `buildAsync().block(60s)` 和「构建返回 `null` 就抛 `MCP_CLIENT_CREATE_FAILED`」——原来那个 null 会在下一行 `addMcp(...)` 变成 NPE；该错误码的消息模板没有占位符，所以服务名改由 `log.error` 带出，不在异常文案里硬塞。

**3. 缓存的并发装载会把 agent 交给别人**

`agentCache.get(sessionId) { ... }` 里 Caffeine 对同一 key 只跑一个 loader，另一个线程**等待并直接拿结果**，而 loader 是用调用者自己的身份解析 MCP 配置与用户级 env 的。两个人同时首次访问同一 sessionId（共享会话，或同一用户双标签页 + 一次权限切换）时，输家拿到的是赢家身份建出来的 agent，里面装着赢家的解密凭据。现在取回后复核 `entry.userId` 与当前用户一致，不一致就 `asMap().remove(key, entry)` 条件删除重建（条件删除是为了不误删第三方刚放入的条目），两轮仍不一致直接拒绝而不是继续复用。`CachedAgent` 因此多带一个 `userId`。

**4. 删掉「查库兜底」这条路径**

`McpConfigAdaptorImpl` 原先在上下文没命中时用 `McpServerMapper.selectById` 直查 `mcp_server`。这一行现在**必然**给错东西：AES 密钥只留在 admin（§7.1 的「下发前解密」方案），库里 `headers` / `env_params` 是密文，兜底读出来会原样当真实请求头交给客户端——连不上且看不出原因。它还顺手绕过了 admin 出口做的租户剔除。构造参数 `mcpServerMapper` 一并删除，缺失只剩一条 warn。工具 / 技能 / 模型三处 adaptor 的查库兜底**没动**：它们读的列不加密。

**5. 分页参数名对不上**

`mcp.ts` 发 `current` / `size`，`McpServerController.pageMcpServer` 收的是 `pageNum` / `pageSize`。Spring 用缺省值补齐，于是 MCP 列表页翻到第 N 页永远是第 1 页，`size` 也改不动。属于「只朝安静方向出错」的那类：页面看不出异常。

**6. 向导不再预填掩码**

绑定 MCP 时环境变量列表的默认值取自服务配置，secret 项后端只给掩码（`****`）。预填等于把掩码当成值存进 `agent_mcp_binding`，运行时解出来是一串星号。现在敏感项一律留空。

这条数据流有**两个客户端**：第一次只改了 webui 的 `McpConfigPanel`，而 `harnax-wechat-app/miniprogram/pages/agent/form/index.ts` 的 `buildEnvBindings` 走的是同一个 `McpServerResponse.fromEntity`（`maskSecret = true`），漏在外面——小程序提交时掩码串经 `toEnvBindingPayload` 变成 `customValue`，`AgentServiceImpl.serializeEnvBindings` 对绑定路径**没有**任何掩码判断（掩码承接只在服务配置那条路径上），于是原样落库、原样随 spec 下发进 `ToolEnvContext`，工具拿到一串被截断的假密钥，而输入框里看着像填好了。同一条 `e.secret ? '' : e.defaultValue || ''` 补到小程序。

**留空的代价要说清楚**：`required` 只是个红色标签——webui 与小程序都没有提交校验，后端 `saveMcpBindings` 也不查必填，所以留空能存进去，运行时该参数拿到空串。本节初稿写的「必填项由提交校验挡下」不成立，已订正；真正的强制点要么在前端补校验，要么在 `saveMcpBindings` 里查 `envParams` 定义，两处都还没做。

**7. 换类型不再留对侧残留，向导不再重复绑定**

`streamablehttp → stdio` 之后行里还带着旧 `url`，反向还带着 `command` 与 `env_params`。`updateById` 无条件写全列，所以「清空即真清」在后端是成立的，只是过去没人清。现在服务端按新类型清对侧字段（`url` / `command` 实体非空用 `""`，`headers` / `env_params` 可空用 `null`），前端切类型时同步清，免得「页面看着清了、提交又带回去」。

重复绑定同样两个入口：webui 的下拉过滤掉本次已绑过的服务（`McpConfigPanel` 里那句 `!mcpConfigs.some(...)`）；小程序每张卡共用一个 `mcpRange`、`pickerIndex` 是它的下标，按下标过滤会让已选卡片错位，所以改成**选中时拒绝**并 toast 提示，判定规则与 webui 那句一致（排除自己那张卡）。落库侧本来有 `distinctBy { it.mcpId }` 兜着，唯一键 `uk_agent_mcp_binding_agent_id_mcp_id` 撞不到——但兜底的后果是「加两张一样的卡、存完回来少一张且没有提示」，所以界面必须先挡。

**8. 改 url 即换资源，旧凭据一并清掉**

RFC 8707 的 resource indicator 让令牌与资源地址绑定，改了 `url` 之后库里那些按旧地址换到的 `mcp_user_credential` **不可能**再被接受，而 `status` 还在回答「已授权」。现在 `wasOAuth && url 有变化` 时 `deleteByMcpId` 并 warn 出条数，与第十四轮「离开 `OAUTH2` 就清」同一口径（判据同样在任何字段被覆盖之前算，`url` 原样重提不算换地址）。编辑页 url 字段加了一行提示，让管理员改地址前知道代价。

**9-10. 链路上顺手收紧的两处鉴权（不在 MCP 的文件里）**

- `InternalTokenProvider` 签发的 token 加 `typ=internal`，`verifyToken()` 只把带这个 claim 的判为 `INTERNAL_SERVICE`。此前任何用 `harnax.auth.internal.shared-secret` 验签通过的 Bearer 都算内部服务——而部署文档要求 `jwt.secret` 在 admin 与 agent-service 之间同值，一旦运维把它也填进内部密钥，浏览器里的登录 JWT 就成了内部服务凭证，直达 `@InternalOnly`。现在无 `typ` 但带 `userId` 的记 `EXTERNAL_API`（认证通过、进不了内部端点），两者都没有的直接拒。router monitor 页与 webui 的 `X-Api-Key` 回退路径不受影响（它们不需要 `@InternalOnly`）。详见 `harnax-auth/AUTH-DESIGN.md` §3.1。
- `AgentProxyController.resolveUserId` 补审计：认证身份与 body 声明不一致打 WARN（记调用方、声明值、真实值），无认证身份而采纳 body 时打 INFO。规则本身没变，只是让「谁在替谁说话」事后查得到。详见 `harnax-session-router/README.md` 对话代理一节。

**测试与未验证（本轮与前面几轮不同，请注意）**：

- **本轮没有执行编译，也没有跑任何单元测试**（本机资源限制，按要求）。因此 `docs/unit-test-cases.md` §17 那个 **1775** 的基线数字**未重新校准**，仍是第十五轮实跑的结果。本轮动过用例的三个套件里只有 `McpServerServiceImplTest` 在 `harnax-admin` 内（+2：第 8 条的换地址、以及「`url` 原样重提不算换地址」）；另两个不在其中——`McpConfigAdaptorImplTest`（agent-service，7 → 6，删掉两条「查库兜底」用例，因为那条路径已不存在）、`InternalTokenProviderTest`（harnax-auth，+3：`typ` 断言、用户 JWT 判外部、无身份 bearer 被拒）。按那份文档自己的纪律（基线数字要么实跑重数，要么别写），本轮不写新数字，只把增量记在这儿等下次校准。
- 第 1～3 条都是并发 / 生命周期类缺陷，单测覆盖不到真实的线程交错，也没有把 harness 起起来跑一轮。stdio 子进程是否真被回收、双线程首次访问是否真不再串身份，仍要人工或集成验证。
- 第 2 条的「跳过后其余照常装配」同理只到读码：`HarnessAgentLauncher` 本来就没有单测，它依赖完整构建链路。
- 第 8 条的两条用例是打桩 mapper 上的行为，没在 Testcontainers 里跑真行。
- 第 6、7 条后来补的小程序两处（`pages/agent/form/index.ts` 的 `buildEnvBindings` 与 `onMcpPick`）**没有任何可跑的验证**：`harnax-wechat-app` 只有 `npm run tsc`（`tsc --noEmit`），没有单测框架，本轮连这条也没执行。改动只到读码，两条判定都是照 webui 已有的写法平移。
- 第 6 条订正时暴露出一个仍未处理的缺口：`required` 环境参数在两个前端与 `saveMcpBindings` 都不校验，留空可入库、运行时拿到空串。要不要补、补在哪一层，尚未定。

### 7.16 已修复（2026-09 第十七轮：把绑定这条线读完，含上一轮留下的两条尾巴）

第十六轮文末留了两条：`required` 环境参数没有任何强制点、非流式 `process()` 没有在途标记。那一轮读的是 MCP，工具侧同三处只读了一半。这一轮补齐绑定这条线，共五条。

**1. 工具向导同样在预填掩码**

第十六轮第 6 条把 MCP 侧的 `e.secret ? '' : e.defaultValue || ''` 补上了，工具侧的 `ToolConfigPanel` 漏着同一句。工具的 `default_value` 在读路径（`AgentToolServiceImpl`）上对 secret 项给的也是掩码，预填进绑定框等于把 `abc****wxyz` 这串字面量存进 `agent_tool_binding.env_bindings`，再随 spec 下发进 `ToolEnvContext`——输入框里看着像填好了，工具拿到的是被截断的假密钥。现在敏感项一律留空，要覆盖就选一个全局变量或者自己填。

**2. 工具重复绑定两侧都不挡**

MCP 那条在第十六轮补过，工具这条没有：webui 的下拉 `options={groupedToolOptions}` 没滤掉本次已绑过的工具，`onToolPick` 也不拒。落库侧有 `distinctBy { it.toolId }` 兜着，所以后果不是报错而是「加两张一样的卡、第二张填的环境变量静默丢掉」。webui 改成按行过滤（`toolOptionsFor(index)`：只挡别的行，本行自己已选的那个必须留着，否则标题会渲染成裸 id，这也是不能整体换成一个共享 memo 的原因）；小程序沿用 MCP 那套「选中时拒绝 + toast」，因为它每张卡共用一个 range、按下标过滤会让已选卡片错位。

**3. 引用型绑定只存指针：掩码不落快照 + 保存时校验 + 删除时挡住**

一条数据流上的三个症状：客户端把 `displayValue`（敏感项即 `******`）当 `envValue` 提交 → `serializeEnvBindings` 原样写进快照 → 下发时 `resolveEnvBindingsJson` 只在变量还在的时候救得回来 → 而 `deleteEnvVariable` 删之前不查有没有人还在用。

「把快照换成解密值」最省事，但那会把明文密钥写进 `env_bindings` 列：AES 密钥只在 admin（§7.1 的下发前解密方案），这一列一旦写明文，等于把第十六轮第 4 条刚删掉的「拿着库里的密文当明文用」那条路换个方向重新铺一遍。所以落地的是三件事的组合：

- **快照不存值**：`serializeEnvBindings` 对引用只写 `envVarId` / `envVarName`。读取路径（`parseEnvBindingsJson`）与下发路径本来就按 id 现取，界面上看到的还是当前值。
- **保存时校验引用可用**：新增 `assertEnvVarRefsBindable`，写法比照 `resolveBindableMcpServers`——查不到、已软删、不属于本租户，一律 `BizException` 并列出 id。同一轮把工具侧也补了 `resolveBindableTools`（原先只有 MCP 有这道），理由与 MCP 那条一样：绑一个查不到的 id，在下发侧是一句日志、在页面上是一个还在的工具；跨租户 id 猜出来就更不只是「少一个工具」。
- **删除时挡住**：`AgentMapper.selectByEnvVarRef` 一次查三张绑定表快照里的 `$[*].envVarId`（`env_bindings` 是 TEXT，所以 `CASE WHEN JSON_VALID` 挡在 `JSON_EXTRACT` 前面，历史脏行不会让整个语句失败），命中就报「被 N 个 agent 绑着：名字…，先改绑再删」。这句必须走 `ApiErrors.message` 才透得出 controller，否则 catch 会把它压成一句 "Failed to delete env variable"——守卫白做。
- 下发侧补一条 warn：引用彻底解不开时说明「这个参数什么都不发」，不再是静默。

**4. `required` 环境参数第一次有了强制点**

三个入口，同一条规则：有 `envVarId` 引用就算填了（运行时按 id 现取），否则要有一个非空且不含 `****` 的自填值——掩码是显示产物，不是值。前端两处（`validateConfigStep` / 小程序 `validateRequiredEnvParams`）报参数名，服务端 `assertRequiredEnvParamsFilled` 是真正拦得住的那道。

**默认值算不算数，两处不一样**，判据是「这个默认值在运行时到底去不去得了」：MCP 的 `mcp_server.env_params` 会整份解密成 stdio 进程的环境变量，默认值进得去 → 算；工具的 `agent_tool_env_param.default_value` 进不去 → 不算。后者本轮查实：`ToolConfigAdaptorImpl` 把 `envParams` 装进了运行时的 `AgentTool` 实体，但**全仓没有任何一处读它**，`ToolEnvContext` 里只有绑定值。参数定义也不能从 `AgentToolService` 拿——那份是给人看的展示视图，secret 项的默认值是掩码，分不清「没有默认值」和「默认值被遮住了」，所以服务端走 mapper 直读表。

**5. 非流式 `process()` 登记在途运行**

先确认入口活着：channel → session-router → `/api/agent/chat` 走的正是 `process()`，不是流式那条，所以第十六轮「窗口小得多」的残余风险不是纸面上的。补 `activeCalls` 集合，`releaseAgent` 的非空判断一并看它，调用结束在 `finally` 里摘标记并 drain。登记点放在 `getOrCreateAgent` **之后**、只包住 `agent.call`：包住整个方法会与本方法自己的缓存装载递归撞在一起。`interrupt()` 不动——阻塞调用没有 subscription 可取消，那条路一直是走 `agent.interrupt()`。

**测试与验证（本轮实跑了，与第十六轮不同）**：

- `harnax-admin`：`AgentServiceImplTest` 新增 9 条「环境参数绑定守卫」（工具 id 查不到 / 跨工具租户 / 引用解不开 / 快照不落值 / 必填缺值 / 工具默认值不顶必填 / 引用可存 / 掩码自填值不算已填 / MCP 默认值算已填），`EnvVariableServiceImplTest` 新增 1 条删除引用守卫；第 3 条改了删除接口对外的语义，`EnvVariableControllerTest` 里那条「`RuntimeException("DB error")` → 压成一句 Failed to delete」的旧断言已经不成立，换成两条各守一半（守卫文案原样透出、数据库异常走 `ApiErrors` 兜底），与 `McpOAuthControllerTest` 同一写法。全量 `-Dtest='**/admin/**/*Test'` **1791 条全绿**。
- `harnax-agent-service`：`DefaultAgentRunnerTest` 新增 1 条「`process` 期间被淘汰时延迟释放」（在 `agent.call` 里阻塞、发 REFRESH、`after(300).never()` 断言没提前释放、放行后 `timeout` 等它真释放），该套件 **52 条全绿**。
- **顺带修掉一处第十六轮留下的编译错误**：removalListener 把 Caffeine 声明为 `@Nullable` 的 key 直接传给了 `releaseAgent(sessionId: String, ...)`。上一轮文末写了「本轮没有执行编译」，这一行因此从没被编译器看过。本轮 `mvn -DskipTests test-compile` 过了除 `harnax-channel-service` 之外的全部模块（它卡在离线仓库缺 `commons-io:2.20.0`，与代码无关）。基线 1775 仍不重新计数，只记增量（admin +11、agent-service +1）。
- webui `npm run tsc`：仓库既有 30 条报错全在 `services/**` 与 `typings` 的 umi 临时类型上（`@@/plugin-request` 未生成），**没有一条落在本轮改的 `pages/agent/**`**。这只说明没新增，不等于通过。
- 小程序仍**没有任何可跑的验证**：`harnax-wechat-app` 没有 node_modules，`tsc --noEmit` 跑不起来；第 2、4 条在小程序侧的改动只到读码，是照 webui 已有写法平移。
- `selectByEnvVarRef` 那条 JSON SQL 没有跑真库，只按 MySQL 语义读码核对。
- **仍未处理**：历史快照里已存进去的掩码没有清洗。新写入不再有；旧的在变量还在时按 id 现取（掩码不露出），只有变量被删后才可能兜出那串星号，而删除现在被第 3 条的引用守卫挡着。真要做是一次 `UPDATE ... env_bindings = JSON_REMOVE(...)` 的数据迁移，本轮判断收益不足以引入一次全表 JSON 改写。

## 8. 关键文件索引

| 模块 | 文件 |
|------|------|
| 实体 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`、`AgentMcpBinding.kt`、`dto/McpDetailDto.kt`；OAuth 侧 `McpAuthTypes.kt`（认证方式常量与 `SUPPORTED`）、`McpOauthClient.kt`、`McpUserCredential.kt`、`McpCallLog.kt` |
| Mapper | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpServerMapper.kt`（XML：`harnax-entity/src/main/resources/mapper/McpServerMapper.xml`，含 `selectByIds` 批量查询、`selectMcpServerList` 与 `selectByName` 的可选 `tenantId` 过滤，以及 `updateOAuthConfig`——发现只写回一列，因为 `updateById` 的 SET 是无条件的）、`AgentMcpBindingMapper.kt`（XML：`mapper/AgentMcpBindingMapper.xml`，含 `deleteByMcpId` 级联清理）；OAuth 三对：`McpOauthClientMapper.kt`（`selectByTenantAndIssuer` 带 `ORDER BY id LIMIT 1`，保证并发发现复用同一行）、`McpUserCredentialMapper.kt`（`selectByUserAndMcp` / `deleteByMcpId` 等四个方法）、`McpCallLogMapper.kt`（**只有 `insert`**，append-only 由「没有别的方法」保证），XML 均在 `harnax-entity/src/main/resources/mapper/` 下同名 |
| 迁移 | `harnax-admin/src/main/resources/db/migration/V7__normalize_agent_bindings.sql`（建绑定表）、`V19__add_mcp_binding_unique_key.sql`（`(agent_id, mcp_id)` 唯一键）、`V20__drop_mcp_binding_enable_skip.sql`（删 `enable_skip`）、`V21__drop_stale_capability_list_columns.sql`（删 `agent` / `session` 上的能力残留列）、`V22__mcp_public_default_and_tenant_backfill.sql`（`is_public` 缺省改 1 + 回填 `tenant_id`）、`V23__add_mcp_server_name_unique_key.sql`（租户内名称唯一键，先给历史重名行改名）、`V24__backfill_agent_session_tenant.sql`（回填 `agent` / `session` 的租户归属，§7.4）、`V25__add_mcp_oauth_columns.sql`（`mcp_server.auth_type` / `oauth_config`，有意不回填）、`V26__add_mcp_oauth_tables.sql`（三张 OAuth 表，§7.5） |
| 管理 API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt`；OAuth 侧只有 `admin/controller/McpOAuthController.kt` 一个类、六个接口：管理面两个（`POST /{id}/oauth/discover`、`POST /{id}/oauth/client`）、按人四个（`GET /{id}/oauth/authorize-url`、`POST /oauth/exchange`、`GET /{id}/oauth/status`、`POST /{id}/oauth/revoke`），**全部要过 JWT**——第十四轮删掉的 `McpOAuthCallbackController` 曾是这里唯一免鉴权的一跳 |
| 管理服务 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImpl.kt`（认证方式那三道校验在 `resolveAuthType` / `validateAuthType` / `writeOAuthConfig`，OAuth 配置的对外形态是 `admin/dto/McpOAuthConfig.kt`；更新时「请求没带 `authorizationServer` 就保住库里那个」的接管在 `updateMcpServer`）；`admin/service/impl/McpOAuthServiceImpl.kt`（发现顺序在 `resolveIssuer`，元数据候选在 `fetchMetadata` / `wellKnownUrls`，端点落库与客户端复用在 `storeEndpoints`；所有对外地址与文档字段都过 `validateHttpUrl` / `validateIssuerUrl` / `normalizeIssuer`——最后这个已从本类的私有方法提到 `RemoteJsonFetcher` 顶层，发现与授权共用同一条规范化，拼进报错的候选失败原因由 `detail` 截断）；`admin/service/McpOAuthUserService.kt` + `admin/service/impl/McpOAuthUserServiceImpl.kt`（P2-3：`authorizeUrl` / `exchange` / `status` / `revoke`；`exchange` 里先烧 `state` 再比归属（第 3 条），换 token 前的六道复核集中在私有 `redeem`——行是否还在发起时的租户、是否还是 `OAUTH2`、`url` 是否还等于 `state` 钉住的 `resource`、注册行是否还在、`client_id` 是否为空、token endpoint 是否已知；scope 宽过列宽那道在 `authorizeUrl`，空 `client_id` 在发起侧另由 `requireClientRegistration` 挡一次）；待授权状态 `admin/util/McpOAuthStateStore.kt`（`PendingAuthorization` 与一次性 `consume` 同文件） |
| 出站 HTTP | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/RemoteJsonFetcher.kt`（管理员输入或上游文档给出的地址只能从这里出去：协议白名单、元数据地址底线、不跟重定向、body 上限与读取截止都在这里，`redactUrl` 也在；JSON 取值 helper `optString` / `optStringList` 同样在这，顶层 `normalizeIssuer` 是发现与授权共用的那一条规范化，P2-3 加的 `postForm` 让带 code 与 client secret 的 token POST 走同一道护栏而不是另起一个 `RestTemplate`，`fetch` / `postForm` 抛的 `RemoteFetchException` 消息会被拼进发现与换发的报错。admin 别处的出站——`McpServerServiceImpl.listTools`、channel、registry——目标是自己配的，不归它管） |
| 加密器 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`（`serializeWithEncryption` / `serializeToolEnvParams` 的 `storedJson` 参数即掩码沿用密文之处，单列密钥用 `resolveSecret`；掩码承接这一条判断的定义只有一份，在 `resolveEnvParamValue`——JSON 列把行里的旧 JSON 作为 `storedJson` 交进来，`agent_tool_env_param` 行表由 `AgentToolServiceImpl.storedEnvSecrets(toolId)` 在删除前读出一张密文表再交进来）；撞唯一键时对外的文案在 `admin/util/ApiErrors.kt` 的索引名映射表里 |
| 解密 SPI | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt`；兜底实现 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt` |
| 绑定保存 / 下发 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`、`controller/InternalApiController.kt` |
| Spec 解析 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` |
| 配置适配器 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt` |
| 客户端构建 | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`、`McpConfig.kt` |
| 运行时装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`、`spring/HarnessAutoConfiguration.kt` |
| 前端（管理面与用户侧） | 类型 `harnax-webui/src/typings.d.ts`（`McpServerItem` / 创建更新请求 / `McpOAuthConfig` / `McpOAuthClientRequest` / `McpOAuthDiscoveryResponse`，与后端 DTO 同名；第十四轮再加 `McpOAuthAuthorizeResponse` / `McpOAuthExchangeRequest` / `McpOAuthExchangeResponse` / `McpOAuthStatusResponse` / `McpOAuthRevokeResponse`）；服务 `harnax-webui/src/services/ant-design-pro/mcp.ts`（`discoverMcpOAuth`、`saveMcpOAuthClient`，加 `getMcpOAuthAuthorizeUrl`、`exchangeMcpOAuthCode`、`getMcpOAuthStatus`、`revokeMcpOAuth`）；页面 `harnax-webui/src/pages/mcp/index.tsx`（卡片列表，OAuth 标签）、`detail.tsx`（详情头部「认证方式」+ 面板挂载点）、`oauth-callback.tsx`（授权落地页：接 AS 的 query、换票、只回一句话，第十四轮加）；路由 `harnax-webui/config/routes.ts` 的 `/mcp/oauth/callback`（`layout: false`，排在 `path: '*'` 那条 404 之前）；`harnax-webui/src/app.tsx` 的 `loginRedirect`（未登录跳 `/login?redirect=` 时，对落地页丢掉 search，别让 `code` 与 `state` 进登录页地址栏）；表单与面板 `pages/mcp/components/CreateForm.tsx`、`UpdateForm.tsx`（认证方式下拉、stdio 联动）、`OAuthFields.tsx`（四个配置字段）、`OAuthPanel.tsx`（发现、登记客户端、按人授权块，§7.8 的四条 UI 侧取舍与 §7.13 第 6 条都在这几个文件里）；文案 `harnax-webui/src/locales/{zh-CN,en-US}/pages.ts` 的 `pages.mcp.oauth.*`（74 键，中英同序） |
| 测试 | 管理侧 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImplTest.kt`（含 `AuthTypeTests` 12 个）、`McpOAuthServiceImplTest.kt`（49 个：issuer 解析 14 / 元数据 11 / 发现落库 11 / 客户端凭据 11 / 回调地址来源 2）、`util/RemoteJsonFetcherTest.kt`（16 个，真起 JDK `HttpServer`）、`util/SecretFieldEncryptorTest.kt`（含 `ResolveSecretTests`）、`controller/McpServerControllerTest.kt`、`controller/McpOAuthControllerTest.kt`（16 个，守 OAuth 接口对外的错误语义：管理面 6 个、按人四个接口 10 个）、`service/impl/McpOAuthUserServiceImplTest.kt`（62 个：发起 13 / 换票 32 / 状态 7 / 撤销 10，出站打桩、`McpOAuthStateStore` 用真件）、`util/McpOAuthStateStoreTest.kt`（7 个）、`controller/InternalApiControllerTest.kt`；SQL 侧 `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/{McpServer,McpOauthClient,McpUserCredential,McpCallLog}MapperTest.kt`（`McpServerMapperTest` 含 `updateOAuthConfig` 只动一列、已删行不动，需 Docker + `TESTCONTAINERS_RYUK_DISABLED=true`）；不依赖 Docker 的 `harnax-entity/src/test/kotlin/com/agnetix/harnax/MapperXmlParseTest.kt`；迁移可执行性由 `harnax-admin` 的 `HealthInfoIT` 覆盖；测试 schema 只剩 `harnax-entity/src/test/resources/schema-test.sql` 一份 |

# harnax 后端单元测试用例目录

> 范围:harnax 后端各模块核心业务逻辑的单元测试(UT)用例目录,覆盖 Service / Controller / Util / Loader 层的分支逻辑,mock 掉 Mapper 与外部依赖。每个域的逐条清单不追求与套件的用例数一一对应,全量计数以 §17 的实跑为准。
> 与集成测试(`harnax-admin/src/test/kotlin/.../admin/it/`,`-Pintegration-test` 运行)互补:集成测试覆盖 HTTP 端到端链路与真库,本文档聚焦不碰容器的这一层。
> 框架:JUnit 5 + mockito-kotlin(`@Mock` / `@InjectMocks`、`whenever`、`argumentCaptor<T>()`、`anyOrNull()`)与 JUnit 的 `assertEquals` / `assertThrows` / `assertNull` 断言。

## 测试分级说明

| 级别 | 说明 | 典型类 |
|------|------|--------|
| L1 纯逻辑 | 直接 new,无需 mock | AesUtil、CaptchaServiceImpl、JwtUtil(反射注入配置)、maskValue、generateRawKey/sha256、SkillLoaderRegistry、ZipSkillLoader(用 @TempDir) |
| L2 少量 mock | mock 1~3 个 Mapper | SysUserServiceImpl、TenantServiceImpl、EnvVariableServiceImpl、TokenStatsServiceImpl、MpMessageService、MpUserService |
| L3 多依赖 mock | mock 多个 service + 静态上下文(RequestContextHolder/TenantContext/SecurityUtils) | AuthServiceImpl、ApiKeyServiceImpl、AgentServiceImpl、SessionServiceImpl、MpAuthService、MpSessionService |
| 不建议 UT | 依赖真实进程/网络,放到集成层 | NpmSkillLoader.loadSkills(npm 进程)、GitSkillLoader.loadSkills(git clone) |

通用前置(L3 必读):

- `UserContextUtil.getCurrentUsername(jwtUtil)` 依赖 `RequestContextHolder`,`@BeforeEach` 需 `RequestContextHolder.setRequestAttributes(...)` 并塞入 `Authorization: Bearer <token>`。
- `SecurityUtils.getCurrentUser()` 依赖静态 `instance`(`@PostConstruct` 填充),UT 中需手动调用 `init()` 或反射注入。
- `TenantContext` 是 ThreadLocal,`@AfterEach` 必须清理,避免用例间污染。
- `MessageUtil.getMessage(code)` 取不到时返回 code 本身,因此断言 `BizException.message` 可直接断言错误码字符串(如 `error.user.notfound`)。

---

## 1. AuthServiceImpl(登录/登出)

### 1.1 login

| 编号 | 用例 | 前置/输入 | 期望 |
|------|------|-----------|------|
| AUTH-01 | 用户不存在 | `getByUsername` 返回 null | BizException `error.user.notfound`;不调用 captchaService |
| AUTH-02 | 密码错误 | user.password = BCrypt(SHA256(正确密码)),请求传错误 SHA256 | BizException `error.user.invalid_credentials` |
| AUTH-03 | 验证码为空 | captcha=null 或空白 | BizException `error.captcha.required` |
| AUTH-04 | 验证码 key 为空 | captchaKey=null 或空白 | BizException `error.captcha.key_required` |
| AUTH-05 | 验证码校验失败 | `validateCaptcha` 返回 false | BizException `error.captcha.invalid` |
| AUTH-06 | 用户被禁用 | user.status=0(其余合法) | BizException `error.user.disabled` |
| AUTH-07 | 普通用户无租户 | userTenants 为空且 isAdmin=0 | BizException `error.user.no_tenant` |
| AUTH-08 | admin 无租户可登录 | userTenants 为空且 isAdmin=1 | 成功;`generateToken` 的 tenantId 参数为 null |
| AUTH-09 | 有租户取第一个 | userTenants=[t5, t7] | `generateToken(tenantId=5)`;响应 currentTenantId=5 |
| AUTH-10 | 登录成功返回结构 | 全部合法 | accessToken 非空、tokenType=Bearer、expiresIn=getExpirationTime()/1000、routerApiKey 来自 getPermanentRawKey |
| AUTH-11 | permanent key 自愈 | `getPermanentRawKey` 第一次返回 null,`createPermanentKeyForUser` 成功 | routerApiKey=新建 key 的 rawKey;InOrder 验证 get→create |
| AUTH-12 | 自愈遇并发冲突重试 | `createPermanentKeyForUser` 抛异常,第二次 `getPermanentRawKey` 返回值 | 登录成功;verify getPermanentRawKey 调用 2 次 |
| AUTH-13 | 自愈彻底失败 | create 抛异常且重试 lookup 仍为 null | BizException `Permanent API Key not found for user: <username>`(硬编码英文,非 messageUtil) |
| AUTH-14 | updateLastLoginTime 失败不影响登录 | `updateLastLoginTime` 抛异常 | 登录仍返回成功响应 |
| AUTH-15 | 校验顺序 | 用户不存在时 | verify(captchaService, never()).validateCaptcha —— 明确用户校验先于验证码(现状行为,若改顺序需同步改此用例) |

### 1.2 logout

| 编号 | 用例 | 前置/输入 | 期望 |
|------|------|-----------|------|
| AUTH-20 | 正常登出加入黑名单 | 请求头 `Authorization: Bearer <合法token>` | `addToBlacklist(token, username, userId, expireTime, "logout")` 被调用 1 次 |
| AUTH-21 | 无 Authorization 头 | 不设置头 | addToBlacklist 0 次;不抛异常 |
| AUTH-22 | 非 Bearer 前缀 | `Authorization: Basic xxx` | addToBlacklist 0 次 |
| AUTH-23 | token 解析失败被吞掉 | getUserIdFromToken 抛异常 | 不向上抛;仅 warn |
| AUTH-24 | 无 servlet 上下文 | RequestContextHolder 为空 | 不抛异常 |
| AUTH-25 | expireTime 计算 | 合法 token | 断言传入的 expireTime ≈ now + expiration(注意实现用 plusNanos(expiration*1_000_000),即毫秒转纳秒) |

---

## 2. ApiKeyServiceImpl

### 2.1 纯逻辑(L1,可反射调私有或提为 internal)

| 编号 | 用例 | 期望 |
|------|------|------|
| KEY-01 | generateRawKey 格式 | 前缀 `hnx_sk_live_`;总长 54(12 前缀 + 43 base64url 无填充);两次生成不同 |
| KEY-02 | sha256 确定性 | 同输入两次结果一致;64 位小写 hex |
| KEY-03 | keyPrefix 格式 | `rawKey.take(12) + "..." + rawKey.takeLast(4)` |

### 2.2 createApiKey

| 编号 | 用例 | 前置/输入 | 期望 |
|------|------|-----------|------|
| KEY-10 | 名称重复 | `selectByName` 非 null | RuntimeException `API Key name already exists: <name>` |
| KEY-11 | 非 admin 无租户上下文 | isAdmin=false 且 TenantContext 为空 | RuntimeException `Tenant context is required to create API Key` |
| KEY-12 | admin 指定租户 | isAdmin=true, request.tenantId=9 | 插入实体 tenantId=9 |
| KEY-13 | admin 未指定租户回退上下文 | request.tenantId=null, TenantContext=3 | 插入实体 tenantId=3 |
| KEY-14 | 默认值 | scopes/rateLimit 不传 | keyType=TEMPORARY、scopes=chat、rateLimit=60、enabled=1 |
| KEY-15 | expiresAt 解析 | 传 `2026-12-31T00:00:00` | 实体 expiresAt 为对应 LocalDateTime;传 null 则为 null |
| KEY-16 | 响应包含明文 key | 成功创建 | 响应 rawKey 与插入实体 keyHash=sha256(rawKey) 对应 |

### 2.3 update/delete/toggle 保护

| 编号 | 用例 | 前置/输入 | 期望 |
|------|------|-----------|------|
| KEY-20 | PERMANENT key 不可修改 | entity.keyType=PERMANENT | RuntimeException `PERMANENT API Key cannot be modified, use regenerate instead` |
| KEY-21 | SYSTEM key 不可删除 | entity.keyType=SYSTEM | delete 抛 RuntimeException |
| KEY-22 | 非 admin 不可改租户 | isAdmin=false, request.tenantId≠entity.tenantId | RuntimeException `No permission to change tenant` |
| KEY-23 | 无权限访问他人 key | checkAccess 失败场景(不同 username 且非 admin 且不同 tenant) | RuntimeException `No permission to access this API Key` |
| KEY-24 | expiresAt 置空 | update 传 blank | 实体 expiresAt=null |

### 2.4 permanent / system key

| 编号 | 用例 | 前置/输入 | 期望 |
|------|------|-----------|------|
| KEY-30 | getPermanentRawKey 不存在 | mapper 返回 null | 返回 null(不抛) |
| KEY-31 | key 被禁用 | entity.enabled=0 | BizException `Your API Key has been disabled, please contact administrator` |
| KEY-32 | 无密文 | rawKeyEncrypted=null | 返回 null |
| KEY-33 | 正常解密 | rawKeyEncrypted=AES 密文 | 返回 aesUtil.decrypt 结果 |
| KEY-34 | createPermanentKeyForUser 字段 | userId=8, username=u8 | name=permanent_u8、keyType=PERMANENT、rateLimit=300、creator=system、rawKeyEncrypted=encrypt(rawKey) |
| KEY-35 | regeneratePermanentKey 不存在 | mapper 返回 null | RuntimeException `Permanent API Key not found for user: <id>` |
| KEY-36 | regeneratePermanentKey 成功 | 存在 | keyHash/keyPrefix/rawKeyEncrypted 均更新;返回新 rawKey ≠ 旧 |
| KEY-37 | initSystemKeys 幂等 | channel-service 已存在、scheduler 不存在 | 只 insert scheduler;name=system_scheduler、rateLimit=600 |

---

## 3. SysUserServiceImpl

> 注:密码长度 6-100、用户名正则由 DTO `@Size/@Pattern` + Controller `@Valid` 保证,Service 层 UT 不重复覆盖(集成测试已覆盖)。

### 3.1 createUser

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| USR-01 | email 缺失 | email=null/blank | BizException `error.validation.required` |
| USR-02 | phone 缺失 | phone=null/blank | BizException `error.validation.required` |
| USR-03 | email 格式非法 | `a@b`、`a@.com`、`@x.com` | BizException `error.validation.email_invalid` |
| USR-04 | phone 格式非法 | `12345678901`(1[3-9] 不满足)、`2380013800`、10 位 | BizException `error.validation.phone_invalid` |
| USR-05 | 用户名重复 | getByUsername 非 null | BizException `error.user.username_exists` |
| USR-06 | 手机重复 | selectByPhone 非 null | BizException `error.user.phone_exists` |
| USR-07 | 邮箱重复 | selectByEmail 非 null | BizException `error.user.email_exists` |
| USR-08 | 密码 BCrypt 存储 | 合法请求 | 插入实体 password ≠ 明文且 `BCrypt.checkpw(明文, 存储值)` 为 true |
| USR-09 | 默认值 | 不传 gender | status=1、isAdmin=0、active=1、gender=2 |
| USR-10 | 自动创建 permanent key | insert 成功 | `createPermanentKeyForUser(user.id, username, tenantId)` 被调用 |
| USR-11 | permanent key 创建失败不影响建用户 | createPermanentKeyForUser 抛异常 | createUser 仍返回 true |
| USR-12 | insert 失败 | mapper.insert 返回 0 | 返回 false;不调用 createPermanentKeyForUser |

### 3.2 updateUser / toggleUserStatus / deleteUser

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| USR-20 | 更新不存在用户 | selectById=null | BizException `error.user.notfound` |
| USR-21 | 更新 email 重复 | email 改变且 selectByEmail 非 null | BizException `error.user.email_exists` |
| USR-22 | 更新 phone 重复 | phone 改变且 selectByPhone 非 null | BizException `error.user.phone_exists` |
| USR-23 | email/phone 相同不触发查重 | 与原值一致 | 不调用 selectByEmail/selectByPhone 查重分支;更新成功 |
| USR-24 | 禁用 admin 被拒 | isAdmin=1, status=0 | BizException `error.user.cannot_disable_admin` |
| USR-25 | 禁用租户管理员被拒 | user_tenant 中 role=admin | BizException `error.user.is_tenant_admin_cannot_disable`;消息含租户名(", " 连接) |
| USR-26 | 租户已删除时跳过 | role=admin 但 tenantMapper.selectById=null | mapNotNull 跳过;不抛(租户名列表为空时的行为需固化) |
| USR-27 | 启用不做保护检查 | status=1 | 直接 updateStatus;不查 user_tenant |
| USR-28 | 删除 admin 被拒 | isAdmin=1 | BizException `error.user.cannot_delete_admin` |
| USR-29 | 删除租户管理员被拒 | role=admin 关联存在 | BizException `error.user.is_tenant_admin` |
| USR-30 | 删除清理关联 | 用户属于 2 个租户(非 admin 角色) | 每个租户各调一次 deleteByUserIdAndTenantId,再 deleteById |

---

## 4. SessionServiceImpl

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| SES-01 | 标题重复 | countByTitle>0 | BizException `Session name already exists, please use another name` |
| SES-02 | agent 不存在 | getAgent=null | BizException `Agent not found`(注意被外层 catch 包装为 RuntimeException `Failed to create session: ...`,断言最终异常类型与 message) |
| SES-03 | 创建复制 agent 字段 | agent 含 name/description/systemPrompt/modelId/owner | Session 实体对应字段一致;sessionId 前缀 `web-`;status=1、isPublic=0(agent 与 session 上都没有 `mcp_list` / `skill_list` 列,创建会话不复制能力清单) |
| SES-04 | 更新不存在会话 | selectById=null | BizException `Session not found` |
| SES-05 | 更新字段映射 | 传 sessionDescription | 写入实体 description 字段(命名不一致点,固化行为) |
| SES-06 | chat config 会话不存在 | selectBySessionIdAndStatus=null | BizException `Session not found or disabled` |
| SES-07 | chat config Boolean→Int | enableThink=true, enableSearch=false | 实体 enableThink=1、enableSearch=0 |
| SES-08 | chat config 更新失败 | updateById 返回 0 | BizException `Failed to update session configuration` |
| SES-09 | convertToResponse 取绑定表 | agent 有 mcp 绑定 3、4 与 skill 绑定 10 | `mcpBindingMapper.selectByAgentId` / `skillBindingMapper.selectByAgentId` 各调一次,响应按绑定行给出,不解析任何 JSON 列 |
| SES-10 | convertToResponse 无绑定 | 两个绑定表都返回空 | response.mcpList / skillList 均为空列表(旧「脏 JSON 兜底」用例随 `mcp_list` 列一起删除) |
| SES-11 | 悬空绑定跳过 | mcp 绑定指向已删服务(getMcpServer=null);skill 绑定指向已删技能 | 该条整体不出现在响应中,不抛;技能存在但其 repository 查不到时,该行保留 id / name,repositoryId 与 repositoryName 为 null |
| SES-12 | 列表查询带租户 | TenantContext 设为 7 | `selectSessionList` 末位参数取到 7;未设租户时回落到 1(`TenantContext.getTenantId() ?: 1`,与 `McpServerServiceImpl` 同口径)。SQL 侧的 `AND tenant_id = ?` 见 `SessionMapperTest` |
| SES-13 | 创建时打上当前租户 | TenantContext 设为 7 | argumentCaptor 捕获的实体 tenantId=7(不打通这一列,会话会落到 DDL 缺省租户,与它绑定的 agent 不同租户);缺省场景另有断言 saved.tenantId=1 |

---

## 5. AgentServiceImpl

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| AGT-01 | 创建成功默认值 | status/isPublic 不传 | status=1、isPublic=0、tenantId=TenantContext ?: 1(落库那一半由 `AgentMapper.xml` 的 insert 与 resultMap 承担，并由 `AgentMapperTest` 的 `insert should persist tenant id` 钉住;本条只验入口默认值) |
| AGT-02 | 子调用异常被包装 | mapper.insert 抛 SQLException | RuntimeException,message 前缀 `Failed to create agent:` |
| AGT-03 | 更新不存在 agent | selectById=null | RuntimeException `Agent not found`(注意非 BizException,前端会拿到 500 语义 —— 建议列为改进项) |
| AGT-04 | 更新时 list 为 null 不覆盖绑定 | toolList=null | 不调用 saveToolBindings;传空列表时则清空绑定 |
| AGT-05 | deleteAgent 级联 | 任意 id | 顺序验证:tool→mcp→skill 绑定删除,最后 agentMapper.deleteById |
| AGT-06 | saveToolBindings 先删后插 | toolList=[{id:1}] | InOrder: deleteByAgentId → insert |
| AGT-07 | toolList 条目缺 id 跳过 | [{id:null}, {id:2}] | 只插入 toolId=2 |
| AGT-08 | needConfirm 透传 | binding 请求 needConfirm=true / false / null | 分别存 1 / 0 / 0;查询侧不读工具实体(运行时取 `agent_tool.needConfirm` 与绑定值的或,绑定层只能加严) |
| AGT-09 | 同一请求内重复 toolId 去重 | [{id:1}, {id:1}, {id:2}] | 只插入 toolId=1、2 各一条(取首次出现),配合 `uk_agent_tool_binding_agent_id_tool_id` |
| AGT-10 | skillList 解析 | "1,,x,3" | 只插入 1、3;空串与非数字跳过 |
| AGT-11 | serializeEnvBindings 空入参 | null / 空列表 | 返回 null |
| AGT-12 | serializeEnvBindings 引用优先 | envVarId=5 且 customValue="abc" | 两个都带时按引用处理：JSON 只写 `envVarId` / `envVarName`，`customValue` 被丢弃（与 `if (envVarId != null) … else if` 的分支一致） |
| AGT-13 | serializeEnvBindings 引用只落指针 | envVarId=7,envValue=`******` | JSON 含 `"envVarId":7`、不含 `******`、不含 `envValue` 键。引用条目带的是指针，值在下发时现取：把 `getDecryptedValue` 的结果写进列等于让明文密钥常驻 `env_bindings`，而客户端回填到这一栏的又常常是掩码，两边都不是可用值。解析侧的同一条不变量在 §5.11 |
| AGT-14 | parseEnvBindingsJson 脏数据 | "not-json" | 返回 null,不抛 |
| AGT-15 | parseEnvBindingsJson 敏感变量掩码 | envVar.sensitive=1 | displayValue="******" |
| AGT-16 | parseEnvBindingsJson 变量已删除回退快照 | getRowWithinTenant=null | 使用存储的 snapshotValue。**只对历史行成立**：新写入的引用条目没有 snapshotValue 可兜，界面与下发都拿不到值（下发侧剩一句 warn），见 §5.11 与 `prod_doc/mcp-management.zh-CN.md` §6 |
| AGT-17 | 同一请求内重复 mcpId 去重 | mcpList=[{id:3},{id:3},{id:4}] | 只插入 mcpId=3、4 各一条(取首次出现),配合 `uk_agent_mcp_binding_agent_id_mcp_id`;绑定行没有 `enableSkip` 字段(schema 里也没有这一列) |
| AGT-18 | 绑定的 mcpId 解析不到 | mcpList=[{id:9}],`mcpServerMapper.selectByIds([9])` 返回空(服务已删/不存在) | BizException,message 含 `9`;`mcpBindingMapper.batchInsert` never()(存得进去但 `agent-spec` 解析不出来的绑定从此造不出来) |
| AGT-19 | 绑定的 mcpId 属别的租户 | selectByIds 返回 tenantId=2 的行,当前租户为默认的 1 | BizException;`batchInsert` never()。判定口径与 `McpServerService.getMcpServer` 一致(`selectByIds` 已排除 `active=0`,再比租户) |
| AGT-20 | 创建时打上当前租户 | TenantContext 设为 7 | argumentCaptor 捕获的实体 tenantId=7;落库列由 `AgentMapperTest` 的 `insert should persist tenant id` 补齐(此前 `AgentMapper.xml` 的 insert 无 `tenant_id`,这行只是意图声明) |
| AGT-21 | 列表查询带租户 | TenantContext 设为 7 | `selectAgentList` 末位参数取到 7;未设租户时回落到 1。SQL 侧 `AND tenant_id = ?` 见 `AgentMapperTest` 的 `selectAgentList should filter by tenant` |
| AGT-22 | 单行读取按租户 | selectById 返回 tenantId=7 的行,当前租户为默认的 1 | `getAgent` 返回 null(列表按租户过滤而单行不过滤,等于把过滤做成摆设);口径与 `McpServerService.getMcpServer` 一致 |
| AGT-23 | 启停不绕过租户守卫 | 同上 id | RuntimeException `Agent not found`,`updateStatus` never()(写入口必须走 `getAgent`) |
| AGT-24 | 删除不绕过租户守卫 | 同上 id | RuntimeException `Agent not found`,`deleteById` 与绑定级联都 never() |

### 5.1 AgentToolServiceImpl(只读查询)

> 工具只剩内置一类,生命周期只有「代码注册」一个入口(见 prod_doc 工具文档 §5),因此工具侧没有创建/更新/删除/启停四类写接口,也不存在 `AgentToolCreateRequest` / `AgentToolUpdateRequest` 这两个请求体。本节覆盖四个读方法与响应装配。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| TLS-01 | 分页查询 | `page(null, null, 1, 10)` | 走 `selectAgentToolList(null, null)`,返回 PageInfo |
| TLS-02 | 分页按关键字 | keyword=`time` | 关键字透传给 `selectAgentToolList` |
| TLS-03 | 分页按状态 | status=0 | 状态透传给 `selectAgentToolList` |
| TLS-04 | 详情命中 | selectById 返回行 | 返回 `AgentToolResponse`,name 一致 |
| TLS-05 | 详情未命中 | selectById=null | 返回 null,不抛异常 |
| TLS-06 | 响应装配环境参数条目 | `agent_tool_env_param` 有 2 条定义 | `envParams` 两条,`required` / `secret` 按定义给出;非敏感项回显明文,敏感项回显掩码(`my-****alue`) |
| TLS-07 | 无环境参数定义 | selectByToolId 返回空 | `envParams` 空 |
| TLS-08 | 敏感项短值全掩码 | 解密后长度 ≤7 | 返回 `******` |
| TLS-09 | 解密失败绝不泄漏 | decrypt 抛异常 | 返回 `******`,异常不外泄 |
| TLS-10 | requiredEnvParamKeys 解析 | 实体为 `["API_KEY"]` | 响应为 `["API_KEY"]` |
| TLS-11 | available 走非必须查询 | — | 调 `selectAvailableTools()`(SQL 侧 `is_required = 0`) |
| TLS-12 | builtin 返回代码全量 | selectBuiltinToolList 有行 | 必须与非都必须都返回 |
| TLS-13 | required-env-params 解析 | `["API_KEY","SECRET"]` | 返回 2 个 key |
| TLS-14 | required-env-params 空值与坏 JSON | null / `not-json` | 都返回空列表 |
| TLS-15 | required-env-params 工具不存在 | selectById=null | RuntimeException |

### 5.2 BuiltinToolAutoRegistrar(启动收敛,只增不删)

> 记录身份是 `@Tool.name`,唯一键 `uk_agent_tool_name`;写入按 `selectByName` 定位后 `insert` 或有差异才 `updateById`,mapper 里没有 upsert 语句。这张表只有这个启动同步会写,因此代码不再声明的行留着但不再下发。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| REG-01 | 库里没有的声明被插入 | selectByName=null | insert 的实体 `name`/`beanName` 按声明,`status=1, active=1, creator='SYSTEM'`;不调 updateById |
| REG-02 | 逐列一致就不写 | 已有一行且代码拥有的列全等 | insert、updateById 都不调用 |
| REG-03 | 有差异才原地更新 | 库中 `method_name` 为 `oldMethod`、`display_name` 为旧值 | updateById 携带既有 `id=7` 与 `createTime`,agent 绑定因此存活 |
| REG-04 | status 由同步收敛回启用 | 库中该行 `status=0` | 被改回 1(整张表只有这个写入方) |
| REG-05 | 代码不再声明的行不碰 | 库中留着 `removed_tool` 一行 | 既不 update 也不 delete;`registeredToolNames()` 不含它,即被挡在下发之外 |
| REG-06 | 同名两条声明先拒后写 | 两个 bean 各声明一个 `@Tool.name` 相同的方法 | 抛 IllegalStateException,消息点名两个 `bean::method`;任何 insert/updateById 都没发生 |
| REG-07 | 扫不到工具就整轮跳过 | getAllToolMeta 返回空 map | 不 insert、不 updateById,`registeredToolNames()` 保持空集 |
| REG-08 | 单组失败不牵连同组外 | broken 组 insert 抛异常,healthy 组正常 | 两组都被尝试(insert 两次);失败组的工具名仍计入 `registeredToolNames()`,写失败不该变成所有 agent 少一个工具 |
| REG-09 | 环境参数跟随所属工具收敛 | 注解声明 1 条必填,库中同 toolId 下有一条陈旧定义 | 声明的那条 insert(`required=1`),注解里已不存在的陈旧定义 deleteById |

### 5.3 McpServerServiceImpl(MCP 写入口缺省、更新语义、删除级联与租户过滤)

> MCP 没有代码注册收敛,页面/API 就是它的生命周期,因此写入口的缺省值与「省略是否等于覆盖」必须钉住。停用链路的下发环节另有覆盖:`InternalApiControllerTest`「getAgentSpec - MCP status 随配置下发」与 agent-service 的 `McpConfigAdaptorImplTest`(DTO→实体透传 status;该套件只验「下发的 spec 是唯一来源」——这一侧不查库,库里那行是它解不开的密文)。最末一环 `HarnessAgentLauncher` 在 harness-core 无单测(该类依赖完整构建链路),它持有三条未验行为:「status=0 跳过装配」、「单台客户端建不起来只跳过这一台并关掉半成品」、「`release()` 逐个关闭本轮建出的 MCP 客户端」,与工具侧的同类跳过一样留给人工/集成验证。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MCS-01 | 创建携带 isPublic | request.isPublic=0 | 落库实体 isPublic=0(isPublic 以请求值落库,不写死公开) |
| MCS-02 | 创建缺省值 | 不传 status / isPublic | 实体 status=1、isPublic=1、active=1 |
| MCS-03 | 创建允许 stdio | type=stdio + command 非空 | 正常 insert(创建与更新对 stdio 同一条闸,口径一致) |
| MCS-04 | stdio 缺 command | type=stdio,command=null | BizException,不调用 insert |
| MCS-05 | 更新应用 status | request.status=0 | argumentCaptor 捕获实体 status=0(`updateById` 的 SET 已含 status 列) |
| MCS-06 | 更新省略 status / isPublic | 两个字段都不传 | 实体保持原值 1、1(可空即「省略不改」,修复编辑页开关静默失效) |
| MCS-07 | 删除级联清绑定 | deleteById 返回 1 | 同事务调用 `agentMcpBindingMapper.deleteByMcpId(id)`,返回 true |
| MCS-08 | 逻辑删除未命中不级联 | deleteById 返回 0 | 返回 false 且 `deleteByMcpId` never() |
| MCS-09 | 列表查询带租户 | TenantContext 里设了 7 | `selectMcpServerList` 末位参数取到 7;未设租户时回落到 1(`TenantContext.getTenantId() ?: 1`,与 `CliServiceImpl` 同口径),Mapper 侧该参数可空、为空即不拼 `AND tenant_id = ?` |
| MCS-10 | 单行读取按租户 | selectById 返回 tenantId=2 的行,当前租户 1 | `getMcpServer` 返回 null(由控制器转 404/未找到),不把别租户的配置读出来 |
| MCS-11 | 删除按租户 | 同上,id 猜对了 | `deleteMcpServer` 拒绝,`deleteById` 与级联清绑定都不执行 |
| MCS-12 | 改名查重按租户 | 更新时传新名,查重 stub 为 `selectByName(新名, 行内 tenantId)` | 命中即 BizException;stub 带第二个参数本身就是断言 —— 查重漏掉租户就等于允许跨租户抢名(库层由 `uk_mcp_server_tenant_active_name` 兜底) |
| MCS-13 | authType 缺省 | 创建请求不传 `authType` | 落库为 `NONE`,不是空串也不是 null(运行侧按值分支,空值等于未定义行为) |
| MCS-14 | 未知 authType | 传 `BEARER` | 直接拒。`McpAuthTypes.SUPPORTED` 之外的值不许入库——入库就等于下发给运行时一个它认不得的分支 |
| MCS-15 | BASIC 先拒 | 传 `BASIC` | 拒,错误信息含 `not wired into the runtime yet`。列上能写、运行时不生效的认证方式是假开关(P4 接上后再放开) |
| MCS-16 | stdio 不接受 OAuth | `type=stdio` + `authType=OAUTH2` | 拒,错误信息含 `no HTTP request to attach a token to`。stdio 是本地进程,没有可注入 Bearer 的 HTTP 请求 |
| MCS-17 | OAuth 配置落库形态 | `authType=OAUTH2` + `oauthConfig` | 走 `objectMapper.writeValueAsString(McpOAuthConfig)` 存入 `oauth_config`;断言序列化的是那个类型化 DTO,不是任意 JSON —— 客户端密钥等敏感项没有字段可写,才不会被误写进这列明文回显给前端 |
| MCS-18 | 非 OAuth 却带配置 | `authType=STATIC_HEADER` + 非空 `oauthConfig` | 拒,错误信息含 `only applies to an auth type of`。静默丢弃会让用户以为配置生效了 |
| MCS-19 | 授权服务器地址形态 | `authorizationServer=file:///etc/passwd` | 拒,错误信息含 `must be an http(s) URL`。这串会被拼进跳转与 token 请求,非 http(s) 协议没有合法语义 |
| MCS-20 | 从 OAUTH2 切走清空 | 原行 `authType=OAUTH2` 且 `oauthConfig` 非空,更新成 `NONE` | `updateById` 捕获的实体 `oauthConfig` 为 null,而不是「保持原值」——`updateById` 的 XML 是无条件 SET,漏清空就会留下旧配置;同一刀还要 `verify(mcpUserCredentialMapper).deleteByMcpId(1L)`:读与撤销都经 `requireOAuthServer`,它拒绝一台不再是 OAUTH2 的服务,留下的密文对它的持有人来说既看不见也撤不掉 |
| MCS-21 | 改 type 时一并校验 | 把 OAUTH2 服务改成 stdio | 拒且 `verify(mcpServerMapper, never()).updateById(any())`:校验必须早于任何写,不能先落库再回滚口径 |
| MCS-22 | 删除级联凭据 | 正常删除 | `verify(mcpUserCredentialMapper).deleteByMcpId(1L)`。服务没了还留着用户对它的 token,是纯债 |
| MCS-23 | 没离开 OAUTH2 就不动凭据 | 原行 `authType=OAUTH2`,请求只改 description | `verify(mcpUserCredentialMapper, never()).deleteByMcpId(anyLong())`——清理的条件是「这次请求把 OAuth 关掉了」,不是「这行现在是 OAuth」 |
| MCS-24 | 本来不是 OAuth 的服务也不去清 | 原行 authType 是 `NONE`(夹具不设这一列,取实体的默认值),请求只改 description | 同上 never():判据是 `wasOAuth`——原行的 `auth_type` 是不是 `OAUTH2`,不是「这行有没有 authType 值」。schema 给这一列的是 `NOT NULL DEFAULT 'NONE'`、实体字段也是非空 `String`,不存在「老行 authType 为 null」;把 `NONE` 当成「刚离开 OAUTH2」就会去删一张与此无关的表 |
| MCS-25 | 换成 stdio 清掉网络型字段 | 原行 `streamablehttp` 带 `url` 与加密 `headers`,请求 `type=stdio` + 非空 `command` | 捕获实体 `type=stdio`、`url == ""`(实体非空)、`headers == null`。`updateById` 无条件写全列,残留的 url 会永远躺在行上,而运行时按 `type` 分派根本不看它——一台服务变成两段配置拼出来的,没人发现得了 |
| MCS-26 | 换成网络型清掉 stdio 字段 | 原行 `stdio` 带 `command` 与加密 `envParams`,请求 `type=sse` + 非空 `url` | 捕获实体 `type=sse`、`command == ""`、`envParams == null`。与 MCS-25 同一刀的两侧,少一侧就还剩一半残留 |
| MCS-27 | OAuth 服务换 url 清逐人凭据 | 原行 `OAUTH2` + `url=:8080/mcp`,请求改 `url=:9090/mcp`(不改 authType) | `verify(mcpUserCredentialMapper).deleteByMcpId(1L)`,且捕获实体仍是 `OAUTH2`、`oauthConfig` 非空。RFC 8707 把令牌与 resource 地址绑死,旧地址换到的凭据不可能再被接受——不清就是 `status` 永远回答「已授权」而换票必失败 |
| MCS-28 | url 原样重提不算换地址 | 原行 `OAUTH2` + `url=:8080/mcp`,请求带同一个 url | `verify(mcpUserCredentialMapper, never()).deleteByMcpId(anyLong())`。编辑表单每次都把填着的 url 提交回去,把「带着但相同」读成搬迁,等于每次保存都把全租户的用户登出 |

> 唯一键与租户过滤的 SQL 行为另有 `McpServerMapperTest`:`selectByName should limit the lookup to the given tenant`、`unique key should guard active names per tenant`(同租户重名 `assertThrows<DuplicateKeyException>`、别租户可同名、软删后可复用)、`selectMcpServerList should filter by tenant`。该类走 Testcontainers,跑法见 §17(本机 Docker 可用即可,无需额外环境变量),已计入回归。`schema-test.sql` 的 DDL 段取自 admin 的 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`,生成列与唯一键因此与生产同源、不需要两头对齐。`mcp_oauth_client`、`mcp_user_credential`、`mcp_call_log` 三张 OAuth 表各有对应用例:`McpOauthClientMapperTest`(8,含 `issuer` 精确匹配与复用取最小 id)、`McpUserCredentialMapperTest`(7,含撤销时把两个密文列写回 NULL、重新授权复用同一行)、`McpCallLogMapperTest`(3,只有 insert)。

### 5.4 InternalApiController(配置下发)

> 下发既是运行时的唯一事实来源,也是 N+1 与「两半答案不一致」的高发地,因此这几个断言成对写:一次批量查询 + 悬空绑定的两半同缺。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| IAD-01 | MCP 一次批量查询 | 绑定 mcpId=7、88,`selectByIds([7,88])` 只返回 7 | `verify(mcpServerMapper).selectByIds(...)` 且 `never().selectById(anyLong())`(一次批量查询，不留逐条 `selectById` 的 N+1) |
| IAD-02 | 悬空 MCP 绑定两半同缺 | 同上 | `mcpDetails` 只含 7;legacy `mcpList` 也只含 7——已删服务的 `env_bindings` 不会经 `mcpList` 漏进 `ToolEnvContext` |
| IAD-03 | 悬空技能绑定两半同缺 | 绑定 skillId=21、999,批量结果只含 21 | `skillDetails` 与 `skillList` 都只剩 21 |
| IAD-04 | getAgentTaskSpec 能力清单取自绑定表 | agent 上没有 `mcp_list` / `skill_list` 列 | `mcpList` 由 `serializeMcpBindings(绑定行)` 得到(含 `env_bindings`),`skillList` 为绑定 skillId 的逗号串 |
| IAD-05 | status 随配置下发 | mcp_server.status=0 | `McpDetailDto.status=0` 原样下发,由运行侧决定跳过 |
| IAD-06 | 别租户的 MCP 不下发 | 绑定 mcpId=7 的行 tenantId=2,agent tenantId=1 | `mcpDetails` 与 `mcpList` 两半都不出现该条,且 `verifyNoInteractions(secretFieldEncryptor)`——别租户的凭据连解密都不发生 |

> **口径差的关闭方式**:下发用的 `selectByIds` 仍然不带租户条件(内部调用没有可信的 `X-Tenant-ID`,注入不进来),但 `agent.tenant_id` 是真正落库的列,于是 `buildAgentSpecResponse` 拿到 agent 归属后在内存里比一刀(IAD-06)。写入侧由绑定校验拦,下发侧由这一刀拦:库里已存在的跨租户绑定不会随 spec 出去。与 `McpServerServiceImpl` 的可见性口径一致:`is_public` 只在租户内成立,不存在跨租户共享。

### 5.5 SecretFieldEncryptor(敏感字段加解密与掩码沿用)

> 前端把 secret 回显成掩码(`abc****defg` / 短值 `******`),原样提交回来时不能当新值写库——否则掩码字符串会覆盖掉真凭据。断言放在加密器这一层,因为 MCP / 工具 / CLI 三个写入口共用它。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| SFE-01 | headers 掩码沿用密文 | secret 条目 value 含 `****`,并传库里已有的 `storedJson` | 落库 JSON 里该条目的密文原样保留,不用掩码重新加密 |
| SFE-02 | 短掩码同样沿用 | value = `******` | 同 SFE-01(一个 `contains("****")` 判据覆盖两种掩码形态) |
| SFE-03 | 无可沿用密文时要求重填 | 掩码值 + `storedJson` 为空/无同名条目 | BizException,message 点名该 key 要求重新填写,不静默写坏 |
| SFE-04 | 工具环境参数同口径 | `serializeToolEnvParams` 的敏感 `defaultValue` 为掩码 | 按 `envParamName` 匹配沿用库里密文 |
| SFE-05 | 夹具必须用 Kotlin 感知的 mapper | 构造 `SecretFieldEncryptor` | 用 `jacksonObjectMapper()`,与生产注入的 `JacksonConfig` bean 一致;裸 `ObjectMapper()` 绑不了 Kotlin 数据类的构造参数,`deserializeEntries` 会返回字段全空的条目,夹具看着绿、测的却是一个空壳 |
| SFE-06 | `resolveSecret` 未提供即保持 | provided=null,stored=密文 | 原样返回 stored(单列密钥的「省略不改」) |
| SFE-07 | `resolveSecret` 掩码即沿用 | provided 含 `****`,stored=密文 | 返回 stored 密文,不把掩码加密 |
| SFE-08 | `resolveSecret` 掩码且无密文可沿用 | provided 含 `****`,stored=null | BizException,message 含 `please re-enter it`——静默保留与静默覆盖都是丢数据 |
| SFE-09 | `resolveSecret` 空串即清空 | provided="   " | 返回 null(公开客户端 + PKCE 的正常形态:显式清掉密钥) |
| SFE-10 | `resolveSecret` 新值加密并 trim | provided="  s3cr3t  " | 返回密文,`decrypt` 回来等于 `s3cr3t` |
| SFE-11 | `resolveSecret` 新值无需既有行 | provided 非空,stored=null | 正常加密返回(首次登记客户端的路径) |

### 5.6 McpOAuthServiceImpl(AS 发现与客户端登记)

> 设计口径见 `prod_doc/mcp-management.zh-CN.md` §3.5(英文版 §3.5)。被测的 MCP 服务 url 是 `http://mcp.example.com:3000/mcp`,issuer 是 `https://as.example.com`。出站请求全部经 `remoteJsonFetcher` 打桩:桩是一个 `Map<String, () -> RemoteFetch>`,**未登记的地址一律回 404**——「试了哪个候选、按什么顺序试」因此是断言出来的,不是猜的。`encryptor.resolveSecret` 也按真实规则打了桩(keep/replace/clear),掩码分支另有 `SecretFieldEncryptorTest` 用真加密器跑。五组分别是 issuer 解析 14、AS 元数据 11、发现落库 11、客户端凭据 11、回跳地址 2,合计 49 个。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOA-01 | 手填 issuer 时不走发现 | `oauthConfig.authorizationServer` 非空 | `issuerSource=CONFIG`,且两个 protected-resource 候选都 `never()` 被请求 |
| MOA-02 | 无 authorizationServer 时走 RFC 9728 插入式 | 只有 `http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp` 有文档 | `issuerSource=PROTECTED_RESOURCE`,端点取自该 AS 的元数据 |
| MOA-03 | 两个路径型文档都没有时试主机根 | 只有 `.../.well-known/oauth-protected-resource` 有文档 | 仍解析出授权服务器(候选顺序是规范要求的,只试一种会漏) |
| MOA-04 | 文档列多个 AS 时取第一个 | `authorization_servers:[本 AS, 另一个]` | 用第一个,不去合并(合并等于把两个 AS 的客户端身份并成一个) |
| MOA-05 | 401 挑战里的 resource_metadata 也算一路 | well-known 全无,GET MCP url 回 401 + `WWW-Authenticate: Bearer realm="mcp", resource_metadata=".../authz.json"` | 顺指针取到 issuer,`issuerSource=RESOURCE_METADATA` |
| MOA-06 | 文档广告非 http(s) 的 issuer 直接拒 | `authorization_servers:["file:///etc/passwd"]` | 报错含 `must be an http(s) URL`——文档是别人写的,不能让它把用户送去 `file://` |
| MOA-07 | 什么都没应答是错误不是降级 | 四个候选都抛 `RemoteFetchException` | 报错以 `Cannot locate the authorization server` 开头(带各候选原因),且 `never()` insert——绝不静默退回静态 header |
| MOA-08 | RFC 8414 插入式优先 | issuer 带 path(`/realms/harnax`) | 命中的是 `origin/.well-known/oauth-authorization-server/realms/harnax` |
| MOA-09 | 插入式没有则试后缀式 | 只有 `issuer/.well-known/oauth-authorization-server` 有 | 采用后缀式(很多实现就是这么发的) |
| MOA-10 | OIDC 文档可作兜底 | 两个 oauth 式都没有、`/.well-known/openid-configuration` 有 | 采用 openid 文档,端点取其 token / revocation |
| MOA-11 | 文档声明的 issuer 不等就拒绝 | 文档 `issuer=https://evil.example.com` | 报错含 `expected <issuer>` 且 `never()` insert(RFC 8414 要求精确相等,否则每个 token 都会在自己的 issuer 校验上失败) |
| MOA-12 | 缺 token_endpoint 不算 AS | 只有 `authorization_endpoint` | 报错含 `no authorization_endpoint/token_endpoint`——半套端点看起来「配好了」更危险 |
| MOA-13 | 200 但没有 JSON 节点 | `RemoteFetch(200, null, null)` | 报错含 `is not a JSON object`(200 不等于拿到了元数据) |
| MOA-14 | 首次发现落新行 | 无既有行 | insert:`clientId=""`、`tenantId=1`、`creator="admin"`、`callbackUrl=${app.frontend-base-url 或 base-url}/mcp/oauth/callback`(指前端路由,见 MOA-48/49);回显 `clientId=null`、`clientSecretPresent=false` |
| MOA-15 | 二次发现保留已登记客户端 | 既有行有 clientId/secret/callback | `never()` insert;三个值原样携带,端点刷新,且 AS 不再广告的 `registrationEndpoint` 被写成 null(快照要说出这件事,而不是留着死端点) |
| MOA-16 | 发现的 issuer 回写配置 | issuer 来自 protected-resource | 捕获 `updateOAuthConfig(eq(1L), json)`,其 `authorizationServer` 等于该 issuer;并 `verify(mcpServerMapper, never()).updateById(any())`——发现只拥有这一列,整行回写会把别人正在编辑的 status/headers 一起覆盖(下次也不必再走网络) |
| MOA-17 | 手填的 issuer 不回写 | issuer 来自 `oauthConfig` | `verifyNoInteractions(mcpServerMapper)`——不覆盖管理员填的那列 |
| MOA-18 | 请求的 scopes 不在 AS 清单里要报告 | config.scopes=`[read, admin]`,AS 只支持 `[read, write]` | `unknownScopes=[admin]`(否则要等用户登完录才 400) |
| MOA-19 | 非 OAuth 服务先拒 | authType=STATIC_HEADER | 报错含该 authType,且 `verify(fetcher, never()).fetch(any())` + 不 insert——不给它发任何出站请求 |
| MOA-20 | OAuth 但没有 url 先拒 | url 空 | 报精确消息 `OAuth discovery needs the MCP server's url, and this one has none`,同样零出站 |
| MOA-21 | 跨租户按不存在回答 | `getMcpServer(9)` 返回 null | 报 `MCP server not found`(租户守卫在 `McpServerService` 那一层,别的路径不重复审一遍) |
| MOA-22 | 登记 client_id + 新密钥 | clientId=`" harnax-web "`、secret=`"s3cret"` | 落库 clientId 已 trim、密文来自 `resolveSecret("s3cret", ...)`;回显只有 `clientSecretPresent=true`,无明文 |
| MOA-23 | 省略 secret 保持原值 | clientSecret=null,行内有密文 | `verify(encryptor).resolveSecret(anyOrNull(), eq("stored-ciphertext"))`,行上的密文不变(掩码回显后再提交是常态) |
| MOA-24 | 空串 secret 清空 | clientSecret="" | 行上 `clientSecretEnc=null` 且回显 `clientSecretPresent=false`(公开 PKCE 客户端) |
| MOA-25 | 端点齐全时登记不触发发现 | 既有行端点完整 | `verify(fetcher, never()).fetch(any())`,端点与 `scopesSupported` 直接来自那行,`issuerSource=CONFIG` |
| MOA-26 | 有行但没端点则补一次发现 | 既有行 authorization/token 为 null | fetch 恰好一次,`updateById` 两次(发现写端点 + 登记写 client),最终同一行两者齐备 |
| MOA-27 | 不知道 issuer 时提示先发现 | `oauthConfig` 无 authorizationServer | 报错含 `run discovery first` 且 `never()` updateById(告诉管理员下一步,而不是丢一句内部状态) |
| MOA-28 | 显式 callbackUrl 覆盖缺省 | callbackUrl=`https://ops.example.com/cb` | 回显与落库都是它,不用生成值 |
| MOA-29 | callbackUrl 非 http(s) 拒绝 | `javascript:alert(1)` | 报错含 `must be an http(s) URL`(`redirect_uri` 要与注册值精确匹配) |
| MOA-30 | client_id 空白拒绝 | clientId="   " | 报 `client_id cannot be empty` 且 `never()` updateById(API 层已有 `@NotBlank`,service 层再钉一次:任何写入口都不能造出脏行) |
| MOA-31 | issuer 结尾斜杠先去掉再当身份 | 配置 `https://as.example.com/` | 回显与落库都是无斜杠的 issuer(复用注册是按字节匹配的,留斜杠等于凭空多出一个授权服务器) |
| MOA-32 | issuer 带 userinfo 拒绝且不回显口令 | `https://admin:s3cret@as.example.com` | 报 `without userinfo, query or fragment`,报错里是 `https://***@as.example.com` 且不含 `s3cret`,零出站零落库 |
| MOA-33 | issuer 带 fragment 拒绝 | `https://as.example.com#main` | 同上——fragment 不会进元数据 URL,存下来只会得到一个谁也确认不了的值 |
| MOA-34 | issuer 超过列宽先拒 | 328 字符 | 报 `longer than the 255 characters it can be stored in`(严格模式外 MySQL 会静默截断一个身份列) |
| MOA-35 | 库里 url 前后有空格仍能发现 | url=`  http://mcp.example.com:3000/mcp  ` | 候选地址按 trim 后的算,`issuerSource=PROTECTED_RESOURCE`(`URI.create` 对带空格的串会直接抛) |
| MOA-36 | 挑战里不加引号的 resource_metadata 也读 | `Bearer error="invalid_token", resource_metadata=http://.../authz.json` | 顺指针取到 issuer(引号是惯例不是规范强制) |
| MOA-37 | 指针不是 http(s) 拒绝 | `resource_metadata="file:///etc/passwd"` | 报错含 `resource_metadata must be an http(s) URL` 且不 insert |
| MOA-38 | 必需的 endpoint 也过同一道 http(s) 关 | `token_endpoint=javascript:alert(1)` | 报错含 `token_endpoint must be an http(s) URL` 与 `advertised by`,不 insert(这个地址正是登记客户端时要往上 POST client_secret 的目标) |
| MOA-39 | 必需的 endpoint 超宽拒绝 | `token_endpoint` 620 字符 | 报错含 `longer than the 500 characters`,不 insert |
| MOA-40 | 可选 endpoint 指歪就丢弃而不是存下来 | `revocation_endpoint=ftp://...` | 发现仍成功、`revocationEndpoint=null`,required 端点照旧落库(缺一个可选能力不是理由,留一个坏地址才是) |
| MOA-41 | 文档不声明 issuer 时无法佐证「文档广告」的 issuer | issuer 来自 protected-resource,元数据没有 `issuer` 字段 | 报 `declares no issuer` 且提示 `set oauthConfig.authorizationServer`,零落库、`verifyNoInteractions(mcpServerMapper)`——这条链路上没有管理员能背书 |
| MOA-42 | 手填 issuer 时文档可以不声明 | 元数据只有两个必需端点 | 接受(管理员已经点名了服务器,这是唯一的逃生口) |
| MOA-43 | 库里 url 不是 http(s) 先拒 | url=`ftp://mcp.example.com/mcp` | 报 `MCP server url must be an http(s) URL`,零出站 |
| MOA-44 | 读不出来的 oauth_config 中止发现 | `oauth_config` 是截断的 JSON | 报含 `cannot be read`,零出站零落库且 `verifyNoInteractions(mcpServerMapper)`——把它当「没配」会在管理员填的 scopes 上写空列表还报成功 |
| MOA-45 | 失败原因拼接有上限 | 四个候选各抛 400 字符的 upstream 消息 | 报错以 `Cannot locate the authorization server` 开头且总长 < 800(这些串要出现在 API 响应体里) |
| MOA-46 | 登记路径重校验存着的 issuer | 配置 issuer=`file:///etc/passwd` | 报 `must be an http(s) URL` 且 `verifyNoInteractions(clientMapper)`——这一路径要往它身上挂 client_secret |
| MOA-47 | callbackUrl 超宽拒绝 | 620 字符 | 报 `longer than the 500 characters` 且 `never()` updateById(注册表里 `redirect_uri` 精确匹配,截断即永远换不到 token) |
| MOA-48 | 配了前端 origin 就以它拼回调地址 | `app.frontend-base-url=https://web.example.com`(末尾带斜杠),发现成功 | 新登记的 `callback_url` 与回显的 `defaultCallbackUrl` 都是 `https://web.example.com/mcp/oauth/callback`——回跳地址指的是浏览器要落下的那个路由,走 nginx 时它与 API 同源,本地开发是两个端口 |
| MOA-49 | 不配前端 origin 时退回 API origin 而不是回空 | 只设 `app.base-url` | 登记的是 `<base-url>/mcp/oauth/callback`(部署在同一个门面下时这就是对的值;回一个空地址等于让 AS 拒掉第一次授权) |

### 5.7 RemoteJsonFetcher(管理员输入引出站请求的唯一出口)

> 真起一个 JDK `com.sun.net.httpserver.HttpServer` 绑 `127.0.0:0`,不用 mock——要测的正是 `java.net.http` 的行为本身(重定向、超时、读上限)。admin 里别处也有出站(如 `McpServerServiceImpl.listTools`、channel、registry),但**目标地址来自管理员/上游文档**的只有这一条路,所以协议白名单、不跟重定向、body 上限与读取截止、元数据服务地址底线都集中在这一处;它们是一道地板而不是一整套策略(地址在这里判过一次,连接时还会再解析一次,DNS 重绑定不在能力范围内)。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| RJF-01 | 正常 JSON | 200 + `{"a":1}` | `ok=true`、`json` 可读、status 原样带出 |
| RJF-02 | 非对象 body(两个用例) | 200 + HTML 文本 / 200 + `[1,2]` | `json=null` 而不是抛:数组与标量同 HTML 一样回答「这个路径没东西」(`takeIf { it.isObject }`) |
| RJF-03 | 非 2xx 不抛,但错误体照样能读 | 404 + `{"error":"not_found"}` | 返回 `ok=false`、status=404 且 `field("error")` 读得到:失败响应的体往往就是理由(RFC 6749 §5.2 把换票被拒的原因写在 400 的体里),`ok` 只由状态决定,把它当元数据用是调用方的主动行为 |
| RJF-04 | 保留 WWW-Authenticate | 401 + 挑战头 | `wwwAuthenticate` 带出全文(RFC 9728 的指针只在头里) |
| RJF-05 | 302 不跟 | 302 + Location,Location 里有计数器 | 计数器为 0 且返回 302——不跟是防重定向绕护栏的硬约束 |
| RJF-06 | 超大响应拒绝 | 70KB | 抛 `RemoteFetchException`,不把大 body 喂给解析器 |
| RJF-07 | 非 http(s) 拒绝 | `file:///etc/passwd` | 抛错,不发起连接 |
| RJF-08 | 没有 host 的 URL 拒绝 | `http:/x`(手输 `http://` 少一个斜杠就是这个形态,`URI.create` 能过但 host=null) | 抛错 |
| RJF-09 | 连不上包装成可读错误 | 端口没人监听 | `RemoteFetchException`,message 含原因(会被拼进发现失败的报错) |
| RJF-10 | 挑战拆成两个头 | 401 + `Bearer scope="mcp:read"` 与 `Bearer resource_metadata="..."` 分两个头发 | 两个都拼进 `wwwAuthenticate`——只取第一个就可能把指针整个丢掉 |
| RJF-11 | 链路本地元数据地址拒绝 | `http://169.254.169.254/latest/meta-data/...` | 抛 `Refusing to request ...`,在建连接之前(link-local 是云元数据服务的地址,会交出这台机器的凭据);环回与私网是**有意**放行的,自建 AS 就在那里面 |
| RJF-12 | 元数据服务主机名拒绝 | `http://metadata.google.internal/computeMetadata/v1/` | 抛 `metadata service host`,不解析名字就拒(解析本身也是一次出站) |
| RJF-13 | 失败信息里抹掉 userinfo | `http://admin:s3cret@127.0.0.1:1/x` | 报错里是 `http://***@127.0.0.1:1/x` 且不含 `s3cret`(`http://user:token@host` 是合法 URL,这些串会进日志和 API 响应) |
| RJF-14 | 表单 POST 与 GET 共用一条收发链路 | 桩服务收 POST 并回 400 + `{"error":"invalid_grant","error_description":"code_verifier did not match"}` | `ok=false`、status=400、`field("error")` 与 `field("error_description")` 都读得到(换票失败的理由只有这一条路能回到页面);同时断言服务端收到的体是 `grant_type=authorization_code&code=a%2Bb%2Fc&client_id=harnax`——`code` 里的 `+` 与 `/` 必须编码,否则一个 base64 形态的 code 会被解成另一个值 |
| RJF-15 | 失败响应没有体 | 400 + 空 body | `ok=false`、`json=null`(网关常把体剥掉);此时调用方只能报「上游拒绝且没说理由」,不能凭空拼一个 |

### 5.8 McpOAuthController(六个 JSON 接口对外的答案)

> 发现与登记的逻辑在 `McpOAuthServiceImpl` 有自己的 49 个用例(§5.6),按人的授权流程在 `McpOAuthUserServiceImpl` 有 77 个(§5.9),这里钉的是**用户在页面上读到的那一段文字**:失败时 `ApiErrors` 的策略(数据库细节不外泄、应用自己写的话原样保留)只有这一层会应用,而这条特性的失败大多是服务层精心写出来的一长句(`run discovery first`、`set oauthConfig.authorizationServer`、`save the OAuth client`)——被控制器包一层前缀就等于把「下一步该做什么」扔掉。这一层接六个管理面接口:发现、登记客户端、`authorize-url`、`exchange`、`status`、`revoke`,六个都返回 `ResultVo`,打桩相应地打在 `McpOAuthService` 与 `McpOAuthUserService` 两个服务上。AS 回调不落在 admin:换票是这六个里的一个普通 JSON 接口,由页面带着 `code`/`state` 主动提交(见 §5.9 的 exchange 组)。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOC-01 | 发现成功回显写入的注册快照 | `discover(1)` 返回一个完整响应 | `code=200` 且 `data` 就是服务层那个对象(`assertSame`)——不在控制器里重新拼一份,免得漏掉 `clientSecretPresent` 这类只此一份的口径 |
| MOC-02 | 服务层写的理由不被前缀吃掉 | `BizException("Cannot locate the authorization server ...: answered 404")` | `code=500`、`message` 与原文逐字相等(有 message 的 `BizException` 原样送出,不套 `Failed to discover OAuth metadata`) |
| MOC-03 | 唯一键冲突不 quoting 库表 | `DuplicateKeyException("Duplicate entry '1-https://as.example.com-' for key 'mcp_oauth_client.uk_...'")` | 命中 `ApiErrors` 的索引名映射,得到「该授权服务器已有客户端注册,请重新发现」,且断言不含 `Duplicate entry`——schema、表名、语句都不该发给浏览器 |
| MOC-04 | 没有 message 的异常落到兜底文案 | `RuntimeException()` | `message` 等于 `Failed to discover OAuth metadata`(兜底只在无话可说时出场) |
| MOC-05 | 请求体不加工就交给服务 | `saveClient(2, request)` | `verify` 捕获 `(eq(2L), captor)`,`assertSame(request, ...)`;回显 `clientSecretPresent=true` 而响应里没有密钥字段 |
| MOC-06 | 「先跑发现」这条指引完整到达 | `BizException` 带 `run discovery first` 与 `oauthConfig.authorizationServer` | 两段都在 `message` 里(前半告诉用户点什么按钮,后半告诉他另一种修法) |
| MOC-07 | 授权链接不经过任何改写就交给浏览器 | `authorizeUrl(1, null)` 返回服务层那个对象 | `code=200` 且 `assertSame(authorize, result.data)`——链接里是 `state` 与 `code_challenge`,控制器再拼一遍就有改坏一次授权的代价 |
| MOC-08 | scope 参数原样下传 | `authorizeUrl(2L, "mcp:read mcp:write")` | `verify(mcpOAuthUserService).authorizeUrl(2L, "mcp:read mcp:write")`(去重与宽度校验是服务层的事,控制器不提前替它决定) |
| MOC-09 | 缺 client_id 时把补救入口送到页面 | `BizException("No client_id is registered for ...: save the OAuth client (POST /api/admin/mcp/3/oauth/client)")` | `code=500` 且 `message` 与原文逐字相等——这句里的 POST 路径是用户此刻唯一能做的事 |
| MOC-10 | 没授权过是正常回答不是错误 | `status` 返回 `authorized=false` | `code=200` 且 `data.authorized` 为假(「还没授权」是页面的正常初始态,报成 500 就多一个红 toast) |
| MOC-11 | 状态读不出来时落到兜底文案 | `RuntimeException()`(无 message) | `message` 等于 `Failed to read the OAuth status` |
| MOC-12 | 本地与上游不一致这句话不能被改写 | `revoked=true`、`upstreamRevoked=false`、message 说「上游没接受撤销」 | `assertSame(response, result.data)` 且 `assertFalse(upstreamRevoked)`——把「本地清了」说成「撤销成功」是在替用户做一个不成立的承诺 |
| MOC-13 | 数据库错误不透传原文 | `QueryTimeoutException("SELECT * FROM mcp_user_credential WHERE tenant_id = 1 ...")` | 得到 `Database operation failed, please check the submitted values and try again`,并断言响应里不含 `mcp_user_credential`(表名与语句不是浏览器该知道的事) |
| MOC-14 | 换票:code 与 state 不改写地交给服务,成功回答原样送出 | `exchange(request)` 返回服务层那个对象(`authorized=true` + scopes + 过期时间) | `code=200` 且 `assertSame(answer, result.data)`——控制器不重拼回答,免得把 `scopes` 或 `accessExpiresAt` 这类只此一份的口径丢掉 |
| MOC-15 | 换票:没有登录态时服务那句拒绝不被前缀包住 | `BizException("Authorization is per user and this request carries no user identity")` | `code=500` 且 `message` 与原文逐字相等(这句是「为什么这次没成」的唯一答案,套上前缀等于把它埋进一句通用错误里) |
| MOC-16 | 换票:无 message 的异常落到兜底文案 | `McpOAuthExchangeRequest()` 全空 + `RuntimeException()` | `message` 等于 `Failed to complete the authorization`(兜底只在无话可说时出场;注意 `authorized=false` 不是异常,它照样回 `code=200`) |

### 5.9 McpOAuthUserServiceImpl(按人的授权码流程)

> 设计口径见 `prod_doc/mcp-management.zh-CN.md` §3.6(英文版同节)。这一层的被测前提是「AS 是一个被打桩的远端」:`remoteJsonFetcher.postForm` 以 URL 为 key 的 `Map<String, RemoteFetch>` 回答,**未登记的地址不会被调用**,所以「该不该发这一枪」本身就是断言。五组分别是发起 13、换票 32、状态 7、撤销 12、访问令牌 13,合计 77;用例编号按 MOU-01… 连续排,但**每条写进它所属那一组的表里**,所以表里的编号不连续而交叉引用始终指得对。其中有四条钉的是「一个用户占不满整份在途预算」「自造的 `error` 不能替别人取消授权」「`state` 已过期时 AS 的拒绝理由照样回给页面」「AS 省略 `scope` 时回答与库里那份一致」。桩里 `jwtUtil.getUserIdFromToken` 给 userId=7、租户 1,并在 `RequestContextHolder` 里放一个带这枚 token 的请求属性——**换票的身份就从这里来**,想测「没有登录态」就 `resetRequestAttributes()`;issuer `https://as.example.com`,MCP url `http://mcp.example.com:3000/mcp`,回跳 `http://localhost:8000/mcp/oauth/callback`(前端路由,不是 admin 的地址);`stateStore` 用**真的** `McpOAuthStateStore` 而不是 mock(它的「取走即失效」正是要被这条链路依赖的行为),aes 桩是 `"enc:" + value` 便于反推明文。

**发起(authorizeUrl,13 个)**

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOU-01 | 授权链接带 PKCE、resource 与登记的回调地址 | 注册行齐备 | 链接的 base 就是注册行的 authorization endpoint,参数含 `response_type=code`、`client_id=harnax-web`、`redirect_uri` 取自**注册行**(不是配置或请求参数)、`code_challenge_method=S256`、43 字符的 challenge、`resource=` MCP url、`scope=mcp:read`(配置里那份)、32 字符 `state`;响应的 `issuer`/`scopes` 与之一致,`expiresIn` 等于 `McpOAuthStateStore.TTL.seconds`,且 `stateStore.size()==1` |
| MOU-02 | 入参 scope 覆盖服务端配置且去重 | config.scopes=`[mcp:read]`,入参 `"mcp:write mcp:read,mcp:write"`(空格与逗号混用、还重复) | 发出去的 `scope` 是 `mcp:write mcp:read`,响应的 `scopes` 是 `["mcp:write","mcp:read"]`(入参赢、分隔符归一、重复去掉) |
| MOU-03 | scope 宽过列宽就拒绝,不是截断存下 | 入参 `"mcp:" + "x".repeat(600)` | 报 `too wide to record`,且 `stateStore.size()==0`——`mcp_user_credential.scopes` 是 VARCHAR(512),发出去换回一个存不下的授权等于凭空造一条读不准的凭据 |
| MOU-04 | 关掉 resource indicator 就不带 resource 参数 | `resourceIndicator=false` | 链接里没有 `resource`(不是所有 AS 都认这个参数,认不认是配置说了算) |
| MOU-05 | 配了 audience 才带 audience 参数 | config.audience 非空 / 为空 | 只有配了才发(凭空带一个 `aud` 会让严格 AS 直接拒) |
| MOU-06 | 发现出的 issuer 带斜杠也能命中注册行 | 配置里写 `https://as.example.com/` | 按无斜杠的 issuer 查注册行并成功(复用注册按字节匹配,发起端必须先把它抹平) |
| MOU-07 | client_id 还没登记就拒绝发起 | 注册行 `clientId=""` | 报 `save the OAuth client` 且 `stateStore.size()==0`(半套配置不该留下一个发不出去的 state) |
| MOU-08 | 没跑过发现就拒绝,并给出 discover 路径 | `oauth_config` 为空 | 报错含 `POST /api/admin/mcp/1/oauth/discover`(把「下一步点哪」写进错误里) |
| MOU-09 | 发现出的行没有 authorization_endpoint 时指向发现 | 注册行 authorizationEndpoint=null | 报 `no recorded authorization endpoint`——有行不等于能发 |
| MOU-10 | 非 OAuth 服务不参与授权码流程 | authType=`STATIC_HEADER` | 报 `BizException`(静态 header 的凭据是服务级的,没有「这个人授权」这回事) |
| MOU-11 | 取不到用户身份就不发起 | token 无效 → userId null | 报 `no user identity`(授权是逐人的;没有身份就没人能消费这个 state) |
| MOU-12 | 待授权状态超过上限时明确拒绝而不是静默丢弃 | 先把 store 填到 `MAX_PENDING`,**且填的是别人的 userId** | 报 `Too many authorizations` 且 `size()` 没有长——上限要说话,不能悄悄丢掉一个 state 让用户对着永远不回来的页面等;填别人的条目是因为调用者自己那份会先撞上 MOU-59,那样这条测的就不是共享预算了 |
| MOU-59 | 一个用户占不满整份待授权预算 | 先塞 `MAX_PER_USER`(5) 条属于调用者自己的 pending | 报 `already have`,`size()` 仍是 5(拒绝不留下任何东西),且 `never()` postForm。500 条是**共享**预算,没有每人一份的上限挡在前面,一个带脚本的登录用户就能把它填满,让其他人在整个 TTL 里都发起不了授权 |

**换票(exchange,32 个)**

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOU-13 | 换票:verifier 与发出的 challenge 对得上,密文落库 | state 里存着 code_challenge(由本用户先发起),AS 回 `access_token=at-1`/`refresh_token=rt-1`/`scope=mcp:read`/`expires_in=3600` | `authorized=true`,回答里的 `scopes` 是**库里记下的那份**(这一条里与 AS 回的同名同值,来源的差别由 MOU-62 与 MOU-15 钉)、`accessExpiresAt` 非空;POST 的表单含 `grant_type=authorization_code`、`code`、`redirect_uri`、`client_id`、`client_secret`(`resolveSecret` 解出的明文 `s3cret`)、`resource`;并用 `challengeOf(表单里的 code_verifier) == code_challenge` 现算校验 PKCE 真的闭环;落库行是 `enc:at-1`/`enc:rt-1`/`STATUS_ACTIVE`/tenantId=1/userId=7/mcpId 对得上/`scopes` 取 AS 回的那份/`lastError=null`/`accessExpiresAt` 非空;`stateStore.size()==0`(state 无论成败都被消费) |
| MOU-14 | 已有的行走更新,且 refresh_token 整体替换 | 库里已有同一 (tenant,user,mcp) 行(带旧 at/rt),AS 这次只回 `access_token=at-2` | `never()` insert,走 updateById;`accessTokenEnc=enc:at-2` 而行上 `refreshTokenEnc=null`——留着旧的是「能用的凭据被一次成功授权换成不能用的」这种最难查的状态;`scopes` 退回发起时请求的那份(AS 没回 `scope` 时按请求算,留 null 等于丢掉刚刚同意过的清单) |
| MOU-15 | AS 发的 scope 宽过列宽:整份不记下来 | AS 回 50 条、共 1190 个字符的 `scope` | `authorized=true` 但落库行 `scopes=null`、`lastError` 含 `is not recorded here` 与那个长度数字、且不含任何一条 scope 名字,`status` 仍是 ACTIVE,**回答里的 `scopes` 同样是空的**(页面读到的是记下的那份):截断会留下一份谁都没授权的半截清单,而调用方没法把 AS 的答案改窄——「不记 + 说清为什么」比「记错」诚实(MOU-03 拒的是**请求**侧,那里调用方能改;这一条是**回答**侧,改不了) |
| MOU-62 | AS 不回 scope:页面读到的与库里记下的是同一份 | AS 只回 `access_token` 与 `expires_in`,**没有 `scope` 字段** | `authorized=true`,落库行 `scopes=mcp:read`(发起时请求的那份),回答里的 `scopes` 也是它。RFC 6749 §5.1 让 `scope` 可选:授的就是请求的时可以不回,而落地页若照 AS 那份空清单显示,几秒后详情页的 `status` 读同一列却显示两条,两处各说一套 |
| MOU-16 | 两次换票撞了唯一键:这次的票写到先落库的那行 | 第一次 `selectByUserAndMcp` 返回 null,`insert` 抛 `DuplicateKeyException`,撞后再返回一行 id=31 | 仍 `authorized=true`,改走 `updateById`,行 `id=31`、`accessTokenEnc=enc:at-2`、status=ACTIVE:同一用户同一服务的两次授权撞在一起时唯一键是对的,把一次真实发生的授权报成数据库失败才是错的(不接住这一枪,用户看到的是「授权失败」而 AS 那边确实同意了) |
| MOU-17 | state 不认识时不查服务也不打 AS,更不写库 | state=`"never-issued"` | `authorized=false`、message 含 `unknown or has expired`,`verify(mcpServerService, never()).getMcpServer(...)`,`verify(fetcher, never()).postForm(...)`,零写入(未知 state 连「它指向哪个服务」都不该被回答) |
| MOU-18 | state 只能用一次 | 同一个 state 换两次,两次都配好可换的桩 | 第一次 `authorized=true`,第二次 `authorized=false` 且话术与「不认识的 state」同一句(重放拿不到第二份 token,也不给出「state 已用过」这种可供探测的区分) |
| MOU-19 | 过期的 state 即使还在表里也不给用 | 手工把条目的过期时间推到过去 | 读不出来且 `size()==0`(「5 分钟」这个 TTL 只有在这里被执行,不然一次没走完的授权会一直悬着) |
| MOU-20 | AS 报 error 时把理由回给页面,不去换 token | 请求体 `error=access_denied`、`errorDescription=The user clicked cancel` | 失败原因含这两段,`never()` postForm(用户在 AS 页面点了拒绝,再拿一个不存在的 code 去换是自伤) |
| MOU-21 | 上游的原话只回显 200 字符 | 请求体 `error=server_error` + 300 个字符的 `errorDescription` | 失败信息里两段都在、但合起来的预算是 200(`assertFalse(contains("x".repeat(188)))`)——AS 啰不起,页面也用不上它剩下的话 |
| MOU-61 | state 已过期时,AS 的拒绝理由照样回给页面 | state 是 `"never-issued"`,请求体带 `error=access_denied` 与一句 `errorDescription` | 失败信息含 `The authorization server refused` 与那句原话,`never()` postForm、零写入。归属比对排在前面(MOU-60),但**这条分支不要求 pending 还在**:既没存下什么、也没什么可烧,把 AS 自己说的理由给页面比一句「请求未知或已过期」有用 |
| MOU-22 | 没有 state 就换不了票 | state 是空白串 | `authorized=false`(身份由 JWT 提供,但 state 是把这份回答配回那次发起的唯一线索;空白在 map 里是能命中的 key,所以它必须被拒) |
| MOU-23 | state 是自己的但 AS 没给 code:不去换票 | `code="   "`,state 有效 | 失败含 `returned no code`、`never()` postForm、`stateStore.size()==0`(这一枪打不打得成,流程到 AS 回答为止就结束了) |
| MOU-24 | 别人会话里发起的 state:拒绝、烧掉、谁的凭据也不动 | store 里一条 `userId=8` 的 pending,调用者是 7,并配好一个换得动 token 的桩 | `authorized=false`、话术含 `another session`;state 照样烧(`size()==0`,留着等于允许同一个 state 再试一次);`never()` postForm、零写入;并断言回答里不含调用者刚交上来的那个 `code`、也不含那条 pending 的 verifier——它们是凭据,不是回显 |
| MOU-60 | 别人的 state 配一个自造的 error:按归属拒绝,不能替对方取消授权 | store 里一条 `userId=8` 的 pending,调用者是 7,请求体**只带** `error=access_denied` 与一句 `errorDescription` | 话术含 `another session` 且**不含** `access_denied`,state 烧掉、零写入、`never()` postForm。归属比对必须排在「AS 报了什么」之前:否则知道别人 state 的人 POST 一个自造的 `error` 就能把对方在途的授权取消掉,而 MOU-24 那条 warn——钓同意的探测器——对这件事一句话都不会说 |
| MOU-25 | 没有登录态就换不了票,而 state 还没被烧 | `RequestContextHolder.resetRequestAttributes()` 后调用 | 抛 `BizException` 含 `no user identity`,且 `stateStore.size()==1`(拒绝排在消费之前——身份都认不出来的一方不该有本事烧掉别人在途的授权) |
| MOU-26 | 换哪个 MCP 由 state 决定:请求里没有可挑的 id | 往 store 塞一条 `mcpId=99` 的 pending | 成功、`verify(mcpServerService).getMcpServer(99)`、落库行 `mcpId=99`——`McpOAuthExchangeRequest` 上没有 id 字段,目标只能来自那次发起,不给调用者挑一个落点 |
| MOU-27 | 成功回答里没有一个字节能装 token | AS 回 `access_token=at-1`、`refresh_token=rt-1`、`scope=mcp:read` | 把响应序列化后断言不含 `at-1`/`rt-1`/`access_token`/`refresh_token` 四个子串(这条接口给页面的只有「成没成、授了什么、什么时候过期」) |
| MOU-28 | AS 不给 access_token 就不算授权成功 | 200 + `{"token_type":"Bearer"}` | 报 `without an access_token`,零写入 |
| MOU-29 | 非 2xx 的换 token 回答带上游状态码与理由 | 400 + `{"error":"invalid_grant","error_description":"code already used"}` | 报错同时含 `HTTP 400` 与 `code already used`(AS 已经把话说完了,不该在中间层被抹平) |
| MOU-30 | AS 发 200 但不是 JSON 对象时也拒绝 | `RemoteFetch(200, null, null)` | 报 `no JSON object`(常见成因是打到了登录页或网关) |
| MOU-31 | token 里的 iss 与发现的 issuer 不符就不落库 | JWT `iss=https://evil.example.com` | 拒绝、零写入(`iss` 是把 token 绑回它所属 AS 的唯一手段,RFC 9728/8414 都要求精确比对) |
| MOU-32 | 响应级 iss 同样按字节严格比 | 不透明 token + 响应体 `iss="https://as.example.com/"` | 结尾多一个斜杠也拒(差一个字节就不是同一个授权服务器) |
| MOU-33 | aud 不含本 MCP 的 resource 时拒绝存这条授权 | JWT `aud=["https://other.example.com"]` | 拒绝、零写入(RFC 8707 的意义就是防这条 token 被别的资源服务用) |
| MOU-34 | opaque token 照常接受:这里不消费 token 内容 | `access_token=enc:v1.abc-123`(不是 JWT) | 成功落库——读不出 `iss`/`aud` 就跳过这两项,但不能因为解析不动就否掉一次已经换成功的授权 |
| MOU-35 | aud 只有一个字符串时也按 resource 比 | JWT `aud="http://mcp.example.com:3000/mcp"`(非数组) | 成功(JWT 规范允许单值,只按数组读会把可用授权判成不可用) |
| MOU-36 | MCP 服务已删或换了租户时不落库 | `mcpServerService.getMcpServer` 返回别的 tenant 的行 | 报 `no longer exists`(请求现在带 JWT,所以走的是服务那个真租户守卫,再额外比一次「行上的租户 == state 里那份」——发起之后服务被挪走或删掉,这一枪就不该落库) |
| MOU-37 | 换票期间服务改回静态 header:授权作废 | 行的 authType 变成 `STATIC_HEADER` | 报 `no longer configured for OAuth`,零写入 |
| MOU-38 | 换票期间服务改了地址:这个 code 是给旧地址换的,不用 | 行 url 被改,`pending.resource` 是旧值 | 报 `changed while the authorization was in progress` 且 `never()` postForm(授权同意是对着旧地址给的,存下换回的 token 等于停一个不能用的凭据) |
| MOU-39 | token 端点丢了指回发现 | 注册行 tokenEndpoint=null | 报 `run discovery again` 且零出站 |
| MOU-40 | 出站网络异常不冒到页面外,只留一句可读的话 | `RemoteFetchException("Connection refused")` | 失败信息可读、零写入(异常原文里可能有内网地址与端口) |
| MOU-41 | 数据库故障不透给浏览器:换成一句可读的失败 | `insert` 抛 `DataIntegrityViolationException("Column 'access_token_enc' cannot be null; statement: insert into mcp_user_credential")` | `authorized=false`、message 含 `Database operation failed` 与 `please start the authorization again`、不含 `access_token_enc`——列名、语句、表名都不是浏览器该知道的事(这一条与 §5.8 MOC-13 分两层:`ApiErrors` 在控制器,而换票失败要包上「重新发起」那句,只能在服务里判) |

**状态(status,7 个)**

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOU-42 | 没授权过的用户读到的是空白而不是错误 | 无行 | `authorized=false`、`status=null`,零写入 |
| MOU-43 | ACTIVE 且未过期才算已授权 | 行 status=ACTIVE、scopes=`mcp:read,mcp:write` | `authorized=true`,`scopes` 拆成两个,`status` 原样带出 |
| MOU-44 | access token 过期后不算可用:刷新还没接 | `accessExpiresAt=now-1min` | `authorized=false` 但 `status` 仍是 `ACTIVE`——行确实还写着它自己那回事,「过期」不等于「已撤销」,把它们混成一个值会误导下一步 |
| MOU-45 | 没有过期时间的行按可用读 | `accessExpiresAt=null` | `authorized=true`(AS 可以不发 `expires_in`,这种 token 不过期) |
| MOU-46 | REVOKED 不复活 | 行 status=REVOKED | `authorized=false` |
| MOU-47 | 只看自己的行 | — | `verify(credentialMapper).selectByUserAndMcp(1L, 7L, mcpId)`(租户与用户都进条件,少一个就是别人的授权) |
| MOU-48 | 非 OAuth 服务不提供授权状态 | authType=`NONE` | 报 `BizException`(不问一个没有授权概念的地址) |

**撤销(revoke,12 个)**

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOU-49 | 撤销优先杀 refresh token,并把两份密文都写回 NULL | 有 rt/at,注册行有 revocationEndpoint | 表单 `token=rt-1`、`token_type_hint=refresh_token`、`client_id`、`client_secret`;行上 `accessTokenEnc`/`refreshTokenEnc`/`scopes`/`accessExpiresAt` 全为 null、status=REVOKED;`revoked=true`、`upstreamRevoked=true`(refresh token 活着的话能换回新 access,只杀 access 等于没撤销) |
| MOU-50 | 没有 refresh token 时拿 access token 去撤销 | 行 `refreshTokenEnc=null` | `token_type_hint=access_token`,上游仍算成功 |
| MOU-51 | AS 不提供 revocation 时说明上游仍然可用 | 注册行无 revocationEndpoint | `revoked=true`、`upstreamRevoked=false`、message 含 `RFC 7009`,`verify(fetcher, never()).postForm(...)`,但本地照写 REVOKED(没有端点不是「已撤销」,也不能因此不清本地) |
| MOU-52 | 注册行被删了:说不清是哪家 AS,不甩锅给 RFC 7009 | 配置里有 issuer 但 `selectByTenantAndIssuer` 查不到行 | `revoked=true`、`upstreamRevoked=false`、message 含 `no longer be resolved` 与 `POST /api/admin/mcp/1/oauth/discover` 且**不含** `RFC 7009`,本地照写 REVOKED:「AS 没有 revocation endpoint」与「解析不出这条授权属于哪家 AS」是两回事,说成后者会把人引向一条改不动的路——这种情形要的是重新发现、把注册行补回来 |
| MOU-53 | 上游拒绝撤销也要清本地,并说清两边不一致 | 403 | `revoked=true`、`upstreamRevoked=false`、message 含 `did not accept`,行写 REVOKED |
| MOU-54 | 撤销端点连不上不整个失败 | `RemoteFetchException("Connection reset")` | `revoked=true`、`upstreamRevoked=false`,行写 REVOKED(拿上游错误当拒绝,会让人反复点撤销还清不掉) |
| MOU-55 | 库里密文是空的就不拿去上游 | at/rt 都为 null,status=NEEDS_CONSENT | `revoked=true`、`upstreamRevoked=false`、message 含 `no stored token`,`never()` postForm(发一个空 token 去上游只会得到一个看不懂的 400) |
| MOU-56 | 换过密钥后密文解不开:本地照清,理由说清是解不开 | 行里有 at/rt,`aesUtil.decrypt` 抛异常 | `revoked=true`、`upstreamRevoked=false`、message 含 `could not be decrypted`,`never()` postForm,行仍写 REVOKED 且两份密文为 null:撤销是用户唯一的出路,AES 密钥轮换不能把一条授权锁死在库里(与 MOU-48 的区别是那里**没有**密文,这里是**读不出**,话术必须分得开) |
| MOU-57 | 没授权过也回答已撤销,但不写库 | 无行 | `revoked=true`、`upstreamRevoked=false`、message 含 `nothing to revoke`,零写入(对「本来就什么都没有」报错,是给用户一个修不了的问题) |
| MOU-58 | 撤销不动别人的行 | — | 只 `selectByUserAndMcp(1L, 7L, mcpId)` 一次(整条链路只写自己那一行,不出现全表 update) |
| MOU-63 | 撤销成功留下一条 REVOKE/OK 审计 | 注册行有 revocationEndpoint、上游回 200 | 恰好一条审计:`action=REVOKE`、`outcome=OK`、`tenantId=1`、`userId=7`、`mcpId`,且 `sessionId=null`(这是人在页面上做的一次决定,不是一次运行花掉的令牌) |
| MOU-64 | 上游拒绝撤销也照样留下这笔审计 | 上游回 403 | 仍有一条 `action=REVOKE`:审计记的是本方做了什么(自己那份已清掉),不是上游配不配合——否则运维可依据的那行会凭空缺失 |

**访问令牌(accessToken,运行时,13 个)**

> 这一组是 agent-service 在运行时打的那一枪,回答决定了一次工具调用带不带人的身份,所以拒绝之间必须分得开:`401` 表示这个人要重新授权,`503` 表示只需要重试。选错要么把一次网络抖动渲染成一张同意页,要么把一条已经死掉的授权留在库里。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOU-65 | 服务行已被删:按「找不到」回答 | `mcpServerMapper.selectById(mcpId)` 返回 null | `BizException`,message 含 `not found`(不拿 null 去比租户,免得空指针冒充一次越权) |
| MOU-66 | 服务不是逐人授权:没有令牌可发 | authType=`STATIC_HEADER` | message 含 `no per-user token`,`verify(fetcher, never()).postForm(...)` |
| MOU-67 | 会话解析不出人:401 并记下这一笔 | `sessionOwnerResolver.resolve("web-1")` 返回 null | `code=401`、message 含 `no user identity`、审计 `outcome=NEEDS_CONSENT`,且 `never()` 查凭据行 |
| MOU-68 | 会话与服务不同租户:403,连凭据都不去查 | 服务 `tenantId=2`、会话归属 `tenantId=1` | `code=403`,`never()` `selectByUserAndMcp`、`never()` postForm(凭据行按服务的租户取键,少了这一刀,B 租户就能花掉 A 租户同一个 userId 的令牌) |
| MOU-69 | 还没授权过的账号:401 指向授权 | `selectByUserAndMcp(1L, 7L, mcpId)` 返回 null | `code=401`、message 含 `not authorized for your account` |
| MOU-70 | 令牌仍在有效期:直接从库里给出,不打 AS | 行 `accessExpiresAt=now+1h` | `accessToken="at-1"`、`expiresAtEpochSecond` 等于该时刻的 epoch、`never()` postForm、零写入;审计 `outcome=OK` 并带 `userId=7` 与 `sessionId="web-1"` |
| MOU-71 | 用户自己撤销过的:拒绝但不改写状态 | 行 `status=REVOKED`、两份密文为 null | `code=401`、message 含 `revoked`、零写入、`never()` postForm(改成 `NEEDS_CONSENT` 会抹掉这行仅存的事实——这个人是主动收回的) |
| MOU-72 | 过期就刷新:表单带 resource 与密钥,轮换后的成对写回 | 行 `accessExpiresAt=now-5min`,AS 回 `at-2` / `rt-2` / `expires_in=3600` | `accessToken="at-2"`;表单 `grant_type=refresh_token`、`refresh_token="rt-1"`、`client_id="harnax-web"`、`client_secret="s3cret"`、`resource=http://mcp.example.com:3000/mcp`(RFC 8707 §2.3:换回来的令牌要重新绑回当初同意的那个 resource);更新行 `accessTokenEnc="enc:at-2"`、`refreshTokenEnc="enc:rt-2"`、`status=ACTIVE`、`lastError=null`;审计 `action=REFRESH` |
| MOU-73 | 刷新回答里没有 `expires_in`:沿用库里那个性命期 | AS 只回 `{"access_token":"at-2"}`,行 `accessExpiresAt=now+30s` | `expiresAtEpochSecond` 与更新行的 `accessExpiresAt` 都等于原来那个时刻(留 null 会被读成「一直有效,直到哪次被拒」,而这一侧永远听不到那次拒绝) |
| MOU-74 | AS 认 `invalid_grant`:标成待重新授权 | 过期行 + `RemoteFetch(400, {"error":"invalid_grant"})` | `code=401`、message 含 `authorize this MCP server again`、更新行 `status=NEEDS_CONSENT` |
| MOU-75 | 上游 5xx 不动用户的授权:那是重试不是重新授权 | 过期行 + `RemoteFetch(503)` | `code=503`、message 含 `unavailable right now`、更新行 `status` 仍是 `ACTIVE` |
| MOU-76 | 客户端密钥解不开:说清是配置坏了,503 可修 | `aesUtil.decrypt("enc:s3cret")` 抛异常 | `code=503`、message 含 `re-save the OAuth client`、`never()` postForm(要重新登记客户端,叫这个人去重新授权是错的——读不出的是那条注册) |
| MOU-77 | refresh token 解不开:这次才真要重新授权 | `aesUtil.decrypt("enc:rt-1")` 抛异常 | `code=401`、message 含 `cannot be decrypted`、更新行 `status=NEEDS_CONSENT`、`never()` postForm |

### 5.10 McpOAuthStateStore(一次性 state 的存放)

> 换票要带登录身份,`state` 只承担一件事:把这份 AS 回答配回那一次发起,是一条一次性线索。它因此不需要集成环境就能测全:真实行为就是「放进去、取一次、取不到第二次、到点自己消失、装不下就拒、数得出某一个用户在途几条」。`McpOAuthUserServiceImplTest` 用的是它的真实例(§5.9),这里测的是它自己的规则。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MOS-01 | consume 是一次性的 | `put("abc", ...)` 后连读两次 | 第一次非空,第二次 null,且 `size()==0`(code 只能用一次,state 也只用一次) |
| MOS-02 | 空白的 state 不算合法查询 | 先 `put(" ", ...)`,再读 `""` 与 `" "` | 两次都是 null——空串与全空格在 map 里都是能命中的 key,而它们不该是一次授权的凭据 |
| MOS-03 | 过期的条目读不出来 | `put("stale", 过期时间在过去)` 后读 | null(`remove` 与「取走即失效」是同一步,顺带把这条丢掉的条目带出表) |
| MOS-04 | put 顺手清掉已过期的,过期条目不占额度 | 灌进 `MAX_PENDING` 条全过期的,再 put 一条新的 | 新条目 put 成功且 `size()==1`(上限管的是在途授权数,不是历史上挂过多少) |
| MOS-05 | 在途数量到上限时拒绝而不是无界增长 | 灌满 `MAX_PENDING` 条未过期的,再 put 一条 | `put` 返回 false 且 `size()` 仍是 `MAX_PENDING`(内存里的东西没有回收站,无界增长就是一次能打完的 DoS) |
| MOS-06 | 未过期就能读到,内容按放进去的算 | put 后读 | 拿回 `tenantId=1`、`userId=7`、`codeVerifier="verifier"`、`expired=false`(换票时要比对归属、要拿去 AS 换 token 的就是这几项) |
| MOS-07 | countFor 只数这个用户的,并且先清掉已过期的 | 三条:自己的、别人的(`userId=8`)、自己的一条已过期 | `countFor(7)==1`、`countFor(8)==1`、`countFor(9)==0`(没有在途授权的人不该被上限挡住),且 `size()==2`——这次计数顺手做的那趟 sweep 已经把过期条目带出表。`MAX_PENDING` 是共享预算,发起侧要靠这个数才能给每人也划一条(§5.9 MOU-59) |

### 5.11 环境参数绑定守卫(`AgentServiceImplTest.EnvBindingGuardTests`)

> 三件事在同一个入口上：绑的工具解析不到、引用的变量解析不到、必填参数留空。三条里任何一条存进去都要到运行期才显形——少一个工具、或者一个参数永远拿到空。所以守卫放在 `updateAgent` 这条写入口，测的也是它（打桩 mapper、`argumentCaptor` 取真正要落库的那份 JSON），而不是私有的 `serializeEnvBindings`。
>
> `resolveBindableTools` 是照着 AGT-18 / AGT-19 那两条 MCP 用例平移的，判据一致（`selectByIds` 已排除 `active=0`，再比租户）；`assertEnvBindingsBindable` 用 `envVariableService.getRowWithinTenant(id)?.tenantId` 比对，理由是引用型快照不存值，id 失效就等于这个参数什么都不发。比的是**租户**而不是创建者：`getEnvVariable` 只认自己建的行（与列表、下拉同口径），绑定解析若跟着收，共享智能体里所有者填的那些变量就成了「运行取得到、表单存不进」。

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| EBG-01 | 绑定的 toolId 解析不到 | `toolList=[{id:9}]`,`agentToolMapper.selectByIds([9])` 返回空 | BizException,message 含 `9`;`batchInsert` never()(下发侧只会在日志里丢掉它,页面上工具还在) |
| EBG-02 | 绑定的 toolId 属别的租户 | selectByIds 返回 tenantId=2 的行,当前租户 1 | BizException;`batchInsert` never()。跨租户 id 不只是「少一个工具」——那行上还有别人的环境参数与密钥 |
| EBG-03 | 引用的 envVarId 解析不到 | 绑定带 `envVarId=7`,`getEnvVariable(7)=null` | BizException,message 含 `7`;`batchInsert` never() |
| EBG-04 | 引用只存指针,回填的掩码不落库 | 绑定带 `envVarId=7` 且 `envValue="******"` | 落库 JSON 含 `"envVarId":7`,不含 `******`,不含 `envValue` 键(即 AGT-13 现在的契约) |
| EBG-05 | 必填工具参数留空 | `agent_tool_env_param` 有 `required=1` 的 `API_KEY`,请求没带绑定 | BizException,message 含参数名 `API_KEY`;`batchInsert` never() |
| EBG-06 | 工具自己的默认值不算填过 | 同上,但 `defaultValue="stored-default"` | 仍然 BizException。`ToolConfigAdaptorImpl` 把 `envParams` 装进了运行时的 `AgentTool`,而**全仓没有一处读它**,`ToolEnvContext` 里只有绑定值 |
| EBG-07 | 引用能顶必填 | `required=1` 的参数带 `envVarId=7`,`getEnvVariable(7)` 返回同租户的行 | 保存通过,`batchInsert` 被调用(运行时按 id 现取,所以「有指针」就是「有值」) |
| EBG-08 | 掩码自填值不算填过 | `required=1` 的参数带 `customValue="sk****ef"` | BizException。含 `****` 的是显示产物,不是值——这条判据同时服务 `secret` 项的预填问题 |
| EBG-09 | MCP 的存储默认值**算**填过 | MCP 绑定 `required=1` 的参数,`mcp_server.env_params` 里有该项 | 保存通过。与 EBG-06 相反的结论、同一条判据：`mcp_server.env_params` 会整份解密成 stdio 进程的环境变量,默认值运行时到得了 |
| EBG-10 | CLI 的必填参数留空 | lark-cli 声明两个 `required=1`,请求只带了 `LARKSUITE_CLI_APP_ID` | BizException,message 同时含 `lark-cli` 与 `LARKSUITE_CLI_APP_SECRET`;`batchInsert` never()。一页可能勾多个 CLI,只说「必填未填」无从下手 |
| EBG-11 | CLI 的必填参数由引用顶 | `required=1` + `secret=true` 的 `TOKEN`,绑定带 `envVarId=7` 且该变量同租户 | 保存通过。引用只存指针、值由下发时现取,所以看得见 id 就算已填 |
| EBG-12 | CLI 的包默认值**算**填过 | `required=1` 的 `REGION`,声明带 `defaultValue="cn"`,请求没带绑定 | 保存通过。与 EBG-06 相反：`mergeCliEnvBindings` 会把包默认值补进下发,它确实到得了沙箱 |
| EBG-13 | CLI 的必填参数只有掩码文本 | `required=1` 的 `TOKEN` 带 `customValue="abc****wxyz"` | BizException;`batchInsert` never()。编辑回来的表单把接口掩码当值提交,存下就是那串字面量 |
| EBG-14 | 平台级工具行可以绑 | selectByIds 返回 `tenant_id` 为平台哨兵的行,当前租户 1 | 保存通过。「工具按平台登记、人人可用」与「引用别人的密钥」是两件事,判据只在后者那边 |
| EBG-15 | CLI 引用别的租户的变量 | CLI 绑定带 `envVarId=7`,该行 tenantId=2 | BizException,message 含 `7`;`cliBindingMapper.batchInsert` never()。CLI 的绑定同样整份并进沙箱环境,少比一次租户就是把别人的密钥送进去 |
| EBG-16 | 引用一个已停用的变量 | `envVarId=7` 且该行 `enabled=0` | BizException,message 含变量名 `OPENAI_KEY`;`batchInsert` never()。停用侧对在用引用返回 null,于是存进去等于表单显示已填、运行拿到空 |
| EBG-17 | 同一个键挂两个来源 | 一个工具两条绑定同为 `OPENAI_KEY`,分别指向 envVarId 7 与 8 | BizException,message 含 `OPENAI_KEY`;`batchInsert` never()。环境参数的唯一键是 `(tenant_id, creator, active_env_key)`，同租户两人各持一个同名键是合法数据,而下发按名字装 map,用谁的凭据全看数组顺序 |
| EBG-18 | 同一个键两条同源**照旧可存** | 两条绑定同为 `API_KEY` 且都不带值（`envValue=""`） | 保存通过,`batchInsert` 被调用。表单是按服务自己声明的参数逐行建绑定的,声明里写两遍同名参数就是这种形状;拒掉等于那类智能体永久存不进,而两个来源的歧义并不存在 |

> EBG-17 与 EBG-18 是一对：守卫只拒「同一个键两个来源」，不拒「同一个键重复出现」。只写前一条，看不出闸是不是开得太宽。

> EBG-10 至 EBG-13 是 CLI 这一类（同一写入口上的第三类配置,2026-09-22 加）。打桩必须成对：`cli.env_params` 存的是密文,声明列表由 `secretFieldEncryptor.deserializeToolEnvEntries` 读出,只桩 `cliMapper` 不桩解密器,守卫读到的是空列表、四条用例全绿得毫无意义。

---

## 6. SkillServiceImpl / SkillRepositoryServiceImpl / SkillSourceServiceImpl

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| SKL-01 | 创建同仓库重名 | getByNameAndRepo 非 null | BizException `Skill name already exists` |
| SKL-02 | 更新改名重名 | 新名在同仓库已存在 | BizException `Skill name already exists` |
| SKL-03 | 更新不改名不查重 | request.name=原名 | 不调用 getByNameAndRepo 查重 |
| SKL-04 | 更新不存在 skill | selectById=null | BizException `Skill not found` |
| SKL-05 | batchSaveSkillsDetailed 空列表 | [] | 返回空的 SkillInstallResponse(savedCount=0),不触发任何 mapper |
| SKL-06 | batchSaveSkillsDetailed 已存在的技能 | 名称已存在 | 从源覆盖写入并记入 `updated`;响应按 installed / updated / failed / flagged 四个桶回答，没有只加计数的静默路径 |
| SKL-07 | batchSaveSkillsDetailed 单条失败不中断 | 第 1 条抛异常,第 2 条正常 | 第 2 条仍插入;失败项进 failed 并带原因,complete=false |
| SKL-10 | 仓库创建重名 | selectByName 非 null | BizException `Repository name already exists` |
| SKL-11 | fetchRemoteSkills 仓库不存在 | selectById=null | BizException `Skill repository not found` |
| SKL-20 | SkillSource 创建重名 | selectByName 非 null | BizException `Source name already exists` |
| SKL-21 | SkillSource 创建校验 loader 配置 | ZIP 类型无 zipPath | validateConfig 抛 IAE,不 insert |
| SKL-22 | GIT 向后兼容配置 | sourceConfig 空但 url/branch 有值 | buildConfigMap 生成 {url, branch};branch 空白时默认 main |
| SKL-23 | 删除 source 级联删 skill | 存在 2 个关联 skill | 每个 skill 各删一次(含 storagePath 内容删除);storage 删除失败仅 warn 不中断 |
| SKL-24 | installSkills 单个 skill 失败不中断 | 第 1 个 save 抛异常 | 第 2 个仍安装(现状行为:失败只 log) |
| SKL-25 | installSkills 已存在则更新 | selectByNameAndRepo 非 null | 走 updateById 而非 insert |

---

## 7. Skill Loader(L1 为主)

### 7.1 ZipSkillLoader(用 @TempDir 构造真实 zip,纯本地)

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| ZIP-01 | validateConfig 缺 zipPath | {} / {zipPath:""} | IAE `ZIP source config requires 'zipPath'` |
| ZIP-02 | zip 文件不存在 | zipPath 指向不存在路径 | IAE `ZIP file not found: ...` |
| ZIP-03 | 单技能目录解析 | zip 内 `my-skill/SKILL.md` | 返回 1 个 AgentSkill,name=my-skill,skillContent=文件内容 |
| ZIP-04 | 多技能目录 | zip 内 a/SKILL.md、b/SKILL.md | 返回 2 个 |
| ZIP-05 | 顶层单包装目录展开 | zip 内 `pkg/a/SKILL.md` | searchRoot 进入 pkg,解析出 a |
| ZIP-06 | 空 SKILL.md 跳过 | 内容为空白 | 该目录跳过 |
| ZIP-07 | 顶层 SKILL.md 回退 | 无子目录技能,仅根 SKILL.md | 返回 1 个,name=根目录名 |
| ZIP-08 | description 提取 | SKILL.md 首行 `# 标题`,第二行正文 | description=正文行;超 500 字截断 |
| ZIP-09 | resources 加载 | `skill/resources/a/b.txt` | resources key=`a/b.txt`(相对路径) |
| ZIP-10 | zip slip 攻击防护 | entry 名 `../../evil.txt` | SecurityException `ZIP entry outside extract directory` |

### 7.2 GitSkillLoader / NpmSkillLoader(仅 validateConfig 做 UT)

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| GIT-01 | url 缺失/空白 | {} / {url:" "} | IAE `Git source config requires 'url'` |
| NPM-01 | packageName 缺失 | {} | IAE |
| NPM-02 | 包名正则 | `@scope/pkg-1.0` 合法;`Evil Pkg`、`pkg;rm -rf` 非法 | 非法抛 IAE(防命令注入,重点用例) |

### 7.3 SkillLoaderRegistry

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| REG-01 | 按类型查找 | "ZIP" | 返回 ZipSkillLoader |
| REG-02 | 未知类型 | "SVN" | BizException `Unsupported skill source type: SVN` |
| REG-03 | 大小写敏感 | "zip" | 现状:抛 BizException(固化行为) |

---

## 8. JwtUtil(L1,反射注入 secret≥32 字节 + expiration)

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| JWT-01 | 生成/解析对称 | generateToken(1, "admin", 5, 1) | getUserIdFromToken=1、getUsernameFromToken=admin、getTenantIdFromToken=5、getIsAdminFromToken=1 |
| JWT-02 | tenantId=null 不写 claim | generateToken(1, "u", null, 0) | getTenantIdFromToken 返回 null(claim 缺失不抛) |
| JWT-03 | validateToken 合法 | 刚生成的 token | true |
| JWT-04 | validateToken 过期 | expiration 设为 1ms,sleep 后校验 | false(不抛) |
| JWT-05 | validateToken 篡改 | token 末位改字符 | false |
| JWT-06 | 错误 secret 解析 | 用另一 secret 的 JwtUtil 解析 | getUsernameFromToken 抛 JwtException;validateToken 返回 false |
| JWT-07 | getIsAdminFromToken 异常安全 | 传垃圾字符串 | 返回 null 不抛 |
| JWT-08 | 弱密钥防护 | secret<32 字节 | generateToken 抛 WeakKeyException(固化 JJWT 行为,提示配置约束) |

---

## 9. CaptchaServiceImpl(L1,可直接 new)

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| CPT-01 | 生成格式 | generateCaptcha() | key 长度 32;imageBase64 前缀 `data:image/png;base64,`;expireTime=300 |
| CPT-02 | 字符集 | 反射读 store 中 code | 4 位且全部 ∈ `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`(无 0/1/I/O) |
| CPT-03 | 校验成功(忽略大小写) | 正确 code 的小写形式 | true |
| CPT-04 | 一次性 | 同 key 校验两次(第一次对) | 第二次 false |
| CPT-05 | 错误 code 也消耗 | 第一次错、第二次对 | 两次均 false(错误尝试同样 remove) |
| CPT-06 | key 不存在 | 随机 key | false |
| CPT-07 | 过期 | 反射改 createTime 为 301 秒前 | false,且 store 中被移除 |
| CPT-08 | code 为 null | validateCaptcha(key, null) | false 且 key 被消耗 |

---

## 10. AesUtil(L1)

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| AES-01 | 加解密往返 | 任意字符串(含中文/emoji/空串) | decrypt(encrypt(x)) == x |
| AES-02 | IV 随机性 | 同一明文加密两次 | 密文不同,均可解密 |
| AES-03 | 密文篡改 | 改 Base64 中间字节 | decrypt 抛 AEADBadTagException(GCM 完整性校验) |
| AES-04 | 非法 Base64 | "not-base64!!!" | 抛异常(固化类型) |
| AES-05 | 短密钥补齐 | 构造 secretKey<32 字节 | copyOf(32) 补 0 后可正常往返 |

---

## 11. EnvVariableServiceImpl

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| ENV-01 | page 参数边界 | pageSize=0/-1/1000,pageNum=0 | 实际 coerce 至 [1,100] 与 ≥1 |
| ENV-02 | 创建敏感变量加密 | sensitive=1 | 存储 envValue=aesUtil.encrypt(原值) |
| ENV-03 | 创建非敏感明文 | sensitive=0 | 存储原值 |
| ENV-04 | 更新越权(IDOR) | creator≠当前用户 或 tenantId≠当前租户 | RuntimeException `No permission to modify this env variable`;delete/toggle 同理 |
| ENV-05 | 更新 value=null 保留原值 | envValue=null | 原(密)值不变 |
| ENV-06 | 更新时敏感标志切换 | 原 sensitive=0,请求 sensitive=1 + 新值 | 新值按加密存储(targetSensitive 决定) |
| ENV-07 | maskValue 边界 | "abc"(≤4) / "abcdefg"(≤8) / "abcdefghijk" | `******` / `a****g` / `abc****jk` |
| ENV-08 | convertToResponse 解密失败 | decrypt 抛异常 | displayValue=`******`,不抛 |
| ENV-09 | getDecryptedValue 不存在 | selectById=null | null |
| ENV-10 | getDecryptedValue 解密失败 | decrypt 抛异常 | null(warn) |
| ENV-11 | listForAgentConfig 过滤禁用 | enabled=0 的记录 | 不出现在结果中 |
| ENV-12 | 被引用的变量删不掉 | `agentMapper.selectByEnvVarRef(1)` 返回一条 `customer-support` | 抛异常,message 同时含数量 `1 agent(s)` 与 agent 名;`deleteById` never()。引用型快照不存值(`AgentServiceImpl.serializeEnvBindings`),删掉变量就等于那个 agent 的这个参数永久为空,而界面区分不出来 |

> 删除接口对外的答案另有一组用例(`EnvVariableControllerTest` 的 DeleteEndpoint,4 条)：成功、服务返回 false、守卫那句「被 N 个 agent 绑着：名字…」必须**原样**到达页面(压成 "Failed to delete env variable" 就等于守卫白做),以及数据库异常必须被 `ApiErrors` 打平成那句通用兜底、不外泄 SQL 片段。写 SQL 侧的 `selectByEnvVarRef` 一次查三张绑定表的 `$[*].envVarId`,`env_bindings` 是 TEXT 所以前置 `CASE WHEN JSON_VALID`,**没有跑过真库**,只按 MySQL 语义读码核对。

---

## 12. TenantServiceImpl / UserTenantService

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| TEN-01 | 创建重名租户 | selectByName 非 null | BizException `error.tenant.name_exists` |
| TEN-02 | 创建时 admin 用户不存在 | selectById=null | BizException `error.user.notfound` |
| TEN-03 | 创建自动绑定管理员 | 合法请求 | user_tenant 插入 role=admin、status=1 |
| TEN-04 | toggleStatus 反转 | 当前 status=1 / 0 | 更新为 0 / 1(无入参,是反转语义) |
| TEN-05 | 删除租户清理成员 | 有成员 | deleteByTenantId 先于 tenantMapper.deleteById |
| TEN-06 | 加入已在租户的用户 | 关联已存在 | BizException `error.user.already_in_tenant` |
| TEN-07 | 移除不在租户的用户 | 关联不存在 | BizException `error.user.not_in_tenant` |
| TEN-08 | 移除唯一管理员被拒 | role=admin 且 status=1 的 admin 数 ≤1 | BizException `error.tenant.cannot_remove_only_admin` |
| TEN-09 | 多管理员时可移除 | admin 数=2 | 删除成功 |
| TEN-10 | 唯一管理员降级被拒 | updateUserRole: admin→member 且唯一 | BizException `error.tenant.cannot_remove_only_admin`(降级保护) |
| TEN-11 | 同角色更新不触发保护 | admin→admin | 直接 updateRole |
| TEN-12 | UserTenantService.updateUserRole 无降级保护 | 唯一 admin 降级 | 现状:直接成功(`UserTenantServiceImplTest` 的 admin→member 用例断言 `updateRole` 被调用并返回 true)—— 与 TenantServiceImpl 行为不一致,**建议评审统一** |

---

## 13. MP 移动端 Service

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| MP-01 | MpAuthService 密码为空 | password=null/blank | BizException `error.user.invalid_credentials` |
| MP-02 | MpAuthService 无自愈 | getPermanentRawKey=null | BizException `Permanent API Key not found for user: ...`(与 web login 自愈行为不同,固化) |
| MP-03 | routerUrl 外部地址优先 | routerExternalUrl 非空 | 响应 routerUrl=external;为空时用内部 url |
| MP-04 | MpSessionService 创建缺 agentId | agentId=null | BizException `Agent ID is required` |
| MP-05 | agent 不可用 | active=0 或 status=0 | BizException `Agent is not available` |
| MP-06 | 创建双写 | 合法请求 | Session(router) 与 MpSession 各 insert 一次;routerSessionId 前缀 `mp-`;两者 routerSessionId 一致 |
| MP-07 | 会话归属校验 | selectByIdAndUserId=null | update/delete 抛 BizException `Session not found or access denied` |
| MP-08 | 更新同步 router 会话 | router 会话存在 | 其 title/name 同步更新;router 会话不存在时不抛 |
| MP-09 | 删除级联 | 合法删除 | router Session、MpSession、MpChatMessage 各删一次 |
| MP-10 | MpMessageService 越权 | 他人 sessionId | getHistory/saveMessages/deleteHistory 均抛 `Session not found or access denied` |
| MP-11 | saveMessages 空列表 | [] | 直接 return,不调用 batchInsert |
| MP-12 | MpUserService 改密旧密码错 | BCrypt 校验失败 | BizException `error.user.invalid_credentials` |
| MP-13 | 改密成功 | 正确旧密码 | updatePassword 收到 BCrypt 哈希(非明文);新哈希可 checkpw 通过 |

---

## 14. TokenStatsServiceImpl

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| TS-01 | overall 为 null 兜底 | getOverallStats=null | response.overall=OverallStats()(全 0),不抛 |
| TS-02 | week 归一化为 day | granularity="week" | 实际调 getTimeSeriesByDay |
| TS-03 | granularity 分支 | hour/month/day | 分别调对应 mapper 方法 |
| TS-04 | 未知 dimensionType | "foo" | 返回空列表,不抛 |
| TS-05 | fillTimePoints 补零(day) | 查询 3 天区间,仅第 2 天有数据 | 输出 3 个点;第 1/3 天 token 全 0、fee=0 |
| TS-06 | fillTimePoints hour 格式 | granularity=hour | timePoint 格式 `yyyy-MM-dd HH:00:00`,起点分钟秒归零 |
| TS-07 | fillTimePoints month 格式 | granularity=month | timeKey `yyyy-MM`,起点日归 1 |
| TS-08 | start==end 边界 | 相同时间 | 至少输出 1 个点 |
| TS-09 | end<start 边界 | 反向区间 | 输出空列表,不抛 |
| TS-10 | fillDimensionTimePoints 补零携带维度名 | model 维度 2 个模型、部分时点缺数据 | 缺失点补 0 且带 modelId/modelName |
| TS-11 | 维度无样本数据 | dimData 为空 | **未覆盖**:`TokenStatsServiceImpl` 的 `response.timeSeriesData!!`(:93 与 :195)在这条路径上抛 NPE,现有 21 条用例都不走到它 |
| TS-12 | mapper 返回 null | `aggregateByModel`=null | **未覆盖**:同文件 :50 的 `modelData!!` 抛 NPE,并列的还有 :55 的 `sessionData!!` 与 :59 的 `agentData!!` |

---

## 15. 评审发现的问题清单(建议随测试一并修复)

1. **TokenStatsServiceImpl**:多处 `!!` 断言(TS-11/TS-12 标的就是这两条未覆盖路径),mapper 返回 null 或维度无数据时 NPE,应改为空集合兜底。
2. **AgentServiceImpl**:`throw RuntimeException("Agent not found")` 出现在 :160、:208、:215 三处而非 BizException,HTTP 层语义变成 500;建议统一为 BizException(AGT-03)。
3. **UserTenantServiceImpl.updateUserRole 与 TenantServiceImpl.updateUserRole 行为不一致**:前者无"唯一管理员降级保护"(TEN-12),存在绕过风险,建议收敛为一处实现。
4. **AuthServiceImpl.login 校验顺序**::55 先 `BCrypt.checkpw`,:59 起才校验验证码,使验证码无法防护用户名枚举与密码爆破;建议验证码前置(AUTH-15 固化现状,调整后同步改用例)。
5. **AuthServiceImpl.logout expireTime**:`plusNanos(expiration * 1_000_000)`(:274)换算易错,建议改 `plusSeconds(expiration / 1000)` 或 Duration.ofMillis,配合 AUTH-25。
6. **JwtUtil 的字段初始密钥只有 22 字节**:HS256 要求 ≥32 字节。`application.yml:81` 与 compose 传给容器的默认值都是 68 字节、够用,所以这条只在不经 Spring 装配直接 `new JwtUtil()` 时暴露——`JwtUtilTest` 因此反射注入 ≥32 字节;建议把初始值也提到 ≥32,或在启动校验时显式给出提示(JWT-08)。
7. **SessionServiceImpl.updateSession**:`request.sessionDescription` 写入 `description` 字段(:253),与 create 路径(写 `sessionDescription`,:192)不对称,建议核对实体字段语义(SES-05)。

## 16. 测试组织与写法约定

- 测试目录按主代码包镜像组织在 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/` 下，`service/impl`、`controller`、`util`、`i18n`、`security`、`context`、`skill/loader` 各有对应套件。`util` 与 `loader` 这一层（`AesUtilTest`、`JwtUtilTest`、`TenantResolverTest`、`UserContextUtilTest`、`RemoteJsonFetcherTest`、`SecretFieldEncryptorTest`、`McpOAuthStateStoreTest`、`McpSessionOwnerResolverTest`、`SkillLoaderTest`）不碰数据库：`McpOAuthStateStoreTest`/`AesUtilTest` 这类是纯对象断言，`SkillLoaderTest` 用 `@TempDir` 造真 zip，`RemoteJsonFetcherTest` 用 JDK 自带 `HttpServer` 起一个本机随机端口的真服务来验出站护栏。
- 静态依赖用 Mockito 的 `mockStatic(...)`，作用域收在一个 `use {}` 块里——仓里两处都这么写：`WechatLoginServiceTest` 对 `ILinkClient.builder`，`EmailToolBoxTest` 对 `Transport`（每用例各开一把）。`UserContextUtil` 这类走实例注入，不 mock 静态。`RequestContextHolder` 在 `@BeforeEach` 放一个带 `Authorization: Bearer <token>` 的请求属性，`TenantContext` 在 `@BeforeEach/@AfterEach` set/clear。
- 用例编号（AUTH-01 等）只存在于本文档：全仓没有任何 `@DisplayName` 带编号前缀，测试方法用 `@DisplayName` 写行为描述（如 `updateUserRole - Downgrade from admin to member`）。文档与代码互查靠「类名 + 被调方法名」，两边都用中文/英文动作名而不是编号。
- `@Disabled` 在仓里只有一处：`EmailToolBoxIntegrationTest` 的类级标注，理由写明「requires real SMTP server」，它属于「要真环境才跑」而不是「已知缺陷留案」。已知缺陷在本文档标 **未覆盖** 并给出抛点位置（如 §14 的 TS-11/TS-12 指到 `TokenStatsServiceImpl` 的 `!!` 行），不靠注释掉的测试传递。

## 17. 测试基线与 Mockito-Kotlin 匹配器陷阱

基线来自一次实跑：`mvn -o -pl harnax-admin,harnax-agent/harnax-agent-service -am test`（13 个 reactor 节点，1 分 20 秒），配下方「容器包排除」那条 `-Dtest`。表里的每一列都取自这一次跑：

| 模块 | 顶层测试类 | 用例 | 失败 / 错误 | 跳过 |
|------|-----------|------|------------|------|
| `harnax-admin` | 93 | 2150 | 0 / 0 | 0 |
| `harnax-agent/harnax-harness-core` | 27 | 326 | 0 / 0 | 0 |
| `harnax-agent/harnax-agent-service` | 14 | 186 | 0 / 0 | 0 |
| `harnax-protocol` | 4 | 103 | 0 / 0 | 0 |
| `harnax-auth` | 9 | 97 | 0 / 0 | 0 |
| `harnax-agent/harnax-tools-sdk` | 4 | 34 | 0 / 0 | 0 |
| `harnax-tools-external/harnax-tools-buildin` | 3 | 24 | 0 / 0 | 2 |
| `harnax-agent/harnax-agent-utils` | 1 | 19 | 0 / 0 | 0 |
| `harnax-common` | 1 | 15 | 0 / 0 | 0 |
| `harnax-entity` | 1 | 2 | 0 / 0 | 0 |
| **合计** | **157** | **2956** | **0 / 0** | **2** |

口径边界要写明三件事：

- 「顶层测试类」与报告文件数不是一回事：一个 `@Nested` 内部类各写一份 `TEST-*.xml`，`harnax-admin` 的 93 个顶层类对应 554 份文件。
- 跳过的 2 条全部来自 `EmailToolBoxIntegrationTest`（类级 `@Disabled`，要真 SMTP 才跑，见 §16），其余模块 0 跳过。
- 这条命令覆盖 13 个节点，其余模块不在表里：`harnax-scheduler`、`harnax-session-router`、`harnax-channel-service`、`harnax-client` 各有自己的跑法。表里 `harnax-entity` 那 2 条只是不碰容器的 `MapperXmlParseTest`；同一模块把容器包放开的完整口径是 `mvn -o -pl harnax-entity test` —— 25 个测试类 / 84 份报告 / **247 条全绿**（5 分 3 秒），其中 24 个 `*MapperTest` 各起一个 MySQL 8 容器，共 245 条。

数这几个数有四条判据，每条都会静默把数错：

1. 类里有 `@Nested` 时，顶层 `<testsuite>` 恒报 `tests="0"`，真实数量只在 `<testcase>` 元素里 —— 一律 `grep -c '<testcase>'`。
2. per-class `.txt` 里那行 `Tests run:` 同样不能汇总：带 `@Nested` 的外层类那儿就是 0。
3. `target/surefire-reports` 会留着上一趟（包括 IT）的报告：`mvn test` 根本不跑 `**/*IT.class`，照单全数会把 `admin.it.*IT` 的用例算进单测。可复现口径是「只取本次实跑新写的 `TEST-*.xml`、排除 `*admin.it.*`、数 `<testcase>`」。
4. `grep -c '@Test'` 会连类上的 `@Testcontainers`、`@TestMethodOrder` 一起数（`harnax-entity` 那 24 个 `*MapperTest` 每个虚高 1）—— 按 `^\s*@Test\s*$` 逐行匹配。

另有两条与构建产物相关的判据：

- **改掉一个 `@Nested` 内部类的名字，Kotlin 的增量编译不会删掉它旧的 class 文件**。两个 class 并存而外层类的 `InnerClasses` 属性只剩新名字时，JUnit 平台在**发现阶段**抛 `IncompatibleClassChangeError: ... disagree on InnerClasses attribute`，surefire 报「TestEngine with ID 'junit-jupiter' encountered a critical issue during test discovery」—— 整个 `harnax-admin` 模块一个用例都没跑，却看不到任何失败断言。它不是代码缺陷，是构建产物陈旧；认出来的办法是看报错里那个内部类名在源码里还在不在，处理办法是删掉 `harnax-admin/target/test-classes`（或直接 `mvn clean`）再跑。改构造函数参数之后同理。
- **依赖 Testcontainers 的包要从 `-Dtest` 里排除**，否则它们在单测阶段以 `ExceptionInInitializerError → Could not find a valid Docker environment` 红掉：`-Dtest='!com.agnetix.harnax.mapper.**,!com.agnetix.harnax.admin.it.**,!com.agnetix.harnax.channel.service.it.**' -Dsurefire.failIfNoSpecifiedTests=false`，配 `-am` 用。被排除的那一面是 `harnax-entity` 的 24 个 `*MapperTest`（245 条用例）与 `harnax-admin` 的 41 个 `com.agnetix.harnax.admin.it.*IT`，两者都要容器里的真 MySQL，走 `mvn -pl harnax-entity test` 或 `-Pintegration-test` 单独跑。

Mockito-Kotlin 匹配器与 Kotlin 类型侧另有四条判据：

1. **Mockito 对可空 `Long` 返回 0,而不是 null**。`JwtUtil.getTenantIdFromToken(token): Long?` 在 Kotlin 里编译成 `java.lang.Long`,Mockito 的默认应答把包装类型也当基础类型,于是 `tenantFromToken() ?: DEFAULT` 里的 `?:` 永不生效,当前租户变成 **0**,一次撞红 20 多个与租户无关的断言(`page`、`getMcpServer`、`deleteMcpServer` 全在)。生产侧本来不会有这个问题(真实现无声明时返回 null),但代码里 `tenantId > 0` 才是「有声明」的正确读法,补上之后测试与生产口径一致。用例侧要显式 `whenever(jwtUtil.getTenantIdFromToken(any())).thenReturn(null)` 才能表达「令牌没带声明」。
2. **给服务/控制器加构造参数,会让用 `@InjectMocks` 的整个测试类全红**(`InjectMocksException: Parameter specified as non-null is null ... parameter mcpStdioPolicy`),不是只红用到它的那一个用例——`InternalApiControllerTest` 与 `McpServerServiceImplTest` 就是这一类文件。两个文件里都已有注释写明了这条纪律,加参数时必须同步补 `@Mock`,并且 mock 的返回值若被非空参数消费(如 `BizException(refusalReason())`)还要额外桩一个非 null。
3. **跨模块的 `val` 不能 smart cast**:`harnax-entity` 里 `McpDetailDto.authType: String?` 在本模块 `if (!it.isNullOrBlank())` 之后仍然只是 `String?`,直接赋值报 Assignment type mismatch。先落到一个局部 `val` 再判断。
4. **KDoc 里写路径通配会吞掉注释**:`/** ... \`/api/admin/internal/**\` ... */` 里的 `/*` 在 Kotlin 里是**嵌套块注释的开头**(KDoc 注释可嵌套),于是整个文件的注释往后不闭合,报错却是「Unclosed comment」和「Missing '}'」,指向的行号毫无关系。文档字符串里的路径写成 `/api/admin/internal` 或改用行注释。

`harnax-entity` 的 `*MapperTest`(24 个类、245 条用例)与 `harnax-admin` 的 `com.agnetix.harnax.admin.it.*IT`(41 个类)都用 Testcontainers 起一个真 MySQL 8，Docker 可用时直接跑，不需要额外环境变量：

```
mvn -o test -pl harnax-entity
mvn -o verify -pl harnax-admin -am -Pintegration-test -Dit.test=<类名> -Dtest=<类名> \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

四条判据：

- `-am` 必带，否则兄弟模块从 `~/.m2` 的旧 SNAPSHOT 解析，跑出来的红绿都不是工作区这一份。
- `-Dtest` 与 `-Dit.test` 要给同一个值（或一个故意不匹配的值）：surefire 那份 `<exclude>**/*IT.class</exclude>` 会被命令行的 `-Dtest` 覆盖，不给就整套单测先来一遍。
- 两个 `failIfNoSpecifiedTests=false` 都要给，缺一个会在别的模块以「no tests matching」红掉。
- 只跑某几个 IT 类时，`harnax-channel-service` 的 `com.agnetix.harnax.channel.service.it.**` 也要一并排除，否则它在单测阶段以 `ExceptionInInitializerError → Could not find a valid Docker environment` 出现（当 Docker 确实不可用时）。

两条容器侧既有纪律：mapper XML 的注释体里不能出现连续连字符（XML 注释不允许），因为三个服务的 `mybatis.mapper-locations` 都是 `classpath*:mapper/*.xml`，一个文件解析不了就建不起 `SqlSessionFactory`，整片测试一起红；`harnax-scheduler` 的 IT 由该模块 pom 固定 fork 成 `-Duser.timezone=UTC`，因为 Testcontainers 的 MySQL 是 UTC，而 mapper 用 `NOW()` 去比 Java 侧 `LocalDateTime.now()` 写进去的 `DATETIME`，开发机 +08:00 时所有「很老」的种子行都会被记成 8 小时后的未来。

注意 `harnax-entity` 的 Mapper 测试用的是手写 `schema-test.sql`，**根本不碰 Flyway**，所以它们全绿不能证明迁移可执行。可执行的证据在 `harnax-admin` 的 `*IT`：它们真起 `HarnaxAdminApplication`、`spring.flyway.enabled=true`，打到一个全新 MySQL 8，把 admin 的 schema 基线整份重放一遍——生成列 `active_client_id`、`issuer` 与 `callback_url` 上的 `utf8mb4_bin` 这类 MySQL 8 专属形状都在重放范围内。`schema-test.sql` 与基线之间的漂移由 `SchemaBaselineDriftIT` 守，而它只在 `-Pintegration-test` 下跑。

防复发:`MapperXmlParseTest`(纯 JVM,不依赖 Docker)把 classpath 上所有 mapper XML 过一遍解析,并单独检查注释体里的 `--`——解析器只会报「not well-formed」,不指出是注释问题,所以这一步要自己查。

Mockito 与断言口径侧另有三条判据，都会以「整片红」而不是「一条红」的形态出现：

1. **裸 Mockito 匹配器在 Kotlin 里触发 NPE**:`ArgumentCaptor.capture()`、`ArgumentMatchers.eq(...)`、`isNull(...)` 返回平台类型,Kotlin 会插入 `checkNotNullParameter` 非空校验,抛 `NullPointerException: capture(...) must not be null`;一次失败还会污染同一 `mock()` 所在的后续用例,表现为 `UnfinishedVerification` / `InvalidUseOfMatchers`。改用 mockito-kotlin 等价物:`argumentCaptor<T>()` + `.firstValue`、`org.mockito.kotlin.eq`。最容易复发的形状是文件里写了 `import org.mockito.ArgumentMatchers.*`——通配 import 里的裸 `eq` 优先级低于任何显式 import,所以这类文件必须单独写一行 `import org.mockito.kotlin.eq`。
2. **`any()` 不匹配 null**:mockito-kotlin 的 `any()` 编译为 `ArgumentMatchers.any(T::class.java)`,对可空参数实际收不到值,于是桩永不生效、被测试方法走进真实分支。可空参数用 `anyOrNull()`,例如 `AgentTaskControllerTest` 里 `crudService.page(...)` 的五个过滤参数。
3. **桩覆盖不全或断言与实现口径不符**:`ApiKeyServiceImpl.initSystemKeys()` 遍历 `listOf("channel-service", "scheduler")`,只 stub 一个服务时另一个仍会插 Key(用例口径见 KEY-37)。同一条判据的形状是:被测 SQL 的 `WHERE ... status IN (3, 4)` 这类「只回写仍在跑的那条」语义,拿已完成状态的种子行去断言更新返回 1 必然对不上——要插一条运行中的行断言返回 1,再补一条已完成行断言返回 0 且原值不动。时间边界过滤同理:`LocalDateTime.toString()` 带时分秒,拿它和 `"2026-07-02"` 做字典序比较会随格式漂移,判据写成 `isBefore` / `isAfter`。

错误信息的口径也是一条约定而非一次修复:`AgentTaskController`(harnax-scheduler)的 7 个 catch 块都拼 `"Prefix: ${e.message}"`,与 `TokenStatsController` / `InternalApiController` 同一种写法——用例按这个约定断言。

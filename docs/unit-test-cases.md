# harnax-admin 单元测试用例设计

> 范围:harnax-admin 模块核心业务逻辑的单元测试(UT)用例设计。
> 与集成测试(harnax-admin/src/test/kotlin/.../admin/it/,`-Pintegration-test` 运行)互补:集成测试覆盖 HTTP 端到端链路,本文档聚焦 Service/Util 层的分支逻辑,mock 掉 Mapper 与外部依赖。
> 框架建议:JUnit 5 + MockK(Kotlin 项目首选,可 mock 静态/顶级函数)+ kotlin.test 断言。

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
| SES-03 | 创建复制 agent 字段 | agent 含 systemPrompt/modelId/mcpList/skillList | Session 实体对应字段一致;sessionId 前缀 `web-`;status=1、isPublic=0 |
| SES-04 | 更新不存在会话 | selectById=null | BizException `Session not found` |
| SES-05 | 更新字段映射 | 传 sessionDescription | 写入实体 description 字段(命名不一致点,固化行为) |
| SES-06 | chat config 会话不存在 | selectBySessionIdAndStatus=null | BizException `Session not found or disabled` |
| SES-07 | chat config Boolean→Int | enableThink=true, enableSearch=false | 实体 enableThink=1、enableSearch=0 |
| SES-08 | chat config 更新失败 | updateById 返回 0 | BizException `Failed to update session configuration` |
| SES-09 | convertToResponse mcpList 脏 JSON | mcpList="not-json" | 不抛;response.mcpList 为空列表 |
| SES-10 | convertToResponse skillList 含非法 ID | skillList="1,abc,2" | abc 跳过;只解析 1、2 |
| SES-11 | mcp/skill 已删除时跳过 | getMcpServer/getSkill 返回 null | 该条不出现在响应中,不抛 |

---

## 5. AgentServiceImpl

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| AGT-01 | 创建成功默认值 | status/isPublic 不传 | status=1、isPublic=0、tenantId=TenantContext ?: 1 |
| AGT-02 | 子调用异常被包装 | mapper.insert 抛 SQLException | RuntimeException,message 前缀 `Failed to create agent:` |
| AGT-03 | 更新不存在 agent | selectById=null | RuntimeException `Agent not found`(注意非 BizException,前端会拿到 500 语义 —— 建议列为改进项) |
| AGT-04 | 更新时 list 为 null 不覆盖绑定 | toolList=null | 不调用 saveToolBindings;传空列表时则清空绑定 |
| AGT-05 | deleteAgent 级联 | 任意 id | 顺序验证:tool→mcp→skill 绑定删除,最后 agentMapper.deleteById |
| AGT-06 | saveToolBindings 先删后插 | toolList=[{id:1}] | InOrder: deleteByAgentId → insert |
| AGT-07 | toolList 条目缺 id 跳过 | [{id:null}, {id:2}] | 只插入 toolId=2 |
| AGT-08 | needConfirm 合成规则 | 工具实体 needConfirm=0,binding 请求 needConfirm=true | 最终 binding.needConfirm=0(工具禁用确认优先);工具 needConfirm=1 时按请求值 |
| AGT-09 | enableSkip 默认 | 不传 | 存 "false"(字符串) |
| AGT-10 | skillList 解析 | "1,,x,3" | 只插入 1、3;空串与非数字跳过 |
| AGT-11 | serializeEnvBindings 空入参 | null / 空列表 | 返回 null |
| AGT-12 | serializeEnvBindings customValue 优先 | envVarId=5 且 customValue="abc" | JSON 中 customValue=abc |
| AGT-13 | serializeEnvBindings envVarId 快照 | envVarId=5,envValue=null | envValue 取 getDecryptedValue(5) |
| AGT-14 | parseEnvBindingsJson 脏数据 | "not-json" | 返回 null,不抛 |
| AGT-15 | parseEnvBindingsJson 敏感变量掩码 | envVar.sensitive=1 | displayValue="******" |
| AGT-16 | parseEnvBindingsJson 变量已删除回退快照 | getEnvVariable=null | 使用存储的 snapshotValue |

---

## 6. SkillServiceImpl / SkillRepositoryServiceImpl / SkillSourceServiceImpl

| 编号 | 用例 | 输入 | 期望 |
|------|------|------|------|
| SKL-01 | 创建同仓库重名 | getByNameAndRepo 非 null | BizException `Skill name already exists` |
| SKL-02 | 更新改名重名 | 新名在同仓库已存在 | BizException `Skill name already exists` |
| SKL-03 | 更新不改名不查重 | request.name=原名 | 不调用 getByNameAndRepo 查重 |
| SKL-04 | 更新不存在 skill | selectById=null | BizException `Skill not found` |
| SKL-05 | batchSaveSkills 空列表 | [] | 返回 0,不触发任何 mapper |
| SKL-06 | batchSaveSkills 已存在计数 | 名称已存在 | 跳过插入但 savedCount+1(现状行为,建议评审是否合理) |
| SKL-07 | batchSaveSkills 单条失败不中断 | 第 1 条抛异常,第 2 条正常 | 第 2 条仍插入;返回 1 |
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
| TEN-12 | UserTenantService.updateUserRole 无降级保护 | 唯一 admin 降级 | 现状:直接成功 —— 与 TenantServiceImpl 行为不一致,**建议评审统一**(测试先固化现状并 @Disabled 标注待定) |

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
| TS-11 | 已知缺陷:维度无 sample 数据 NPE | dimData 为空 | 现状抛 NPE —— **建议修复**;测试用 @Disabled("BUG-待修复") 记录 |
| TS-12 | 已知缺陷:mapper 返回 null NPE | aggregateByModel=null | `modelData!!` NPE —— 同上标记 |

---

## 15. 评审发现的问题清单(建议随测试一并修复)

1. **TokenStatsServiceImpl**:多处 `!!` 断言(TS-11/TS-12),mapper 返回 null 或维度无数据时 NPE,应改为空集合兜底。
2. **AgentServiceImpl.updateAgent**:"Agent not found" 抛 RuntimeException 而非 BizException,HTTP 层语义变成 500;建议统一为 BizException(AGT-03)。
3. **UserTenantServiceImpl.updateUserRole 与 TenantServiceImpl.updateUserRole 行为不一致**:前者无"唯一管理员降级保护"(TEN-12),存在绕过风险,建议收敛为一处实现。
4. **SkillServiceImpl.batchSaveSkills**:git 拉取后过滤不到目标 skill 时不报错且 savedCount 仍 +1,调用方无法感知失败(SKL-06)。
5. **AuthServiceImpl.login 校验顺序**:先校验用户/密码、后校验验证码,使验证码无法防护用户名枚举与密码爆破;建议验证码前置(AUTH-15 固化现状,调整后同步改用例)。
6. **AuthServiceImpl.logout expireTime**:`plusNanos(expiration * 1_000_000)` 换算易错,建议改 `plusSeconds(expiration / 1000)` 或 Duration.ofMillis,配合 AUTH-25。
7. **JwtUtil 默认 secret 仅 23 字节**:HS256 要求 ≥32 字节,默认配置下启动即抛 WeakKeyException;建议在配置校验时显式给出提示(JWT-08)。
8. **SessionServiceImpl.updateSession**:`request.sessionDescription` 写入 `description` 字段,与 create 路径(写 sessionDescription)不对称,建议核对实体字段语义(SES-05)。

## 16. 落地建议

- 建议在 `harnax-admin/src/test/kotlin` 下按包镜像组织:`service/impl/AuthServiceImplTest.kt` 等;Loader/Util 属 L1 优先落地(约 40 个用例,零 mock 成本)。
- MockK 处理静态依赖:`mockkStatic(::loadSkillsFromGit)`、`mockkObject(UserContextUtil)`;`TenantContext` 在 `@BeforeEach/@AfterEach` set/clear。
- 已知缺陷用例统一 `@Disabled("BUG: 见 docs/unit-test-cases.md #15-x")` 标注,修复后启用。
- 用例编号(AUTH-01 等)写入测试方法 `@DisplayName`,便于文档与代码互查。

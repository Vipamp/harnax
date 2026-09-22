# CLI 插件包登记模型（安装命令 + 技能 → 包 + 技能）· 设计规格

- 日期：2026-09-21
- 状态：已实现（§2/§3/§6/§7 已按落地结果回写：`checkCommand` 示例、包目录默认值、登记分支、两个摘要的语义）
- 取代：`2026-07-26-sandbox-cli-plugin-design.md`（内置 CLI 插件那一套，本规格将其退役）
- 部分取代：`2026-07-28-cli-management-sandbox-design.md`（自定义 CLI 的手工安装命令形态，本规格将其退役）
- 面向使用方的说明文档：`prod_doc/cli-plugin-package-design.zh-CN.md` / `prod_doc/cli-plugin-package-design.en-US.md`（平台侧链路与不变量）
- 面向包作者的规范：`prod_doc/cli-package-spec.zh-CN.md` / `prod_doc/cli-package-spec.en-US.md`（字段字符集、上限、拒绝清单、打包与自检、lark-cli 实例）
- 关联缺口：`prod_doc/skill-management.zh-CN.md` TODO-9 四条、TODO-10

## 0. 需求与现状错位

用户给出的重新定义：**不区分内置 CLI 与自定义 CLI，统一用 `cli` 实体**；CLI 的扩展方式改成「像 tools 一样」——把 CLI 插件包放进 admin 的包目录，admin 启动时自动解析、登记进数据库、并把二进制包上传 MinIO。

现状与之错位的四处：

1. **两套并行实体**：自定义走 `cli` + `agent_cli_binding`（真功能），内置走 `cli_plugin` + `agent_cli_plugin_binding`（后者无读者）。内置那一侧只有一个布尔环境变量开关，页面已因此改成只读。
2. **技能这一半是死的**：`cli_skill_binding` 要求技能预先存在于 `builtin-cli-skills` 仓库，而该仓库对技能域只读（`SkillServiceImpl.requireWritableRepo`），内容只由 V12 种子写入且**只有一条** `harnax-cli`。结果：自定义 CLI 配不上自己的技能（出不来），内置 CLI 的技能装载不到任何 agent（进不去）。
3. **安装靠自由文本 shell**：`install_script` 非空即入库，原样拼进 Dockerfile 并以 root 执行；且必须依赖构建期外网下载，而本机与容器直连 GitHub 不通，等于镜像构建随机失败。
4. **`harnax-cli` 是一堆特例的集合**：`sandbox-plugins/build.sh` 交叉编译 → `Dockerfile.sandbox` COPY → `init.sh` → `HarnaxCliPluginInitializer` → V12 种子技能 → 同名约定。它没有按任何公开标准实现，因此也没有参考实现。

## 1. 决策清单（已定稿）

| # | 决策 | 理由 |
|---|---|---|
| D1 | CLI 的唯一来源是**插件包**，手工新增入口关闭 | 与 tools 同形（`agent_tool` 整表只有一种来源，V29 已取消 type 与逐工具可见性） |
| D2 | 包放进 admin 的包目录，`ApplicationReadyEvent` 时解析登记，产物上传 MinIO | 用户指定。信任边界由「谁能往发布物里放文件」决定，不需要 Web 上传与审核流 |
| D3 | 一个包 = 一个 CLI，且**必须自带技能**（`skill/SKILL.md` 必需） | 技能是事实上的必需项；无技能的 CLI 装上模型也不知道，属于半成品 |
| D4 | 技能与 CLI 是 1:1，`cli.skill_id` 直指，`cli_skill_binding` 表退役 | 「这条技能是谁的」不需要查第三张表 |
| D5 | 自由 `install_script` 列**删除**（表当前 0 行，无损） | 保留即等于两套身份共存 |
| D6 | 安装语义 = `payload/` 文件树 + 可选 `deps.apt` 声明清单，无任意 shell | 落点可静态校验；纯 payload 型插件构建期零网络 |
| D7 | 插件标准必须有**激活槽位** `runtimeEnv`，harnax-cli 靠它成为插件 #1 | 它的 url+secret 是每会话配置注入，不是安装动作。没有这一槽位，「harnax-cli 也按标准实现」不成立 |
| D8 | 包从目录消失 ⇒ **硬删 `cli` 行**，级联清 `agent_cli_binding`/`agent_skill_binding`/`team_skill_binding` 并软删自带技能行（`active=0`），WARN 日志列出被移除的包名与行 id | 对齐 `BuiltinToolAutoRegistrar.pruneMissingBuiltinTools` 的先例。不引入 `source_missing` 新状态 |
| D9 | `status` 与 toggle 接口**保留**，且成为真开关 | 下发侧读 `status`（`InternalApiController` 已跳过停用项），技能随行同步。这正好补上 TODO-9「要做成真开关」那条 |
| D10 | `cli` 转平台级表：去掉 `tenant_id` / `creator` / `is_public` | 与 D1 同源。包是全平台发布的，租户维度无内容可表达；技能行租户沿用现状 |
| D11 | `builtin-cli-skills` 仓库行与常量名**不改**，只改其语义说明 | 改名要牵动 V12/V14 已应用迁移、`BuiltinRepository` 常量、前端常量与 `isBuiltin` 判定，纯 churn。它从此的含义是「CLI 插件自带技能的托管仓库」 |
| D12 | V12 种子的那条 `harnax-cli` 技能**不删**，由登记器在首次启动时按包内容覆盖 | 已应用迁移不可改（Flyway checksum），删除反而制造「登记失败即无技能」的窗口 |
| D13 | 包目录非空而 MinIO 未启用 ⇒ 登记启动失败并明确报错；目录为空则不要求 MinIO | 降级分支等于多一套要测的路径与多一份真相源。但只在「有包要存却无处可存」这个真正的矛盾上报错——为用不到 CLI 的部署强行摊派一套外部依赖没有道理 |
| D14 | 技能附带文件（`skill/assets/**`）落在沙箱内的方式沿用技能域既有投影设计，**本规格不解决 TODO-10 的通用投影问题** | 范围控制。v1 的包规范允许但运行侧不承诺 |
| D15 | **两个摘要分工**：`packageDigest`（整包 sha256）管存储键与登记比对，`payloadDigest`（`payload/` 文件树 + `deps` 的规范化 sha256）管镜像指纹 | 否则只改 `SKILL.md` 也会换 tag、重建所有保活容器。见 §4.2 |

## 2. 包规范

### 2.1 文件形态

文件名 `<name>-<version>.harnaxcli.zip`，一个目录可放多个包（多个 CLI）。`name` 是 `plugin.yaml` 里的 `name`，不是仓库名——harnax-cli 这个仓库发布的 CLI 叫 `harnax`，包名因此是 `harnax-1.0.0.harnaxcli.zip`。文件名字干必须与 `plugin.yaml` 的 `name` 逐字相等：放错名的包会顶着另一个 CLI 的身份登记进去，而目录里根本看不出出错。

```
harnax-1.0.0.harnaxcli.zip
├── plugin.yaml            # 唯一元数据来源
├── skill/
│   ├── SKILL.md           # 必需（D3）
│   └── assets/**          # 可选
└── payload/               # 打进镜像的文件树，包内相对路径 = 容器内绝对路径
    └── usr/local/bin/harnax
```

`payload` 用「目标路径即包内路径」而非用户写 `RUN`，是为了让平台能在登记期静态校验落点。`payload/` 下文件的**可执行位由 zip 内的 unix mode 外部属性携带**（打包时即 `chmod +x`），平台不改写它。

### 2.2 `plugin.yaml`

```yaml
name: harnax                 # 必填，^[a-z][a-z0-9-]{1,63}$，全平台唯一
version: 1.0.0               # 必填，^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$
description: 平台管理命令行     # 必填
checkCommand: harnax --version # 必填，构建后在镜像内执行，非零即判定失败
deps:                         # 可选，只有 apt 这一个段
  apt: [ca-certificates]      # 纯 [a-z0-9+.-] + 可选 =版本，可钉版本
envParams:                    # 可选，沿用现有 env_params 声明语义与加密存储
  - { envParamName: HARNAX_PROFILE, required: false, secret: false, defaultValue: prod }
runtimeEnv:                   # 可选，D7 的激活槽位；平台在容器创建时注入
  HARNAX_URL: ${platform.adminUrl}
  HARNAX_TOKEN: ${platform.internalToken}
```

无 `install` 段——payload 解到哪就是包内路径，`deps` 是唯一额外的安装动作。

`checkCommand` 是在构建出的镜像里用 `/bin/sh -c` 跑的，所以它必须是镜像内确实存在的命令。harnax-cli 用 `harnax --version` 而不是 `harnax version`：cobra 只生成了 `--version` 标志，写子命令会在每次镜像构建时以 127 失败。

`envParams` 的条目就是 `ToolEnvParamEntry`（与工具域共用同一个类），字段名以它为准：`envParamName` / `required` / `secret` / `defaultValue`。`secret: true` 且带 `defaultValue` 直接拒绝登记——值的归宿是库里的密文列，不该写进发给所有人的 zip。

`runtimeEnv` 的值只能是一个字面量，或者一整个 `${platform.<slot>}` 占位符；目前平台只发布 `adminUrl` 与 `internalToken` 两个槽位。字面量与占位符混排（`http://${platform.host}`）被拒绝，因为那样就没有任何东西保证注入值是 URL 里安全的一段。

### 2.3 不变量

- I1 `name` 唯一即身份：新版本覆盖同一行，不并存多版本（`agent_cli_binding` 绑的是 CLI 不是版本）。
- I2 `packageDigest = sha256(zip)` 是包的版本身份，用于 MinIO 对象键与「是否需要重新登记」判定；`payloadDigest` 是镜像指纹（D15），二者互不替代。注意 `packageDigest` 吃的是 zip 的**字节**，所以同样的内容重新打包一次它就会变（zip 存了时间戳），而 `payloadDigest` 不变——结果是对象重传、行更新，容器不动。这不是 bug，正是 D15 存在的理由。
- I3 `payload/` 内每条目标路径必须**只有一种拼法**：绝对路径、空段（`usr//bin/x`）、`.` 段（`./etc/x`、`usr/./bin/x`）、`..` 段、NUL、段内冒号一律拒绝。这条真正咬住的不是逃逸，而是**两套代码对同一个名字给出不同答案**：门内按文本判拒绝清单，门外 `resolveInside` 拼到目标目录后再归一化，`usr/./bin/docker` 恰好文字上过、落地后落在清单上；同一份唯一性还兜住摘要，`payload/./usr/bin/x` 与 `payload/usr/bin/x` 是一个文件的两条记录，两条都进指纹则镜像 tag 不再描述镜像内容。拒绝清单 `etc/`、`root/`、`usr/bin/docker`、`usr/local/bin/docker`、`bin/su`、`usr/bin/su`、`sbin/`、`usr/sbin/`、`var/run/docker.sock` 按**路径段**比较（带斜杠的是目录前缀、不带斜杠的是完整路径），字符串前缀匹配会误伤 `etc2/x`、`sbin2/x`、`usr/bin/su-client`。另有五条同源约束：拒绝符号链接（否则一个链接就能把上面的路径校验绕过去）、拒绝 setuid/setgid/sticky 位、`plugin.yaml`/`skill/`/`payload/` 之外的游离条目一律拒绝、同名条目在包里出现两次即拒绝（`readBytes` 给第一条、`extractTree` 留最后一条、摘要把每条都算进去——一个名字必须只意味着一个条目）、整棵 payload 至少要有一个可执行位（否则这个包装不出任何命令）。
- I4 解析期**只读**包内容，绝不执行包内任何文件。
- I5 `cli.status = 0` ⇒ 技能行同步为 0，二者不单独生效。唯一例外：启用**不解除内容扫描的隔离**——`toggleCliStatus` 在启用方向会先 `isQuarantined(skillId)`（把库里那份 `skillmd` + resources 重扫一遍，读不出来按隔离处理），仍命中规则就只改 `cli` 行、技能行留 0。否则一个 kill switch 就顺手批准了扫描器拒绝的内容。
- I6 包内 `skill/SKILL.md` 的技能名强制 = `plugin.yaml` 的 `name`。技能页里看不到一条独立的「harnax 技能」来源，只能看到它随 CLI 存在。
- I7 `payloadDigest` 的计算必须是**规范化**的：只取 `payload/` 下的**文件**条目（目录条目不进指纹——`zip -r` 会为每个目录各写一行），按目标路径字典序，对每条 `path|mode|sha256(content)` 串联后再取 sha256，再拼上排序后的 `deps.apt`。打包时间戳、文件在 zip 内的顺序、打包器额外记下的目录项都不得影响结果。

各内容源的解析期上限，超限即拒绝：`plugin.yaml` 64 KB（同时受 YAML `codePointLimit` 与 `nestingDepthLimit=20` 约束）、`skill/SKILL.md` 1 MB、单个 `skill/assets/` 文件 512 KB 且该类合计 4 MB、payload 2000 个文件 / 解包后 512 MB、`deps.apt` 50 条。文本三类走 `CliPackageArchive.readBounded`，**边读边累加、一到上限就返回 null**——中央目录里的 size 由打包器自己填、流式写入时干脆是 `-1`，不能当依据。

## 3. admin 侧改动

### 3.1 数据层

`cli` 表（Flyway **V35**，本设计落地的迁移到 **V38** 为止）：

- 新增：`skill_id BIGINT NULL`、`package_digest CHAR(64) NOT NULL DEFAULT ''`、`payload_digest CHAR(64) NOT NULL DEFAULT ''`、`package_object VARCHAR(256) NOT NULL DEFAULT ''`（MinIO key）
- 删除：`install_script`、`tenant_id`、`creator`、`is_public`（D1/D5/D10）
- 删除表：`cli_skill_binding`、`cli_plugin`、`agent_cli_plugin_binding`
- 保留：`name`、`description`、`version`（= manifest 的 `version`，不再手填）、`check_command`、`env_params`、`status`、`active`、时间戳
- `agent_cli_binding` 不动（`env_bindings` 仍是 per-agent 的 env 快照）
- 连带改 mapper：`selectCliList` 去掉 `currentUsername`/`tenantId` 两个参数，新增 `upsertCliPackage`（对齐 `agentToolMapper.upsertBuiltinTool`；`ON DUPLICATE KEY UPDATE` 覆盖 manifest 声明的每一列，**唯独不动 `status`**——它是操作员的 kill switch，D9）
- `schema-test.sql` 必须手工镜像本次列变更，否则 mapper 测试以 `BadSqlGrammar` 红

随本设计一起落地的三条收尾迁移：

- **V36** 删 `agent_skill_binding.env_bindings`：技能绑定除了一对 id 没有别的内容，运行侧从不解析它；同一列在 tool / mcp / cli 三张绑定表上是活的（下发时才展开占位符），所以技能表里留一份死的只会误导读表的人。
- **V37** 软删 V12 手工种下的 `harnax-cli` 技能行：包模型下 CLI 的技能就是包内 `skill/SKILL.md`，`harnax` 包按包名登记自己的行，种下的那行再没有读者（`GET /internal/builtin-skills` 已删，`SkillBindingResolver` 又禁止直接绑定内置仓库），而管理面对内置仓库写入是封死的，只能靠迁移清掉。守卫落在 `cli.skill_id` 上而非名字上：已有包占用同名时保留它的行。
- **V38** 退役 `package_digest = ''` 的遗留 `cli` 行（用过旧 CLI 页面的安装才有）：这类行由手工表单写入，`install_script` 列已随 V35 消失、空的 `payload_digest` 也命名不出任何镜像，而 D2 之后除了登记器没人写这张表——它们是死行。清掉的方式：先 `DELETE FROM agent_cli_binding`，再 `active = 0` 并把 `name` 改写成 `<前 110 字符>#retired-<id>`（`uk_cli_name` 也覆盖软删行，不加后缀则同名新包 upsert 时会覆盖这具尸壳而不是开自己的行）。技能行不动：手工 CLI 可以绑运维自己仓库里的技能，那些技能仍归技能页面管。**为什么必须由迁移做**：`pruneMissingPackages` 的第二道刹车只把 `package_digest` 非空的行当候选（否则「包下架」与「本模型写不出来的行」分不清，会连带硬删别人写的行），所以遗留行永远轮不到 prune 去清。

### 3.2 登记器 `CliPackageAutoRegistrar`

`harnax-admin/.../registrar/`，与 `BuiltinToolAutoRegistrar` 同目录同形状（`CliPluginAutoRegistrar` 删除）：

```
@EventListener(ApplicationReadyEvent::class)
fun syncCliPackages()
  ├─ package-dir 未配置 → INFO 后返回
  ├─ package-dir 配了但不存在 → ERROR 后返回：这是卷没挂，此时既不该登记、更不该 prune
  ├─ 扫 ${harnax.cli.package-dir}/*.harnaxcli.zip 按文件名排序（为空 → WARN 后继续）
  │    非空而 MinIO 未启用 → 抛错终止启动（D13）
  ├─ 第一轮：每个包 CliPackageParser.parse(file) → manifest + packageDigest + payloadDigest
  │    └─ 校验失败 → 跳过该包，ERROR 日志含文件名与全部原因，不影响其余包，不阻断启动，failCount++
  ├─ 第二轮：按 manifest 的 name 分组后登记（先解析完再登记，因为两个文件可能声明同一个 name）
  │    ├─ 一个 name 多个包 → 登记 version 数值最高的那个，其余跳过并 ERROR
  │    │    （version 按 `[._+-]` 逐段切、数字段按数值比，所以 1.0.10 > 1.0.9；
  │    │     预发布后缀只是又一段文字，于是 1.0.0-rc1 判得比 1.0.0 新——两份本就同名，只回答谁上架）
  │    ├─ 同 name 同 version 两个文件 → 两份都不登记（没有任何依据说操作员要哪个），failCount++
  │    │    名字因此进不了 registered，由 failCount 挡住 prune：已在架的那行不会被当成「包下架」删掉
  │    └─ 通过 → packageDigest 或 packageObject 与库中不同才 putObject()
  │             对象键 = `<name>/<packageDigest>.harnaxcli.zip`，桶 = minio.cli-package-bucket
  │             → upsertCliPackage() → upsert 托管仓库技能行 → 回写 cli.skill_id
  │             （技能行 status 跟随已有 cli.status，操作员关掉的 kill switch 不因换包被复位；
  │              每次登记都重扫内容，仍命中规则就写 0——换一版干净的正文才会恢复跟随，I5）
  └─ pruneMissingPackages(registered, failCount)  // D8，对齐 pruneMissingBuiltinTools
```

prune 有三道刹车，因为这是全平台唯一会删掉「某个 agent 可能还在用」的 CLI 的地方：本轮有任何包读取失败就不 prune（没有可信的在架集合）；**只有 `package_digest` 非空的行才是候选**——非空是登记器亲手写过的证据，它因此永不删除自己没写过的行（旧页面的遗留行由 V38 退役，见 §3.1），「待删 / 剩余」这个比例也只在这些行之间算（否则一堆遗留行会算进分母，把一个空目录该踩的刹车顶掉）；`stale.size >= live` 时拒绝执行并 ERROR——那更像卷没挂而不是操作员退掉了半个平台。

实测的边界后果：目录里只剩 2 个包时删掉其中 1 个，`stale=1 / live=1` 正落在被拒的一侧；合法退役要求**留在架上的包比待删的更多**（3 个包删 1 个即 `1 < 2` 通过）。这条刹车因此给 prune 定了下界：一次运行要能删就得至少留 2 个包在架上，所以登记器写的行**永远删不到只剩 1 行**——退掉倒数第二个 CLI 只能走 SQL。级联真正跑完时的可见状态：`cli` 行消失（硬删）、`agent_cli_binding` 无残留、`skill` 行还在但 `active=0`（技能域统一软删，`selectByNameAndRepo` 只认 `active=1`，所以同名包回来时会新建一行而不是复用）。

包级隔离（单包坏不拖垮整批）沿用 `BuiltinToolAutoRegistrar` 的做法。

admin 侧新增配置项 `minio.cli-package-bucket`（`AdminMinioConfig` 只有 endpoint 与两把 key，没有 bucket 概念），与 harness-core 的 `harness.minio.cli-package-bucket` 同默认值 `harnax-cli-packages`；两侧都读同一个 `MINIO_CLI_PACKAGE_BUCKET`，因为对不上时表现是 agent-service 报对象不存在，很难一眼归因成「两个服务写读不同的桶」。

### 3.3 解析器 `CliPackageParser`

纯函数式：输入 zip 文件，输出 `ParsedCliPackage(manifest, packageDigest, payloadDigest, skillMd, skillDescription, skillAssets, payloadFiles)`，不落库不碰网络。单独可测，是本次测试的主要落点（§7）。所有规则一次跑完、把所有问题拼成一条异常文本一起报出——操作员改一处就要重启一次去撞下一处，成本太高。校验面覆盖：文件名字干必须以 `<name>-` 开头、manifest 的未知键与重复键、四个字符集正则、`SKILL.md` 存在且与 `name` 同名、payload 路径规则（I3）、重名条目、`plugin.yaml`/`skill/`/`payload/` 之外的游离条目、各内容源上限（§2.3 末段）。

**实现坑（必须记）**：解负载时不能直接用 `java.util.zip.ZipFile` 拿 mode——它不暴露 unix 外部属性，会把所有文件解成 `0644`，二进制进镜像即不可执行，而 `checkCommand` 报的是 126（Permission denied）而不是「文件不存在」，很难归因。需按 `ZipEntry.extra`/external attributes 自行解析，或改用能读该位的实现。

落地时把「包的形状」和两个摘要的计算从 admin 里抽了出来，放在 `harnax-common` 的 `com.agnetix.harnax.common.cli.CliPackageLayout`：文件名后缀、三个入口常量、四个字符集正则（`NAME_PATTERN`/`VERSION_PATTERN`/`APT_PACKAGE_PATTERN`/`DIGEST_PATTERN`）、拒绝清单、`checkRelativePath`/`checkPayloadPath`/`resolveInside`、`MAX_PAYLOAD_FILES`/`MAX_PAYLOAD_BYTES`、`packageDigest`/`payloadDigest`。原因是 agent-service 侧要用同一套规则重算 `payloadDigest`、按同一套路径规则解包并 `COPY`，两份实现只要漂一点，产出的就是一个「跟登记内容不匹配却没有任何一方报错」的镜像。共享的是判据本身，所以它也第一次有了自己的测试（`harnax-common/src/test/kotlin/.../CliPackageLayoutTest.kt`，§7）。

mode 由 `ZipCentralDirectoryModes` 从中央目录的 external attributes 读，且只采信 `versionMadeBy shr 8 == 3`（UNIX）的条目——别的来源声称的 mode 没有共同语义，宁可在登记期以「打包器没写 unix mode」拒绝，也不要在镜像里悄悄给成 `0644`。因此打包侧要 `chmod 755` 之后再 `zip -X`。

### 3.4 内部下发（`InternalApiController`）

- `cliDetails[]` 每条新增 `skill: { name, description, skillmd }`（从 `cli.skill_id` 取）、`packageObject`、`packageDigest`、`payloadDigest`、`deps`、`runtimeEnv`；去掉 `installScript`；`checkCommand` 保留。
- **删除** `GET /api/admin/internal/builtin-skills`：新模型下技能随 `cliDetails` 内联，该端点失去读者。
- `envBindings` 合并逻辑不变（`mergeCliEnvBindings`）。
- 跳过 `status = 0` 的行为不变——它从此是真开关（D9）。

### 3.5 对外接口与清理

- 保留 `GET /api/admin/clis/page`、`GET /{id}`、`PUT /toggle/{id}`、`GET /{id}/related-agents`、`GET /{id}/related-sessions`
- 删除 `POST /api/admin/clis`、`PUT /update/{id}`、`DELETE /{id}` 及 `CliService` 对应方法（手工形态关闭）
- `toggleCliStatus` 现有的「有启用中 agent 绑定即拒绝停用」守卫**移除**：D9 的语义就是 kill switch，拦停操作与目的相反；影响面改由 `related-agents` 预览承担
- 删除 `CliPluginService`/`CliPluginController`/`cli_plugin` 全套，含 `PUT /api/admin/cli-plugins/toggle/{id}`
- `BuiltinRepository.CLI_SKILLS` 常量保留，注释改成托管仓库语义（D11）

## 4. 运行侧改动（`harnax-harness-core`）

### 4.1 产物取回

`MinioConfig` 增加 `cliPackageBucket`（默认 `harnax-cli-packages`）并进 `ensureBuckets()`。新增 `CliPackageStore.materialize(packageDigest, objectKey) → Path`，把包负载留在本地磁盘，按 `packageDigest` 分片：`<cacheDir>/<packageDigest>/`，目录存在且 `<packageDigest>.complete` 标记在则直接返回。

两个入参都来自 admin 下发的 spec，不是本进程造出来的，所以先复核再使用——它们紧接着就要当路径分量用：`packageDigest` 必须整串匹配 `DIGEST_PATTERN`，`objectKey` 必须非空；形状是下载完成之前唯一还能判的东西，判完才轮得到哈希。报错只说字段不回显原值，否则拒绝一个 `../../etc/passwd` 就是把调用方的值写进日志。

取回与解包三步，每步都不假设上游已经做过：

- 下载边读边算 sha256（`DigestInputStream`），落盘后与期望摘要比对，不符即抛「存的对象不是 admin 登记的那份」；
- 解 `payload/` 走 `CliPackageArchive.extractTree`，**逐条目重判 §2.3 的 I3 清单**（拒绝前缀、唯一拼法、逃逸），不采信「admin 读包时已经判过」；目录条目先去掉尾部那个使它成为目录的斜杠再判——登记侧只对文件条目判 I3，两侧对同一个名字必须给出同一个答案，而 `zip -r` 会给每个中间目录都留一条记录，判据若不认这个斜杠，真打包器产出的包就能登记却无法安装；`written == 0` 视为坏包；
- 解到 `cacheDir` 内的 `.staging-*` 临时目录，`ATOMIC_MOVE` 进位后再写完成标记——所以对外只见「完整树或不出现」，失败仅遗留 staging 并由 `finally` 清掉；有树无标记就是上次中断的残留，先递归删掉再建（`rename` 不肯落在非空目录上，不清就永远修不好这个 digest）。

一个 digest 一把 JVM 内锁（`locks`），两个会话同时起手不会各拉一遍几十 MB。产物目录只作为 `docker build` 的输入，这里不执行任何包内内容。

已核实：`docker build <本地路径>` 是 CLI 在客户端把目录打 tar 流发给 daemon 的，**不需要宿主可见的共享卷**，agent-service 自己的容器文件系统即可。缓存目录需要可写卷或至少容器生命周期内稳定，否则每次新会话都要重拉几十 MB。

### 4.2 `CliImageBuilder`

- `resolveImage(cliSpecs)`：空集直接返回 `baseImage`（一条 docker 命令都不发）；否则**先复核再动手**——`validate()` 按 §2.2 的字符集重判 `name`/`version`/两个摘要/`depsApt`，不假设拿到的那行是登记器写的（这些字符串接下来要进 Dockerfile 与目录名，`version` 里一个换行就是一条多出来的构建指令），报错只点名被拒字段、不回显其值。然后算 tag，命中 `knownImages` 直接返回，未命中才 `docker image inspect`，仍不存在才在该 tag 的 JVM 内锁下 `double check + build`，建成即记进 `knownImages`。
- 指纹：`sha256(baseImage + sorted("cliId:version:payloadDigest"))`，取前 12 位，tag 前缀不变（`harnax-sandbox:cli-<12hex>`）；按 `cliId` 排序保证同一套 CLI 与列举顺序无关。
  - 换二进制 ⇒ `payloadDigest` 变 ⇒ tag 变 ⇒ **新会话**直接落在新 tag 上。已在保活的会话**不会**换镜像（实测）：`KeepAliveSandboxManager.getOrCreate` 的 Scenario 0 在比镜像之前就返回内存里的沙箱，而 `scanAndRestore` 会在启动时把既有容器重新收养进同一张表，所以那段「仅镜像变化才重建」的分支只在内存没有该会话、Docker 里却有同名容器时才走到——正常保活路径上基本碰不到。要让在用会话用上新二进制，只能等它被空闲回收，或 `docker rm -f agentscope-sandbox-<sessionId>`。
  - 只改 `SKILL.md` ⇒ `packageDigest` 变（对象重传、行更新）但 `payloadDigest` 不变 ⇒ **不重建容器**，新会话的提示词生效。这是 D15 存在的唯一理由。
- `generateDockerfile`：

```dockerfile
FROM <base>
# CLI: harnax 1.4.0 (payload sha256:ab12…)
COPY <packageDigest>/ /                                     # payload 文件树按原路径落到根
RUN apt-get update && apt-get install -y --no-install-recommends <deps.apt> \
    && rm -rf /var/lib/apt/lists/*                          # 仅当有 deps.apt
```

  - `COPY` 目标必须是 `/`，不是 `./`——沙箱镜像的 `WORKDIR` 是 `/workspace`，写到 `./` 会把整棵树落进工作区而不是文件系统。
  - 不再需要 `RUN chmod +x`：`COPY` 保留源文件 mode，mode 由 §3.3 的解压器负责。
  - `deps.apt` 进 `RUN` 之前 `distinct().sorted()`，同一条 `apt-get` 尾部带 `rm -rf /var/lib/apt/lists/*`，只在需要它的那一层清理。
- 构建上下文是**每次构建现造的临时目录**，把每个负载树按 `COPY` 引用的那个 `packageDigest` 名字硬链接进去（跨文件系统时退化为保留属性的复制），`docker build` 之后 `finally` 删掉。上下文不能直接指向缓存目录：CLI 每次构建都会把整个上下文打 tar 传给 daemon，指过去等于把这台机器取过的每一个包都发一遍。
- 构建后逐个 `checkCommand`（空值跳过）在镜像内执行，非零 ⇒ `docker rmi -f` + `IllegalStateException`（语义不变）。
- 移除 `installScript` 分支。

### 4.3 装配与配置

- `CliSpec`：删 `installScript`，增 `packageObject`、`packageDigest`、`payloadDigest`、`depsApt`、`checkCommand`、`runtimeEnv`。
- `AgentSpecResolver.withCliSkills`（设计时名 `withBuiltinSkills`，实现时随数据源一起改名）：数据源从「调 `/internal/builtin-skills` 拿全量」改为「取 `cliDetails[].skill`」。去重、同名遮蔽分区、未下发告警三段逻辑保持。
- `HarnessAgentLauncher`：删除 `cliPluginsEnabled` 分支、`pluginImage`、`HarnaxCliPluginInitializer` 挂载，`baseImage` 恒为 `sandbox.image`；`cliEnv` 组装时追加 `runtimeEnv` 解析结果（`${platform.adminUrl}` / `${platform.internalToken}` 由现有配置填入）。`!isLead` 门控保留。
- 删除 `application.yml` 的 `harness.sandbox.cliPluginsEnabled`、`pluginImage` 两项及 docker-compose 中 `SANDBOX_CLI_PLUGINS_ENABLED`；删除 `sandbox-plugins/Dockerfile.sandbox` 与 `init.sh`。`sandbox-plugins/` 目录本身保留：`build.sh` 与 `Dockerfile.custom-sandbox` 只构建默认基座 `harnax-sandbox:py-node`（`ARG BASE_IMAGE=python:3.11-slim`），原有的 CLI 插件基座分支已移除。

## 5. 前端（`harnax-webui`）

- `/context/cli` 撤掉双 Tab，单表只读，与工具页同形。列：名称 / 描述 / 版本 / 自带技能 / 健康检查 / 包摘要（`packageDigest` 前 12 位）/ 状态。「自带技能」是进那份技能详情页（`/context/skill/detail/:id`）的入口；启停是唯一会改状态的动作。
- 页首不放说明条：包怎么发布是文档的事，界面只呈现登记结果。
- 删除 `CliForm.tsx`、`BuiltinCliTable.tsx`、`src/services/ant-design-pro/cliPlugin.ts`；`cli.ts` 去掉 create/update/delete 三个函数。
- agent 表单 `CliConfigPanel` 不变（技能 Tag 的语义现在是真的了）；会话详情 CLI 面板不变。
- 文案中英同步，key 沿用 `pages.cli.*`，被删组件的 key 一并清。验证闸门只有 `npm run build` + `npx @biomejs/biome lint`。

## 6. 包的生产与部署

- `harnax-cli/Makefile` 的 `package` 目标：交叉编译（`GOOS`/`GOARCH` 可覆盖，默认 linux/amd64）→ 按 §2.1 布局组装 → `dist/<name>-<version>.harnaxcli.zip`。三处不是随手写的：
  - 包名里的身份取自 `plugin.yaml` 而不是 git tag 或仓库名——登记器认的是 manifest，用 git 推出来的版本会和库里的 `version` 说的是两套话。
  - Go 编译带 `-buildvcs=false`。VCS 戳（revision、dirty 标记）会打进二进制，不关掉的话任何一次无关提交都会改 `payloadDigest`，连带把所有保活沙箱重建一遍。
  - `chmod 755` 之后 `zip -X`。可执行位靠 zip 的 unix mode 外部属性携带（§3.3），`-X` 少存额外字段，让同一内容重打包的 `packageDigest` 至少在本机上是可复现的。
- CLI 在容器里没有家目录可写 `~/.harnax`，因此改为读 `HARNAX_URL`/`HARNAX_TOKEN`。落点是 `config.EffectiveCredentials()`：令牌存在时直接返回一份 `Mode=internal` 的凭据，复用 CLI 原本就有的内部密钥通路（`Bearer <secret>`），不新增一种认证模式。优先级 `--server-url` > `HARNAX_URL` > profile，且**显式 `--profile` 时不认 env**——否则一条残留的环境变量会把 prod profile 悄悄改写到别的地址。原先负责注入的 `init.sh` 已删除，这层职责由包的 `runtimeEnv` 承担。
- 货架 `cli-packages/dist/` 同时是投递口：`cli-packages/build.sh` 只把有来源的包补齐进来（自研的 `harnax-cli` 走 `make package`，第三方的包各由 `cli-packages/<name>/build.sh` 打），**不清架**，手工丢进来的 zip 原样留下、一起投放；包脚本也不清自己名字的旧版本。同名两份在 `build.sh` 里裁决：留 manifest `version` 高的那份，落选的移进 `cli-packages/dist/.superseded/`（是移走不是删除——手工投的包没有源码可重建）。它只有两处失败：货架为空（admin 会带着零条登记启动）、同名同版本两份（没有任何一侧能判断该留哪个，绕过脚本时 admin 的做法同样是两份都不登记）。Go 不在时若架上已有 `harnax-*` 只 WARN 并保留原样，什么都没得留才失败。
- `admin` 镜像 `COPY docker-new/dist/cli-packages/ /home/harnax/cli-packages/`（配置键 `harnax.cli.package-dir`，环境变量 `HARNAX_CLI_PACKAGE_DIR`）。compose 把宿主机 `docker-new/dist/cli-packages/` 以**只读 bind mount** 盖在这个路径上，所以那份就是唯一一份：不再有「镜像里是新包、登记读到旧包」的失同步，`docker cp` 进容器则被挂载直接拒掉；单独 `docker run` 这个镜像时，`COPY` 进去的那份仍是要登记的来源。多出的 `docker-new/dist/cli-packages/` staging 层不能省——`.dockerignore` 忽略 `dist/` 只放行 `docker-new/dist/`。
- 三个入口脚本（`build.sh`、`deploy-all.sh`、`deploy-service.sh admin`）都先跑 `cli-packages/build.sh`，再 `mkdir -p docker-new/dist/cli-packages/`、清掉其中上一轮的 `*.harnaxcli.zip`、从货架整份复制，然后才 `docker build`：`Dockerfile.admin` 的那条 `COPY` 在源目录缺失时是硬失败，而不是构建出一个不带 CLI 的镜像。清的是构建输入那一份，不是货架。
- agent-service 需要：MinIO 可达（已有）、可写缓存目录、docker.sock（已有）。
- 部署文档：CLI 包投放与升级流程已写进 `docs/deploy-harnax-admin.md`（新增「CLI 插件包投放与升级」一节，并补 `HARNAX_CLI_PACKAGE_DIR` / `MINIO_CLI_PACKAGE_BUCKET` 两个变量）；`prod_doc/skill-management.zh-CN.md` 的 TODO-9 四条按落地结果改写（三条结案、一条仍成立并更新了后果）。

## 7. 测试与验收（先红后绿）

**harnax-common**
- `CliPackageLayoutTest`（该模块的第一个测试类）：`checkRelativePath` 的六种拒绝形状（绝对、空段、`.` 段、`..` 段、NUL、段内冒号）与放行边界；`checkPayloadPath` 的按段匹配——`etc/passwd`、`usr/bin/su`、`sbin/x` 命中，`etc2/x`、`sbin2/x`、`usr/bin/su-client`、`rootfs/etc/passwd` 必须放行（字符串前缀匹配会误伤它们，而误伤是看不见的功能缺失）；同一目的地换一种拼法必须同样被拒；`resolveInside` 与文本规则对同一个名字给出同一个答案；四个字符集正则各自的正反例（含 32/64 长度边界、换行、前导 `-`）。放在这里是因为 admin 与 agent-service 共用这一份判据，漂移没有任何一方会报错。

**admin**
- `CliPackageParserTest`：合法包 / 缺 `SKILL.md` 拒绝 / manifest 缺必填字段拒绝（一次报全）/ 未识别的 manifest 键与重复键拒绝 / 文件名字干与 `name` 不符拒绝 / zip-slip 路径拒绝 / 拒绝前缀命中 / 符号链接、setuid、全无可执行位、`payload/` 外的游离文件、包内重名条目各自拒绝 / 打包器未写 unix mode 时拒绝 / 上限边界成对断言（2000 个文件可登记、2001 个报 `over the 2000 limit`）/ **同一内容重打包两次：`payloadDigest` 不变而 `packageDigest` 变（I7 + D15）**，另用「同一份字节读两次摘要相同」守住确定性，并单独断言「只改 `SKILL.md` 不动镜像指纹」「改二进制/改 mode/加一条 apt 都动镜像指纹」/ 打包器写出的目录条目（`payload/`、`payload/usr/local/bin/`）不移动 `payloadDigest`（`zip -r` 每次都带它们，否则每次重打包都会白建一层镜像）/ **仓库自带的 `harnax-cli/plugin.yaml` + `SKILL.md` 必须能被解析**，并断言 `runtimeEnv` 两个槽位与 `SkillContentScanner` 零命中——第 1 号包自己不合格等于整条链没有验收
- `CliPackageAutoRegistrarTest`：首次登记建 `cli` + 技能行并回写 `skill_id`；`packageDigest` 未变则不上传 MinIO（mock 客户端断言 `putObject` 零调用）；`packageDigest` 变则覆盖行；**捕获 `putObject` 的桶与对象键，断言它等于行里写的 `packageObject`**（首登记、换包、同名裁决三处都钉住——对象键写错时页面、下发、取包各说一套话，而每一步单看都成功）；只改技能正文换 `packageDigest` 不换 `payloadDigest`；重复登记不产生第二行技能；kill switch 不被换包复位；内容扫描命中 ⇒ CLI 登记成功而技能行存 0；同名多包取 version 数值最高者（且与目录列举顺序无关）、`1.0.10 > 1.0.9`、同 name 同 version 两份都不登记；prune 三道刹车各一条（有包读失败 / `stale >= live` / `package_digest` 为空的行不是它的）、级联删绑定与技能行、只退一个包时其他行的绑定不动；**目录里有包而 MinIO 未启用时启动明确失败**
- `CliManagementIT`（真库 MySQL）：`upsertCliPackage` 走一遍包模型的全部列（含 `ON DUPLICATE KEY UPDATE` 不覆盖 `status`）；换包后行身份与 kill switch 都保持；页面与详情读出登记值；prune 的两条 SQL 语义只有真库能证——一次 `deleteByIds` + `deleteByCliIds` 同时断言「点名的行、它的技能行、它的绑定都消失」与「没点名的行、技能、绑定一个都不动」；写路由（create/update/delete）已不存在；五个路由无 token 即 401；启停未知 id 失败
- `CliServiceImplTest` 重写：读路径与 toggle（断言停用不因绑定而拒绝）、`status` 只接受 0/1、停用与启用都带走技能行状态、启用不解除内容扫描隔离；create/update/delete 用例随接口删除
- `CliControllerTest`：只剩读与 toggle 五条路由，写路由不存在
- `InternalApiControllerTest`：`cliDetails` 内联 `skill`；停用项既不出现在 `cliDetails` 也不贡献技能；`/internal/builtin-skills` 路由移除
- `SkillServiceImplTest`：托管仓库仍对技能域只读，新增断言「CLI 登记路径可写」

**运行侧**
- `CliImageBuilderTest`（harness-core）：零 CLI 直返基座且不发任何 docker 命令；指纹与传入顺序无关、随 `payloadDigest` 与 `baseImage` 变化、随只换包（`packageDigest`/`packageObject`）**不**变（D15）；Dockerfile 按 `cliId` 排序、`COPY <digest>/ /`、payload sha 注释、apt 去重排序、有/无 `deps.apt` 两种，且所有 `RUN` 只装 apt 不碰负载（I4）；spec 复核——`name`/`version`/两个摘要/`depsApt` 各有一条被拒用例且在取包与任何 docker 调用之前生效，`version` 里塞换行注入被拒时报错只点名不回显其值；镜像已存在则不构建不取包；构建上下文只含本轮需要的树（`Dockerfile` 加各 `packageDigest` 目录，不指向共享缓存）、可执行位进上下文后仍是 `rwxr-xr-x`、取包按登记值传摘要与对象键；构建失败带 CLI 名；`checkCommand` 失败时 `rmi -f`；空 `checkCommand` 跳过；同套 CLI 第二次解析不再问 docker
- `CliPackageStoreTest`（harness-core）：负载按包声明路径落地且不带 `plugin.yaml`/`skill`；可执行文件 mode 保留（守住 §3.3 的坑）；带显式目录条目的包（`zip -r` 的真实产物形状）能装，指向 payload 根之外的目录条目仍然拒绝；哈希与登记摘要不符 ⇒ 不发布树、无完成标记、无 staging 残留；命中缓存则第二次不再访问 MinIO；有树无标记的中断残留能重建而非永久修不好；无 `payload/` 条目的包拒绝；`payload/etc/cron.d/demo` 这类逃逸路径经解包重判后到不了缓存；摘要形状非法（空、`../../etc/passwd`、截掉一位）与空对象键都在下载之前拒绝，且报错不回显调用方给的值
- `HarnessAgentLauncherCliEnvTest`（harness-core，独立类而非塞进已有的 `HarnessAgentLauncherTest`）：`runtimeEnv` 的两个槽位解析后进 `dockerSpec.environment`，`envBindings` 最后覆盖；`cliPluginsEnabled` 相关断言随开关一起删除
- `AgentSpecResolverTest`（agent-service）：`withCliSkills` 从 `cliDetails` 取，去重与同名遮蔽回归

**包生产侧（`harnax-cli`，Go）**
- `internal/config/config_test.go`：env 注入生效、带任一 `--profile` 时两个环境变量都不认（`GetServerURL` 只对默认 profile 认 env）、只设 `HARNAX_URL` 而无 `HARNAX_TOKEN` 时报错点名缺的是哪个变量且不静默回落、两者都不设时才回落已保存的登录、没有登录时如实报「未登录」
- `cmd/cli_resource_test.go`：`toggle` 省略 `--status` 时先 `get` 当前值、再显式提交翻转后的值（admin 侧 `status` 始终必填）；把 admin 真实响应形状反序列进 `CliTool`，钉住字段名与 `packageText`/`envParamsText` 的渲染——键名漂移在这里不是编译错误，只会让运维看到空的版本与包摘要并以为包坏了；可选字段缺失时渲染有占位

未覆盖的两处，写清楚比留白有用：`MAX_PAYLOAD_BYTES`（512 MB 解包总量）没有用例，要真写 512 MB 才撞得到——「边读边判长度」这条路径本身由 manifest、`SKILL.md`、单条 asset 三条「超上限即拒」用例覆盖，缺的只是 payload 总量这一维；前端只有 `npm run build` + `biome lint` 两道闸门，没有断言级测试，`/context/cli` 的渲染正确性靠下面的验收第 1 条人工看。

**验收（docker-new）**
1. 放一个自制最小包（一个脚本 + 一个 `SKILL.md`），重启 admin → CLI 页出现、状态 Enabled、带技能；MinIO 有对象。
2. 某 agent 勾选它 → 新会话 → `docker images | grep harnax-sandbox:cli-` 有新 tag → 容器内该文件在 manifest 声明的绝对路径上存在且可执行 → `agent-service` 日志出现 `Resolved CLI sandbox image`。
3. 换二进制（`payloadDigest` 变）重启 → 新 tag 建出来，**新会话**用它；已在保活的会话仍跑旧容器旧镜像（Scenario 0 短路了镜像比较，启动扫描又会收养容器）。已实测：新镜像 `harnax-sandbox:cli-def579d13661` 内 `hello --version` 报 `0.1.1`，旧会话容器未被触碰，新会话 `docker inspect` 落到新 tag。
4. 只改 `SKILL.md` 重打包重启 → 行更新、对象新增，但 tag 不变、容器不重建，新会话提示词已含新内容。
5. 沙箱内 `harnax health` 靠 env 通过，无 `init.sh`。
6. prune：从目录删包 → 重启 → `cli` 行消失且 `agent_cli_binding` 无残留。**先决条件**：待删数必须小于剩余数，否则第三道刹车直接拒绝（见 §3.2）——本次即在「2 个包删 1 个」上被拒过一次。补齐到 3 个包后再删才跑通：`cli` 硬删、`agent_cli_binding` 无残留、自带 `skill` 行 `active=0`。

## 8. 已知风险（明确接受，不在本规格内解决）

1. **平台成为软件分发渠道**：能往包目录放文件的人，即能决定所有 agent 沙箱里以 root 落什么文件。D2 把这条边界从「Web 表单校验」搬到「部署物管控」，是转移不是消除。拒绝前缀（I3）只是纵深防御一层，不是沙箱。
2. **`runtimeEnv` 把内部密钥送进容器**：`HARNAX_TOKEN` 若是 admin 内部密钥，容器内进程即可以 SYSTEM 身份调 admin。被退役的 `init.sh` 传的正是同一个密钥，所以这不是新增暴露面；但这条从「一处硬编码」变成「标准里的公开槽位」，更容易被别的包照抄。建议后续改 per-session 短令牌。
3. **`deps.apt` 仍需镜像源可达**：声明式不等于离线。纯 payload 型包（Go 静态二进制）零网络，应作为推荐写法写进包规范文档。
4. **登记与页面之间没有反馈通道**：包放错目录、manifest 写坏，页面只会表现为「没出现」，真相在 admin 日志。可后续加一个「最近一次登记结果」只读接口。
5. **技能附带文件进沙箱仍未通（D14）**：包能带 `assets/`，但运行侧不承诺它在容器里可见——TODO-10 未解。
6. **不并存版本**（I1）：滚动升级期间不同 agent 拿到的都是最新一版，没有「按 agent 钉版本」的能力。
7. **升级不跟进保活会话**：新二进制只到新会话；旧会话继续跑旧容器旧镜像，直到被空闲回收或手工 `docker rm -f`（机制见 §4.2）。这不是本次引入的，但页面的版本列显示的是最新登记值，与在用会话实际所跑可以不一致。
8. **文件名的版本段只给人看**：登记身份取 manifest 的 `name`/`version`，同名裁决也只读 manifest 的 `version`。文件名写成 `harnax-9.9.9.harnaxcli.zip` 而 manifest 写 `1.0.0`，平台就按 `1.0.0` 登记并展示。
9. **包对象、本地缓存、镜像三处只增不减**：MinIO 每 `packageDigest` 一个对象、agent-service 每 `packageDigest` 一个缓存目录、docker 每 `payloadDigest` 一个 tag，三处都没有回收路径；`knownImages` 在 `docker image prune` 之后也不会自愈。打包侧还有一处：`make package` 不固定 mtime，同一内容重打包每次都是新 `packageDigest`，于是每次发版各多一个对象与一个缓存目录（`payloadDigest` 相同，不会多镜像）。下架包的旧对象与旧镜像目前只能手工清。
10. **多副本 agent-service 共用一个 docker daemon 时无构建互斥**：tag 按内容寻址，结果一致，但两个副本同时建同一个 tag、或一个 `rmi -f` 掉验收失败的镜像而另一个正在建，会让一方看到构建失败。单副本不会命中；agent-service 横向扩容前要先解决。

## 9. 不在范围内

- 技能域通用的文件投影（TODO-10 其余部分）
- 团队主管的 CLI 装配（主管仍只配技能，`!isLead` 判断保留）
- 包版本并存、灰度、回滚策略（I1）
- 非 x86 宿主的包构建（包内二进制架构由发布者负责，manifest 不声明）
- 包签名与校验链（digest 只防错不防恶意）

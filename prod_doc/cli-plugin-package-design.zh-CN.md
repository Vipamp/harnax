# Harnax CLI 插件包设计（中文）

> 英文版本见 [cli-plugin-package-design.en-US.md](./cli-plugin-package-design.en-US.md)
>
> 工具集成设计见 [tool-integration-design.zh-CN.md](./tool-integration-design.zh-CN.md)，技能体系见 [skill-management.zh-CN.md](./skill-management.zh-CN.md)，MCP 见 [mcp-management.zh-CN.md](./mcp-management.zh-CN.md)。
>
> **写给包作者的包规范单独成文**：[cli-package-spec.zh-CN.md](./cli-package-spec.zh-CN.md)。本文讲平台侧链路（登记、镜像、下发、页面）与不变量及其理由。
>
> 本文定义 **CLI 插件包到达沙箱的全链路**；改动清单、迁移与测试计划见实现规格 `docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md`。

## 0. 文档状态

本文描述的都是**已落地的现状**：登记链路、镜像构建、技能到达、页面与接口。字段字符集、上限、拒绝清单、打包与自检这些「作者要照抄的东西」在 [cli-package-spec.zh-CN.md](./cli-package-spec.zh-CN.md)，两份文档以 `CliPackageLayout.kt` 与 `CliPackageParser.kt` 为共同真相源。实现规格见 `docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md`（决策 D1~D15、V35~V38 迁移、改动清单与测试计划）。

仍敞开的只有 §10 那十条边界。其中「`skill/assets/` 到不了容器」不是本设计的缺口，而是它承接的技能域通用投影问题（`skill-management` 的 TODO-10），包规范允许带、运行侧不承诺送达。

## 1. 一句话模型

**一个 CLI = 一份二进制负载 + 一份技能说明书，二者必须同生同灭。**

- 二进制负载决定**能力**：容器里有没有那个可执行文件；
- 技能说明书决定**可知**：模型是否知道该用什么命令、参数怎么给。

只有前者的 CLI 对模型等于不存在。因此技能不是可选项，而是包规范的必需成员（§2.2 I6）。这条判断决定了本设计的全部形态。

CLI 不区分「内置」与「自定义」——`harnax` 自身就是按本规范实现的第一个包（§2.3）。

## 2. 包规范

包规范单独成文，交给包作者照着写：[cli-package-spec.zh-CN.md](./cli-package-spec.zh-CN.md)。那份文档承担布局与文件名规则、`plugin.yaml` 七个字段与各自字符集、payload 路径规范与拒绝落点清单、大小与数量上限、`checkCommand` 的写法、打包命令、上架与退役、发布前自检清单、拒绝原因原文对照，以及一个第三方实例。

本节只留设计侧要承担的两样：包的形状，和七条不变量。§3~§10 的链路都建立在这几条上，正文里提到 I3、I5、I7 指的都是 §2.2。

### 2.1 形状

```
<name>-<version>.harnaxcli.zip
├── plugin.yaml            # 唯一元数据来源，平台只读它
├── skill/SKILL.md         # 必需，技能名强制等于 name
└── payload/               # 打进镜像的文件树，相对路径就是容器内绝对路径
    └── usr/local/bin/<name>
```

包里只有这三处。平台不执行任何安装脚本，能声明的只有「哪些文件落到哪里」和「装哪几个 apt 包」——没有 `install` 段，也没有任何接受脚本的字段（D6）。

### 2.2 不变量

- **I1** `name` 唯一即身份，新版本覆盖同一行，不并存多版本；
- **I2** 两个摘要：`packageDigest = sha256(zip)` 是包的身份，`payloadDigest` 是镜像指纹（§6）；
- **I3** `payload/` 的每个目标路径必须**只有一种拼法**：绝对路径、空段（`usr//bin/x`）、`.` 段（`./etc/x`、`usr/./bin/x`）、`..` 段、NUL、段内冒号一律拒绝。拒绝清单 `etc/`、`root/`、`usr/bin/docker`、`usr/local/bin/docker`、`bin/su`、`usr/bin/su`、`sbin/`、`usr/sbin/`、`var/run/docker.sock` 按**路径段**比较：带斜杠的是目录前缀（`etc/passwd` 命中，`etc2/x` 不命中），不带斜杠的是完整路径（`usr/bin/su` 命中，`usr/bin/su-client` 不命中）。符号链接、setuid/setgid/sticky 位、`plugin.yaml`/`skill/`/`payload/` 之外的游离条目均拒绝；同名条目在包里出现两次即拒绝；整棵 payload 至少要有一个可执行位（否则这个包装不出任何命令）；
- **I4** 解析期只读包内容，**绝不执行包内任何文件**；
- **I5** CLI 停用 ⇒ 它的技能同步停用，不单独生效；
- **I6** `skill/SKILL.md` 的技能名强制等于 `name`；
- **I7** `payloadDigest` 计算必须规范化：取 `payload/` 下的**文件**条目（目录条目不进指纹，`zip -r` 会为它们各写一行），按目标路径字典序串接 `path|mode|sha256(content)`，再拼排序后的 `deps.apt`。打包时间戳、zip 内顺序、以及打包器额外记下的目录项都不得影响结果。

各类内容有各自的大小上限，超限即拒绝：`plugin.yaml` 64 KB、`skill/SKILL.md` 1 MB、单个 `skill/assets/` 文件 512 KB 且这一类合计 4 MB、payload 最多 2000 个文件、解包后最多 512 MB、`deps.apt` 最多 50 条。前三类是文本，读取**边读边算长度**（中央目录里的 size 由打包器自己填，流式写入时干脆是 `-1`，因此不作为依据）；manifest 还额外受 YAML 的嵌套深度与重复键限制。

要求唯一拼法是因为门内门外两套代码：门内按文本判拒绝清单，门外把路径拼到目标目录下再归一化。`usr/./bin/docker` 这种路径文字上过、落地后落在拒绝清单上，只有一边会拦。同一份不变量还兜住摘要——`payload/./usr/bin/x` 与 `payload/usr/bin/x` 是一个文件的两份拼法，两条都进指纹的话镜像 tag 就不再描述镜像内容。

可执行位由 zip 的 unix mode 外部属性携带，平台不改写，因此打包侧要**先 `chmod 755` 再 `zip -X`**。平台只采信 `versionMadeBy` 声明为 UNIX 的条目；打包器压根不写 mode 时登记会被拒绝，而不是悄悄把二进制解成 `0644`——后者要到镜像构建期才以 `checkCommand` 退出 126（Permission denied）的形式暴露，归因成本高得多。

### 2.3 参考实现：`harnax` 自身

`harnax-cli/` 是 Go 写的平台管理命令行，本规范的第 1 号包。它同时验证了规范里最容易被省掉的两槽：

- `checkCommand: harnax --version` —— 构建期验收。cobra 只生成 `--version` 标志、没有 `version` 子命令，写成后者会以「未知命令」失败、退出码 1，镜像构建随之作废；
- `runtimeEnv: {HARNAX_URL: ${platform.adminUrl}, HARNAX_TOKEN: ${platform.internalToken}}` —— 它必须知道平台地址与令牌才能工作。

容器里没有家目录可写 `~/.harnax`，所以 `harnax` 改读这两个环境变量。它不新增认证模式：令牌存在时直接复用 CLI 原有的 internal 凭据（`Bearer <secret>`）。地址的取值优先级 `--server-url` > `HARNAX_URL` > profile，且带上任一 `--profile` 时两个环境变量都不认——那是一句「去别的环境说话」，否则它会拿平台自己的内部令牌去访问运维随手配的 prod 并报告成功。只设了 `HARNAX_URL` 而 `HARNAX_TOKEN` 为空时直接报错「指向一个自己无法认证的平台」，不回落到 profile：静默降级会表现成一次普通登录失效。

包模型下 `harnax` 与其余 CLI 走同一条链路：镜像里的那个二进制来自包 payload，页面上那条技能来自包 `skill/SKILL.md`，镜像构建侧没有任何为本仓库特设的挂载或基座分支。`sandbox-plugins/build.sh` 只构建默认沙箱镜像 `harnax-sandbox:py-node`（基座为 `python:3.11-slim`），与 CLI 分发无关。

`harnax cli` 只有三个动作：`list`、`get`、`toggle <id>`。`toggle` 的 `--status` 可以省略，省略即翻转当前值——翻转由 CLI 侧完成（先 `get` 读回当前 `status` 再显式提交目标值），admin 侧的 `status` 参数始终必填。`--status` 的默认值刻意不是 1，否则一条叫 toggle 的命令每次运行都在启用目标包，包括被内容扫描判下线的技能（§7）。

## 3. 登记链路（admin 启动）

```
/home/harnax/cli-packages/*.harnaxcli.zip
        │  ApplicationReadyEvent（Flyway 之后）
        ▼
CliPackageParser.parse      纯函数：manifest + 两个摘要 + 结构校验
        │  校验失败 → 跳过该包，ERROR 日志，不影响其余包、不阻断启动
        ▼
按 manifest name 分组       同名多包 → 只登记 version 数值最高的那个，其余跳过并 ERROR
        │  同版本两份 → 两份都不登记，计入失败
        ▼
packageDigest 与库中不同？──否→ 只 upsert 行，不传对象
        │是
        ▼
MinIO putObject  harnax-cli-packages/<name>/<packageDigest>.harnaxcli.zip
        ▼
upsert cli 行（name/version/description/check_command/env_params/两个摘要/object key）
        ▼
upsert 托管仓库技能行（SKILL.md 正文 + assets）→ 回写 cli.skill_id
        │  技能 status 跟随 CLI 行 status；内容扫描命中则强制 0 并 ERROR 列出命中的规则
        ▼
pruneMissingPackages  目录里已消失的包 → 硬删 cli 行、级联清 agent_cli_binding / agent_skill_binding / team_skill_binding、软删自带技能行；WARN 列出包名与行 id
```

目录配了却不存在，按「卷没挂」处理：ERROR 之后既不登记、更不 prune。此时照流程 prune 会把全平台的 CLI 连着绑定一起删掉，而真相只是挂载丢了。

同名多包按 manifest 的 `version` 逐段比数值裁决（`1.0.10` 比 `1.0.9` 新），因此升级时忘了下架旧包不会变成「看目录列举顺序脸色」。预发布后缀在这里只是又一段文字，于是 `1.0.0-rc1` 反而判得比 `1.0.0` 新——两份文件本来就同名，这里只回答谁上架，这个反向可以接受。

一共三道刹车：本轮有任何包读取失败就不 prune（没有可信的在架集合）；**只有 `package_digest` 非空的行才是候选**——非空是登记器亲手写过的证据，它因此永不删除自己没写过的行，「待删 / 剩余」这个比例也只在这些行之间算；**待删数不小于剩余数（本轮登记到的包数）即拒绝执行并 ERROR**。第三条在包目录很小的时候会咬到运维：只有 2 个包时删掉 1 个，`待删 1 / 剩余 1` 正好落在拒绝的一侧，日志会明确写着「看起来像目录没挂而不是退役包」。要合法退役，架上留下的包必须比待删的多——这同时给 prune 定了下界：一次运行要能删就得至少留 2 个包在架上，所以登记器写的行永远删不到只剩 1 个 CLI，退掉倒数第二个只能走 SQL。

手工录入的旧 `cli` 行 `package_digest` 为空，因此永不成为 prune 候选——它不是「包下架了」，而是一条本模型写不出来的记录。这类行由 `V38__retire_pre_package_cli_rows.sql` 一次性退役：绑定硬删、行 `active=0` 且名字加 `#retired-<id>` 后缀（`uk_cli_name` 也覆盖软删行，不加后缀的话同名新包会覆盖这具尸壳）。

与工具同形：`BuiltinToolAutoRegistrar` 在启动时按 `@Tool`/`@ToolMeta` 注解同步 `agent_tool` 表，CLI 按包目录同步 `cli` 表。两者的表都只有这一种来源，页面都只读。

对象键里没有 `version`：`name` 就是身份（一个 CLI 只有一行），版本之间的差别由摘要表达。

目录里确实有包而 MinIO 未启用 ⇒ 启动失败并说明原因，不做本地降级——降级分支会造出第二份真相源。目录本来就是空的那就不要求 MinIO，用不到 CLI 的部署不该被摊派一套外部依赖。

## 4. 到达镜像与容器

`agent-service` 在创建沙箱前解析镜像（仅非主管、且该 agent 勾了 CLI 时）：

1. 按镜像指纹算 tag：`sha256(基座镜像 + 各 CLI 的 cliId:version:payloadDigest，按 cliId 排序)` 取前 12 位，写作 `harnax-sandbox:cli-<12hex>`——它不是 `payloadDigest` 本身，同一套 CLI 与列举顺序无关；本地已有则完全跳过构建；
2. 需要构建时，从 MinIO 取包、解 `payload/` 到按 `packageDigest` 分片的本地缓存目录。取之前先复核输入：`packageDigest` 必须是 sha256 十六进制、`objectKey` 必须非空——它们接下来要当路径分量用，且来自 admin 下发的 spec 而不是本进程构造的值。下载边读边算 sha256，与期望值不一致就抛错（存的字节不是登记的那个包）。解包时逐个条目重判 I3 的拒绝清单与逃逸，不假定「admin 那次读包已经判过」；目录条目先去掉尾部那个使它成为目录的斜杠再判，登记侧只对文件条目判 I3，两侧对同一个名字必须给同一个答案；
3. 生成 Dockerfile：`FROM <base>` + `COPY <packageDigest>/ /` + 可选 apt 层；
4. `docker build`（上下文是 agent-service 容器内路径即可，CLI 会打包流给 daemon）；
5. 逐个 `checkCommand` 在新镜像内跑，**任一非零即 `docker rmi -f` 并抛异常**——坏包不会流到运行时；
6. `env_params` + `runtimeEnv` 展平为容器 env，在容器创建那一刻注入。

保活语义（现状已核实，本设计不改）：env 只在容器创建那一刻注入，所以**改 CLI 的参数需要新会话**。换二进制也一样只有新会话生效——**在用的保活容器不会被换镜像**：`KeepAliveSandboxManager.getOrCreate` 命中内存里的会话时直接返回旧沙箱，比较镜像的那段代码根本走不到；agent-service 重启也没用，启动扫描 `scanAndRestore` 会把既有容器重新收养回同一张缓存表。要让一个在用会话真正用上新二进制，只能等它被空闲回收，或直接 `docker rm -f agentscope-sandbox-<sessionId>`。

## 5. 技能如何到模型

`skill/SKILL.md` 落 `skill` 表（正文即真相源，运行期不回读包文件），随 `cliDetails` **内联下发**给 agent-service 后注入 agent 上下文——技能没有独立的取用端点，它的搬运工就是那条 CLI。

这条链路上技能行只有一个写入方：admin 启动时的登记器。行落在托管仓库 `builtin-cli-skills`，该仓库对技能页只读（增、改、删都被拒），其中的技能也**不允许被单独绑定**给 agent——`SkillBindingResolver` 会直接拒绝，装载它们的唯一途径是绑那条 CLI（§8 D3 的「二进制与说明书同生同灭」在数据层的落点）。读取侧同理：下发只带这条 CLI 自带的那一条，名字与 `cli.skill_id` 指向的行一致。

## 6. 为什么是两个摘要

| 摘要 | 覆盖 | 用来 |
|---|---|---|
| `packageDigest` | 整个 zip | MinIO 对象键、「要不要重新登记」判定 |
| `payloadDigest` | `payload/` 文件树 + `deps.apt` | 镜像 tag 指纹 |

只用整包摘要会出错：改一个字的错别字（只动 `SKILL.md`）也会换 tag，于是为一份内容完全相同的文件树再建一层镜像、再落一个新缓存目录，tag 从此与「镜像里到底有什么」脱钩。分开之后：改说明书 → 镜像不动，新会话提示词生效；换二进制 → 新会话用新镜像（在用的保活容器仍跑旧镜像，原因见 §4）。这是包模型唯一一处容易做错的地方。

## 7. 页面与接口

`/context/cli` 是单表只读，列 = 名称 / 描述 / 版本 / 自带技能 / 健康检查 / 包摘要（`packageDigest` 前 12 位）/ 状态。「自带技能」那一格是入口，点进去是这份技能的详情页（`/context/skill/detail/:id`）；启停是这页唯一会改状态的动作。包怎么发布不写在页面上：作者要照抄的字段、上限与打包动作见独立成文的 [cli-package-spec.zh-CN.md](./cli-package-spec.zh-CN.md)，包放上去之后平台怎么处理见本文 §3（登记链路）。

接口面只有五条：`GET /api/admin/clis/page`、`GET /{id}`、`PUT /toggle/{id}`、`GET /{id}/related-agents`、`GET /{id}/related-sessions`；`/api/admin/clis` 下没有创建、更新、删除任何一条路由。启停不因「有 agent 在用」而拒绝——它是 kill switch，拦停与目的相反；影响面由 `related-agents` 预览承担（是提示，不是闸门），改完还会提示要不要刷新相关会话。

`PUT /toggle/{id}` 的 `status` 必填且只接受 0 或 1，越界直接报错：全站所有消费方都按 `== 1` 比较这个字段，一个 2 不会报错，只会永久读成「停用」而页面上再也切回来。停用会带着自带技能行一起停用（I5）；启用则有一个例外——**不会解除内容扫描的隔离**。隔离的判定不是标记位，而是把库里那份 `skillmd` + resources 重新扫一遍还命中若干规则；正文读不出来时按隔离处理（多花一次人工启用的代价，换掉「一个开关顺手批准了 `curl | sh`」的代价）。要解除只能改技能内容本身。

## 8. 关键设计决策

15 条决策（D1~D15）连同理由记录在实现规格 §1，此处只列影响外部实现者的三条：

- **D3 技能必需**：包里没有 `SKILL.md` 即判非法，不提供「先只装二进制」的退路；
- **D6 无任意 shell**：安装语义只有 payload 落点与 apt 清单，manifest 不接受脚本字段，包作者不能假设容器里有网络；
- **D15 两个摘要**：镜像 tag 只由 `payloadDigest` 决定，包作者改说明书不会新建镜像层。

## 9. 与 tools / skill / MCP 的一致性

| | 来源 | 登记时机 | 页面 | 表 |
|---|---|---|---|---|
| 工具 | 代码里的 `@Tool` | admin 启动 | 只读 | `agent_tool` |
| **CLI 包** | **目录里的 `.harnaxcli.zip`** | **admin 启动** | **只读 + 启停** | **`cli`** |
| 技能 | Git 仓库 / 手工 | 用户点安装 | 可写 | `skill` |
| MCP | 配置 + 远端 | 用户配置 | 可写 | `mcp_*` |

CLI 与工具同形的代价相同：都靠重启生效，都没有热更新。收益也一样：来源单一、页面不撒谎、镜像里跑的东西与库里的行必然同源。

## 10. 已知边界

1. **平台即软件分发渠道**：能往包目录放文件的人，能决定所有 agent 沙箱里以 root 落什么文件。I3 的拒绝前缀是纵深防御一层，不是沙箱。信任边界从「Web 表单校验」搬到了「部署物管控」，是转移不是消除。
2. **`runtimeEnv` 会送密钥进容器**：`HARNAX_TOKEN` 若用 admin 内部密钥，容器内进程即可以 SYSTEM 身份调 admin。这个槽位是公开标准的一部分，别的包会照抄同一个写法。应尽快换 per-session 短令牌。
3. **`deps.apt` 需要镜像源可达**：声明式不等于离线，本机与容器直连 GitHub 不通。推荐写法是纯 payload 静态二进制。
4. **登记结果对页面不可见**：包放错目录或 manifest 写坏，页面只表现为「没出现」，真相在 admin 日志。缺一个「最近一次登记结果」的只读接口。
5. **`skill/assets/` 到不了容器**：技能附带文件从未被投影进沙箱（`skill-management` 的 TODO-10），包能带但运行侧不承诺。
6. **不并存版本**：升级期间所有 agent 拿最新版，没有按 agent 钉版本或灰度的能力。
7. **升级不跟进已在保活的会话**：换二进制只对新会话生效，老会话会一直跑旧镜像的旧容器，直到被空闲回收或手工 `docker rm`（机制见 §4）。这不是回归，是保活缓存的既有语义；但页面上「版本」一栏显示的是最新登记值，与在用容器可以不一致。
8. **文件名里的版本段只是给人看的**：登记身份取 manifest 的 `name`/`version`，同名多包的裁决也只看 manifest 的 `version`。文件名写成 `harnax-9.9.9.harnaxcli.zip` 而 manifest 写 `1.0.0`，平台按 `1.0.0` 登记，页面上也显示 `1.0.0`。
9. **包对象、payload 缓存与 CLI 镜像只增不减**：MinIO 里每个 `packageDigest` 一个对象、agent-service 本地每个 `packageDigest` 一个缓存目录、docker 每个 `payloadDigest` 一个 tag，三处都没有回收路径；`knownImages` 那层判定在一次 `docker image prune` 之后也不会自愈。加重这条的是打包侧：`make package` 不固定 mtime，同一份内容每次重打包都会得到新的 `packageDigest`，于是每发布一次就多一个对象和一个缓存目录（`payloadDigest` 相同，镜像不会多）。删除包目录里不再在架的对象与镜像目前是一件纯手工运维。
10. **多副本 agent-service 共用一个 docker daemon 时构建无互斥**：镜像 tag 按内容寻址所以结果一致，但两个副本同时构建同一 tag、或一个构建时另一个 `docker rmi -f` 掉验收失败的镜像，会有一边看到构建失败。单副本部署碰不到；扩容 agent-service 时要先解决。

## 11. 运维手册

| 动作 | 做法 | 生效时机 |
|---|---|---|
| 新增 CLI | 有来源的：自研进 `harnax-cli/`、第三方新建 `cli-packages/<name>/`。已经打好的包：直接丢进货架 `cli-packages/dist/`，不必有来源目录。两条路接同一步——`./cli-packages/build.sh`（只为有来源的包建包，顺带完成同名裁决）→ 把 `cli-packages/dist/*.harnaxcli.zip` 复制进 `docker-new/dist/cli-packages/` → 重启 admin。走 docker-new 的部署脚本时后两步已经串好 | 新会话 |
| 升级 CLI | 换掉那个包的来源（manifest 的 `version` 提高），或直接把新版本包丢进货架——同名两份时 `build.sh` 留 `version` 高的、把落选者移进 `cli-packages/dist/.superseded/`；然后重跑上面一条（同 `name` 覆盖同一行），重启 admin | 新会话。已在保活的会话仍跑旧镜像，要立刻换过来只能等空闲回收或 `docker rm -f agentscope-sandbox-<sessionId>` |
| 只改说明书 | 改 `SKILL.md` 重打包（同 `name`）重新上架，重启 admin | 新会话提示词生效，镜像不动：`packageDigest` 变了会多一个 MinIO 对象与一个本地缓存目录，`payloadDigest` 没变因此不建新镜像（§10-9） |
| 下线 CLI | 两处都删：`docker-new/dist/cli-packages/` 与货架 `cli-packages/dist/`（有来源目录的还要删掉 `cli-packages/<name>/`，否则下次构建又把它打回货架），重启 admin | 级联：`cli` 行硬删、三张绑定表清空、自带技能行 `active=0`，WARN 列出包名与行 id。**前提是架上留下的包比待删的多**，否则第三道刹车会拒绝并打 ERROR（见 §3） |
| 紧急停用 | 页面启停（保留绑定） | 下一次配置解析即生效 |
| 定位「模型说没有这个命令」 | 按顺序查：CLI 页有无 → admin 登记日志 → agent-service `Resolved CLI sandbox image` → 容器内文件与 mode → `checkCommand` 手动跑 | — |

投放走货架：`cli-packages/build.sh` 只把有来源目录的包补齐到 `cli-packages/dist/`（自研的 `harnax-cli` 走 `make package`，第三方的包各由 `cli-packages/<name>/build.sh` 打），**不清架**——手工丢进来的 zip 原样留下，一起被三个入口脚本整份复制进 `docker-new/dist/cli-packages/`，交给 `Dockerfile.admin` 的 `COPY`，compose 再把这一份以**只读 bind mount** 盖在容器的 `/home/harnax/cli-packages` 上。宿主机那份就是唯一一份：不再有「镜像里是新包、卷里是旧包」的失同步，也不需要 `docker cp`（挂载只读，写进去会被拒）。同名两份的裁决也在这里：留 `version` 高的，落选者移进 `cli-packages/dist/.superseded/`（移走不删除，手工投的包没有源码可重建），同名同版本两份则构建失败。代价是新增一条失败模式——把货架清空等于把架上所有 CLI 一起报成退役，靠 prune 的三道刹车兜住（见 §3）。

登记只发生在 admin 启动时，没有「重扫一次」的接口；页面也不会告诉你某个包为什么没出现——放错目录、manifest 写坏，在页面上的表现都只是「没多出一条」。真相在 admin 日志的 `CliPackageAutoRegistrar` 行里。

## 12. 关键文件索引

| 环节 | 文件 | 作用 |
|---|---|---|
| 包形状 | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageLayout.kt` | 后缀、三个入口常量、四个正则（name/version/apt/digest）、拒绝清单、`checkRelativePath`/`checkPayloadPath`/`resolveInside`、两个摘要的计算、文件数与字节上限 |
| 包形状 | `.../common/cli/CliPackageArchive.kt`、`ZipCentralDirectoryModes.kt` | 只读解包、边读边判长度的 `readBounded`、重名条目清单；从中央目录取 unix mode（§2.2 那个坑的正解） |
| 登记 | `harnax-admin/.../registrar/CliPackageParser.kt` | zip → manifest + 摘要 + 结构校验，纯函数；一次性列出全部拒绝理由 |
| 登记 | `harnax-admin/.../registrar/CliPackageAutoRegistrar.kt` | `ApplicationReadyEvent` 扫目录、同名裁决、上传 MinIO、upsert `cli` 与技能行、带三道刹车的 prune |
| 登记 | `harnax-admin/.../constant/BuiltinRepository.kt` | `CLI_SKILLS` 托管仓库常量（含义为「CLI 自带技能的托管仓库」） |
| 对外 | `harnax-admin/.../controller/CliController.kt`、`dto/CliResponse.kt` | 只剩读与 toggle 五条路由；响应带 `packageDigest` 与内联 `skill` |
| 对外 | `harnax-admin/.../service/impl/CliServiceImpl.kt` | 启停与技能状态跟随；启用时重扫内容以判定隔离 |
| 下发 | `harnax-admin/.../controller/InternalApiController.kt` | `cliDetails` 内联技能、跳过 `status=0` |
| 数据 | `harnax-entity/.../entity/Cli.kt`、`mapper/CliMapper.kt`、`resources/mapper/CliMapper.xml` | `skill_id` / 两个摘要 / `package_object` |
| 数据 | `harnax-admin/src/main/resources/db/migration/V35__cli_package_registration.sql` | 加列、删 `install_script` 与租户列、删三张废表 |
| 数据 | `harnax-admin/src/main/resources/db/migration/V36__drop_skill_binding_env_bindings.sql` | 删 `agent_skill_binding.env_bindings` 死列（从未有消费方） |
| 数据 | `harnax-admin/src/main/resources/db/migration/V37__retire_seeded_builtin_cli_skill.sql` | 软删手工种下的 `harnax-cli` 技能行（该名字现在归包所有） |
| 数据 | `harnax-admin/src/main/resources/db/migration/V38__retire_pre_package_cli_rows.sql` | 退役 `package_digest` 为空的遗留 `cli` 行：清绑定、`active=0`、名字加 `#retired-<id>`（§3） |
| 运行 | `harnax-agent/harnax-harness-core/.../sandbox/CliPackageStore.kt` | 摘要先复核再当路径用、按 `packageDigest` 取包并校验哈希、解 `payload/` 到缓存目录 |
| 运行 | `.../sandbox/CliImageBuilder.kt` | 镜像 tag、Dockerfile、`checkCommand` 验收（失败即 `rmi`） |
| 运行 | `.../harness/HarnessAgentLauncher.kt` | `runtimeEnv` 解析进容器 env、镜像解析入口 |
| 运行 | `.../harness/config/MinioConfig.kt`、`harnax-agent-service/src/main/resources/application.yml` | `cli-package-bucket` 与 `ensureBuckets` |
| 运行 | `harnax-agent/harnax-agent-service/.../runner/AgentSpecResolver.kt` | `withCliSkills` 把 `cliDetails[].skill` 并进 spec |
| 前端 | `harnax-webui/src/pages/cli/index.tsx` | 单表只读，唯一交互是启停 |
| 第 1 号包 | `harnax-cli/plugin.yaml`、`Makefile`（`package` 目标） | manifest 与打包（`package` 不固定 mtime，见 §10-9） |
| 第 1 号包 | `harnax-cli/internal/config/config.go`、`cmd/root.go` | `HARNAX_URL`/`HARNAX_TOKEN` → internal 凭据；`--profile` 与缺一半时的报错（§2.3） |
| 第 1 号包 | `harnax-cli/cmd/cli_resource.go` | `list`/`get`/`toggle` 三个动作，`--status` 省略即翻转 |
| 部署 | `cli-packages/build.sh` | 补齐货架 `cli-packages/dist/`：不清架（手工丢的包照留），跑各包脚本，同名只留 `version` 最高的一份、落选者移进 `dist/.superseded/`；货架为空或同名同版本两份才失败 |
| 部署 | `docker-new/Dockerfile.admin`、`docker-compose.yml` | COPY 包目录；只读 bind mount `./dist/cli-packages` → `/home/harnax/cli-packages` |
| 部署 | `docker-new/build.sh`、`deploy-all.sh`、`deploy-service.sh` | 三处都要先跑 `cli-packages/build.sh` 再 staging `docker-new/dist/cli-packages/` |
| 测试 | `harnax-common/src/test/kotlin/.../cli/CliPackageLayoutTest.kt` | 共享路径规则与四个字符集（admin 与 agent-service 共用这一份判据） |
| 测试 | `harnax-admin/src/test/kotlin/.../registrar/CliPackageParserTest.kt`、`CliPackageAutoRegistrarTest.kt`、`.../it/CliManagementIT.kt` | 解析期每一条拒绝规则、登记/裁决/prune 行为、真库上的 upsert 与级联删除 |
| 测试 | `harnax-agent/harnax-harness-core/src/test/kotlin/.../sandbox/CliPackageStoreTest.kt`、`CliImageBuilderTest.kt`、`.../harness/HarnessAgentLauncherCliEnvTest.kt` | 取包侧的摘要复核、下载哈希比对与解包重判；镜像 tag、Dockerfile、构建期验收；`runtimeEnv` 落进容器 env |
| 测试 | `harnax-cli/internal/config/config_test.go`、`cmd/cli_resource_test.go` | `--profile` 优先级与只配一半环境变量的报错；`toggle` 省略 `--status` 时的翻转 |
| 包作者侧 | `prod_doc/cli-package-spec.zh-CN.md`、`cli-packages/lark-cli/` | 独立成文的包规范（字段字符集、上限、拒绝清单、打包与自检、拒绝原因对照），以及第 2 号包：一个第三方二进制的取物与打包脚本 |

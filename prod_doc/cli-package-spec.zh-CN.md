# Harnax CLI 插件包规范

面向 CLI 包的作者：你要交付什么形状的文件、`plugin.yaml` 有哪些字段、平台会因为什么拒绝一个包、怎么打包、怎么上架和下架。

本文与《Harnax CLI 插件包设计》的分工：本文的读者是包作者，内容是包的形状与校验契约；那本的读者是平台实现者与运维，内容是登记链路、镜像与容器如何拿到包、货架目录与对象存储的运维操作。两份文档各自完整，不需要对照阅读。

「硬约束」小节与「拒绝原因原文 → 改法」表里的每一条拒绝文案都逐字抄自代码，可以当成契约来读：程序输出的英文字符串与本文给出的一字不差；尖括号里的变量含义在上下文中说明。

## 1. 一句话模型

一个 CLI 插件包 = 一个 `.harnaxcli.zip` 文件。作者把它放进货架目录，平台在启动期读它，一次性完成三件事：把归档按 `packageDigest` 存进对象存储、把包里的 `skill/SKILL.md` 登记成这个 CLI 自带的技能、把 `plugin.yaml` 的字段写成一行 `cli` 记录。

包内的二进制永不被平台执行。登记过程只读字节、不起进程，所以一个 `checkCommand` 写得再离谱也不可能在登记阶段跑起来；真正执行它的地方是镜像构建完成之后。

包没有租户、没有作者、没有可见性概念：上架即平台级资产。

## 2. 包布局与文件名

文件名格式是 `<name>-<version>.harnaxcli.zip`，其中 `<name>` 必须等于 `plugin.yaml` 声明的 `name`。校验先用文件名反推身份，再用声明名复核，两边不一致就拒。

归档内部只认三个落点：

```text
plugin.yaml                  # 清单，必须在归档根
skill/
  SKILL.md                   # 这个 CLI 自带的技能说明书
  assets/
    <相对路径>               # 可选；技能的文本资源
payload/
  usr/local/bin/<binary>     # 相对容器根的路径，去掉 payload/ 前缀后原样落盘
  <任意文件>                 # 权限位来自打包时文件系统的 st_mode
```

`payload/` 下的路径去掉前缀后就是容器里的路径，所以安装到 `/usr/local/bin/foo` 的包写的是 `payload/usr/local/bin/foo`。

目录条目（zip 里以 `/` 结尾的项）不占资源、不校验路径、也不参与摘要；平台只处理文件条目。

一次登记的结论只有两种：整包通过并写库，或者整包被拒并列出全部原因。不存在「注册了一半」的包。

## 3. 平台对包做的三件事（作者需要知道的后果）

1. **摘要定身份。** `packageDigest` 是整个 zip 的 sha256，同时是对象存储的 key 和「包有没有变」的判据。`payloadDigest` 是 `payload/` 下每个文件的 `path|mode|sha256(content)` 按路径排序后的规范摘要，末尾再接一行 `#apt:` 与排序后的 apt 列表；它是镜像的指纹。
2. **说明书进提示词。** `skill/SKILL.md` 变成一条技能记录，与 CLI 同名，由 `cli.skill_id` 直接指向。技能内容先过一遍与第三方技能加载器相同的内容扫描；命中规则的包，技能会以停用状态入库，并在日志里点出命中的资源与规则。
3. **换版本时行 id 不变。** 同名包出新版本时，平台原地覆盖清单拥有的所有列，行 id 保留，因此智能体上的绑定不会因为你发新版本而失效。`status`（开关）不在覆盖之列。

## 4. `plugin.yaml` 字段

认识的顶层键只有七个：`name`、`version`、`description`、`checkCommand`、`deps`、`envParams`、`runtimeEnv`。多出任何一个都会被拒。

| 字段 | 必填 | 类型 | 约束 | 落点 |
| --- | --- | --- | --- | --- |
| `name` | 是 | 字符串 | 匹配 `^[a-z][a-z0-9-]{1,63}$`；与文件名 stem 的前半段一致；与 `skill/SKILL.md` 的技能名一致 | `cli.name`（唯一键 `uk_cli_name`），同时是技能名 |
| `version` | 是 | 字符串 | 匹配 `^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$` | `cli.version`；同名两包时的取舍依据；镜像标签哈希材料之一 |
| `description` | 是 | 字符串 | 非空白 | `cli.description`，页面与工具说明用 |
| `checkCommand` | 是 | 字符串 | 非空白 | `cli.check_command`，构建后在镜像里执行一次 |
| `deps.apt` | 否 | 字符串列表 | 至多 50 项；每项匹配 `^[a-z0-9][a-z0-9+.-]{0,62}(=[0-9A-Za-z.+-]{1,32})?$`；不许重复 | `cli.deps_apt`（JSON 数组），进 `apt-get install -y` |
| `envParams` | 否 | 映射列表 | 见「参数与凭证」一节 | `cli.env_params`（JSON，secret 项的值加密后存） |
| `runtimeEnv` | 否 | 映射 | 见「参数与凭证」一节 | `cli.runtime_env`（JSON），容器创建时注入 |

`deps` 下除了 `apt` 不存在第二个段。

YAML 用安全加载器解析：未知标签不会实例化对象，重复键是错误而不是后写覆盖前写，嵌套深度上限 20，码点上限 65536。字符串值裁剪首尾空白。布尔值接受 `true`/`false` 字面量，也接受大小写不敏感的 `"true"` 字符串，其他一律按 `false`。

## 5. 硬约束：什么会被拒

下面每条都是代码里的实际文案，括号里是触发条件。尖括号内容是变量插值。

### 5.1 文件名与可读性

- 文件名不以 `.harnaxcli.zip` 结尾时：`file name must end with .harnaxcli.zip`。`checkFileName` 加完这一条就返回，跳过的只是同一函数里的 stem 判定；清单、说明书、`skill/`、`payload/` 各项检查照旧执行并把问题一起累积（见 5.7）。
- 去掉后缀的 stem 里找不到一个既不在开头也不在结尾的 `-`：`file name must be <name>-<version>.harnaxcli.zip, got "<file>"`
- stem 不以「声明名 + `-`」开头：`file name "<file>" does not start with the declared name plus a version, as in "<name>-1.4.0.harnaxcli.zip" — rename the file, the declared name is the identity`
- zip 本身打不开：整条抛出，形如 `<file>: unreadable package (<原因>)`。

### 5.2 路径必须只有一种拼法

`checkRelativePath` 是包内名字的全部防穿越规则，`skill/assets/` 的键与 `payload/` 的目的地共用它。它拒绝：

| 返回值 | 触发条件 |
| --- | --- |
| `empty path` | 空白路径 |
| `path must be relative: <path>` | 以 `/` 开头 |
| `path contains a NUL byte` | 含 NUL 字符 |
| `path has an empty segment: <path>` | 连续斜杠或尾随斜杠 |
| `path is not in canonical form: <path>` | 某一段是 `.` |
| `path escapes its root: <path>` | 某一段是 `..` |
| `path contains a colon: <path>` | 任意一段含 `:` |

表里给的是文案本体。由 `payload/` 条目触发时，进问题列表的是 `<原因> (entry <归档内路径>)`——`readPayload` 把归档里的完整条目名（带 `payload/` 前缀）拼在原因后面；`skill/assets/` 的键触发时拼的是另一条更长的尾巴，见 6.4 节。

理由是同一个位置只允许一种写法：`usr//bin/su`、`./etc/passwd`、`usr/./bin/docker` 作为文本能躲过拒绝清单，但落盘时会被规范化到那些目标上；`payload/./usr/bin/x` 与 `payload/usr/bin/x` 是同一个文件的两次写入、却是两条被哈希的条目，镜像标签与镜像内容就此各说一套。

### 5.3 payload 目的地拒绝清单

`checkPayloadPath` 在相对路径规则之上再查一层，命中时返回 `payload path lands on a denied target: <path>`；它同时透传 5.2 节那几条相对路径原因。进问题列表时两者都带 ` (entry <归档内路径>)` 尾巴。清单是 `DENIED_PAYLOAD_TARGETS`：

| 条目 | 匹配方式 |
| --- | --- |
| `etc/` | 段对齐前缀匹配 |
| `root/` | 段对齐前缀匹配 |
| `usr/bin/docker` | 整路径相等 |
| `usr/local/bin/docker` | 整路径相等 |
| `bin/su` | 整路径相等 |
| `usr/bin/su` | 整路径相等 |
| `sbin/` | 段对齐前缀匹配 |
| `usr/sbin/` | 段对齐前缀匹配 |
| `var/run/docker.sock` | 整路径相等 |

这一层是纵深防御，不是沙箱：能往货架目录放文件的人，本来就已经决定了每个容器里以 root 跑什么。

`extractTree` 在落盘时把这层检查再跑一遍，包括对目标根目录的规范化检查。一个绕过登记期校验的归档在解包时同样会被拒，报错形如 `<原因> — refusing to extract <entry>`。

### 5.4 payload 条目形状

- 符号链接：`<entry> is a symbolic link — extraction writes file content, never links, so it would arrive as a text file holding a path`
- setuid / setgid / sticky 位：`<entry> carries setuid/setgid/sticky bits (mode <八进制>)`，八进制由 `Integer.toOctalString(mode)` 生成，含类型位。
- 权限位必须真实存在。zip 中央目录里没有记录 unix mode 的 payload 条目会被列为：`<最多前 5 条路径，逗号分隔> carry no unix mode, so they would land 0644 and fail as "permission denied" — repack on a filesystem that stores permissions (zip -X, or the platform packer)`；超过 5 条时列表结尾追加 `, … (N total)`。
- 补一条的判据是「无 mode 条目数小于 payload 文件总数」且「`payload/` 里没有任何文件带可执行位」（`offenders.size < files.size && files.none { it.isExecutable }`）：无 mode 的条目覆盖整份 payload 时，前一条已经把原因说尽，这一条不会出现。补的是：`no payload file carries an execute bit — nothing the check command could run would start`

可执行位的判定是三个 x 位任一为 1。摘要与解包用的 mode 来自 `ZipCentralDirectoryModes` 对中央目录外部属性的直接遍历，两条视图交叉核对后不一致的包按上两条拒绝，而不是替它猜一个 0755。

### 5.5 大小与数量上限

| 上限 | 值 | 拒绝文案 |
| --- | --- | --- |
| 清单字节 | 65536 | `plugin.yaml is over the 65536 limit — the manifest describes a CLI, it is not where a package parks data` |
| 说明书字节 | 1048576 | `skill/SKILL.md is over the 1048576 limit — a skill teaches one CLI, and past this size it is documentation that belongs next to the binary` |
| 单资源字节 | 524288 | `<entry> is <N> bytes, over the 524288 per-asset limit` |
| 资源总字节 | 4194304 | `skill assets exceed the 4194304 byte budget (<entry> is the one that tipped it)` |
| payload 文件数 | 2000 | `payload has <N> files, over the 2000 limit` |
| payload 解包字节 | 536870912 | `payload unpacks to <N> bytes, over the 536870912 limit` |
| `deps.apt` 条数 | 50 | `plugin.yaml deps.apt lists <N> packages, over the 50 limit` |

清单、说明书、资源三类走 `readBounded`：中央目录自报的 size 由打包器决定、流式条目甚至是 `-1`，所以只能边读边截断。资源条目在 `readBounded` 返回 `null` 时另有对应文案 `<entry> ships more than the 524288 per-asset limit`。payload 的总字节按中央目录自报值求和。

### 5.6 归档布局

三个文档化落点之外不许有任何条目。存在多余条目时：

`unexpected entry outside plugin.yaml, skill/ and payload/: <前 5 条>`（多于一条时用 `unexpected entries`，多于 5 条时结尾追加 ` (+N more)`）

归档里出现同名两条目时：

`<name> appears more than once in the archive — one name has to mean one entry, because the digests hash every copy while reading answers with the first and extraction keeps the last`

多余条目会被静默忽略（作者于是继续携带它们），`__MACOSX/` 这类目录会进摘要、把对象 key 变成另一个值，所以两者都直接拒。

### 5.7 一次报全

校验不会在第一条问题上退出。就地结束整份校验的只有一种情况：归档打不开，`<file>: unreadable package (<原因>)` 直接抛出。「文件名后缀不对」只是让 `checkFileName` 提前返回，清单、说明书、`skill/`、`payload/` 各项检查照旧执行。所有问题攒进一个列表，最后一次性抛出：

```text
<file>: refused with <N> problem(s):
  - <原因 1>
  - <原因 2>
```

按列表逐条处理、改完重跑，会看到剩下的问题。清单读不出来时，进列表的是 `readManifest` 在返回空值之前先记下的那一条，例如 `<file>: refused with 1 problem(s):` 下面跟着 `no plugin.yaml at the archive root — a package without a manifest cannot be registered`；超限、空文件、非映射、YAML 语法错误各给出自己的那一条（见 5.5 节与 13 节）。`<file>: plugin.yaml is missing` 这句拿不到控制权——返回空值的每条路径都先加了一条问题，列表非空时抛出的就是列表。

## 6. `skill/SKILL.md` 怎么写

`skill/SKILL.md` 是必需条目：`no skill/SKILL.md — a CLI ships its own skill, and one without it leaves the agent with a binary it has been told nothing about`。内容为空白时：`skill/SKILL.md is empty`。

### 6.1 frontmatter

技能描述取自这份 markdown 的 frontmatter，由与技能加载器共用的 `SkillFileParser.parseMeta` 读出。`name` 存在且与 `plugin.yaml` 的 `name` 不同即被拒：

`skill/SKILL.md declares name "<skillName>" while plugin.yaml declares "<pluginName>" — the skill takes the CLI's name, so remove the frontmatter name or fix it`

推荐写法是不写 `name`，让技能继承 CLI 名。`description` 建议写：它会成为技能记录的描述列。

### 6.2 正文四要素

正文会进入绑定智能体的提示词，所以按「能直接照做」来写，四块内容：

1. 这个 CLI 解决什么问题，一句话；
2. 装在哪里、命令叫什么，给出容器内的可执行路径；
3. 常用命令的形状，每个给一条可直接复制的完整命令行；
4. 出错时看什么：认证、网络、参数三类各自的判断依据。

### 6.3 命令面很大的 CLI：索引式写法

命令数上百时不要逐条罗列。正文写一小节高频命令的完整示例，其余按主题分组给「命令名 + 一行用途」，让模型按需要再取 `--help`。完整的命令清单适合放资源文件，路径写进正文。

### 6.4 `skill/assets/`

键是去掉 `skill/assets/` 前缀后的相对路径，值是文本内容，整体作为技能的资源集合入库。限制：单个 524288 字节、总量 4194304 字节、必须是 UTF-8 文本，否则 `<entry> is not UTF-8 text — a skill's resources ship as text, binaries belong in payload/`；键本身要过 `checkRelativePath`，不过时报 `<原因> (entry <path>) — a skill's resource keys are the paths they are written to, so one has to name exactly one location`。

## 7. 参数与凭证：`envParams` 还是 `runtimeEnv`

两张表解决两件不同的事，选错的后果是凭证进了分发的 zip。

### 7.1 `envParams`：声明「这个 CLI 需要哪些环境变量」

每一项是一个映射，键为 `envParamName`（必填，匹配 `^[A-Za-z_][A-Za-z0-9_]{0,63}$`）、`description`、`required`、`secret`、`defaultValue`。它只是声明：告诉在智能体上配置的人这里要填什么。

- 条目不是映射：`plugin.yaml envParams entries must be mappings, found <item>`
- 缺 `envParamName`：`plugin.yaml envParams entry is missing envParamName`
- 名字不合形：`plugin.yaml envParams name "<paramName>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$`
- 同名声明两次：`plugin.yaml envParams declares <[names]> more than once`
- `secret: true` 同时带非空 `defaultValue`：`plugin.yaml envParams "<paramName>" is marked secret and carries a defaultValue — a package is distributed as a plain zip, so credentials belong in env-var bindings, not here`

参数值在智能体上填写，由 `agent_cli_binding.env_bindings` 保存为该智能体的快照。智能体表单按包声明逐条渲染参数行，输入框的值一律起始为空——包里的 `defaultValue` 不预填进表单，所以「这一项还没人填过」在界面上始终看得见。

下发时起作用的是另一条规则：智能体填过的值优先，没填过的键由包声明的 `defaultValue` 补齐；两边都没有值的键不发送（一个不存在的变量与 `TOKEN=` 对「靠变量名判断自己是否已配置」的 CLI 是两种状态）。因此必填校验只把「既没填、又没有默认值」的必填项算作缺项。

要在包里表达一个「建议值」，写进 `SKILL.md` 正文比写进 `defaultValue` 更清楚：后者会静默生效，前者由模型看见并解释。

非 secret 项的 `defaultValue` 明文入库；secret 项的值走加密器。页面读回时 secret 值掩码为前 3 后 4 字符，长度不足 7 或解密失败时整串替换为 `******`。

### 7.2 `runtimeEnv`：声明「平台在容器创建时注入什么」

这是一个 `变量名 -> 值` 映射。键合 `^[A-Za-z_][A-Za-z0-9_]{0,63}$`。值只有两种合法形状：字面量字符串，或者整体是一个平台槽占位符 `${...}`。平台只发布两个槽：`platform.adminUrl` 与 `platform.internalToken`。

- 键不合形：`plugin.yaml runtimeEnv key "<key>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$`
- 值不是字符串：`plugin.yaml runtimeEnv <name> must be a string, found <value>`
- 值为空白：`plugin.yaml runtimeEnv <name> has an empty value`
- 值里嵌了占位符但不是整体：`plugin.yaml runtimeEnv <name> writes "<value>" — a placeholder has to be the whole value, as in <name>: ${platform.adminUrl}`
- 槽不在支持列表：`plugin.yaml runtimeEnv <name> asks for <value>, which the platform does not publish (supported: platform.adminUrl, platform.internalToken)`

注入发生在容器创建时，不参与镜像内容，因此改 `runtimeEnv` 不改镜像指纹。槽值由部署侧提供；某个部署没有发布该槽时，变量整体不设，并在 agent 日志里留一条警告点名槽名。

同一变量名同时被智能体级绑定覆盖：绑定值最后写入，所以它赢。

### 7.3 选择依据

凭证 → 智能体上绑定，包里的声明只写 `secret: true` 不带默认值。平台自身的地址与内部令牌 → `runtimeEnv` 用槽；不要把 URL 硬编码进包，那会让同一个包在不同部署上不可用。固定常量（比如某个 CLI 的日志级别）→ `runtimeEnv` 写字面量。需要每个智能体不同值的 → `envParams` + 智能体绑定。

## 8. `checkCommand`：唯一的验收钩子

`checkCommand` 必填且非空白，缺时报 `plugin.yaml: checkCommand is required`。

平台在镜像构建完成后，用 `docker run --rm --entrypoint /bin/sh <tag> -c "<checkCommand>"` 跑一次。任何非零退出都会把刚构建出的镜像 `docker rmi -f` 删掉，并让这一次镜像解析抛错，报错文本尾部带上命令输出最后 500 字符。

它不参与 `payloadDigest`，也不单独决定镜像标签：标签哈希材料是 `cliId`、`version`、`payloadDigest` 与基础镜像名。所以只改 `checkCommand` 会让 `packageDigest` 变化（整包字节变了），对象存储里多一个归档、`cli` 行的 `check_command` 被更新，但已经在跑的容器和它们的镜像不受影响，下一次构建才用新命令。

写法的四条实用约束：

1. 它是 `/bin/sh -c` 的入参，基本探测最合适：`<binary> --version`、`<binary> --help`、`command -v <binary>`。
2. 它不带网络假设，不要放需要凭证或需要出网的命令。
3. 它跑在 `deps.apt` 安装完之后，可以依赖 apt 装的东西。
4. 一个镜像按智能体的 CLI 集合整体构建，集合里每个 CLI 的 `checkCommand` 都要通过；一个不过，这一集合的镜像就没有。

## 9. 打包

### 9.1 最小的一个包

```text
mycli-1.4.0.harnaxcli.zip
├── plugin.yaml
├── skill/
│   └── SKILL.md
└── payload/
    └── usr/local/bin/mycli
```

`plugin.yaml`：

```yaml
name: mycli
version: 1.4.0
description: One-line description shown on the CLI page
checkCommand: mycli --version
deps:
  apt:
    - ca-certificates
envParams:
  - envParamName: MYCLI_TOKEN
    description: Token for the mycli service
    required: true
    secret: true
runtimeEnv:
  MYCLI_URL: ${platform.adminUrl}
  MYCLI_LOG: info
```

### 9.2 打包步骤

```bash
cd mycli-1.4.0-src
chmod 755 payload/usr/local/bin/mycli
zip -X -r ../mycli-1.4.0.harnaxcli.zip plugin.yaml skill payload
```

`-X` 不写额外字段，同时在多数平台上保留权限位。权限位是否真的进了中央目录，可以用 `zipinfo -v` 看外部文件属性字段是否为八进制 mode；Windows 上打包通常拿不到，这类包按 5.4 节那两条被拒。

macOS 上 Finder 的压缩会塞进 `__MACOSX/`，用命令行 `zip` 而不是 Finder，或打包后显式剔除。

### 9.3 打包前自查

```bash
# 布局：只有 plugin.yaml / skill/ / payload/ 三个落点
unzip -Z1 mycli-1.4.0.harnaxcli.zip | sed 's#/.*##' | sort -u
# 条目清单
zipinfo -1 mycli-1.4.0.harnaxcli.zip | grep '^payload/'
# 同名条目
unzip -Z1 mycli-1.4.0.harnaxcli.zip | sort | uniq -d
```

## 10. 上架与下架

**上架**：把 `.harnaxcli.zip` 放进 admin 配置的包目录（配置项 `harnax.cli.package-dir`，容器化部署里就是货架目录），重启 admin 触发一次同步。启动完成时打印一行 `Sync complete: N registered, M failed`。

**升级**：把新版本文件放进同一目录。同名两包同时在场时，清单版本更高的那个被登记，另一个被跳过并留一行 ERROR。版本号按 `[._+-]` 分段、数字段按数值比较，所以 `1.0.10` 比 `1.0.9` 新；预发布后缀只是另一段，因此 `1.0.0-rc1` 排在 `1.0.0` 之后。同名同版本而文件不同的两个包，谁都不登记，并且这会让同一趟同步里的清理整段跳过。

**下架**：把文件从目录里移除，重启 admin。平台硬删这一行 `cli`、按各技能删除的惯例逻辑删除它自带的技能（连同技能上的智能体绑定与团队绑定），并删掉对象存储里的归档。有三道闸会让清理不执行：这一趟同步里有包解析失败；待清理行数不少于注册过的行数；这一行的 `package_digest` 为空（不是包写出来的行，清理不碰）。

**临时停用（kill switch）**：页面上把状态切成停用。这是唯一一条重启后保持的写路径——`status` 不被登记覆盖，自带技能的启停跟随它。停用不要求先解绑智能体；影响面通过 `related-agents` 与 `related-sessions` 两个只读接口在拉闸前量出来。

## 11. 发布前自检清单

- [ ] 文件名 = `<name>-<version>.harnaxcli.zip`，且 `<name>` 与 `plugin.yaml` 的 `name` 一字不差
- [ ] `name` 以小写字母开头，只含小写字母、数字、连字符，长度 2–64
- [ ] `version` 以字母或数字开头，只含字母数字与 `.` `_` `+` `-`，总长度 ≤ 32
- [ ] 顶层键只有七个认识的名字，没有拼错的第八个
- [ ] `skill/SKILL.md` 存在、非空、frontmatter 不声明冲突的 `name`
- [ ] `skill/assets/` 全是 UTF-8 文本
- [ ] `payload/` 至少一个文件，且至少一个带可执行位
- [ ] `payload/` 里没有符号链接、没有 setuid/setgid/sticky、落点不在拒绝清单上
- [ ] 路径已规范化：没有 `//`、没有 `./`、没有 `../`、没有 `:`
- [ ] secret 项不带 `defaultValue`
- [ ] `runtimeEnv` 的占位符是整体值，槽名只有两个候选
- [ ] `deps.apt` 每项是裸包名或 `名=版本`，无空格、无 `;`、无 `$`、无反引号、不以 `-` 开头
- [ ] 在保留权限位的文件系统上打包
- [ ] 二进制是目标容器架构（Linux），不是开发机本机格式

## 12. 完整实例

### 12.1 平台自身 CLI：`harnax`

`harnax-cli/plugin.yaml` 是一个包全部内容只有四个字段加 `runtimeEnv` 的最小例子：

```yaml
name: harnax
version: 1.0.0
description: Harnax 平台管理命令行，在沙箱内以注入的内部令牌读写 Agent、模型、技能、会话等资源
checkCommand: harnax --version
runtimeEnv:
  HARNAX_URL: ${platform.adminUrl}
  HARNAX_TOKEN: ${platform.internalToken}
```

没有 `deps.apt`，因为 payload 是一个 CGO-free 静态二进制，镜像不需要额外系统层。`harnax-cli/Makefile` 的 `package` 目标做的事是：清空暂存目录与整个 `dist/*.harnaxcli.zip`（一个版本残留的 zip 就是第二个声明同名的包）、按 `GOOS`/`GOARCH` 交叉编译并注入 `-X main.version`、把 `plugin.yaml` 与 `SKILL.md` 分别摆到 `plugin.yaml` 与 `skill/SKILL.md`、`chmod 755` 二进制、然后 `zip -X -q -r` 三个落点。

`cli-packages/build.sh` 再把它复制到货架。Go CLI 本身有 25 个 `.go` 文件，命令面覆盖 agent / model / skill / session / task / mcp / tool / channel / envvar / apikey / user / tenant / health / config / cli，`harnax-cli/SKILL.md` 用索引式写法覆盖这些命令组。`cli` 这一组是 `harnax cli list`、`harnax cli get <id>`、`harnax cli toggle <id>`，三条都打 `/api/admin/clis`（`harnax-cli/cmd/cli_resource.go`）；`harnax cli` 的 `Short` 原文就是 `Inspect CLI plugin packages registered by admin (status is the only writable field)`——这一组只有状态可写，没有创建、更新、删除命令。

### 12.2 第三方 CLI：`lark-cli`

货架上 `cli-packages/lark-cli/` 是一个第三方包的完整源：`plugin.yaml` + `skill/SKILL.md` + `build.sh` + `checksums.txt`。`build.sh` 从上游取 `lark-cli-<version>-linux-amd64.tar.gz`，对照 `checksums.txt` 校验，把解出的二进制放进 `payload/usr/local/bin/`，再打成 `dist/lark-cli-1.0.96.harnaxcli.zip`。

它的清单说明了三类判断：

- `checkCommand: lark-cli --version`。上游用 cobra，暴露 `--version` 标志但没有 `version` 子命令，所以写 `lark-cli version` 会以「unknown command」失败并让整张镜像拿不到；`--version` 是证明二进制带着可执行位落地的最小手段。
- 没有 `deps.apt`：一个静态 Go 二进制，没有共享库需求，构建保持离线。
- 用 `envParams` 而不是 `runtimeEnv`：`LARKSUITE_CLI_APP_ID`（`required: true`、`secret: false`）与 `LARKSUITE_CLI_APP_SECRET`（`required: true`、`secret: true`）。平台没有为某个第三方应用发布槽，而包是明文 zip，所以凭证一律由智能体侧填写。

三点值得抄：二进制按 linux/amd64 取（在开发机上直接打包本机格式会产出容器里跑不起来的包，而失败要到镜像构建后的校验阶段才可见）；版本从上游版本串原样带入 `plugin.yaml` 与文件名两处；`checksums.txt` 让「取到的是哪串字节」在源码里可读，`payloadDigest` 让它在平台侧可核对。

## 13. 拒绝原因原文 → 改法

| 拒绝原文（逐字） | 改法 |
| --- | --- |
| `file name must end with .harnaxcli.zip` | 文件名后缀改成 `.harnaxcli.zip` |
| `file name must be <name>-<version>.harnaxcli.zip, got "<file>"` | 补上版本段 |
| `file name "<file>" does not start with the declared name plus a version, as in "<name>-1.4.0.harnaxcli.zip" — rename the file, the declared name is the identity` | 以声明名为准重命名文件，不要反过来改 `name` |
| `unreadable package (<原因>)` | 检查 zip 是否被截断、是否 0 字节 |
| `no plugin.yaml at the archive root — a package without a manifest cannot be registered` | 清单放归档根，不要放进子目录 |
| `plugin.yaml is empty` | 至少写四个必填字段 |
| `plugin.yaml must be a YAML mapping, found <类型>` | 顶层改成映射 |
| `plugin.yaml is not valid YAML: <problem>` | 按 problem 改；重复键会在这里报出来 |
| `plugin.yaml is over the 65536 limit — the manifest describes a CLI, it is not where a package parks data` | 把数据从清单里拿出去 |
| `unknown plugin.yaml key(s) <[键]>: the manifest is the only metadata source, so an unrecognised key is a typo that would otherwise be dropped` | 删掉，或改成七个认识的名字之一 |
| `plugin.yaml: name is required` | 补 `name` |
| `name "<n>" must match ^[a-z][a-z0-9-]{1,63}$ (lowercase letters, digits and dashes; it is also the skill name and the file-name stem)` | 去掉大写、下划线、点；改成以小写字母开头 |
| `plugin.yaml: version is required` | 补 `version` |
| `version "<v>" must match ^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$` | 去掉首字符符号与不允许的字符 |
| `plugin.yaml: description is required` | 补 `description` |
| `plugin.yaml: checkCommand is required` | 补 `checkCommand` |
| `plugin.yaml deps has an unsupported section "<k>" — only deps.apt exists` | 删掉 `deps` 下的其他段 |
| `plugin.yaml deps.apt must be a list of package names` | 每项改成裸字符串，不要写成嵌套映射 |
| `plugin.yaml deps.apt lists <N> packages, over the 50 limit` | 精简依赖 |
| `plugin.yaml deps.apt entry "<x>" is not a plain apt package name — it is interpolated into an apt-get command line, so only [a-z0-9+.-] and an optional =version are allowed` | 去掉空格、`;`、`$`、反引号、`-` 前缀、`>`/`<` 比较符 |
| `plugin.yaml deps.apt lists <[x]> more than once` | 去重 |
| `plugin.yaml envParams entries must be mappings, found <item>` | 每项写成映射 |
| `plugin.yaml envParams entry is missing envParamName` | 补 `envParamName` |
| `plugin.yaml envParams name "<p>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$` | 改成合法 shell 变量名形状 |
| `plugin.yaml envParams declares <[x]> more than once` | 去重 |
| `plugin.yaml envParams "<p>" is marked secret and carries a defaultValue — a package is distributed as a plain zip, so credentials belong in env-var bindings, not here` | 删掉 `defaultValue`，或把 `secret` 改成 `false` |
| `plugin.yaml runtimeEnv key "<key>" must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$` | 改键名 |
| `plugin.yaml runtimeEnv <n> must be a string, found <v>` | 值加引号，不要写成嵌套映射或裸数字 |
| `plugin.yaml runtimeEnv <n> has an empty value` | 给非空值，或删掉这一项 |
| `plugin.yaml runtimeEnv <n> writes "<v>" — a placeholder has to be the whole value, as in <name>: ${platform.adminUrl}` | 拆成「一个字面量」或「一个整体槽」 |
| `plugin.yaml runtimeEnv <n> asks for <v>, which the platform does not publish (supported: platform.adminUrl, platform.internalToken)` | 只用这两个槽，或改写字面量 |
| `no skill/SKILL.md — a CLI ships its own skill, and one without it leaves the agent with a binary it has been told nothing about` | 加说明书，哪怕只写最小四要素 |
| `skill/SKILL.md is over the 1048576 limit — a skill teaches one CLI, and past this size it is documentation that belongs next to the binary` | 长文档移到 `payload/`，正文只留索引 |
| `skill/SKILL.md is empty` | 写内容 |
| `skill/SKILL.md declares name "<s>" while plugin.yaml declares "<p>" — the skill takes the CLI's name, so remove the frontmatter name or fix it` | 删掉 frontmatter 的 `name` |
| `<原因> (entry <path>) — a skill's resource keys are the paths they are written to, so one has to name exactly one location` | 资源键规范化成一个位置一种写法 |
| `<entry> is <N> bytes, over the 524288 per-asset limit` | 拆文件 |
| `<entry> ships more than the 524288 per-asset limit` | 同上（中央目录自报值超限） |
| `skill assets exceed the 4194304 byte budget (<entry> is the one that tipped it)` | 精简资源总量 |
| `<entry> is not UTF-8 text — a skill's resources ship as text, binaries belong in payload/` | 二进制改放 `payload/` |
| `no files under payload/ — the payload is what the image copies in, a CLI with nothing to install does not need a package` | 没有要装的文件就不要做包 |
| `payload has <N> files, over the 2000 limit` | 只装必要文件 |
| `payload unpacks to <N> bytes, over the 536870912 limit` | 超过 512 MiB 说明不该由包整体携带 |
| `payload path lands on a denied target: <path> (entry <归档内路径>)` | 换目的地，`usr/local/bin/` 是默认选择 |
| `path escapes its root: <path> (entry <归档内路径>)` | 去掉 `../` 段 |
| `path is not in canonical form: <path> (entry <归档内路径>)` | 去掉 `.` 段 |
| `path has an empty segment: <path> (entry <归档内路径>)` | 去掉连续斜杠与尾随斜杠 |
| `path must be relative: <path> (entry <归档内路径>)` | 去掉开头的 `/` |
| `path contains a colon: <path> (entry <归档内路径>)` | 改掉冒号 |
| `empty path (entry <归档内路径>)` | 条目名去掉前缀后为空，检查 `payload/` 下的层级 |
| `<entry> is a symbolic link — extraction writes file content, never links, so it would arrive as a text file holding a path` | 把链接目标作为真实文件放进包 |
| `<entry> carries setuid/setgid/sticky bits (mode <八进制>)` | `chmod 755`；需要提权的行为交给部署侧，不交给包 |
| `<列表> carry no unix mode, so they would land 0644 and fail as "permission denied" — repack on a filesystem that stores permissions (zip -X, or the platform packer)` | 在 Linux/macOS 文件系统上用 `zip -X` 重打 |
| `no payload file carries an execute bit — nothing the check command could run would start` | `chmod` 主二进制后重打 |
| `unexpected entr(y\|ies) outside plugin.yaml, skill/ and payload/: <列表>` | 剔除多余条目（常见是 `__MACOSX/`、`.DS_Store`、`README.md`） |
| `<name> appears more than once in the archive — one name has to mean one entry, because the digests hash every copy while reading answers with the first and extraction keeps the last` | 重打，确保每个名字一条条目 |

## 14. 边界与已知的坑

- **包不是容器。** `payload/` 只能拷文件，没有「构建期执行」这一步。系统级准备走 `deps.apt`；apt 解决不了的，那个 CLI 不该以包形式上架。
- **一个镜像 = 一个智能体的 CLI 集合。** 集合按 `cliId` 排序后与基础镜像名一起哈希出标签。给智能体加一个 CLI 会构建一个新镜像，集合里每个 CLI 的 `checkCommand` 都要通过。
- **改 `SKILL.md` 不重建镜像。** `payloadDigest` 只看 payload 与 apt，所以说明书措辞变化会更新提示词、保留运行中容器的镜像。
- **改 `version` 会换镜像标签。** 标签哈希材料里有 `version`，只改版本号（payload 字节完全相同）也产出一个新标签、触发一次构建。
- **`runtimeEnv` 的槽没发布时变量整体缺失，而不是空串。** CLI 报自己的错，平台不替它兜底。
- **同名歧义会冻结清理。** 两个同 name 同 version 的文件会让整个 prune 过程跳过，因此把文件从架上拿走并不会让对应行注销，直到歧义解除。
- **`status` 是唯一重启后保持的列。** 其余列每次启动都被包里的值覆盖，所以「在页面上编辑一个字段」没有对应通路——页面只能读和停用。
- **空货架不是「什么都不做」。** 目录存在但一个包都没有时日志给一行 WARN，清理照常执行，把注册过的行删掉。
- **`deps.apt` 在拼进 `apt-get` 前会再排序去重一次**，包内顺序既不影响 apt 命令行，也不影响摘要（摘要用排序后的列表）。
- **平台不做架构探测。** 包里放开发机架构的二进制、容器要另一种架构，登记期发现不了，失败落在镜像构建后的 `checkCommand`。
- **`skill/assets/` 只能放文本。** 图片这类资源没有通路：技能资源以字符串形态存，非 UTF-8 直接拒。

## 15. 关键文件索引

| 路径 | 内容 |
| --- | --- |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParser.kt` | 全部校验规则与拒绝文案；`plugin.yaml` 的读取 |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageLayout.kt` | 布局常量、四个正则、payload 拒绝清单、路径规范化、两个摘要的算法 |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/CliPackageArchive.kt` | 只读归档视图：条目模式、符号链接与特殊位判定、`readBounded`、`extractTree` |
| `harnax-common/src/main/kotlin/com/agnetix/harnax/common/cli/ZipCentralDirectoryModes.kt` | 从 zip 中央目录外部属性读 unix mode |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParserTest.kt` | 每条拒绝原因的用例 |
| `harnax-cli/plugin.yaml` | 平台自身 CLI 的清单 |
| `harnax-cli/Makefile` | `package` 目标：交叉编译并自打一次包 |
| `harnax-cli/SKILL.md` | 说明书的索引式写法样本 |
| `cli-packages/lark-cli/plugin.yaml` | 第三方包的完整清单 |
| `cli-packages/lark-cli/skill/SKILL.md` | 第三方包的说明书 |
| `cli-packages/lark-cli/build.sh` | 取上游二进制、校验摘要、打包 |
| `cli-packages/lark-cli/checksums.txt` | 上游产物的摘要 |
| `cli-packages/build.sh` | 货架构建：整架不落空、同名只留一个 |

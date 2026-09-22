# Harnax CLI 插件包规范

> 英文版本见 [cli-package-spec.en-US.md](./cli-package-spec.en-US.md)
>
> 平台侧链路（登记、MinIO、镜像构建、下发、页面与接口、设计决策与边界）见 [cli-plugin-package-design.zh-CN.md](./cli-plugin-package-design.zh-CN.md)。
>
> 本文是**给包作者的契约**：字符集、上限、拒绝清单、写法要求、打包与上架，逐条对应代码。真相源是 `harnax-common/.../cli/CliPackageLayout.kt` 与 `harnax-admin/.../registrar/CliPackageParser.kt`，两者改了要同步回来。

## 0. 这份文档回答什么

三类读者，三个问题：

| 你是谁 | 你想做的事 | 看哪节 |
|---|---|---|
| 手上有一个现成 CLI（Go / Rust / Node 打出的二进制），想让 agent 会用 | 把上游二进制打成 harnax 包 | §2、§4、§9、§13 |
| 自研一个 CLI 作为产品能力 | 从第一次提交起就按规范走 | §4~§8、§11 |
| 运维：包放进目录了，页面上没有 | 找到拒绝原因 | §10、§13、§14 |

范围只到「一个 `.harnaxcli.zip` 被打出来并放上包目录」。包放上去之后平台怎么处理，是设计文档的事。

## 1. 一句话模型

**一个包 = 一份二进制负载（`payload/`）+ 一份技能说明书（`skill/SKILL.md`），两份必须同时存在。**

- `payload/` 决定**能力**：容器里有没有那个可执行文件；
- `skill/SKILL.md` 决定**可知**：模型是否知道该敲什么命令、参数怎么给。

只给二进制的包，对模型等于不存在——它不知道命令名，也不知道你这个 CLI 的规矩。所以说明书不是可选项，校验器会直接拒掉没有 `SKILL.md` 的包。

一个包只带**一份**说明书，并且它的技能名强制等于 CLI 名（§6.1）。命令面很大的 CLI 靠「索引式写法」解决，见 §6.3。

## 2. 包布局与文件名

```
lark-cli-1.0.96.harnaxcli.zip
├── plugin.yaml                    # 唯一元数据来源，平台不读别处的配置
├── skill/
│   ├── SKILL.md                   # 必需
│   └── assets/<path>              # 可选（运行侧暂不承诺送达容器，§14-5）
└── payload/                       # 打进镜像的文件树
    └── usr/local/bin/lark-cli     # 相对路径 = 容器内绝对路径
```

只有这三处。包里的第四个位置（`README.md`、`__MACOSX/`、`.DS_Store`、`deps/`……）一律拒绝——游离条目要么被静默忽略（作者会一直带着它），要么污染摘要。

文件名规则两条，都参与校验：

1. 后缀必须是 `.harnaxcli.zip`，去掉后缀的名字里要有一个 `-` 分隔版本段；
2. 文件名必须以 `plugin.yaml` 声明的 `name` 加一个 `-` 开头。

第 2 条是给打包脚本兜底的：名字写错等于顶着另一个 CLI 的身份发布，而包目录里看不出出错。版本段只影响人眼，平台不会拿它和 manifest 的 `version` 比对——**登记身份永远取 manifest**。

## 3. 平台对包做的三件事（作者需要知道的后果）

1. **只读不执行**：登记过程不运行包内任何文件。`checkCommand` 也只在**构建出来的新镜像里**执行，不在宿主机上。
2. **两个摘要**：`packageDigest = sha256(zip 整个文件)` 是包的身份，决定 MinIO 对象键和「要不要重新登记」；`payloadDigest` 只覆盖 `payload/` 文件树与 `deps.apt`，决定镜像 tag。改说明书不会重建镜像，换二进制会。
3. **按 `name` 覆盖**：一个 `name` 只有一行记录，新版本覆盖同一行，不并存多版本。

## 4. `plugin.yaml` 字段

七个键，多一个都没有：

| 字段 | 必填 | 形状 | 语义 |
|---|---|---|---|
| `name` | 是 | `^[a-z][a-z0-9-]{1,63}$` | 全平台唯一，即身份；同时是技能名和文件名前缀 |
| `version` | 是 | `^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$` | 不参与唯一性；同名多包时由它裁决谁上架；会进生成的 Dockerfile，所以字符集不含换行 |
| `description` | 是 | 非空文本 | 给管理员看的一行话，出现在 CLI 页与智能体配置里 |
| `checkCommand` | 是 | 非空文本 | 构建后在新镜像内执行，非零即作废整个镜像。作者唯一的验收钩子，见 §8 |
| `deps.apt` | 否 | 字符串列表，≤50 条、不重复 | 单条形状 `^[a-z0-9][a-z0-9+.-]{0,62}(=[0-9A-Za-z.+-]{1,32})?$`；留空则构建期零网络 |
| `envParams` | 否 | 对象列表，`envParamName` 不重复 | 管理员在每个智能体上填的 env 声明，见 §7 |
| `runtimeEnv` | 否 | key → value | 平台在容器创建那一刻注入的固定 env，见 §7 |

**没有 `install` 段，也没有任何接受脚本的字段。** 平台对包只做两件事：把 `payload/` 铺到镜像的对应路径上，按 `deps.apt` 装系统包。你不能假设容器里有网络，也不能让它跑你自己的安装逻辑。

三个容易踩的严格性：

- 出现未识别的键 ⇒ 拒绝。静默丢弃会让一个自认为配好了的包其实没配。
- 同一个键写两次 ⇒ 拒绝（YAML 解析器禁了重复键，不是后者覆盖前者）。
- `deps` 下只认 `apt`；写 `deps.pip` 会被拒绝，而不是被忽略。

`version` 的排序语义：**逐段比数值**，所以 `1.0.10` 比 `1.0.9` 新。预发布后缀在这里只是又一段文字，`1.0.0-rc1` 反而判得比 `1.0.0` 新——裁决只回答「同名两份文件谁上架」，这个反向可以接受。

## 5. 硬约束：什么会被拒

### 5.1 路径必须只有一种拼法

`payload/` 与 `skill/assets/` 下的每个名字都必须是规范化相对路径。以下一律拒绝：

- 绝对路径（`/usr/bin/x`）；
- 空段（`usr//bin/x`）；
- `.` 段（`./etc/x`、`usr/./bin/x`）；
- `..` 段（`../x`）；
- 段内含冒号（`bin/a:b`）、含 NUL 字节；
- 空名字。

要求唯一拼法是因为门内门外两套代码：登记侧按文本判拒绝清单，运行侧把路径拼到目标目录下再归一化。`usr/./bin/docker` 这种路径文字上过、落地后落在拒绝清单上，只有一边会拦。同一份不变量还兜住摘要——`payload/./usr/bin/x` 与 `payload/usr/bin/x` 是一个文件的两份拼法，两条都进指纹的话镜像 tag 就不再描述镜像内容。

### 5.2 拒绝落点清单

`payload/` 里的目标路径不得落在这些位置（按**路径段**比较）：

| 条目 | 匹配方式 | 命中示例 | 不命中示例 |
|---|---|---|---|
| `etc/` | 目录前缀 | `etc/passwd` | `etc2/x` |
| `root/` | 目录前缀 | `root/.ssh/authorized_keys` | `rootfs/app` |
| `sbin/`、`usr/sbin/` | 目录前缀 | `sbin/x` | — |
| `usr/bin/docker`、`usr/local/bin/docker` | 完整路径 | `usr/bin/docker` | `usr/bin/docker-compose` |
| `bin/su`、`usr/bin/su` | 完整路径 | `usr/bin/su` | `usr/bin/su-client` |
| `var/run/docker.sock` | 完整路径 | — | — |

这是纵深防御一层，不是沙箱：能往包目录放文件的人，本来就能决定所有 agent 沙箱里以 root 落什么文件。清单挡的是无心之失和越界的一部分。

请把 CLI 装在 `usr/local/bin/`（或它自己的 `usr/local/lib/<name>/` 下）。

### 5.3 条目形状

- 符号链接 ⇒ 拒绝（解包只写文件内容，链接会变成一个装着路径文本的文件）；
- setuid / setgid / sticky 位 ⇒ 拒绝；
- 同一个名字在包里出现两次 ⇒ 拒绝（摘要把每一份都算进去，而读取只认第一个、解包只留最后一个）；
- `payload/` 下没有任何文件 ⇒ 拒绝（没有要装的东西就不需要一个包）；
- 整棵 payload 没有一个可执行位 ⇒ 拒绝（这个包装不出任何命令）。

### 5.4 权限位来自打包器

可执行位由 zip 中央目录的 unix mode 携带，平台**不改写也不补**。因此：

```bash
chmod 755 stage/payload/usr/local/bin/mycli   # 先 chmod
(cd stage && zip -X -q -r ../mycli-1.0.0.harnaxcli.zip plugin.yaml skill payload)   # 再打包
```

打包器压根没写 mode 时，登记会被拒绝，而不是悄悄把二进制解成 `0644`——后者要到镜像构建期才以 `checkCommand` 退出 126（Permission denied）的形式暴露，归因成本高得多。用 Windows 侧工具或某些归档库打的包最容易踩这条。

### 5.5 大小与数量上限

| 对象 | 上限 | 超限后果 |
|---|---|---|
| `plugin.yaml` | 64 KB | 拒绝 |
| `skill/SKILL.md` | 1 MB | 拒绝 |
| 单个 `skill/assets/` 文件 | 512 KB | 拒绝 |
| `skill/assets/` 合计 | 4 MB | 拒绝 |
| payload 文件数 | 2000 | 拒绝 |
| payload 解包后字节数 | 512 MB | 拒绝 |
| `deps.apt` 条数 | 50 | 拒绝 |

文本类上限边读边算长度，中央目录里那个 size 不作依据（它由打包器自己填，流式写入时干脆是 `-1`）。

### 5.6 一次报全

校验器不会在第一条就停下：一个包有 5 处问题，日志里就是 5 行。全部修完再重启，比一轮一轮试快。

## 6. `skill/SKILL.md` 怎么写

### 6.1 frontmatter

```yaml
---
name: lark-cli
description: 用 lark-cli 命令行读写飞书 / Lark 开放平台资源……当用户要操作飞书里的对象时使用。
---
```

- `name` **必须等于** `plugin.yaml` 的 `name`，否则拒绝。一个技能行不能同时回答两个身份；
- `description` 决定技能列表里那一行，也是模型决定「要不要用这个 CLI」的第一道信号。写清**能操作什么对象**和**什么场景该用**；
- 正文没有 frontmatter `name` 时，技能名回落到 CLI 名——所以最省事的写法是干脆不写 `name`。

### 6.2 正文四要素

一份能用的说明书回答这四件事，按这个顺序写最省事：

1. **什么时候用**：一句话说清这个 CLI 覆盖哪类对象；
2. **命令怎么写**：给 2~4 条**可以直接抄**的命令，标出参数怎么给；
3. **凭证与前置**：需要哪些环境变量（和 §7 的 `envParams` 一一对应）、要不要登录、怎么确认自己可用（`whoami` 这类自检命令）；
4. **边界与失败怎么读**：哪些错不是 agent 能自己修的（权限没开、配额、平台侧配置），报出来该找谁。

模型只能读到你写的这一份文件。含糊的地方它一定会猜，猜错了表现为「命令不存在」或「参数不对」。

### 6.3 命令面很大的 CLI：索引式写法

一个包只能带一份说明书，但有些 CLI 有几十个域、上百条命令。不要把它们全塞进 `SKILL.md`——那会撞上 1 MB 上限，而且模型读不完。

写法是：**`SKILL.md` 当地图，深度留给 CLI 自己**。lark-cli 这个包就是这么做的（§12）：正文只讲选型顺序、风险级别、输出控制，然后指一条自查命令：

```bash
lark-cli skills list                 # 有哪些域指南
lark-cli skills read lark-calendar   # 读某一域的完整用法
```

这样做还有个附带好处：域指南跟着二进制一起发版，永远不会和文档站脱节。

前提是目标 CLI 本身提供可自查的 help / 文档子命令。如果它没有，你要么把正文控制在最常用的 20% 命令上，要么先给上游补一个。

### 6.4 `skill/assets/`

允许带，路径规则同 §5.1（键就是它被写到的相对路径）。**但当前运行侧不承诺把这些文件投影进沙箱**（§14-5）。所以：技能正文要能独立成立，别让 `SKILL.md` 指着几个容器里取不到的文件。

## 7. 参数与凭证：`envParams` 还是 `runtimeEnv`

| | `envParams` | `runtimeEnv` |
|---|---|---|
| 值由谁给 | 管理员在智能体向导的 CLI 步骤上逐项填 | 平台在容器创建时注入 |
| 包里能带默认值 | 能（`defaultValue`），但 `secret: true` 时**不许**带 | 不适用 |
| 值的形状 | 任意字符串 | 字面量，或 `${platform.<slot>}` 且锚点独占整个值 |
| 存储 | 声明随包入库（`secret` 项的默认值 AES 加密，页面掩码）；值：引用只存指针（全局变量本身加密入库），手填的字面值明文进绑定快照 | 明文入库（不含密钥） |
| 典型用途 | 第三方平台的 App ID / Token / 端点 | CLI 需要知道平台地址与内部令牌才能工作 |

一句话判断：**值随部署或租户变 ⇒ `envParams`；值由平台自己提供 ⇒ `runtimeEnv`。** 无论哪种，**包里绝不放真密钥**——一个包就是一个明文 zip，会进对象存储、会被复制。

**谁来填、填在哪：** 智能体配置向导的 CLI 步骤是一卡一个 CLI（点「添加 CLI」加一张卡，卡上下拉里换要装的包），这张卡里就是这个包的参数表，你的每一个 `envParams` 声明占一行。每行两种给法：引用一个全局环境变量（页面存的是引用，值由运行时按引用现取），或选「自定义」直接填字面值。两种给法的安全差别不小：全局环境变量是加密入库的，引用只留下一个指针；手填的字面值则和工具、MCP 一样明文存进绑定快照，并被读接口原样回显——所以 `secret: true` 的参数该走引用。留空不是「填了个空串」，而是这一行整个不下发，由包的 `defaultValue` 顶上。同一份声明在「CLI 工具」页的「所需参数」列只读可见——那一格是一个数字徽标（数字即声明条数），悬停弹出一张小表格，逐行列出参数名、说明、必填、敏感与包内默认值，形状与工具页的「环境参数」列一致；那里不能改值，因为**参数属于包，值属于智能体**。

**`required: true` 是一道必答题：** 该声明在这个智能体上没有值、又没有任何兜底时，前端会在点「完成」时挡住并点名是哪个 CLI 的哪个参数，后端写入绑定时同样拒绝。算已填的三种情况：填了值、引用了全局环境变量、包带了 `defaultValue`。所以给打包的人的结论是——**不希望管理员漏填的参数就标 `required: true` 且别带 `defaultValue`；标 `required: false`，缺值的报错只会出现在运行期，由 CLI 自己负责。**

下发规则（决定了很多行为）：

- 每个 key 单独合并：智能体上覆盖的值优先，其余用包声明的 `defaultValue` 兜底；
- **一个声明没有任何值时不会下发**（不发空串）。对靠「环境变量存不存在」判断自己是否配好的 CLI，缺失和 `TOKEN=` 是两种状态；
- env 只在**容器创建那一刻**注入。改了参数、换了版本，都要**新会话**才生效（保活中的容器不会被换，见 §14-3）。

`runtimeEnv` 的两条硬规矩：

1. 槽位只有 `platform.adminUrl` 与 `platform.internalToken`，写别的直接拒绝；
2. 锚点必须独占整个值。`MY_URL: http://x/${platform.adminUrl}` 会被拒，拆成两个变量写。

## 8. `checkCommand`：唯一的验收钩子

执行方式与后果：

```bash
docker run --rm --entrypoint /bin/sh <新镜像> -c "<checkCommand>"
```

任一非零 ⇒ `docker rmi -f <新镜像>` 并抛异常。坏包不会流到运行时，代价是这次构建白做。

三条写法要求：

1. **必须在没有业务凭证的情况下通过。** 构建期既没有管理员填的 `envParams`，也还没到注入 `runtimeEnv` 的时机。任何「先登录才能跑」的写法都会让镜像永远构建不出来。
2. **要真的验到东西。** 最常见的合格写法是 `<cli> --version`：它同时证明了二进制在、执行位没丢、动态链接满足。
3. **注意子命令和标志的区别。** Go/cobra 类 CLI 常常只生成 `--version` 标志而没有 `version` 子命令，写成后者会以「未知命令」失败、退出码 1，镜像随之作废。harnax 自己的第 1 号包 `harnax --version` 和 lark-cli 的 `lark-cli --version` 都是这条的实测结果。

想验得更实，就挑一个**不需要网络也不需要凭证**的子命令（`--help`、列出内置资源的命令）。退出码含义：`126` 丢了执行位（回 §5.4）、`127` 命令名不对、`1` 多半是子命令写法。

## 9. 打包

### 9.1 最小的一个包

一个只需要存在的脚本工具，全部内容有这些：

```
greet-0.1.0.harnaxcli.zip
├── plugin.yaml
├── skill/SKILL.md
└── payload/usr/local/bin/greet      # mode 0755
```

```yaml
name: greet
version: 0.1.0
description: 问候工具
checkCommand: greet --version
```

`SKILL.md` 讲清「什么时候用、命令怎么写、参数怎么给」就够了——它是模型唯一的信息来源。

### 9.2 打包步骤

```bash
NAME=lark-cli VERSION=1.0.96 SHELF=../dist
rm -rf stage && mkdir -p stage/skill stage/payload/usr/local/bin
cp plugin.yaml stage/
cp SKILL.md stage/skill/SKILL.md
cp bin/lark-cli stage/payload/usr/local/bin/lark-cli
chmod 755 stage/payload/usr/local/bin/lark-cli
(cd stage && zip -X -q -r $SHELF/${NAME}-${VERSION}.harnaxcli.zip plugin.yaml skill payload)
```

三个动作各有原因：

- `chmod 755` 在 `zip` 之前：执行位靠中央目录携带（§5.4）；
- `zip -X`：不让额外字段进包，同一内容每次打成同一个包；
- 只 add 那三个入口：第四个位置会被拒（§2）。

**包脚本不清货架，连自己的旧版本也不清。** 旧版本留在架上是「同名两份」，但那正是有人手工投了升级包的样子——按名字 `rm` 会把它一起删掉，等于悄悄回滚。裁决放在 `cli-packages/build.sh`：留 `version` 高的那份，落选的移进 `cli-packages/dist/.superseded/`（§10）。

仓库里有三份可以直接抄的脚本——一份建整个货架，两份打单个包：

| 场景 | 位置 | 特点 |
|---|---|---|
| 货架：把有源码的包一次补齐 | `cli-packages/build.sh` | 不清架（手工丢进来的包照留），跑 `harnax-cli` 的 `make package` 和每个 `cli-packages/<name>/build.sh`，再把同名落选者移进 `.superseded/` |
| 自研 CLI，源码在同仓库 | `harnax-cli/Makefile` 的 `package` 目标 | `CGO_ENABLED=0 GOOS=linux GOARCH=amd64` 交叉编译 + 版本号取自 `plugin.yaml` |
| 第三方 CLI，二进制在上游 | `cli-packages/lark-cli/build.sh` | 打包期下载、按钉住的 sha256 校验、镜像源优先 |

`packageDigest` 对时间戳敏感：`zip` 会把每个条目的 mtime 写进包，同一份内容每次重打包都会得到**新的** `packageDigest`（`payloadDigest` 不变，因此不会多建镜像）。这是已知的运维账（§14-4），不是 bug。

## 10. 上架与下架

**上架 = 把包丢进货架** `cli-packages/dist/`，再走一次打包部署。登记只发生在 admin 启动时，没有「重扫一次」的接口：

```bash
cp mycli-1.2.0.harnaxcli.zip cli-packages/dist/                                   # 上架
cp cli-packages/dist/*.harnaxcli.zip docker-new/dist/cli-packages/                # 进构建输入目录
docker compose -f docker-new/docker-compose.yml up -d --force-recreate admin
```

后两行 `docker-new/build.sh` / `deploy-all.sh` / `deploy-service.sh admin` 都已经串好（它们先跑 `cli-packages/build.sh` 再整份复制），所以手工动作只有丢包那一次加一次部署。**货架不被清空**：`cli-packages/build.sh` 只把有源码的包（`harnax-cli` 与 `cli-packages/<name>/`）补齐上架，手工丢进来的包原样留下一起上线——这正是丢包这条路的意义。

`docker-new` 把宿主机 `docker-new/dist/cli-packages/` 那份以**只读 bind mount** 盖在容器的 `/home/harnax/cli-packages` 上，所以容器看到的就是这一份：不再有「镜像里是新包、跑的是旧包」的失同步，也不再需要 `docker cp`——挂载只读，`docker cp` 进容器会被直接拒掉。

**别把任何一份货架清空**：`cli-packages/dist/`（丢包那一份）与 `docker-new/dist/cli-packages/`（构建输入那一份）清空哪一份，后果都一样——目录缺失时 Docker 还会就地建一个空目录，admin 读到空货架就把架上所有 CLI 一起报成退役。下面「下架」的三道刹车会拦掉大部分误删，但它是兜底不是保险。

**看结果**：`docker logs harnax-admin | grep CliPackageAutoRegistrar`。页面不会告诉你某个包为什么没出现——放错目录、manifest 写坏，在页面上的表现都只是「没多出一条」。

**同名多包**：一个 CLI 名字在架上只留一份，取 `version` 数值最高的那个，落选的那份由 `cli-packages/build.sh` 移进 `cli-packages/dist/.superseded/`（是移走不是删除，手工投的包没有源码可重建）。同名同版本两份则构建直接失败——没有任何一侧能替你判断该留哪个：绕过脚本时 admin 的做法是两份都不登记、都计入失败。

**下架**：两处都删——`docker-new/dist/cli-packages/` 与货架 `cli-packages/dist/`（有源码的包还要删掉 `cli-packages/<name>/` 目录，否则下次构建又把它打回货架），重启 admin。后果是级联——`cli` 行硬删、三张绑定表清干净、自带技能行软删，WARN 列出包名与行 id。三道刹车保护你：

1. 本轮有任何包读取失败就不执行清理（此时没有可信的在架集合）；
2. 只有 `package_digest` 非空的行才是候选（登记器亲手写过的才允许它删）；
3. **待删数不小于剩余数即拒绝执行并打 ERROR**。

第 3 条在包目录很小时会咬到运维：架上只有 2 个包时删 1 个，比例正好落在拒绝的一侧。要合法退役，留下的包必须比待删的多。

## 11. 发布前自检清单

- [ ] 文件名是 `<name>-<version>.harnaxcli.zip`，且前缀等于 manifest 的 `name`；
- [ ] 包内只有 `plugin.yaml`、`skill/`、`payload/` 三处，没有第四样东西；
- [ ] manifest 只用那七个键、无重复键、四个必填都非空；
- [ ] `unzip -Z1 pkg.zip | grep '^payload/'` 的相对路径 = 你想要的容器内路径，且一条都不落在 §5.2 清单上；
- [ ] `zipinfo pkg.zip 'payload/usr/local/bin/*'` 第一列是 `-rwxr-xr-x`，不是 `-rw-r--r--`；
- [ ] payload 至少一个可执行位，无符号链接、无 setuid；
- [ ] `skill/SKILL.md` 的 frontmatter `name` 等于 CLI 名；
- [ ] `SKILL.md` 里提到的每个环境变量都在 `envParams` 或 `runtimeEnv` 里声明了；
- [ ] `secret: true` 的声明没有带 `defaultValue`；
- [ ] 每个 `required: true` 的声明都想清楚了值从哪来：带了 `defaultValue` 就等于替管理员答了这道必答题，向导不会再逼值（§7）；
- [ ] `checkCommand` 在无凭证下能退出 0（本机用同架构容器验一次：`docker run --rm -v $PWD/bin:/usr/local/bin/mycli:ro <base> -c 'mycli --version'`）；
- [ ] 货架上同名只留一份：`version` 最高的那份留下，落选的由 `cli-packages/build.sh` 移进 `cli-packages/dist/.superseded/`；同名同版本两份则构建直接失败（§10）。

## 12. 完整实例：lark-cli（第三方 CLI）

飞书 / Lark 开放平台官方命令行，一个包从零到镜像内可执行的完整过程，值得照抄的地方在于**它证明了这个规范能吃下 45 MB 的第三方二进制**。

### 12.1 上游事实决定了包的形状

| 上游事实 | 包侧决定 |
|---|---|
| npm 包 `@larksuite/cli` 只是一个下载器（`postinstall` 拉二进制），真实产物是单个约 45 MB 静态二进制 | 不进 `payload/` 的是 npm，进的是二进制；容器里没有 node 依赖 |
| 二进制按平台发在 GitHub Releases，另有官方镜像源 | 打包期下载，仓库不入库二进制 |
| 上游发布了 linux 各架构的 sha256 清单 | `checksums.txt` 钉住摘要；上游换版即构建失败，而不是悄悄换了字节 |
| 无头环境读 `LARKSUITE_CLI_APP_ID` / `LARKSUITE_CLI_APP_SECRET` 即可，不需要配置文件与登录 | 凭证走 `envParams`，`checkCommand` 用 `--version` |
| 命令面覆盖十几个域，且自带 `lark-cli skills list/read` | 说明书走索引式写法（§6.3） |

### 12.2 `cli-packages/lark-cli/plugin.yaml`

```yaml
name: lark-cli
version: 1.0.96
description: 飞书 / Lark 开放平台命令行，读写消息、日历、多维表格、文档、云盘、审批、任务、邮箱、通讯录等平台资源
checkCommand: lark-cli --version
envParams:
  - envParamName: LARKSUITE_CLI_APP_ID
    description: 飞书开放平台应用的 App ID（形如 cli_xxx），在「凭证与基础信息」页取
    required: true
    secret: false
  - envParamName: LARKSUITE_CLI_APP_SECRET
    description: 对应的 App Secret；应用需已开通所要调用的接口权限
    required: true
    secret: true
```

没有 `deps.apt`（纯静态二进制，构建零网络），没有 `runtimeEnv`（平台不发布飞书槽位，包也不该带密钥）。

两个声明都是 `required: true` 且没有 `defaultValue`，所以在智能体向导上勾了 lark-cli 就必须把这两行各填一个值（或各引用一个全局环境变量）才能保存（§7）。`LARKSUITE_CLI_APP_SECRET` 按 §7 的规矩走引用，别手填。

### 12.3 说明书

`skill/SKILL.md` 共 5743 字节，结构就是 §6.2 的四要素加一次索引式收敛：前置（明确写「不要执行 `login` / `config init --new` / `update`」，并解释 `doctor` 的 `config_file` 一项在沙箱里必然报错、以 `whoami` 为准）→ 选命令的顺序（`+shortcut` > 类型化方法 > 原始 `api`）→ 风险与确认（`read|write|high-risk-write`，`--yes` 只在用户确认后给）→ 输出控制（`--jq`、`--format`、`--page-all`）→ 身份切换（`--as`）→ 域一览 → 深入用法指向 `lark-cli skills read lark-<domain>`。

### 12.4 实测结果（本机 arm64 主机，基座 `harnax-sandbox:py-node`）

| 环节 | 结果 |
|---|---|
| 打包 | `dist/lark-cli-1.0.96.harnaxcli.zip`，14132636 字节，linux/amd64 |
| 登记 | `Registered CLI package lark-cli 1.0.96 (payload e4223d4a14b1, uploaded)`；本轮 `Sync complete: 3 registered, 0 failed` |
| `cli` 行 | id=39、`version=1.0.96`、`status=1`、`skill_id=40`、`package_digest=3f337b54c511c2d6321a1e793b24f141378add0caa09afe7d9fcdcc183fe5011`、`payload_digest=e4223d4a14b1…52be` |
| 摘要一致性 | 库里 `package_digest` == 本地 zip 的 sha256 |
| MinIO 对象 | `harnax-cli-packages/lark-cli/3f337b54c511…5011.harnaxcli.zip` |
| 技能行 | id=40、`status=1`、`repository_id=1`（托管仓库 `builtin-cli-skills`）、正文 5743 字节 |
| 镜像 | `harnax-sandbox:cli-2ba7f7813168`（harnax + lark-cli 两个 CLI 的组合 tag） |
| 镜像内文件 | `-rwxr-xr-x 1 root root 48124066 /usr/local/bin/lark-cli` |
| `checkCommand` | `lark-cli version 1.0.96`，退出码 0 |
| 凭证就绪自检 | 只注入两个 env 的情况下 `lark-cli whoami` → `identity: bot`、`tokenStatus: ready` |

镜像 tag 那一步还反向验证了公式：先用设计文档 §4 的算法手算出 tag，再与运行时构建出的 tag 逐字符比对一致。

**未验的一公里**：把这条 CLI 绑到某个智能体上、真发一次飞书 API 调用。它需要人工填 App ID / App Secret，本仓库的自动化不代填。

## 13. 拒绝原因原文 → 改法

日志格式固定为 `<文件名>: refused with N problem(s):` 后跟每行一条原因。下表按代码原文列出最常见的十四种。

| 日志里的原因（原文节选） | 为什么 | 怎么改 |
|---|---|---|
| `file name must end with .harnaxcli.zip` | 后缀不对 | 改名 |
| `file name "…" does not start with the declared name plus a version` | 文件名前缀与 `name` 不符 | 按 `<name>-<version>.harnaxcli.zip` 改名 |
| `unknown plugin.yaml key(s) [install]` | 有未识别的键 | 删掉；平台不接受安装脚本 |
| `plugin.yaml is not valid YAML: <problem>` | YAML 语法/重复键/嵌套过深 | 按 `<problem>` 那行修 |
| `name "MyCLI" must match ^[a-z][a-z0-9-]{1,63}$` | 名字含大写或下划线 | 全小写、只用 `-` |
| `version "v1.0" must match …` | 版本首字符不能是 `-`/`.` | 去掉前导 `v` 之类 |
| `plugin.yaml: checkCommand is required` | 缺必填 | 补上，见 §8 |
| `plugin.yaml deps.apt entry "…" is not a plain apt package name` | 条目里有空格、`$`、分号或开头 `-` | 只留包名和可选的 `=版本` |
| `plugin.yaml envParams "TOKEN" is marked secret and carries a defaultValue` | 包不能带密钥 | 去掉 `defaultValue`，值由管理员填 |
| `plugin.yaml runtimeEnv KEY asks for ${platform.foo}, which the platform does not publish` | 槽位不存在 | 换成 `platform.adminUrl` / `platform.internalToken`，或改走 `envParams` |
| `payload path lands on a denied target: etc/cron.d/x` | 落在拒绝清单 | 装到 `usr/local/…` 下 |
| `payload/… is a symbolic link` / `carries setuid/setgid/sticky bits` | 条目形状不合法 | 换成真实文件、清掉特殊位 |
| `… carry no unix mode, so they would land 0644 and fail as "permission denied"` | 打包器没记 mode | 在会存权限的文件系统上 `chmod 755` 后 `zip -X` 重打 |
| `unexpected entries outside plugin.yaml, skill/ and payload/: …` | 包里有第四处内容 | 从打包清单里去掉（Finder 压缩会带 `__MACOSX/`） |

另有两条来自技能侧：

| 原因 | 改法 |
|---|---|
| `no skill/SKILL.md — a CLI ships its own skill…` | 补一份，见 §6 |
| `skill/SKILL.md declares name "x" while plugin.yaml declares "y"` | 二者取一对齐（技能随 CLI 名） |

## 14. 边界与已知的坑

写包之前就该知道，平台目前不解决这些：

1. **平台即软件分发渠道**：能往包目录放文件的人，能决定所有 agent 沙箱里以 root 落什么文件。§5.2 的清单是纵深防御，不是沙箱。
2. **不并存版本**：升级期间所有 agent 拿最新版，没有按 agent 钉版本或灰度。
3. **升级不跟进已在保活的会话**：换二进制只对新会话生效，老会话会一直跑旧镜像的旧容器，直到被空闲回收或手工 `docker rm -f agentscope-sandbox-<sessionId>`。CLI 页「版本」一栏显示的是最新登记值，与在用容器可以不一致。
4. **包对象、payload 缓存与 CLI 镜像只增不减**：MinIO 每个 `packageDigest` 一个对象、agent-service 每 `packageDigest` 一个缓存目录、docker 每 `payloadDigest` 一个 tag，都没有回收路径。叠加 §9 说的 mtime 敏感性，每次重打包都会多一个对象和一个缓存目录。
5. **`skill/assets/` 到不了容器**：包能带，运行侧不承诺投影。
6. **`deps.apt` 需要镜像源可达**：声明式不等于离线。推荐写法是纯 payload 静态二进制。
7. **登记结果对页面不可见**：真相只在 admin 日志里（§10）。
8. **多副本 agent-service 共用一个 docker daemon 时构建无互斥**：tag 按内容寻址所以结果一致，但两个副本同时构建同一 tag 会有一边看到失败。

## 15. 相关文档

| 文档 | 内容 |
|---|---|
| [cli-plugin-package-design.zh-CN.md](./cli-plugin-package-design.zh-CN.md) | 平台侧全链路、不变量与理由、决策 D1~D15、运维手册、关键文件索引 |
| [docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md](../docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md) | 实现规格：改动清单、V35~V38 迁移、测试计划 |
| [skill-management.zh-CN.md](./skill-management.zh-CN.md) | 技能体系（CLI 自带技能落在托管仓库，随包生命周期级联） |
| [tool-integration-design.zh-CN.md](./tool-integration-design.zh-CN.md) | 工具系统（与 CLI 同形：启动登记、页面只读） |
| [docs/deploy-harnax-admin.md](../docs/deploy-harnax-admin.md) | admin 部署与 CLI 包目录的挂载运维 |
| `harnax-cli/`、`cli-packages/lark-cli/` | 两个可直接抄的打包实例：自研 CLI 与第三方 CLI |

---
name: lark-cli
description: 用 lark-cli 命令行读写飞书 / Lark 开放平台资源：消息与群聊、日历日程、多维表格、文档、云盘文件、审批、任务、邮箱、通讯录、知识库、OKR、视频会议、妙搭应用等。当用户要操作飞书里的对象或在飞书里收发消息时使用。
---

# 飞书 / Lark 命令行（lark-cli）

`lark-cli` 是飞书开放平台的官方命令行，一个命令对应平台的一类对象。它被设计成由 agent 直接驱动，命令、参数、风险级别都能自查，不需要翻文档站点。

## 前置：不需要登录

平台在创建沙箱时注入两个环境变量，凭证即已就绪：

```bash
LARKSUITE_CLI_APP_ID=<应用 App ID>
LARKSUITE_CLI_APP_SECRET=<应用 App Secret>
```

**不要执行 `lark-cli login`、`config init --new` 或 `update`。** 前两个会阻塞等待浏览器授权，第三个会替换掉平台分发的二进制。要确认当前身份与可用性，用：

```bash
lark-cli whoami        # JSON：appId / brand / identity / tokenStatus
```

`whoami` 认的是环境变量，`lark-cli doctor` 里的 `config_file` 一项只认磁盘上的配置文件，在沙箱里必然报 `not configured`——以 `whoami` 为准。

报 `not_configured` 且 `whoami` 也读不到 appId，说明这个智能体没配 CLI 环境变量，只能停下来请管理员在「智能体 → CLI 绑定」里填；这不是能在沙箱内自行绕开的事。

## 选命令的顺序

同一个动作往往有三种写法，**按下面的优先级选**：

```bash
lark-cli calendar +agenda                                   # 1. +shortcut：一个完整任务，优先用
lark-cli im chat.messages list --chat-id oc_xxx             # 2. 类型化资源方法：单个 API 方法
lark-cli api GET /open-apis/calendar/v4/calendars           # 3. 原始 HTTP：仅当上面两种都没有
```

`api` 是逃生通道，不做参数校验、不提示风险、不带用法指引，只有确认没有类型化命令时才用。

调用某个方法前先看它的形状，别猜：

```bash
lark-cli im +messages-send --help                   # 参数、类型、风险级别都在这里
lark-cli schema im.chat.messages.list                # 一份方法的全部参数、类型、所需权限与示例
```

## 风险与确认

每个命令的 `--help` 标注 `read` / `write` / `high-risk-write`。**`high-risk-write` 必须带 `--yes`，且只能在用户明确同意之后加**——删除、撤回、覆盖、发消息给第三方都属于这一类。

不确定一条写命令会做什么时，先 `--dry-run`：它打印将要发出的请求而不执行。

## 输出控制

平台返回的 JSON 往往很大，先收窄再读：

```bash
lark-cli im +chat-list --jq '.data.items[] | {name, chat_id}'   # 过滤字段
lark-cli contact +user-search --query 张三 --format table        # json|ndjson|table|csv
lark-cli base +record-list --table-id tbl_xxx --page-all         # 自动翻页（默认上限 10 页）
```

`--page-limit 0` 是无上限，只在确实需要全量时用。下载类命令把文件写到当前工作目录的安全相对路径（沙箱里即 `/workspace`）。

## 身份：应用身份还是用户身份

`whoami` 的 `identity` 表示当前生效身份。多数命令两种身份都能用，少数只接受其一（`--help` 会写 `user-only` / `bot only`）。

- 应用身份（bot）：只能访问应用被授权的资源，读不到某个人的私人日历、邮箱、云盘。
- 用户身份（user）：以授权过的用户身份操作，需要该用户此前完成过 OAuth 授权；沙箱里没有浏览器，通常不可用。

需要用户身份而当前只有 bot 时，不要硬试，直接说明缺的是哪一侧的授权。显式指定用 `--as bot|user`。

## 深入用法看 CLI 自带的域指南

每个域都有一份讲清概念、命令取舍与惯例的指南，随二进制一起发布（与版本严格同步）。先读指南再动手，比试错快：

```bash
lark-cli skills list                     # 全部域指南
lark-cli skills read lark-base           # 读一份：多维表格
lark-cli im --help                       # 尾部也会提示该域的指南名字
```

## 域一览

`lark-cli <domain> --help` 展开该域的命令。

| 域 | 用来 |
|---|---|
| `im` | 消息收发、群聊与成员、话题、书签 |
| `calendar` | 日程、参会人、忙闲、会议室 |
| `base` | 多维表格：表、字段、记录、视图、仪表盘、表单、工作流 |
| `docs` / `markdown` | 文档内容读写；云盘原生 Markdown 文件 |
| `drive` | 文件、目录、评论、权限、上传 |
| `sheets` / `slides` | 电子表格、演示文稿 |
| `wiki` / `mindnotes` | 知识库空间与节点、脑图 |
| `contact` | 通讯录：姓名/邮箱 ↔ open_id |
| `task` / `approval` | 任务与子任务；审批待办与实例 |
| `mail` | 邮箱、草稿、文件夹 |
| `minutes` / `note` / `vc` | 妙记内容、会议笔记、视频会议 |
| `attendance` / `okr` | 打卡记录；OKR |
| `event` | 消费实时事件 |
| `apps` | 妙搭应用开发与托管 |
| `whiteboard` | 画板 |
| `application` | 应用自身设置（如斜杠命令） |

## 边界

- 能调什么由应用的接口权限决定，命令行绕不过去。报权限类错误时，缺的是应用侧的权限开通，把需要的 scope 名字告诉用户即可（`lark-cli auth scopes` 可查应用已开通的权限）。
- 一次会话内多个命令之间没有事务，写命令失败要重读确认再决定是否重试，别盲目重发；发消息这类命令支持幂等键参数。
- 二进制由平台分发与升级，`lark-cli update` 在沙箱里没有意义。

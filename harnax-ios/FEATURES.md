# Harnax iOS App 功能列表

配套方案见同目录 `DESIGN.md`。本文件是范围与验收的唯一清单：每一项都能在 Web 侧 `harnax-webui` 找到出处，iOS 侧逐条对应实现或明确不做。

标记含义：**v1**＝第一版交付；**v1.1**＝依赖后端改造或明确后置；**否**＝不实现；复杂度 S（一个表单/接口）→ M（多接口 + 状态）→ L（跨实体流程或复杂交互）→ XL（流式与多路合并）。

## 1. 登录态与设置

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 用户名 + 口令登录 | `/login` | v1 | `POST /api/admin/auth/cli-login` | S |
| 服务器地址配置（Admin / Router 两个基址） | 无（Web 靠同源反代） | v1 | 本地存储 | S |
| 图形验证码登录 | `/login` | v1.1 | `POST /api/admin/auth/login` + `GET /auth/captcha` | M |
| 令牌临期自动续期 | 无（Web 未启用该接口） | v1 | `POST /api/admin/auth/refresh-token` | M |
| 冷启动恢复登录态与租户 | `getInitialState` | v1 | `GET /api/admin/auth/me` | S |
| 生物识别解锁已存凭据 | 无 | v1 | Keychain + LocalAuthentication | M |
| 退出登录 | 头像菜单 | v1 | `POST /api/admin/auth/logout` | S |
| 租户切换（仅「我的」页，多租户账号才可见） | 组件已硬关闭 | v1（O1 定案） | `POST /api/admin/auth/switch-tenant` | M |
| 语言切换（中 / 英） | `SelectLang` | v1 | `Accept-Language` | S |
| 主题切换（浅 / 深 / 跟随系统） | `ThemeSwitcher` | v1 | 本地 | S |
| 修改密码、编辑个人资料 | 无此能力 | 否 | — | — |
| 404 页 | `*` | 否 | — | — |
| 欢迎页（数据全硬编码的演示页） | `/welcome` | 否 | — | — |

## 2. 智能体

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 智能体列表（卡片 + 搜索 + 状态筛选） | `/agent/manager` | v1 | `GET /api/admin/agents/page` | M |
| 新建 / 编辑：5 步向导（基本信息 → 工具 → MCP → 技能 → CLI） | 同上 | v1 | `POST /api/admin/agents`、`PUT /api/admin/agents/update/{id}` | XL |
| 四类关联项的选择器与顺序编排 | 向导各步 | v1 | 候选来自 `tools` / `mcp` / `skills` / `clis` | L |
| 环境参数绑定表（工具 / MCP / CLI 三类） | 向导各步 | v1 | 随智能体保存提交 | L |
| 启用 / 停用 | 同上 | v1 | `PUT /api/admin/agents/toggle/{id}`（`status` 走查询串，无请求体） | S |
| 删除（无前置查询，后端拒绝时原样展示引用清单：所属团队、会话、渠道） | 同上 | v1 | `DELETE /api/admin/agents/{id}` | S |
| 保存后「刷新受影响会话」多选流程 | 同上 | v1 | `GET /agents/{id}/related-sessions`、`POST /agents/refresh-sessions` | L |
| 团队列表 | `/agent/team` | v1 | `GET /api/admin/teams/page` | M |
| 新建 / 编辑团队：主管 + 成员编排与排序 | 同上 | v1 | `POST /api/admin/teams`、`PUT /api/admin/teams/update/{id}` | L |
| 团队启停 | 同上 | v1 | `PUT /api/admin/teams/toggle/{id}` | S |
| 团队删除（先读关联会话定文案，真拦截在后端 message） | 同上 | v1 | `GET /api/admin/teams/{id}/related-sessions` + `DELETE /api/admin/teams/{id}` | M |

## 3. 会话与对话

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 会话列表（左列表，翻页追加；无搜索／分组／排序控件，Web 侧亦无） | `/agent/session` | v1 | `GET /api/admin/sessions/page` | M |
| 新建会话（选智能体或团队、名称查重；三个字段是 `SessionCreateRequest` 的全部，Web 那张 `isPublic` 开关不进 `createData` 也不在 DTO 上，故不搬） | 同上 | v1 | `check-title`、`POST /sessions` | M |
| 会话详情弹窗（七个只读面板 + 配置查看与修改；工具 / CLI / 团队成员只能由执行者自己的行填，故打开时按 `agentId` 或 `teamId` 读单行） | 同上 | v1 | `GET/PUT /sessions/{id}/config`、`GET /api/admin/agents/{id}`、`GET /api/admin/teams/{id}` | M |
| 删除会话（含沙箱释放失败的阻断语义） | 同上 | v1 | `DELETE /sessions/{id}` | M |
| Workspace 抽屉（文件列表 / 上传 / 下载） | 同上 | v1 | router `workspace/*` | L |
| 团队产物抽屉与下载（`TeamArtifactController` 整控制器同样受 `minio.enabled=true` 开关控制，裸配置默认关、标准部署 compose 显式开启，与附件下载一路的 O8 门槛同族） | 同上 | v1 | `GET /api/admin/team-artifacts` | M |
| 历史消息回放（含分段重建） | 同上 | v1 | `GET /api/router/agent/chat/history/{id}` | L |
| 发消息与流式接收 | `ChatWindow` | v1 | `POST /api/router/agent/chat/stream` | XL |
| 五类可见分段渲染（文本 / 思考 / 工具调用合并卡 / 确认卡 / 计划卡；`tool_result` 段在 Web 恒不渲染） | 同上 | v1 | 同一事件流 | XL |
| 思考过程折叠展开 | 同上 | v1 | `ThinkingEvent` | M |
| 工具确认：单个批准 / 全部拒绝 / 总是允许 | 同上 | v1 | `POST /agent/confirm` + `CONFIRM` 请求体 | L |
| 高危工具的显著标识 | 同上 | v1 | `pendingCallTools[].isDangerous` | S |
| 团队成员多路合并与气泡归属 | 同上 | v1 | `event.source.childRunId` | XL |
| 计划面板（当前计划 + 历史计划） | 同上 | v1 | `current-plan`、`session/{id}/plans` | L |
| slash 命令（10 个命令及其参数解析） | 同上 | v1 | `POST /agent/command` | L |
| 权限模式切换（5 档） | 同上 | v1 | `COMMAND` + `PERMISSION` | M |
| 图片输入（拍照 / 相册） | 同上 | v1 | `imageUrls` | M |
| 停止生成 / 清空会话 / 停止沙箱 | 同上 | v1 | `COMMAND`（`INTERRUPT`/`CLEAR`/`STOP_SANDBOX`） | M |
| 上下文占用读数（标题栏一枚只读芯片，点开是名／数两列的五个读数；两种报不出来的形状整块不显示，绝不显示 `0%`） | 会话页标题栏（`pages/session/index.tsx` 的 `ContextUsageTag`） | v1 | router `GET /api/router/agent/context/{sessionId}` | M |
| 压缩上下文入口（输入区，无二次确认；应答四支、成败只认 `success` 旗标；画面仍全量原文气泡） | `ChatWindow.tsx` 输入区 | v1 | `POST /agent/command` + `COMPACT` | S |
| 附件收取与下载（iOS 超出 Web 的一项：后端已下发，webui 全仓零处引用 `attachments`） | 无 | v1 | `EndEvent.attachments` + `GET /api/output-files/{sessionType}/{sessionId}/{fileId}`，该路由整控制器受 `minio.enabled=true` 开关控制：裸配置默认关（`${MINIO_ENABLED:false}`）、标准部署 compose 显式开启，未开启时整条路由不注册即 404（O8） | M |
| 模型能力门控（推理 / 思考模式 / 联网 / 视觉） | 同上 | v1 | 会话配置字段 | M |
| 联网搜索开关 | 同上 | v1 | `ENABLE`/`DISABLE` | S |
| 断线自动重连 | 无（Web 也未做） | 否 | — | — |

## 4. 定时任务

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 任务列表（含下次执行时间、轮询刷新） | `/agent/task` | v1 | `GET /api/admin/agent-tasks/page` | M |
| 新建 / 编辑（cron 预设与表达式、目标智能体、提示词） | 同上 | v1 | `POST/PUT /agent-tasks` | L |
| 启停 / 删除 | 同上 | v1 | `toggle`、`DELETE` | S |
| 立即执行 | 同上 | v1 | `POST /agent-tasks/{id}/trigger` | S |
| 执行日志列表（筛选 + 分页） | 日志弹窗 | v1 | `GET /agent-tasks/{id}/logs` | M |
| 单条日志详情 | 同上 | v1 | 日志详情接口 | M |
| 停止运行中的任务执行 | 同上 | v1 | `POST /agent-tasks/logs/{logId}/stop` | S |
| 任务失败推送提醒 | 无 | v1.1 | 需后端新增推送登记 | M |

## 5. 上下文：模型

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 供应商卡片列表（增改、启停、删除） | `/context/model` | v1 | `GET/POST/PUT /api/admin/model-providers` | L |
| 供应商连通性测试 | 同上 | v1 | `POST /model-providers/{id}/test` | M |
| 该供应商下的模型表（增改、启停、删除；搜索 + 状态 + 模型类型 + 能力标签 + 价格区间五个筛选） | 同上 | v1 | `models/*` | L |
| 思考模式取值（关闭 / 自动 / 强制） | 同上 | v1 | 模型字段 | S |
| 模型用量统计入口 | 同上 | v1 | `GET /model-providers/{id}/stats` | S |

## 6. 上下文：工具

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 内置工具只读表（描述、需确认标记） | `/context/tool` | v1 | `GET /api/admin/tools/builtin` | S |
| 工具环境参数查看 | 同上 | v1 | 同一响应 | S |
| 工具搜索 | 同上 | v1 | 本地过滤 | S |
| 工具新增 / 编辑 | Web 亦无 | 否 | — | — |

## 7. 上下文：MCP

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| MCP 卡片列表（增改、启停、删除） | `/context/mcp` | v1 | `mcp/page`、`POST`、`toggle`、`DELETE` | L |
| 配置条目编辑（含传输类型） | 同上 | v1 | 随保存提交 | L |
| 环境参数编辑 | 同上 | v1 | 同上 | M |
| 连通性测试 | 同上 | v1 | `connectivity-test` | M |
| 删除前关联智能体检查与阻断 | 同上 | v1 | `related-agents` | M |
| 详情页：基本信息 + 工具列表（参数可展开） | `/context/mcp/detail/:id` | v1 | `mcp/{id}`、`list_tools` | L |
| OAuth：发现、客户端注册、发起授权 | 详情 OAuth 面板 | v1 | `oauth/discover`、`oauth/client`、`authorize-url` | L |
| OAuth 授权回跳 | `/mcp/oauth/callback`（Web 页面） | v1（应用内 `WKWebView` 把每次导航逐段比对注册表里那条 http(s) `redirect_uri`，命中即取消导航并由本端发 `oauth/exchange`，徽标最终以 `oauth/status` 为准；另留 Safari 交接加轮询一条并列入口。回跳本身不能是本机自定义 scheme，后端存不住那样的注册） | `oauth/exchange`、`oauth/status`、`oauth/revoke` | L |

## 8. 上下文：技能

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 技能仓库列表（增改、启停、删除） | `/context/skill` 左栏 | v1 | `skill-sources/*` | L |
| 从 git / npm 源拉取与安装 | 同上 | v1 | `fetch`、`install` | L |
| ZIP 包上传 | 同上 | v1 | `upload`（multipart） | M |
| 仓库同步（拉取清单 + 安装，含结果与失败明细弹窗） | 同步弹窗 | v1 | `GET /skill-sources/{id}/fetch` + `POST /skill-sources/{id}/install`（后端无单独 sync 口，Web 的「同步」即这两步的组合） | M |
| 技能列表（启停、按仓库筛选） | 右栏 | v1 | `skills/page`、`toggle` | M |
| 技能停用拦截（列表行自带的 `boundAgentCount`/`boundTeamCount` 任一 > 0 即置灰；后端还多查一路 CLI 包内置技能，只在 message 里出现。技能本身没有删除入口，删除只在仓库列表） | 同上 | v1 | 无额外接口 | M |
| 技能详情：Markdown 正文 | `/context/skill/detail/:id` | v1 | `GET /skills/{id}` | M |
| 技能详情：资源文件树与文件内容 | 同上 | v1 | 同一详情响应 | L |
| 技能行的来源标记（智能体晋升 / 人工，两枚徽标；整行没有这一列时两枚都不画） | 技能表新增列 | v1 | 列表行与详情行的 `origin` | S |
| 技能草稿队列（`PENDING`/`APPROVED`/`REJECTED` 三档分段、按名搜索、每页 20 条） | `/context/skill-drafts` | v1 | `GET /api/admin/skill-drafts`（无 `/page` 后缀） | M |
| 会话域入口行（租户级待审计数；装配缺失即撤整行，计数读失败只隐藏数字） | 无（Web 走左侧菜单） | v1 | 同一队列接口 | S |
| 草稿详情六页签（正文 / 文件 / 脚本 / 内容扫描 / 来源 / 轨迹） | `/context/skill-draft/detail/:id` | v1 | `GET /skill-drafts/{id}` | L |
| 两份扫描分色并置（harnax 内容扫描非空即存成禁用；沙箱上报只展示、永不按住） | 详情扫描页签 | v1 | 同一详情响应 | M |
| 批准晋升（先取摘要再确认，六种结果各有一句，未知结果走兜底） | 审核详情操作区 | v1 | `POST /skill-drafts/{id}/approve` | L |
| 重名冲突处置（改名或覆盖，预选改名、改名限 100 字，取消即重读） | 冲突弹窗 | v1 | 同一批准接口的 `conflictResolution` 与 `newName` | M |
| 驳回并留原话（理由必填、trim 后限 512 字） | 驳回弹窗 | v1 | `POST /skill-drafts/{id}/reject` | M |
| 智能体的技能自我进化开关（基本信息里可见性开关之后那一项，不动即不发送这一位） | `/agent/manager` 表单基本区 | v1 | 智能体创建与更新体的 `skillSelfWrite` | S |
| 按会话过滤的待审计数 | 无 | v1.1 | 队列接口需后端补 `sessionId` 过滤参数 | M |

## 9. 上下文：CLI

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| CLI 列表（启停、拦截原因） | `/context/cli` | v1 | `clis/page`、`toggle` | M |
| 详情：镜像指纹、依赖、运行时环境 | 详情抽屉 | v1 | `GET /clis/{id}` | M |
| 关联智能体 / 关联会话查询与刷新会话流程 | 同上 | v1 | `related-agents`、`related-sessions` | M |
| 货架投递与包注册（写侧） | 无 | 否 | 按既有裁定只留读与开关 | — |

## 10. 系统：渠道

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 渠道列表（类型、接入模式、状态、启停、删除） | `/system/channel` | v1 | `channels/*` | L |
| 新建 / 编辑：按渠道类型与接入模式切换条件字段（`http` 类型运行时无适配器，灰显不可提交，O7） | 同上 | v1 | 随保存提交 | XL |
| 渠道级思考 / 联网 / 计划三开关 | 无（Web 表单从未暴露） | v1 | `POST /api/admin/channels`、`PUT /api/admin/channels/update/{id}` 已带三列（O6 已落地），Web 仍无入口，iOS 表单是可编辑的那一端 | M |
| 回调地址展示（`callbackKey` 后端从不进响应，只有派生的 `callbackUrl`；Web 侧同样只展示地址） | 同上 | v1 | 同一响应 | M |
| 沙箱状态展示 | 同上 | v1 | 同一响应 | S |
| 微信扫码绑定（二维码 + 状态轮询 + 取消） | 扫码弹窗 | v1 | `wechat/login`、`wechat/status`、`wechat/cancel` | L |

## 11. 系统：API Key 与环境变量

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| API Key 列表（增改、启停、删除；Web 侧入口仅管理员可见，后端未校验，O5） | `/system/api-key` | v1 | `api-keys/*` | L |
| 重新生成 + 一次性原始 Key 展示与复制 | 同上 | v1 | `regenerate` | M |
| 作用域、限流、有效期、归属租户编辑 | 同上 | v1 | 随保存提交 | M |
| 环境变量列表（增改、启停、删除） | `/system/env-variable` | v1 | `env-variables/*` | M |
| 键名格式校验与敏感值掩码回读 | 同上 | v1 | 同一响应 | M |

## 12. 系统：Token 监控

| 能力 | Web 出处 | iOS | 依赖接口 | 复杂度 |
|---|---|---|---|---|
| 日期区间 + 时间粒度 + 统计维度三个筛选 | `/system/token-monitor` | v1 | `token-stats/aggregation` | M |
| 统计卡片区 | 同上 | v1 | 同一响应 | M |
| 分布图（模型 / 智能体 / 会话三个维度） | 同上 | v1 | `time-series/{model\|agent\|session}` | L |
| 趋势折线图 | 同上 | v1 | `time-series` | L |
| 图表交互（图例开关、点选下钻） | Web 亦有限 | v1 | 本地 | M |

## 13. 不做清单（汇总）

| 项 | 原因 |
|---|---|
| 用户管理页 | 用户裁定排除（注意：该页对管理员可见可写，并非已下线） |
| 租户管理页 | 用户裁定排除 |
| 修改密码、个人资料编辑 | 用户裁定排除；Web 侧同样不存在该能力 |
| 欢迎页、404 页、旧地址重定向 | 无实际功能 |
| 工具的新增与编辑 | Web 侧为只读内置表 |
| CLI 包注册与投递（写侧） | 按既有裁定只保留读与开关 |
| 离线缓存与写操作排队 | 成本在冲突合并，收益不明确 |
| APNs 推送 | 需后端新增接口，列 v1.1 |
| 对话断线自动重连 | 服务端未定义重放语义 |
| Android 端 | 技术栈选了原生且首版只做 iOS |

## 14. 计数与口径

本清单分 12 个功能域，逐条枚举 **111 项**能力：v1 **102 项**（其中 2 项在档位后附了口径说明）、v1.1 **3 项**、不做 **6 项**。O1 定案后租户切换计入 v1。复杂度分布 S 24 / M 49 / L 27 / XL 5，合计 105——不做的 6 项不计复杂度。

5 个 XL 分别是：智能体 5 步向导、发消息与流式接收、五类可见分段渲染、团队成员多路合并与气泡归属、渠道的条件字段表单。这五项加在 L 档的 27 项之前构成工期主体——L 档集中在跨实体流程（关联拦截、刷新会话、OAuth 回跳、Workspace、扫码绑定）与多接口表单矩阵。

第 13 章的「不做清单」另有 10 条，与上表标记为「否」的 6 项口径不同：那 6 项是本应在某页里、但确认不实现的功能；这 10 条是整体层面的取舍，包含推送、离线、断线重连、Android 这类未进入任何页面拆分的项。两处不要相加。

改动上表任一档位或复杂度后需重跑本段核对计数（注意先还原转义竖线，否则会少算一行）：

```python
import re, collections
rows = []
for line in open('FEATURES.md', encoding='utf-8'):
    s = line.strip()
    if not s.startswith('|'):
        continue
    cells = [c.strip() for c in re.sub(r'\\\|', '\x00', s.strip('|')).split('|')]
    cells = [c.replace('\x00', '|') for c in cells]
    if len(cells) != 5 or set(''.join(cells)) <= set('-: ') or cells[2] == 'iOS':
        continue
    rows.append(cells)
tier = collections.Counter('v1.1' if c[2].startswith('v1.1') else 'v1' if c[2].startswith('v1') else '否' for c in rows)
print(len(rows), dict(tier), dict(collections.Counter(c[4] for c in rows)))
```

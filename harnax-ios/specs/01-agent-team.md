# iOS 实现规格 · 智能体域（智能体管理 + 多智能体团队）

权威口径：`harnax-webui/src/pages/agent/`、`harnax-webui/src/pages/team/` 及其 components，顺着 `harnax-webui/src/services/` 追到 `harnax-admin` 的 Controller 与 DTO。凡两端源码能证实的结论才写进正文，其余进「未确认」。唯一例外：webui 没有智能体详情页，仅在描述「详情形态」缺口时引用 `harnax-wechat-app` 的同名实现作参考，并逐处标注。

路由锚点：`/agent/manager` → `./agent`，`/agent/team` → `./team`（`harnax-webui/config/routes.ts:39`、`harnax-webui/config/routes.ts:45`）。

---

## 页面清单

| 页面 | webui 路径 | 列表形态 | 新建/编辑入口 | 详情形态 |
| --- | --- | --- | --- | --- |
| 智能体管理 | `harnax-webui/src/pages/agent/index.tsx:445` | 仅卡片网格（`ResponsiveCardGrid`＋`CardPagination`），无表格视图 | 工具栏「创建智能体」按钮与空态按钮同开一个弹窗（`harnax-webui/src/pages/agent/index.tsx:634`、`:698`） | webui 无详情页：卡片自身即全部信息，编辑直接用列表返回的那一行（`:714`、`:759`） |
| 多智能体团队 | `harnax-webui/src/pages/team/index.tsx:33` | 仅 `Table`（`:334`），无卡片视图 | 工具栏「新建团队」（`:318`） | 无独立详情页，编辑用 Table 行数据（`:285`） |
| 保存后刷新会话弹窗 | `harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:36` | 共享组件，`source` 取 `agent`/`cli`/`team`（`:22`） | 智能体保存成功（`harnax-webui/src/pages/agent/index.tsx:777`）、团队保存成功（`harnax-webui/src/pages/team/index.tsx:398`）后自动弹出 | — |

### 卡片展示字段（智能体）

外层容器 `EntityCard`：图标 `RocketOutlined`、名称、类型标签留空（`tagLabel=""`，智能体不显示类型标签，`harnax-webui/src/pages/agent/index.tsx:424`）、标签色 `#4f6ef7`（`:425`）、描述、状态、公开标记、创建人、创建时间、统计区、操作区（`harnax-webui/src/pages/agent/index.tsx:418`-`:442`，渲染细节 `harnax-webui/src/components/EntityCard/index.tsx:292`-`:304`、`:509`-`:536`）。

| 区块 | 内容 | 数据字段 | 锚点 |
| --- | --- | --- | --- |
| 头部右上 | 启停 `Switch`，`checked=status===1`，文案「启用／停用」，点击回调传反值 | `status`（缺省按 1 处理） | `harnax-webui/src/pages/agent/index.tsx:428`；`harnax-webui/src/components/EntityCard/index.tsx:294`-`:298` |
| 标签行 | 蓝色 `ApiOutlined` 标签＝模型名；青色标签＝`¥{modelPrice}/M` | `modelName`、`modelPrice` | `harnax-webui/src/pages/agent/index.tsx:74`-`:87` |
| 描述 | 两行截断，空则「暂无描述」，hover Tooltip 全文 | `description` | `harnax-webui/src/components/EntityCard/index.tsx:307`-`:327`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:149` |
| 统计区 5 格 | Tools／MCPs／Skills／CLIs／Sessions 计数，各带浮层列表 | `toolList`/`mcpList`/`skillList`/`cliList`/`sessionList`＋`sessionCount` | `harnax-webui/src/pages/agent/index.tsx:67`-`:71`、`:90`-`:416` |
| 浮层行 | Tools：`toolDisplayNameZh`→`toolDisplayName`→`toolName`（按语言择一），副行 `toolDescription`，点击新开 `/context/tool` | `toolList[]` | `harnax-webui/src/pages/agent/index.tsx:106`-`:148` |
| 浮层行 | MCPs：`mcpName`＋`mcpDescription`，新开 `/context/mcp/detail/{mcpId}` | `mcpList[]` | `harnax-webui/src/pages/agent/index.tsx:197`-`:207` |
| 浮层行 | Skills：`skillName`＋可选「仓库: {repositoryName}」＋`skillDescription`，新开 `/context/skill/detail/{skillId}` | `skillList[]` | `harnax-webui/src/pages/agent/index.tsx:257`-`:276` |
| 浮层行 | CLIs：`cliName`＋同行小字 `version`＋`cliDescription`＋「关联技能: a, b」，新开 `/context/cli` | `cliList[]` | `harnax-webui/src/pages/agent/index.tsx:324`-`:346` |
| 浮层行 | Sessions：`title`（空则「会话 #{id}」）＋`sessionDescription`，新开 `/agent/session?id={id}` | `sessionList[]` | `harnax-webui/src/pages/agent/index.tsx:396`-`:406` |
| 底部左 | 公开／私有标签、`creator`、`createTime`（`T`→空格后截到分钟） | `isPublic`/`creator`/`createTime` | `harnax-webui/src/pages/agent/index.tsx:429`-`:431`；`harnax-webui/src/components/EntityCard/index.tsx:511`-`:535` |
| 底部右 | 测试按钮关闭（`showTest:false`）；编辑、删除按权限显示 | `creator` | `harnax-webui/src/pages/agent/index.tsx:434`-`:436` |

权限：编辑／删除由 `hasOperationPermission(isAdmin, currentUser, creator)` 决定（管理员恒可，非管理员仅自己创建的，`harnax-webui/src/utils/permissionUtil.ts:111`-`:123`），`EntityCard` 内再与传入的 `showEdit`/`showDelete` 取与（`harnax-webui/src/components/EntityCard/index.tsx:554`、`:567`）。启停开关不受这条权限判断约束（`:294`）。管理员与当前用户来自 `localStorage.currentUser`（`harnax-webui/src/utils/permissionUtil.ts:80`-`:97`）。

### 排序与筛选

| 页面 | 排序 | 服务端分页参数 | 筛选项 | 锚点 |
| --- | --- | --- | --- | --- |
| 智能体 | `ORDER BY create_time DESC`，前端无排序 UI（列头不带 sorter） | `pageNum`（默认 1）、`pageSize`（默认 10，服务端 `coerceIn(1,1000)`） | 关键词→`name` LIKE；`status`→1/0；租户＋`is_public=1 OR creator=当前用户` 强制条件 | `harnax-entity/src/main/resources/mapper/AgentMapper.xml:80`-`:94`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:36`-`:55`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:80`-`:91` |
| 智能体（前端） | — | 初始 `pageNum=1`、`pageSize=8`（注释「默认第一个选项：4 * 2 = 8」）；分页选项由每行卡数派生 | 关键词 500 毫秒防抖后置页回 1；状态筛选即时；重置清空两项 | `harnax-webui/src/pages/agent/index.tsx:455`-`:456`、`:500`-`:522`、`:533`-`:566`；`harnax-webui/src/components/CardPagination/index.tsx:49`-`:59` |
| 团队 | `ORDER BY update_time DESC`，前端无排序 UI | 服务函数收 `current`/`size`，出参映射为 `pageNum`/`pageSize`，默认 1／10 | 只有名称关键词（500 毫秒防抖），无状态筛选（服务函数支持 `status` 但页面未传） | `harnax-entity/src/main/resources/mapper/TeamMapper.xml:28`-`:41`；`harnax-webui/src/services/ant-design-pro/team.ts:6`-`:25`；`harnax-webui/src/pages/team/index.tsx:52`-`:73`、`:96`-`:104` |
| 团队（前端） | — | `pageSize` 选项 `10/20/50`，`showSizeChanger`、`showQuickJumper` | 空态文案「暂无团队」 | `harnax-webui/src/pages/team/index.tsx:44`、`:340`-`:359`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:1110` |

### 团队表格列

| 列 | 宽度 | 渲染 | 锚点 |
| --- | --- | --- | --- |
| 团队名称 | 180，ellipsis | 粗体文本，Tooltip 显示 `description` | `harnax-webui/src/pages/team/index.tsx:197`-`:209` |
| 主管模型 | 150 | 蓝色 Tag `modelName`，无名字时退化成 `#{modelId}` | `:210`-`:219` |
| 成员 | 自适应 | 每个成员一个 Tag，`agentName` 或 `#{agentId}`；`agentAvailable===false` 标红并把 Tooltip 换成「该智能体已停用或已被删除」，否则显示 `delegationDescription \|\| agentDescription` | `:220`-`:248`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:1106` |
| 状态 | 100 | `StatusSwitch`，`status ?? 1` | `:249`-`:258` |
| 创建人 | 100 | `creator` | `:259`-`:264` |
| 创建时间 | 170 | `createTime` 截到秒（19 字符） | `:265`-`:271` |
| 操作 | 120 | 编辑、删除两个图标按钮，无权限门禁（不像智能体卡片那样判 `hasOperationPermission`） | `:272`-`:295` |

---

## 表单字段规格

新建与编辑是两套组件（`harnax-webui/src/pages/agent/components/CreateForm.tsx`／`harnax-webui/src/pages/agent/components/UpdateForm.tsx`），向导步序与表单项完全同构，只有初值来源、提交调用和第 5 步按钮文案不同：**基本信息 → 工具配置 → MCP 配置 → 技能配置 → CLI 配置**（`harnax-webui/src/pages/agent/components/CreateForm.tsx:226`-`:232`；`harnax-webui/src/pages/agent/components/UpdateForm.tsx:330`-`:336`）。

### 第 1 步 · 基本信息

| 字段名 | 控件类型 | 必填 | 校验规则 | 默认值 | 候选数据来源接口 | 锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| `name` | 单行输入 | 是 | 前端仅「非空」；后端 `@NotBlank`＋长度 1–100；同租户重名拒绝 | 空；编辑态回填 `values.name` | — | `harnax-webui/src/pages/agent/components/CreateForm.tsx:238`-`:240`；`harnax-webui/src/pages/agent/components/UpdateForm.tsx:342`-`:344`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:13`-`:16`；重名检查 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:114`-`:116`、`:162`-`:168` |
| `description` | 多行输入（3 行） | 是 | 前端非空；后端 `@NotNull`（允许空串，长度未限制） | 空；编辑态回填 | — | `harnax-webui/src/pages/agent/components/CreateForm.tsx:241`-`:243`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:18`-`:20` |
| `systemPrompt` | 多行输入（8 行），支持 Markdown | 是 | 前端非空；后端 `@NotNull` | 空；编辑态回填 | — | `harnax-webui/src/pages/agent/components/CreateForm.tsx:244`-`:246`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:22`-`:24` |
| `modelId` | 单选下拉（`allowClear`，无搜索） | 是 | 前端必选；后端 `@NotNull`，且必须是当前租户可见模型 | 空；编辑态回填 `values.modelId` | `GET /api/admin/models/page`，前端再过滤 `status===1 && modelType==='chat'` | `harnax-webui/src/pages/agent/components/CreateForm.tsx:247`-`:254`、`:101`-`:107`；可见性 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:101`-`:105` |
| `owner` | 单行输入，`disabled` | 否 | 不参与提交（提交体里没有该字段），后端一律写当前用户名 | 新建自动填当前用户 `username \|\| nickname` | 初始态 `useModel('@@initialState').currentUser` | `harnax-webui/src/pages/agent/components/CreateForm.tsx:255`-`:257`、`:62`-`:66`、`:157`-`:178`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:124` |
| `isPublic` | 开关（公开／私有） | 否 | 新建可任意切换；编辑时非管理员且非创建人禁用，非管理员对已公开行也禁用 | 新建 `false`；编辑 `values.isPublic===1` | — | `harnax-webui/src/pages/agent/components/CreateForm.tsx:258`-`:263`；`harnax-webui/src/pages/agent/components/UpdateForm.tsx:362`-`:365`；规则 `harnax-webui/src/utils/permissionUtil.ts:57`-`:75` |

模型选项文案模板：`{modelName} - {providerName \|\| 未知供应商} ¥{price \|\| 0}/M`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:252`）。模型能力门禁：第 2 步、第 3 步按所选模型的 `supportTool`/`supportMcp` 是否等于 1 决定，不满足时顶部出现告警条并把整个配置面板置为 0.4 透明度＋不可交互（不阻断「下一步」）（`harnax-webui/src/pages/agent/components/CreateForm.tsx:269`-`:287`、`:293`-`:311`）。

### 第 2 步 · 工具配置（可跳过）

面板为「行列表」，每行一条工具绑定；状态形状 `ToolConfigState`（`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:7`-`:13`）。

| 字段名 | 控件类型 | 必填 | 校验规则 | 默认值 | 候选数据来源接口 | 锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| `toolId` | 单选下拉，宽 200，不支持搜索 | 行内一旦有内容即必填 | 空行（无 `toolId` 且无 env 行）视为不存在；有 env 行但没选工具→「请选择工具，或删除这一空行」；同一列表内其他行已选过的工具从候选中剔除 | 新行 `{}` | `GET /api/admin/tools/available`（`status=1 AND active=1 AND is_required=0`，按 name 升序；必装工具由运行时注入故被排除） | `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:36`-`:46`、`:104`-`:110`；`harnax-webui/src/pages/agent/components/configValidation.ts:115`-`:124`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:54`-`:65`；SQL `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:60`-`:62` |
| `needConfirm` | 小开关（「需要确认」） | 否 | 无 | `false`（提交时 `c.needConfirm \|\| false`） | — | `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:111`-`:114`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:171`；后端只允许加严不允许放松 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:425`-`:427` |
| `envBindings[]` | 见「关联与绑定」的共用环境参数表 | 声明了参数才出现 | 工具的必填参数缺值即拦（默认值不算填过） | 选中工具后按 `tool.envParams` 逐行建；非敏感项回填 `defaultValue`，敏感项留空 | `GET /api/admin/env-variables/list`（下拉候选）＋工具行的 `envParams` | `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:54`-`:67`；`harnax-webui/src/pages/agent/components/configValidation.ts:120`；`harnax-webui/src/pages/agent/components/envBinding.ts:21`-`:35`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:414`-`:421` |

行操作：「添加工具」尾部追加空行；每行右侧「删除」直接删行（`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:81`-`:82`、`:115`-`:117`）。工具名本地化：中文优先 `displayNameZh`→`displayName`→`name`（`:42`-`:43`）。

### 第 3 步 · MCP 配置（可跳过）

状态形状 `McpConfigState`（`harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:7`-`:12`）。

| 字段名 | 控件类型 | 必填 | 校验规则 | 默认值 | 候选数据来源接口 | 锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| `mcpId` | 单选下拉，`allowClear`，不支持搜索，卡片带「MCP #序号」标题 | 行内一旦有内容即必填 | 空行视为不存在；有 env 行未选服务→「请选择 MCP 服务，或删除这一空行」；其他行已选过的服务从候选剔除 | 新行 `{}` | `GET /api/admin/mcp/page?pageNum=1&pageSize=100&status=1` | `harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:88`-`:114`；`harnax-webui/src/pages/agent/components/configValidation.ts:126`-`:134`；`harnax-webui/src/services/ant-design-pro/agent.ts:99`-`:116` |
| `envBindings[]` | 共用环境参数表，开启「包内默认值」灰字提示 | 声明了参数才出现 | MCP 必填参数缺值才拦，且**声明默认值算已填** | 选中服务后按 `envParams` 建行，**一律留空** | `GET /api/admin/env-variables/list` | `harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:41`-`:48`、`:116`-`:126`；`harnax-webui/src/pages/agent/components/configValidation.ts:130`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:452`-`:461` |

行操作：「添加 MCP」追加；仅当行数大于 1 时才显示删除按钮，最后一行删不掉（`harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:62`-`:69`、`:98`-`:102`）。

### 第 4 步 · 技能配置（可跳过）

状态形状 `SkillConfigState`，只有仓库与技能两个维度，无环境参数（`harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx:6`-`:13`；后端技能绑定不带 env：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:530`-`:533`）。

| 字段名 | 控件类型 | 必填 | 校验规则 | 默认值 | 候选数据来源接口 | 锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| `repositoryId` | 单选下拉（左半，`allowClear`） | 逻辑必填（不选仓库无法选技能） | 清空仓库＝整行归零 | 新行 `{}` | `GET /api/admin/skill-sources/page?pageNum=1&pageSize=100&status=1`，前端剔除内置 CLI 仓库 | `harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx:108`-`:115`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:93`-`:99`；`harnax-webui/src/services/ant-design-pro/skillSource.ts:5`-`:22`、`:101`-`:113` |
| `skillId` | 单选下拉（右半，`labelInValue`＋富选项，未选仓库时禁用，不支持搜索） | 有仓库即必填 | 未选→「请选择技能，或删除这一空行」；同一次提交内重复→「技能 {name} 已经添加过了」；候选剔除其他行已选技能 | 空 | `GET /api/admin/skills/page?repositoryId={id}&pageNum=1&pageSize=100&status=1` | `harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx:116`-`:153`、`:63`-`:84`；`harnax-webui/src/pages/agent/components/configValidation.ts:137`-`:148`；`harnax-webui/src/services/ant-design-pro/agent.ts:121`-`:140` |

富选项三行：技能名、`仓库: {repositoryName}`、单行截断描述（`harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx:132`-`:151`）。内置 CLI 仓库的技能在页面候选里被剔除，后端同一条规则二次拒绝（`harnax-webui/src/pages/agent/components/CreateForm.tsx:96`-`:97`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillBindingResolver.kt:55`-`:60`）。同提交内「同名不同 id」的技能也会被后端拒绝（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillBindingResolver.kt:63`-`:68`）。

### 第 5 步 · CLI 配置（可跳过）

CLI 是唯一「卡片＝已选项」的形态：状态是 `selectedCliIds: number[]` 加一张按 `cliId` 归拢的绑定字典（`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:11`、`:20`-`:27`）。

| 字段名 | 控件类型 | 必填 | 校验规则 | 默认值 | 候选数据来源接口 | 锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| `cliList[].id` | 每卡一个单选下拉（宽 240，富选项带版本与描述，不支持搜索），可换选成别的 CLI | 卡即选项 | 其他卡已选过的包不再列出；「添加 CLI」直接放入第一个未选的包，全部选完时按钮禁用 | `selectedCliIds` 初值 `[]` | `GET /api/admin/clis/page?pageNum=1&pageSize=100&status=1` | `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:90`-`:106`、`:132`-`:139`、`:150`-`:177`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:79`-`:84`；`harnax-webui/src/services/ant-design-pro/cli.ts:6`-`:22` |
| `cliList[].envBindings[]` | 共用环境参数表；包未声明参数时显示「该 CLI 不需要环境变量参数」 | 否 | 必填参数缺值→「{cli} 缺少必填环境参数 {param}」，声明默认值算已填 | 每次渲染按包 `envParams` 重建行，未存的键值为空 | `GET /api/admin/env-variables/list` | `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:35`-`:40`、`:188`-`:205`；`harnax-webui/src/pages/agent/components/configValidation.ts:154`-`:159`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:579`-`:586` |
| 包内技能 | 只读 Tag（不可点，不可选） | — | 不提交；技能随包在运行时加载 | — | `CliResponse.skill`（`{skillId,skillName,skillDescription}`） | `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:178`-`:182`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt:43`-`:58` |

行/卡操作：每卡一个「删除」按钮，直接按索引移除（`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:108`）。删掉再加回来，之前填过的值仍在（`CliEnvBindings` 按 `cliId` 存，`:11`）。

### 校验与提交流程（两类向导共用）

- 第 1 步「下一步」只校验 `name`/`description`/`systemPrompt`/`modelId` 四项（`harnax-webui/src/pages/agent/components/CreateForm.tsx:147`-`:148`）。
- 第 2～4 步「下一步」各校验自己那一类（`harnax-webui/src/pages/agent/components/CreateForm.tsx:194`-`:196`）。
- 第 5 步「完成／保存」先把**四类一起**过一遍再提交（`harnax-webui/src/pages/agent/components/CreateForm.tsx:149`-`:152`；`harnax-webui/src/pages/agent/components/UpdateForm.tsx:251`-`:254`）。规则集中在 `harnax-webui/src/pages/agent/components/configValidation.ts`，`ConfigIssue` 共 7 种，中文文案见 `harnax-webui/src/locales/zh-CN/pages.ts:459`-`:465`。
- 最后一步提交前重新取一次四个基本字段，其余字段（`owner`）不进请求体（`harnax-webui/src/pages/agent/components/CreateForm.tsx:153`-`:178`）。
- 新建体固定带 `status: 1`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:160`）；编辑体不带 `status`（`harnax-webui/src/pages/agent/components/UpdateForm.tsx:257`-`:280`）。

### 团队向导字段（与智能体向导的差异）

团队向导是**三步**，不是两步：基本信息 → 主管技能 → 成员智能体（`harnax-webui/src/pages/team/components/TeamWizard.tsx:261`-`:269`）。产品裁定的「主管自带配置、团队只配 skill」在代码里成立：`TeamCreateRequest` 的 `name`/`description`/`systemPrompt`/`modelId` 就是主管字段，`skillIds` 是唯一能力位，没有 `toolList`/`mcpList`/`cliList`；`TeamResponse` 注释同样写明「不再有独立的主管智能体」（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:11`-`:35`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:9`-`:28`；`harnax-webui/src/services/ant-design-pro/typings.d.ts:201`）。裁定里「两步向导」这一条与源码不符，现状是三步。

| 步骤 | 字段 | 控件 | 必填 | 校验 | 默认／回填 | 锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `name` | 单行输入 | 是 | 非空＋`max:100`；后端 1–100 且同租户重名拒绝 | 新建空；编辑回填 | `harnax-webui/src/pages/team/components/TeamWizard.tsx:282`-`:312`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:17`-`:20`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:71`-`:73`、`:106`-`:111` |
| 1 | `description` | 多行（2 行，`maxLength 500`） | 是 | 非空（后端 `@NotBlank`） | 新建空；编辑回填；后端 null 时存空串 | `harnax-webui/src/pages/team/components/TeamWizard.tsx:314`-`:335`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:22`-`:24`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:82` |
| 1 | `systemPrompt` | 多行（6 行） | 是 | 非空（后端 `@NotBlank`） | 编辑回填 | `harnax-webui/src/pages/team/components/TeamWizard.tsx:337`-`:362`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:26`-`:28` |
| 1 | `modelId` | 单选下拉，`showSearch`＋`optionFilterProp="label"` | 是 | 非空；后端更严：模型必须存在、租户可见（自有或 `is_public`）、`status===1`、`modelType==='chat'` | 编辑回填；若原模型已不在候选里，插入一条禁用项「{名称} · 该模型已停用、已删除或不再是对话模型」 | `harnax-webui/src/pages/team/components/TeamWizard.tsx:364`-`:386`、`:49`-`:68`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:243`-`:254` |
| 1 | `isPublic` | 开关（Form 受控，`valuePropName="checked"`） | 否 | 无权限门禁（与智能体编辑页不同） | `initialValues.isPublic=false`；编辑按 `values.isPublic===1` | `harnax-webui/src/pages/team/components/TeamWizard.tsx:279`、`:388`-`:401`、`:94` |
| 2 | `skillIds` | 复用智能体的 `SkillConfigPanel` | 否 | 同一套技能校验 | 编辑回填 `values.skillList`，失效技能保留并追加「该技能已停用或已被删除」 | `harnax-webui/src/pages/team/components/TeamWizard.tsx:404`-`:419`、`:100`-`:123`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:182`-`:192` |
| 3 | `members[].agentId` | 每行单选下拉，`showSearch`＋远程搜索 | 是 | 非空；同一团队不允许重复；后端再判存在／同租户／已启用／对当前用户可见 | `initialValues.members=[{}]` | `harnax-webui/src/pages/team/components/MembersField.tsx:102`-`:144`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:262`-`:289` |
| 3 | `members[].delegationDescription` | 单行输入（`maxLength 500`） | 否 | 长度 500 | 留空时后端用成员 agent 自己的 `description` 兜底 | `harnax-webui/src/pages/team/components/MembersField.tsx:146`-`:168`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:57`-`:59`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:301`-`:304` |

与智能体向导的实质差异（逐条对应代码）：

1. 步数 5 比 3，没有工具、MCP、CLI 三步；主管只挂技能，页面文案直说「主管只挂技能，不挂工具、MCP 与 CLI……」（`harnax-webui/src/pages/team/components/TeamWizard.tsx:404`-:`411`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:1095`）。
2. 没有 `owner` 字段，创建人由后端取当前用户（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:88`）。
3. 模型下拉可搜索，智能体向导的不可搜索（`harnax-webui/src/pages/team/components/TeamWizard.tsx:377`-`:385` 对 `harnax-webui/src/pages/agent/components/CreateForm.tsx:247`-`:254`）。
4. 新增成员编排区（`MembersField`），带远程搜索：输入关键词 300 毫秒防抖后 `GET /api/admin/agents/page?pageSize=50&status=1&name={kw}`，请求失败退回初始候选而不是显示空列表（`harnax-webui/src/pages/team/components/MembersField.tsx:35`-`:53`）。页面打开时先用 `pageSize=200&status=1` 取一屏作种子候选（`harnax-webui/src/pages/team/index.tsx:76`-`:83`）。
5. 无「环境参数」这一维：团队不提交任何 `envBindings`。
6. 成员引用与主管技能引用都允许「已失效但仍在列表里」的呈现（红 Tag／禁用选项），见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:195`-`:202`、`:182`-`:191` 与 `harnax-webui/src/pages/team/components/MembersField.tsx:65`-`:81`。
7. 技能是「集合语义」：只有技能集合真的变了才发 `skillIds`，比较用排序后的 id 串，行序变动不算改动；成员仍是整体替换（`harnax-webui/src/pages/team/components/TeamWizard.tsx:199`-`:216`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:120`-`:132`）。

### 团队向导的提交与跳步规则

- 「下一步」在第 1 步只校验四个基本字段，在第 2 步只跑技能校验；第 3 步按钮变「创建／保存」并走 `handleFinish`（`harnax-webui/src/pages/team/components/TeamWizard.tsx:225`-`:240`）。
- `handleFinish` 里若技能校验不过→跳回第 2 步；若基本字段报错且报错项属于第 1 步→跳回第 1 步；若成员为空→跳回第 3 步并提示「至少需要一个成员」（`harnax-webui/src/pages/team/components/TeamWizard.tsx:173`-`:198`）。
- 三步共用一个 Form 实例，未显示的步骤保持挂载，切回来时红字与已填值都在（`harnax-webui/src/pages/team/components/TeamWizard.tsx:272`-`:280`）。

---

## 关联与绑定

### 四类关联的交互形态对照

| 维度 | 工具 | MCP | 技能 | CLI | 团队成员 |
| --- | --- | --- | --- | --- | --- |
| 多选方式 | 多行，每行单选 | 多行，每行单选 | 多行，仓库＋技能两级单选 | 多卡，每卡单选可换选 | 多行，每行单选 |
| 支持搜索 | 否（无 `showSearch`） | 否 | 否 | 否 | 是，且走远程搜索 |
| 已选回显 | 行内下拉标题＋展开的环境参数表 | 「MCP #序号」＋下拉 | 两列下拉，技能用 `labelInValue` 保住名字 | 卡标题为下拉值，右侧包内技能 Tag | 行内下拉，标签文本「{name} - {description}」 |
| 去重手段 | 其他行已选的工具不再列出 | 同左 | 同左，另有提交前重复校验 | 同左；添加按钮在全部选完后禁用 | 下拉可重复选，靠行级 validator 拦 |
| 能否设顺序 | 只有行序，可增删但无拖拽手柄 | 同左 | 同左 | 卡片顺序＝数组顺序，可删后重加 | 行序即主管看到的顺序，界面明示但仍无拖拽 |
| 单行时能否删 | 能删（可删到 0 行） | 不能删（大于 1 才显示按钮） | 不能删，改为清空该行 | 能删 | 不能删（大于 1 才显示移除图标） |
| 锚点 | `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:36`-`:46`、`:81`-`:82`、`:115` | `harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:62`-`:69`、`:98`-`:114` | `harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx:63`-`:84`、`:116`-`:153` | `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:95`-`:108`、`:132`-`:139`、`:150`-`:186` | `harnax-webui/src/pages/team/components/MembersField.tsx:55`-`:81`、`:114`-`:130`、`:170`-`:176` |

顺序语义在后端是真实的：四张绑定表都是「先删后插」，读取一律 `ORDER BY id`，因此提交数组顺序就是回读顺序（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:404`/`:442`/`:560` 与 `:432`/`:469`/`:594`；`harnax-entity/src/main/resources/mapper/AgentToolBindingMapper.xml:15`-`:17`、`AgentMcpBindingMapper.xml:14`-`:16`、`AgentSkillBindingMapper.xml:13`-`:16`、`AgentCliBindingMapper.xml:14`-`:16`、`TeamMemberMapper.xml:15`-`:17`）。去重发生在保序之后：`distinctBy { it.toolId / it.mcpId / it.cliId }` 只留第一条，技能在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillBindingResolver.kt:44` 用 `distinct()`。这就是四类选择器都把「别的行已选」剔出候选的原因——留两行第二行填的值会静默丢掉（`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:37`-`:41`、`harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:110`-`:112`、`harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx:71`-`:74`、`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:93`-`:95`）。

### 保存请求体里的结构（四类）

智能体创建体（`harnax-webui/src/pages/agent/components/CreateForm.tsx:157`-`:178`）、更新体（`harnax-webui/src/pages/agent/components/UpdateForm.tsx:257`-`:280`）形状一致，字段与后端 DTO 对应如下（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:30`-`:40`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentUpdateRequest.kt:24`-`:34`）：

| 键 | 类型 | 说明 | 锚点 |
| --- | --- | --- | --- |
| `mcpList` | `{id, envBindings[]}[]` | 只保留选了 `mcpId` 的行；请求侧用 `id`，响应侧用 `mcpId`（读写不对称，iOS 建模要分两套 key） | `harnax-webui/src/pages/agent/components/CreateForm.tsx:161`-`:167`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:54`-`:61`；响应 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:91`-`:96` |
| `toolList` | `{id, needConfirm, envBindings[]}[]` | 只保留选了 `toolId` 的行 | `harnax-webui/src/pages/agent/components/CreateForm.tsx:169`-`:176`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt:6`-`:15` |
| `skillList` | 逗号分隔字符串，如 `"1,2,3"` | 新建用 `join(',')`，编辑用 `map(toString).join(',')`；空选＝空串，后端按空清空全部 | `harnax-webui/src/pages/agent/components/CreateForm.tsx:168`；`harnax-webui/src/pages/agent/components/UpdateForm.tsx:270`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:534`-`:539` |
| `cliList` | `{id, envBindings[]}[]` | 每张卡一项，`envBindings` 只发「有值」的行 | `harnax-webui/src/pages/agent/components/CreateForm.tsx:177`；`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:65`-`:72` |
| `members`（团队） | `{agentId, delegationDescription}[]` | 空数组不允许 | `harnax-webui/src/pages/team/components/TeamWizard.tsx:211`-`:214`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:37`-`:40` |
| `skillIds`（团队） | `number[] \| 不发送` | 未改动技能时字段缺席，避免后端「整体替换」被一条失效技能顶住 | `harnax-webui/src/pages/team/components/TeamWizard.tsx:200`-`:210`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamUpdateRequest.kt:30`-`:31` |

### 环境参数绑定表（三张表已合一）

工具／MCP／CLI 三类共用同一个组件 `EnvParamTable`（`harnax-webui/src/pages/agent/components/EnvParamTable.tsx:51`），技能不带 env（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:530`-`:533`）。

声明侧字段 `ToolEnvParamEntry`：`envParamName`、`description`、`required`、`secret`、`defaultValue`（`harnax-webui/src/typings.d.ts:151`-`:159`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt:12`-`:32`）。来源分别是工具的 `envParams`（`GET /api/admin/tools/available`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt:30`）、MCP 服务的 `envParams`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt:44`）、CLI 包的 `envParams`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt:37`）。

表格结构：可折叠标题「环境参数 (N)」＋三列（参数名称 30%／环境变量 35%／值 35%）；参数名用等宽字体，Tooltip 显示 `description`，`required` 打红标签「必填」，`secret` 打橙标签「敏感」（`harnax-webui/src/pages/agent/components/EnvParamTable.tsx:76`-`:132`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:525`-`:526`、`:653`-`:659`）。

本地编辑态 `EnvBindingRow = {envKey, envValue, envVarId?, customInput?}`（`harnax-webui/src/pages/agent/components/EnvParamTable.tsx:14`-`:19`）。两个动作：

- 「环境变量」列是单选下拉，候选＝全局变量列表＋末项哨兵「✏️ 自定义」（值 `-1`），支持 `showSearch` 按 `envKey` 过滤，可清空（`harnax-webui/src/pages/agent/components/EnvParamTable.tsx:36`-`:37`、`:134`-`:155`）。
- 选中真实变量时只落 `envVarId` 并把 `envValue` 清掉，绝不把列表给的掩码当值存（`:62`-`:73`）；选「自定义」时清空 `envVarId` 并置 `customInput=true`（`:64`-`:67`）；「值」列在自定义态是输入框（敏感参数用密码框），引用态是只读的 `displayValue`（敏感项带锁图标），都没填且允许提示时显示灰字「包内默认值：{value}」，否则显示 `-`（`:157`-`:189`）。

变量候选来自 `GET /api/admin/env-variables/list`，映射成 `{id, envKey, displayValue, sensitive}`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:116`-`:123`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/EnvVariableController.kt:39`-`:46`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:245`-`:272`）。这里只列当前用户自己创建、`enabled==1` 的变量，且候选范围窄于「已存绑定可解析」的范围（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:247`-`:251`）。

提交形状 `EnvBinding = {envKey, envValue?, envVarId?, envVarName?, customValue?}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt:18`-`:33`；前端 `harnax-webui/src/typings.d.ts:550`-`:556`）。映射规则：`customInput \|\| 没有 envVarId` → 发 `customValue: envValue`；否则只发 `envVarId`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:163`-`:166`、`:172`-`:175`；CLI 同规则加一层过滤，`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:65`-`:72`）。后端序列化时引用只存指针，不存值（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:606`-`:631`）。

**已存在的不对称**（iOS 需要决定是沿用还是修平）：工具与 MCP 把每一行都发出去，空行也发成 `customValue: ""`；CLI 只发非空行（`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:59`-`:64` 的注释明确说明理由：空行会占住快照键位，使「留空＝跟随包默认值」在数据上分不清）。

默认值与回填规则（既有裁定已在代码中落地，逐条对应）：

| 场景 | 规则 | 锚点 |
| --- | --- | --- |
| 新建时选工具 | 非敏感项回填 `defaultValue`，敏感项留空——后端对敏感项只给掩码，抄进来会把 `abc****wxyz` 变成真值存进快照 | `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:58`-`:64`；掩码实现 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:80`-`:85`、`:91`-`:112` |
| 新建时选 MCP | **一律留空**，不回填包默认值：默认值本会随 spec 整份下发，回填等于把它冻在建 agent 时的副本上 | `harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:43`-`:48` |
| 新建时选 CLI | **一律留空**，且未填行不进请求体 | `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:35`-`:40`、`:59`-`:72` |
| 必填判定 | 工具：默认值不算已填（`defaultValueCounts=false`）；MCP 与 CLI：默认值算已填（`true`） | `harnax-webui/src/pages/agent/components/configValidation.ts:120`、`:130`、`:156`；`harnax-webui/src/pages/agent/components/envBinding.ts:13`-`:34`；后端同口径 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:420`、`:460`、`:585` |
| 编辑回填 | 有 `envVarId` 的行只带 id 回去、`envValue` 置空；无 id 的行按 `customValue ?? envValue` 回填并置 `customInput=true` | `harnax-webui/src/pages/agent/components/UpdateForm.tsx:81`-`:88`（MCP）、`:118`-`:123`（工具）、`:134`-`:140`（CLI）；后端把引用解析成最新可显示值返回，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:746`-`:782` |
| 编辑回填后补声明 | 工具与 MCP 的行在候选加载完后用实体 `envParams` 补 `envEntries`（只补没有的） | `harnax-webui/src/pages/agent/components/UpdateForm.tsx:154`-`:164`、`:167`-`:179` |
| CLI 编辑回填 | 只回填 `cliId` 数组与按 id 归拢的绑定行；参数行仍每次按包声明重建 | `harnax-webui/src/pages/agent/components/UpdateForm.tsx:130`-`:142`；`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:35`-`:57` |
| 服务端二次校验 | 同一 key 混用两种来源→拒绝；引用了不存在／跨租户／已停用的变量→拒绝；必填缺值→拒绝；MCP 服务缺失／跨租户／停用→拒绝；CLI 包缺失→拒绝 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:649`-`:679`、`:701`-`:716`、`:718`-`:725`、`:504`-`:519`、`:567`-`:570` |

### 工具／技能／MCP 的响应侧回显字段

卡片浮层与编辑回填都读 `AgentResponse` 的四个列表：`toolList[]`（`toolId,toolName,toolDisplayName,toolDisplayNameZh,toolDescription,needConfirm,envBindings`）、`mcpList[]`（`mcpId,mcpName,mcpDescription,envBindings`）、`skillList[]`（`repositoryId,repositoryName,skillId,skillName,skillDescription`）、`cliList[]`（`cliId,cliName,cliDescription,version,envBindings,skillList`）（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:124`-`:166`；填充逻辑 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:304`-`:392`，其中 CLI 的 `skillList` 是包内那一条技能，`:374`-`:388`）。

---

## 拦截与副作用流程

### 启停（智能体）

开关直接调 `PUT /api/admin/agents/toggle/{id}?status={0|1}`，**没有任何前置查询**；成功后只就地改这一张卡的状态、不重刷列表，失败提示「操作失败，请重试」（`harnax-webui/src/pages/agent/index.tsx:594`-`:608`）。后端仅做单行租户校验后写 `status`，不拦引用关系（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:206`-`:210`；SQL `AgentMapper.xml:75`-`:77`）。

### 启停（团队）

`PUT /api/admin/teams/toggle/{id}?status=`，无前置查询；成功提示「启用成功／停用成功」并整页重刷，失败显示后端 message（`harnax-webui/src/pages/team/index.tsx:113`-`:129`）。后端同样只校验可见性（`is_public=1 或 creator=当前用户`＋同租户）（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:137`-`:141`、`:340`-`:349`）。

### 删除（智能体）：先弹确认，靠后端拒绝

前端二次确认标题「确认删除智能体吗？」、内容「此操作不可恢复，请谨慎操作。」，确定按钮为危险样式；确认后 `DELETE /api/admin/agents/{id}`，`code!==200` 时把后端 message 原样 toast 出来（`harnax-webui/src/pages/agent/index.tsx:568`-`:592`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:466`-`:467`）。**没有 related 前置查询**，拦截全部由后端抛业务异常，三类文案分支（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:212`-`:254`）：

| 拦截原因 | 后端消息（英文原文） | 锚点 |
| --- | --- | --- |
| 仍是某些团队的成员 | `This agent is a member of team(s): {团队名列表}. Remove it from these teams before deleting it.` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:220`-`:229` |
| 仍有会话（含进行中数量） | `This agent is still used by {N} session(s)[, {M} of them still in progress] and channel(s): {渠道名列表}. Remove these before deleting it.` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:232`-`:247` |
| 跨租户 | `Agent not found` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:214`-`:216` |

删除成功前会清掉四张绑定表的行（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:249`-`:252`）。iOS 若要「先问再一次点击」，可以复用别的域的 related 查询，但智能体自身没有这个端点（见「未确认」）。

### 删除（团队）：有前置查询，三分支

点击删除先以 `skipErrorHandler` 调 `GET /api/admin/teams/{id}/related-sessions`，只读数量（`harnax-webui/src/pages/team/index.tsx:136`-`:145`）。三分支（`:147`-`:195`）：

1. 查询失败：不弹任何窗，只 toast「加载关联信息失败，请重试」——因为空列表和查询失败含义相反，不能把失败说成「没人依赖它」。
2. 有绑定会话：确认框内容用「还有 {count} 个会话绑定着该团队，删除会被拒绝。请先删除这些会话——删除会话同时会清掉它们的团队产物。」，标题仍是「确认删除该团队？」。
3. 无绑定会话：内容「此操作不可恢复，成员与主管技能绑定会一并删除。」

文案见 `harnax-webui/src/locales/zh-CN/pages.ts:1111`-`:1114`。确认后 `DELETE /api/admin/teams/{id}`；后端仍会二次拦截，消息点名最多 5 个会话 id，超出用「, …」收尾（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:153`-`:168`、`:358`-`:361`）。成功则整页重刷（`harnax-webui/src/pages/team/index.tsx:185`）。

### 保存后「刷新受影响会话」弹窗

- 触发时机：**只有更新成功才弹**，新建不弹（`harnax-webui/src/pages/agent/index.tsx:771`-`:779`；团队同形态 `harnax-webui/src/pages/team/index.tsx:394`-`:399`）。
- 数据来源：按 `source` 分支——`agent` 用 `GET /api/admin/agents/{agentId}/related-sessions`，`cli` 用 `GET /api/admin/clis/{id}/related-sessions`，`team` 用 `GET /api/admin/teams/{id}/related-sessions`，全部带 `skipErrorHandler`（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:56`-`:61`）。
- 响应行 `{sessionId, sourceType, sourceName, agentName?}`，`sourceType` 只有 `channel`／`session`（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:11`-`:17`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:182`-`:188`）。渠道行名格式「{渠道名} ({类型})」，Web 会话行名取标题、标题为空时用 sessionId；渠道要求 `sessionId` 非空、会话要求 `active==1` 且 `sessionId` 非空，并按各行自身 `tenant_id` 过滤（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:60`-`:88`）。团队侧只产 `session` 类型（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:207`-`:220`）。
- 默认全勾选（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:67`-`:68`）；一个都没勾时主按钮文案变「跳过」，点击只关窗不发请求（`:81`-`:84`、`:123`-`:131`）；取消按钮文案「稍后」。
- 提交：`POST /api/admin/agents/refresh-sessions`，请求体 `{sessionIds: string[]}`（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:87`；`harnax-webui/src/services/ant-design-pro/agent.ts:176`-`:183`；后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:105`-:`118`）。团队与 CLI 的刷新复用同一个提交端点。
- 后端逐条向 session-router 发 `POST {router}/api/router/agent/command`，体 `{sessionId, command:"REFRESH", args:"", type:"COMMAND"}`，用渠道服务的 SYSTEM key 认证；单条失败不中断整批，返回 `[{sessionId, success, error}]`；拿不到系统 key 时整批返回失败（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:94`-`:121`、`:127`-`:134`）。
- 结果文案：全成功「已刷新 {count} 个会话」；有失败「{ok} 个成功，{fail} 个失败」；整体失败「刷新失败」（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:91`-`:109`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:391`-`:402`）。500 毫秒后自动关窗（`:106`）。
- 空列表与加载失败是两种状态：空态「暂无关联会话」，失败态「加载关联信息失败，请重试」＋「重试」按钮（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:174`-`:191`）。
- 行渲染：类型标签（渠道＝蓝、会话＝紫）＋`sourceName`＋CLI 来源时追加 geekblue 的 `agentName` 标签＋等宽小字 sessionId（超过 24 字符截断加省略号）（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:198`-`:214`）。
- 说明文案三条分支，都写「未勾选的会话将在约 30 分钟内自动生效」；30 分钟对应 agent-service 按 sessionId 缓存 spec 的 TTL（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:141`-`:168`；文案 `harnax-webui/src/locales/zh-CN/pages.ts:393`、`:1115`、`:1032`；TTL 出处 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:22`-`:27`）。

### 编辑回填的读源

编辑弹窗不额外请求详情：`values` 直接是列表页返回的那一行（`harnax-webui/src/pages/agent/index.tsx:714`-`:717`、`:759`-`:762`）。列表响应即 `AgentResponse` 全量（含 `systemPrompt`、四类绑定、`sessionList`、`sessionCount`），转换时逐行查会话与四类绑定表（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:265`-`:395`）。`GET /api/admin/agents/{id}` 在智能体页与团队页都没有被调用，只被会话详情弹窗使用（`harnax-webui/src/pages/session/components/DetailModal.tsx:117`、`:132`）。

---

## 接口清单

统一信封 `{code, message, data}`（`harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/ResultVo.kt:10`-`:18`）；分页体为 `{pageNum, pageSize, total, records, pages, hasPrevious, hasNext}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/Page.kt:9`-`:31`；前端 `PageResult` 只按 `records`/`total` 用，`harnax-webui/src/services/ant-design-pro/typings.d.ts:69`-`:75`）。

| # | 方法＋路径 | 关键请求字段 | 响应关键字段 | 前端调用点 | 后端定义 |
| --- | --- | --- | --- | --- | --- |
| 1 | `GET /api/admin/agents/page` | `pageNum`、`pageSize`、`name`、`status` | `Page<AgentResponse>`：`id,name,description,systemPrompt,modelId,modelName,modelPrice,status,isPublic,owner,creator,createTime,updateTime,sessionCount,sessionList,toolList,mcpList,skillList,cliList` | `harnax-webui/src/services/ant-design-pro/agent.ts:8`-`:24`；`harnax-webui/src/pages/agent/index.tsx:505`；团队页取候选 `harnax-webui/src/pages/team/index.tsx:78` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:36`-`:55`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:11`-`:67` |
| 2 | `GET /api/admin/agents/{id}` | 路径 `id` | 同上单条；跨租户按 404 文案 `error.agent.notfound` | 智能体页未用（会话详情用，`harnax-webui/src/pages/session/components/DetailModal.tsx:132`） | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:57`-`:69` |
| 3 | `POST /api/admin/agents` | `name,description,systemPrompt,modelId,status,isPublic,mcpList[{id,envBindings}],toolList[{id,needConfirm,envBindings}],skillList(逗号串),cliList[{id,envBindings}]`；`owner` 被忽略 | `Void`，`code` 决定 toast | `harnax-webui/src/pages/agent/index.tsx:744`；体拼装 `harnax-webui/src/pages/agent/components/CreateForm.tsx:157`-`:178` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:71`-`:80`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:12`-`:49` |
| 4 | `PUT /api/admin/agents/update/{agentId}` | 同 3 且全部可选；`null` 字段保持原值；四类列表非 `null` 即整体替换 | `Void` | `harnax-webui/src/pages/agent/index.tsx:769`；体拼装 `harnax-webui/src/pages/agent/components/UpdateForm.tsx:257`-`:280` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:82`-`:92`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentUpdateRequest.kt:10`-`:41`；替换语义 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:184`-`:196` |
| 5 | `PUT /api/admin/agents/toggle/{id}` | query `status`（0/1） | `Void` | `harnax-webui/src/services/ant-design-pro/agent.ts:74`-`:84`；`harnax-webui/src/pages/agent/index.tsx:597` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:120`-`:130` |
| 6 | `DELETE /api/admin/agents/{id}` | 路径 `id` | `Void`；被拦时 `message` 即拒绝理由 | `harnax-webui/src/pages/agent/index.tsx:578` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:132`-`:141`；拦截 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:212`-`:254` |
| 7 | `GET /api/admin/agents/{agentId}/related-sessions` | 路径 `agentId` | `[{sessionId,sourceType,sourceName}]` | `harnax-webui/src/services/ant-design-pro/agent.ts:166`-`:171`；`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:61` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:94`-`:103`；实现 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:60`-`:88` |
| 8 | `POST /api/admin/agents/refresh-sessions` | `{sessionIds: string[]}` | `[{sessionId,success,error}]` | `harnax-webui/src/services/ant-design-pro/agent.ts:176`-`:183`；`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:87` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:105`-`:118`；实现 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:94`-`:121` |
| 9 | `GET /api/admin/teams/page` | `pageNum`、`pageSize`、`name`、`status` | `Page<TeamResponse>`：`id,name,description,systemPrompt,modelId,modelName,skillList[],memberList[],status,isPublic,tenantId,creator,createTime,updateTime` | `harnax-webui/src/services/ant-design-pro/team.ts:6`-`:25`；`harnax-webui/src/pages/team/index.tsx:56` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:33`-`:52`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:14`-`:84` |
| 10 | `GET /api/admin/teams/{id}` | 路径 `id` | 单条 `TeamResponse`；不可见时 404 `error.team.notfound` | 团队页未用（会话详情用，`harnax-webui/src/pages/session/components/DetailModal.tsx:117`） | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:54`-`:65` |
| 11 | `POST /api/admin/teams` | `name,description,systemPrompt,modelId,skillIds[],members[{agentId,delegationDescription}],status,isPublic` | `Void` | `harnax-webui/src/services/ant-design-pro/team.ts:33`-`:40`；`harnax-webui/src/pages/team/index.tsx:368`（固定补 `status:1`） | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:67`-`:76`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:16`-`:60` |
| 12 | `PUT /api/admin/teams/update/{id}` | 同 11 但除 `name` 外都可空；`skillIds` 非空即整体替换、空数组＝不给主管挂技能；`members` 非空即整体替换 | `Void` | `harnax-webui/src/services/ant-design-pro/team.ts:43`-`:54`；`harnax-webui/src/pages/team/index.tsx:393`；拼装 `harnax-webui/src/pages/team/components/TeamWizard.tsx:205`-`:216` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:78`-`:88`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamUpdateRequest.kt:16`-`:39`；语义 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:101`-`:134` |
| 13 | `DELETE /api/admin/teams/{id}` | 路径 `id` | `Void`；有会话时 `message` 点名最多 5 个 sessionId | `harnax-webui/src/services/ant-design-pro/team.ts:57`-`:59`；`harnax-webui/src/pages/team/index.tsx:183` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:102`-`:111`；拦截 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:153`-`:168` |
| 14 | `PUT /api/admin/teams/toggle/{id}` | query `status` | `Void` | `harnax-webui/src/services/ant-design-pro/team.ts:62`-`:68`；`harnax-webui/src/pages/team/index.tsx:115` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:90`-`:100` |
| 15 | `GET /api/admin/teams/{id}/related-sessions` | 路径 `id` | `[{sessionId,sourceType:"session",sourceName}]` | `harnax-webui/src/services/ant-design-pro/team.ts:71`-`:76`；删除前置 `harnax-webui/src/pages/team/index.tsx:140`；刷新弹窗 `:413` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:113`-`:125`；实现 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:207`-`:220` |
| 16 | `GET /api/admin/models/page` | `pageNum`（默认 1）、`pageSize`（默认 100）；无服务端状态过滤 | `records[]`：`id,modelName,providerName,price,modelType,status,supportTool,supportMcp` 等 | `harnax-webui/src/services/ant-design-pro/agent.ts:145`-`:161`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:101`-`:107` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelController.kt:22`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelResponse.kt:13`-`:51` |
| 17 | `GET /api/admin/mcp/page` | `pageNum=1,pageSize=100,status=1` | `records[]`：`id,name,description,type,status,envParams[]` | `harnax-webui/src/services/ant-design-pro/agent.ts:99`-`:116`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:86`-`:91` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:22`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt:16`-`:44` |
| 18 | `GET /api/admin/tools/available` | 无参 | `[{id,name,displayName,displayNameZh,description,envParams[],needConfirm,isRequired,status}]`；`is_required=1` 的必装工具被排除 | `harnax-webui/src/services/ant-design-pro/tool.ts:6`-`:11`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:109`-`:114` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:54`-`:65`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt:9`-`:54`；SQL `AgentToolMapper.xml:60`-`:62` |
| 19 | `GET /api/admin/env-variables/list` | 无参 | `[{id,envKey,displayValue,sensitive}]`，只含当前用户创建且 `enabled==1` | `harnax-webui/src/services/ant-design-pro/envVariable.ts:68`-`:73`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:116`-`:123` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/EnvVariableController.kt:39`-`:46`；实现 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:245`-`:272` |
| 20 | `GET /api/admin/clis/page` | `pageNum=1,pageSize=100,status=1` | `records[]`：`id,name,description,version,packageDigest,envParams[],skill{skillId,skillName},status` | `harnax-webui/src/services/ant-design-pro/cli.ts:6`-`:22`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:79`-`:84` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:37`-`:52`；DTO `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt:23`-`:58` |
| 21 | `GET /api/admin/skill-sources/page` | `pageNum=1,pageSize=100,status=1`（`getSkillSourceOptions` 封装） | `records[]`：`id,name,url,branch,sourceType,status` 等 | `harnax-webui/src/services/ant-design-pro/skillSource.ts:5`-`:22`、`:101`-`:113`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:93`-`:99` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt:19` |
| 22 | `GET /api/admin/skills/page` | `repositoryId,pageNum=1,pageSize=100,status=1` | `records[]`：`id,name,repositoryId,repositoryName,description,status` | `harnax-webui/src/services/ant-design-pro/agent.ts:121`-`:140`；`harnax-webui/src/pages/agent/components/CreateForm.tsx:125`-`:130` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillController.kt:47`-`:50`；可见性 SQL `harnax-entity/src/main/resources/mapper/SkillMapper.xml:84`-`:104` |
| 23 | `GET /api/admin/clis/{id}/related-agents` | 路径 `id` | `[{agentId,agentName,status}]`（CLI 域使用，智能体域不直接调；弹窗共用组件时走 `source:'cli'`） | `harnax-webui/src/services/ant-design-pro/cli.ts:48`-`:53` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:144`-`:158`；类型 `harnax-webui/src/typings.d.ts:628`-`:632` |
| 24 | `GET /api/admin/clis/{id}/related-sessions` | 路径 `id` | `[{sessionId,sourceType,sourceName,agentName}]`（带所属 agent 名） | `harnax-webui/src/services/ant-design-pro/cli.ts:56`-`:61`；`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:58` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:170`-`:172` |

---

## iOS 适配注意点

1. **两套列表形态要分开做**：智能体是卡片瀑布（`ResponsiveCardGrid`＋`CardPagination`），团队是表格（`Table`）。iOS 上智能体适合 `LazyVGrid` 两列卡片，团队更适合 `List` 行＋右滑删除；两者的分页语义不同（前者 pageSize 与每行卡数联动，后者固定 `10/20/50`）。
2. **hover 浮层必须换形态**：五格统计的 Popover（`harnax-webui/src/pages/agent/index.tsx:90`-`:416`）与浮层里 `window.open` 跳别的域页面（`:117`、`:178`、`:238`、`:305`、`:377`）在触摸端不成立。建议改为点统计格→底部 sheet 列条目，条目→NavigationLink 到对应域。
3. **向导的宽度假设**：webui 用 `FormModal size="xl"`、内容区 `maxHeight calc(100vh - 320px)`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:216`-`:234`）。iOS 应做全屏幕 sheet ＋ 顶部 `ProgressView`/步骤条，第 4 步的两列下拉（仓库＋技能）要拆成两级导航。
4. **无搜索的下拉**：工具／MCP／技能／CLI 四个选择器都没有 `showSearch`，但候选集被 `pageSize=100` 截断（`harnax-webui/src/pages/agent/components/CreateForm.tsx:81`、`:88`、`:103`、`:127`）。iOS 加搜索是改进，但要保持「别的行已选不再列出」的去重口径，否则用户能选出重复行、后端 `distinctBy` 静默丢第二条（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:432`、`:469`、`:594`）。
5. **成员选择是唯一带远程搜索的**：300 毫秒防抖、`pageSize=50`、失败退回种子候选（`harnax-webui/src/pages/team/components/MembersField.tsx:35`-`:53`）。iOS 可直接沿用 `.task(id:)` 防抖写法。
6. **顺序编辑缺交互**：四类绑定和团队成员都有顺序语义（后端 `ORDER BY id` 回读，团队还明示「主管按这个顺序看到可委派的成员」，`harnax-webui/src/locales/zh-CN/pages.ts:1107`），但 webui 只能靠删了重加。iOS 建议加 `.onMove`／拖拽把手，写入顺序即提交数组顺序，不需要新增接口。
7. **环境参数表要为窄屏重排**：三列等宽表格（`harnax-webui/src/pages/agent/components/EnvParamTable.tsx:99`-`:109`）在 iPhone 上放不下，建议一参数一 section：标题＝参数名＋必填／敏感标签，值行＝「引用变量」或「自定义」二选一分段控件，引用态显示 `displayValue`（敏感加锁图标），空态且有包默认值时显示灰字提示。
8. **掩码不可回写**：敏感项的 `displayValue`／`defaultValue` 都是掩码，任何「回填后原样提交」的实现都会把星号变成正式值。规则见 `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:58`-`:64`、`harnax-webui/src/pages/agent/components/EnvParamTable.tsx:62`-`:73`、`harnax-webui/src/pages/agent/components/UpdateForm.tsx:81`-`:88`；后端还会用 `contains("****")` 兜底判定（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:723`）。
9. **读写 key 不对称**：MCP／工具／CLI 的请求用 `id`，响应分别用 `mcpId`／`toolId`／`cliId`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:55`-`:73` 对 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:91`-`:166`）。Swift Codable 需要两套模型，别共用。
10. **技能提交是字符串不是数组**：`skillList: "1,2,3"`（`harnax-webui/src/pages/agent/components/CreateForm.tsx:168`），团队才是 `skillIds: number[]`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:35`）。同名不同 id 的技能会被后端整批拒绝（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillBindingResolver.kt:63`-`:68`），iOS 最好在提交前先查重名。
11. **团队技能字段的「不动即不发」**：比较的是排序后的 id 集合（`harnax-webui/src/pages/team/components/TeamWizard.tsx:200`-`:210`）。iOS 若直接每次带 `skillIds`，一条失效技能就会让团队连改名都保存不了（后端整体替换会拒收失效绑定，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:126`）。
12. **失效引用的呈现**：成员红 Tag、主管技能「已停用或已被删除」标签、主管模型的禁用项（`harnax-webui/src/pages/team/index.tsx:241`、`harnax-webui/src/pages/team/components/TeamWizard.tsx:107`-`:113`、`:57`-`:66`）。iOS 需要等价的三种降级样式，不能把这些项直接从列表里隐藏。
13. **启停是乐观更新还是重刷要定**：智能体页只改本地那一张卡（`harnax-webui/src/pages/agent/index.tsx:600`-`:604`），团队页整表重刷（`harnax-webui/src/pages/team/index.tsx:122`）。iOS 建议统一为乐观更新＋失败回滚，并保留「智能体开关不受权限门禁」这一现状判断（`harnax-webui/src/components/EntityCard/index.tsx:294`）或在 app 内收紧。
14. **删除拦截文案来自后端英文消息**：智能体删除被拦时前端原样 toast `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:226`-`:246` 的英文串。iOS 要么做后端消息的模式匹配再本地化，要么在文案层显式承认这是未 i18n 的例外。
15. **刷新弹窗要保留三个状态**：加载中／加载失败（带重试）／空列表，且加载失败绝不能渲染成空态（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:42`-`:44`、`:174`-`:191`）。团队删除前置查询同理（`harnax-webui/src/pages/team/index.tsx:149`-`:158`）。
16. **`skipErrorHandler` 的含义**：这几处调用关掉全局错误提示，因为错误要在控件内呈现（`harnax-webui/src/pages/agent/components/AgentRefreshModal.tsx:52`-`:56`、`harnax-webui/src/pages/team/index.tsx:138`-`:140`）。iOS 网络层需要一个「本次请求由调用方自己处理错误」的开关。
17. **列表响应即详情**：`/agents/page` 每行都带回四类绑定与全部会话（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:291`-`:392`），列表接口是 N+1 查询。iOS 可以直接吃这个结构省一次详情请求（webui 就是这么做的），但在大列表下要考虑改用 `GET /api/admin/agents/{id}` 按需展开。
18. **`ResultVo` 的 `message` 在成功时也是 `"success"`**，判定必须走 `code === 200`，不能用 message 判空（`harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/ResultVo.kt:12`-`:15`；前端各分支一致，如 `harnax-webui/src/pages/agent/index.tsx:745`）。
19. **分页体字段名不一致**：后端是 `pageNum/pageSize`，前端 `PageResult` 声明的是 `size/current`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/Page.kt:9`-`:13` 对 `harnax-webui/src/services/ant-design-pro/typings.d.ts:69`-`:75`）。iOS 建模按后端为准。
20. **webui 没有智能体详情页**，`GET /api/admin/agents/{id}` 只在会话详情里用（`harnax-webui/src/pages/session/components/DetailModal.tsx:132`）。`harnax-wechat-app/miniprogram/pages/agent/detail/index.ts:21`-`:27`（非权威，仅参考）有一版详情页：读同一端点、对 `mcpList/toolList/skillList` 做空数组兜底，但没有 `cliList`——即该参考落后于 CLI 这一步，iOS 若做详情页应以 webui 卡片字段集为准。

---

## 未确认

1. **已定案**：团队向导按源码的三步实现（基本信息／主管技能／成员智能体，`harnax-webui/src/pages/team/components/TeamWizard.tsx:261`-`:269`），不合并步骤。裁定的另外两条——主管自带配置、团队只配 skill 且无独立主管 agent 行——已在代码中证实（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:11`-`:35`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:9`-`:28`、`harnax-webui/src/services/ant-design-pro/typings.d.ts:201`）。
2. **已定案**：智能体删除不新增前置查询，沿用 webui 形态——直接提交、把后端拒绝 message 原样呈现（`harnax-webui/src/pages/agent/index.tsx:568`-`:592` 直接确认后调用，后端只有拒绝式拦截）。要做「先列受影响团队／会话再一键确认」需要后端新接口，不在 v1。
3. 「约 30 分钟自动生效」这句话的权威出处只在 admin 侧注释（`harnax-session-router`／`agent-service` 的缓存 TTL 未在智能体域源码中读到，见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:22`-`:27`）。iOS 文案要不要保留该时长，建议向运行时域规格确认。
4. **已定案**：管理员标记取自登录响应的 `data.userInfo.isAdmin`（`Int`，`1` 才是管理员；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginResponse.kt:98`），`cli-login` 同样填了它（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AuthServiceImpl.kt:220`-`:224`）。iOS 用它替代 webui 的 `localStorage.currentUser` 读法（`harnax-webui/src/utils/permissionUtil.ts:80`-`:97`），同一个值也进了 JWT 声明，因此切租户重签令牌时要把它一并带上。
5. 卡片 `pageSize` 默认 8 是按「每行 4 张 ×2」硬编码的初值（`harnax-webui/src/pages/agent/index.tsx:456`），实际每行卡数在运行时由容器宽度算出（`harnax-webui/src/components/ResponsiveCardGrid/index.tsx:60`-`:79`）。iOS 的每行卡数与 pageSize 需要自己定规则，源码里没有可直接搬的常量。
6. 工具、MCP 提交时空行也发 `customValue: ""`，而 CLI 只发非空行（`harnax-webui/src/pages/agent/components/CreateForm.tsx:161`-`:177` 对 `harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:59`-`:72`）。这是既有不对称，iOS 是否统一（以及统一会不会影响已存快照的语义）需要产品／后端裁定。
7. `pages.team.membersExtra` 这个 key 被两处复用、语义不同：`harnax-webui/src/pages/team/components/MembersField.tsx:88` 用它做成员区说明（英文兜底「The lead only delegates to these agents…」），`harnax-webui/src/pages/team/components/TeamWizard.tsx:195` 用它做「至少需要一个成员」的错误提示，而中文值只有一条「至少需要一个成员」（`harnax-webui/src/locales/zh-CN/pages.ts:1098`）。iOS 该显示哪句需确认。
8. 新建智能体是否会触发刷新弹窗：webui 只在更新成功后弹（`harnax-webui/src/pages/agent/index.tsx:771`-`:779`），新建不弹。这是遗漏还是有意为之（新建时不可能有历史会话，理论上确实为空），源码未表明。
9. `AgentCreateRequest.SkillConfig` 标注 deprecated 且未被任何前端调用（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:76`-`:91`）。iOS 建模是否直接丢弃该结构，需后端确认它是否还服务别的客户端。
10. 编辑态 `isPublic` 开关在团队向导里没有权限门禁（`harnax-webui/src/pages/team/components/TeamWizard.tsx:388`-`:401`），在智能体向导里有（`harnax-webui/src/pages/agent/components/UpdateForm.tsx:362`-`:365`）。这个差异是遗漏还是有意，未确认。

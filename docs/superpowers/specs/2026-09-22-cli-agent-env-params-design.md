# CLI 包参数在智能体上填值 · 设计规格

- 日期：2026-09-22
- 状态：已实现，后端 + webui + 文档齐；运行期端到端已在 docker-new 环境验毕
- 范围：`harnax-admin`（校验与下发）、`harnax-webui`（智能体向导 CLI 步骤、CLI 页）、`prod_doc` 双语包规范

## 0. 要解决的问题

一个 CLI 包在 `plugin.yaml` 里声明它需要哪些环境变量（`envParams`），但智能体勾选这个 CLI 时只能勾，不能填值：缺值的报错只会出现在沙箱里第一条命令上（「未配置」「未登录」），管理员在页面上看不出任何区别。

目标：让 CLI 的参数填写与 tools / MCP 完全同构——声明在包上，值在智能体上给，必填参数缺值时挡住保存。

## 1. 决策清单（已定稿）

| # | 决策 | 依据 |
|---|---|---|
| D1 | 参数列表由包声明，智能体只存值，不给参数列表提供任何编辑入口 | 一个 CLI 一份声明，N 个智能体共享；声明改动随包升级自然生效 |
| D2 | 填写入口放在智能体配置向导的 CLI 步骤（第五步），形态与工具步骤同构：「一卡一个 CLI，卡内一张参数表」 | 与 tools / MCP 的既有交互同构，管理员不需要学新东西 |
| D3 | CLI 管理页只读展示参数列表（「所需参数」列复用工具页那套：数字徽标 + 悬停小表格列出说明 / 必填 / 敏感 / 包内默认值） | 填值的人需要一个地方读懂该填什么；值不属于那一页 |
| D4 | `required: true` 缺值时前后端都拦住保存，并点名是哪个 CLI 的哪个参数 | 只挡前端等于把报错留给后端；只报「必填未填」在一页加了多个 CLI 时无从下手 |
| D5 | 包的 `defaultValue` 算「已填」 | 下发时它确实会顶上去（D6），拦管理员反而逼他抄一遍包里的值 |
| D6 | 下发按 key 合并：智能体值优先，其余用包声明的 `defaultValue` 兜底；两边都空则该 key 不下发 | 缺变量与 `TOKEN=` 对 CLI 是两种状态 |
| D7 | 向导里 MCP 与 CLI 都不把声明默认值抄成绑定值；只有内置工具抄 | 工具声明行的 `default_value` 不下发（运行时只有绑定值），抄进绑定才算填了；MCP / CLI 的默认值会下发，抄进绑定等于把它冻在建 agent 那一刻的副本上 |
| D8 | 三张环境参数表抽成一个共享组件，用一个开关表达 D7 的差异 | 三份逐字节相似的代码，改一处漏两处 |
| D9 | 值有两条来源：引用全局环境变量（只存指针）或手填字面值 | 引用侧 AES 加密入库且随变量改动生效；手填值明文进绑定快照 |
| D10 | 团队自带的主管配置不参与本轮 | 主管只配技能，CLI 绑定仍在成员智能体上 |

## 2. 契约与数据

### 2.1 包侧声明

`cli-packages/<name>/plugin.yaml`：

```yaml
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

解析期约束（`CliPackageParser`）：`envParamName` 包内不重复；`secret: true` 与 `defaultValue` 互斥（一个包就是一个明文 zip）。登记时整份声明经 `SecretFieldEncryptor.serializeToolEnvParams` 写进 `cli.env_params`（`harnax-admin/.../registrar/CliPackageAutoRegistrar.kt:217`）。

### 2.2 请求与响应

| 位置 | 字段 |
|---|---|
| `AgentCreateRequest.CliConfig`（`AgentUpdateRequest.cliList` 复用它，`dto/AgentUpdateRequest.kt:37`） | `id: Long?`、`envBindings: List<EnvBinding>?`（`harnax-admin/.../dto/AgentCreateRequest.kt:63`） |
| `AgentResponse.CliItem` | `envBindings`，由 `parseEnvBindingsJson` 解出，供编辑表单回显（`AgentServiceImpl.kt:317`） |
| `CliResponse` 列表 / 详情 | `envParams: List<ToolEnvParamEntry>`，敏感项的默认值掩码后给出（`dto/CliResponse.kt:86`） |
| 内部下发 `internal/agent-spec` | `cliDetails[].envBindings`，已合并完的 `envKey/envValue` 列表 |

`EnvBinding` 沿用工具与 MCP 的同一个 DTO：`envKey` + `envVarId`（引用）或 `customValue`（手填），二者互斥。

### 2.3 存储

`agent_cli_binding.env_bindings`（JSON 快照）：引用行只存 `envVarId` / `envVarName`，不存值；手填行存 `customValue`。写入口 `AgentServiceImpl.serializeEnvBindings`（`:544`）——引用不落值是刻意的：客户端带回来的 `envValue` 只是展示值（敏感项是 `******`），存下来会让「变量已被删除」时工具拿到一串星号当兜底值。

## 3. admin 侧

### 3.1 必填校验

`saveCliBindings` 对每个已选 CLI 依次跑工具与 MCP 用的一对守卫（`AgentServiceImpl.kt:497`）：

1. `assertEnvVarRefsBindable(config.envBindings, "CLI '<name>'")`：引用的全局变量必须存在、未禁用、租户可绑定。这一列同样会解进沙箱 env，未校验的引用能解析出别的租户的密钥。
2. `assertRequiredEnvParamsFilled("CLI '<name>'", secretFieldEncryptor.deserializeToolEnvEntries(cli.envParams), config.envBindings, defaultValueCounts = true)`。

判定落在 `ToolEnvParamEntry.hasRuntimeValue`（`:622`），「算已填」只有三种：

- 有 `envVarId`（不看值，下发时现取）；
- 有非空字面值，且文本里不含 `****`（掩码是展示工件，不是值）；
- 包声明带了非空 `defaultValue`（`defaultValueCounts = true`）。

缺值即 `BizException`，消息形如 `CLI 'lark-cli' requires env params that are left without a value: LARKSUITE_CLI_APP_SECRET`——CLI 名与参数名都必须在，一页可能勾多个 CLI。

### 3.2 下发合并

`InternalApiController.mergeCliEnvBindings(binding.envBindings, cli.envParams)`（`:896`）：

- 先 `resolveEnvBindingsJson` 把引用现解成值；
- 再取包声明的默认值（`decryptToolEnvParamsToMap`）补上没人覆盖的 key；
- 值为空的声明整个丢弃，不发空串。

`runtimeEnv` 与这些值一起在容器创建时注入，绑定值优先级更高（`HarnessAgentLauncher.cliEnvironment`，`harnax-agent/harnax-harness-core/.../HarnessAgentLauncher.kt:681`：先 `runtimeEnv` 后 `putAll(envBindings)`）。

## 4. webui

### 4.1 共享组件 `EnvParamTable`

`harnax-webui/src/pages/agent/components/EnvParamTable.tsx`，三个消费方（tools / MCP / CLI）。导出 `EnvVarOption`、`EnvBindingRow = { envKey, envValue, envVarId?, customInput? }`。

- 整块可折叠，标题是「环境参数 (N)」；参数名列带 `description` 悬停，红色「必填」、橙色「敏感」标签；
- 值列二选一：下拉引用全局环境变量（只存 `envVarId`，清掉 `envValue`，引用敏感项时值列显示锁形图标 + 掩码）或「自定义输入」直接填（`secret` 项用密码框）；
- `showDefaultHint` 开关决定表格里是否提示「包内默认值：X」。开关表达的就是 D7：工具的默认值到不了运行时，提示它等于误导；MCP 与 CLI 的默认值会下发，提示它才是诚实的「留空会怎样」。

并入一张表的可见副作用：MCP 的参数块随组件带上 8px 上边距和左侧竖线，与工具、CLI 三处一致。

### 4.2 向导 CLI 步骤

`CliConfigPanel.tsx` 与工具步骤同构：一卡一个 CLI，右上角「添加 CLI」追加一张卡（候选用完后按钮禁用），卡上是一个下拉（换成别的包，选项里带版本号与描述，且挡掉别的卡已选过的，因为后端按 `cliId` 去重、第二张卡填的值会静默丢掉）加自带技能标签和「删除」，卡内就是它的参数表。三个导出被表单与校验共用：

- `cliEnvRows(cli, stored)`：**行由声明派生**，不是由已存绑定派生。包升级新增的参数会自动出现在表单上，包里删掉的参数不再渲染。
- `cliConfigRows(selectedCliIds, clis, bindings)`：把 `Record<cliId, EnvBindingRow[]>` 和当前选中集合成一份渲染 / 提交 / 校验共用的行数据。
- `cliEnvPayload(rows)`：只把「算已填」的行转成 `API.EnvBinding[]`（复用 `envBinding.isEnvBindingFilled`），保证留空不会变成 `customValue: ""` 存进快照。

绑定状态按 `cliId` 键存放（`CliEnvBindings`），删掉一张卡再加回来，已填的值还在；提交按选中集过滤，不会下发陈旧绑定。

### 4.3 校验与提交

`configValidation.ts` 的 `findConfigIssue` 覆盖工具 / MCP / 技能 / CLI 四类，新增 `cli_env_missing` 与 `CLI_STEP = 4`；缺值时用 `pages.agent.cli.envRequired` 点名「{cli} 缺少必填环境参数 {param}」，与 D4 的后端消息同构。CLI 侧走 `findMissingRequiredEnvParam(entries, bindings, defaultCountsAsFilled = true)`——与 MCP 同一个参数，工具传 `false`。

提交体：`cliList: cliRows.map(c => ({ id: c.cliId, envBindings: cliEnvPayload(c.envBindings) }))`。

### 4.4 编辑回显

`UpdateForm.tsx` 从 `values.cliList[].envBindings` 建初始状态：有 `envVarId` 的行只带回引用（`envValue` 置空，`customInput` 为假），手填行带回 `customValue`。把后端回的展示值再存一遍，等价于把 `******` 写成值。

### 4.5 CLI 页只读列

`harnax-webui/src/pages/cli/index.tsx` 在「自带技能」后新增「所需参数」列，列本身与工具页同形：一个 `EnvironmentOutlined` 紫色数字徽标（数字为声明条数），悬停弹出小表格，列为参数名 / 说明 / 必填 / 敏感 / 默认值，必填是红 `Y`、敏感是橙 `Y`，无声明时是 `-`。这套展示抽成 `harnax-webui/src/components/EnvParamsPopover`，工具页的环境参数列一起改用它，两页从此不会各自漂移；工具页原来只有参数名 / 说明 / 必填 / 敏感四列，共用后多出「默认值」一列（`ToolEnvParamEntry` 本来带 `defaultValue`，此前无处可见）。

### 4.6 文案

`pages.agent.cli.envRequired`、`pages.agent.envParam.defaultHint`、`pages.cli.envParams`、`pages.tool.envParamDefault` 四键在中英文 locale 各一份；所有新增 UI 文案走 `intl.formatMessage`。

## 5. 一张表看清语义

| 管理员在参数表上的动作 | 存进 `agent_cli_binding.env_bindings` | 下发给容器的 env |
|---|---|---|
| 引用全局变量 | 只有 `envVarId` + `envVarName` | 该变量的当前值（每次解析现取） |
| 手填字面值 | `customValue` 明文 | 该字面值 |
| 留空，包带 `defaultValue` | 无该 key | 包的默认值 |
| 留空，包无默认值且 `required: false` | 无该 key | 该变量不存在 |
| 留空，包无默认值且 `required: true` | 存不进去：前后端各挡一次 | — |

生效时机：env 只在容器创建那一刻注入。改了参数或换了包版本，需要新会话；保活中的容器不会被换。

## 6. 测试与验收

- `AgentServiceImplTest.EnvBindingGuardTests` 新增 CLI 四例：必填缺值被拒且消息点名 CLI 与参数、`envVarId` 引用算已填、包 `defaultValue` 算已填、`abc****wxyz` 这种掩码字面值不算已填。
- `InternalApiControllerTest.CliEnvDeliveryTests` 三例：agent 未采集任何值时下发声明的默认值、显式值逐 key 优先且未覆盖的声明照常补上、声明有名字但没有任何值时不下发空值。
- `HarnessAgentLauncherCliEnvTest` 覆盖 `runtimeEnv` 与绑定值的优先级和未发布槽位的降级。
- 后端全量：1957 tests，BUILD SUCCESS。
- 前端：`npx max build` EXIT=0；`biome lint` 新增零错误（`noArrayIndexKey` 是本轮之前就红的既有门，本轮把三张表并一张后计数由 5 降到 4）。
- 产物级断言：`dist` 中按 i18n key 名检索新增文案键（中文值在构建产物里不以明文出现，不能按 copy 断言）。
- 运行期端到端（docker-new，admin 与 frontend 重新部署后，用 lark-cli 的两个必填声明）：手填 + 引用两种给法同时保存成功，detail 回显两行且引用行只剩指针；三条负例（缺必填、只有 `****` 掩码文本、空值）各自被拒，消息同时含 `lark-cli` 与参数名；连续被拒后回读 detail 绑定不变（保存是事务的，先删后插不会留下半写状态）；`internal/agent-spec` 的 `cliDetails[].envBindings` 两个 key 齐且引用已解析成值；沙箱容器（`harnax-sandbox:cli-*`）内两个 key 都在 env 中，`lark-cli` 1.0.96 就位。部署产物侧 `pages.cli.envParams` 落在 `p__cli__index` chunk、`envParamsCount` 落在 `p__agent__index` chunk。

## 7. 边界与已知限制（记账，本规格内不解决）

1. 手填的 `customValue` 在 tools / MCP / CLI 三处都明文入库，且被智能体读接口原样回显。真密钥走引用；引用侧的值不落绑定快照。
2. 校验发生在保存时，不发生在「包升级」时：包里把某个参数新标成 `required: true`，已存在的智能体不会被打回，直到有人再保存它。下发侧该 key 没有值就是没有值，CLI 自己在运行时报错。
3. 环境参数表统一只覆盖智能体向导；系统其他界面若有各自的参数编辑入口，不在本轮收敛范围内。

## 8. 不在范围内

- 团队自带主管配置的 CLI 绑定（主管只配技能）。
- CLI 包参数的小组 / 租户级默认值池（全局环境变量已覆盖该需求）。
- 参数值的运行时热更新（保活容器不换）。

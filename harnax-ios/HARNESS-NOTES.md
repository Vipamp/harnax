# iOS 包内施工说明

给在 `harnax-ios/` 里写代码的人（含自动化施工体）的硬约束与既有件清单。这里的每一条都有测试或闸门守着，
违反不会在 review 时才发现，而是在 `swift test` 里直接红。

## 工程结构与命令

- 单个 `Package.swift`，四个模块依赖顺序固定：`HarnaxCore → HarnaxAPI → HarnaxKit → HarnaxFeatures`，
  四个 test target 同名对应。全部 `.swiftLanguageMode(.v5)`。
- `App/` 不是 SwiftPM target：`swift build` 不编译它，它只被 `Harnax.xcodeproj` 使用。
  单独验它能过类型检查：
  `xcrun -sdk macosx swiftc -typecheck -I .build/arm64-apple-macosx/debug/Modules App/HarnaxDebugScreens.swift`
- 日常两条命令：`swift build --build-tests` 与 `swift test`。没有外部 SPM 依赖，离线可跑。

## 测试宿主是 macOS

闸门与视图模型测试跑在 macOS 上，iOS-only 的 SwiftUI API 会直接编译失败。已踩过的两处：

- `ToolbarItem(placement: .topBarTrailing)` → 用 `.primaryAction`。
- `.navigationBarTitleDisplayMode(.inline)` → 包进 `#if canImport(UIKit) … #endif`。
- `.confirmationAction` 是可用的，不用包。

## 闸门（Tests/ 里有四条，全部是源码级判据）

- `Tests/HarnaxKitTests/ThemeGateTests.swift`：颜色只能从 `HarnaxKit` 语义令牌取——`Theme/` 之外不写字面色值、
  不写裸系统色名，`#RRGGBB` 只允许出现在 `Theme/Palette.swift`，`.colorScheme` 只在 `Theme/` 内读；
  另外 `Text/Label/Button/Toggle/Picker/SecureField/TextField/NavigationLink` 后面紧跟引号即违规（
  固定文本用 `Text(verbatim:`）。
- `Tests/HarnaxKitTests/LocalizationKeyTests.swift`：只扫 `Sources/`（不含 `App/`）。两份 `.strings` 的键集必须
  set 相等且 `sorted()` 相等、值非空；被判成键的是 `hx("…")`/`HXText("…")` 里的字面量，以及任意「带点且
  首段命中命名空间」的小写字面量。当前命名空间：
  `common, state, tab, login, server, me, context, env, agent, team, task, chat, model, tool, skill, mcp, cli,
  channel, cron, token, monitor, log, error, auth, session`。
  ⇒ SF Symbol 不能撞这些前缀（`server.rack` 会被当键）。安全示例：`sparkles`、`network`、`terminal`、
  `books.vertical`、`wrench.and.screwdriver`、`textformat`、`checkmark.circle.fill`、`link.badge.plus`、`ellipsis`。

新增命名空间要同步改 `LocalizationKeyTests.namespaces`，否则该域的键不会被闸门看见。

## 文案落点

- 两份目录：`Sources/HarnaxKit/Resources/en.lproj/Localizable.strings` 与
  `Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings`，按域分块，键在两份里同序追加。
- `HarnaxCatalog.render` 用 `String(format:)`：键里的占位符数量必须与调用点实参逐条对上，
  多传会被忽略、少传会出错。带 `%d` 的键不要拿它去喂字符串。
- 跨域同词（启停/删除/刷新/徽标）走 `state.*`；域特有语义（搜索提示、空态、删除前置文案）留在域前缀里。

## 契约层约定

- 信封 `Envelope{code,message,data,timestamp}`，只有 `code == 200` 算成功；`ResultVo<Void>` 用 `EmptyResponse`。
- 分页 `Page{pageNum,pageSize,total,records}` + `PagedState`（`hasMore`、`removeRow(id:)` 会把 `total` 减一）。
- 后端 `default-property-inclusion: non_null` ⇒ 字段可能整个缺席。DTO 的可选性按后端 DTO 声明逐字段核：
  `AgentResponse.kt` 全字段可空，`TeamResponse.kt` 的 `systemPrompt/modelId/skillList/memberList` 有非空默认值，
  因此这四个在 `TeamSummary` 里是非可选。测试夹具要按这个形状造行。
- 日期是 `2026-09-12 10:20:30` 这种带空格的形态，也可能是 ISO 的 `T` 分隔；解析不了就不显示（`hxMonthDay`）。
- `hxPresented(_:)`：`null`、空串、纯空格三种都算「这行没有名字」，展示回落放 presenter，不放视图。
- 0/1 标志列用 `Flags`（`Sources/HarnaxCore/Contract/Flags.swift`）。

## 既有可复用件

- 令牌：`Color.hx(.background/.surface/.surfaceAlt/.separator/.textPrimary/.textSecondary/.textTertiary/
  .brand/.onBrand/.success/.warning/.danger/.indigo/.purple/.teal)`；屏级容器 `.harnaxScreen()`、
  根级 `.harnaxThemed()`；按钮 `.hxPrimary/.hxSecondary/.hxInline`；留白 `HXLayout.tabBarClearance`。
- 组件：`HXCard`/`HXGroupCard`、`HXRow(_ titleKey:subtitle:systemImage:tone:divider:trailing:)` 与
  `HXRow(text:…)`（verbatim 版）、`HXBadge(_ titleKey:, tone:)`（收键名）、`HXChip(_ label:, tone:)`（收已解析文本）、
  `HXStatusDot(tone:)`、`HXBanner(_ titleKey:, message:, systemImage:, tone:)`、
  `HXStateView(_ kind: .loading/.empty/.error, message:, retry:)`、`HXSectionHeader(_ titleKey)`、
  `HXSegmented([HXSegmentOption], selection: Binding<Int>)`、`HXFlow`、`HXField(_ placeholderKey:, text:, systemImage:,
  secure:, kind:)`、`HXValue`、`HXAvatar`。
- 下钻：`HXBindingRow`/`HXBindingSection`/`HXBindingListView`/`HXBindingSheet`（`Support/HXBindingListView.swift`）。
- 环境参数：`Support/HXEnvParams.swift`；刷新面板：`Support/HXSessionRefreshSheet.swift`
  （`SessionRefreshTarget` + `SessionRefreshModel`，三个域共用，键在 `session.*`）。
- 状态过滤：`Support/StatusFilter.swift`；行副信息：`Support/RowMeta.swift`（`RowMeta.byline`、`hxMonthDay`）。
- 参考实现（形状照抄即可）：`Sources/HarnaxFeatures/Agents/AgentListViewModel.swift`、
  `Teams/TeamListViewModel.swift`、`Agents/AgentBindingsSheet.swift`（命名回落的 presenter 写法）、
  `Agents/AgentListView.swift`（列表卡片 + 菜单 + 确认框 + sheet 装配）。

## 边界

- 共享文件不在域施工里改：`Sources/HarnaxCore/Contract/Facades.swift`、
  `Sources/HarnaxFeatures/Context/ContextDomain.swift`、`Sources/HarnaxFeatures/Support/HarnaxDependencies.swift`、
  `Tests/HarnaxFeaturesTests/Fakes.swift`、`Package.swift`。新的门面协议开自己的文件（例如
  `Contract/ModelCataloging.swift`），测试替身开自己的文件（例如 `Tests/HarnaxFeaturesTests/ModelFakes.swift`，
  可以直接用 `Fakes.swift` 里的 `PageStub`/`PageStub.page(_:records:total:)`/`PageStub.list`）。
- 不做修改密码与个人资料；接口只走 `/api/admin/**` 与 `/api/router/**`，不碰计划下线的 `/api/admin/mp/**`。
- 每一屏都要在深浅两档下都成立，颜色一律走令牌；中英文键集逐字相同。
- 施工完不要 `git add`/`git commit`，交回工作树由主控方合并与提交。

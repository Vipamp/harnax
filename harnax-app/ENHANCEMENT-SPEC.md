# 移动端三大核心模块增强

## Task 1: AgentCard 增强 — 展示更多信息

**现状**: AgentCard 只显示 name, description, modelName, sessionCount

**改动**:
- `src/components/agents/AgentCard.vue` — 添加 MCP 数量、Skill 数量的标签，添加模型价格标签（对标 webui 的 EntityCard Tag 体系）
- `src/types/api.ts` — MpAgentResponse 添加 `modelPrice?: number` 字段（需后端配合，如果后端已有该字段则直接用）
- `src/pages/agents/detail.vue` — 详情页增加 modelPrice 展示、MCP/Skill 数量统计

## Task 2: 智能体列表空状态与交互优化

**现状**: 空状态仅显示文字，无视觉引导

**改动**:
- `src/pages/agents/index.vue` — 空状态添加图标和引导文案，下拉刷新支持
- `src/pages/agents/detail.vue` — "开始对话"按钮交互优化：如果该 Agent 已有历史 Session，提供"继续上次对话"或"新建对话"选择

## Task 3: SessionList 增强 — 重命名 + 消息预览 + 空状态

**现状**: SessionItem 只显示 name, agentName, time, delete 按钮

**改动**:
- `src/components/session/SessionItem.vue` — 添加长按/滑动触发重命名操作，添加 lastMessage 预览行
- `src/components/session/SessionList.vue` — 空状态添加图标和引导文案（"选择一个智能体开始对话"），"+"按钮改为弹出智能体选择器而非直接跳转页面
- `src/types/api.ts` — 确认 MpSessionResponse 的 lastMessage 字段已在使用
- `src/locales/zh-CN.ts` + `en-US.ts` — 添加重命名相关的国际化文案

## Task 4: Session 详情弹窗

**现状**: 没有 Session 详情查看功能

**改动**:
- 新增 `src/components/session/SessionDetailModal.vue` — 展示 Session 的 Agent 信息、模型、MCP 工具列表、Skill 列表、创建时间等（对标 webui 的 DetailModal）
- `src/components/layout/AppLayout.vue` — 头部添加"详情"按钮入口

## Task 5: 聊天功能开关 — 深度思考 / 联网搜索 / 规划模式

**现状**: 聊天输入区没有任何功能开关，webui 有 3 个开关按钮

**改动**:
- `src/components/chat/InputArea.vue` — 底部工具栏添加 3 个开关按钮（灯泡=深度思考, 搜索=联网搜索, 列表=规划模式），使用 toggle 样式
- `src/store/useChatStore.ts` — 添加 `enableThink`, `enableSearch`, `enablePlan` 响应式状态
- `src/api/router.ts` — `streamChat` 请求体增加 `enableThink`, `enableSearch`, `enablePlan` 字段
- `src/types/chat.ts` — ChatRequest 扩展对应字段（或复用已有类型）
- `src/locales/zh-CN.ts` + `en-US.ts` — 添加开关文案

## Task 6: 会话配置持久化 — 加载/保存每个 Session 的开关配置

**现状**: 没有任何会话级别的配置

**改动**:
- `src/api/admin.ts` — 新增 `mpGetSessionConfig(sessionId)` 和 `mpUpdateSessionConfig(sessionId, config)` API（需后端配合，如果后端 `/api/admin/sessions/{id}/config` 已有则直接对接）
- `src/store/useChatStore.ts` — `switchSession` 时自动加载会话配置，开关变化时自动保存
- `src/components/chat/InputArea.vue` — 开关切换时触发保存

## Task 7: 图片上传与展示

**现状**: ChatRequest 类型有 imageUrls 字段，但 UI 无上传入口，消息气泡无图片渲染

**改动**:
- `src/components/chat/InputArea.vue` — 添加图片上传按钮（图标按钮），调用 `uni.chooseImage` 选图，输入框上方显示已选图片预览（可删除）
- `src/components/chat/MessageBubble.vue` — 用户消息气泡中渲染 imageUrls 图片列表
- `src/store/useChatStore.ts` — `sendMessage` 传递 imageUrls 参数
- `src/api/sse.ts` — SSE body 中添加 imageUrl 字段传递

## Task 8: 空状态快捷建议

**现状**: ChatView 空状态只有 "开始新的对话" 文字

**改动**:
- `src/components/chat/ChatView.vue` — 空状态改为引导卡片样式，显示 3-4 个快捷建议问题（对标 webui 的 SUGGESTIONS），点击直接发送
- `src/locales/zh-CN.ts` + `en-US.ts` — 添加建议问题文案

## Task 9: 思考过程显隐切换

**现状**: ThinkingBlock 默认展开，无法全局控制显隐

**改动**:
- `src/components/chat/ChatView.vue` — 头部工具栏添加"显示/隐藏思考过程"按钮
- `src/store/useChatStore.ts` — 添加 `showThinking` 状态，持久化到 localStorage
- `src/components/chat/MessageBubble.vue` — 根据 showThinking 状态控制 ThinkingBlock 的渲染

## Task 10: 清空聊天记录增强

**现状**: AppLayout 头部有清空按钮，但使用简单的 uni.showModal

**改动**:
- `src/components/chat/InputArea.vue` — 输入区工具栏添加清空按钮（对标 webui），使用二次确认
- 保持 AppLayout 中的清空入口不变

## Task 11: 中断对话命令

**现状**: stopStreaming 只在前端 abort，没有发送 INTERRUPT 命令给后端

**改动**:
- `src/store/useChatStore.ts` — `stopStreaming` 中增加调用 `sendCommand({ sessionId, command: 'INTERRUPT' })` 的逻辑
- `src/components/chat/InputArea.vue` — 停止按钮点击时调用 `interruptSession` 而非 `stopStreaming`

## 实施优先级

| 优先级 | Task | 说明 |
|-------|------|------|
| P0 | Task 5 | 聊天开关 — 核心功能，直接影响 AI 行为 |
| P0 | Task 7 | 图片上传 — 视觉对话基础能力 |
| P0 | Task 11 | 中断命令 — 修复停止功能不完整的问题 |
| P1 | Task 8 | 快捷建议 — 提升首次体验 |
| P1 | Task 3 | Session 增强 — 重命名+消息预览 |
| P1 | Task 1 | AgentCard 增强 — 更丰富的信息展示 |
| P2 | Task 6 | 会话配置持久化 — 需后端配合 |
| P2 | Task 9 | 思考显隐 — 界面信息密度控制 |
| P2 | Task 10 | 清空增强 — 交互细节 |
| P3 | Task 2 | Agent 空状态优化 — 锦上添花 |
| P3 | Task 4 | Session 详情弹窗 — 信息展示 |

## 技术依赖说明

- Task 5/6 的 `enableThink/enableSearch/enablePlan` 需要 Router 服务端支持在 chat stream 请求中接收这些参数
- Task 6 的 Session Config API 需要 Admin 后端提供 `/api/admin/mp/sessions/{id}/config` 端点（webui 用的 `/api/admin/sessions/{id}/config` 可能需要适配为 mp 路径）
- Task 7 的图片上传使用 `uni.chooseImage`，H5 和 App 平台都支持；图片以 URL 形式发送（需确认后端是否支持 base64 或需要上传接口）

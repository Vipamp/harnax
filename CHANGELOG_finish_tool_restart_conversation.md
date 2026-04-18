# 修复：finish_subtask 和 finish_plan 工具调用结果后重启对话

## 问题描述
前端收到工具 `finish_subtask` 或 `finish_plan` 调用结果时，对话内容应该重启开启一个新的对话块，而不是追加在原来的正文后。

## 解决方案

### 修改文件
- `/Users/heqingsong/code/my_project/vipclaw/vipclaw-webui/src/pages/session/components/ChatWindow.tsx`

### 核心改动

#### 1. 引入可变的 assistant message ID
在 `doSend` 函数中，将原本固定的 `assistantMessageId` 改为可变的 `currentAssistantMessageId`：

```typescript
let currentAssistantMessageId = assistantMessageId; // 可变的 assistant message ID
```

#### 2. 更新 flushUI 函数
让 `flushUI` 使用可变的 `currentAssistantMessageId`：

```typescript
const flushUI = () => {
  const idx = currentMsgs.findIndex((m) => m.id === currentAssistantMessageId);
  if (idx >= 0) {
    currentMsgs = [...currentMsgs];
    currentMsgs[idx] = { ...currentMsgs[idx], segments: [...currentSegs] };
    setMessages(currentMsgs);
  }
};
```

#### 3. 在 ToolResultEvent 处理中添加检测逻辑
在三个地方添加了相同的检测逻辑：

**位置1：主 SSE 流中的 ToolResultEvent（约第 863 行）**
```typescript
// 检查是否是 finish_subtask 或 finish_plan 工具
if (toolName === 'finish_subtask' || toolName === 'finish_plan') {
  console.log('[ToolResultEvent] Detected finish tool, creating new assistant message');
  
  // 完成当前消息
  flushUI();
  
  // 创建新的 assistant 消息
  const newAssistantMessageId = `assistant-${Date.now()}-new`;
  const newAssistantMessage: ChatMessage = {
    id: newAssistantMessageId,
    role: 'assistant',
    segments: [],
    timestamp: Date.now(),
  };
  
  // 更新 currentMsgs
  currentMsgs = [...currentMsgs, newAssistantMessage];
  setMessages(currentMsgs);
  
  // 更新当前 assistant message ID
  currentAssistantMessageId = newAssistantMessageId;
  
  // 重置所有状态以开始新消息
  currentSegs = [];
  accText = '';
  accThinking = '';
  activeTextIdx = -1;
  activeThinkIdx = -1;
  currentEventType = null;
  toolCallMap = new Map<string, number>();
}
```

**位置2：Confirm 流中的 ToolResultEvent（约第 1099 行）**
相同的逻辑应用于用户确认后的工具调用结果处理。

**位置3：嵌套 Confirm 流中的 ToolResultEvent（约第 1324 行）**
相同的逻辑应用于嵌套确认的工具调用结果处理。

### 工作流程

1. 当收到 `ToolResultEvent` 时，首先正常更新工具调用 segment 的结果
2. 检查工具名称是否为 `finish_subtask` 或 `finish_plan`
3. 如果是：
   - 调用 `flushUI()` 完成当前消息的渲染
   - 创建一个新的 assistant 消息对象
   - 将新消息添加到消息列表
   - 更新 `currentAssistantMessageId` 指向新消息
   - 重置所有累积状态（文本、思考、工具映射等）
4. 后续的文本、思考、工具调用等事件会自动追加到新的 assistant 消息中

### 测试建议

1. 触发 `finish_subtask` 工具调用，观察是否在结果后创建新的对话块
2. 触发 `finish_plan` 工具调用，观察是否在结果后创建新的对话块
3. 验证新对话块可以正常接收后续的文本和工具调用
4. 验证原有的消息历史不受影响

## 影响范围
- 仅影响前端消息展示逻辑
- 不影响后端 SSE 事件流
- 不影响消息存储和加载
- 对所有工具调用流程保持兼容

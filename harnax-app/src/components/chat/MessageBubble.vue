<template>
  <view :class="['message-bubble', `message-${message.role}`]">
    <view class="bubble-avatar">
      <text class="avatar-text">{{ message.role === 'user' ? '👤' : '🤖' }}</text>
    </view>
    <view class="bubble-body">
      <template v-for="(seg, idx) in message.segments" :key="idx">
        <ThinkingBlock v-if="seg.type === 'thinking'" :content="seg.content" />
        <ToolCallCard
          v-else-if="seg.type === 'tool_call'"
          :toolName="seg.toolName || ''"
          :args="seg.content"
          :result="seg.toolResult"
          :success="seg.success"
        />
        <ToolConfirmCard
          v-else-if="seg.type === 'tool_confirm' && seg.pendingCallTools"
          :pendingCallTools="seg.pendingCallTools"
          :status="seg.confirmStatus || 'pending'"
          @confirm="handleConfirm"
        />
        <MarkdownRenderer v-else-if="seg.type === 'text'" :content="seg.content" />
      </template>
      <TokenUsage v-if="message.role === 'assistant' && message.tokenUsage" :usage="message.tokenUsage" />
    </view>
  </view>
</template>

<script setup lang="ts">
import type { ChatMessage } from '@/types/chat'
import ThinkingBlock from './ThinkingBlock.vue'
import ToolCallCard from './ToolCallCard.vue'
import ToolConfirmCard from './ToolConfirmCard.vue'
import MarkdownRenderer from '@/components/common/MarkdownRenderer.vue'
import TokenUsage from './TokenUsage.vue'

defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  confirm: [confirmed: boolean]
}>()

function handleConfirm(confirmed: boolean) {
  emit('confirm', confirmed)
}
</script>

<style lang="scss" scoped>
.message-bubble {
  display: flex;
  padding: 12px 16px;
  gap: 10px;

  &.message-user {
    flex-direction: row-reverse;

    .bubble-body {
      align-items: flex-end;
    }
  }
}

.bubble-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--chat-bg-elevated, rgba(0, 0, 0, 0.05));
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.avatar-text {
  font-size: 16px;
}

.bubble-body {
  flex: 1;
  max-width: 85%;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.message-user .bubble-body {
  background: var(--chat-bubble-user, #4f6ef7);
  border-radius: var(--chat-radius-lg, 12px) var(--chat-radius-lg, 12px) 4px var(--chat-radius-lg, 12px);
  padding: 10px 14px;

  :deep(.markdown-body) {
    color: #fff;
  }
}

.message-assistant .bubble-body {
  background: var(--chat-bubble-assistant, transparent);
  padding: 0 4px;
}
</style>

<template>
  <view class="chat-view">
    <scroll-view
      class="chat-messages"
      scroll-y
      :scroll-into-view="scrollAnchor"
      :scroll-with-animation="true"
    >
      <view v-if="messages.length === 0" class="chat-empty">
        <text class="empty-icon">💬</text>
        <view class="empty-text">{{ t('chat.emptyHint') }}</view>
      </view>
      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        @confirm="handleConfirm"
      />
      <view v-if="isStreaming && !hasAssistantMessage" class="typing-indicator">
        <text class="typing-dot">●</text>
        <text class="typing-dot typing-delay-1">●</text>
        <text class="typing-dot typing-delay-2">●</text>
      </view>
      <view :id="`scroll-bottom-${scrollCounter}`" style="height: 1px" />
    </scroll-view>
    <InputArea
      :isStreaming="isStreaming"
      @send="handleSend"
      @stop="handleStop"
    />
  </view>
</template>

<script setup lang="ts">
import { computed, watch, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useChatStore } from '@/store/useChatStore'
import MessageBubble from './MessageBubble.vue'
import InputArea from './InputArea.vue'
import { storeToRefs } from 'pinia'

const { t } = useI18n()
const chatStore = useChatStore()
const { messages, isStreaming, scrollToBottom } = storeToRefs(chatStore)

const scrollAnchor = ref('')
let scrollCounter = 0

const hasAssistantMessage = computed(() =>
  messages.value.some((m) => m.role === 'assistant'),
)

function handleSend(text: string) {
  chatStore.sendMessage(text)
}

function handleStop() {
  chatStore.stopStreaming()
}

function handleConfirm(confirmed: boolean) {
  chatStore.confirmTools(confirmed)
}

watch(scrollToBottom, (val) => {
  if (val) {
    scrollCounter++
    scrollAnchor.value = `scroll-bottom-${scrollCounter}`
    chatStore.scrollToBottom = false
  }
})
</script>

<style lang="scss" scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--chat-bg-container);
}

.chat-messages {
  flex: 1;
  overflow: hidden;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  gap: 12px;
}

.empty-icon {
  font-size: 40px;
  opacity: 0.5;
}

.empty-text {
  font-size: 14px;
  color: var(--chat-text-secondary);
}

.typing-indicator {
  display: flex;
  padding: 12px 16px;
  gap: 4px;
}

.typing-dot {
  font-size: 10px;
  color: var(--chat-text-tertiary);
  animation: typing-pulse 1.4s infinite;
}

.typing-delay-1 {
  animation-delay: 0.2s;
}

.typing-delay-2 {
  animation-delay: 0.4s;
}

@keyframes typing-pulse {
  0%, 60%, 100% {
    opacity: 0.3;
  }
  30% {
    opacity: 1;
  }
}
</style>

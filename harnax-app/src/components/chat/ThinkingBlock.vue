<template>
  <view class="thinking-block" @tap="toggle">
    <view class="thinking-header">
      <text class="thinking-icon">💡</text>
      <text class="thinking-label">{{ t('chat.thinkingProcess') }}</text>
      <text class="thinking-arrow">{{ expanded ? '▾' : '▸' }}</text>
    </view>
    <view v-if="expanded" class="thinking-content">
      <text>{{ content }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

defineProps<{
  content: string
}>()

const { t } = useI18n()
const expanded = ref(true)

function toggle() {
  expanded.value = !expanded.value
}
</script>

<style lang="scss" scoped>
.thinking-block {
  margin: 6px 0;
  border-radius: var(--chat-radius-sm, 6px);
  background: var(--chat-thinking-bg, rgba(79, 110, 247, 0.06));
  border: 1px solid var(--chat-thinking-border, rgba(79, 110, 247, 0.15));
  overflow: hidden;
}

.thinking-header {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  gap: 6px;
}

.thinking-icon {
  font-size: 13px;
}

.thinking-label {
  flex: 1;
  font-size: 12px;
  color: var(--chat-thinking-text, rgba(79, 110, 247, 0.8));
  font-weight: 500;
}

.thinking-arrow {
  font-size: 11px;
  color: var(--chat-thinking-text, rgba(79, 110, 247, 0.8));
}

.thinking-content {
  padding: 0 12px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--chat-text-secondary, #666);
  white-space: pre-wrap;
}
</style>

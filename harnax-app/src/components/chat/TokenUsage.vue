<template>
  <view v-if="usage" class="token-usage">
    <text class="token-item">📝 {{ usage.inputTokens }}</text>
    <text class="token-item">💬 {{ usage.outputTokens }}</text>
    <text class="token-item">Σ {{ usage.totalTokens }}</text>
    <text v-if="usage.costTime" class="token-item">⏱ {{ formatTime(usage.costTime) }}</text>
  </view>
</template>

<script setup lang="ts">
import type { TokenUsage } from '@/types/chat'

defineProps<{
  usage: TokenUsage | null
}>()

function formatTime(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
</script>

<style lang="scss" scoped>
.token-usage {
  display: flex;
  gap: 10px;
  padding: 4px 0;
  flex-wrap: wrap;
}

.token-item {
  font-size: 11px;
  color: var(--chat-text-tertiary, #999);
}
</style>

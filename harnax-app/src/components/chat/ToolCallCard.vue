<template>
  <view class="tool-card">
    <view class="tool-header" @tap="toggle">
      <text class="tool-icon" :style="{ color: statusColor }">🔧</text>
      <text class="tool-name">{{ toolName }}</text>
      <text class="tool-status" :style="{ color: statusColor }">{{ statusText }}</text>
      <text class="tool-arrow">{{ expanded ? '▴' : '▾' }}</text>
    </view>
    <view v-if="expanded" class="tool-body">
      <view v-if="args" class="tool-section">
        <view class="tool-section-label">{{ t('chat.toolArguments') }}</view>
        <scroll-view scroll-x class="tool-code-scroll">
          <text class="tool-code">{{ args }}</text>
        </scroll-view>
      </view>
      <view v-if="result" class="tool-section">
        <view class="tool-section-label">{{ t('chat.toolResult') }}</view>
        <scroll-view scroll-x class="tool-code-scroll">
          <text class="tool-code" :class="{ 'tool-error': !success }">{{ result }}</text>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  toolName: string
  args?: string
  result?: string
  success?: boolean
}>()

const expanded = ref(false)

const statusText = computed(() => {
  if (props.result !== undefined) {
    return props.success ? t('chat.toolCompleted') : t('chat.toolFailed')
  }
  return t('chat.toolCalling')
})

const statusColor = computed(() => {
  if (props.result !== undefined) {
    return props.success ? 'var(--chat-success)' : 'var(--chat-error)'
  }
  return 'var(--chat-primary)'
})

function toggle() {
  expanded.value = !expanded.value
}
</script>

<style lang="scss" scoped>
.tool-card {
  margin: 6px 0;
  border-radius: var(--chat-radius-sm, 6px);
  background: var(--chat-bg-elevated);
  border: 1px solid var(--chat-border);
  overflow: hidden;
}

.tool-header {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  gap: 6px;
}

.tool-icon {
  font-size: 13px;
}

.tool-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--chat-text-primary);
}

.tool-status {
  font-size: 11px;
}

.tool-arrow {
  font-size: 11px;
  color: var(--chat-text-secondary);
}

.tool-body {
  border-top: 1px solid var(--chat-border);
  padding: 8px 12px;
}

.tool-section {
  margin-bottom: 8px;

  &:last-child {
    margin-bottom: 0;
  }
}

.tool-section-label {
  font-size: 11px;
  color: var(--chat-text-secondary);
  margin-bottom: 4px;
  display: block;
}

.tool-code-scroll {
  max-height: 120px;
}

.tool-code {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.5;
  color: var(--chat-text-primary);
  white-space: pre;
}

.tool-error {
  color: var(--chat-error);
}
</style>

<template>
  <view class="confirm-card">
    <view class="confirm-header">
      <text class="confirm-icon">⚠️</text>
      <view class="confirm-title">工具执行确认</view>
      <view :class="['confirm-badge', statusClass]">
        <text class="confirm-badge-text">{{ statusText }}</text>
      </view>
    </view>

    <view class="confirm-tools">
      <view
        v-for="tool in pendingCallTools"
        :key="tool.toolId"
        class="confirm-tool-item"
      >
        <view class="confirm-tool-name">
          <text>🔧 {{ tool.toolName }}</text>
          <view v-if="tool.isDangerous" class="danger-tag">
            <view class="danger-tag-text">高风险</view>
          </view>
        </view>
        <text class="confirm-tool-args">{{ JSON.stringify(tool.arguments, null, 2) }}</text>
      </view>
    </view>

    <view v-if="status === 'pending'" class="confirm-actions">
      <view class="btn btn-reject" @tap="$emit('confirm', false)">
        <view class="btn-text">拒绝</view>
      </view>
      <view class="btn btn-allow" @tap="$emit('confirm', true)">
        <view class="btn-text btn-text-primary">允许执行</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PendingCallTool } from '@/types/chat'

const props = defineProps<{
  pendingCallTools: PendingCallTool[]
  status: 'pending' | 'confirmed' | 'rejected'
}>()

defineEmits<{
  confirm: [confirmed: boolean]
}>()

const statusText = computed(() => {
  switch (props.status) {
    case 'confirmed': return '已确认'
    case 'rejected': return '已拒绝'
    default: return '待确认'
  }
})

const statusClass = computed(() => {
  switch (props.status) {
    case 'confirmed': return 'badge-success'
    case 'rejected': return 'badge-error'
    default: return 'badge-warning'
  }
})
</script>

<style lang="scss" scoped>
.confirm-card {
  margin: 8px 0;
  border-radius: var(--chat-radius-sm, 6px);
  background: var(--chat-bg-elevated, rgba(0, 0, 0, 0.03));
  border: 1px solid var(--chat-warning-border, rgba(245, 158, 11, 0.3));
  overflow: hidden;
}

.confirm-header {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  gap: 6px;
  background: var(--chat-warning-bg, rgba(245, 158, 11, 0.06));
}

.confirm-icon {
  font-size: 14px;
}

.confirm-title {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--chat-text-primary, #1a1a2e);
}

.confirm-badge {
  padding: 2px 8px;
  border-radius: 10px;
}

.badge-warning {
  background: rgba(245, 158, 11, 0.15);
}

.badge-success {
  background: rgba(34, 197, 94, 0.15);
}

.badge-error {
  background: rgba(239, 68, 68, 0.15);
}

.confirm-badge-text {
  font-size: 11px;
  color: var(--chat-text-secondary, #666);
}

.confirm-tools {
  padding: 8px 12px;
}

.confirm-tool-item {
  padding: 8px;
  margin-bottom: 6px;
  border-radius: 4px;
  background: var(--chat-bg-base, #fff);
  border: 1px solid var(--chat-border, #e5e5e5);

  &:last-child {
    margin-bottom: 0;
  }
}

.confirm-tool-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 4px;
  color: var(--chat-text-primary, #1a1a2e);
}

.danger-tag {
  padding: 1px 6px;
  border-radius: 3px;
  background: rgba(239, 68, 68, 0.12);
}

.danger-tag-text {
  font-size: 10px;
  color: var(--chat-error, #ef4444);
  font-weight: 500;
}

.confirm-tool-args {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.4;
  color: var(--chat-text-secondary, #666);
  white-space: pre-wrap;
  max-height: 100px;
  overflow: hidden;
}

.confirm-actions {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--chat-border, #e5e5e5);
}

.btn {
  flex: 1;
  padding: 8px 0;
  border-radius: var(--chat-radius-sm, 6px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-reject {
  background: var(--chat-bg-base, #fff);
  border: 1px solid var(--chat-error, #ef4444);
}

.btn-allow {
  background: var(--chat-primary, #4f6ef7);
}

.btn-text {
  font-size: 13px;
  font-weight: 500;
  color: var(--chat-error, #ef4444);
}

.btn-text-primary {
  color: #fff;
}
</style>

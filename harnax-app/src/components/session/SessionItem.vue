<template>
  <view
    :class="['session-item', { active: isActive }]"
    @tap="$emit('select')"
  >
    <view class="session-info">
      <text class="session-name">{{ session.name }}</text>
      <text class="session-time">{{ formatTime(session.updatedAt) }}</text>
    </view>
    <view class="session-actions" @tap.stop>
      <view class="action-btn" @tap="$emit('delete')">
        <text class="action-icon">🗑</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import type { SessionItem } from '@/store/useSessionStore'

defineProps<{
  session: SessionItem
  isActive: boolean
}>()

defineEmits<{
  select: []
  delete: []
}>()

function formatTime(ts: number): string {
  const d = new Date(ts)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) {
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
</script>

<style lang="scss" scoped>
.session-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: var(--chat-radius-sm, 6px);
  margin-bottom: 2px;
  transition: background 0.15s;

  &.active {
    background: var(--chat-sidebar-active, rgba(79, 110, 247, 0.1));
  }
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--chat-text-primary, #1a1a2e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

.session-time {
  font-size: 11px;
  color: var(--chat-text-tertiary, #999);
  margin-top: 2px;
  display: block;
}

.session-actions {
  opacity: 0.5;
  padding: 4px;
}

.action-btn {
  padding: 4px;
}

.action-icon {
  font-size: 12px;
}
</style>

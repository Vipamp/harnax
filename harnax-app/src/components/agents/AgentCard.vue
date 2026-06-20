<template>
  <view class="agent-card" @tap="$emit('select')">
    <view class="card-header">
      <view class="agent-avatar">
        <text class="avatar-text">{{ name.charAt(0).toUpperCase() }}</text>
      </view>
      <view class="card-info">
        <text class="agent-name">{{ name }}</text>
        <text class="agent-model">{{ modelName }}</text>
      </view>
      <view class="session-badge">
        <text class="badge-text">{{ sessionCount }}</text>
      </view>
    </view>
    <text class="agent-desc">{{ description }}</text>
  </view>
</template>

<script setup lang="ts">
defineProps<{
  name: string
  description: string
  modelName: string
  sessionCount: number
}>()

defineEmits<{
  select: []
}>()
</script>

<style lang="scss" scoped>
.agent-card {
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 16px;
  margin-bottom: 10px;
  box-shadow: var(--chat-shadow-sm);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.agent-avatar {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--chat-primary, #4f6ef7);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.avatar-text {
  color: var(--chat-text-on-primary);
  font-size: 18px;
  font-weight: 600;
}

.card-info {
  flex: 1;
  min-width: 0;
}

.agent-name {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: var(--chat-text-primary, #1a1a2e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-model {
  display: block;
  font-size: 12px;
  color: var(--chat-text-tertiary, #999);
  margin-top: 2px;
}

.session-badge {
  background: rgba(79, 110, 247, 0.1);
  border-radius: 10px;
  padding: 2px 8px;
  flex-shrink: 0;
}

.badge-text {
  font-size: 12px;
  color: var(--chat-primary, #4f6ef7);
  font-weight: 500;
}

.agent-desc {
  font-size: 13px;
  color: var(--chat-text-secondary, #666);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>

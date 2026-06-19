<template>
  <view class="sidebar" :class="{ collapsed: !visible }">
    <view v-if="visible" class="sidebar-content">
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
defineProps<{
  visible: boolean
}>()
</script>

<style lang="scss" scoped>
.sidebar {
  height: 100%;
  transition: width 0.2s;
  background: var(--chat-sidebar-bg, #f8f8fa);
  border-right: 1px solid var(--chat-border, #e5e5e5);

  &.collapsed {
    width: 0;
    overflow: hidden;
  }
}

.sidebar-content {
  width: 260px;
  height: 100%;
  overflow: hidden;
}
</style>

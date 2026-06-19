<template>
  <view class="page-setup">
    <ConnectionSetup @connected="onConnected" />
  </view>
</template>

<script setup lang="ts">
import ConnectionSetup from '@/components/connection/ConnectionSetup.vue'

function onConnected() {
  uni.redirectTo({ url: '/pages/chat/index' })
}
</script>

<style lang="scss" scoped>
.page-setup {
  min-height: 100vh;
  background: var(--chat-bg-base, #f5f5f5);
}
</style>

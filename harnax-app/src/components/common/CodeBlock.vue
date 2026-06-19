<template>
  <view class="code-block">
    <view class="code-header">
      <text class="code-lang">{{ language }}</text>
      <view class="code-copy" @tap="copyCode">
        <view class="code-copy-text">{{ copied ? '已复制' : '复制' }}</view>
      </view>
    </view>
    <scroll-view scroll-x class="code-scroll">
      <text class="code-content">{{ code }}</text>
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  code: string
  language?: string
}>()

const copied = ref(false)

async function copyCode() {
  try {
    // #ifdef H5
    await navigator.clipboard.writeText(props.code)
    // #endif
    // #ifndef H5
    uni.setClipboardData({ data: props.code })
    // #endif
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch {
    uni.showToast({ title: '复制失败', icon: 'none' })
  }
}
</script>

<style lang="scss" scoped>
.code-block {
  border-radius: var(--chat-radius-md, 8px);
  overflow: hidden;
  margin: 8px 0;
  background: var(--chat-code-bg, #1e1e2e);
}

.code-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: var(--chat-code-header-bg, rgba(255, 255, 255, 0.06));
}

.code-lang {
  font-size: 11px;
  color: var(--chat-code-lang, #a6adc8);
  text-transform: lowercase;
}

.code-copy {
  padding: 2px 8px;
  border-radius: 4px;
}

.code-copy-text {
  font-size: 11px;
  color: var(--chat-code-copy, #cdd6f4);
}

.code-scroll {
  padding: 12px;
}

.code-content {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--chat-code-text, #cdd6f4);
  white-space: pre;
}
</style>

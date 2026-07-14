<template>
  <!-- 未配置服务器时显示登录组件 -->
  <ConnectionSetup v-if="!connection.isConfigured()" @connected="onConnected" />

  <!-- 已配置后显示聊天界面 -->
  <AppLayout v-else-if="isReady">
    <ChatView />
  </AppLayout>
  <view v-else class="loading-page">
    <text class="loading-text">{{ t('common.loading') }}</text>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useI18n } from 'vue-i18n'
import AppLayout from '@/components/layout/AppLayout.vue'
import ChatView from '@/components/chat/ChatView.vue'
import ConnectionSetup from '@/components/connection/ConnectionSetup.vue'
import { useConnectionStore } from '@/store/useConnectionStore'
import { useSessionStore } from '@/store/useSessionStore'
import { useChatStore } from '@/store/useChatStore'

const { t } = useI18n()
const connection = useConnectionStore()
const sessionStore = useSessionStore()
const chatStore = useChatStore()

const isReady = ref(false)
let lastLoadedSessionId = ''

onMounted(() => {
  if (connection.isConfigured()) {
    initChat()
  }
})

// Re-sync session data when tab becomes visible again (e.g. after creating new session from agents)
onShow(() => {
  if (!connection.isConfigured() || !isReady.value) return
  const currentId = sessionStore.currentSessionId
  if (currentId !== lastLoadedSessionId) {
    lastLoadedSessionId = currentId
    chatStore.switchSession(currentId)
  }
})

function onConnected() {
  initChat()
}

async function initChat() {
  try {
    await sessionStore.fetchSessions()
  } catch {
    // server fetch failed, continue with local data
  }

  const sessionId = await sessionStore.ensureSession()
  if (sessionId) {
    lastLoadedSessionId = sessionId
    await chatStore.switchSession(sessionId)
  }
  isReady.value = true
}
</script>

<style scoped>
.loading-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
.loading-text {
  font-size: 14px;
  color: var(--chat-text-tertiary, #999);
}
</style>

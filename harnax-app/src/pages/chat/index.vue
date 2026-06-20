<template>
  <AppLayout v-if="isReady">
    <ChatView />
  </AppLayout>
  <view v-else class="loading-page">
    <text class="loading-text">{{ t('common.loading') }}</text>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import AppLayout from '@/components/layout/AppLayout.vue'
import ChatView from '@/components/chat/ChatView.vue'
import { useConnectionStore } from '@/store/useConnectionStore'
import { useSessionStore } from '@/store/useSessionStore'
import { useChatStore } from '@/store/useChatStore'

const { t } = useI18n()
const connection = useConnectionStore()
const sessionStore = useSessionStore()
const chatStore = useChatStore()

const isReady = ref(false)

onMounted(() => {
  if (!connection.isConfigured()) {
    uni.redirectTo({ url: '/pages/setup/index' })
    return
  }

  initChat()
})

async function initChat() {
  try {
    await sessionStore.fetchSessions()
  } catch {
    // server fetch failed, continue with local data
  }

  const sessionId = await sessionStore.ensureSession()
  if (sessionId) {
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

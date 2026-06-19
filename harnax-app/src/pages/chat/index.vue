<template>
  <AppLayout @settings="goSettings">
    <ChatView />
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import ChatView from '@/components/chat/ChatView.vue'
import { useConnectionStore } from '@/store/useConnectionStore'
import { useSessionStore } from '@/store/useSessionStore'
import { useChatStore } from '@/store/useChatStore'

const connection = useConnectionStore()
const sessionStore = useSessionStore()
const chatStore = useChatStore()

function goSettings() {
  uni.redirectTo({ url: '/pages/setup/index' })
}

onMounted(() => {
  if (!connection.isConfigured()) {
    uni.redirectTo({ url: '/pages/setup/index' })
    return
  }

  const sessionId = sessionStore.ensureSession()
  chatStore.switchSession(sessionId)
})
</script>

<template>
  <!-- Login screen when not configured -->
  <ConnectionSetup v-if="!isReady" @connected="onConnected" />
  <!-- Main chat layout when logged in -->
  <AppLayout v-else>
    <ChatView />
  </AppLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import ChatView from '@/components/chat/ChatView.vue'
import ConnectionSetup from '@/components/connection/ConnectionSetup.vue'
import { useConnectionStore } from '@/store/useConnectionStore'
import { useSessionStore } from '@/store/useSessionStore'
import { useChatStore } from '@/store/useChatStore'

const connection = useConnectionStore()
const sessionStore = useSessionStore()
const chatStore = useChatStore()

const isReady = ref(false)

function onConnected() {
  isReady.value = true
  initChat()
}

async function initChat() {
  try {
    await sessionStore.fetchSessions()
  } catch {
    // server fetch failed, continue with local data
  }
  const sessionId = await sessionStore.ensureSession()
  chatStore.switchSession(sessionId)
}

onMounted(() => {
  if (connection.isConfigured()) {
    isReady.value = true
    initChat()
  }
})
</script>

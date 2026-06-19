import { defineStore } from 'pinia'
import { ref, nextTick } from 'vue'
import type {
  ChatMessage,
  MessageSegment,
  ChatEvent,
  PendingCallTool,
  TokenUsage,
} from '@/types/chat'
import { streamChat, streamConfirm, sendCommand, getChatHistory, clearSession } from '@/api/router'
import { useSessionStore } from './useSessionStore'
import { getStorage, setStorage } from '@/utils/storage'
import { generateUUID } from '@/utils/platform'

function messagesKey(sessionId: string): string {
  return `messages_${sessionId}`
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const isStreaming = ref(false)
  const isWaitingConfirm = ref(false)
  const pendingConfirmTools = ref<PendingCallTool[]>([])
  const currentAbortController = ref<AbortController | null>(null)
  const scrollToBottom = ref(false)
  const lastTokenUsage = ref<TokenUsage | null>(null)

  let assistantMessage: ChatMessage | null = null

  function persistMessages(sessionId: string) {
    setStorage(messagesKey(sessionId), messages.value)
  }

  function loadMessages(sessionId: string) {
    const saved = getStorage<ChatMessage[]>(messagesKey(sessionId))
    messages.value = saved || []
  }

  function triggerScroll() {
    nextTick(() => {
      scrollToBottom.value = true
    })
  }

  function findOrCreateAssistantMessage(): ChatMessage {
    if (assistantMessage) return assistantMessage
    assistantMessage = {
      id: generateUUID(),
      role: 'assistant',
      segments: [],
      timestamp: Date.now(),
    }
    messages.value.push(assistantMessage)
    return assistantMessage
  }

  function updateTokenUsage(usage?: TokenUsage) {
    if (usage) {
      lastTokenUsage.value = usage
      if (assistantMessage) {
        assistantMessage.tokenUsage = usage
      }
    }
  }

  function handleEvent(event: ChatEvent) {
    const msg = findOrCreateAssistantMessage()

    switch (event.eventType) {
      case 'ThinkingEvent': {
        const last = msg.segments[msg.segments.length - 1]
        if (last && last.type === 'thinking') {
          last.content += event.message
        } else {
          msg.segments.push({ type: 'thinking', content: event.message })
        }
        updateTokenUsage(event.tokenUsage)
        triggerScroll()
        break
      }

      case 'TextEvent': {
        const last = msg.segments[msg.segments.length - 1]
        if (last && last.type === 'text') {
          last.content += event.message
        } else {
          msg.segments.push({ type: 'text', content: event.message })
        }
        updateTokenUsage(event.tokenUsage)
        triggerScroll()
        break
      }

      case 'CallToolEvent': {
        msg.segments.push({
          type: 'tool_call',
          content: JSON.stringify(event.arguments, null, 2),
          toolName: event.toolName,
          toolId: event.toolId,
        })
        updateTokenUsage(event.tokenUsage)
        triggerScroll()
        break
      }

      case 'ToolResultEvent': {
        const toolCall = [...msg.segments].reverse().find(
          (s) => s.type === 'tool_call' && s.toolId === event.toolId,
        )
        if (toolCall) {
          toolCall.toolResult = event.message
          toolCall.success = event.success
        } else {
          msg.segments.push({
            type: 'tool_result',
            content: event.message,
            toolName: event.toolName,
            toolId: event.toolId,
            success: event.success,
          })
        }
        updateTokenUsage(event.tokenUsage)
        triggerScroll()
        break
      }

      case 'ToolConfirmEvent': {
        msg.segments.push({
          type: 'tool_confirm',
          content: '',
          confirmStatus: 'pending',
          pendingCallTools: event.pendingCallTools,
        })
        pendingConfirmTools.value = event.pendingCallTools
        isWaitingConfirm.value = true
        triggerScroll()
        break
      }

      case 'ErrorEvent': {
        msg.segments.push({
          type: 'text',
          content: `\n\n> **Error**: ${event.message}`,
        })
        triggerScroll()
        break
      }

      case 'EndEvent': {
        break
      }
    }
  }

  async function sendMessage(text: string, imageUrls?: string[]) {
    const sessionStore = useSessionStore()
    const sessionId = sessionStore.ensureSession()

    const userMessage: ChatMessage = {
      id: generateUUID(),
      role: 'user',
      segments: [{ type: 'text', content: text }],
      timestamp: Date.now(),
      imageUrls,
    }
    messages.value.push(userMessage)

    assistantMessage = null
    isStreaming.value = true
    lastTokenUsage.value = null
    triggerScroll()

    const abortController = new AbortController()
    currentAbortController.value = abortController

    try {
      await streamChat(
        { sessionId, message: text, imageUrls },
        handleEvent,
        abortController.signal,
      )
    } catch (e) {
      const msg = findOrCreateAssistantMessage()
      msg.segments.push({
        type: 'text',
        content: `\n\n> **Error**: ${(e as Error).message}`,
      })
    } finally {
      isStreaming.value = false
      currentAbortController.value = null
      sessionStore.touchSession(sessionId)
      persistMessages(sessionId)
      triggerScroll()
    }
  }

  async function confirmTools(confirmed: boolean) {
    const sessionStore = useSessionStore()
    const sessionId = sessionStore.currentSessionId

    const tools = pendingConfirmTools.value
    isWaitingConfirm.value = false
    pendingConfirmTools.value = []

    if (assistantMessage) {
      const confirmSeg = [...assistantMessage.segments].reverse().find(
        (s) => s.type === 'tool_confirm' && s.confirmStatus === 'pending',
      )
      if (confirmSeg) {
        confirmSeg.confirmStatus = confirmed ? 'confirmed' : 'rejected'
      }
    }

    isStreaming.value = true
    const abortController = new AbortController()
    currentAbortController.value = abortController

    try {
      await streamConfirm(
        {
          sessionId,
          isConfirmed: confirmed,
          toolInfoList: tools.map((t) => ({
            toolId: t.toolId,
            toolName: t.toolName,
          })),
        },
        handleEvent,
        abortController.signal,
      )
    } catch (e) {
      const msg = findOrCreateAssistantMessage()
      msg.segments.push({
        type: 'text',
        content: `\n\n> **Error**: ${(e as Error).message}`,
      })
    } finally {
      isStreaming.value = false
      currentAbortController.value = null
      persistMessages(sessionId)
      triggerScroll()
    }
  }

  function stopStreaming() {
    currentAbortController.value?.abort()
    isStreaming.value = false
    currentAbortController.value = null
  }

  async function interruptSession() {
    const sessionStore = useSessionStore()
    const sessionId = sessionStore.currentSessionId
    stopStreaming()
    await sendCommand({ sessionId, command: 'INTERRUPT' })
  }

  async function clearCurrentSession() {
    const sessionStore = useSessionStore()
    const sessionId = sessionStore.currentSessionId
    await clearSession(sessionId)
    messages.value = []
    assistantMessage = null
    lastTokenUsage.value = null
    persistMessages(sessionId)
  }

  async function loadHistoryFromServer(sessionId: string) {
    try {
      const res = await getChatHistory(sessionId)
      if (res.code === 200 && res.data) {
        messages.value = res.data.map((msg, i) => ({
          id: `history_${i}_${generateUUID()}`,
          role: msg.role as 'user' | 'assistant',
          segments: [{ type: 'text' as const, content: msg.content }],
          timestamp: msg.timestamp || Date.now(),
        }))
        persistMessages(sessionId)
      }
    } catch {
      // fall back to local storage
      loadMessages(sessionId)
    }
  }

  function switchSession(sessionId: string) {
    stopStreaming()
    assistantMessage = null
    isWaitingConfirm.value = false
    pendingConfirmTools.value = []
    lastTokenUsage.value = null
    loadMessages(sessionId)
  }

  return {
    messages,
    isStreaming,
    isWaitingConfirm,
    pendingConfirmTools,
    scrollToBottom,
    lastTokenUsage,
    sendMessage,
    confirmTools,
    stopStreaming,
    interruptSession,
    clearCurrentSession,
    loadHistoryFromServer,
    switchSession,
    loadMessages,
    persistMessages,
  }
})

import { defineStore } from 'pinia'
import { ref, nextTick } from 'vue'
import type {
  ChatMessage,
  MessageSegment,
  ChatEvent,
  PendingCallTool,
  TokenUsage,
} from '@/types/chat'
import { streamChat, streamConfirm, sendCommand, clearSession } from '@/api/router'
import type { CommandRequest } from '@/types/api'
import { mpGetChatHistory, mpSaveChatMessages, mpDeleteChatHistory } from '@/api/admin'
import { useSessionStore } from './useSessionStore'
import { getStorage, setStorage } from '@/utils/storage'
import { generateUUID } from '@/utils/platform'
import { vibrateShort, vibrateLong } from '@/utils/network'

/**
 * Slash-command keyword → CommandType mapping (mirrors Kotlin CommandType enum).
 */
const COMMAND_KEYWORDS: Record<string, CommandRequest['command']> = {
  interrupt: 'INTERRUPT',
  stop: 'INTERRUPT',
  clear: 'CLEAR',
  compact: 'COMPACT',
  approve: 'APPROVE',
  'stop-sandbox': 'INTERRUPT', // map to INTERRUPT for now; stop-sandbox not exposed via frontend
  enable: 'ENABLE',
  disable: 'DISABLE',
}

/**
 * Parse a slash-command text into a CommandRequest.
 * Returns null if the text is not a valid command.
 */
function parseSlashCommand(text: string): { command: CommandRequest['command']; args: string } | null {
  if (!text.startsWith('/')) return null
  const afterSlash = text.substring(1).trim()
  if (!afterSlash) return null

  const spaceIdx = afterSlash.indexOf(' ')
  const colonIdx = afterSlash.indexOf(':')
  const sepIdx =
    spaceIdx < 0 && colonIdx < 0
      ? -1
      : spaceIdx < 0
        ? colonIdx
        : colonIdx < 0
          ? spaceIdx
          : Math.min(spaceIdx, colonIdx)

  const keyword = (sepIdx >= 0 ? afterSlash.substring(0, sepIdx) : afterSlash).toLowerCase()
  const args = sepIdx >= 0 ? afterSlash.substring(sepIdx + 1).trim() : ''

  const command = COMMAND_KEYWORDS[keyword]
  if (!command) return null
  return { command, args }
}

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

  /** Sync current messages to admin server (fire-and-forget) */
  function syncToServer(sessionId: string) {
    const sessionStore = useSessionStore()
    const session = sessionStore.sessions.find((s) => s.id === sessionId)
    if (!session?.serverId) return

    const dtos = messages.value.map((msg) => ({
      role: msg.role,
      content: msg.segments
        .filter((s) => s.type === 'text' || s.type === 'thinking')
        .map((s) => s.content)
        .join('\n'),
      segmentsJson: JSON.stringify(msg.segments),
      tokenUsageJson: msg.tokenUsage ? JSON.stringify(msg.tokenUsage) : undefined,
      imageUrlsJson: msg.imageUrls ? JSON.stringify(msg.imageUrls) : undefined,
    }))

    mpSaveChatMessages(session.serverId, dtos).catch((e) => {
      console.warn('[Chat] Failed to sync messages to server', e)
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
    if (usage && assistantMessage) {
      assistantMessage.tokenUsage = usage
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
        const isFirstText = !msg.segments.some((s) => s.type === 'text')
        if (last && last.type === 'text') {
          last.content += event.message
        } else {
          msg.segments.push({ type: 'text', content: event.message })
        }
        updateTokenUsage(event.tokenUsage)
        triggerScroll()
        // Only vibrate on first text chunk to avoid excessive buzzing
        if (isFirstText) vibrateShort()
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
        vibrateLong()
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
    // Prevent concurrent sends while streaming
    if (isStreaming.value) return

    const sessionStore = useSessionStore()
    const sessionId = await sessionStore.ensureSession()

    if (!sessionId) {
      // No session available, redirect to agent selection
      uni.switchTab({ url: '/pages/agents/index' })
      return
    }

    // Detect slash commands and route to /command endpoint
    const parsed = parseSlashCommand(text)
    if (parsed) {
      const userMessage: ChatMessage = {
        id: generateUUID(),
        role: 'user',
        segments: [{ type: 'text', content: text }],
        timestamp: Date.now(),
      }
      messages.value.push(userMessage)

      isStreaming.value = true
      triggerScroll()

      try {
        const res = await sendCommand({
          sessionId,
          command: parsed.command,
          args: parsed.args,
        })
        const reply = res.data?.message || (res.data?.success ? 'Done' : res.message || 'Command failed')
        const assistantMsg: ChatMessage = {
          id: generateUUID(),
          role: 'assistant',
          segments: [{ type: 'text', content: reply }],
          timestamp: Date.now(),
        }
        messages.value.push(assistantMsg)
        assistantMessage = null
      } catch (e) {
        const msg: ChatMessage = {
          id: generateUUID(),
          role: 'assistant',
          segments: [{ type: 'text', content: `\n\n> **Error**: ${(e as Error).message}` }],
          timestamp: Date.now(),
        }
        messages.value.push(msg)
      } finally {
        isStreaming.value = false
        triggerScroll()
        sessionStore.touchSession(sessionId)
        persistMessages(sessionId)
        syncToServer(sessionId)
      }
      return
    }

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
      // Sync latest messages to server
      syncToServer(sessionId)
    }
  }

  async function confirmTools(confirmed: boolean) {
    // Prevent concurrent confirmations while streaming
    if (isStreaming.value) return

    const sessionStore = useSessionStore()
    const sessionId = sessionStore.currentSessionId
    if (!sessionId) return

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
      sessionStore.touchSession(sessionId)
      persistMessages(sessionId)
      triggerScroll()
      syncToServer(sessionId)
    }
  }

  function stopStreaming() {
    currentAbortController.value?.abort()
    const sessionId = useSessionStore().currentSessionId
    isStreaming.value = false
    currentAbortController.value = null
    if (sessionId) {
      persistMessages(sessionId)
      syncToServer(sessionId)
    }
  }

  async function interruptSession() {
    const sessionStore = useSessionStore()
    const sessionId = sessionStore.currentSessionId
    stopStreaming()
    if (!sessionId) return
    await sendCommand({ sessionId, command: 'INTERRUPT' })
  }

  async function clearCurrentSession() {
    const sessionStore = useSessionStore()
    const sessionId = sessionStore.currentSessionId
    if (!sessionId) return
    const session = sessionStore.currentSession

    // Abort any active stream first
    stopStreaming()

    // Clear on router side
    await clearSession(sessionId).catch(() => {})

    // Clear on admin side
    if (session?.serverId) {
      await mpDeleteChatHistory(session.serverId).catch(() => {})
    }

    messages.value = []
    assistantMessage = null
    persistMessages(sessionId)
  }

  async function loadHistoryFromServer(sessionId: string) {
    const sessionStore = useSessionStore()
    const session = sessionStore.sessions.find((s) => s.id === sessionId)

    // Try admin API first if session has a serverId
    if (session?.serverId) {
      try {
        const res = await mpGetChatHistory(session.serverId)
        if (res.code === 200 && res.data) {
          messages.value = res.data.map((msg, i) => {
            let segments: MessageSegment[] = []
            if (msg.segmentsJson) {
              try {
                segments = JSON.parse(msg.segmentsJson)
              } catch {
                segments = [{ type: 'text' as const, content: msg.content }]
              }
            } else {
              segments = [{ type: 'text' as const, content: msg.content }]
            }

            let tokenUsage: TokenUsage | undefined
            if (msg.tokenUsageJson) {
              try {
                tokenUsage = JSON.parse(msg.tokenUsageJson)
              } catch {
                // ignore
              }
            }

            let imageUrls: string[] | undefined
            if (msg.imageUrlsJson) {
              try {
                imageUrls = JSON.parse(msg.imageUrlsJson)
              } catch {
                // ignore
              }
            }

            return {
              id: `history_${i}_${generateUUID()}`,
              role: msg.role as 'user' | 'assistant',
              segments,
              timestamp: Date.now() + i,
              tokenUsage,
              imageUrls,
            }
          })
          persistMessages(sessionId)
          return
        }
      } catch {
        // fall through to local storage
      }
    }

    // Fallback to local storage
    loadMessages(sessionId)
  }

  async function switchSession(sessionId: string) {
    stopStreaming()
    assistantMessage = null
    isWaitingConfirm.value = false
    pendingConfirmTools.value = []
    await loadHistoryFromServer(sessionId)
  }

  return {
    messages,
    isStreaming,
    isWaitingConfirm,
    pendingConfirmTools,
    scrollToBottom,
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

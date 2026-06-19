import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getStorage, setStorage } from '@/utils/storage'
import { generateUUID } from '@/utils/platform'

const STORAGE_KEY = 'sessions'

export interface SessionItem {
  id: string
  name: string
  createdAt: number
  updatedAt: number
}

export const useSessionStore = defineStore('session', () => {
  const sessions = ref<SessionItem[]>([])
  const currentSessionId = ref<string>('')

  const currentSession = computed(() =>
    sessions.value.find((s) => s.id === currentSessionId.value) || null,
  )

  const sortedSessions = computed(() =>
    [...sessions.value].sort((a, b) => b.updatedAt - a.updatedAt),
  )

  function loadFromStorage() {
    const data = getStorage<SessionItem[]>(STORAGE_KEY)
    if (data) {
      sessions.value = data
    }
    const savedId = getStorage<string>('current_session_id')
    if (savedId && sessions.value.some((s) => s.id === savedId)) {
      currentSessionId.value = savedId
    }
  }

  function persist() {
    setStorage(STORAGE_KEY, sessions.value)
    setStorage('current_session_id', currentSessionId.value)
  }

  function createSession(name?: string): string {
    const id = generateUUID()
    const now = Date.now()
    sessions.value.push({
      id,
      name: name || `New Chat ${sessions.value.length + 1}`,
      createdAt: now,
      updatedAt: now,
    })
    currentSessionId.value = id
    persist()
    return id
  }

  function switchSession(id: string) {
    if (sessions.value.some((s) => s.id === id)) {
      currentSessionId.value = id
      persist()
    }
  }

  function renameSession(id: string, name: string) {
    const session = sessions.value.find((s) => s.id === id)
    if (session) {
      session.name = name
      persist()
    }
  }

  function deleteSession(id: string) {
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = sessions.value[0]?.id || ''
    }
    persist()
  }

  function touchSession(id: string) {
    const session = sessions.value.find((s) => s.id === id)
    if (session) {
      session.updatedAt = Date.now()
      persist()
    }
  }

  function ensureSession(): string {
    if (currentSessionId.value && sessions.value.some((s) => s.id === currentSessionId.value)) {
      return currentSessionId.value
    }
    if (sessions.value.length > 0) {
      currentSessionId.value = sessions.value[0].id
      persist()
      return currentSessionId.value
    }
    return createSession()
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    sortedSessions,
    loadFromStorage,
    persist,
    createSession,
    switchSession,
    renameSession,
    deleteSession,
    touchSession,
    ensureSession,
  }
})

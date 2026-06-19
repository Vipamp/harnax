import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getStorage, setStorage } from '@/utils/storage'
import { generateUUID } from '@/utils/platform'
import {
  mpListSessions,
  mpCreateSession,
  mpUpdateSession,
  mpDeleteSession,
} from '@/api/admin'
import type { MpSessionResponse } from '@/types/api'

const STORAGE_KEY = 'sessions'

export interface SessionItem {
  /** Local ID (same as routerSessionId) */
  id: string
  /** Server-side mp_session.id */
  serverId: number | null
  name: string
  createdAt: number
  updatedAt: number
}

export const useSessionStore = defineStore('session', () => {
  const sessions = ref<SessionItem[]>([])
  const currentSessionId = ref<string>('')
  const isLoading = ref(false)

  const currentSession = computed(() =>
    sessions.value.find((s) => s.id === currentSessionId.value) || null,
  )

  const sortedSessions = computed(() =>
    [...sessions.value].sort((a, b) => b.updatedAt - a.updatedAt),
  )

  /** Load sessions from local storage as initial state */
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

  /** Fetch session list from admin server and merge with local */
  async function fetchSessions() {
    isLoading.value = true
    try {
      const res = await mpListSessions()
      if (res.code === 200 && res.data) {
        sessions.value = res.data.map(toSessionItem)
        // Ensure current session is still valid
        if (currentSessionId.value && !sessions.value.some((s) => s.id === currentSessionId.value)) {
          currentSessionId.value = sessions.value[0]?.id || ''
        }
        persist()
      }
    } catch (e) {
      console.warn('[Session] Failed to fetch from server, using local data', e)
    } finally {
      isLoading.value = false
    }
  }

  /** Create a new session, sync to server */
  async function createSession(name?: string): Promise<string> {
    const routerSessionId = generateUUID()
    const sessionName = name || `New Chat ${sessions.value.length + 1}`

    try {
      const res = await mpCreateSession({
        sessionName,
        routerSessionId,
      })
      if (res.code === 200 && res.data) {
        const item = toSessionItem(res.data)
        sessions.value.push(item)
        currentSessionId.value = item.id
        persist()
        return item.id
      }
    } catch (e) {
      console.warn('[Session] Server create failed, creating locally', e)
    }

    // Fallback: create locally
    const now = Date.now()
    const item: SessionItem = {
      id: routerSessionId,
      serverId: null,
      name: sessionName,
      createdAt: now,
      updatedAt: now,
    }
    sessions.value.push(item)
    currentSessionId.value = item.id
    persist()
    return item.id
  }

  function switchSession(id: string) {
    if (sessions.value.some((s) => s.id === id)) {
      currentSessionId.value = id
      persist()
    }
  }

  async function renameSession(id: string, name: string) {
    const session = sessions.value.find((s) => s.id === id)
    if (!session) return

    session.name = name
    persist()

    if (session.serverId) {
      try {
        await mpUpdateSession(session.serverId, { sessionName: name })
      } catch (e) {
        console.warn('[Session] Server rename failed', e)
      }
    }
  }

  async function deleteSession(id: string) {
    const session = sessions.value.find((s) => s.id === id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = sessions.value[0]?.id || ''
    }
    persist()

    if (session?.serverId) {
      try {
        await mpDeleteSession(session.serverId)
      } catch (e) {
        console.warn('[Session] Server delete failed', e)
      }
    }
  }

  function touchSession(id: string) {
    const session = sessions.value.find((s) => s.id === id)
    if (session) {
      session.updatedAt = Date.now()
      persist()
    }
  }

  async function ensureSession(): Promise<string> {
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

  function toSessionItem(mp: MpSessionResponse): SessionItem {
    return {
      id: mp.routerSessionId,
      serverId: mp.id,
      name: mp.sessionName,
      createdAt: new Date(mp.createTime).getTime(),
      updatedAt: new Date(mp.updateTime).getTime(),
    }
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    sortedSessions,
    isLoading,
    loadFromStorage,
    persist,
    fetchSessions,
    createSession,
    switchSession,
    renameSession,
    deleteSession,
    touchSession,
    ensureSession,
  }
})


import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getStorage, setStorage, ensureCacheSpace } from '@/utils/storage'
import {
  mpListSessions,
  mpCreateSession,
  mpUpdateSession,
  mpDeleteSession,
} from '@/api/admin'
import type { MpSessionResponse } from '@/types/api'

const STORAGE_KEY = 'sessions'

export interface SessionItem {
  id: string
  serverId: number | null
  name: string
  agentId: number
  agentName: string
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

  async function fetchSessions() {
    isLoading.value = true
    try {
      const res = await mpListSessions()
      if (res.code === 200 && res.data) {
        sessions.value = res.data.map(toSessionItem)
        if (currentSessionId.value && !sessions.value.some((s) => s.id === currentSessionId.value)) {
          currentSessionId.value = sessions.value[0]?.id || ''
        }
        persist()
        ensureCacheSpace()
      }
    } catch (e) {
      console.warn('[Session] Failed to fetch from server, using local data', e)
    } finally {
      isLoading.value = false
    }
  }

  async function createSession(agentId: number, agentName: string, name?: string): Promise<string> {
    const sessionName = name || `${agentName} ${new Date().toLocaleString()}`

    try {
      const res = await mpCreateSession({ sessionName, agentId })
      if (res.code === 200 && res.data) {
        const item = toSessionItem(res.data)
        sessions.value.push(item)
        currentSessionId.value = item.id
        persist()
        return item.id
      }
    } catch (e) {
      console.warn('[Session] Server create failed', e)
    }

    const now = Date.now()
    const fallbackId = `local_${now}`
    const item: SessionItem = {
      id: fallbackId,
      serverId: null,
      name: sessionName,
      agentId,
      agentName,
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
    return ''
  }

  function toSessionItem(mp: MpSessionResponse): SessionItem {
    return {
      id: mp.routerSessionId,
      serverId: mp.id,
      name: mp.sessionName,
      agentId: mp.agentId,
      agentName: mp.agentName,
      createdAt: parseDateTime(mp.createTime),
      updatedAt: parseDateTime(mp.updateTime),
    }
  }

  function parseDateTime(dt: string): number {
    const normalized = dt.includes('T') ? dt : dt.replace(' ', 'T')
    const ts = new Date(normalized).getTime()
    return isNaN(ts) ? Date.now() : ts
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

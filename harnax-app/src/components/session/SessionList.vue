<template>
  <view class="session-list">
    <view class="session-list-header">
      <text class="list-title">{{ t('session.title') }}</text>
      <view class="btn-new" @tap="handleNew">
        <text class="btn-new-text">+</text>
      </view>
    </view>
    <scroll-view scroll-y class="session-scroll">
      <SessionItem
        v-for="session in sortedSessions"
        :key="session.id"
        :session="session"
        :isActive="session.id === currentSessionId"
        @select="handleSelect(session.id)"
        @delete="handleDelete(session.id)"
      />
      <view v-if="sortedSessions.length === 0" class="no-sessions">
        <text class="no-sessions-text">{{ t('session.noSessions') }}</text>
      </view>
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useSessionStore } from '@/store/useSessionStore'
import { useChatStore } from '@/store/useChatStore'
import { storeToRefs } from 'pinia'
import SessionItem from './SessionItem.vue'

const { t } = useI18n()
const sessionStore = useSessionStore()
const chatStore = useChatStore()
const { sortedSessions, currentSessionId } = storeToRefs(sessionStore)

function handleNew() {
  const id = sessionStore.createSession()
  chatStore.switchSession(id)
}

function handleSelect(id: string) {
  sessionStore.switchSession(id)
  chatStore.switchSession(id)
}

function handleDelete(id: string) {
  uni.showModal({
    title: t('common.confirm'),
    content: t('session.deleteConfirm'),
    success: (res) => {
      if (res.confirm) {
        sessionStore.deleteSession(id)
        if (sessionStore.currentSessionId) {
          chatStore.switchSession(sessionStore.currentSessionId)
        }
      }
    },
  })
}
</script>

<style lang="scss" scoped>
.session-list {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.session-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 12px 10px;
}

.list-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--chat-text-primary, #1a1a2e);
}

.btn-new {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--chat-primary, #4f6ef7);
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-new-text {
  color: #fff;
  font-size: 18px;
  font-weight: 300;
}

.session-scroll {
  flex: 1;
  padding: 0 6px;
}

.no-sessions {
  padding: 40px 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.no-sessions-text {
  font-size: 13px;
  color: var(--chat-text-tertiary, #999);
}
</style>

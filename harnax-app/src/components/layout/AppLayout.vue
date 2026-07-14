<template>
  <view class="app-layout">
    <!-- #ifdef H5 -->
    <view class="layout-desktop">
      <Sidebar :visible="sidebarVisible">
        <SessionList />
      </Sidebar>
      <view class="layout-main">
        <view class="main-header">
          <view class="header-toggle" @tap="sidebarVisible = !sidebarVisible">
            <text class="toggle-icon">☰</text>
          </view>
          <view class="header-back" @tap="goToAgents">
            <text class="back-icon">&lt;</text>
          </view>
          <text class="header-title">{{ currentSessionName }}</text>
          <view class="header-actions">
            <view class="header-btn" @tap="handleClear">
              <text class="header-btn-text">🗑</text>
            </view>
            <view class="header-btn" @tap="handleSettings">
              <text class="header-btn-text">⚙</text>
            </view>
          </view>
        </view>
        <view class="main-content">
          <slot />
        </view>
      </view>
    </view>
    <!-- #endif -->

    <!-- #ifdef APP-PLUS -->
    <view class="layout-mobile">
      <view v-if="!isConnected" class="network-banner">
        <text class="network-banner-text">{{ t('common.networkDisconnected') }}</text>
      </view>
      <view v-if="showDrawer" class="drawer-overlay" @tap="showDrawer = false" />
      <view v-if="showDrawer" class="drawer-panel">
        <SessionList />
      </view>
      <view class="layout-main">
        <view class="main-header">
          <view class="header-back" @tap="goToAgents">
            <text class="back-icon">&lt;</text>
          </view>
          <view class="header-toggle" @tap="showDrawer = !showDrawer">
            <text class="toggle-icon">☰</text>
          </view>
          <text class="header-title">{{ currentSessionName }}</text>
          <view class="header-actions">
            <view class="header-btn" @tap="handleClear">
              <text class="header-btn-text">🗑</text>
            </view>
            <view class="header-btn" @tap="handleSettings">
              <text class="header-btn-text">⚙</text>
            </view>
          </view>
        </view>
        <view class="main-content">
          <slot />
        </view>
      </view>
    </view>
    <!-- #endif -->
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSessionStore } from '@/store/useSessionStore'
import { useChatStore } from '@/store/useChatStore'
import { useConnectionStore } from '@/store/useConnectionStore'
import { useNetwork } from '@/utils/network'
import { mpLogout } from '@/api/admin'
import Sidebar from './Sidebar.vue'
import SessionList from '@/components/session/SessionList.vue'

const { t } = useI18n()
const sessionStore = useSessionStore()
const chatStore = useChatStore()
const connectionStore = useConnectionStore()
const { isConnected } = useNetwork()

const sidebarVisible = ref(true)
const showDrawer = ref(false)

const currentSessionName = computed(() =>
  sessionStore.currentSession?.name || t('app.title'),
)

function handleClear() {
  uni.showModal({
    title: t('common.confirm'),
    content: t('session.clearConfirm'),
    success: (res) => {
      if (res.confirm) {
        chatStore.clearCurrentSession()
      }
    },
  })
}

function handleSettings() {
  uni.showActionSheet({
    itemList: [t('profile.logout')],
    success: (res) => {
      if (res.tapIndex === 0) {
        uni.showModal({
          title: t('profile.logoutConfirmTitle'),
          content: t('profile.logoutConfirm'),
          success: async (modalRes) => {
            if (modalRes.confirm) {
              try {
                await mpLogout()
              } catch {
                // ignore
              }
              connectionStore.clearAuth()
            }
          },
        })
      }
    },
  })
}

function goToAgents() {
  uni.switchTab({ url: '/pages/agents/index' })
}
</script>

<style lang="scss" scoped>
.app-layout {
  height: 100vh;
  overflow: hidden;
}

.layout-desktop,
.layout-mobile {
  display: flex;
  height: 100%;
}

.layout-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.main-header {
  display: flex;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--chat-border, #e5e5e5);
  background: var(--chat-bg-base, #fff);
  gap: 10px;
  padding-top: calc(10px + var(--status-bar-height, 0px));
}

.header-back {
  padding: 4px 8px;
}

.back-icon {
  font-size: 18px;
  color: var(--chat-text-primary, #1a1a2e);
  font-weight: 600;
}

.header-toggle {
  padding: 4px 8px;
}

.toggle-icon {
  font-size: 18px;
  color: var(--chat-text-primary, #1a1a2e);
}

.header-title {
  flex: 1;
  font-size: 15px;
  font-weight: 600;
  color: var(--chat-text-primary, #1a1a2e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-actions {
  display: flex;
  gap: 4px;
}

.header-btn {
  padding: 4px 8px;
  border-radius: var(--chat-radius-sm, 6px);
}

.header-btn-text {
  font-size: 15px;
}

.main-content {
  flex: 1;
  overflow: hidden;
}

.network-banner {
  background: var(--chat-warning, #faad14);
  padding: 6px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.network-banner-text {
  font-size: 12px;
  color: #fff;
  font-weight: 500;
}

.drawer-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 100;
}

.drawer-panel {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: 280px;
  background: var(--chat-sidebar-bg, #f8f8fa);
  z-index: 101;
  padding-top: var(--status-bar-height, 0px);
}
</style>

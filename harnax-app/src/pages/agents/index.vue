<template>
  <!-- 未登录时显示登录组件 -->
  <ConnectionSetup v-if="!connection.isLoggedIn" @connected="onConnected" />

  <!-- 已登录后显示智能体列表 -->
  <view v-else class="page-agents">
    <view class="page-header">
      <text class="page-title">{{ t('agents.title') }}</text>
      <view class="header-right">
        <view class="header-btn" @tap="handleProfile">
          <text class="btn-icon">&#x1F464;</text>
        </view>
      </view>
    </view>

    <view class="search-bar">
      <input
        v-model="searchQuery"
        class="search-input"
        :placeholder="t('agents.searchPlaceholder')"
        type="text"
      />
    </view>

    <scroll-view scroll-y class="agent-scroll">
      <view v-if="isLoading" class="loading-state">
        <text class="loading-text">{{ t('common.loading') }}</text>
      </view>

      <view v-else-if="filteredAgents.length === 0" class="empty-state">
        <text class="empty-text">{{ t('agents.noAgents') }}</text>
      </view>

      <AgentCard
        v-for="agent in filteredAgents"
        :key="agent.id"
        :name="agent.name"
        :description="agent.description"
        :model-name="agent.modelName"
        :session-count="agent.sessionCount"
        @select="handleSelectAgent(agent)"
      />
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { mpListAgents } from '@/api/admin'
import { useConnectionStore } from '@/store/useConnectionStore'
import type { MpAgentResponse } from '@/types/api'
import AgentCard from '@/components/agents/AgentCard.vue'
import ConnectionSetup from '@/components/connection/ConnectionSetup.vue'

const { t } = useI18n()
const connection = useConnectionStore()

const agents = ref<MpAgentResponse[]>([])
const searchQuery = ref('')
const isLoading = ref(false)

const filteredAgents = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return agents.value
  return agents.value.filter(
    (a) => a.name.toLowerCase().includes(q) || a.description.toLowerCase().includes(q),
  )
})

async function loadAgents() {
  isLoading.value = true
  try {
    const res = await mpListAgents()
    if (res.code === 200 && res.data) {
      agents.value = res.data
    }
  } catch (e) {
    console.error('[Agents] Failed to load', e)
  } finally {
    isLoading.value = false
  }
}

function handleSelectAgent(agent: MpAgentResponse) {
  uni.navigateTo({
    url: `/pages/agents/detail?agentId=${agent.id}&agentName=${encodeURIComponent(agent.name)}`,
  })
}

function handleProfile() {
  uni.switchTab({ url: '/pages/profile/index' })
}

function onConnected() {
  loadAgents()
}

onMounted(() => {
  if (connection.isLoggedIn) {
    loadAgents()
  }
})

// Refresh agent list when returning from detail page (sessionCount may have changed)
onShow(() => {
  if (connection.isLoggedIn && agents.value.length > 0) {
    loadAgents()
  }
})

onPullDownRefresh(async () => {
  await loadAgents()
  uni.stopPullDownRefresh()
})
</script>

<style lang="scss" scoped>
.page-agents {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--chat-bg-base, #f5f5f5);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  padding-top: calc(12px + var(--status-bar-height, 0px));
  background: var(--chat-bg-base, #fff);
  border-bottom: 1px solid var(--chat-border, #e5e5e5);
}

.page-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--chat-text-primary, #1a1a2e);
}

.header-right {
  display: flex;
  gap: 8px;
}

.header-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--chat-bg-elevated);
}

.btn-icon {
  font-size: 16px;
}

.search-bar {
  padding: 10px 16px;
}

.search-input {
  width: 100%;
  height: 38px;
  border-radius: var(--chat-radius-sm, 6px);
  background: var(--chat-bg-base, #fff);
  border: 1px solid var(--chat-border, #e5e5e5);
  padding: 0 12px;
  font-size: 14px;
  color: var(--chat-text-primary, #1a1a2e);
}

.agent-scroll {
  flex: 1;
  padding: 0 16px 16px;
}

.loading-state,
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
}

.loading-text,
.empty-text {
  font-size: 14px;
  color: var(--chat-text-tertiary, #999);
}
</style>

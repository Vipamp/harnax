<template>
  <view class="page-detail">
    <view class="detail-header">
      <view class="back-btn" @tap="goBack">
        <text class="back-icon">&lt;</text>
      </view>
      <text class="header-title">{{ agent?.name || agentName }}</text>
    </view>

    <scroll-view scroll-y class="detail-scroll">
      <view v-if="isLoading" class="loading-state">
        <text class="loading-text">{{ t('common.loading') }}</text>
      </view>

      <template v-else-if="agent">
        <view class="detail-card">
          <view class="agent-avatar">
            <text class="avatar-text">{{ agent.name.charAt(0).toUpperCase() }}</text>
          </view>
          <view class="agent-info">
            <text class="agent-name">{{ agent.name }}</text>
            <text class="agent-model">{{ agent.modelName }} / {{ agent.modelProvider }}</text>
          </view>
        </view>

        <view v-if="agent.description" class="section">
          <text class="section-title">{{ t('agents.description') }}</text>
          <text class="section-text">{{ agent.description }}</text>
        </view>

        <view v-if="agent.mcpList.length > 0" class="section">
          <text class="section-title">{{ t('agents.mcpTools') }}</text>
          <view class="tag-list">
            <view v-for="mcp in agent.mcpList" :key="mcp.id" class="tag-item">
              <text class="tag-text">{{ mcp.name }}</text>
            </view>
          </view>
        </view>

        <view v-if="agent.skillList.length > 0" class="section">
          <text class="section-title">{{ t('agents.skills') }}</text>
          <view class="tag-list">
            <view v-for="skill in agent.skillList" :key="skill.id" class="tag-item">
              <text class="tag-text">{{ skill.name }}</text>
            </view>
          </view>
        </view>

        <view class="section">
          <text class="section-title">{{ t('agents.capabilities') }}</text>
          <view class="cap-row">
            <text class="cap-label">{{ t('agents.deepThinking') }}</text>
            <text class="cap-value">{{ agent.enableThink ? t('common.yes') : t('common.no') }}</text>
          </view>
          <view class="cap-row">
            <text class="cap-label">{{ t('agents.webSearch') }}</text>
            <text class="cap-value">{{ agent.enableSearch ? t('common.yes') : t('common.no') }}</text>
          </view>
          <view class="cap-row">
            <text class="cap-label">{{ t('agents.planning') }}</text>
            <text class="cap-value">{{ agent.enablePlan ? t('common.yes') : t('common.no') }}</text>
          </view>
        </view>
      </template>
    </scroll-view>

    <view v-if="agent" class="detail-footer">
      <view class="btn-start" :class="{ 'btn-loading': isCreating }" @tap="handleStartChat">
        <text class="btn-start-text">{{ isCreating ? t('agents.creating') : t('agents.startChat') }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mpGetAgent } from '@/api/admin'
import { useSessionStore } from '@/store/useSessionStore'
import type { MpAgentDetailResponse } from '@/types/api'

const { t } = useI18n()

const agentId = ref(0)
const agentName = ref('')
const agent = ref<MpAgentDetailResponse | null>(null)
const isLoading = ref(false)
const isCreating = ref(false)

onMounted(() => {
  const pages = getCurrentPages()
  const page = pages[pages.length - 1] as any
  agentId.value = parseInt(page.options?.agentId || '0', 10)
  agentName.value = decodeURIComponent(page.options?.agentName || '')
  loadAgent()
})

async function loadAgent() {
  if (!agentId.value) return
  isLoading.value = true
  try {
    const res = await mpGetAgent(agentId.value)
    if (res.code === 200 && res.data) {
      agent.value = res.data
    }
  } catch (e) {
    console.error('[AgentDetail] Failed to load', e)
  } finally {
    isLoading.value = false
  }
}

async function handleStartChat() {
  if (!agent.value || isCreating.value) return
  isCreating.value = true

  try {
    const sessionStore = useSessionStore()
    await sessionStore.createSession(agent.value.id, agent.value.name)

    uni.switchTab({ url: '/pages/chat/index' })
  } catch (e) {
    console.error('[AgentDetail] Failed to create session', e)
  } finally {
    isCreating.value = false
  }
}

function goBack() {
  uni.navigateBack()
}
</script>

<style lang="scss" scoped>
.page-detail {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--chat-bg-base, #f5f5f5);
}

.detail-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  padding-top: calc(12px + var(--status-bar-height, 0px));
  background: var(--chat-bg-base, #fff);
  border-bottom: 1px solid var(--chat-border, #e5e5e5);
  gap: 12px;
}

.back-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.back-icon {
  font-size: 18px;
  color: var(--chat-text-primary, #1a1a2e);
  font-weight: 600;
}

.header-title {
  flex: 1;
  font-size: 16px;
  font-weight: 600;
  color: var(--chat-text-primary, #1a1a2e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-scroll {
  flex: 1;
  padding: 16px;
}

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
}

.loading-text {
  font-size: 14px;
  color: var(--chat-text-tertiary, #999);
}

.detail-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 20px 16px;
  margin-bottom: 16px;
  box-shadow: var(--chat-shadow-sm);
}

.agent-avatar {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--chat-primary, #4f6ef7);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.avatar-text {
  color: var(--chat-text-on-primary);
  font-size: 24px;
  font-weight: 600;
}

.agent-info {
  flex: 1;
  min-width: 0;
}

.agent-name {
  display: block;
  font-size: 18px;
  font-weight: 700;
  color: var(--chat-text-primary, #1a1a2e);
  margin-bottom: 4px;
}

.agent-model {
  display: block;
  font-size: 13px;
  color: var(--chat-text-tertiary, #999);
}

.section {
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 16px;
  margin-bottom: 12px;
  box-shadow: var(--chat-shadow-sm);
}

.section-title {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--chat-text-primary, #1a1a2e);
  margin-bottom: 10px;
}

.section-text {
  font-size: 14px;
  color: var(--chat-text-secondary, #666);
  line-height: 1.6;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-item {
  background: rgba(79, 110, 247, 0.08);
  border-radius: 6px;
  padding: 4px 10px;
}

.tag-text {
  font-size: 13px;
  color: var(--chat-primary, #4f6ef7);
}

.cap-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}

.cap-label {
  font-size: 14px;
  color: var(--chat-text-secondary, #666);
}

.cap-value {
  font-size: 14px;
  color: var(--chat-text-primary, #1a1a2e);
  font-weight: 500;
}

.detail-footer {
  padding: 12px 16px;
  padding-bottom: calc(12px + env(safe-area-inset-bottom, 0px));
  background: var(--chat-bg-base, #fff);
  border-top: 1px solid var(--chat-border, #e5e5e5);
}

.btn-start {
  width: 100%;
  height: 46px;
  border-radius: var(--chat-radius-sm, 6px);
  background: var(--chat-primary, #4f6ef7);
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-loading {
  opacity: 0.7;
}

.btn-start-text {
  color: var(--chat-text-on-primary);
  font-size: 16px;
  font-weight: 600;
}
</style>

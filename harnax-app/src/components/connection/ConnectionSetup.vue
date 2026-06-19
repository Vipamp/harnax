<template>
  <view class="setup-page">
    <view class="setup-card">
      <text class="setup-title">{{ t('setup.title') }}</text>
      <text class="setup-desc">{{ t('setup.description') }}</text>

      <view class="form-group">
        <text class="form-label">{{ t('setup.routerUrl') }}</text>
        <input
          v-model="routerUrl"
          class="form-input"
          :placeholder="t('setup.routerUrlPlaceholder')"
          type="text"
        />
      </view>

      <view class="form-group">
        <text class="form-label">{{ t('setup.apiKey') }}</text>
        <input
          v-model="apiKeyInput"
          class="form-input"
          :placeholder="t('setup.apiKeyPlaceholder')"
          :password="true"
          type="text"
        />
      </view>

      <view class="btn-connect" :class="{ 'btn-loading': isTesting }" @tap="handleConnect">
        <text class="btn-connect-text">
          {{ isTesting ? t('setup.testing') : t('setup.connect') }}
        </text>
      </view>

      <view v-if="error" class="error-msg">
        <text class="error-text">{{ error }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useConnectionStore } from '@/store/useConnectionStore'
import { checkHealth } from '@/api/router'

const emit = defineEmits<{
  connected: []
}>()

const { t } = useI18n()
const connection = useConnectionStore()

const routerUrl = ref(connection.routerUrl)
const apiKeyInput = ref(connection.apiKey)
const isTesting = ref(false)
const error = ref('')

async function handleConnect() {
  if (!routerUrl.value.trim() || !apiKeyInput.value.trim()) {
    error.value = t('setup.requiredFields')
    return
  }

  error.value = ''
  isTesting.value = true

  connection.routerUrl = routerUrl.value.trim()
  connection.apiKey = apiKeyInput.value.trim()

  try {
    const ok = await checkHealth()
    if (ok) {
      connection.setConnected(true)
      connection.save()
      emit('connected')
    } else {
      error.value = t('setup.connectFailed')
    }
  } catch (e) {
    error.value = (e as Error).message || t('setup.connectFailed')
  } finally {
    isTesting.value = false
  }
}
</script>

<style lang="scss" scoped>
.setup-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: var(--chat-bg-base, #f5f5f5);
}

.setup-card {
  width: 100%;
  max-width: 420px;
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 32px 24px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
}

.setup-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--chat-text-primary, #1a1a2e);
  display: block;
  margin-bottom: 8px;
}

.setup-desc {
  font-size: 13px;
  color: var(--chat-text-secondary, #666);
  line-height: 1.5;
  display: block;
  margin-bottom: 28px;
}

.form-group {
  margin-bottom: 18px;
}

.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--chat-text-primary, #1a1a2e);
  display: block;
  margin-bottom: 6px;
}

.form-input {
  width: 100%;
  height: 42px;
  border: 1px solid var(--chat-border, #e5e5e5);
  border-radius: var(--chat-radius-sm, 6px);
  padding: 0 12px;
  font-size: 14px;
  color: var(--chat-text-primary, #1a1a2e);
  background: var(--chat-bg-base, #fff);
}

.btn-connect {
  width: 100%;
  height: 44px;
  border-radius: var(--chat-radius-sm, 6px);
  background: var(--chat-primary, #4f6ef7);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 8px;
}

.btn-loading {
  opacity: 0.7;
}

.btn-connect-text {
  color: #fff;
  font-size: 15px;
  font-weight: 500;
}

.error-msg {
  margin-top: 14px;
  padding: 8px 12px;
  border-radius: var(--chat-radius-sm, 6px);
  background: rgba(239, 68, 68, 0.08);
}

.error-text {
  font-size: 13px;
  color: var(--chat-error, #ef4444);
}
</style>

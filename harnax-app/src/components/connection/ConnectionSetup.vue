<template>
  <view class="setup-page">
    <view class="setup-card">
      <!-- Step 1: Server URL -->
      <template v-if="step === 'server'">
        <view class="setup-title">{{ t('setup.serverTitle') }}</view>
        <view class="setup-desc">{{ t('setup.serverDesc') }}</view>

        <view class="form-group">
          <view class="form-label">{{ t('setup.serverUrl') }}</view>
          <input
            v-model="serverUrl"
            class="form-input"
            :placeholder="t('setup.serverUrlPlaceholder')"
            type="text"
          />
        </view>

        <view class="btn-connect" :class="{ 'btn-loading': isTesting }" @tap="handleTestServer">
          <view class="btn-connect-text">{{ isTesting ? t('setup.testing') : t('setup.next') }}</view>
        </view>

        <view v-if="error" class="error-msg">
          <view class="error-text">{{ error }}</view>
        </view>
      </template>

      <!-- Step 2: Login -->
      <template v-if="step === 'login'">
        <view class="setup-title">{{ t('setup.title') }}</view>
        <view class="setup-desc">{{ serverUrl }}</view>

        <view class="form-group">
          <view class="form-label">{{ t('setup.username') }}</view>
          <input
            v-model="username"
            class="form-input"
            :placeholder="t('setup.usernamePlaceholder')"
            type="text"
          />
        </view>

        <view class="form-group">
          <view class="form-label">{{ t('setup.password') }}</view>
          <input
            v-model="password"
            class="form-input"
            :placeholder="t('setup.passwordPlaceholder')"
            :password="true"
            type="text"
          />
        </view>

        <view class="btn-connect" :class="{ 'btn-loading': isTesting }" @tap="handleLogin">
          <view class="btn-connect-text">{{ isTesting ? t('setup.logging') : t('setup.login') }}</view>
        </view>

        <view v-if="error" class="error-msg">
          <view class="error-text">{{ error }}</view>
        </view>

        <view class="back-link" @tap="step = 'server'">
          <text class="back-link-text">{{ t('setup.changeServer') }}</text>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useConnectionStore } from '@/store/useConnectionStore'
import { mpLogin } from '@/api/admin'
import { sha256 } from '@/utils/crypto'

const { t } = useI18n()

const emit = defineEmits<{
  connected: []
}>()

const connection = useConnectionStore()

const step = ref<'server' | 'login'>('server')
const serverUrl = ref(connection.adminUrl || '')
const username = ref(connection.username || '')
const password = ref('')
const isTesting = ref(false)
const error = ref('')

onMounted(() => {
  if (connection.adminUrl) {
    step.value = 'login'
  }
})

async function handleTestServer() {
  const url = serverUrl.value.trim()
  if (!url) {
    error.value = t('setup.requiredServerUrl')
    return
  }

  error.value = ''
  isTesting.value = true

  try {
    const healthUrl = `${url.replace(/\/+$/, '')}/api/admin/mp/auth/captcha`
    await new Promise<void>((resolve, reject) => {
      uni.request({
        url: healthUrl,
        method: 'GET',
        timeout: 10000,
        success: (res) => {
          if (res.statusCode >= 200 && res.statusCode < 500) {
            resolve()
          } else {
            reject(new Error(`HTTP ${res.statusCode}`))
          }
        },
        fail: (err) => reject(new Error(err.errMsg || 'Connection failed')),
      })
    })

    connection.setServerUrl(url)
    step.value = 'login'
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message || t('setup.serverUnreachable')
  } finally {
    isTesting.value = false
  }
}

async function handleLogin() {
  if (!username.value.trim() || !password.value.trim()) {
    error.value = t('setup.requiredFields')
    return
  }

  error.value = ''
  isTesting.value = true

  try {
    const hashedPassword = await sha256(password.value)
    const res = await mpLogin({
      username: username.value.trim(),
      password: hashedPassword,
    })

    if (res.code === 200 && res.data) {
      connection.setLoginResult(
        res.data.accessToken,
        res.data.routerUrl,
        res.data.routerApiKey,
        username.value.trim(),
        res.data.userInfo.userId,
        res.data.userInfo.nickname || '',
        res.data.expiresIn,
      )
      emit('connected')
    } else {
      error.value = res.message || t('setup.loginFailed')
    }
  } catch (e) {
    error.value = (e as Error).message || t('setup.loginFailed')
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
  box-shadow: var(--chat-shadow-lg);
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  color: var(--chat-text-on-primary);
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

.back-link {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}

.back-link-text {
  font-size: 13px;
  color: var(--chat-primary, #4f6ef7);
}
</style>

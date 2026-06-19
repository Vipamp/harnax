<template>
  <view class="setup-page">
    <view class="setup-card">
      <view class="setup-title">登录</view>
      <view class="setup-desc">配置服务地址并登录以开始对话</view>

      <view class="form-group">
        <view class="form-label">管理后台地址</view>
        <input
          v-model="adminUrlInput"
          class="form-input"
          placeholder="请输入管理后台服务地址"
          type="text"
        />
      </view>

      <view class="form-group">
        <view class="form-label">Router 地址</view>
        <input
          v-model="routerUrl"
          class="form-input"
          placeholder="请输入 Router 服务地址"
          type="text"
        />
      </view>

      <view class="form-group">
        <view class="form-label">API Key</view>
        <input
          v-model="apiKeyInput"
          class="form-input"
          placeholder="请输入 API Key"
          :password="true"
          type="text"
        />
      </view>

      <view class="form-group">
        <view class="form-label">用户名</view>
        <input
          v-model="username"
          class="form-input"
          placeholder="请输入用户名"
          type="text"
        />
      </view>

      <view class="form-group">
        <view class="form-label">密码</view>
        <input
          v-model="password"
          class="form-input"
          placeholder="请输入密码"
          :password="true"
          type="text"
        />
      </view>

      <view class="btn-connect" :class="{ 'btn-loading': isTesting }" @tap="handleLogin">
        <view class="btn-connect-text">{{ isTesting ? '登录中...' : '登录' }}</view>
      </view>

      <view v-if="error" class="error-msg">
        <view class="error-text">{{ error }}</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useConnectionStore } from '@/store/useConnectionStore'
import { mpLogin } from '@/api/admin'

const emit = defineEmits<{
  connected: []
}>()

const connection = useConnectionStore()

const adminUrlInput = ref(connection.adminUrl)
const routerUrl = ref(connection.routerUrl)
const apiKeyInput = ref(connection.apiKey)
const username = ref(connection.username)
const password = ref('')
const isTesting = ref(false)
const error = ref('')

async function handleLogin() {
  if (!adminUrlInput.value.trim() || !routerUrl.value.trim()) {
    error.value = '请填写服务地址'
    return
  }
  if (!username.value.trim() || !password.value.trim()) {
    error.value = '请填写所有必填项'
    return
  }

  error.value = ''
  isTesting.value = true

  // Save connection config first
  connection.adminUrl = adminUrlInput.value.trim()
  connection.routerUrl = routerUrl.value.trim()
  connection.apiKey = apiKeyInput.value.trim()
  connection.username = username.value.trim()

  try {
    // SHA-256 hash the password (backend stores BCrypt(SHA-256(plain)))
    const hashedPassword = await sha256(password.value)

    // Login via admin API
    const res = await mpLogin({
      username: username.value.trim(),
      password: hashedPassword,
    })

    if (res.code === 200 && res.data?.accessToken) {
      connection.setToken(res.data.accessToken)
      connection.setConnected(true)
      connection.save()
      emit('connected')
    } else {
      error.value = res.message || '登录失败，请检查用户名和密码'
    }
  } catch (e) {
    error.value = (e as Error).message || '登录失败，请检查用户名和密码'
  } finally {
    isTesting.value = false
  }
}

async function sha256(message: string): Promise<string> {
  const msgBuffer = new TextEncoder().encode(message)
  const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('')
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

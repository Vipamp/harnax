<template>
  <view class="page-profile">
    <view class="profile-header">
      <view class="back-btn" @tap="goBack">
        <text class="back-icon">&lt;</text>
      </view>
      <text class="header-title">{{ t('profile.title') }}</text>
    </view>

    <scroll-view scroll-y class="profile-scroll">
      <view v-if="profile" class="profile-card">
        <view class="profile-avatar">
          <text class="avatar-text">{{ (profile.nickname || profile.username).charAt(0).toUpperCase() }}</text>
        </view>
        <text class="profile-name">{{ profile.nickname || profile.username }}</text>
        <text class="profile-username">@{{ profile.username }}</text>
      </view>

      <view v-if="profile" class="info-section">
        <view v-if="profile.email" class="info-row">
          <text class="info-label">{{ t('profile.email') }}</text>
          <text class="info-value">{{ profile.email }}</text>
        </view>
        <view v-if="profile.phone" class="info-row">
          <text class="info-label">{{ t('profile.phone') }}</text>
          <text class="info-value">{{ profile.phone }}</text>
        </view>
      </view>

      <view class="action-section">
        <view class="action-item" @tap="handleChangePassword">
          <text class="action-text">{{ t('profile.changePassword') }}</text>
          <text class="action-arrow">&gt;</text>
        </view>
        <view class="action-item" @tap="handleLogout">
          <text class="action-text action-danger">{{ t('profile.logout') }}</text>
          <text class="action-arrow">&gt;</text>
        </view>
      </view>
    </scroll-view>

    <view v-if="showPasswordModal" class="modal-overlay" @tap="showPasswordModal = false">
      <view class="modal-card" @tap.stop>
        <text class="modal-title">{{ t('profile.changePassword') }}</text>

        <view class="form-group">
          <text class="form-label">{{ t('profile.oldPassword') }}</text>
          <input v-model="oldPassword" class="form-input" :password="true" type="text" />
        </view>
        <view class="form-group">
          <text class="form-label">{{ t('profile.newPassword') }}</text>
          <input v-model="newPassword" class="form-input" :password="true" type="text" />
        </view>
        <view class="form-group">
          <text class="form-label">{{ t('profile.confirmPassword') }}</text>
          <input v-model="confirmPassword" class="form-input" :password="true" type="text" />
        </view>

        <view v-if="pwdError" class="error-msg">
          <text class="error-text">{{ pwdError }}</text>
        </view>

        <view class="modal-actions">
          <view class="modal-btn modal-cancel" @tap="showPasswordModal = false">
            <text class="modal-btn-text">{{ t('common.cancel') }}</text>
          </view>
          <view class="modal-btn modal-confirm" :class="{ 'btn-loading': isChangingPwd }" @tap="submitPasswordChange">
            <text class="modal-btn-text-primary">{{ t('common.confirm') }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mpGetProfile, mpChangePassword, mpLogout } from '@/api/admin'
import { useConnectionStore } from '@/store/useConnectionStore'
import { sha256 } from '@/utils/crypto'
import type { MpUserProfileResponse } from '@/types/api'

const { t } = useI18n()

const connection = useConnectionStore()
const profile = ref<MpUserProfileResponse | null>(null)
const showPasswordModal = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const pwdError = ref('')
const isChangingPwd = ref(false)

onMounted(() => {
  loadProfile()
})

async function loadProfile() {
  try {
    const res = await mpGetProfile()
    if (res.code === 200 && res.data) {
      profile.value = res.data
    }
  } catch (e) {
    console.error('[Profile] Failed to load', e)
  }
}

function handleChangePassword() {
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  pwdError.value = ''
  showPasswordModal.value = true
}

async function submitPasswordChange() {
  if (!oldPassword.value || !newPassword.value) {
    pwdError.value = t('profile.pwdRequired')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    pwdError.value = t('profile.pwdMismatch')
    return
  }
  if (newPassword.value.length < 6) {
    pwdError.value = t('profile.pwdTooShort')
    return
  }

  pwdError.value = ''
  isChangingPwd.value = true

  try {
    const [oldHash, newHash] = await Promise.all([
      sha256(oldPassword.value),
      sha256(newPassword.value),
    ])

    const res = await mpChangePassword({ oldPassword: oldHash, newPassword: newHash })
    if (res.code === 200) {
      showPasswordModal.value = false
      uni.showToast({ title: t('profile.pwdChanged'), icon: 'success' })
    } else {
      pwdError.value = res.message || t('profile.pwdChangeFailed')
    }
  } catch (e) {
    pwdError.value = (e as Error).message || t('profile.pwdChangeFailed')
  } finally {
    isChangingPwd.value = false
  }
}

async function handleLogout() {
  uni.showModal({
    title: t('profile.logoutConfirmTitle'),
    content: t('profile.logoutConfirm'),
    success: async (res) => {
      if (res.confirm) {
        try {
          await mpLogout()
        } catch {
          // ignore
        }
        connection.clearAuth()
        uni.redirectTo({ url: '/pages/setup/index' })
      }
    },
  })
}

function goBack() {
  uni.navigateBack()
}
</script>

<style lang="scss" scoped>
.page-profile {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--chat-bg-base, #f5f5f5);
}

.profile-header {
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
}

.profile-scroll {
  flex: 1;
  padding: 16px;
}

.profile-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 24px 16px;
  margin-bottom: 16px;
  box-shadow: var(--chat-shadow-sm);
}

.profile-avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--chat-primary, #4f6ef7);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
}

.avatar-text {
  color: var(--chat-text-on-primary);
  font-size: 28px;
  font-weight: 600;
}

.profile-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--chat-text-primary, #1a1a2e);
  margin-bottom: 4px;
}

.profile-username {
  font-size: 13px;
  color: var(--chat-text-tertiary, #999);
}

.info-section {
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 4px 16px;
  margin-bottom: 16px;
  box-shadow: var(--chat-shadow-sm);
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--chat-border, #f0f0f0);
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  font-size: 14px;
  color: var(--chat-text-secondary, #666);
}

.info-value {
  font-size: 14px;
  color: var(--chat-text-primary, #1a1a2e);
}

.action-section {
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  overflow: hidden;
  box-shadow: var(--chat-shadow-sm);
}

.action-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--chat-border, #f0f0f0);
}

.action-item:last-child {
  border-bottom: none;
}

.action-text {
  font-size: 15px;
  color: var(--chat-text-primary, #1a1a2e);
}

.action-danger {
  color: var(--chat-error, #ef4444);
}

.action-arrow {
  font-size: 14px;
  color: var(--chat-text-tertiary, #ccc);
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
  padding: 24px;
}

.modal-card {
  width: 100%;
  max-width: 360px;
  background: var(--chat-bg-base, #fff);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 24px;
}

.modal-title {
  display: block;
  font-size: 17px;
  font-weight: 600;
  color: var(--chat-text-primary, #1a1a2e);
  margin-bottom: 16px;
}

.form-group {
  margin-bottom: 14px;
}

.form-label {
  display: block;
  font-size: 13px;
  color: var(--chat-text-secondary, #666);
  margin-bottom: 4px;
}

.form-input {
  width: 100%;
  height: 38px;
  border: 1px solid var(--chat-border, #e5e5e5);
  border-radius: var(--chat-radius-sm, 6px);
  padding: 0 10px;
  font-size: 14px;
  color: var(--chat-text-primary, #1a1a2e);
}

.error-msg {
  margin-bottom: 12px;
}

.error-text {
  font-size: 13px;
  color: var(--chat-error, #ef4444);
}

.modal-actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}

.modal-btn {
  flex: 1;
  height: 40px;
  border-radius: var(--chat-radius-sm, 6px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-cancel {
  background: var(--chat-border, #e5e5e5);
}

.modal-btn-text {
  font-size: 14px;
  color: var(--chat-text-primary, #1a1a2e);
}

.modal-confirm {
  background: var(--chat-primary, #4f6ef7);
}

.modal-btn-text-primary {
  font-size: 14px;
  color: var(--chat-text-on-primary);
}

.btn-loading {
  opacity: 0.7;
}
</style>

<template>
  <view class="theme-toggle" @tap="toggle">
    <text class="theme-icon">{{ isDark ? '🌙' : '☀️' }}</text>
    <text class="theme-label">{{ isDark ? t('common.darkMode') : t('common.lightMode') }}</text>
    <view class="toggle-track" :class="{ active: isDark }">
      <view class="toggle-thumb" />
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getStorage, setStorage } from '@/utils/storage'

const { t } = useI18n()
const THEME_KEY = 'theme_mode'

const isDark = ref(false)

onMounted(() => {
  const saved = getStorage<string>(THEME_KEY)
  if (saved === 'dark') {
    isDark.value = true
  } else if (saved === 'light') {
    isDark.value = false
  } else {
    // Follow system theme
    isDark.value = detectSystemDark()
  }
  applyTheme()

  // Listen for system theme changes
  if (typeof uni !== 'undefined' && uni.onThemeChange) {
    uni.onThemeChange((res: { theme: string }) => {
      const saved = getStorage<string>(THEME_KEY)
      if (!saved || saved === 'system') {
        isDark.value = res.theme === 'dark'
        applyTheme()
      }
    })
  }
})

watch(isDark, () => {
  applyTheme()
  setStorage(THEME_KEY, isDark.value ? 'dark' : 'light')
})

function toggle() {
  isDark.value = !isDark.value
}

function detectSystemDark(): boolean {
  try {
    const sysInfo = uni.getSystemInfoSync()
    return (sysInfo as any).osTheme === 'dark'
  } catch {
    return false
  }
}

function applyTheme() {
  // #ifdef H5
  const html = document.documentElement
  if (isDark.value) {
    html.classList.add('dark')
  } else {
    html.classList.remove('dark')
  }
  // #endif

  // #ifdef APP-PLUS
  // For app, we use navigationBarBackgroundColor change
  // The CSS variables handle the visual theme
  // #endif
}
</script>

<style lang="scss" scoped>
.theme-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
}

.theme-icon {
  font-size: 16px;
  margin-right: 8px;
}

.theme-label {
  flex: 1;
  font-size: 15px;
  color: var(--chat-text-primary, #1a1a2e);
}

.toggle-track {
  width: 44px;
  height: 24px;
  border-radius: 12px;
  background: var(--chat-border, #e5e5e5);
  position: relative;
  transition: background 0.2s;

  &.active {
    background: var(--chat-primary, #4f6ef7);
  }
}

.toggle-thumb {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 2px;
  left: 2px;
  transition: transform 0.2s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.15);

  .active & {
    transform: translateX(20px);
  }
}
</style>

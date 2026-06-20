<template>
  <view class="input-area">
    <view class="input-row">
      <view class="input-wrap">
        <textarea
          v-model="inputText"
          class="input-field"
          :placeholder="t('chat.inputPlaceholder')"
          :auto-height="true"
          :maxlength="10000"
          :disabled="isStreaming"
          @confirm="handleSend"
        />
      </view>
      <view v-if="isStreaming" class="btn-stop" @tap="$emit('stop')">
        <text class="btn-stop-text">■</text>
      </view>
      <view v-else class="btn-send" :class="{ 'btn-disabled': !canSend }" @tap="handleSend">
        <text class="btn-send-text">↑</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  isStreaming: boolean
}>()

const emit = defineEmits<{
  send: [text: string]
  stop: []
}>()

const inputText = ref('')

const canSend = computed(() => inputText.value.trim().length > 0)

function handleSend() {
  const text = inputText.value.trim()
  if (!text || props.isStreaming) return
  emit('send', text)
  inputText.value = ''
}
</script>

<style lang="scss" scoped>
.input-area {
  padding: 8px 12px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom));
  background: var(--chat-bg-container);
  border-top: 1px solid var(--chat-border);
}

.input-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.input-wrap {
  flex: 1;
  background: var(--chat-bg-elevated);
  border-radius: var(--chat-radius-lg, 12px);
  padding: 8px 14px;
}

.input-field {
  width: 100%;
  font-size: 15px;
  line-height: 1.5;
  color: var(--chat-text-primary);
  min-height: 24px;
  max-height: 120px;
}

.btn-send {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--chat-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.btn-disabled {
  opacity: 0.4;
}

.btn-send-text {
  color: var(--chat-text-on-primary);
  font-size: 18px;
  font-weight: 700;
}

.btn-stop {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--chat-error);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.btn-stop-text {
  color: var(--chat-text-on-primary);
  font-size: 14px;
}
</style>

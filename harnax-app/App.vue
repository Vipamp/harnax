<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { useConnectionStore } from './src/store/useConnectionStore'
import { useSessionStore } from './src/store/useSessionStore'

onLaunch(() => {
  const connectionStore = useConnectionStore()
  connectionStore.loadFromStorage()

  const sessionStore = useSessionStore()
  sessionStore.loadFromStorage()
})
</script>

<style lang="scss">
@import './src/styles/global.scss';
</style>

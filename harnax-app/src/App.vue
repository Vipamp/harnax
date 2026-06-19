<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { useConnectionStore } from './store/useConnectionStore'
import { useSessionStore } from './store/useSessionStore'

onLaunch(() => {
  const connectionStore = useConnectionStore()
  connectionStore.loadFromStorage()

  const sessionStore = useSessionStore()
  sessionStore.loadFromStorage()
})
</script>

<style lang="scss">
@import './styles/global.scss';
</style>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { resolveGraphExplorerUrl } from './graphExplorer'

const props = defineProps<{ open: boolean }>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'close'): void
}>()

const graphExplorerUrl = resolveGraphExplorerUrl(import.meta.env.VITE_NANWANG_GRAPH_EXPLORER_URL)
const viewRef = ref<HTMLElement | null>(null)
const iframeLoaded = ref(false)
const iframeTimedOut = ref(false)
const previousActiveElement = ref<HTMLElement | null>(null)
const previousBodyOverflow = ref('')
let loadTimeout: ReturnType<typeof setTimeout> | null = null

function clearLoadTimeout() {
  if (loadTimeout !== null) {
    clearTimeout(loadTimeout)
    loadTimeout = null
  }
}

function beginIframeLoad() {
  clearLoadTimeout()
  iframeLoaded.value = false
  iframeTimedOut.value = false
  loadTimeout = setTimeout(() => {
    if (!iframeLoaded.value) iframeTimedOut.value = true
  }, 8000)
}

function handleIframeLoad() {
  iframeLoaded.value = true
  iframeTimedOut.value = false
  clearLoadTimeout()
}

function close() {
  emit('update:open', false)
  emit('close')
}

watch(
  () => props.open,
  async (isOpen) => {
    clearLoadTimeout()
    if (!isOpen) {
      document.body.style.overflow = previousBodyOverflow.value
      previousActiveElement.value?.focus()
      return
    }

    previousActiveElement.value = document.activeElement instanceof HTMLElement ? document.activeElement : null
    previousBodyOverflow.value = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    beginIframeLoad()
    await nextTick()
    viewRef.value?.focus()
  },
)

onBeforeUnmount(() => {
  clearLoadTimeout()
  document.body.style.overflow = previousBodyOverflow.value
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      ref="viewRef"
      class="graph-explorer-modal"
      role="dialog"
      aria-modal="true"
      aria-label="数据管理"
      tabindex="-1"
      @keydown.esc="close"
    >
      <div class="graph-explorer-modal-body">
        <div v-if="!iframeLoaded && !iframeTimedOut" class="graph-explorer-modal-loading" role="status">
          正在加载数据管理…
        </div>
        <div v-if="iframeTimedOut" class="graph-explorer-modal-error" role="alert">
          <p>数据管理页面加载时间较长，请检查 8765 服务是否正常运行。</p>
          <a :href="graphExplorerUrl" target="_blank" rel="noopener noreferrer">新窗口打开数据管理</a>
        </div>
        <iframe
          v-show="!iframeTimedOut"
          class="graph-explorer-modal-frame"
          :src="graphExplorerUrl"
          title="数据管理"
          allow="fullscreen"
          @load="handleIframeLoad"
        />
      </div>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.graph-explorer-modal {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: #f6f8fb;
}

.graph-explorer-modal-body {
  position: relative;
  min-height: 0;
  flex: 1;
}

.graph-explorer-modal-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #f6f8fb;
}

.graph-explorer-modal-loading,
.graph-explorer-modal-error {
  position: absolute;
  z-index: 1;
  inset: 0;
  display: grid;
  place-content: center;
  gap: 10px;
  background: rgb(246 248 251 / 88%);
  color: #64748b;
  font-size: 13px;
  text-align: center;
  pointer-events: none;
}

.graph-explorer-modal-error p { margin: 0; }

.graph-explorer-modal-error a {
  color: #2563eb;
  pointer-events: auto;
}
</style>

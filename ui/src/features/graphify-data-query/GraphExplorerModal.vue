<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import { resolveGraphExplorerUrl } from './graphExplorer'

const props = defineProps<{ open: boolean }>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'close'): void
}>()

const graphExplorerUrl = resolveGraphExplorerUrl(import.meta.env.VITE_GRAPH_EXPLORER_URL)
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
      aria-label="医保知识图谱"
      tabindex="-1"
      @keydown.esc="close"
    >
      <header class="graph-explorer-modal-header">
        <div class="graph-explorer-modal-title">
          <span>医保知识图谱</span>
          <small>完整页面</small>
        </div>
        <div class="graph-explorer-modal-actions">
          <a :href="graphExplorerUrl" target="_blank" rel="noopener noreferrer">新窗口打开</a>
          <button type="button" aria-label="关闭知识图谱" title="关闭知识图谱" @click="close">
            <CloseOutlined />
          </button>
        </div>
      </header>

      <div class="graph-explorer-modal-body">
        <div v-if="!iframeLoaded && !iframeTimedOut" class="graph-explorer-modal-loading" role="status">
          正在加载知识图谱…
        </div>
        <div v-if="iframeTimedOut" class="graph-explorer-modal-error" role="alert">
          <p>知识图谱页面加载时间较长，请检查 8770 服务是否正常运行。</p>
          <a :href="graphExplorerUrl" target="_blank" rel="noopener noreferrer">新窗口打开知识图谱</a>
        </div>
        <iframe
          v-show="!iframeTimedOut"
          class="graph-explorer-modal-frame"
          :src="graphExplorerUrl"
          title="医保知识图谱"
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

.graph-explorer-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 52px;
  padding: 0 18px 0 22px;
  border-bottom: 1px solid #e2e8f0;
  background: #fff;
  color: #0f172a;
}

.graph-explorer-modal-title,
.graph-explorer-modal-actions {
  display: flex;
  align-items: center;
}

.graph-explorer-modal-title {
  gap: 10px;
  font-size: 15px;
  font-weight: 650;
}

.graph-explorer-modal-title small {
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
}

.graph-explorer-modal-actions {
  gap: 12px;
}

.graph-explorer-modal-actions a {
  color: #2563eb;
  font-size: 12px;
  text-decoration: none;
}

.graph-explorer-modal-actions a:hover { text-decoration: underline; }

.graph-explorer-modal-actions button {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
}

.graph-explorer-modal-actions button:hover {
  background: #f1f5f9;
  color: #0f172a;
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

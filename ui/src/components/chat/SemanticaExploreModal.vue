<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'

/**
 * The local medical knowledge graph Explorer is intentionally fixed to port
 * 8000. Keeping the URL here makes the chat entry point deterministic for the
 * local deployment instead of relying on the browser's current origin.
 */
const SEMANTICA_EXPLORE_URL = 'http://127.0.0.1:8000/?workspace=explore&view=graph'

const props = defineProps<{ open: boolean }>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

const dialogRef = ref<HTMLElement | null>(null)
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
}

watch(
  () => props.open,
  async (isOpen) => {
    clearLoadTimeout()
    if (!isOpen) {
      document.body.style.overflow = previousBodyOverflow.value
      if (previousActiveElement.value?.isConnected) previousActiveElement.value.focus()
      return
    }

    previousActiveElement.value = document.activeElement instanceof HTMLElement ? document.activeElement : null
    previousBodyOverflow.value = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    beginIframeLoad()
    await nextTick()
    dialogRef.value?.focus()
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
      ref="dialogRef"
      class="semantica-explore-modal"
      role="dialog"
      aria-modal="true"
      aria-label="知识图谱"
      tabindex="-1"
      @keydown.esc="close"
    >
      <header class="semantica-explore-modal-header">
        <div class="semantica-explore-modal-title">
          <span class="semantica-explore-mark" aria-hidden="true">S</span>
          <div>
            <strong>知识图谱</strong>
            <small>智能医生知识关联</small>
          </div>
        </div>
        <div class="semantica-explore-modal-actions">
          <a :href="SEMANTICA_EXPLORE_URL" target="_blank" rel="noopener noreferrer">新窗口打开</a>
          <button type="button" aria-label="返回对话" title="返回对话" @click="close">
            <CloseOutlined />
            <span>返回对话</span>
          </button>
        </div>
      </header>

      <div class="semantica-explore-modal-body">
        <div v-if="!iframeLoaded && !iframeTimedOut" class="semantica-explore-modal-loading" role="status">
          正在打开知识图谱…
        </div>
        <div v-if="iframeTimedOut" class="semantica-explore-modal-error" role="alert">
          <p>知识图谱加载时间较长，请确认本机 8000 服务已启动。</p>
          <a :href="SEMANTICA_EXPLORE_URL" target="_blank" rel="noopener noreferrer">在新窗口重试</a>
        </div>
        <iframe
          v-show="!iframeTimedOut"
          class="semantica-explore-modal-frame"
          :src="SEMANTICA_EXPLORE_URL"
          title="知识图谱"
          allow="fullscreen"
          @load="handleIframeLoad"
        />
      </div>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.semantica-explore-modal {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: #f6f8fb;
}

.semantica-explore-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 56px;
  padding: 0 18px 0 22px;
  border-bottom: 1px solid #e2e8f0;
  background: #fff;
  color: #10233d;
}

.semantica-explore-modal-title,
.semantica-explore-modal-actions {
  display: flex;
  align-items: center;
}

.semantica-explore-modal-title {
  gap: 10px;
}

.semantica-explore-modal-title strong,
.semantica-explore-modal-title small {
  display: block;
}

.semantica-explore-modal-title strong {
  font-size: 15px;
  font-weight: 650;
  line-height: 1.2;
}

.semantica-explore-modal-title small {
  margin-top: 3px;
  color: #718096;
  font-size: 11px;
  font-weight: 500;
}

.semantica-explore-mark {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 9px;
  background: #0f74ff;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  box-shadow: 0 4px 12px rgba(15, 116, 255, 0.22);
}

.semantica-explore-modal-actions {
  gap: 14px;
}

.semantica-explore-modal-actions a {
  color: #2563eb;
  font-size: 12px;
  text-decoration: none;
}

.semantica-explore-modal-actions a:hover {
  text-decoration: underline;
}

.semantica-explore-modal-actions button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
  background: #fff;
  color: #526173;
  cursor: pointer;
  font-size: 12px;
  transition: border-color 0.2s ease, background-color 0.2s ease, color 0.2s ease;
}

.semantica-explore-modal-actions button:hover {
  border-color: #b8c8dd;
  background: #f5f8fc;
  color: #0f74ff;
}

.semantica-explore-modal-actions button:focus-visible {
  outline: 2px solid #0f74ff;
  outline-offset: 2px;
}

.semantica-explore-modal-body {
  position: relative;
  flex: 1;
  min-height: 0;
}

.semantica-explore-modal-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}

.semantica-explore-modal-loading,
.semantica-explore-modal-error {
  position: absolute;
  z-index: 1;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  color: #526173;
  font-size: 13px;
  pointer-events: none;
}

.semantica-explore-modal-error {
  background: #f6f8fb;
  pointer-events: auto;
}

.semantica-explore-modal-error p {
  margin: 0;
}

.semantica-explore-modal-error a {
  color: #2563eb;
  font-size: 12px;
}

@media (max-width: 768px) {
  .semantica-explore-modal-header {
    flex-basis: 52px;
    padding: 0 10px 0 14px;
  }

  .semantica-explore-modal-title small {
    display: none;
  }

  .semantica-explore-modal-actions {
    gap: 8px;
  }

  .semantica-explore-modal-actions a {
    display: none;
  }

  .semantica-explore-modal-actions button span {
    display: none;
  }
}
</style>

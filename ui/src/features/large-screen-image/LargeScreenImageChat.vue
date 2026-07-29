<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import * as agentApi from '@/api/agent'
import * as attachApi from '@/api/attach'
import * as chatSessionApi from '@/api/chatSession'
import { useAgentDetail } from '@/composables/chat/useAgentDetail'
import { useChatStream } from '@/composables/chat/useChatStream'
import { useCurrentSession } from '@/composables/chat/useCurrentSession'
import { useSessions } from '@/composables/chat/useSessions'
import type { AgentDefinitionVO, ChatMessageVO, ChatSessionVO } from '@/types'
import { LARGE_SCREEN_IMAGE_AGENT_CODE, resolveLargeScreenImageAgent } from './agent'
import { parseGeneratedImages } from './gallery'
import { validateReferenceFiles } from './upload'

type ReferenceImage =
  | { kind: 'attachment'; fileId: string; fileName: string }
  | { kind: 'output'; imageUrl: string }
  | null

const agent = ref<AgentDefinitionVO | null>(null)
const loading = ref(true)
const loadError = ref('')
const prompt = ref('')
const ratio = ref('16:9')
const quality = ref('standard')
const reference = ref<ReferenceImage>(null)
const uploading = ref(false)
const dragActive = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const agentId = computed(() => agent.value ? String(agent.value.id) : '')
const referenceFileIds = computed(() => reference.value?.kind === 'attachment' ? [reference.value.fileId] : [])
const memoryActive = ref(false)
const planActive = ref(false)
const toolProcessActive = ref(false)
const { agentDetail } = useAgentDetail(agentId)
const {
  pinnedSessions,
  otherSessions,
  loadSessions,
  createSession,
  deleteSession,
} = useSessions(agentId)
const {
  currentSessionId,
  messagesList,
  selectSession,
  resetSession,
} = useCurrentSession(agentId)
const {
  streamingContent,
  streamingRole,
  isRunning,
  sendMessage,
  disconnect: disconnectStream,
} = useChatStream(
  agentId,
  agentDetail,
  currentSessionId,
  referenceFileIds,
  memoryActive,
  planActive,
  toolProcessActive,
  (chatMsg: ChatMessageVO) => messagesList.value.push(chatMsg),
)

const sessions = computed(() => [...pinnedSessions.value, ...otherSessions.value])
const generatedImages = computed(() => messagesList.value.flatMap((item) =>
  item.role === 'assistant' && item.content ? parseGeneratedImages(item.content) : [],
))

const visibleMessages = computed(() => {
  const persisted = messagesList.value.filter((item) => item.role !== 'system' && item.role !== 'thinking' && item.content)
  if (streamingContent.value && streamingRole.value !== 'thinking') {
    return [...persisted, { id: 'streaming', role: streamingRole.value, content: streamingContent.value }]
  }
  return persisted
})

function actionText(action: 'analyze' | 'generate') {
  const referenceFileId = reference.value?.kind === 'attachment' ? reference.value.fileId : ''
  const referenceImageUrl = reference.value?.kind === 'output' ? reference.value.imageUrl : ''
  if (action === 'analyze') {
    return `[large-screen-image action=analyze referenceFileId=${referenceFileId}]\n请分析这张参考图，返回可编辑的大屏生图提示词。`
  }
  return `[large-screen-image action=generate ratio=${ratio.value} quality=${quality.value} referenceFileId=${referenceFileId} referenceImageUrl=${referenceImageUrl}]\n${prompt.value.trim()}`
}

async function ensureSession() {
  if (currentSessionId.value) return currentSessionId.value
  const created = await createSession('大屏生图')
  if (!created) return null
  currentSessionId.value = String(created.id)
  return currentSessionId.value
}

async function sendAction(action: 'analyze' | 'generate') {
  if (isRunning.value) return
  if (action === 'analyze' && reference.value?.kind !== 'attachment') {
    message.info('请先上传一张参考图')
    return
  }
  if (action === 'generate' && !prompt.value.trim()) {
    message.info('请先填写或确认生图提示词')
    return
  }
  const sessionId = await ensureSession()
  if (!sessionId) {
    message.error('创建会话失败，请重试')
    return
  }
  const text = actionText(action)
  try {
    const appended = await chatSessionApi.appendMessage(sessionId, { role: 'user', content: text })
    if (appended.data?.data) messagesList.value.push(appended.data.data)
    await sendMessage(text, [{ id: `large-screen-image-${Date.now()}`, role: 'user', content: text }] as ChatMessageVO[], referenceFileIds.value)
  } catch {
    message.error(action === 'analyze' ? '识图请求发送失败，可重试' : '生图请求发送失败，可重试')
  }
}

async function uploadReference(file: File) {
  const result = validateReferenceFiles([file])
  if (!result.ok) {
    message.error('仅支持一次上传一张图片')
    return
  }
  uploading.value = true
  try {
    const response = await attachApi.upload(file)
    const fileId = response.data?.data
    if (!fileId) throw new Error('missing file id')
    reference.value = { kind: 'attachment', fileId: String(fileId), fileName: file.name }
    message.success('图片已上传，可以开始识图')
  } catch {
    message.error('上传失败，可重试')
  } finally {
    uploading.value = false
  }
}

function handlePicker(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (fileInput.value) fileInput.value.value = ''
  const result = validateReferenceFiles(files)
  if (!result.ok) {
    message.error(result.code === 'MULTIPLE_IMAGES' ? '每次仅支持一张图片' : '请选择一张图片')
    return
  }
  void uploadReference(result.file)
}

function handleDrop(event: DragEvent) {
  event.preventDefault()
  dragActive.value = false
  const result = validateReferenceFiles(Array.from(event.dataTransfer?.files ?? []))
  if (!result.ok) {
    message.error(result.code === 'MULTIPLE_IMAGES' ? '每次仅支持拖入一张图片' : '仅支持图片文件')
    return
  }
  void uploadReference(result.file)
}

async function handleNewSession() {
  disconnectStream()
  reference.value = null
  prompt.value = ''
  resetSession(null)
}

async function handleDelete(session: ChatSessionVO) {
  if (isRunning.value) return
  await deleteSession(session.id)
  if (String(session.id) === currentSessionId.value) resetSession(null)
}

function useOutputAsReference(imageUrl: string) {
  reference.value = { kind: 'output', imageUrl }
  message.success('已选择生成图作为参考图')
}

async function loadAgent() {
  loading.value = true
  loadError.value = ''
  try {
    const response = await agentApi.page({ agentCode: LARGE_SCREEN_IMAGE_AGENT_CODE, page: 1, size: 2 })
    const found = resolveLargeScreenImageAgent(response.data?.data?.records ?? [])
    if (!found) {
      loadError.value = '大屏生图智能体尚未配置或未启用'
      return
    }
    agent.value = found as AgentDefinitionVO
    await loadSessions()
  } catch (error) {
    loadError.value = error instanceof Error && error.message.includes('Duplicate')
      ? '检测到重复的大屏生图智能体，请联系管理员处理'
      : '大屏生图智能体加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void loadAgent() })
</script>

<template>
  <main class="large-screen-image-page">
    <section v-if="loading" class="large-screen-image-state">正在加载大屏生图…</section>
    <section v-else-if="loadError" class="large-screen-image-state large-screen-image-state--error">
      <p>{{ loadError }}</p>
      <button type="button" @click="loadAgent">重新加载</button>
    </section>
    <template v-else>
      <aside class="large-screen-image-sessions" aria-label="大屏生图会话">
        <div class="large-screen-image-panel-title">
          <strong>大屏生图</strong>
          <button type="button" @click="handleNewSession">新建</button>
        </div>
        <button
          v-for="session in sessions"
          :key="String(session.id)"
          class="large-screen-image-session"
          :class="{ 'is-active': String(session.id) === currentSessionId }"
          type="button"
          @click="selectSession(session)"
        >
          <span>{{ session.title || '新对话' }}</span>
          <span class="large-screen-image-session-delete" @click.stop="handleDelete(session)">×</span>
        </button>
        <p v-if="sessions.length === 0" class="large-screen-image-empty">从一次大屏创作开始</p>
      </aside>

      <section class="large-screen-image-workspace">
        <header class="large-screen-image-header">
          <div>
            <h1>{{ agentDetail?.name || '大屏生图' }}</h1>
            <p>上传参考图、识别视觉要素，再生成可编辑的大屏设计图。</p>
          </div>
        </header>

        <div class="large-screen-image-messages" aria-live="polite">
          <p v-if="visibleMessages.length === 0" class="large-screen-image-empty">上传一张参考图，或直接描述你要生成的大屏。</p>
          <article v-for="item in visibleMessages" :key="String(item.id)" class="large-screen-image-message" :class="`is-${item.role}`">
            <span>{{ item.role === 'user' ? '你' : '智能体' }}</span>
            <pre>{{ item.content }}</pre>
          </article>
        </div>

        <div class="large-screen-image-composer">
          <label
            class="large-screen-image-dropzone"
            :class="{ 'is-dragging': dragActive, 'is-uploading': uploading }"
            @dragenter.prevent="dragActive = true"
            @dragover.prevent="dragActive = true"
            @dragleave.prevent="dragActive = false"
            @drop="handleDrop"
          >
            <input ref="fileInput" type="file" accept="image/*" :disabled="uploading || isRunning" @change="handlePicker">
            <span v-if="uploading">正在上传图片…</span>
            <span v-else-if="reference?.kind === 'attachment'">{{ reference.fileName }} · 点击更换</span>
            <span v-else-if="reference?.kind === 'output'">已选当前会话生成图作为参考</span>
            <span v-else>拖入一张图片，或点击上传参考图</span>
          </label>

          <textarea v-model="prompt" :disabled="isRunning" placeholder="描述你要生成的数据大屏：业务主题、场景、布局、图表和视觉风格。" />
          <div class="large-screen-image-controls">
            <label>比例
              <select v-model="ratio" :disabled="isRunning"><option>16:9</option><option>21:9</option><option>9:16</option></select>
            </label>
            <label>质量
              <select v-model="quality" :disabled="isRunning"><option value="standard">标准</option><option value="high">高</option></select>
            </label>
            <button type="button" :disabled="isRunning || uploading || reference?.kind !== 'attachment'" @click="sendAction('analyze')">识图</button>
            <button type="button" class="large-screen-image-primary" :disabled="isRunning || uploading || !prompt.trim()" @click="sendAction('generate')">
              {{ isRunning ? '生成中…' : '生成大屏图' }}
            </button>
          </div>
        </div>
      </section>

      <aside class="large-screen-image-gallery" aria-label="本次会话作品">
        <div class="large-screen-image-panel-title"><strong>本次会话作品</strong></div>
        <p v-if="generatedImages.length === 0" class="large-screen-image-empty">本次对话还没有生成图片</p>
        <figure v-for="image in generatedImages" :key="image.imageUrl" class="large-screen-image-output">
          <img :src="image.imageUrl" alt="大屏生图结果">
          <figcaption>
            <a :href="image.imageUrl" target="_blank" rel="noopener">预览 / 下载</a>
            <button type="button" @click="useOutputAsReference(image.imageUrl)">作为参考图</button>
          </figcaption>
        </figure>
      </aside>
    </template>
  </main>
</template>

<style scoped lang="scss">
.large-screen-image-page { min-height: 100vh; display: grid; grid-template-columns: 240px minmax(0, 1fr) 300px; background: #f5f7fb; color: #172033; }
.large-screen-image-sessions, .large-screen-image-gallery { padding: 16px; background: #fff; overflow-y: auto; }
.large-screen-image-sessions { border-right: 1px solid #e7ebf3; }
.large-screen-image-gallery { border-left: 1px solid #e7ebf3; }
.large-screen-image-workspace { min-width: 0; display: flex; flex-direction: column; }
.large-screen-image-header, .large-screen-image-composer { padding: 20px 24px; background: #fff; }
.large-screen-image-header { border-bottom: 1px solid #e7ebf3; }
.large-screen-image-header h1, .large-screen-image-header p { margin: 0; }
.large-screen-image-header p, .large-screen-image-empty { color: #75809a; font-size: 13px; }
.large-screen-image-panel-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.large-screen-image-panel-title button, .large-screen-image-controls button, .large-screen-image-output button { border: 0; border-radius: 6px; padding: 7px 10px; cursor: pointer; background: #e9eef8; color: #25314d; }
.large-screen-image-session { width: 100%; display: flex; justify-content: space-between; gap: 8px; padding: 10px; border: 0; border-radius: 6px; background: transparent; text-align: left; cursor: pointer; }
.large-screen-image-session:hover, .large-screen-image-session.is-active { background: #e8f0ff; color: #1559cf; }
.large-screen-image-session-delete { opacity: .6; }
.large-screen-image-messages { flex: 1; overflow-y: auto; padding: 24px; }
.large-screen-image-message { max-width: 88%; margin: 0 0 14px; padding: 12px; border-radius: 8px; background: #fff; box-shadow: 0 1px 2px rgb(22 36 63 / 6%); }
.large-screen-image-message.is-user { margin-left: auto; background: #e8f0ff; }
.large-screen-image-message span { font-size: 12px; color: #75809a; }
.large-screen-image-message pre { margin: 6px 0 0; white-space: pre-wrap; font: inherit; }
.large-screen-image-composer { border-top: 1px solid #e7ebf3; }
.large-screen-image-dropzone { display: block; padding: 14px; border: 1px dashed #8ba7da; border-radius: 8px; color: #3f5f9e; cursor: pointer; }
.large-screen-image-dropzone.is-dragging { background: #e8f0ff; border-color: #1559cf; }
.large-screen-image-dropzone input { display: none; }
.large-screen-image-composer textarea { box-sizing: border-box; width: 100%; min-height: 100px; margin: 12px 0; padding: 12px; border: 1px solid #d7deeb; border-radius: 8px; resize: vertical; }
.large-screen-image-controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.large-screen-image-controls label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.large-screen-image-controls select { border: 1px solid #d7deeb; border-radius: 6px; padding: 6px; }
.large-screen-image-controls .large-screen-image-primary { background: #1559cf; color: #fff; }
.large-screen-image-controls button:disabled { cursor: not-allowed; opacity: .55; }
.large-screen-image-output { margin: 0 0 16px; }
.large-screen-image-output img { display: block; width: 100%; border-radius: 8px; background: #e9eef8; }
.large-screen-image-output figcaption { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-top: 7px; font-size: 12px; }
.large-screen-image-state { min-height: 100vh; display: grid; place-content: center; gap: 12px; background: #f5f7fb; text-align: center; }
.large-screen-image-state--error { color: #a12f2f; }
@media (max-width: 1024px) { .large-screen-image-page { grid-template-columns: 220px minmax(0, 1fr); } .large-screen-image-gallery { display: none; } }
@media (max-width: 768px) { .large-screen-image-page { display: block; } .large-screen-image-sessions { display: none; } .large-screen-image-header, .large-screen-image-composer, .large-screen-image-messages { padding-left: 16px; padding-right: 16px; } }
</style>

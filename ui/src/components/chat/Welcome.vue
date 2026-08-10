<script setup lang="ts">
import ChatInput from './ChatInput.vue'
import { computed, ref } from 'vue'
import DiyWelcome from './DiyWelcome.vue'
import AgentRunActivity from './AgentRunActivity.vue'
import AgentRunWaiting from './AgentRunWaiting.vue'
import type { ChatAttachmentPolicy } from '@/composables/chat/useChatAttachments'
import type { DiyOutputFormat, DiyPageConfig, RunActivity, UploadedFileItem } from '@/types'

const props = defineProps<{
  messageSize: number
  headline: string
  inputValue: string
  agentId: string
  description?: string
  uploadedFiles?: import('@/types').UploadedFileItem[]
  isRunning?: boolean
  runActivities?: RunActivity[]
  showRunActivity?: boolean
  showRunWaiting?: boolean
  runStartedAt?: number | null
  showInput?: boolean
  memoryActive?: boolean
  planActive?: boolean
  enableMemory?: boolean
  enablePlanning?: boolean
  toolProcessActive?: boolean
  showToolProcess?: boolean
  allowUploadFileType?: string[]
  attachmentPolicy?: ChatAttachmentPolicy
  attachmentDropEnabled?: boolean
  onUploadComplete?: (file: UploadedFileItem) => void
  onAttachmentRemoved?: (file: UploadedFileItem) => void
  sessionId?: string | null
  mentionAllowed?: boolean
  hasCodeExecutionConfig?: boolean
  diyConfig?: DiyPageConfig | null
}>()

const needInit = computed(() => {
  return props.hasCodeExecutionConfig && props.messageSize === 0
})

defineEmits<{
  (e: 'update:inputValue', value: string): void
  (e: 'update:uploadedFiles', value: import('@/types').UploadedFileItem[]): void
  (e: 'send'): void
  (e: 'memory', value: boolean): void
  (e: 'plan', value: boolean): void
  (e: 'toolProcess', value: boolean): void
  (e: 'newSession'): void
  (e: 'abort'): void
  (e: 'quickSend', payload: { text: string; outputFormat: DiyOutputFormat }): void
}>()

const diyFormActive = ref(false)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)
const resolvedHeadline = computed(() => props.diyConfig?.headline || props.headline)
const resolvedDescription = computed(() => props.diyConfig?.description || props.description)

const requestAttachmentPicker = (options?: { replace?: boolean }) => {
  chatInputRef.value?.requestAttachmentPicker(options)
}

defineExpose({ requestAttachmentPicker })
</script>

<template>
  <div
    class="chat-welcome"
    :class="{
      'has-diy-form': diyFormActive,
      'is-diy-welcome': Boolean(diyConfig),
    }"
  >
    <h2 class="chat-welcome-title" :title="resolvedHeadline">{{ resolvedHeadline }}</h2>
    <p v-if="resolvedDescription && !diyConfig" class="chat-welcome-desc" :title="resolvedDescription">{{ resolvedDescription }}</p>
    <AgentRunActivity
      v-if="showRunActivity"
      :activities="runActivities || []"
      :started-at="runStartedAt"
      @abort="$emit('abort')"
    />
    <AgentRunWaiting
      v-else-if="showRunWaiting"
      :started-at="runStartedAt"
      @abort="$emit('abort')"
    />
    <DiyWelcome
      v-else-if="diyConfig"
      :config="diyConfig"
      :is-running="isRunning"
      @confirm="$emit('quickSend', $event)"
      @form-active="diyFormActive = $event"
    />
    <div v-if="!diyFormActive && showInput !== false" class="chat-input-outer chat-welcome-input">
      <ChatInput
        ref="chatInputRef"
        :model-value="inputValue"
        :agent-id="agentId"
        :uploaded-files="uploadedFiles"
        :isRunning="isRunning"
        :placeholder="diyConfig?.inputPlaceholder"
        :memory-active="memoryActive"
        :plan-active="planActive"
        :enable-memory="enableMemory"
        :enable-planning="enablePlanning"
        :allow-upload-file-type="allowUploadFileType"
        :attachment-policy="attachmentPolicy"
        :attachment-drop-enabled="attachmentDropEnabled"
        :on-upload-complete="onUploadComplete"
        :on-attachment-removed="onAttachmentRemoved"
        :show-tool-process="showToolProcess"
        :tool-process-active="toolProcessActive"
        :session-id="sessionId"
        :mention-allowed="mentionAllowed"
        :need-init="needInit"
        @update:model-value="$emit('update:inputValue', $event)"
        @update:uploaded-files="$emit('update:uploadedFiles', $event)"
        @memory="$emit('memory', $event)"
        @plan="$emit('plan', $event)"
        @toolProcess="$emit('toolProcess', $event)"
        @send="$emit('send')"
        @new-session="$emit('newSession')"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;
</style>

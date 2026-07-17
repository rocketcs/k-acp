<script setup lang="ts">
import ChatInput from './ChatInput.vue'
import { computed, ref } from 'vue'
import DiyWelcome from './DiyWelcome.vue'
import type { DiyOutputFormat, DiyPageConfig } from '@/types'

const props = defineProps<{
  messageSize: number
  headline: string
  inputValue: string
  agentId: string
  description?: string
  uploadedFiles?: import('@/types').UploadedFileItem[]
  isRunning?: boolean
  memoryActive?: boolean
  planActive?: boolean
  enableMemory?: boolean
  enablePlanning?: boolean
  toolProcessActive?: boolean
  showToolProcess?: boolean
  allowUploadFileType?: string[]
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
  (e: 'quickSend', payload: { text: string; outputFormat: DiyOutputFormat }): void
}>()

const diyFormActive = ref(false)
const resolvedHeadline = computed(() => props.diyConfig?.headline || props.headline)
const resolvedDescription = computed(() => props.diyConfig?.description || props.description)
</script>

<template>
  <div class="chat-welcome" :class="{ 'has-diy-form': diyFormActive }">
    <h2 class="chat-welcome-title" :title="resolvedHeadline">{{ resolvedHeadline }}</h2>
    <p v-if="resolvedDescription" class="chat-welcome-desc" :title="resolvedDescription">{{ resolvedDescription }}</p>
    <DiyWelcome
      v-if="diyConfig"
      :config="diyConfig"
      :is-running="isRunning"
      @confirm="$emit('quickSend', $event)"
      @form-active="diyFormActive = $event"
    />
    <div v-if="!diyFormActive" class="chat-input-outer chat-welcome-input">
      <ChatInput
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

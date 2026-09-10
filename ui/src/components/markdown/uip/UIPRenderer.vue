<template>
  <div class="uip-renderer">
    <!-- 流式 JSON 不完整 → 优雅占位 -->
    <UipSkeleton v-if="!parsed && isStreaming" />

    <!-- form 交互 -->
    <FormRenderer
      v-else-if="parsed?.interaction.type === 'form'"
      :interaction="parsed.interaction"
      :disabled="disabled"
      :is-diy-chat="isDiyChat"
      @submit="onSubmit"
    />

    <!-- choice 交互 -->
    <ChoiceRenderer
      v-else-if="parsed?.interaction.type === 'choice'"
      :interaction="parsed.interaction"
      :disabled="disabled"
      @submit="onSubmit"
    />

    <!-- confirm 交互 -->
    <ConfirmRenderer
      v-else-if="parsed?.interaction.type === 'confirm'"
      :interaction="parsed.interaction"
      :disabled="disabled"
      @submit="onSubmit"
    />

  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { InteractionSubmitPayload } from './types'
import { validateUIP } from '@/utils/chat/uip.ts'
import UipSkeleton from './UIPSkeleton.vue'
import FormRenderer from './FormRenderer.vue'
import ChoiceRenderer from './ChoiceRenderer.vue'
import ConfirmRenderer from './ConfirmRenderer.vue'

const props = defineProps<{
  /** uip 代码块原始内容（不含 ``` 标记） */
  code: string
  /** 是否处于流式输出阶段 */
  isStreaming?: boolean
  /** 历史消息只读模式（或已提交只读） */
  disabled?: boolean
  isDiyChat?: boolean
}>()

const emit = defineEmits<{
  submit: [payload: InteractionSubmitPayload]
}>()

const parsed = computed(() => validateUIP(props.code).message)

function onSubmit(data: Record<string, unknown>) {
  const item = parsed.value?.interaction
  if (!item) return
  emit('submit', {
    interactionId: item.id,
    type: item.type,
    data,
    uipCode: props.code,
  })
}
</script>

<style scoped>
.uip-renderer {
  margin: 4px 0;
}

</style>

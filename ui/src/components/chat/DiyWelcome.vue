<script setup lang="ts">
import { computed, ref } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { DiyOutputFormat, DiyPageConfig, DiyQuestionCard } from '@/types'
import { renderQuestionTemplate } from '@/utils/diy/questionTemplate'

const props = defineProps<{
  config: DiyPageConfig
  isRunning?: boolean
}>()

const emit = defineEmits<{
  confirm: [payload: { text: string; outputFormat: DiyOutputFormat }]
  formActive: [active: boolean]
}>()

const selected = ref<DiyQuestionCard | null>(null)
const values = ref<Record<string, string>>({})

const enabledQuestions = computed(() => props.config.questions.filter((item) => item.enabled))

function selectQuestion(question: DiyQuestionCard) {
  selected.value = question
  values.value = Object.fromEntries(
    question.placeholders.map((field) => [field.name, field.defaultValue || '']),
  )
  emit('formActive', true)
}

function cancel() {
  selected.value = null
  values.value = {}
  emit('formActive', false)
}

function selectValue(name: string, value: string) {
  values.value[name] = value
}

function resolveOutputFormat(): DiyOutputFormat {
  const value = Object.entries(values.value)
    .find(([name]) => name === '输出格式' || name.includes('输出格式'))?.[1] || '文本'
  if (/柱/.test(value)) return 'ECHARTS_BAR'
  if (/饼/.test(value)) return 'ECHARTS_PIE'
  if (/json/i.test(value)) return 'JSON'
  return 'TEXT'
}

function confirm() {
  if (!selected.value || props.isRunning) return
  const optionalNames = selected.value.placeholders
    .filter((field) => !field.required)
    .map((field) => field.name)
  const rendered = renderQuestionTemplate(selected.value.template, values.value, optionalNames)
  if (rendered.missing.length) {
    message.warning(`请填写：${rendered.missing.join('、')}`)
    return
  }
  emit('confirm', { text: rendered.text.trim(), outputFormat: resolveOutputFormat() })
}
</script>

<template>
  <div class="diy-welcome-content">
    <div v-if="!selected" class="diy-question-grid">
      <button
        v-for="question in enabledQuestions"
        :key="question.id"
        type="button"
        class="diy-question-card"
        :disabled="isRunning"
        @click="selectQuestion(question)"
      >
        <span class="diy-question-icon">{{ question.icon || '✦' }}</span>
        <span class="diy-question-copy">
          <strong>{{ question.title }}</strong>
          <small>{{ question.description }}</small>
        </span>
      </button>
    </div>

    <section v-else class="diy-question-form">
      <header class="diy-form-header">
        <div><span class="diy-question-icon">{{ selected.icon || '✦' }}</span><strong>{{ selected.title }}</strong></div>
        <AButton type="text" @click="cancel"><CloseOutlined />取消</AButton>
      </header>
      <div class="diy-fields">
        <div v-for="field in selected.placeholders" :key="field.name" class="diy-field">
          <label>{{ field.name }}<span v-if="field.required" class="required">*</span></label>
          <AInput
            v-if="field.inputType === 'INPUT'"
            v-model:value="values[field.name]"
            :placeholder="field.placeholder || `请输入${field.name}`"
            @pressEnter="confirm"
          />
          <div v-else class="diy-option-list">
            <button
              v-for="option in field.options"
              :key="option"
              type="button"
              class="diy-option"
              :class="{ active: values[field.name] === option }"
              @click="selectValue(field.name, option)"
            >{{ option }}</button>
          </div>
        </div>
      </div>
      <footer class="diy-form-footer">
        <AButton @click="cancel">取消</AButton>
        <AButton type="primary" :disabled="isRunning" @click="confirm">确认发送</AButton>
      </footer>
    </section>
  </div>
</template>

<style scoped lang="scss">
.diy-welcome-content {
  width: min(100%, 760px);
  margin: 0 auto;
  text-align: left;
}

.diy-question-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.diy-question-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-height: 88px;
  padding: 16px 18px;
  border: 1px solid #e6eaf0;
  border-radius: 14px;
  background: #fff;
  color: var(--color-text-primary);
  text-align: left;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(31, 41, 55, 0.04);
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;

  &:hover:not(:disabled) {
    border-color: rgba(15, 116, 255, 0.45);
    box-shadow: 0 8px 22px rgba(15, 116, 255, 0.1);
    transform: translateY(-1px);
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}

.diy-question-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #eef5ff;
  font-size: 20px;
  line-height: 1;
}

.diy-question-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;

  strong {
    font-size: 15px;
    line-height: 1.4;
  }

  small {
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.5;
  }
}

.diy-question-form {
  width: min(100%, 680px);
  margin: 0 auto;
  padding: 22px 24px 20px;
  border: 1px solid #e6eaf0;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 12px 32px rgba(31, 41, 55, 0.08);
}

.diy-form-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 18px;
  border-bottom: 1px solid #f0f2f5;

  > div {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }

  strong {
    overflow: hidden;
    font-size: 17px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.diy-fields {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 20px 0 22px;
}

.diy-field {
  display: flex;
  flex-direction: column;
  gap: 9px;

  label {
    color: var(--color-text-primary);
    font-size: 14px;
    font-weight: 600;
    line-height: 1.4;
  }

  .required {
    margin-left: 3px;
    color: #e5484d;
  }

  :deep(.ant-input) {
    min-height: 42px;
    padding: 8px 12px;
    border-radius: 10px;
    font-size: 14px;
  }
}

.diy-option-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.diy-option {
  min-height: 38px;
  padding: 7px 14px;
  border: 1px solid #dfe4ea;
  border-radius: 9px;
  background: #fff;
  color: var(--color-text-regular);
  font-size: 14px;
  line-height: 1.4;
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, background-color 0.18s ease;

  &:hover {
    border-color: #8bbcff;
    color: #0f74ff;
  }

  &.active {
    border-color: #0f74ff;
    background: #eef5ff;
    color: #0f74ff;
    font-weight: 600;
    box-shadow: inset 0 0 0 1px rgba(15, 116, 255, 0.08);
  }
}

.diy-form-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 16px;
  border-top: 1px solid #f0f2f5;

  :deep(.ant-btn) {
    min-width: 96px;
    height: 40px;
    border-radius: 10px;
  }
}

@media (max-width: 768px) {
  .diy-question-grid {
    grid-template-columns: 1fr;
  }

  .diy-question-form {
    padding: 18px 16px 16px;
    border-radius: 14px;
  }

  .diy-form-header {
    padding-bottom: 14px;
  }

  .diy-fields {
    gap: 16px;
    padding: 16px 0 18px;
  }

  .diy-option {
    flex: 1 1 auto;
  }

  .diy-form-footer :deep(.ant-btn) {
    flex: 1;
  }
}
</style>

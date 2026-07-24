<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeftOutlined, CopyOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import * as agentApi from '@/api/agent'
import * as agentDiyApi from '@/api/agentDiy'
import { RouteNames } from '@/router'
import type {
  AgentDefinitionVO,
  DiyPageConfig,
  DiyPlaceholderConfig,
  DiyQuestionCard,
} from '@/types'
import { extractPlaceholders, renderQuestionTemplate } from '@/utils/diy/questionTemplate'

const route = useRoute()
const router = useRouter()
const agentId = computed(() => String(route.params.agentId || ''))
const agent = ref<AgentDefinitionVO | null>(null)
const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const publishedAt = ref<string | null>(null)
const emojiPickerOpenId = ref<string | null>(null)

const BUSINESS_EMOJI_OPTIONS = [
  { emoji: '📊', label: '数据分析' },
  { emoji: '📈', label: '趋势分析' },
  { emoji: '💰', label: '销售业绩' },
  { emoji: '📄', label: '合同管理' },
  { emoji: '🤝', label: '客户管理' },
  { emoji: '📁', label: '项目管理' },
  { emoji: '📝', label: '售前支持' },
  { emoji: '🧪', label: 'POC 验证' },
  { emoji: '🎫', label: '工单处理' },
  { emoji: '🧑‍💼', label: '人员分析' },
  { emoji: '🏢', label: '部门分析' },
  { emoji: '🕒', label: '报工统计' },
  { emoji: '✈️', label: '出差管理' },
  { emoji: '🧾', label: '报销管理' },
  { emoji: '📦', label: '产品分析' },
  { emoji: '🎯', label: '目标与 KPI' },
  { emoji: '🏆', label: '排名对比' },
  { emoji: '🔍', label: '明细查询' },
  { emoji: '⚠️', label: '异常排查' },
  { emoji: '🔗', label: '全链路分析' },
] as const

const createDefaultConfig = (): DiyPageConfig => ({
  headline: '有什么我能帮你的吗？',
  description: '请选择一个快捷问题，并补充查询条件',
  inputPlaceholder: '输入您的问题……',
  questions: [],
})

const config = ref<DiyPageConfig>(createDefaultConfig())

function newId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function chooseEmoji(question: DiyQuestionCard, emoji: string) {
  question.icon = emoji
  emojiPickerOpenId.value = null
}

function emojiLabel(emoji?: string) {
  return BUSINESS_EMOJI_OPTIONS.find((item) => item.emoji === emoji)?.label || (emoji ? '自定义图标' : '选择业务图标')
}

function createQuestion(): DiyQuestionCard {
  return {
    id: newId(),
    title: '新快捷问题',
    description: '填写问题说明',
    icon: '📊',
    template: '请查询 {{查询内容}}，时间范围为 {{时间范围}}，并以 {{输出格式}} 输出。',
    enabled: true,
    placeholders: [],
  }
}

function syncPlaceholders(question: DiyQuestionCard) {
  const previous = new Map(question.placeholders.map((item) => [item.name, item]))
  question.placeholders = extractPlaceholders(question.template).map((name) =>
    previous.get(name) || {
      name,
      inputType: name === '输出格式' ? 'SELECT' : 'INPUT',
      required: true,
      placeholder: `请输入${name}`,
      options: name === '输出格式'
        ? ['文本', 'ECharts 柱状图', 'ECharts 饼图', 'JSON']
        : [],
    },
  )
}

function addQuestion() {
  const question = createQuestion()
  syncPlaceholders(question)
  config.value.questions.push(question)
}

function copyQuestion(index: number) {
  const source = config.value.questions[index]
  if (!source) return
  const copied = structuredClone(source)
  copied.id = newId()
  copied.title = `${copied.title}（副本）`
  config.value.questions.splice(index + 1, 0, copied)
}

function removeQuestion(index: number) {
  config.value.questions.splice(index, 1)
}

function moveQuestion(index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= config.value.questions.length) return
  const [question] = config.value.questions.splice(index, 1)
  if (question) config.value.questions.splice(target, 0, question)
}

function optionsText(field: DiyPlaceholderConfig) {
  return field.options?.join('、') || ''
}

function updateOptions(field: DiyPlaceholderConfig, event: Event) {
  const value = (event.target as HTMLInputElement).value
  field.options = value.split(/[、,，\n]/).map((item) => item.trim()).filter(Boolean)
}

function validateConfig() {
  if (!config.value.headline.trim()) {
    message.error('请填写欢迎标题')
    return false
  }
  for (const question of config.value.questions) {
    syncPlaceholders(question)
    if (!question.title.trim() || !question.template.trim()) {
      message.error('快捷问题标题和问题模板不能为空')
      return false
    }
    const invalidSelect = question.placeholders.find(
      (field) => field.inputType === 'SELECT' && !field.options?.length,
    )
    if (invalidSelect) {
      message.error(`“${question.title}”的“${invalidSelect.name}”缺少选项`)
      return false
    }
  }
  return true
}

async function saveDraft(showSuccess = true) {
  if (!validateConfig()) return false
  saving.value = true
  try {
    await agentDiyApi.saveDraft(agentId.value, config.value)
    if (showSuccess) message.success('草稿已保存')
    return true
  } finally {
    saving.value = false
  }
}

async function publish() {
  if (!(await saveDraft(false))) return
  publishing.value = true
  try {
    const response = await agentDiyApi.publish(agentId.value)
    if (!response.data.data) {
      message.error('发布失败，请先检查配置')
      return
    }
    publishedAt.value = new Date().toISOString()
    message.success('DIY 页面已发布')
  } finally {
    publishing.value = false
  }
}

function previewQuestion(question: DiyQuestionCard) {
  const values = Object.fromEntries(
    question.placeholders.map((field) => [
      field.name,
      field.defaultValue || field.options?.[0] || `示例${field.name}`,
    ]),
  )
  return renderQuestionTemplate(question.template, values).text
}

function openChat() {
  const href = router.resolve({ name: RouteNames.CHAT_DIY, params: { agentId: agentId.value } }).href
  window.open(href, '_blank')
}

async function load() {
  loading.value = true
  try {
    const [agentResponse, diyResponse] = await Promise.all([
      agentApi.detail(agentId.value),
      agentDiyApi.getDraft(agentId.value),
    ])
    agent.value = agentResponse.data.data
    const record = diyResponse.data.data
    const source = record?.draftConfig || record?.publishedConfig
    config.value = source ? structuredClone(source) : createDefaultConfig()
    config.value.questions.forEach(syncPlaceholders)
    publishedAt.value = record?.publishedAt || null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="agent-diy-page">
    <header class="diy-page-header">
      <div class="header-title-wrap">
        <AButton type="text" @click="router.push({ name: RouteNames.AGENT })"><ArrowLeftOutlined /></AButton>
        <div>
          <h2>{{ agent?.name || '智能体' }} · DIY 页面</h2>
          <p>使用问题模板和占位符配置快捷问数入口</p>
        </div>
      </div>
      <div class="header-actions">
        <span v-if="publishedAt" class="published-time">已发布</span>
        <AButton @click="openChat">打开对话页</AButton>
        <AButton :loading="saving" @click="saveDraft()">保存草稿</AButton>
        <AButton type="primary" :loading="publishing" @click="publish">发布</AButton>
      </div>
    </header>

    <ASpin :spinning="loading">
      <div class="diy-editor-layout">
        <section class="diy-settings">
          <ACard title="页面设置" size="small">
            <AForm layout="vertical">
              <AFormItem label="欢迎标题" required>
                <AInput v-model:value="config.headline" />
              </AFormItem>
              <AFormItem label="欢迎说明">
                <AInput v-model:value="config.description" />
              </AFormItem>
              <AFormItem label="输入框提示语">
                <AInput v-model:value="config.inputPlaceholder" />
              </AFormItem>
            </AForm>
          </ACard>

          <div class="question-heading">
            <h3>快捷问题</h3>
            <AButton type="primary" size="small" @click="addQuestion"><PlusOutlined />新增问题</AButton>
          </div>

          <AEmpty v-if="config.questions.length === 0" description="还没有快捷问题" />
          <ACard
            v-for="(question, index) in config.questions"
            :key="question.id"
            class="question-editor-card"
            size="small"
          >
            <template #title>
              <div class="question-card-title">
                <span>{{ index + 1 }}. {{ question.title }}</span>
                <ASwitch v-model:checked="question.enabled" size="small" />
              </div>
            </template>
            <template #extra>
              <AButton type="text" size="small" :disabled="index === 0" @click="moveQuestion(index, -1)">上移</AButton>
              <AButton type="text" size="small" :disabled="index === config.questions.length - 1" @click="moveQuestion(index, 1)">下移</AButton>
              <AButton type="text" size="small" @click="copyQuestion(index)"><CopyOutlined /></AButton>
              <AButton type="text" danger size="small" @click="removeQuestion(index)"><DeleteOutlined /></AButton>
            </template>

            <AForm layout="vertical">
              <div class="two-columns">
                <AFormItem label="卡片标题" required><AInput v-model:value="question.title" /></AFormItem>
                <AFormItem label="业务图标">
                  <div class="emoji-picker-field">
                    <APopover
                      :open="emojiPickerOpenId === question.id"
                      placement="bottomLeft"
                      trigger="click"
                      @update:open="(open: boolean) => { emojiPickerOpenId = open ? question.id : null }"
                    >
                      <template #content>
                        <div class="emoji-picker">
                          <button
                            v-for="item in BUSINESS_EMOJI_OPTIONS"
                            :key="item.emoji"
                            type="button"
                            class="emoji-option"
                            :class="{ active: question.icon === item.emoji }"
                            :title="item.label"
                            @click="chooseEmoji(question, item.emoji)"
                          >
                            <span class="emoji-option-symbol">{{ item.emoji }}</span>
                            <span class="emoji-option-label">{{ item.label }}</span>
                          </button>
                        </div>
                      </template>
                      <AButton class="emoji-picker-trigger">
                        <span class="emoji-picker-current">{{ question.icon || '✦' }}</span>
                        <span>{{ emojiLabel(question.icon) }}</span>
                      </AButton>
                    </APopover>
                    <AButton
                      v-if="question.icon"
                      type="text"
                      size="small"
                      @click="question.icon = ''"
                    >清除</AButton>
                  </div>
                </AFormItem>
              </div>
              <AFormItem label="卡片说明"><AInput v-model:value="question.description" /></AFormItem>
              <AFormItem label="问题模板" required>
                <ATextarea
                  v-model:value="question.template"
                  :rows="4"
                  placeholder="例如：请查询 {{时间范围}} 的 {{分析指标}}"
                  @blur="syncPlaceholders(question)"
                />
                <div class="template-tip">使用双大括号声明占位符，例如：&#123;&#123;时间范围&#125;&#125;</div>
              </AFormItem>

              <div class="placeholder-heading">
                <strong>模板选项</strong>
                <AButton size="small" @click="syncPlaceholders(question)">重新识别</AButton>
              </div>
              <div v-for="field in question.placeholders" :key="field.name" class="placeholder-row">
                <div class="placeholder-name">{{ field.name }}</div>
                <ASelect v-model:value="field.inputType" style="width: 110px">
                  <ASelectOption value="INPUT">用户输入</ASelectOption>
                  <ASelectOption value="TEXTAREA">多行输入</ASelectOption>
                  <ASelectOption value="SELECT">单选</ASelectOption>
                </ASelect>
                <ACheckbox v-model:checked="field.required">必填</ACheckbox>
                <AInput v-model:value="field.defaultValue" placeholder="默认值" />
                <AInput
                  v-if="field.inputType === 'SELECT'"
                  :value="optionsText(field)"
                  placeholder="选项用、分隔"
                  @change="updateOptions(field, $event)"
                />
                <AInput v-else v-model:value="field.placeholder" placeholder="输入提示" />
              </div>
              <AEmpty v-if="question.placeholders.length === 0" description="模板中暂无占位符" />
            </AForm>
          </ACard>
        </section>

        <aside class="diy-preview">
          <div class="preview-window">
            <h1>{{ config.headline }}</h1>
            <p>{{ config.description }}</p>
            <div class="preview-questions">
              <div v-for="question in config.questions.filter(item => item.enabled)" :key="question.id" class="preview-card">
                <span class="preview-icon">{{ question.icon || '✦' }}</span>
                <div><strong>{{ question.title }}</strong><small>{{ question.description }}</small></div>
              </div>
            </div>
            <div class="preview-input">{{ config.inputPlaceholder }}</div>
          </div>
          <ACollapse v-if="config.questions.length" ghost>
            <ACollapsePanel v-for="question in config.questions" :key="question.id" :header="`${question.title} · 模板预览`">
              <pre>{{ previewQuestion(question) }}</pre>
            </ACollapsePanel>
          </ACollapse>
        </aside>
      </div>
    </ASpin>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/agent-diy/index.scss' as *;

.emoji-picker-field {
  display: flex;
  align-items: center;
  gap: 6px;
}

.emoji-picker-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 142px;
  height: 36px;
  padding: 4px 10px;
  border-color: #dfe4ea;
  border-radius: 9px;
  color: var(--color-text-regular);
  text-align: left;
}

.emoji-picker-current {
  font-size: 20px;
  line-height: 1;
}

.emoji-picker {
  display: grid;
  grid-template-columns: repeat(4, 88px);
  gap: 6px;
  max-width: 390px;
}

.emoji-option {
  display: flex;
  min-height: 58px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 5px 4px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--color-text-regular);
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease;

  &:hover,
  &.active {
    border-color: #8bbcff;
    background: #eef5ff;
  }
}

.emoji-option-symbol {
  font-size: 22px;
  line-height: 1;
}

.emoji-option-label {
  overflow: hidden;
  max-width: 100%;
  font-size: 11px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .emoji-picker {
    grid-template-columns: repeat(3, 82px);
    max-width: 280px;
  }
}
</style>

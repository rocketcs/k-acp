<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CopyOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  getTracingDetail,
  getTracingPage,
  getTracingRaw,
  getTracingSummary,
  getTracingUsers,
} from '@/api/sessionTracing'
import type { PageResult } from '@/types'
import type {
  TracingDetail,
  TracingPageItem,
  TracingRaw,
  TracingResultStatus,
  TracingSummary,
  TracingUser,
} from '@/types/sessionTracing'
import { formatTracingUserLabel, tracingStatusTone } from '@/utils/sessionTracing'

type DetailTab = 'conversation' | 'trace' | 'raw'

const PAGE_SIZE = 20
const emptyPage = (): PageResult<TracingPageItem> => ({
  records: [],
  total: 0,
  size: PAGE_SIZE,
  current: 1,
  pages: 0,
})

const users = ref<TracingUser[]>([])
const pageData = ref<PageResult<TracingPageItem>>(emptyPage())
const summary = ref<TracingSummary | null>(null)
const detail = ref<TracingDetail | null>(null)
const rawByRecordId = ref<Record<string, TracingRaw>>({})

const selectedUserId = ref<string | undefined>()
const selectedStatus = ref<TracingResultStatus | undefined>()
const currentPage = ref(1)
const selectedId = ref<string | null>(null)
const activeTab = ref<DetailTab>('conversation')

const usersLoading = ref(false)
const listLoading = ref(false)
const detailLoading = ref(false)
const rawLoading = ref(false)
const summaryLoading = ref(false)

const usersError = ref<string | null>(null)
const listError = ref<string | null>(null)
const detailError = ref<string | null>(null)
const rawError = ref<string | null>(null)
const summaryError = ref<string | null>(null)

let listRequestSequence = 0
let detailRequestSequence = 0
let rawRequestSequence = 0

const currentRaw = computed(() =>
  selectedId.value ? rawByRecordId.value[selectedId.value] ?? null : null,
)

const selectedRow = computed(() =>
  pageData.value.records.find((record) => record.id === selectedId.value) ?? null,
)

const rawSections = computed(() => {
  if (!currentRaw.value) return []
  return [
    ['llmAnalysisJson', currentRaw.value.llmAnalysisJson],
    ['qaPairsJson', currentRaw.value.qaPairsJson],
    ['conversationJson', currentRaw.value.conversationJson],
    ['envelopeJson', currentRaw.value.envelopeJson],
    ['warningsJson', currentRaw.value.warningsJson],
  ] as const
})

function errorText(error: unknown, fallback: string): string {
  if (typeof error === 'string' && error.trim()) return error
  if (error instanceof Error && error.message) return error.message
  return fallback
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '-'
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}

function statusColor(status: TracingResultStatus): string {
  const tone = tracingStatusTone(status)
  return tone === 'success' ? 'success' : tone === 'warning' ? 'warning' : 'error'
}

function userAvatar(user: { nickname: string | null; username: string | null; userId: string }): string {
  return (user.nickname || user.username || user.userId || '?').trim().slice(0, 1).toUpperCase()
}

async function loadUsers(): Promise<void> {
  usersLoading.value = true
  usersError.value = null
  try {
    const response = await getTracingUsers()
    users.value = response.data.data ?? []
  } catch (error) {
    usersError.value = errorText(error, '用户列表加载失败')
  } finally {
    usersLoading.value = false
  }
}

async function loadSummary(): Promise<void> {
  summaryLoading.value = true
  summaryError.value = null
  try {
    const response = await getTracingSummary()
    summary.value = response.data.data
  } catch (error) {
    summaryError.value = errorText(error, '汇总信息加载失败')
  } finally {
    summaryLoading.value = false
  }
}

async function loadDetail(id: string): Promise<void> {
  const requestSequence = ++detailRequestSequence
  detailLoading.value = true
  detailError.value = null
  detail.value = null
  try {
    const response = await getTracingDetail(id)
    if (requestSequence !== detailRequestSequence || selectedId.value !== id) return
    detail.value = response.data.data
  } catch (error) {
    if (requestSequence !== detailRequestSequence || selectedId.value !== id) return
    detailError.value = errorText(error, '对话详情加载失败')
  } finally {
    if (requestSequence === detailRequestSequence) detailLoading.value = false
  }
}

async function loadPage(): Promise<void> {
  const requestSequence = ++listRequestSequence
  listLoading.value = true
  listError.value = null
  try {
    const response = await getTracingPage({
      userId: selectedUserId.value,
      status: selectedStatus.value,
      page: currentPage.value,
      size: PAGE_SIZE,
    })
    if (requestSequence !== listRequestSequence) return

    pageData.value = response.data.data ?? emptyPage()
    const selectedStillExists = pageData.value.records.some((record) => record.id === selectedId.value)
    const nextId = selectedStillExists ? selectedId.value : pageData.value.records[0]?.id ?? null

    if (!nextId) {
      selectedId.value = null
      detail.value = null
      detailError.value = null
      detailRequestSequence++
      return
    }

    selectedId.value = nextId
    await loadDetail(nextId)
  } catch (error) {
    if (requestSequence !== listRequestSequence) return
    pageData.value = emptyPage()
    selectedId.value = null
    detail.value = null
    listError.value = errorText(error, '会话列表加载失败')
  } finally {
    if (requestSequence === listRequestSequence) listLoading.value = false
  }
}

async function selectRecord(record: TracingPageItem): Promise<void> {
  if (record.id === selectedId.value && detail.value) return
  selectedId.value = record.id
  activeTab.value = 'conversation'
  rawError.value = null
  await loadDetail(record.id)
}

async function resetAndReload(): Promise<void> {
  currentPage.value = 1
  selectedId.value = null
  detail.value = null
  activeTab.value = 'conversation'
  detailRequestSequence++
  rawRequestSequence++
  rawError.value = null
  await loadPage()
}

async function handlePageChange(page: number): Promise<void> {
  currentPage.value = page
  selectedId.value = null
  detail.value = null
  activeTab.value = 'conversation'
  await loadPage()
}

async function loadRaw(force = false): Promise<void> {
  const id = selectedId.value
  if (!id || (!force && rawByRecordId.value[id])) return

  const requestSequence = ++rawRequestSequence
  rawLoading.value = true
  rawError.value = null
  try {
    const response = await getTracingRaw(id)
    if (requestSequence !== rawRequestSequence || selectedId.value !== id) return
    rawByRecordId.value = { ...rawByRecordId.value, [id]: response.data.data }
  } catch (error) {
    if (requestSequence !== rawRequestSequence || selectedId.value !== id) return
    rawError.value = errorText(error, '入库 JSON 加载失败')
  } finally {
    if (requestSequence === rawRequestSequence) rawLoading.value = false
  }
}

async function handleTabChange(key: string | number): Promise<void> {
  activeTab.value = key as DetailTab
  if (activeTab.value === 'raw') await loadRaw()
}

async function refreshAll(): Promise<void> {
  await Promise.all([loadUsers(), loadPage(), loadSummary()])
}

async function copyConversation(): Promise<void> {
  if (!detail.value) return
  const text = detail.value.turns
    .map(
      (turn) =>
        `第 ${turn.turn} 轮\n用户问题：${turn.userQuestion || '未记录用户问题'}\nAgent 最终回答：${turn.agentAnswer || '未记录最终回答'}`,
    )
    .join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    await message.success('对话已复制')
  } catch {
    await message.error('复制失败')
  }
}

onMounted(() => {
  void Promise.all([loadUsers(), loadPage(), loadSummary()])
})
</script>

<template>
  <div class="tracing-page">
    <header class="page-header">
      <div>
        <h1>用户对话复盘</h1>
        <p>按用户查看已入库的多轮问题与 Agent 最终回答</p>
      </div>
      <ATooltip title="刷新数据">
        <AButton
          class="icon-button"
          :loading="usersLoading || listLoading || summaryLoading"
          aria-label="刷新数据"
          @click="refreshAll"
        >
          <template #icon><ReloadOutlined /></template>
        </AButton>
      </ATooltip>
    </header>

    <div class="tracing-grid">
      <!-- Session list: extracted into TracingSessionList in E8. -->
      <aside class="list-panel panel-surface">
        <div class="filter-bar">
          <label>
            <span>用户</span>
            <ASelect
              v-model:value="selectedUserId"
              :loading="usersLoading"
              allow-clear
              placeholder="全部用户"
              @change="resetAndReload"
            >
              <ASelectOption v-for="user in users" :key="user.userId" :value="user.userId">
                {{ formatTracingUserLabel(user) }} · {{ user.conversationCount }} 条
              </ASelectOption>
            </ASelect>
          </label>
          <label>
            <span>结果</span>
            <ASelect
              v-model:value="selectedStatus"
              allow-clear
              placeholder="全部结果"
              @change="resetAndReload"
            >
              <ASelectOption value="COMPLETE">COMPLETE</ASelectOption>
              <ASelectOption value="PARTIAL">PARTIAL</ASelectOption>
              <ASelectOption value="ERROR">ERROR</ASelectOption>
            </ASelect>
          </label>
          <AAlert v-if="usersError" :message="usersError" type="warning" show-icon />
        </div>

        <div class="session-list" :aria-busy="listLoading">
          <ASpin v-if="listLoading" class="center-state" />
          <AAlert v-else-if="listError" :message="listError" type="error" show-icon />
          <AEmpty v-else-if="pageData.records.length === 0" description="暂无匹配的用户对话" />
          <button
            v-for="record in pageData.records"
            v-else
            :key="record.id"
            type="button"
            class="session-row"
            :class="{ active: record.id === selectedId }"
            @click="selectRecord(record)"
          >
            <span class="row-top">
              <span class="user-identity">
                <AAvatar :size="34">{{ userAvatar(record) }}</AAvatar>
                <span class="identity-copy">
                  <strong>{{ record.nickname || record.username || '未知用户' }}</strong>
                  <small>{{ record.username || record.email || record.userId }}</small>
                </span>
              </span>
              <ATag :color="statusColor(record.status)">{{ record.status }}</ATag>
            </span>
            <span class="question-preview">{{ record.firstUserQuestion || '未记录用户问题' }}</span>
            <span class="row-meta">
              <span>{{ formatTime(record.processedAt) }}</span>
              <span>{{ record.turnCount }} 轮</span>
              <span>{{ record.fullObservationCount }} observations</span>
            </span>
          </button>
        </div>

        <div v-if="pageData.total > PAGE_SIZE" class="pagination-wrap">
          <APagination
            :current="currentPage"
            :page-size="PAGE_SIZE"
            :total="pageData.total"
            :show-size-changer="false"
            size="small"
            @change="handlePageChange"
          />
        </div>
      </aside>

      <!-- Conversation and trace detail: extracted into child components in E8. -->
      <main class="detail-panel panel-surface">
        <ASpin v-if="detailLoading" class="center-state" />
        <AAlert v-else-if="detailError" :message="detailError" type="error" show-icon />
        <AEmpty v-else-if="!detail" description="请选择一条用户对话" />
        <template v-else>
          <div class="detail-heading">
            <div>
              <h2>{{ detail.user.nickname || detail.user.username || '未知用户' }}</h2>
              <p>{{ formatTracingUserLabel(detail.user) }}</p>
            </div>
            <ATooltip title="复制对话">
              <AButton class="icon-button" aria-label="复制对话" @click="copyConversation">
                <template #icon><CopyOutlined /></template>
              </AButton>
            </ATooltip>
          </div>

          <ATabs :active-key="activeTab" class="detail-tabs" @change="handleTabChange">
            <ATabPane key="conversation" tab="用户对话">
              <div v-if="detail.turns.length" class="turn-list">
                <section v-for="turn in detail.turns" :key="`${detail.id}-${turn.turn}`" class="turn-block">
                  <div class="turn-index">{{ turn.turn }}</div>
                  <div class="turn-content">
                    <article class="message-block user-message">
                      <header>
                        <strong>用户问题</strong>
                        <time>{{ formatTime(turn.userTimestamp) }}</time>
                      </header>
                      <p>{{ turn.userQuestion || '未记录用户问题' }}</p>
                    </article>
                    <article class="message-block agent-message">
                      <header>
                        <strong>Agent 最终回答</strong>
                        <time>{{ formatTime(turn.agentTimestamp) }}</time>
                      </header>
                      <p>{{ turn.agentAnswer || '未记录最终回答' }}</p>
                    </article>
                  </div>
                </section>
              </div>
              <AEmpty v-else description="暂无可展示问答" />
            </ATabPane>

            <ATabPane key="trace" tab="Trace 信息">
              <div class="trace-grid">
                <div class="trace-item"><span>用户</span><strong>{{ formatTracingUserLabel(detail.user) }}</strong></div>
                <div class="trace-item"><span>项目</span><strong>{{ detail.projectId || '-' }}</strong></div>
                <div class="trace-item technical"><span>Session ID</span><strong>{{ detail.sessionId }}</strong></div>
                <div class="trace-item"><span>结果状态</span><strong>{{ detail.status }}</strong></div>
                <div class="trace-item"><span>Trace</span><strong>{{ detail.traceSummary.traceCount }}</strong></div>
                <div class="trace-item"><span>种子 Observation</span><strong>{{ detail.traceSummary.seedObservationCount }}</strong></div>
                <div class="trace-item"><span>完整 Observation</span><strong>{{ detail.traceSummary.fullObservationCount }}</strong></div>
                <div class="trace-item"><span>Score</span><strong>{{ detail.traceSummary.scoreCount }}</strong></div>
                <div class="trace-item"><span>QA 对</span><strong>{{ detail.traceSummary.qaPairCount }}</strong></div>
                <div class="trace-item"><span>开始时间</span><strong>{{ formatTime(detail.traceSummary.firstObservationStartTime) }}</strong></div>
                <div class="trace-item"><span>结束时间</span><strong>{{ formatTime(detail.traceSummary.lastObservationEndTime) }}</strong></div>
                <div class="trace-item"><span>处理时间</span><strong>{{ formatTime(detail.traceSummary.processedAt) }}</strong></div>
              </div>
              <div class="trace-section">
                <h3>类型分布</h3>
                <div class="tag-list">
                  <ATag v-for="(count, type) in detail.traceSummary.typeCounts" :key="type">
                    {{ type }} {{ count }}
                  </ATag>
                  <span v-if="Object.keys(detail.traceSummary.typeCounts).length === 0" class="muted">暂无</span>
                </div>
              </div>
              <div v-if="detail.traceSummary.warnings.length" class="trace-section">
                <h3>处理告警</h3>
                <AAlert
                  v-for="warning in detail.traceSummary.warnings"
                  :key="warning"
                  :message="warning"
                  type="warning"
                  show-icon
                />
              </div>
            </ATabPane>

            <ATabPane key="raw" tab="入库 JSON">
              <ASpin v-if="rawLoading" class="center-state" />
              <AAlert v-else-if="rawError" :message="rawError" type="error" show-icon>
                <template #action><AButton size="small" @click="loadRaw(true)">重试</AButton></template>
              </AAlert>
              <AEmpty v-else-if="!currentRaw" description="暂无原始数据" />
              <div v-else class="raw-sections">
                <section v-for="section in rawSections" :key="section[0]">
                  <h3>{{ section[0] }}</h3>
                  <pre>{{ JSON.stringify(section[1], null, 2) }}</pre>
                </section>
              </div>
            </ATabPane>
          </ATabs>
        </template>
      </main>

      <!-- Read-only summary: extracted into TracingInspector in E8. -->
      <aside class="summary-panel panel-surface">
        <div class="summary-heading">
          <h2>处理概览</h2>
          <p>最近处理 {{ formatTime(summary?.lastProcessedAt) }}</p>
        </div>
        <ASpin v-if="summaryLoading" class="center-state" />
        <AAlert v-else-if="summaryError" :message="summaryError" type="error" show-icon />
        <template v-else-if="summary">
          <section class="summary-section">
            <h3>结果状态</h3>
            <div class="summary-metrics">
              <div><span>COMPLETE</span><strong>{{ summary.resultStatusCounts.COMPLETE }}</strong></div>
              <div><span>PARTIAL</span><strong>{{ summary.resultStatusCounts.PARTIAL }}</strong></div>
              <div><span>ERROR</span><strong>{{ summary.resultStatusCounts.ERROR }}</strong></div>
            </div>
          </section>
          <section class="summary-section">
            <h3>处理游标</h3>
            <dl class="status-list">
              <div><dt>DISCOVERED</dt><dd>{{ summary.cursorStatusCounts.DISCOVERED }}</dd></div>
              <div><dt>PROCESSING</dt><dd>{{ summary.cursorStatusCounts.PROCESSING }}</dd></div>
              <div><dt>COMPLETE</dt><dd>{{ summary.cursorStatusCounts.COMPLETE }}</dd></div>
              <div><dt>FAILED</dt><dd>{{ summary.cursorStatusCounts.FAILED }}</dd></div>
            </dl>
          </section>
          <AAlert
            v-if="summary.staleProcessingCount > 0"
            :message="`${summary.staleProcessingCount} 条 PROCESSING 已超过租约时间`"
            type="warning"
            show-icon
          />
          <section v-if="selectedRow" class="summary-section selected-summary">
            <h3>当前对话</h3>
            <p>{{ selectedRow.nickname || selectedRow.username || '未知用户' }}</p>
            <dl class="status-list">
              <div><dt>对话轮数</dt><dd>{{ selectedRow.turnCount }}</dd></div>
              <div><dt>Trace 数</dt><dd>{{ selectedRow.traceCount }}</dd></div>
              <div><dt>Observation</dt><dd>{{ selectedRow.fullObservationCount }}</dd></div>
            </dl>
          </section>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.tracing-page {
  min-height: 100%;
  padding: 20px;
  color: #1f2329;
  background: #f5f6f8;
}

.page-header,
.detail-heading,
.row-top,
.user-identity,
.message-block header {
  display: flex;
  align-items: center;
}

.page-header {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header h1,
.detail-heading h2,
.summary-heading h2 {
  margin: 0;
  letter-spacing: 0;
}

.page-header h1 {
  font-size: 24px;
  line-height: 1.35;
}

.page-header p,
.detail-heading p,
.summary-heading p {
  margin: 5px 0 0;
  color: #667085;
  font-size: 13px;
}

.icon-button {
  width: 34px;
  height: 34px;
  padding: 0;
}

.tracing-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr) 320px;
  gap: 14px;
  min-height: calc(100vh - 154px);
}

.panel-surface {
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e6e9ef;
  border-radius: 8px;
}

.list-panel,
.summary-panel,
.detail-panel {
  display: flex;
  flex-direction: column;
}

.filter-bar {
  display: grid;
  gap: 10px;
  padding: 14px;
  background: #fafbfc;
  border-bottom: 1px solid #eceff3;
}

.filter-bar label {
  display: grid;
  gap: 5px;
  color: #475467;
  font-size: 12px;
  font-weight: 600;
}

.filter-bar :deep(.ant-select) {
  width: 100%;
}

.session-list {
  flex: 1;
  min-height: 260px;
  max-height: calc(100vh - 325px);
  overflow-y: auto;
  padding: 8px;
}

.center-state {
  display: block;
  margin: 48px auto;
}

.session-row {
  width: 100%;
  display: block;
  margin-bottom: 7px;
  padding: 11px;
  text-align: left;
  color: inherit;
  background: #fff;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
}

.session-row:hover {
  background: #f7f9ff;
  border-color: #dce5ff;
}

.session-row.active {
  background: #eef3ff;
  border-color: #b8c9ff;
}

.row-top {
  justify-content: space-between;
  gap: 8px;
}

.user-identity {
  min-width: 0;
  gap: 9px;
}

.identity-copy {
  min-width: 0;
}

.identity-copy strong,
.identity-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-copy strong {
  font-size: 14px;
}

.identity-copy small {
  margin-top: 2px;
  color: #667085;
  font-size: 11px;
}

.question-preview {
  display: -webkit-box;
  overflow: hidden;
  margin: 10px 0;
  color: #344054;
  font-size: 13px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 9px;
  color: #7d8597;
  font-size: 11px;
}

.pagination-wrap {
  padding: 10px;
  border-top: 1px solid #eceff3;
}

.detail-panel {
  padding: 18px;
}

.detail-heading {
  justify-content: space-between;
  gap: 12px;
}

.detail-heading h2,
.summary-heading h2 {
  font-size: 18px;
}

.detail-tabs {
  min-width: 0;
  margin-top: 8px;
}

.turn-list {
  display: grid;
  gap: 16px;
}

.turn-block {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 11px;
}

.turn-index {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: #315ee8;
  font-weight: 700;
  background: #edf2ff;
  border-radius: 7px;
}

.turn-content {
  display: grid;
  gap: 9px;
  min-width: 0;
}

.message-block {
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid #e6e9ef;
  border-radius: 7px;
}

.user-message {
  background: #fafbff;
}

.agent-message {
  border-left: 3px solid #4772ff;
}

.message-block header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 7px;
  color: #667085;
  font-size: 12px;
}

.message-block time {
  flex: 0 0 auto;
  font-weight: 400;
}

.message-block p {
  margin: 0;
  overflow-wrap: anywhere;
  color: #252a34;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.trace-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.trace-item {
  min-width: 0;
  padding: 12px;
  background: #fafbfc;
  border: 1px solid #eceff3;
  border-radius: 6px;
}

.trace-item span,
.trace-item strong {
  display: block;
}

.trace-item span {
  margin-bottom: 5px;
  color: #667085;
  font-size: 12px;
}

.trace-item strong {
  overflow-wrap: anywhere;
  font-size: 13px;
}

.trace-item.technical strong,
.raw-sections pre {
  font-family: "SFMono-Regular", Consolas, monospace;
}

.trace-section {
  margin-top: 18px;
}

.trace-section h3,
.raw-sections h3,
.summary-section h3 {
  margin: 0 0 9px;
  font-size: 14px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.muted {
  color: #98a2b3;
}

.raw-sections {
  display: grid;
  gap: 14px;
}

.raw-sections pre {
  max-width: 100%;
  max-height: 420px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  color: #273142;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f6f8fb;
  border: 1px solid #e6e9ef;
  border-radius: 6px;
}

.summary-panel {
  padding: 16px;
}

.summary-heading {
  padding-bottom: 14px;
  border-bottom: 1px solid #eceff3;
}

.summary-section {
  margin-top: 16px;
}

.summary-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
}

.summary-metrics div {
  min-width: 0;
  padding: 9px 7px;
  text-align: center;
  background: #f7f8fa;
  border-radius: 6px;
}

.summary-metrics span,
.summary-metrics strong {
  display: block;
}

.summary-metrics span {
  overflow: hidden;
  color: #667085;
  font-size: 10px;
  text-overflow: ellipsis;
}

.summary-metrics strong {
  margin-top: 4px;
  font-size: 18px;
}

.status-list {
  margin: 0;
}

.status-list div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f2f5;
}

.status-list dt {
  color: #667085;
}

.status-list dd {
  margin: 0;
  font-weight: 700;
}

.selected-summary p {
  margin: 0 0 6px;
  overflow-wrap: anywhere;
  font-weight: 600;
}

@media (max-width: 1180px) {
  .tracing-grid {
    grid-template-columns: 300px minmax(0, 1fr);
  }

  .summary-panel {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .tracing-page {
    padding: 12px;
  }

  .page-header {
    align-items: flex-start;
  }

  .tracing-grid {
    grid-template-columns: minmax(0, 1fr);
    min-height: auto;
  }

  .summary-panel {
    grid-column: auto;
  }

  .session-list {
    max-height: 420px;
  }

  .detail-panel,
  .summary-panel {
    padding: 14px;
  }

  .trace-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .turn-block {
    grid-template-columns: minmax(0, 1fr);
  }

  .turn-index {
    width: 32px;
    height: 28px;
  }

  .message-block header {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }
}
</style>

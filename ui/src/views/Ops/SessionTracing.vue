<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import {
  getTracingDetail,
  getTracingPage,
  getTracingRaw,
  getTracingSummary,
  getTracingUsers,
} from '@/api/sessionTracing'
import TracingConversation, {
  type TracingDetailTab,
} from '@/components/session-tracing/TracingConversation.vue'
import TracingInspector from '@/components/session-tracing/TracingInspector.vue'
import TracingSessionList from '@/components/session-tracing/TracingSessionList.vue'
import type { PageResult } from '@/types'
import type {
  TracingDetail,
  TracingPageItem,
  TracingRaw,
  TracingResultStatus,
  TracingSummary,
  TracingUser,
} from '@/types/sessionTracing'

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
const activeTab = ref<TracingDetailTab>('conversation')

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

function errorText(error: unknown, fallback: string): string {
  if (typeof error === 'string' && error.trim()) return error
  if (error instanceof Error && error.message) return error.message
  return fallback
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
  activeTab.value = key as TracingDetailTab
  if (activeTab.value === 'raw') await loadRaw()
}

async function refreshAll(): Promise<void> {
  await Promise.all([loadUsers(), loadPage(), loadSummary()])
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
      <TracingSessionList
        v-model:selected-user-id="selectedUserId"
        v-model:selected-status="selectedStatus"
        :users="users"
        :page-data="pageData"
        :selected-id="selectedId"
        :current-page="currentPage"
        :page-size="PAGE_SIZE"
        :users-loading="usersLoading"
        :list-loading="listLoading"
        :users-error="usersError"
        :list-error="listError"
        @filters-change="resetAndReload"
        @select="selectRecord"
        @page-change="handlePageChange"
      />
      <TracingConversation
        :detail="detail"
        :raw="currentRaw"
        :active-tab="activeTab"
        :detail-loading="detailLoading"
        :raw-loading="rawLoading"
        :detail-error="detailError"
        :raw-error="rawError"
        @tab-change="handleTabChange"
        @retry-raw="loadRaw(true)"
      />
      <TracingInspector
        :summary="summary"
        :selected-row="selectedRow"
        :loading="summaryLoading"
        :error="summaryError"
      />
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

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  line-height: 1.35;
  letter-spacing: 0;
}

.page-header p {
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

@media (max-width: 1180px) {
  .tracing-grid {
    grid-template-columns: 300px minmax(0, 1fr);
  }
}

@media (max-width: 760px) {
  :global(.app-layout:has(.tracing-page) > .sidebar) {
    display: none;
  }

  :global(.app-layout:has(.tracing-page) > .layout-content) {
    width: 100%;
    margin: 0 !important;
    border-radius: 0 !important;
  }

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
}
</style>

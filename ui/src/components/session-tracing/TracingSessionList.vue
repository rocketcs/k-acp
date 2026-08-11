<script setup lang="ts">
import dayjs from 'dayjs'
import type { PageResult } from '@/types'
import type {
  TracingPageItem,
  TracingResultStatus,
  TracingUser,
} from '@/types/sessionTracing'
import { formatTracingUserLabel, tracingStatusTone } from '@/utils/sessionTracing'

defineProps<{
  users: TracingUser[]
  pageData: PageResult<TracingPageItem>
  selectedUserId?: string
  selectedStatus?: TracingResultStatus
  selectedId: string | null
  currentPage: number
  pageSize: number
  usersLoading: boolean
  listLoading: boolean
  usersError: string | null
  listError: string | null
}>()

const emit = defineEmits<{
  'update:selectedUserId': [value: string | undefined]
  'update:selectedStatus': [value: TracingResultStatus | undefined]
  filtersChange: []
  select: [record: TracingPageItem]
  pageChange: [page: number]
}>()

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
</script>

<template>
  <aside class="list-panel panel-surface">
    <div class="filter-bar">
      <label>
        <span>用户</span>
        <ASelect
          :value="selectedUserId"
          :loading="usersLoading"
          allow-clear
          placeholder="全部用户"
          @update:value="emit('update:selectedUserId', $event)"
          @change="emit('filtersChange')"
        >
          <ASelectOption v-for="user in users" :key="user.userId" :value="user.userId">
            {{ formatTracingUserLabel(user) }} · {{ user.conversationCount }} 条
          </ASelectOption>
        </ASelect>
      </label>
      <label>
        <span>结果</span>
        <ASelect
          :value="selectedStatus"
          allow-clear
          placeholder="全部结果"
          @update:value="emit('update:selectedStatus', $event)"
          @change="emit('filtersChange')"
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
        @click="emit('select', record)"
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

    <div v-if="pageData.total > pageSize" class="pagination-wrap">
      <APagination
        :current="currentPage"
        :page-size="pageSize"
        :total="pageData.total"
        :show-size-changer="false"
        size="small"
        @change="emit('pageChange', $event)"
      />
    </div>
  </aside>
</template>

<style scoped lang="scss">
.panel-surface {
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e6e9ef;
  border-radius: 8px;
}

.list-panel {
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

.row-top,
.user-identity {
  display: flex;
  align-items: center;
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

@media (max-width: 760px) {
  .session-list {
    max-height: 420px;
  }
}
</style>

/**
 * 系统参数组件
 *
 * @author huxuehao
 */
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { SearchOutlined, EditOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons-vue'
import * as paramsApi from '@/api/params'
import type { Params } from '@/types'

const dataList = ref<Params[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const editingId = ref<string | null>(null)
const editingValue = ref('')

/** 前端过滤后的列表 */
const filteredList = computed(() => {
  const kw = searchKeyword.value?.trim().toLowerCase()
  if (!kw) return dataList.value
  return dataList.value.filter(
    (item) =>
      (item.paramName ?? '').toLowerCase().includes(kw) ||
      (item.paramKey ?? '').toLowerCase().includes(kw) ||
      (item.paramValue ?? '').toLowerCase().includes(kw)
  )
})

async function load() {
  loading.value = true
  try {
    const res = await paramsApi.page({
      current: 1,
      size: 500
    })
    const result = res.data.data
    dataList.value = result?.records ?? []
  } catch {
    console.error('加载系统参数失败')
  } finally {
    loading.value = false
  }
}

function startEdit(row: Params) {
  editingId.value = row.id
  editingValue.value = row.paramValue ?? ''
}

function cancelEdit() {
  editingId.value = null
  editingValue.value = ''
}

async function saveEdit(row: Params) {
  const newValue = editingValue.value
  try {
    loading.value = true
    await paramsApi.update({
      id: row.id,
      paramName: row.paramName ?? '',
      paramKey: row.paramKey,
      paramValue: newValue
    })
    message.success('保存成功')
    row.paramValue = newValue
    cancelEdit()
  } catch {
    console.error('保存失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
})
</script>

<template>
  <div>
    <div class="system-params-header">
      <h2 class="settings-page-title m-0">系统参数</h2>
      <div class="system-params-toolbar">
        <AInput
          v-model:value="searchKeyword"
          placeholder="搜索 名称 / key / value"
          style="width: 260px"
          allow-clear
        >
          <template #prefix>
            <SearchOutlined class="search-icon" />
          </template>
        </AInput>
      </div>
    </div>

    <ApboaSpin :spinning="loading">
      <div class="params-list">
        <div
          v-for="item in filteredList"
          :key="item.id"
          class="params-list-item"
          :class="{ editing: editingId === item.id }"
        >
          <div class="params-list-content">
            <div class="params-key-row">
              <span class="params-key">{{ item.paramName || '-' }}</span>
              <div class="params-list-actions">
                <template v-if="editingId === item.id">
                  <AButton
                    type="text"
                    size="small"
                    class="params-action-btn params-action-save"
                    @click="saveEdit(item)"
                  >
                    <CheckOutlined />保存
                  </AButton>
                  <AButton
                    type="text"
                    size="small"
                    class="params-action-btn"
                    @click="cancelEdit"
                  >
                    <CloseOutlined />取消
                  </AButton>
                </template>
                <AButton
                  v-else
                  type="text"
                  size="small"
                  class="params-action-btn params-action-edit"
                  @click="startEdit(item)"
                >
                  <EditOutlined />编辑
                </AButton>
              </div>

            </div>
            <div class="params-value-row">
              <template v-if="editingId === item.id">
                <ATextarea
                  v-model:value="editingValue"
                  placeholder="参数值"
                  size="small"
                  :rows="2"
                  class="params-edit-input"
                  @keyup.enter="saveEdit(item)"
                />
              </template>
              <span v-else class="params-value-text">{{ item.paramValue || '-' }}</span>

            </div>
          </div>
        </div>
      </div>

      <AEmpty
        v-if="!loading && filteredList.length === 0"
        :description="searchKeyword ? '暂无匹配的系统参数' : '暂无系统参数'"
      />
    </ApboaSpin>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;

.system-params-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-xl);
  flex-wrap: wrap;
  gap: var(--spacing-md);
}

.system-params-toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.search-icon {
  color: var(--color-text-placeholder);
  font-size: 14px;
}

.params-list {
  display: flex;
  flex-direction: column;
  gap: 0;
  max-height: calc(100vh - 190px);
  overflow-y: auto;
}

.params-list-item {
  padding: 12px;
  background: var(--color-bg-primary, #fff);
  border-radius: 10px;
  transition: box-shadow 0.2s ease, transform 0.2s ease, border-color 0.2s ease;
}

.params-list-content {
  min-width: 0;
}

// key + 名称行
.params-key-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-md);
  min-height: 22px;
}

.params-key {
  font-size: var(--font-size-base);
  // font-weight: 600;
  color: var(--color-text-primary);
  //font-family: var(--font-family-code, 'SF Mono', 'Menlo', monospace);
}

.params-name {
  font-size: var(--font-size-sm);
  color: var(--color-text-placeholder);
  margin-left: auto;
  flex-shrink: 0;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

// value + 操作行
.params-value-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-height: 28px;
}

.params-value-text {
  font-size: var(--font-size-base);
  // color: var(--color-primary);
  word-break: break-all;
  flex: 1;
  min-width: 0;
}

.params-edit-input {
  flex: 1;
  // min-width: 120px;
  // max-width: 480px;
}

.params-list-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  margin-left: auto;
  gap: var(--spacing-xs);
}

.params-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.params-action-edit {
  color: var(--color-primary);

  &:hover {
    color: var(--color-primary-hover);
  }
}

.params-action-save {
  color: #52c41a;

  &:hover {
    color: #73d13d;
  }
}
</style>

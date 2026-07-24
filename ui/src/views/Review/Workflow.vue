<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 审查工作流页面
 *
 * @author huxuehao
 */
import { ref } from 'vue'
import { AuditOutlined } from '@ant-design/icons-vue'

/**
 * 审查列表
 */
const reviewList = ref([
  { id: 1, name: '工作流1', status: '待审核', createTime: '2024-01-01' },
  { id: 2, name: '工作流2', status: '已审核', createTime: '2024-01-02' },
  { id: 3, name: '工作流3', status: '审核中', createTime: '2024-01-03' }
])
</script>

<template>
  <div class="review-page">
    <div class="page-header">
      <h1 class="page-title">
        <AuditOutlined />
        审查工作流
      </h1>
      <p class="page-desc">审核工作流配置和内容</p>
    </div>
    <div class="page-content">
      <div class="review-list">
        <div
          v-for="item in reviewList"
          :key="item.id"
          class="review-item"
        >
          <div class="review-info">
            <div class="review-name">{{ item.name }}</div>
            <div class="review-meta">
              <span class="review-status">{{ item.status }}</span>
              <span class="review-time">{{ item.createTime }}</span>
            </div>
          </div>
          <div class="review-actions">
            <AButton type="primary" size="small">审核</AButton>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.review-page {
  padding: 24px;
  background-color: #F5F6F8;
  min-height: 100%;
}

.page-header {
  margin-bottom: 24px;
}

.page-title {
  font-size: 24px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 8px 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-desc {
  font-size: 14px;
  color: #666;
  margin: 0;
}

.page-content {
  background: white;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.review-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.review-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  transition: all 0.2s ease;

  &:hover {
    border-color: #1677ff;
    background-color: #fafafa;
  }
}

.review-info {
  flex: 1;
}

.review-name {
  font-size: 16px;
  font-weight: 500;
  color: #1a1a1a;
  margin-bottom: 4px;
}

.review-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: #666;
}

.review-status {
  padding: 2px 8px;
  border-radius: 4px;
  background-color: #f0f0f0;
  font-size: 12px;
}

.review-actions {
  flex-shrink: 0;
}
</style>

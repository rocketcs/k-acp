<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 服务监控页面
 *
 * @author huxuehao
 */
import ExecutionNodes from '@/components/settings/ExecutionNodes.vue'
</script>

<template>
  <div class="ops-page">
    <div class="page-header">
      <h1 class="page-title">服务监控</h1>
      <p class="page-desc">监控系统服务运行状态</p>
    </div>
    <div class="page-content">
      <ExecutionNodes />
    </div>
  </div>
</template>

<style scoped lang="scss">
.ops-page {
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
</style>

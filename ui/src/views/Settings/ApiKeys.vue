<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * API Keys页面
 *
 * @author huxuehao
 */
import ApiKeys from '@/components/settings/ApiKeys.vue'
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <h1 class="page-title">API Keys</h1>
      <p class="page-desc">管理您的API密钥</p>
    </div>
    <div class="page-content">
      <ApiKeys />
    </div>
  </div>
</template>

<style scoped lang="scss">
.settings-page {
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

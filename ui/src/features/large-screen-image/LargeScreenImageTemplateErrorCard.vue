<script setup lang="ts">
defineProps<{
  reason: string
  canRetry: boolean
  onRetryAnalyze?: () => void
}>()
</script>

<template>
  <section class="large-screen-template-error-card" role="alert">
    <p class="large-screen-template-error-title">识图失败，可重试</p>
    <p class="large-screen-template-error-reason">模板结果未通过安全校验。</p>
    <button
      v-if="canRetry && onRetryAnalyze"
      class="large-screen-template-error-retry"
      type="button"
      @click="onRetryAnalyze"
    >重新识图</button>
  </section>
</template>

<style scoped>
.large-screen-template-error-card { max-width: 720px; padding: 16px; border: 1px solid #f0b7b7; border-radius: 10px; background: #fff8f8; color: #6e1b1b; }
.large-screen-template-error-title { margin: 0; font-weight: 700; }
.large-screen-template-error-reason { margin: 8px 0 0; font-size: 13px; }
.large-screen-template-error-retry { margin-top: 12px; padding: 6px 12px; border: 0; border-radius: 6px; background: #b42318; color: #fff; cursor: pointer; }
</style>

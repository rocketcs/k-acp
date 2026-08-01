<script setup lang="ts">
defineProps<{
  imageUrl: string
  messageId: string
  busy?: boolean
  onAnalyze: () => void
}>()
</script>

<template>
  <section class="large-screen-generated-card" :data-large-screen-generated-message-id="messageId">
    <img class="large-screen-generated-card-image" :src="imageUrl" alt="当前生成的大屏图片">
    <div class="large-screen-generated-card-actions">
      <span>可将此结果作为新的参考图重新识别</span>
      <button type="button" :disabled="busy" @click="onAnalyze">识别此生成图</button>
    </div>
  </section>
</template>

<style scoped>
.large-screen-generated-card { display: grid; gap: 10px; width: 100%; }.large-screen-generated-card:global(.large-screen-generated-card-highlighted) { animation: large-screen-generated-card-flash 1.8s ease-out; }.large-screen-generated-card-image { display: block; width: 100%; max-height: 720px; object-fit: contain; border-radius: 10px; background: #f1f5f9; }.large-screen-generated-card-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; color: #64748b; font-size: 12px; }.large-screen-generated-card-actions button { padding: 6px 10px; border: 1px solid #0369a1; border-radius: 6px; background: #fff; color: #075985; cursor: pointer; font: inherit; }.large-screen-generated-card-actions button:disabled { cursor: not-allowed; opacity: .55; }@keyframes large-screen-generated-card-flash { 0%, 55% { box-shadow: 0 0 0 4px rgb(14 165 233 / 80%); } 100% { box-shadow: 0 0 0 0 transparent; } }
</style>

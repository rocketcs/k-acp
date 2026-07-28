<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps<{
  startedAt?: number | null
}>()

defineEmits<{
  (e: 'abort'): void
}>()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

const elapsed = computed(() => {
  const startedAt = props.startedAt || now.value
  const totalSeconds = Math.max(0, Math.floor((now.value - startedAt) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`
})

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="agent-run-waiting" aria-live="polite" aria-label="智能体任务仍在运行">
    <span class="agent-run-waiting__pulse" aria-hidden="true"></span>
    <span>任务仍在运行，正在等待下一步结果</span>
    <time>已等待 {{ elapsed }}</time>
    <button type="button" @click="$emit('abort')">停止处理</button>
  </div>
</template>

<style scoped lang="scss">
.agent-run-waiting {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  margin: 4px 0 8px;
  padding: 8px 11px;
  border: 1px solid rgba(15, 116, 255, 0.12);
  border-radius: 10px;
  background: rgba(247, 251, 255, 0.82);
  color: #59718d;
  font-size: 12px;
  line-height: 1.35;

  time {
    margin-left: 4px;
    color: #8192a5;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }

  button {
    padding: 0;
    border: 0;
    background: transparent;
    color: #527fb6;
    font-size: 12px;
    cursor: pointer;

    &:hover { color: #0f5fc9; }
  }
}

.agent-run-waiting__pulse {
  width: 6px;
  height: 6px;
  flex: 0 0 6px;
  border-radius: 50%;
  background: #0f74ff;
  animation: run-waiting-pulse 1.5s ease-in-out infinite;
}

@keyframes run-waiting-pulse {
  0%, 100% { transform: scale(0.72); opacity: 0.45; }
  50% { transform: scale(1); opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .agent-run-waiting__pulse { animation: none; }
}
</style>

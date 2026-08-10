<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { CheckOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import type { RunActivity } from '@/types'
import { aggregateRunActivities } from '@/utils/chat/runActivity'

const props = defineProps<{
  activities: RunActivity[]
  startedAt?: number | null
}>()

defineEmits<{
  (e: 'abort'): void
}>()

const expanded = ref(false)
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

const aggregatedActivities = computed(() => aggregateRunActivities(props.activities))
const activeActivity = computed(() =>
  [...aggregatedActivities.value].reverse().find((activity) => activity.status === 'running'),
)
const completedCount = computed(() =>
  aggregatedActivities.value.filter((activity) => activity.status === 'completed').length,
)
const failedCount = computed(() =>
  aggregatedActivities.value.filter((activity) => activity.status === 'failed').length,
)
const elapsed = computed(() => {
  const startedAt = props.startedAt || now.value
  const seconds = Math.max(0, Math.floor((now.value - startedAt) / 1000))
  const minutes = Math.floor(seconds / 60)
  return minutes > 0 ? `${minutes} 分 ${seconds % 60} 秒` : `${seconds} 秒`
})
const headline = computed(() => activeActivity.value ? `正在${activeActivity.value.label}` : '正在启动工作流')
const visibleActivities = computed(() => expanded.value ? aggregatedActivities.value : aggregatedActivities.value.slice(-3))

function activityLabel(activity: ReturnType<typeof aggregateRunActivities>[number]) {
  if (activity.status === 'completed') return `${activity.label}完成`
  if (activity.status === 'failed') return `${activity.label}待重试`
  return `正在${activity.label}`
}

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <section class="agent-run-activity" aria-live="polite" aria-label="智能体正在处理请求">
    <div class="agent-run-activity__summary">
      <span class="agent-run-activity__pulse" aria-hidden="true"></span>
      <strong>{{ headline }}</strong>
      <span v-if="aggregatedActivities.length" class="agent-run-activity__metric">已完成 {{ completedCount }}/{{ aggregatedActivities.length }} 步</span>
      <span v-if="failedCount" class="agent-run-activity__metric is-warning">{{ failedCount }} 步待重试</span>
      <time>已等待 {{ elapsed }}</time>
    </div>
    <button type="button" class="agent-run-activity__abort" @click="$emit('abort')">停止处理</button>
    <button
      v-if="aggregatedActivities.length"
      type="button"
      class="agent-run-activity__details-toggle"
      @click="expanded = !expanded"
    >{{ expanded ? '收起进度' : '查看进度' }}</button>

    <div v-if="expanded && visibleActivities.length" class="agent-run-activity__details">
      <span v-for="activity in visibleActivities" :key="activity.id" :class="`is-${activity.status}`">
        <LoadingOutlined v-if="activity.status === 'running'" spin />
        <CheckOutlined v-else-if="activity.status === 'completed'" />
        <i v-else aria-hidden="true"></i>
        {{ activityLabel(activity) }}
        <em v-if="activity.count > 1">{{ activity.count }} 次</em>
      </span>
    </div>
  </section>
</template>

<style scoped lang="scss">
.agent-run-activity {
  display: flex;
  width: min(100%, 960px);
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin: 4px 0 8px;
  padding: 10px 14px;
  border: 1px solid #d9e7fb;
  border-radius: 10px;
  background: #f8fbff;
  color: #627b99;
  font-size: 13px;
  line-height: 1.4;
}

.agent-run-activity__summary {
  display: flex;
  min-width: 0;
  flex: 1 1 360px;
  align-items: center;
  gap: 10px;

  strong {
    overflow: hidden;
    color: #456989;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  time {
    margin-left: auto;
    color: #8194aa;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
}

.agent-run-activity__pulse {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: #5d9af8;
  animation: run-activity-pulse 1.5s ease-in-out infinite;
}

.agent-run-activity__metric {
  padding-left: 10px;
  border-left: 1px solid #dce8f6;
  color: #7890aa;
  white-space: nowrap;
}

.agent-run-activity__metric.is-warning { color: #9a7045; }

.agent-run-activity__abort,
.agent-run-activity__details-toggle {
  padding: 0;
  border: 0;
  background: transparent;
  color: #4f82bc;
  font: inherit;
  cursor: pointer;
  white-space: nowrap;

  &:hover { color: #245f9f; }
}

.agent-run-activity__abort {
  color: #6f849c;
  &:hover { color: #3e556e; }
}

.agent-run-activity__details {
  display: flex;
  width: 100%;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding-top: 8px;
  border-top: 1px solid #e5eef9;
  color: #6b8199;
  font-size: 12px;

  span { display: inline-flex; align-items: center; gap: 5px; }
  .is-running { color: #3977b7; }
  .is-completed { color: #5d7d6b; }
  .is-failed { color: #a1704a; }
  i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
  em { color: #8194aa; font-style: normal; }
}

@keyframes run-activity-pulse {
  0%, 100% { transform: scale(0.72); opacity: 0.45; }
  50% { transform: scale(1); opacity: 1; }
}

@media (max-width: 640px) {
  .agent-run-activity { align-items: flex-start; }
  .agent-run-activity__summary { flex-wrap: wrap; gap: 6px 10px; }
  .agent-run-activity__summary time { width: calc(100% - 17px); margin-left: 17px; }
  .agent-run-activity__details-toggle { margin-left: 17px; }
}

@media (prefers-reduced-motion: reduce) {
  .agent-run-activity__pulse { animation: none; }
}
</style>

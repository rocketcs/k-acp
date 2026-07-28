<script setup lang="ts">
import { computed, ref } from 'vue'
import { CheckOutlined, LoadingOutlined, ToolOutlined } from '@ant-design/icons-vue'
import type { RunActivity } from '@/types'
import { aggregateRunActivities } from '@/utils/chat/runActivity'

const props = defineProps<{
  activities: RunActivity[]
}>()

defineEmits<{
  (e: 'abort'): void
}>()

const expanded = ref(false)
const aggregatedActivities = computed(() => aggregateRunActivities(props.activities))
const visibleActivities = computed(() => {
  const activities = aggregatedActivities.value
  if (expanded.value || activities.length <= 4) return activities
  const latestActivity = activities[activities.length - 1]
  return latestActivity ? [...activities.slice(0, 3), latestActivity] : activities
})
const hiddenActivityCount = computed(() => Math.max(0, aggregatedActivities.value.length - visibleActivities.value.length))
const activeActivity = computed(() =>
  [...aggregatedActivities.value].reverse().find((activity) => activity.status === 'running'),
)

const headline = computed(() => {
  if (activeActivity.value) return `正在${activeActivity.value.label}`
  if (aggregatedActivities.value.length) return '正在整理查询结果'
  return '正在理解你的问题'
})

function activityLabel(activity: ReturnType<typeof aggregateRunActivities>[number]) {
  if (activity.status === 'completed') return `${activity.label}完成`
  if (activity.status === 'failed') return `${activity.label}未完成`
  return `正在${activity.label}`
}
</script>

<template>
  <section class="agent-run-activity" aria-live="polite" aria-label="智能体正在处理请求">
    <div class="agent-run-activity__halo" aria-hidden="true"></div>
    <div class="agent-run-activity__head">
      <span class="agent-run-activity__orb" aria-hidden="true"><span></span></span>
      <div>
        <strong>{{ headline }}</strong>
        <p>智能体正在为你处理，请稍候</p>
      </div>
    </div>

    <ol v-if="aggregatedActivities.length" class="agent-run-activity__steps">
      <li v-for="activity in visibleActivities" :key="activity.id" :class="`is-${activity.status}`">
        <span class="agent-run-activity__step-icon">
          <LoadingOutlined v-if="activity.status === 'running'" spin />
          <CheckOutlined v-else-if="activity.status === 'completed'" />
          <ToolOutlined v-else />
        </span>
        <span>{{ activityLabel(activity) }}</span>
        <span v-if="activity.count > 1" class="agent-run-activity__count">{{ activity.count }} 次</span>
        <time v-else-if="activity.elapsed">{{ Math.max(1, Math.ceil(activity.elapsed / 1000)) }} 秒</time>
      </li>
    </ol>
    <button
      v-if="hiddenActivityCount || expanded"
      type="button"
      class="agent-run-activity__toggle"
      @click="expanded = !expanded"
    >{{ expanded ? '收起步骤' : `查看其余 ${hiddenActivityCount} 个步骤` }}</button>
    <div v-else class="agent-run-activity__starting">
      <span></span><span></span><span></span>
      <em>准备执行工作流</em>
    </div>
    <button type="button" class="agent-run-activity__abort" @click="$emit('abort')">停止处理</button>
  </section>
</template>

<style scoped lang="scss">
.agent-run-activity {
  position: relative;
  width: min(100%, 620px);
  overflow: hidden;
  margin: 4px 0 8px;
  padding: 18px 20px 16px;
  border: 1px solid rgba(15, 116, 255, 0.16);
  border-radius: 18px;
  background: linear-gradient(135deg, #f8fbff 0%, #f1f7ff 100%);
  box-shadow: 0 10px 32px rgba(15, 116, 255, 0.08);
}

.agent-run-activity__halo {
  position: absolute;
  width: 130px;
  height: 130px;
  top: -74px;
  right: -36px;
  border-radius: 50%;
  background: rgba(15, 116, 255, 0.11);
  animation: activity-halo 2.4s ease-in-out infinite;
}

.agent-run-activity__head {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;

  strong {
    display: block;
    color: #173a69;
    font-size: 15px;
    font-weight: 650;
    line-height: 1.45;
  }

  p {
    margin: 2px 0 0;
    color: #6d819f;
    font-size: 12px;
  }
}

.agent-run-activity__orb {
  position: relative;
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #0f74ff;
  box-shadow: 0 0 0 0 rgba(15, 116, 255, 0.35);
  animation: activity-orb 1.9s ease-out infinite;

  span {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #fff;
    animation: activity-core 1.4s ease-in-out infinite;
  }
}

.agent-run-activity__steps {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 15px 0 0;
  padding: 12px 0 0;
  border-top: 1px solid rgba(15, 116, 255, 0.1);
  list-style: none;

  li {
    display: flex;
    align-items: center;
    gap: 8px;
    color: #52687f;
    font-size: 13px;
    line-height: 1.35;
  }

  .is-running { color: #0f5fc9; font-weight: 600; }
  .is-completed { color: #52705d; }
  .is-failed { color: #9a6b52; }

  time {
    margin-left: auto;
    color: #8b9bad;
    font-size: 11px;
    font-variant-numeric: tabular-nums;
  }
}

.agent-run-activity__step-icon {
  display: inline-flex;
  width: 17px;
  justify-content: center;
  color: currentColor;
  font-size: 12px;
}

.agent-run-activity__count {
  margin-left: auto;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(15, 116, 255, 0.08);
  color: #5a7598;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.agent-run-activity__toggle {
  position: relative;
  margin-top: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #3978c8;
  font-size: 12px;
  cursor: pointer;

  &:hover { color: #0f5fc9; }
}

.agent-run-activity__abort {
  position: relative;
  margin: 10px 0 0 12px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #708399;
  font-size: 12px;
  cursor: pointer;

  &:hover { color: #3e556e; }
}

.agent-run-activity__starting {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 15px;
  color: #6d819f;

  span {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #0f74ff;
    animation: activity-dot 1.25s ease-in-out infinite;
  }

  span:nth-child(2) { animation-delay: 0.15s; }
  span:nth-child(3) { animation-delay: 0.3s; }

  em {
    margin-left: 5px;
    font-size: 12px;
    font-style: normal;
  }
}

@keyframes activity-orb {
  0% { box-shadow: 0 0 0 0 rgba(15, 116, 255, 0.32); }
  70% { box-shadow: 0 0 0 10px rgba(15, 116, 255, 0); }
  100% { box-shadow: 0 0 0 0 rgba(15, 116, 255, 0); }
}

@keyframes activity-core {
  0%, 100% { transform: scale(0.72); opacity: 0.82; }
  50% { transform: scale(1); opacity: 1; }
}

@keyframes activity-halo {
  0%, 100% { transform: scale(0.94); opacity: 0.5; }
  50% { transform: scale(1.08); opacity: 0.9; }
}

@keyframes activity-dot {
  0%, 80%, 100% { transform: scale(0.65); opacity: 0.45; }
  40% { transform: scale(1); opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .agent-run-activity__halo,
  .agent-run-activity__orb,
  .agent-run-activity__orb span,
  .agent-run-activity__starting span { animation: none; }
}
</style>

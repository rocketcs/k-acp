<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { GraphifyDataQueryPage } from '@/features/graphify-data-query'
import * as agentApi from '@/api/agent'

const GRAPHIFY_DATA_QUERY_AGENT_CODE = 'default-graphify-data-query'

const agentId = ref('')
const loading = ref(true)
const loadError = ref('')

async function loadAgent() {
  loading.value = true
  loadError.value = ''
  agentId.value = ''
  try {
    const response = await agentApi.page({ agentCode: GRAPHIFY_DATA_QUERY_AGENT_CODE, page: 1, size: 2 })
    const agents = (response.data?.data?.records ?? []).filter((item) => item.agentCode === GRAPHIFY_DATA_QUERY_AGENT_CODE)
    if (agents.length > 1) throw new Error('Duplicate default-graphify-data-query agents')
    if (!agents[0]) {
      loadError.value = '智能问数助手尚未配置或未启用'
      return
    }
    agentId.value = String(agents[0].id)
  } catch (error) {
    loadError.value = error instanceof Error && error.message.includes('Duplicate')
      ? '检测到重复的智能问数助手，请联系管理员处理'
      : '智能问数助手加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void loadAgent() })
</script>

<template>
  <GraphifyDataQueryPage v-if="agentId" :agent-id="agentId" />
  <main v-else class="graphify-route-state" aria-live="polite">
    <ASpin v-if="loading" tip="正在加载智能问数助手…" />
    <section v-else>
      <p>{{ loadError }}</p>
      <AButton type="primary" @click="loadAgent">重新加载</AButton>
    </section>
  </main>
</template>

<style scoped lang="scss">
.graphify-route-state {
  display: grid;
  min-height: 100dvh;
  place-items: center;
  padding: 24px;
  background: #f2f5f5;
  color: #1b2b2f;
  text-align: center;
}

.graphify-route-state section {
  display: grid;
  gap: 16px;
  max-width: 32rem;
}

.graphify-route-state p { margin: 0; }
</style>

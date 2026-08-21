<script setup lang="ts">
import { computed } from 'vue'
import { DatabaseOutlined, ExperimentOutlined, MedicineBoxOutlined, SearchOutlined, ShareAltOutlined } from '@ant-design/icons-vue'
import { displayGraphifyLabel } from './evidenceAdapter'
import type { GraphifyEvidenceEnvelope } from './types'

const props = defineProps<{ evidence: GraphifyEvidenceEnvelope }>()

const path = computed(() => props.evidence.execution_path)
const hasPath = computed(() => Boolean(path.value))

// 优先展示服务端生成的阶段链路；对旧版（修复前落库、无 stages）数据，
// 依据 models/columns/source_record_count 降级生成一条基础链路。
const stagesToRender = computed(() => {
  const p = path.value
  if (!p) return []
  if (p.stages?.length) return p.stages
  const cols = (p.columns ?? []).length
  return [
    { step: 'agent', system: 'agent', title: 'Agent 语义分析', detail: `识别为医保目录问题（${p.intent || '—'}）`, icon: 'analysis' },
    { step: 'wren_mdl', system: 'wren_mdl', title: 'Wren 语义层（MDL）', detail: `命中的业务模型：${(p.models ?? []).join('、') || 'medical_catalog'}；查询字段 ${cols} 个`, icon: 'mdl' },
    { step: 'postgres', system: 'postgres', title: 'PostgreSQL 数据源', detail: '查询位于统一目录视图 medical_catalog', icon: 'postgres' },
    { step: 'neo4j', system: 'neo4j', title: 'Neo4j 语义图谱', detail: '来源追溯与语义关联', icon: 'neo4j' },
    { step: 'result', system: 'result', title: '查询结果', detail: `关联 ${p.source_record_count ?? 0} 条来源记录`, icon: 'result' },
  ]
})

// 每个阶段对应一个展示图标与强调色；不认识的 system 回退到通用图标。
const SYSTEM_META: Record<string, { icon: string; color: string; label: string }> = {
  agent: { icon: 'analysis', color: '#5b8def', label: 'Agent' },
  wren_mdl: { icon: 'mdl', color: '#2b6692', label: 'Wren MDL' },
  postgres: { icon: 'postgres', color: '#336791', label: 'PostgreSQL' },
  neo4j: { icon: 'neo4j', color: '#409fc7', label: 'Neo4j' },
  result: { icon: 'result', color: '#2fa46b', label: '结果' },
}

function renderIcon(system: string) {
  const key = system
  const map: Record<string, unknown> = {
    analysis: ExperimentOutlined,
    mdl: DatabaseOutlined,
    postgres: DatabaseOutlined,
    neo4j: ShareAltOutlined,
    result: MedicineBoxOutlined,
  }
  return map[key] ?? SearchOutlined
}
</script>

<template>
  <div v-if="hasPath" class="exec-path" aria-label="查询执行链路">
    <div class="exec-path-head">
      <SearchOutlined class="minus" />
      <div>
        <small>查询执行链路</small>
        <b>{{ path?.intent }}</b>
      </div>
    </div>

    <ol class="exec-path-stages">
      <li v-for="(stage, index) in stagesToRender" :key="stage.step + index" class="exec-path-stage">
        <div
          class="stage-rail"
          :style="{ '--rail': SYSTEM_META[stage.system]?.color ?? '#8aa0b5' }"
        >
          <span class="stage-dot">
            <component :is="renderIcon(stage.system)" class="stage-icon" />
          </span>
          <span v-if="index < stagesToRender.length - 1" class="stage-line" />
        </div>
        <div class="stage-body">
          <div class="stage-title">
            <em :style="{ color: SYSTEM_META[stage.system]?.color ?? '#5b7186' }">{{ SYSTEM_META[stage.system]?.label ?? stage.system }}</em>
            <b>{{ stage.title }}</b>
          </div>
          <p>{{ stage.detail }}</p>
        </div>
      </li>
    </ol>

    <div class="exec-path-foot">
      <span>MDL 模型：{{ (path?.models ?? []).map((m) => displayGraphifyLabel(m, m)).join('、') || 'medical_catalog' }}</span>
      <span>查询字段：{{ (path?.columns ?? []).length }} 个</span>
      <span>来源记录：{{ path?.source_record_count }} 条</span>
    </div>
  </div>

  <div v-else class="exec-path-empty">
    <MedicineBoxOutlined />
    <p>本次回答未返回可解析的执行链路信息。</p>
  </div>
</template>

<style scoped lang="scss">
.exec-path { display: grid; gap: 12px; }
.exec-path-head { display: flex; align-items: flex-start; gap: 9px; padding: 11px 12px; border: 1px solid #d7e4e8; border-radius: 6px; background: linear-gradient(135deg, #f2f8fb, #eaf4fb); }
.exec-path-head > .minus { margin-top: 2px; color: #2b6692; font-size: 15px; }
.exec-path-head small { display: block; color: #7c8e99; font-size: 10px; }
.exec-path-head b { display: block; margin-top: 2px; color: #21455f; font-size: 13px; line-height: 1.4; }

.exec-path-stages { display: grid; margin: 0; padding: 2px 0 0 4px; list-style: none; }
.exec-path-stage { display: flex; gap: 11px; min-height: 0; }
.stage-rail { display: flex; flex: 0 0 auto; flex-direction: column; align-items: center; width: 30px; }
.stage-dot { display: grid; width: 26px; height: 26px; flex: 0 0 auto; place-items: center; border-radius: 50%; background: color-mix(in srgb, var(--rail) 14%, #fff); color: var(--rail); }
.stage-icon { font-size: 14px; }
.stage-line { width: 2px; flex: 1; margin: 3px 0 2px; background: linear-gradient(to bottom, color-mix(in srgb, var(--rail) 40%, #fff), #dfe7ec); }
.stage-body { min-width: 0; padding: 1px 0 14px; }
.stage-title { display: flex; align-items: baseline; gap: 8px; }
.stage-title em { font-size: 10px; font-style: normal; font-weight: 700; letter-spacing: 0.4px; }
.stage-title b { color: #2b4a58; font-size: 13px; }
.stage-body p { margin: 3px 0 0; color: #5f7480; font-size: 11px; line-height: 1.65; overflow-wrap: anywhere; }

.exec-path-foot { display: flex; flex-wrap: wrap; gap: 6px 14px; padding: 9px 12px; border-top: 1px dashed #d5e2e6; color: #718896; font-size: 11px; }
.exec-path-empty { display: grid; place-items: center; gap: 8px; min-height: 120px; padding: 12px; color: #6a858f; text-align: center; font-size: 12px; }
</style>

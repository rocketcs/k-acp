<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import {
  AimOutlined, BranchesOutlined, CompressOutlined, DatabaseOutlined, DeploymentUnitOutlined, ExperimentOutlined, FieldStringOutlined, FilterOutlined,
  FullscreenExitOutlined, FullscreenOutlined, MedicineBoxOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, SendOutlined,
  ShareAltOutlined, ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons-vue'
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'
import GraphifyEvidenceGraph from './GraphifyEvidenceGraph.vue'
import { displayGraphifyLabel, displayGraphifyNodeLabel } from './evidenceAdapter'
import { useGraphifyDataQueryChat } from './useGraphifyDataQueryChat'

const props = defineProps<{ agentId: string }>()
const agentId = computed(() => props.agentId)
const {
  sessions, sessionsLoading, currentSessionId, displayMessages, isRunning, streamingContent, activeEvidence, activeOutcome,
  selectedAssistantMessageId, chooseSession, startNewSession, sendQuestion,
} = useGraphifyDataQueryChat(agentId)

const question = ref('')
const graphOpen = ref(true)
const fullscreen = ref(false)
const panelWidth = ref(470)
const activeTab = ref<'graph' | 'mdl'>('graph')
const relationFilter = ref<'all' | 'business' | 'provenance' | 'semantic'>('all')
const mdlSearch = ref('')
const selectedId = ref<string | null>(null)
const graphRef = ref<InstanceType<typeof GraphifyEvidenceGraph> | null>(null)
const viewMode = ref<'focused' | 'full'>('focused')
const showFields = ref(false)
const selectedNode = computed(() => activeEvidence.value?.evidence.nodes.find((node) => node.id === selectedId.value) ?? activeEvidence.value?.evidence.nodes[0])
const selectedNodeSummary = computed(() => {
  const evidence = activeEvidence.value
  const node = selectedNode.value
  if (!evidence || !node) return null
  const nodeById = new Map(evidence.evidence.nodes.map((item) => [item.id, item]))
  const edges = evidence.evidence.edges.filter((edge) => edge.source === node.id || edge.target === node.id)
  const relations = [...new Set(edges.map((edge) => {
    const related = nodeById.get(edge.source === node.id ? edge.target : edge.source)
    if (!related) return ''
    const direction = edge.source === node.id ? '关联到' : '来自'
    const relation = displayGraphifyLabel(edge.label, displayGraphifyLabel(edge.kind, '业务关联'))
    return `${direction}${displayGraphifyNodeLabel(related)}（${relation}）`
  }).filter(Boolean))]
  return {
    type: ({ model: '业务模型', record: '查询记录', entity: '业务实体', source: '来源记录', product: '查询目录项', registration: '注册备案', organization: '耗材企业', base: '基础耗材', concept: '映射概念', catalog_record: '原始目录记录', source_file: '来源工作簿', import_batch: '导入批次' } as Record<string, string>)[node.kind] ?? '业务实体',
    label: displayGraphifyNodeLabel(node),
    edgeCount: edges.length,
    relations: relations.slice(0, 4),
    extraCount: Math.max(0, relations.length - 4),
  }
})
const selectedTurn = computed(() => selectedAssistantMessageId.value)
const resultColumns = computed(() => activeEvidence.value?.result.columns.map((key) => ({ key, label: displayGraphifyLabel(key) })) ?? [])
const resultState = computed<'idle' | 'running' | 'executed' | 'blocked' | 'unavailable'>(() => {
  if (isRunning.value) return 'running'
  if (activeEvidence.value) return 'executed'
  if (activeOutcome.value) return activeOutcome.value.status
  return 'idle'
})
const mdlModels = computed(() => {
  const evidence = activeEvidence.value
  if (!evidence) return []
  const query = mdlSearch.value.trim().toLocaleLowerCase()
  const models = evidence.semantic_context.recommended_models.map((name) => ({
    name, description: evidence.semantic_context.graph_version,
    fields: evidence.semantic_context.recommended_columns,
  }))
  return query ? models.filter((model) => `${model.name} ${model.description} ${model.fields.join(' ')}`.toLocaleLowerCase().includes(query)) : models
})

async function submit() {
  const sent = await sendQuestion(question.value)
  if (sent) question.value = ''
}
async function focusGraph() {
  graphOpen.value = true
  activeTab.value = 'graph'
  await nextTick()
  graphRef.value?.fit()
}
function onViewModeChange(mode: 'focused' | 'full') {
  viewMode.value = mode
  nextTick(() => graphRef.value?.relayout())
}
function onResizeStart(event: PointerEvent) {
  const initial = panelWidth.value
  const startX = event.clientX
  const move = (moveEvent: PointerEvent) => { panelWidth.value = Math.min(760, Math.max(360, initial + startX - moveEvent.clientX)) }
  const stop = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop) }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop, { once: true })
}
</script>

<template>
  <main class="graphify-page" :class="{ 'is-fullscreen': fullscreen }" :data-agent-id="agentId">
    <aside class="session-rail" aria-label="查询会话">
      <div class="brand-mark"><MedicineBoxOutlined /><strong>医疗目录</strong></div>
      <button class="new-session" type="button" :disabled="isRunning" @click="startNewSession"><PlusOutlined /> 新建对话</button>
      <span class="rail-label">历史会话</span>
      <div class="session-list" tabindex="0" aria-label="历史会话列表">
        <button v-for="session in sessions" :key="session.id" class="session-item" :class="{ active: String(session.id) === currentSessionId }" :disabled="isRunning" @click="chooseSession(session)">{{ session.title || '新对话' }}</button>
        <p v-if="sessionsLoading" class="rail-empty">正在加载会话…</p>
        <p v-else-if="!sessions.length" class="rail-empty">尚无历史会话</p>
      </div>
      <p class="rail-foot">Wren MDL + Neo4j<br>受治理数据查询</p>
    </aside>

    <section class="conversation-pane">
      <header class="conversation-head"><div class="page-title"><MedicineBoxOutlined /><div><h1>医疗目录智能检索</h1><p>WREN MDL + NEO4J / GOVERNED EVIDENCE</p></div></div><div class="head-status"><b>{{ isRunning ? '正在查询' : activeEvidence ? `${activeEvidence.result.rows.length} 条结果` : '等待查询' }}</b><span>{{ activeEvidence ? `追踪 ${activeEvidence.trace_id}` : '实时证据链' }}</span></div><button class="icon-btn" title="打开语义依据" @click="graphOpen = true"><ShareAltOutlined /></button></header>
      <div class="conversation-body">
        <section v-if="!displayMessages.length" class="empty-conversation"><MedicineBoxOutlined /><h2>从业务问题开始</h2><p>查询结果、语义依据和来源记录将在同一轮对话中展示。</p><div class="medical-domains" aria-label="可查询业务范围"><span><ExperimentOutlined /> 药品</span><span><DeploymentUnitOutlined /> 耗材</span><span><DatabaseOutlined /> 医疗目录</span></div></section>
        <article v-for="message in displayMessages" :key="message.id" :class="message.role === 'user' ? 'user-message' : 'answer'" @click="message.role === 'assistant' && (selectedAssistantMessageId = String(message.id))">
          <template v-if="message.role === 'user'">{{ message.content }}</template>
          <template v-else>
            <header><h2>智能问数结果</h2><span :class="{ selected: selectedTurn === String(message.id) }">{{ selectedTurn === String(message.id) ? '当前证据' : '选择查看证据' }}</span></header>
            <MarkdownRenderer
              v-if="message.content"
              class="answer-text markdown-answer"
              :content="message.content"
              :is-streaming="isRunning && selectedTurn === String(message.id)"
              :is-diy-chat="true"
            />
            <p v-else class="answer-text">正在整理查询结果…</p>
            <template v-if="selectedTurn === String(message.id) && activeEvidence">
              <div v-if="activeEvidence.semantic_context.rules.length" class="warning"><div v-for="rule in activeEvidence.semantic_context.rules" :key="rule.code">{{ rule.message }}</div></div>
              <div v-if="activeEvidence.result.rows.length" class="result-table">
                <table>
                  <thead><tr><th v-for="column in resultColumns" :key="column.key">{{ column.label }}</th></tr></thead>
                  <tbody><tr v-for="(row, index) in activeEvidence.result.rows" :key="index"><td v-for="column in resultColumns" :key="column.key" :title="String(row[column.key] ?? '')">{{ row[column.key] ?? '-' }}</td></tr></tbody>
                </table>
              </div>
              <p v-else class="inline-state">本次查询未返回业务记录。</p>
              <footer><button class="evidence-link" @click.stop="focusGraph"><ShareAltOutlined /> 查看语义依据</button><span>Trace: {{ activeEvidence.trace_id }}{{ activeEvidence.result.truncated ? ' · 结果已截断' : '' }}</span></footer>
            </template>
            <p v-else-if="selectedTurn === String(message.id) && activeOutcome" class="state-error">{{ activeOutcome.status === 'blocked' ? '该查询已被安全规则拦截，未执行任何数据操作。' : '语义依据暂不可用，请稍后重试。' }} {{ activeOutcome.reason || '' }} {{ activeOutcome.trace_id ? `Trace: ${activeOutcome.trace_id}` : '' }}</p>
          </template>
        </article>
        <section v-if="resultState === 'running' && !streamingContent" class="inline-state">正在调用受治理数据服务并生成证据链…</section>
        <section v-if="resultState === 'idle' && displayMessages.some((message) => message.role === 'assistant') && !activeEvidence && !activeOutcome" class="inline-state">该回答未返回可验证的 MCP 查询证据。</section>
      </div>
      <form class="composer" @submit.prevent="submit"><textarea v-model="question" :disabled="isRunning" aria-label="继续追问" placeholder="继续追问，或输入一个新的业务问题" /><button type="submit" :disabled="!question.trim() || isRunning" title="发送当前问题" aria-label="发送当前问题"><SendOutlined /></button></form>
    </section>

    <aside v-show="graphOpen" class="evidence-panel" :style="{ width: fullscreen ? '100vw' : `${panelWidth}px` }" aria-label="语义依据">
      <div v-if="!fullscreen" class="resize-handle" role="separator" aria-orientation="vertical" @pointerdown="onResizeStart" />
      <header class="panel-head"><h2>语义依据</h2><div><button class="icon-btn" :title="fullscreen ? '退出图谱大屏' : '图谱大屏查看'" @click="fullscreen = !fullscreen"><FullscreenExitOutlined v-if="fullscreen" /><FullscreenOutlined v-else /></button><button class="icon-btn" title="关闭" @click="graphOpen = false"><CompressOutlined /></button></div></header>
      <div class="panel-tabs" role="tablist"><button :class="{ active: activeTab === 'graph' }" @click="activeTab = 'graph'">当前问题</button><button :class="{ active: activeTab === 'mdl' }" @click="activeTab = 'mdl'">MDL 结构</button></div>
      <section class="evidence-scroll">
        <template v-if="activeTab === 'graph'">
          <template v-if="activeEvidence">
            <header v-if="!fullscreen" class="question-summary"><small>当前对话轮次 · {{ activeEvidence.trace_id }}</small><strong>{{ activeEvidence.question }}</strong><span>{{ activeEvidence.result.rows.length }} 条结果 · {{ activeEvidence.evidence.source_record_ids.length }} 条来源记录</span></header>
            <div class="legend"><span><DatabaseOutlined class="blue" /> Wren MDL</span><span><MedicineBoxOutlined class="teal" /> 业务实体</span><span><ShareAltOutlined class="green" /> 来源追溯</span><span><DeploymentUnitOutlined class="plum" /> 业务关系</span></div>
            <div class="graph-workspace"><GraphifyEvidenceGraph ref="graphRef" :evidence="activeEvidence" :relation-filter="relationFilter" :fullscreen="fullscreen" :view-mode="viewMode" :show-fields="showFields" @select="selectedId = $event" @update:view-mode="onViewModeChange" /><div class="graph-tools" aria-label="图谱工具栏"><button class="icon-btn" title="放大" @click="graphRef?.zoomIn()"><ZoomInOutlined /></button><button class="icon-btn" title="缩小" @click="graphRef?.zoomOut()"><ZoomOutOutlined /></button><button class="icon-btn" title="适应画布" @click="graphRef?.fit()"><AimOutlined /></button><button class="icon-btn" title="重新布局" @click="graphRef?.relayout()"><ReloadOutlined /></button><button class="icon-btn" :class="{ active: viewMode === 'full' }" :title="viewMode === 'full' ? '收起为聚焦视图' : `查看全部 ${activeEvidence.evidence.nodes.filter((n) => n.kind !== 'record' && n.kind !== 'source').length} 个节点`" @click="viewMode = viewMode === 'full' ? 'focused' : 'full'"><BranchesOutlined /></button><button class="icon-btn" :class="{ active: showFields }" title="显示/隐藏语义字段" @click="showFields = !showFields"><FieldStringOutlined /></button><button class="icon-btn" :title="fullscreen ? '退出图谱大屏' : '图谱大屏查看'" @click="fullscreen = !fullscreen"><FullscreenExitOutlined v-if="fullscreen" /><FullscreenOutlined v-else /></button></div><div class="graph-actions"><label><FilterOutlined /><select v-model="relationFilter" aria-label="筛选图谱关系"><option value="all">全部关系</option><option value="business">业务关系</option><option value="provenance">来源追溯</option><option value="semantic">语义关系</option></select></label></div></div>
            <section v-if="!fullscreen && selectedNodeSummary" class="node-summary"><header><span class="node-summary-type">{{ selectedNodeSummary.type }}</span><b>已选节点</b><span class="node-summary-count">{{ selectedNodeSummary.edgeCount }} 个关联</span></header><h3>{{ selectedNodeSummary.label }}</h3><div v-if="selectedNodeSummary.relations.length" class="node-summary-relations"><span v-for="relation in selectedNodeSummary.relations" :key="relation">{{ relation }}</span><span v-if="selectedNodeSummary.extraCount">另有 {{ selectedNodeSummary.extraCount }} 条关联</span></div><p v-else>当前节点暂无可展示的关联。</p></section>
          </template>
          <section v-else class="panel-empty"><DatabaseOutlined /><p>{{ activeOutcome ? '当前问题没有可展示的图谱证据。' : '选择一个带 MCP 查询证据的回答后，图谱将在这里显示。' }}</p></section>
        </template>
        <section v-else class="mdl-view">
          <template v-if="activeEvidence"><p>当前查询使用的受治理语义模型、字段与来源。</p><label class="mdl-search"><SearchOutlined /><input v-model="mdlSearch" type="search" placeholder="搜索模型或字段" aria-label="搜索 MDL 模型或字段"></label><div class="mdl-tree"><article v-for="model in mdlModels" :key="model.name" class="mdl-model"><header><DatabaseOutlined /><div><b>{{ displayGraphifyLabel(model.name, '业务模型') }}</b><small>{{ model.description }}</small></div></header><ul><li v-for="field in model.fields" :key="field">{{ displayGraphifyLabel(field) }}</li></ul></article></div><p v-if="!mdlModels.length" class="mdl-empty">未匹配到模型或字段。</p><section class="provenance"><b>来源</b><dl><div v-for="(source, key) in activeEvidence.semantic_context.provenance" :key="key"><dt>{{ displayGraphifyLabel(String(key), '来源说明') }}</dt><dd>{{ source }}</dd></div></dl></section></template>
          <p v-else class="panel-empty">当前没有可展示的 MDL 结构。</p>
        </section>
      </section>
    </aside>
  </main>
</template>

<style scoped lang="scss">
.graphify-page { --ink:#183034; --green:#0d6a5c; --line:#d5e1df; display:grid; grid-template-columns:260px minmax(520px,1fr) auto; grid-template-rows:minmax(0,1fr); height:100dvh; overflow:hidden; background:#f6f9f8; color:var(--ink); font-family:"PingFang SC","Microsoft YaHei",sans-serif; }
.session-rail { display:flex; min-height:0; flex-direction:column; gap:5px; padding:18px 14px 14px; overflow:hidden; background:#1e3033; color:#dbe9e5; }.brand-mark { display:flex; align-items:center; gap:10px; padding:0 8px 18px; color:#fff; font-size:17px; }.brand-mark svg { padding:6px; border-radius:4px; background:#dff2ed; color:#0d6a5c; font-size:23px; }.new-session { height:38px; flex:0 0 auto; border:1px solid #b7d5cc; border-radius:4px; background:#dcefe9; color:#183e3b; font-weight:700; cursor:pointer; }.rail-label { flex:0 0 auto; margin:20px 8px 6px; color:#9bb2b1; font-size:11px; }.session-list { display:grid; min-height:0; flex:1; gap:3px; overflow-y:auto; overscroll-behavior:contain; padding:0 0 6px; scrollbar-color:#66817d transparent; }.session-list:focus { outline:1px solid rgb(134 207 189 / 60%); outline-offset:2px; }.session-item { min-height:42px; overflow:hidden; border:0; border-radius:3px; background:transparent; color:#c7d8d4; text-align:left; text-overflow:ellipsis; white-space:nowrap; cursor:pointer; }.session-item:hover,.session-item.active { padding-left:9px; background:rgb(130 194 180 / 18%); color:#fff; box-shadow:inset 2px 0 #86cfbd; }.rail-empty { padding:0 8px; color:#9eb4b1; font-size:12px; }.rail-foot { flex:0 0 auto; margin:0; padding:14px 8px 0; border-top:1px solid rgb(255 255 255 / 14%); color:#9eb4b1; font-size:11px; line-height:1.65; }
.conversation-pane { display:flex; min-width:0; min-height:0; flex-direction:column; background:#fbfcfc; }.conversation-head { display:flex; align-items:center; gap:25px; min-height:68px; padding:0 24px; border-bottom:1px solid var(--line); background:#fff; }.conversation-head h1 { margin:0; font-family:STSong,"Songti SC",serif; font-size:19px; }.conversation-head p { margin:3px 0 0; color:#64767a; font-size:11px; }.head-status { display:flex; gap:10px; color:#64767a; font-size:11px; }.head-status b { color:#176958; }.icon-btn { display:grid; width:34px; height:34px; place-items:center; border:1px solid transparent; border-radius:4px; background:transparent; color:#45636a; cursor:pointer; }.icon-btn:hover { border-color:#b9d9d1; background:#e7f3ef; color:var(--green); }.conversation-head .icon-btn { margin-left:auto; }.conversation-body { flex:1; min-height:0; overflow:auto; padding:34px max(30px,9%); }.conversation-body > * { max-width:780px; margin-right:auto; margin-left:auto; }.empty-conversation,.panel-empty { display:grid; place-items:center; gap:10px; min-height:210px; color:#6a7d7b; text-align:center; }.empty-conversation svg,.panel-empty svg { color:#218a78; font-size:30px; }.empty-conversation h2 { margin:0; font-size:17px; }.empty-conversation p { margin:0; font-size:13px; }.user-message { width:max-content; max-width:86%; margin-right:0; margin-bottom:24px; padding:12px 15px; border:1px solid #d5e4e0; border-radius:5px 2px 5px 5px; background:#e5f0ee; line-height:1.65; white-space:pre-wrap; }.answer { max-width:680px; margin-bottom:25px; cursor:pointer; }.answer header,.answer footer { display:flex; align-items:baseline; justify-content:space-between; gap:12px; }.answer h2 { margin:0; font-size:15px; }.answer header span { color:#728188; font-size:11px; }.answer header .selected { color:#176958; font-weight:700; }.answer-text { min-width:0; margin:12px 0; line-height:1.72; overflow-wrap:anywhere; }.markdown-answer :deep(p) { margin:0 0 10px; }.markdown-answer :deep(p:last-child) { margin-bottom:0; }.markdown-answer :deep(ul),.markdown-answer :deep(ol) { margin:8px 0; padding-left:22px; }.markdown-answer :deep(li + li) { margin-top:4px; }.markdown-answer :deep(strong) { color:#0c564b; }.markdown-answer :deep(a) { color:#087f87; overflow-wrap:anywhere; }.markdown-answer :deep(pre) { max-width:100%; overflow:auto; padding:12px; border:1px solid #d7e3e0; border-radius:4px; background:#f1f6f5; }.markdown-answer :deep(code) { overflow-wrap:anywhere; }.markdown-answer :deep(.md-table-wrapper) { max-width:100%; overflow-x:auto; margin:12px 0; border:1px solid #d7e3e0; border-radius:4px; }.markdown-answer :deep(table) { min-width:max-content; border-collapse:collapse; }.markdown-answer :deep(th),.markdown-answer :deep(td) { padding:7px 10px; border-bottom:1px solid #e4ecea; text-align:left; vertical-align:top; overflow-wrap:anywhere; }.markdown-answer :deep(th) { background:#eff6f3; color:#627973; font-size:11px; }.warning,.state-error,.inline-state { padding:9px 12px; border-left:3px solid #ae6d16; background:#fbf6ed; color:#50605d; font-size:12px; line-height:1.6; }.state-error { border-color:#b6523a; background:#fff5f2; }.inline-state { margin-top:14px; }.result-table { overflow-x:auto; border:1px solid #d7e3e0; background:#fff; }.result-table table { width:100%; min-width:600px; border-collapse:collapse; table-layout:fixed; }.result-table th,.result-table td { min-width:120px; max-width:220px; padding:7px 10px; border-top:1px solid #e4ecea; color:#425b56; font-size:11px; overflow:hidden; text-align:left; text-overflow:ellipsis; white-space:nowrap; }.result-table th { border-top:0; background:#eff6f3; color:#627973; font-size:10px; font-weight:700; }.answer footer { margin-top:12px; color:#738188; font-size:11px; }.evidence-link { display:inline-flex; align-items:center; gap:6px; border:0; background:#e7f4ef; color:#106c5e; font-weight:700; cursor:pointer; }.composer { display:flex; gap:10px; padding:13px max(24px,9%) 20px; border-top:1px solid var(--line); background:#fff; }.composer textarea { flex:1; max-width:780px; min-height:48px; max-height:120px; margin:auto; padding:12px 52px 12px 14px; border:1px solid #c6d5d2; border-radius:5px; color:var(--ink); resize:vertical; }.composer button { width:38px; height:38px; margin:auto 0 auto -54px; border:0; border-radius:4px; background:#0f6a5e; color:#fff; cursor:pointer; }.composer button:disabled,.new-session:disabled,.session-item:disabled { cursor:not-allowed; opacity:.5; }
.evidence-panel { position:relative; display:flex; min-width:360px; min-height:0; flex-direction:column; border-left:1px solid var(--line); background:#fbfcfc; }.resize-handle { position:absolute; z-index:10; top:0; bottom:0; left:-6px; width:12px; cursor:col-resize; }.resize-handle:hover { border-left:2px solid #087f87; }.panel-head { display:flex; align-items:center; justify-content:space-between; min-height:68px; padding:0 16px 0 19px; border-bottom:1px solid var(--line); }.panel-head h2 { margin:0; font-family:STSong,"Songti SC",serif; font-size:17px; }.panel-head div { display:flex; }.panel-tabs { display:grid; grid-template-columns:1fr 1fr; height:44px; border-bottom:1px solid var(--line); background:#fff; }.panel-tabs button { border:0; border-bottom:2px solid transparent; background:transparent; color:#627478; cursor:pointer; }.panel-tabs button.active { border-bottom-color:var(--green); color:var(--green); font-weight:700; }.evidence-scroll { flex:1; min-height:0; overflow:auto; padding:16px; }.question-summary { display:grid; gap:6px; padding-bottom:13px; border-bottom:1px solid var(--line); }.question-summary small { color:#66787c; font-size:11px; }.question-summary strong { font-size:13px; line-height:1.5; }.question-summary span { width:max-content; padding:3px 6px; background:#e5f2ee; color:#176548; font-size:11px; font-weight:700; }.legend { display:flex; flex-wrap:wrap; gap:9px; margin:14px 0 10px; color:#62767a; font-size:11px; }.legend i { display:inline-block; width:7px; height:7px; margin-right:3px; border-radius:50%; }.blue{background:#2b6692}.orange{background:#a86a17}.teal{background:#087f87}.green{background:#136c61}.plum{background:#805e93}.graph-workspace { position:relative; height:440px; overflow:hidden; border:1px solid #bfd2cf; border-radius:4px; background:#f2f8f7; }.graph-tools { position:absolute; z-index:6; top:10px; right:10px; display:flex; gap:3px; padding:3px; border:1px solid #ceddda; border-radius:4px; background:rgb(255 255 255 / 94%); box-shadow:0 4px 14px rgb(31 58 58 / 8%); }.graph-tools .icon-btn { width:27px; height:27px; }.graph-tools .icon-btn.active { border-color:#abd0c4; background:#e7f4ef; color:var(--green); }.graph-actions { position:absolute; z-index:6; left:10px; bottom:10px; display:flex; align-items:center; padding:4px; border:1px solid #ceddda; border-radius:4px; background:rgb(255 255 255 / 94%); }.graph-actions label { display:flex; align-items:center; gap:5px; padding-right:5px; color:#1e766c; }.graph-actions select { border:0; outline:0; background:transparent; color:#526863; font-size:11px; }.selection-line { display:flex; justify-content:space-between; gap:12px; margin:10px 0 15px; color:#0e7061; font-size:11px; }.node-inspector { padding-top:15px; border-top:1px solid var(--line); }.node-inspector header { display:flex; justify-content:space-between; font-size:12px; }.node-inspector h3 { margin:10px 0 5px; font-family:STSong,"Songti SC",serif; font-size:17px; overflow-wrap:anywhere; }.node-inspector dl,.provenance dl { display:grid; gap:9px; margin:13px 0 0; }.node-inspector dl div,.provenance dl div { padding-left:7px; border-left:2px solid #b9d9d1; }.node-inspector dt,.provenance dt { color:#718187; font-size:10px; }.node-inspector dd,.provenance dd { margin:3px 0 0; overflow-wrap:anywhere; font-size:11px; font-weight:650; }.node-inspector p { color:#52615d; font-size:12px; line-height:1.55; overflow-wrap:anywhere; }.mdl-view { display:grid; gap:14px; }.mdl-view > p { margin:0; color:#586967; font-size:12px; line-height:1.6; }.mdl-search { display:flex; align-items:center; gap:8px; height:36px; padding:0 10px; border:1px solid #cddbd8; border-radius:4px; background:#fff; color:#17695c; }.mdl-search input { min-width:0; width:100%; border:0; outline:0; color:var(--ink); background:transparent; font-size:12px; }.mdl-tree { display:grid; gap:8px; }.mdl-model { border:1px solid #d1dfdc; border-radius:4px; background:#fff; }.mdl-model header { display:flex; align-items:center; gap:9px; padding:10px; color:#2b6692; }.mdl-model header div { display:grid; gap:2px; color:var(--ink); }.mdl-model header b { font-size:12px; }.mdl-model header small { color:#70807c; font-size:10px; }.mdl-model ul { display:grid; gap:3px; margin:0; padding:7px 10px 9px 34px; border-top:1px solid #e2ebea; color:#58706a; font-size:11px; list-style:square; }.mdl-empty { padding:12px; border-left:3px solid #a86a17; background:#f8eedf; }.provenance { padding-top:12px; border-top:1px solid var(--line); font-size:12px; }.is-fullscreen { display:block; }.is-fullscreen .session-rail,.is-fullscreen .conversation-pane { display:none; }.is-fullscreen .evidence-panel { width:100% !important; height:100dvh; }.is-fullscreen .evidence-scroll { display:flex; flex-direction:column; overflow:hidden; }.is-fullscreen .graph-workspace { flex:1; height:auto; min-height:0; }
@media (max-width:980px) { .graphify-page { grid-template-columns:210px minmax(0,1fr); }.evidence-panel { position:fixed; z-index:30; top:0; right:0; bottom:0; box-shadow:-14px 0 36px rgb(14 38 37 / 16%); }.resize-handle { display:none; } } @media (max-width:680px) { .graphify-page { display:block; }.session-rail { display:none; }.conversation-head { padding:0 15px; }.head-status { display:none; }.conversation-body { padding:22px 16px; }.evidence-panel { width:100% !important; }.graph-workspace { height:430px; } }
 .node-summary { margin-top:12px; padding:11px 0 2px; border-top:1px solid var(--line); }
 .node-summary header { display:flex; align-items:center; gap:7px; color:#627478; font-size:11px; }
 .node-summary-type { padding:3px 6px; border:1px solid #b8d8d0; border-radius:3px; background:#e8f4f0; color:#17695c; font-size:10px; font-weight:700; }
 .node-summary-count { margin-left:auto; color:#17695c; font-size:10px; }
 .node-summary h3 { margin:7px 0 8px; color:#183e3b; font-size:14px; overflow-wrap:anywhere; }
 .node-summary-relations { display:flex; flex-wrap:wrap; gap:5px; }
 .node-summary-relations span { padding:4px 6px; border:1px solid #d6e3df; border-radius:3px; background:#f7faf9; color:#58716b; font-size:10px; line-height:1.35; }
.node-summary > p { margin:0; color:#718187; font-size:11px; }

.page-title { display:flex; align-items:center; gap:10px; }.page-title > svg { flex:0 0 auto; padding:7px; border:1px solid #c4deef; border-radius:4px; background:#edf6fc; color:#2f80c5; font-size:22px; }.page-title h1 { margin:0; }.medical-domains { display:flex; flex-wrap:wrap; justify-content:center; gap:7px; margin-top:5px; }.medical-domains span { display:inline-flex; align-items:center; gap:5px; padding:5px 8px; border:1px solid #d3e3ef; border-radius:3px; background:#f6fbfe; color:#4e718a; font-size:11px; }.medical-domains svg { color:#3c91d3; }.legend span { display:inline-flex; align-items:center; gap:4px; }.legend span > svg { width:12px; height:12px; }.legend i { display:none; }

/* Dedicated Graphify palette: quiet blue supports extended data-reading sessions. */
.graphify-page { --ink:#1e3448; --green:#2f80c5; --line:#d5e3ef; background:#f5f8fc; }
.session-rail { background:#21384d; color:#dce9f4; }.brand-mark svg { background:#e2f0fb; color:#2f80c5; }.new-session { border-color:#b5d3e9; background:#e3f1fb; color:#1e4f78; }.rail-label,.rail-empty,.rail-foot { color:#a9c0d2; }.session-list { scrollbar-color:#7899b2 transparent; }.session-list:focus { outline-color:rgb(133 188 229 / 70%); }.session-item { color:#ccdae6; }.session-item:hover,.session-item.active { background:rgb(116 176 219 / 20%); box-shadow:inset 2px 0 #83c1eb; }
.conversation-pane,.evidence-panel { background:#fbfdff; }.conversation-head,.panel-tabs { background:#fff; }.conversation-head p,.head-status { color:#667b8e; }.head-status b,.answer header .selected { color:#2577bc; }.icon-btn { color:#567187; }.icon-btn:hover { border-color:#bed9ed; background:#eaf4fc; color:#2f80c5; }.empty-conversation,.panel-empty { color:#6a8091; }.empty-conversation svg,.panel-empty svg { color:#3c91d3; }
.user-message { border-color:#cfdfec; background:#e9f3fb; }.markdown-answer :deep(strong) { color:#1d5f95; }.markdown-answer :deep(a) { color:#267fc5; }.markdown-answer :deep(pre) { border-color:#d7e4ee; background:#f1f6fa; }.markdown-answer :deep(.md-table-wrapper),.result-table { border-color:#d4e2ed; }.markdown-answer :deep(th),.result-table th { background:#edf5fb; color:#5b7387; }.markdown-answer :deep(th),.markdown-answer :deep(td),.result-table th,.result-table td { border-color:#e1ebf2; }.result-table td { color:#425f72; }.evidence-link { background:#e7f2fb; color:#276fa9; }.composer { background:#fff; }.composer textarea { border-color:#c7d8e6; }.composer button { background:#2f80c5; }.composer button:disabled { background:#9bbbd3; }
.resize-handle:hover { border-left-color:#3d91d1; }.panel-tabs button.active { border-bottom-color:#2f80c5; color:#2f80c5; }.question-summary small,.legend { color:#627b8e; }.question-summary span { background:#e7f2fb; color:#286da5; }.blue{background:#3c91d3}.teal{background:#3d9fbe}.green{background:#4d9d9b}.graph-workspace { border-color:#c5dbea; background:#f2f8fc; }.graph-tools,.graph-actions { border-color:#cfdfeb; background:rgb(255 255 255 / 94%); }.graph-tools .icon-btn.active { border-color:#b8d8ed; background:#e8f3fb; color:#2f80c5; }.graph-actions label { color:#2c79b5; }.graph-actions select { color:#526d82; }
.node-summary { border-top-color:#d5e3ef; }.node-summary header { color:#627b8e; }.node-summary-type { border-color:#b8d7eb; background:#e8f3fb; color:#286fa8; }.node-summary-count { color:#2b75af; }.node-summary h3 { color:#1e4667; }.node-summary-relations span { border-color:#d5e3ed; background:#f7fbfe; color:#567186; }
</style>

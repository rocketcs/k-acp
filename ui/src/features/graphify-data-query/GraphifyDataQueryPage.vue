<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  AimOutlined, CloseOutlined, CompressOutlined, DatabaseOutlined, DeleteOutlined, DeploymentUnitOutlined, ExperimentOutlined, FieldStringOutlined, FilterOutlined,
  FullscreenOutlined, MedicineBoxOutlined, PlusOutlined, ReloadOutlined, SendOutlined,
  ShareAltOutlined, ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons-vue'
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'
import DiyWelcome from '@/components/chat/DiyWelcome.vue'
import { getPublished } from '@/api/agentDiy'
import type { DiyOutputFormat, DiyPageConfig } from '@/types'
import GraphifyEvidenceGraph from './GraphifyEvidenceGraph.vue'
import GraphifyExecutionPath from './GraphifyExecutionPath.vue'
import { displayGraphifyLabel } from './evidenceAdapter'
import { graphNodeLabel, graphRelationSentences, graphRelationSummary } from './evidencePresentation'
import { nodeTypeLabel } from './evidenceStyles'
import type { DomainSemantics } from './evidenceStyles'
import { shouldRenderAssistantPlaceholder, shouldRenderAssistantText } from './resultPresentation'
import { useGraphifyDataQueryChat } from './useGraphifyDataQueryChat'
import { shouldSubmitComposerShortcut, toggleEvidencePanel } from './composerControls'
import { shouldResetDeletedSession } from './composerControls'
import { splitAssistantContent } from './tablePlacement'
import type { ChatSessionVO } from '@/types'

const props = defineProps<{ agentId: string }>()
const agentId = computed(() => props.agentId)
const {
  sessions, sessionsLoading, currentSessionId, displayMessages, isRunning, streamingContent, activeEvidence, activeOutcome,
  selectedAssistantMessageId, chooseSession, deleteGraphifySession, startNewSession, sendQuestion,
} = useGraphifyDataQueryChat(agentId)

const question = ref('')
const graphOpen = ref(false)
/** DIY 快捷问答配置（页面管理里可在线维护），用于空状态快捷问题卡。 */
const diyConfig = ref<DiyPageConfig | null>(null)
/** 图谱大屏弹窗开关（点击全屏按钮弹出模态框）。 */
const fullscreen = ref(false)
const panelWidth = ref(470)
const relationFilter = ref<'all' | 'business' | 'provenance' | 'semantic'>('all')
const selectedId = ref<string | null>(null)
const graphRef = ref<InstanceType<typeof GraphifyEvidenceGraph> | null>(null)
const modalGraphRef = ref<InstanceType<typeof GraphifyEvidenceGraph> | null>(null)
const showFields = ref(false)
const showGraph = ref(false)
const relationSummary = computed(() => activeEvidence.value ? graphRelationSummary(activeEvidence.value) : null)
const domainSemantics = computed<DomainSemantics>(() => activeEvidence.value
  ? {
    labels: activeEvidence.value.semantic_context.domain_labels,
    headings: activeEvidence.value.semantic_context.domain_headings,
  }
  : {})
const selectedNode = computed(() => activeEvidence.value?.evidence.nodes.find((node) => node.id === selectedId.value) ?? activeEvidence.value?.evidence.nodes[0])
const selectedNodeSummary = computed(() => {
  const evidence = activeEvidence.value
  const node = selectedNode.value
  if (!evidence || !node) return null
  const relations = graphRelationSentences(evidence, node.id)
  const detail = node.kind === 'product' ? evidence.product_details?.[node.label] : undefined
  return {
    type: nodeTypeLabel(node, domainSemantics.value),
    label: graphNodeLabel(node),
    edgeCount: relations.length,
    relations: relations.slice(0, 4),
    extraCount: Math.max(0, relations.length - 4),
    productDetail: detail,
  }
})
const selectedTurn = computed(() => selectedAssistantMessageId.value)
const resultColumns = computed(() => {
  const evidence = activeEvidence.value
  if (!evidence) return []
  const rows = evidence.result.rows ?? []
  return evidence.result.columns
    // 过滤全空列：某列在全部结果行里都无值（null/空串）时不展示，避免药品/耗材
    // 不同域共用字段造成一整列“-”的无效展示。
    .filter((key) => rows.some((row) => {
      const value = row[key]
      return value !== null && value !== undefined && String(value).trim() !== ''
    }))
    .map((key) => ({
      key,
      label: evidence.result.column_labels?.[key] ?? displayGraphifyLabel(key),
      formatValue: (value: unknown): string => {
        const text = value === null || value === undefined ? '' : String(value)
        // 目录域等枚举值面向用户用后端下发的域标签中文呈现（行业配置驱动，前端不硬编码）。
        if (key === 'catalog_domain' || key === 'domain') {
          return domainSemantics.value.labels?.[text] ?? text
        }
        return text
      },
    }))
})
const resultState = computed<'idle' | 'running' | 'executed' | 'blocked' | 'unavailable'>(() => {
  if (isRunning.value) return 'running'
  if (activeEvidence.value) return 'executed'
  if (activeOutcome.value) return activeOutcome.value.status
  return 'idle'
})

async function submit() {
  const sent = await sendQuestion(question.value)
  if (sent) question.value = ''
}
function onComposerKeydown(event: KeyboardEvent) {
  if (!shouldSubmitComposerShortcut(event)) return
  event.preventDefault()
  void submit()
}
function toggleGraph() {
  graphOpen.value = toggleEvidencePanel(graphOpen.value)
}
async function focusGraph() {
  graphOpen.value = true
  await nextTick()
  graphRef.value?.fit()
}
async function removeSession(session: ChatSessionVO) {
  if (!window.confirm(`确定删除“${session.title || '新对话'}”吗？此操作无法撤销。`)) return
  await deleteGraphifySession(session)
}
function onResizeStart(event: PointerEvent) {
  const initial = panelWidth.value
  const startX = event.clientX
  const move = (moveEvent: PointerEvent) => { panelWidth.value = Math.min(760, Math.max(360, initial + startX - moveEvent.clientX)) }
  const stop = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop) }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop, { once: true })
}
function closeFullscreen() {
  fullscreen.value = false
}
// Esc 关闭图谱大屏弹窗。
onMounted(() => {
  window.addEventListener('keydown', onModalKeydown)
  void loadDiyConfig()
})
onUnmounted(() => window.removeEventListener('keydown', onModalKeydown))
function onModalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && fullscreen.value) fullscreen.value = false
}
async function loadDiyConfig() {
  try {
    const res = await getPublished(props.agentId)
    diyConfig.value = res?.data?.data ?? null
  } catch {
    diyConfig.value = null
  }
}
/** DIY 快捷问答确认 → 直接把生成的问题发给 MCP 语义查询（忽略图表输出格式指令）。 */
function onQuickSend(payload: { text: string; outputFormat: DiyOutputFormat }) {
  void submitWith(payload.text)
}
async function submitWith(text: string) {
  const sent = await sendQuestion(text)
  if (sent) question.value = ''
}
</script>

<template>
  <main class="graphify-page" data-agent-id="agentId">
    <aside class="session-rail" aria-label="查询会话">
      <div class="brand-mark">
        <MedicineBoxOutlined /><strong>医疗目录</strong>
      </div>
      <button class="new-session" type="button" :disabled="isRunning" @click="startNewSession">
        <PlusOutlined /> 新建对话
      </button>
      <span class="rail-label">历史会话</span>
      <div class="session-list" tabindex="0" aria-label="历史会话列表">
        <div v-for="session in sessions" :key="session.id" class="session-item-row"
          :class="{ active: String(session.id) === currentSessionId }">
          <button class="session-item" :disabled="isRunning" @click="chooseSession(session)">{{ session.title || '新对话'
            }}</button>
          <button class="session-delete" type="button" :disabled="isRunning"
            :aria-label="`删除会话：${session.title || '新对话'}`" title="删除会话" @click.stop="removeSession(session)">
            <DeleteOutlined />
          </button>
        </div>
        <p v-if="sessionsLoading" class="rail-empty">正在加载会话…</p>
        <p v-else-if="!sessions.length" class="rail-empty">尚无历史会话</p>
      </div>
      <p class="rail-foot">Wren MDL + Neo4j<br>受治理数据查询</p>
    </aside>

    <section class="conversation-pane">
      <header class="conversation-head">
        <div class="page-title">
          <MedicineBoxOutlined />
          <div>
            <h1>医疗目录智能检索</h1>
            <p>WREN MDL + NEO4J / GOVERNED EVIDENCE</p>
          </div>
        </div>
        <div class="head-status"><b>{{ isRunning ? '正在查询' : activeEvidence ? `${activeEvidence.result.rows.length} 条结果`
          : '等待查询' }}</b><span>{{ activeEvidence ? `追踪 ${activeEvidence.trace_id}` : '实时证据链' }}</span></div><button
          class="icon-btn" :title="graphOpen ? '收起语义依据' : '展开语义依据'" :aria-expanded="graphOpen"
          aria-controls="graphify-evidence-panel" @click="toggleGraph">
          <ShareAltOutlined />
        </button>
      </header>
      <div class="conversation-body">
        <section v-if="!displayMessages.length && !diyConfig" class="empty-conversation">
          <MedicineBoxOutlined />
          <h2>从业务问题开始</h2>
          <p>查询结果、语义依据和来源记录将在同一轮对话中展示。</p>
          <div class="medical-domains" aria-label="可查询业务范围"><span>
              <ExperimentOutlined /> 药品
            </span><span>
              <DeploymentUnitOutlined /> 耗材
            </span><span>
              <DatabaseOutlined /> 医疗目录
            </span></div>
        </section>
        <section v-else-if="!displayMessages.length && diyConfig" class="diy-welcome-wrap">
          <DiyWelcome :config="diyConfig" :is-running="isRunning" @confirm="onQuickSend" />
        </section>
        <article v-for="message in displayMessages" :key="message.id"
          :class="message.role === 'user' ? 'user-message' : 'answer'"
          @click="message.role === 'assistant' && (selectedAssistantMessageId = String(message.id))">
          <template v-if="message.role === 'user'">{{ message.content }}</template>
          <template v-else>
            <header>
              <h2>智能问数结果</h2><span :class="{ selected: selectedTurn === String(message.id) }">{{ selectedTurn ===
                String(message.id) ? '当前证据' : '选择查看证据' }}</span>
            </header>
            <!-- 若正文含表格占位符 [[data-table]]，则 before=结论+摘要、after=说明+可选项；否则 after 为空 -->
            <MarkdownRenderer
              v-if="shouldRenderAssistantText({ hasContent: Boolean(splitAssistantContent(message.content).before), isSelectedTurn: selectedTurn === String(message.id), hasEvidence: Boolean(activeEvidence) })"
              class="answer-text markdown-answer" :content="splitAssistantContent(message.content).before"
              :is-streaming="isRunning && selectedTurn === String(message.id)" :is-diy-chat="true" />
            <p v-else-if="shouldRenderAssistantPlaceholder({ hasContent: Boolean(splitAssistantContent(message.content).before), hasEvidence: Boolean(activeEvidence) })"
              class="answer-text">正在整理查询结果…</p>
            <template v-if="selectedTurn === String(message.id) && activeEvidence">
              <div v-if="activeEvidence.result.rows.length" class="result-table">
                <table>
                  <thead>
                    <tr>
                      <th v-for="column in resultColumns" :key="column.key">{{ column.label }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, index) in activeEvidence.result.rows" :key="index">
                      <td v-for="column in resultColumns" :key="column.key"
                        :title="column.formatValue(row[column.key])">{{ column.formatValue(row[column.key]) ?? '-' }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-else class="inline-state">本次查询未返回业务记录。</p>
            </template>
            <p v-if="selectedTurn === String(message.id) && activeOutcome" class="state-error">{{ activeOutcome.status
              === 'blocked'
              ? '该查询已被安全规则拦截，未执行任何数据操作。' : '语义依据暂不可用，请稍后重试。' }} {{ activeOutcome.reason || '' }} {{
                activeOutcome.trace_id ? `Trace:
              ${activeOutcome.trace_id}` : '' }}</p>
            <MarkdownRenderer v-if="splitAssistantContent(message.content).after" class="answer-text markdown-answer"
              :content="splitAssistantContent(message.content).after"
              :is-streaming="isRunning && selectedTurn === String(message.id)" :is-diy-chat="true" />
            <footer v-if="selectedTurn === String(message.id) && activeEvidence"><button class="evidence-link"
                @click.stop="focusGraph">
                <ShareAltOutlined /> 查看语义依据
              </button><span>Trace: {{ activeEvidence.trace_id }}{{ activeEvidence.result.truncated ? ' · 结果已截断' : ''
                }}</span>
            </footer>
          </template>
        </article>
        <section v-if="resultState === 'running' && !streamingContent" class="inline-state">正在调用受治理数据服务并生成证据链…</section>
        <section
          v-if="resultState === 'idle' && displayMessages.some((message) => message.role === 'assistant') && !activeEvidence && !activeOutcome"
          class="inline-state">该回答未返回可验证的 MCP 查询证据。</section>
      </div>
      <form class="composer" @submit.prevent="submit"><textarea v-model="question" :disabled="isRunning"
          aria-label="继续追问" placeholder="继续追问，或输入一个新的业务问题" @keydown="onComposerKeydown" /><button type="submit"
          :disabled="!question.trim() || isRunning" title="发送当前问题" aria-label="发送当前问题">
          <SendOutlined />
        </button></form>
    </section>

    <aside v-show="graphOpen" id="graphify-evidence-panel" class="evidence-panel"
      :style="{ width: `${panelWidth}px` }" aria-label="语义依据">
      <div class="resize-handle" role="separator" aria-orientation="vertical"
        @pointerdown="onResizeStart" />
      <header class="panel-head">
        <h2>语义依据</h2>
        <div><button class="icon-btn" title="图谱大屏查看" @click="fullscreen = true">
            <FullscreenOutlined />
          </button><button class="icon-btn" title="关闭" @click="graphOpen = false">
            <CompressOutlined />
          </button></div>
      </header>
      <section class="evidence-scroll">
          <template v-if="activeEvidence">
            <header class="question-summary"><small>当前对话轮次 · {{ activeEvidence.trace_id
                }}</small><strong>{{ activeEvidence.question }}</strong><span>{{ activeEvidence.result.rows.length }}
                条结果 · {{ activeEvidence.evidence.source_record_count ?? activeEvidence.evidence.source_record_ids.length
                }} 条来源记录</span>
              <p v-if="relationSummary" class="relation-summary">关系摘要：{{ relationSummary }}</p>
            </header>
            <GraphifyExecutionPath :evidence="activeEvidence" />
            <button class="graph-toggle" :aria-expanded="showGraph" @click="showGraph = !showGraph">
              <ShareAltOutlined /> {{ showGraph ? '收起结果子图' : '查看结果子图' }}（{{ activeEvidence.evidence.nodes.length }} 节点 ·
              {{ activeEvidence.evidence.edges.length }} 关系）
            </button>
            <div v-show="showGraph" class="legend"><span>
                <DatabaseOutlined class="blue" /> Wren MDL
              </span><span>
                <MedicineBoxOutlined class="teal" /> 业务实体
              </span><span>
                <ShareAltOutlined class="green" /> 来源追溯
              </span><span>
                <DeploymentUnitOutlined class="plum" /> 业务关系
              </span></div>
            <div v-show="showGraph" class="graph-workspace">
              <GraphifyEvidenceGraph ref="graphRef" :evidence="activeEvidence" :relation-filter="relationFilter"
                :fullscreen="false" :show-fields="showFields" @select="selectedId = $event" />
              <div class="graph-tools" aria-label="图谱工具栏"><button class="icon-btn" title="放大"
                  @click="graphRef?.zoomIn()">
                  <ZoomInOutlined />
                </button><button class="icon-btn" title="缩小" @click="graphRef?.zoomOut()">
                  <ZoomOutOutlined />
                </button><button class="icon-btn" title="适应画布" @click="graphRef?.fit()">
                  <AimOutlined />
                </button><button class="icon-btn" title="重新布局" @click="graphRef?.relayout()">
                  <ReloadOutlined />
                </button><button class="icon-btn" :class="{ active: showFields }" title="显示/隐藏语义字段"
                  @click="showFields = !showFields">
                  <FieldStringOutlined />
                </button><button class="icon-btn" title="图谱大屏查看" @click="fullscreen = true">
                  <FullscreenOutlined />
                </button></div>
              <div class="graph-actions"><label>
                  <FilterOutlined /><select v-model="relationFilter" aria-label="筛选图谱关系">
                    <option value="all">全部关系</option>
                    <option value="business">业务关系</option>
                    <option value="provenance">来源追溯</option>
                    <option value="semantic">语义关系</option>
                  </select><span class="graph-hint">点击节点展开下一级</span>
                </label></div>
            </div>
            <section v-if="showGraph && selectedNodeSummary" class="node-summary">
              <header><span class="node-summary-type">{{ selectedNodeSummary.type }}</span><b>已选节点</b><span
                  class="node-summary-count">{{ selectedNodeSummary.edgeCount }} 个关联</span></header>
              <h3>{{ selectedNodeSummary.label }}</h3><template v-if="selectedNodeSummary.productDetail">
                <div class="node-summary-detail">
                  <p class="node-summary-meta"><span>{{ selectedNodeSummary.productDetail.count }} 条目录记录</span><span
                      v-if="selectedNodeSummary.productDetail.specifications.length">规格：{{
                        selectedNodeSummary.productDetail.specifications.join(' / ') }}</span><span
                      v-if="selectedNodeSummary.productDetail.enterprises.length">生产企业：{{
                        selectedNodeSummary.productDetail.enterprises.join(' / ') }}</span><span
                      v-if="selectedNodeSummary.productDetail.categories.length">支付类别：{{
                        selectedNodeSummary.productDetail.categories.join(' / ') }}</span></p>
                  <details v-if="selectedNodeSummary.productDetail.codes.length" class="node-summary-codes">
                    <summary>目录编码（{{ selectedNodeSummary.productDetail.codes.length }}）</summary>
                    <ol>
                      <li v-for="code in selectedNodeSummary.productDetail.codes" :key="code"><code>{{ code }}</code>
                      </li>
                    </ol>
                  </details>
                </div>
              </template>
              <div v-if="selectedNodeSummary.relations.length" class="node-summary-relations"><span
                  v-for="relation in selectedNodeSummary.relations" :key="relation">{{ relation }}</span><span
                  v-if="selectedNodeSummary.extraCount">另有 {{ selectedNodeSummary.extraCount }} 条关联</span></div>
              <p v-else-if="!selectedNodeSummary.productDetail">当前节点暂无可展示的关联。</p>
            </section>
          </template>
          <section v-else class="panel-empty">
            <DatabaseOutlined />
            <p>{{ activeOutcome ? '当前问题没有可展示的图谱证据。' : '选择一个带 MCP 查询证据的回答后，图谱将在这里显示。' }}</p>
          </section>
      </section>
    </aside>

    <!-- 图谱大屏弹窗：点击“图谱大屏查看”弹出，遮罩/关闭/Esc 均可退出 -->
    <Teleport to="body">
      <div v-if="fullscreen && activeEvidence" class="graph-modal" role="dialog" aria-modal="true"
        aria-label="图谱大屏" @click.self="closeFullscreen">
        <div class="graph-modal-panel">
          <header class="graph-modal-head">
            <h2>图谱大屏</h2>
            <button class="icon-btn" title="关闭大屏" aria-label="关闭大屏" @click="closeFullscreen">
              <CloseOutlined />
            </button>
          </header>
          <div class="graph-modal-body">
            <div class="graph-modal-canvas">
              <GraphifyEvidenceGraph ref="modalGraphRef" :evidence="activeEvidence" :relation-filter="relationFilter"
                fullscreen :show-fields="showFields" @select="selectedId = $event" />
              <div class="graph-tools" aria-label="图谱工具栏"><button class="icon-btn" title="放大"
                  @click="modalGraphRef?.zoomIn()">
                  <ZoomInOutlined />
                </button><button class="icon-btn" title="缩小" @click="modalGraphRef?.zoomOut()">
                  <ZoomOutOutlined />
                </button><button class="icon-btn" title="适应画布" @click="modalGraphRef?.fit()">
                  <AimOutlined />
                </button><button class="icon-btn" title="重新布局" @click="modalGraphRef?.relayout()">
                  <ReloadOutlined />
                </button><button class="icon-btn" :class="{ active: showFields }" title="显示/隐藏语义字段"
                  @click="showFields = !showFields">
                  <FieldStringOutlined />
                </button></div>
              <div class="graph-actions"><label>
                  <FilterOutlined /><select v-model="relationFilter" aria-label="筛选图谱关系">
                    <option value="all">全部关系</option>
                    <option value="business">业务关系</option>
                    <option value="provenance">来源追溯</option>
                    <option value="semantic">语义关系</option>
                  </select><span class="graph-hint">点击节点展开下一级</span>
                </label></div>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </main>
</template>

<style scoped lang="scss">
.graphify-page {
  --ink: #183034;
  --green: #0d6a5c;
  --line: #d5e1df;
  display: grid;
  grid-template-columns: 260px minmax(520px, 1fr) auto;
  grid-template-rows: minmax(0, 1fr);
  height: 100dvh;
  overflow: hidden;
  background: #f6f9f8;
  color: var(--ink);
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}

.session-rail {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 5px;
  padding: 18px 14px 14px;
  overflow: hidden;
  background: #1e3033;
  color: #dbe9e5;
}

.brand-mark {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px 18px;
  color: #fff;
  font-size: 17px;
}

.brand-mark svg {
  padding: 6px;
  border-radius: 4px;
  background: #dff2ed;
  color: #0d6a5c;
  font-size: 23px;
}

.new-session {
  height: 38px;
  flex: 0 0 auto;
  border: 1px solid #b7d5cc;
  border-radius: 4px;
  background: #dcefe9;
  color: #183e3b;
  font-weight: 700;
  cursor: pointer;
}

.rail-label {
  flex: 0 0 auto;
  margin: 20px 8px 6px;
  color: #9bb2b1;
  font-size: 11px;
}

.session-list {
  display: grid;
  min-height: 0;
  flex: 1;
  gap: 3px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 0 6px;
  scrollbar-color: #66817d transparent;
}

.session-list:focus {
  outline: 1px solid rgb(134 207 189 / 60%);
  outline-offset: 2px;
}

.session-item {
  min-height: 42px;
  overflow: hidden;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: #c7d8d4;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.session-item:hover,
.session-item.active {
  padding-left: 9px;
  background: rgb(130 194 180 / 18%);
  color: #fff;
  box-shadow: inset 2px 0 #86cfbd;
}

.rail-empty {
  padding: 0 8px;
  color: #9eb4b1;
  font-size: 12px;
}

.rail-foot {
  flex: 0 0 auto;
  margin: 0;
  padding: 14px 8px 0;
  border-top: 1px solid rgb(255 255 255 / 14%);
  color: #9eb4b1;
  font-size: 11px;
  line-height: 1.65;
}

.conversation-pane {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  background: #fbfcfc;
}

.conversation-head {
  display: flex;
  align-items: center;
  gap: 25px;
  min-height: 68px;
  padding: 0 24px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}

.conversation-head h1 {
  margin: 0;
  font-family: STSong, "Songti SC", serif;
  font-size: 19px;
}

.conversation-head p {
  margin: 3px 0 0;
  color: #64767a;
  font-size: 11px;
}

.head-status {
  display: flex;
  gap: 10px;
  color: #64767a;
  font-size: 11px;
}

.head-status b {
  color: #176958;
}

.icon-btn {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: #45636a;
  cursor: pointer;
}

.icon-btn:hover {
  border-color: #b9d9d1;
  background: #e7f3ef;
  color: var(--green);
}

.conversation-head .icon-btn {
  margin-left: auto;
}

.conversation-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 34px max(30px, 9%);
}

.conversation-body>* {
  max-width: 780px;
  margin-right: auto;
  margin-left: auto;
}

/* DIY 快捷问答卡：放宽宽度以便卡片横排。 */
.conversation-body>.diy-welcome-wrap {
  max-width: 1060px;
  padding-top: 20px;
}

/*
 * 医药目录专属卡片风格（scoped + :deep 局部覆盖，不影响其他页面/agent）：
 * 贴合本智能体页面的冷静蓝色主题（--green:#2f80c5 / 浅蓝背景）· 紧凑标签（胶囊）式。
 */
.diy-welcome-wrap :deep(.diy-welcome-content) {
  width: 100%;
}
.diy-welcome-wrap :deep(.diy-question-grid) {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}
.diy-welcome-wrap :deep(.diy-question-card) {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 42px;
  /* 药丸/胶囊外观 */
  padding: 6px 16px 6px 8px;
  border: 1px solid #c6ddf1;
  border-radius: 999px;
  background: linear-gradient(135deg, #eff6fd, #e1eef9);
  box-shadow: 0 2px 6px rgb(63 145 209 / 10%);
  color: #1e4f78;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease, background 0.18s ease;
}
.diy-welcome-wrap :deep(.diy-question-card:hover:not(:disabled)) {
  transform: translateY(-2px);
  border-color: #4b9bd4;
  box-shadow: 0 8px 18px rgb(47 128 197 / 18%);
  background: linear-gradient(135deg, #e7f3fc, #d5e9f7);
}
.diy-welcome-wrap :deep(.diy-question-icon) {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #dfedf8;
  font-size: 15px;
}
.diy-welcome-wrap :deep(.diy-question-copy) {
  gap: 0;
}
.diy-welcome-wrap :deep(.diy-question-copy strong) {
  color: #1e4f78;
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
}
.diy-welcome-wrap :deep(.diy-question-copy small) {
  display: none;
}
/* 点击进入参数表单后，表单容器沿用默认样式，仅套用医药蓝色主题。 */
.diy-welcome-wrap :deep(.diy-question-form) {
  border-color: #c6ddf1;
  box-shadow: 0 12px 32px rgb(47 128 197 / 14%);
}
.diy-welcome-wrap :deep(.diy-form-footer .ant-btn-primary) {
  border-color: #2f80c5;
  background: #2f80c5;
}
.diy-welcome-wrap :deep(.diy-option.active) {
  border-color: #3c91d3;
  background: #e7f2fb;
  color: #286fa8;
}

.empty-conversation,
.panel-empty {
  display: grid;
  place-items: center;
  gap: 10px;
  min-height: 210px;
  color: #6a7d7b;
  text-align: center;
}

.empty-conversation svg,
.panel-empty svg {
  color: #218a78;
  font-size: 30px;
}

.empty-conversation h2 {
  margin: 0;
  font-size: 17px;
}

.empty-conversation p {
  margin: 0;
  font-size: 13px;
}

.user-message {
  width: max-content;
  max-width: 86%;
  margin-right: 0;
  margin-bottom: 24px;
  padding: 12px 15px;
  border: 1px solid #d5e4e0;
  border-radius: 5px 2px 5px 5px;
  background: #e5f0ee;
  line-height: 1.65;
  white-space: pre-wrap;
}

.answer {
  max-width: 680px;
  margin-bottom: 25px;
  cursor: pointer;
}

.answer header,
.answer footer {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.answer h2 {
  margin: 0;
  font-size: 15px;
}

.answer header span {
  color: #728188;
  font-size: 11px;
}

.answer header .selected {
  color: #176958;
  font-weight: 700;
}

.answer-text {
  min-width: 0;
  margin: 12px 0;
  line-height: 1.72;
  overflow-wrap: anywhere;
}

.markdown-answer :deep(p) {
  margin: 0 0 10px;
}

.markdown-answer :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-answer :deep(ul),
.markdown-answer :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
}

.markdown-answer :deep(li + li) {
  margin-top: 4px;
}

.markdown-answer :deep(strong) {
  color: #0c564b;
}

.markdown-answer :deep(a) {
  color: #087f87;
  overflow-wrap: anywhere;
}

.markdown-answer :deep(pre) {
  max-width: 100%;
  overflow: auto;
  padding: 12px;
  border: 1px solid #d7e3e0;
  border-radius: 4px;
  background: #f1f6f5;
}

.markdown-answer :deep(code) {
  overflow-wrap: anywhere;
}

.markdown-answer :deep(.md-table-wrapper) {
  max-width: 100%;
  overflow-x: auto;
  margin: 12px 0;
  border: 1px solid #d7e3e0;
  border-radius: 4px;
}

.markdown-answer :deep(table) {
  min-width: max-content;
  border-collapse: collapse;
}

.markdown-answer :deep(th),
.markdown-answer :deep(td) {
  padding: 7px 10px;
  border-bottom: 1px solid #e4ecea;
  text-align: left;
  vertical-align: top;
  overflow-wrap: anywhere;
}

.markdown-answer :deep(th) {
  background: #eff6f3;
  color: #627973;
  font-size: 11px;
}

.warning,
.state-error,
.inline-state {
  padding: 9px 12px;
  border-left: 3px solid #ae6d16;
  background: #fbf6ed;
  color: #50605d;
  font-size: 12px;
  line-height: 1.6;
}

.state-error {
  border-color: #b6523a;
  background: #fff5f2;
}

.inline-state {
  margin-top: 14px;
}

.result-table {
  overflow-x: auto;
  max-width: 100%;
  border: 1px solid #d7e3e0;
  background: #fff;
}

.result-table table {
  min-width: max-content;
  border-collapse: collapse;
}

.result-table th,
.result-table td {
  min-width: 140px;
  padding: 7px 10px;
  border-top: 1px solid #e4ecea;
  color: #425b56;
  font-size: 11px;
  text-align: left;
  vertical-align: top;
}

.result-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  border-top: 0;
  background: #eff6f3;
  color: #627973;
  font-size: 10px;
  font-weight: 700;
}

.answer footer {
  margin-top: 12px;
  color: #738188;
  font-size: 11px;
}

.evidence-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: #e7f4ef;
  color: #106c5e;
  font-weight: 700;
  cursor: pointer;
}

.composer {
  display: flex;
  gap: 10px;
  padding: 13px max(24px, 9%) 20px;
  border-top: 1px solid var(--line);
  background: #fff;
}

.composer textarea {
  flex: 1;
  max-width: 780px;
  min-height: 48px;
  max-height: 120px;
  margin: auto;
  padding: 12px 52px 12px 14px;
  border: 1px solid #c6d5d2;
  border-radius: 5px;
  color: var(--ink);
  resize: vertical;
}

.composer button {
  width: 38px;
  height: 38px;
  margin: auto 0 auto -54px;
  border: 0;
  border-radius: 4px;
  background: #0f6a5e;
  color: #fff;
  cursor: pointer;
}

.composer button:disabled,
.new-session:disabled,
.session-item:disabled {
  cursor: not-allowed;
  opacity: .5;
}

.evidence-panel {
  position: relative;
  display: flex;
  min-width: 360px;
  min-height: 0;
  flex-direction: column;
  border-left: 1px solid var(--line);
  background: #fbfcfc;
}

.resize-handle {
  position: absolute;
  z-index: 10;
  top: 0;
  bottom: 0;
  left: -6px;
  width: 12px;
  cursor: col-resize;
}

.resize-handle:hover {
  border-left: 2px solid #087f87;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 68px;
  padding: 0 16px 0 19px;
  border-bottom: 1px solid var(--line);
}

.panel-head h2 {
  margin: 0;
  font-family: STSong, "Songti SC", serif;
  font-size: 17px;
}

.panel-head div {
  display: flex;
}

.evidence-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
}

.question-summary {
  display: grid;
  gap: 6px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--line);
}

.question-summary small {
  color: #66787c;
  font-size: 11px;
}

.execution-path {
  display: grid;
  gap: 6px;
  margin: 12px 0 2px;
  padding: 10px 11px;
  border: 1px solid #d7e4e1;
  border-radius: 4px;
  background: #f4faf8;
}

.execution-path-step {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.execution-path-step small {
  flex: 0 0 62px;
  color: #7c8f8c;
  font-size: 10px;
}

.execution-path-step b {
  min-width: 0;
  color: #2b4a46;
  font-size: 11px;
  font-weight: 600;
  overflow-wrap: anywhere;
  line-height: 1.5;
}

.question-summary strong {
  font-size: 13px;
  line-height: 1.5;
}

.graph-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: max-content;
  margin: 12px 0 2px;
  padding: 7px 12px;
  border: 1px solid #cfe0e6;
  border-radius: 6px;
  background: #f4fafb;
  color: #1f6f8e;
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;
}

.graph-toggle:hover {
  border-color: #9ccce0;
  background: #eaf6fb;
}

.question-summary span {
  width: max-content;
  padding: 3px 6px;
  background: #e5f2ee;
  color: #176548;
  font-size: 11px;
  font-weight: 700;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin: 14px 0 10px;
  color: #62767a;
  font-size: 11px;
}

.legend i {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 3px;
  border-radius: 50%;
}

.blue {
  background: #2b6692
}

.orange {
  background: #a86a17
}

.teal {
  background: #087f87
}

.green {
  background: #136c61
}

.plum {
  background: #805e93
}

.graph-workspace {
  position: relative;
  height: 440px;
  overflow: hidden;
  border: 1px solid #bfd2cf;
  border-radius: 4px;
  background: #f2f8f7;
}

.graph-tools {
  position: absolute;
  z-index: 6;
  top: 10px;
  right: 10px;
  display: flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid #ceddda;
  border-radius: 4px;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 4px 14px rgb(31 58 58 / 8%);
}

.graph-tools .icon-btn {
  width: 27px;
  height: 27px;
}

.graph-tools .icon-btn.active {
  border-color: #abd0c4;
  background: #e7f4ef;
  color: var(--green);
}

.graph-actions {
  position: absolute;
  z-index: 6;
  left: 10px;
  bottom: 10px;
  display: flex;
  align-items: center;
  padding: 4px;
  border: 1px solid #ceddda;
  border-radius: 4px;
  background: rgb(255 255 255 / 94%);
}

.graph-actions label {
  display: flex;
  align-items: center;
  gap: 5px;
  padding-right: 5px;
  color: #1e766c;
}

.graph-actions select {
  border: 0;
  outline: 0;
  background: transparent;
  color: #526863;
  font-size: 11px;
}

.selection-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin: 10px 0 15px;
  color: #0e7061;
  font-size: 11px;
}

.node-inspector {
  padding-top: 15px;
  border-top: 1px solid var(--line);
}

.node-inspector header {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.node-inspector h3 {
  margin: 10px 0 5px;
  font-family: STSong, "Songti SC", serif;
  font-size: 17px;
  overflow-wrap: anywhere;
}

.node-inspector dl {
  display: grid;
  gap: 9px;
  margin: 13px 0 0;
}

.node-inspector dl div {
  padding-left: 7px;
  border-left: 2px solid #b9d9d1;
}

.node-inspector dt {
  color: #718187;
  font-size: 10px;
}

.node-inspector dd {
  margin: 3px 0 0;
  overflow-wrap: anywhere;
  font-size: 11px;
  font-weight: 650;
}

.node-inspector p {
  color: #52615d;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.node-summary-detail {
  margin: 0 0 8px;
  padding: 8px 9px;
  border: 1px solid #d5e3ed;
  border-radius: 4px;
  background: #f7fbfe;
}

.node-summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin: 0 0 6px;
  color: #567186;
  font-size: 10.5px;
  line-height: 1.5;
}

.node-summary-meta span {
  display: inline-flex;
  gap: 3px;
}

.node-summary-codes summary {
  color: #286fa8;
  font-size: 10.5px;
  font-weight: 650;
  cursor: pointer;
}

.node-summary-codes ol {
  max-height: 150px;
  margin: 6px 0 0;
  padding-left: 20px;
  overflow-y: auto;
  color: #425f72;
  font-size: 10.5px;
  line-height: 1.6;
}

.node-summary-codes code {
  color: #1d5f95;
  user-select: all;
}

/* 图谱大屏弹窗：遮罩 + 居中面板 */
.graph-modal {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 12px;
  background: rgb(20 30 38 / 55%);
}
.graph-modal-panel {
  display: flex;
  flex-direction: column;
  width: min(1600px, 97vw);
  height: min(1100px, 96vh);
  overflow: hidden;
  border: 1px solid #b9cdd9;
  border-radius: 8px;
  background: #fbfdff;
  box-shadow: 0 18px 60px rgb(15 35 45 / 35%);
}
.graph-modal-head {
  display: flex;
  height: 52px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px 0 20px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}
.graph-modal-head h2 {
  margin: 0;
  font-family: STSong, "Songti SC", serif;
  font-size: 16px;
}
.graph-modal-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 8px;
  overflow: hidden;
}
.graph-modal-canvas {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border: 1px solid #c5dbea;
  border-radius: 6px;
  background: #f2f8fc;
}
.graph-modal .graph-tools {
  top: 12px;
  right: 12px;
}
.graph-modal .graph-actions {
  left: 12px;
  bottom: 12px;
}

@media (max-width:980px) {
  .graphify-page {
    grid-template-columns: 210px minmax(0, 1fr);
  }

  .evidence-panel {
    position: fixed;
    z-index: 30;
    top: 0;
    right: 0;
    bottom: 0;
    box-shadow: -14px 0 36px rgb(14 38 37 / 16%);
  }

  .resize-handle {
    display: none;
  }
}

@media (max-width:680px) {
  .graphify-page {
    display: block;
  }

  .session-rail {
    display: none;
  }

  .conversation-head {
    padding: 0 15px;
  }

  .head-status {
    display: none;
  }

  .conversation-body {
    padding: 22px 16px;
  }

  .evidence-panel {
    width: 100% !important;
  }

  .graph-workspace {
    height: 430px;
  }
}

.node-summary {
  margin-top: 12px;
  padding: 11px 0 2px;
  border-top: 1px solid var(--line);
}

.node-summary header {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #627478;
  font-size: 11px;
}

.node-summary-type {
  padding: 3px 6px;
  border: 1px solid #b8d8d0;
  border-radius: 3px;
  background: #e8f4f0;
  color: #17695c;
  font-size: 10px;
  font-weight: 700;
}

.node-summary-count {
  margin-left: auto;
  color: #17695c;
  font-size: 10px;
}

.node-summary h3 {
  margin: 7px 0 8px;
  color: #183e3b;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.node-summary-relations {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.node-summary-relations span {
  padding: 4px 6px;
  border: 1px solid #d6e3df;
  border-radius: 3px;
  background: #f7faf9;
  color: #58716b;
  font-size: 10px;
  line-height: 1.35;
}

.node-summary>p {
  margin: 0;
  color: #718187;
  font-size: 11px;
}

.relation-summary {
  margin: 0;
  color: #627b8e;
  font-size: 11px;
  line-height: 1.55;
}

.session-item-row {
  display: flex;
  min-width: 0;
  min-height: 42px;
  border-radius: 3px;
}

.session-item-row .session-item {
  min-width: 0;
  min-height: 42px;
  flex: 1;
}

.session-item-row:hover,
.session-item-row.active {
  background: rgb(116 176 219 / 20%);
  box-shadow: inset 2px 0 #83c1eb;
}

.session-item-row:hover .session-item,
.session-item-row.active .session-item {
  padding-left: 9px;
  color: #fff;
}

.session-delete {
  display: grid;
  width: 30px;
  flex: 0 0 30px;
  place-items: center;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: #a9c0d2;
  cursor: pointer;
  opacity: 0;
}

.session-item-row:hover .session-delete,
.session-item-row:focus-within .session-delete {
  opacity: 1;
}

.session-delete:hover {
  background: rgb(255 255 255 / 14%);
  color: #fff;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-title>svg {
  flex: 0 0 auto;
  padding: 7px;
  border: 1px solid #c4deef;
  border-radius: 4px;
  background: #edf6fc;
  color: #2f80c5;
  font-size: 22px;
}

.page-title h1 {
  margin: 0;
}

.medical-domains {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 7px;
  margin-top: 5px;
}

.medical-domains span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 8px;
  border: 1px solid #d3e3ef;
  border-radius: 3px;
  background: #f6fbfe;
  color: #4e718a;
  font-size: 11px;
}

.medical-domains svg {
  color: #3c91d3;
}

.legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.legend span>svg {
  width: 12px;
  height: 12px;
}

.legend i {
  display: none;
}

/* Dedicated Graphify palette: quiet blue supports extended data-reading sessions. */
.graphify-page {
  --ink: #1e3448;
  --green: #2f80c5;
  --line: #d5e3ef;
  background: #f5f8fc;
}

.session-rail {
  background: #21384d;
  color: #dce9f4;
}

.brand-mark svg {
  background: #e2f0fb;
  color: #2f80c5;
}

.new-session {
  border-color: #b5d3e9;
  background: #e3f1fb;
  color: #1e4f78;
}

.rail-label,
.rail-empty,
.rail-foot {
  color: #a9c0d2;
}

.session-list {
  scrollbar-color: #7899b2 transparent;
}

.session-list:focus {
  outline-color: rgb(133 188 229 / 70%);
}

.session-item {
  color: #ccdae6;
}

.session-item:hover,
.session-item.active {
  background: rgb(116 176 219 / 20%);
  box-shadow: inset 2px 0 #83c1eb;
}

.conversation-pane,
.evidence-panel {
  background: #fbfdff;
}

.conversation-head {
  background: #fff;
}

.conversation-head p,
.head-status {
  color: #667b8e;
}

.head-status b,
.answer header .selected {
  color: #2577bc;
}

.icon-btn {
  color: #567187;
}

.icon-btn:hover {
  border-color: #bed9ed;
  background: #eaf4fc;
  color: #2f80c5;
}

.empty-conversation,
.panel-empty {
  color: #6a8091;
}

.empty-conversation svg,
.panel-empty svg {
  color: #3c91d3;
}

.user-message {
  border-color: #cfdfec;
  background: #e9f3fb;
}

.markdown-answer :deep(strong) {
  color: #1d5f95;
}

.markdown-answer :deep(a) {
  color: #267fc5;
}

.markdown-answer :deep(pre) {
  border-color: #d7e4ee;
  background: #f1f6fa;
}

.markdown-answer :deep(.md-table-wrapper),
.result-table {
  border-color: #d4e2ed;
}

.markdown-answer :deep(th),
.result-table th {
  background: #edf5fb;
  color: #5b7387;
}

.markdown-answer :deep(th),
.markdown-answer :deep(td),
.result-table th,
.result-table td {
  border-color: #e1ebf2;
}

.result-table td {
  color: #425f72;
}

.evidence-link {
  background: #e7f2fb;
  color: #276fa9;
}

.composer {
  background: #fff;
}

.composer textarea {
  border-color: #c7d8e6;
}

.composer button {
  background: #2f80c5;
}

.composer button:disabled {
  background: #9bbbd3;
}

.resize-handle:hover {
  border-left-color: #3d91d1;
}

.question-summary small,
.legend {
  color: #627b8e;
}

.question-summary span {
  background: #e7f2fb;
  color: #286da5;
}

.blue {
  background: #3c91d3
}

.teal {
  background: #3d9fbe
}

.green {
  background: #4d9d9b
}

.graph-workspace {
  border-color: #c5dbea;
  background: #f2f8fc;
}

.graph-tools,
.graph-actions {
  border-color: #cfdfeb;
  background: rgb(255 255 255 / 94%);
}

.graph-tools .icon-btn.active {
  border-color: #b8d8ed;
  background: #e8f3fb;
  color: #2f80c5;
}

.graph-actions label {
  color: #2c79b5;
}

.graph-actions select {
  color: #526d82;
}

.node-summary {
  border-top-color: #d5e3ef;
}

.node-summary header {
  color: #627b8e;
}

.node-summary-type {
  border-color: #b8d7eb;
  background: #e8f3fb;
  color: #286fa8;
}

.node-summary-count {
  color: #2b75af;
}

.node-summary h3 {
  color: #1e4667;
}

.node-summary-relations span {
  border-color: #d5e3ed;
  background: #f7fbfe;
  color: #567186;
}
</style>

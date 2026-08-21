import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const component = readFileSync(new URL('./GraphifyDataQueryChat.vue', import.meta.url), 'utf8')
const assistantMessage = readFileSync(new URL('./GraphifyAssistantMessage.vue', import.meta.url), 'utf8')
const graphView = readFileSync(new URL('./GraphifyGraphView.vue', import.meta.url), 'utf8')
const evidenceGraph = readFileSync(new URL('./GraphifyEvidenceGraph.vue', import.meta.url), 'utf8')
const executionPath = readFileSync(new URL('./GraphifyExecutionPath.vue', import.meta.url), 'utf8')
const evidenceAdapter = readFileSync(new URL('./evidenceAdapter.ts', import.meta.url), 'utf8')
const chat = readFileSync(new URL('../../views/Chat/index.vue', import.meta.url), 'utf8')
const chatMain = readFileSync(new URL('../../components/chat/ChatMain.vue', import.meta.url), 'utf8')
const messageList = readFileSync(new URL('../../components/chat/MessageList.vue', import.meta.url), 'utf8')
const chatStream = readFileSync(new URL('../../composables/chat/useChatStream.ts', import.meta.url), 'utf8')
const runActivity = readFileSync(new URL('../../components/chat/AgentRunActivity.vue', import.meta.url), 'utf8')
const questionSkill = readFileSync(new URL('../../../../skills/skills/medical-catalog-question-semantics/SKILL.md', import.meta.url), 'utf8')
const systemPrompt = readFileSync(new URL('../../../../docs/operations/medical-catalog-question-semantics-system-prompt.md', import.meta.url), 'utf8')

test('empty state provides the centered business-question introduction', () => {
  assert.match(component, /class="graphify-quick-intro"/)
  assert.match(component, /从业务问题开始/)
  assert.match(component, /选择一个快捷问题，快速查看结构化表格数据。/)
})

test('quick questions provide 30 real catalog examples and show eight at a time', () => {
  const questionBlock = component.match(/const QUICK_QUESTIONS:[\s\S]*?\n\]/)?.[0] ?? ''

  assert.equal((questionBlock.match(/^  '/gm) ?? []).length, 30)
  assert.match(questionBlock, /查询“复方氯己定含漱液”的生产企业和规格/)
  assert.match(questionBlock, /查询“覆膜气管支架”的分类、材质和生产企业/)
  assert.match(questionBlock, /查询“互联网首诊（普通医师）”的支付类别和省级一档最高限额/)
  assert.doesNotMatch(questionBlock, /dosage_form|剂型/)
  assert.match(component, /quickQuestions\.value = shuffle\(QUICK_QUESTIONS\)\.slice\(0, 8\)/)
})

test('quick questions wrap as content-sized pills instead of fixed columns', () => {
  assert.match(component, /\.graphify-quick-pills-list\s*\{[\s\S]*?flex-wrap:\s*wrap/)
  assert.match(component, /\.graphify-quick-pill\s*\{[\s\S]*?flex:\s*0 0 auto/)
})

test('empty state hides the duplicate chat welcome and anchors its input at the bottom', () => {
  assert.match(component, /:deep\(\.graphify-data-query-chat \.chat-welcome-title\)[\s\S]*?display:\s*none/)
  assert.match(component, /:deep\(\.graphify-data-query-chat \.chat-welcome\)\s*\{[\s\S]*?justify-content:\s*flex-end/)
  assert.match(component, /:deep\(\.graphify-data-query-chat \.chat-welcome-input\)\s*\{[\s\S]*?margin-bottom:/)
})

test('each answer owns a Neo4j graph action instead of a global recent-query action', () => {
  assert.doesNotMatch(component, /最近查询图谱/)
  assert.doesNotMatch(component, /GraphifyGraphView/)
  assert.match(component, /component: GraphifyAssistantMessage/)
  assert.match(component, /neo4jGraph: turn\?\.neo4jGraph/)
  assert.match(assistantMessage, /const hasNeo4jGraph = computed\(\(\) => Boolean\(props\.neo4jGraph\?\.nodes\.length && props\.neo4jGraph\?\.edges\.length\)\)/)
  assert.match(assistantMessage, /<div v-if="hasNeo4jGraph"[\s\S]*?<button[\s\S]*?查看知识图谱/)
  assert.match(assistantMessage, /<GraphifyGraphView :open="graphViewOpen" :evidence="evidence"/)
})

test('answer graph action only appears for real Neo4j nodes and edges', () => {
  assert.match(assistantMessage, /neo4jGraph\?: Neo4jReadCypherGraph/)
  assert.match(assistantMessage, /v-if="hasNeo4jGraph"/)
  assert.match(assistantMessage, /:evidence="evidence"/)
  assert.match(assistantMessage, /const shouldRenderResultTable = computed\(\(\) => Boolean\(contentParts\.value\.hasPlaceholder && props\.evidence && hasRows\.value\)\)/)
  assert.match(assistantMessage, /v-if="shouldRenderResultTable && evidence"/)
})

test('knowledge graph dialog renders only the complete Neo4j graph', () => {
  assert.match(graphView, /<GraphifyEvidenceGraph[\s\S]*?view-mode="full"[\s\S]*?fullscreen/)
  assert.match(graphView, /:show-summary="false"/)
  assert.doesNotMatch(graphView, /GraphifyExecutionPath/)
  assert.doesNotMatch(graphView, /graphify-graph-view-summary/)
  assert.doesNotMatch(graphView, /graphify-graph-view-legend/)
  assert.doesNotMatch(graphView, /graphify-graph-view-actions/)
  assert.doesNotMatch(graphView, /查询执行链路|Wren MDL|查询字段|来源记录/)
  assert.match(evidenceGraph, /showSummary\?: boolean/)
  assert.match(evidenceGraph, /v-if="showSummary"/)
})

test('query progress follows the latest user message for the entire run', () => {
  assert.match(component, /function handleRunStateChanged\(isRunning: boolean\)/)
  assert.match(component, /:on-run-state-changed="handleRunStateChanged"/)
  assert.match(component, /:run-activity-adapter="adaptQueryFlowActivities"/)
  assert.match(component, /:force-run-activity="true"/)
  assert.match(component, /run-activity-placement="after-latest-user"/)
  assert.match(component, /const QUERY_FLOW_STEPS/)
  assert.match(component, /function createQueryFlowActivities\(\)/)
  assert.match(component, /queryFlowActivities\.value = createQueryFlowActivities\(\)/)
  assert.match(component, /\{ id: 'answer', name: '整理回答' \}/)
  assert.match(component, /function beginAnswerComposition\(content: string\)/)
  assert.match(component, /function completeAnswerComposition\(\)/)
  assert.match(component, /const answerCompositionStarted = ref\(false\)/)
  assert.match(component, /answerCompositionStarted\.value && t\.status === 'running'/)
  assert.match(component, /:on-assistant-text-activity="beginAnswerComposition"/)
  assert.match(component, /'read-cypher':\s*'查询知识图谱'/)
  assert.match(component, /parseNeo4jReadCypherGraph\(t\.content \?\? ''\)/)
  assert.match(chatStream, /const failedTool = toolCallsInProgress\.value\[toolCallsInProgress\.value\.length - 1\]/)
  assert.match(chatStream, /onToolCallActivity\?\.\(\{ toolName: failedTool\?\.name \?\? '', status: 'failed' \}\)/)
  assert.match(chatStream, /onToolCallActivity\?\.\(\{ toolName: activeTool\?\.name \?\? '', status: 'completed', content: e\.content \}\)/)
  assert.match(chat, /content\?: string/)
  assert.match(component, /const FINAL_ANSWER_ANCHOR/)
  assert.match(component, /function assistantBody\(content: string\): string/)
  assert.match(runActivity, /const expanded = ref\(true\)/)
  assert.match(runActivity, /等待\$\{activity\.label\}/)
  assert.doesNotMatch(component, /graphify-query-flow/)
  assert.match(component, /component: GraphifyAssistantMessage/)
  assert.match(chat, /onRunStateChanged\?: \(isRunning: boolean\) => void/)
  assert.match(chat, /onAssistantTextActivity\?: \(content: string\) => void/)
  assert.match(chatStream, /onAssistantTextActivity\?\.\(currentText\)/)
  assert.match(chat, /watch\(isRunning, \(isRunning\) => \{\s*props\.onRunStateChanged\?\.\(isRunning\)/)
  assert.match(chat, /runActivityAdapter\?: \(activities: readonly RunActivity\[\]\) => RunActivity\[\]/)
  assert.match(chat, /const presentationRunActivities = computed\(\(\) =>/)
  assert.match(chat, /:run-activities="presentationRunActivities"/)
  assert.match(chat, /runActivityPlacement\?: 'tail' \| 'after-latest-user'/)
  assert.match(chat, /forceRunActivity\?: boolean/)
  assert.match(chatMain, /:run-activity-placement="runActivityPlacement"/)
  assert.match(chatMain, /props\.forceRunActivity \? props\.isRunning/)
  assert.match(messageList, /gIdx === latestUserGroupIndex/)
  assert.doesNotMatch(chatMain, /hideRunActivity/)
})

test('uses 医保 consistently across the query assistant UI, prompt, and skill', () => {
  for (const content of [component, assistantMessage, executionPath, evidenceAdapter, questionSkill, systemPrompt]) {
    assert.doesNotMatch(content, /医药|医疗/)
  }
  assert.match(component, /医保服务项目/)
  assert.match(evidenceAdapter, /medical_catalog: '医保目录'/)
  assert.match(questionSkill, /# 医保目录问题语义解析与呈现/)
  assert.match(systemPrompt, /企业级医保目录问数智能体/)
})

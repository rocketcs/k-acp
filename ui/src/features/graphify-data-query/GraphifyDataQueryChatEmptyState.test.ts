import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const component = readFileSync(new URL('./GraphifyDataQueryChat.vue', import.meta.url), 'utf8')
const assistantMessage = readFileSync(new URL('./GraphifyAssistantMessage.vue', import.meta.url), 'utf8')
const executionPath = readFileSync(new URL('./GraphifyExecutionPath.vue', import.meta.url), 'utf8')
const evidenceAdapter = readFileSync(new URL('./evidenceAdapter.ts', import.meta.url), 'utf8')
const chat = readFileSync(new URL('../../views/Chat/index.vue', import.meta.url), 'utf8')
const chatMain = readFileSync(new URL('../../components/chat/ChatMain.vue', import.meta.url), 'utf8')
const messageList = readFileSync(new URL('../../components/chat/MessageList.vue', import.meta.url), 'utf8')
const chatStream = readFileSync(new URL('../../composables/chat/useChatStream.ts', import.meta.url), 'utf8')
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

test('recent-query evidence graph entry is shown only for a non-empty result', () => {
  assert.match(component, /<span>最近查询图谱<\/span>/)
  assert.match(component, /const canViewLatestGraph = computed\(\(\) => Boolean\(latestEvidence\.value\?\.evidence\?\.result\.rows\.length\)\)/)
  assert.match(component, /<button v-if="canViewLatestGraph" type="button" class="graphify-graph-entry"/)
  assert.match(component, /<GraphifyGraphView :open="graphViewOpen" :evidence="latestEvidence\?\.evidence"/)
})

test('recent-query evidence graph entry follows the responsive composer anchor', () => {
  assert.match(component, /\.graphify-data-query-chat-shell\s*\{[\s\S]*?--graphify-composer-bottom:\s*clamp\(32px, 5vh, 72px\)/)
  assert.match(component, /\.graphify-graph-entry\s*\{[\s\S]*?left:\s*calc\(50% \+ min\(130px, 12vw\)\)[\s\S]*?bottom:\s*calc\(var\(--graphify-composer-bottom\) \+ 88px\)[\s\S]*?transform:\s*translateX\(-50%\)/)
  assert.match(component, /@media \(max-width: 900px\)\s*\{[\s\S]*?\.graphify-graph-entry\s*\{[\s\S]*?left:\s*50%/)
})

test('query progress follows the latest user message for the entire run', () => {
  assert.match(component, /function handleRunStateChanged\(isRunning: boolean\)/)
  assert.match(component, /:on-run-state-changed="handleRunStateChanged"/)
  assert.match(component, /:run-activity-adapter="adaptQueryFlowActivities"/)
  assert.match(component, /:force-run-activity="true"/)
  assert.match(component, /run-activity-placement="after-latest-user"/)
  assert.match(component, /evidence_subgraph:\s*'查询知识图谱'/)
  assert.match(component, /'read-cypher':\s*'查询知识图谱'/)
  assert.match(component, /function appendCompletedEvidenceGraphStep\(content\?: string\)/)
  assert.match(component, /parseGraphifyEvidence\('', content\)/)
  assert.match(chatStream, /const failedTool = toolCallsInProgress\.value\[toolCallsInProgress\.value\.length - 1\]/)
  assert.match(chatStream, /onToolCallActivity\?\.\(\{ toolName: failedTool\?\.name \?\? '', status: 'failed' \}\)/)
  assert.match(chatStream, /onToolCallActivity\?\.\(\{ toolName: activeTool\?\.name \?\? '', status: 'completed', content: e\.content \}\)/)
  assert.match(chat, /content\?: string/)
  assert.doesNotMatch(component, /graphify-query-flow/)
  assert.match(component, /<button v-if="canViewLatestGraph" type="button" class="graphify-graph-entry"/)
  assert.match(chat, /onRunStateChanged\?: \(isRunning: boolean\) => void/)
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

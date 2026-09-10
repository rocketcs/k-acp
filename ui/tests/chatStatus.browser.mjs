import { createRequire } from 'node:module'
import assert from 'node:assert/strict'

// Run against the Vite dev server; SSE is simulated without creating real conversations.
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const baseURL = process.env.CHAT_TEST_URL || 'http://127.0.0.1:3030'

const browser = await chromium.launch({ headless: true })
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  await page.route('**/status-check', route => route.fulfill({ contentType: 'text/html', body: '<html><body><div id="test"></div></body></html>' }))
  await page.goto(`${baseURL}/status-check`)
  await page.evaluate(async () => {
    const vueSource = await (await fetch('/src/composables/useAgentClient.ts')).text()
    const piniaSource = await (await fetch('/src/stores/modules/account.ts')).text()
    const { createApp, h, ref } = await import(vueSource.match(/from "([^"]*\/vue.js[^\"]*)"/)[1])
    const { createPinia } = await import(piniaSource.match(/from "([^"]*\/pinia.js[^\"]*)"/)[1])
    const { useChatStream } = await import('/src/composables/chat/useChatStream.ts')
    const { default: Activity } = await import('/src/components/chat/AgentRunActivity.vue')
    const { default: Waiting } = await import('/src/components/chat/AgentRunWaiting.vue')
    const { shouldShowRunActivity, shouldShowRunWaiting } = await import('/src/utils/chat/runActivity.ts')
    window.fetch = async () => new Response(new ReadableStream({ start(c) { window.streamController = c } }), { headers: { 'Content-Type': 'text/event-stream' } })
    window.sendEvent = async event => {
      window.streamController.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(event)}\n\n`))
      await new Promise(resolve => setTimeout(resolve, 30))
    }
    createApp({ setup() {
      const stream = useChatStream(ref('test-agent'), ref({ agentCode: 'test-agent' }), ref('test-session'), undefined, undefined, undefined, ref(false))
      window.chatStream = stream
      window.startRun = () => { window.runPromise = stream.sendMessage('test', [{ id: 'user', role: 'user', content: 'test' }]) }
      return () => h('main', { style: 'padding:16px;font-family:sans-serif' }, [
        shouldShowRunActivity(false, stream.isRunning.value, stream.hasVisibleAnswer.value)
          ? h(Activity, { activities: stream.runActivities.value, startedAt: stream.runStartedAt.value, isRunning: true, onAbort: () => stream.abortRun() }) : null,
        shouldShowRunWaiting(false, stream.isRunning.value, stream.hasVisibleAnswer.value)
          ? h(Waiting, { startedAt: stream.runStartedAt.value }) : null,
        h('p', stream.streamingContent.value),
      ])
    } }).use(createPinia()).mount('#test')
  })
  await page.evaluate(() => window.startRun())
  await page.getByText('正在处理请求', { exact: true }).waitFor()
  await page.evaluate(async () => {
    await window.sendEvent({ type: 'RUN_STARTED', threadId: 'test-session', runId: '1' })
    await window.sendEvent({ type: 'TOOL_CALL_START', toolCallId: 'a', toolCallName: 'query_graph' })
    await window.sendEvent({ type: 'TOOL_CALL_ARGS', toolCallId: 'a', delta: '{"mode":"search","query":"头晕"}' })
  })
  await page.getByText('关键词：头晕', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-run-activity').count(), 1)
  assert.equal(await page.locator('.agent-run-activity__details').count(), 0)
  await page.evaluate(async () => {
    await window.sendEvent({ type: 'TOOL_CALL_START', toolCallId: 'b', toolCallName: 'get_graph_summary' })
    await window.sendEvent({ type: 'TOOL_CALL_RESULT', toolCallId: 'a', messageId: 'result-a', content: '{}' })
  })
  assert.deepEqual(await page.evaluate(() => window.chatStream.toolCallsInProgress.value.map(t => t.id)), ['b'])
  assert.deepEqual(await page.evaluate(() => window.chatStream.runActivities.value.map(t => t.status)), ['completed', 'running'])
  await page.screenshot({ path: '/tmp/k-acp-chat-status-desktop.png' })
  await page.evaluate(() => window.sendEvent({ type: 'TOOL_CALL_RESULT', toolCallId: 'b', messageId: 'result-b', content: '{}' }))
  await page.getByText('正在整理回复', { exact: true }).waitFor()
  await page.setViewportSize({ width: 375, height: 812 })
  await page.screenshot({ path: '/tmp/k-acp-chat-status-mobile.png' })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
  await page.evaluate(async () => {
    await window.sendEvent({ type: 'TEXT_MESSAGE_START', messageId: 'answer', role: 'assistant' })
    await window.sendEvent({ type: 'TEXT_MESSAGE_CONTENT', messageId: 'answer', delta: 'Test answer' })
  })
  await page.getByText('任务仍在运行，正在等待下一步结果').waitFor()
  assert.equal(await page.locator('p').textContent(), 'Test answer')
  assert.equal(await page.evaluate(() => window.chatStream.isRunning.value), true)
  await page.evaluate(() => window.sendEvent({ type: 'TEXT_MESSAGE_CONTENT', messageId: 'answer', delta: ' continued' }))
  assert.equal(await page.locator('p').textContent(), 'Test answer continued')
  assert.equal(await page.locator('.agent-run-activity,.agent-run-waiting').count(), 1)
  await page.evaluate(async () => {
    await window.sendEvent({ type: 'TEXT_MESSAGE_END', messageId: 'answer' })
    await window.sendEvent({ type: 'RUN_FINISHED', threadId: 'test-session', runId: '1' })
    window.streamController.close()
    await window.runPromise
  })
  assert.equal(await page.locator('[aria-live="polite"]').count(), 0)
  await page.evaluate(() => window.startRun())
  await page.getByText('正在处理请求', { exact: true }).waitFor()
  assert.equal(await page.evaluate(() => window.chatStream.runActivities.value.length), 0)
  await page.evaluate(async () => {
    await window.sendEvent({ type: 'RUN_ERROR', message: 'Test failure' })
    window.streamController.close()
    await window.runPromise
  })
  assert.equal(await page.locator('.agent-run-activity,.agent-run-waiting').count(), 0)
  console.log('PASS: pre-event waiting, concurrent tools, history disabled, reply waiting, completion, second run reset, error cleanup, mobile overflow')
} finally {
  await browser.close()
}

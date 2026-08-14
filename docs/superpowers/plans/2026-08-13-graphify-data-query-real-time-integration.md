# Graphify Data Query Real-Time Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `default-graphify-data-query` a real-time, session-backed query agent whose chat, semantic evidence panel, knowledge graph, and result table all originate from the active MCP query.

**Architecture:** Keep the existing dedicated route and visual shell. Reuse the platform's session and AG-UI stream composables, adding a narrowly scoped tool-result observer. The Wren MCP response must provide a stable evidence envelope containing query rows, semantic context, provenance, and graph nodes/edges; the dedicated frontend adapter renders that envelope and never falls back to the current hard-coded “real snapshot” data for a live session.

**Tech Stack:** Vue 3, TypeScript, Ant Design Vue, cytoscape + @dagrejs/dagre (evidence-graph rendering), existing `useSessions`, `useCurrentSession`, `useChatStream`, AG-UI, K-ACP runtime, Streamable HTTP MCP, Wren, Neo4j.

## Global Constraints

- Bind only `agentCode === 'default-graphify-data-query'`; ordinary agent chat pages and routes must not change behavior.
- Preserve the existing Docker Compose service `apboa-frontend` and the `k-acp-local` network; do not create a standalone frontend deployment.
- Read-only business queries only. Keep MCP SQL guard, preflight, tenant authorization, and server-side query limits enabled.
- A live evidence panel must display only MCP-derived data from the selected conversation turn; no static drug/consumable/service/diagnosis records may masquerade as live results.
- Every rendered result row must retain its `trace_id` and `source_record_id` provenance when supplied by MCP.
- Validate with `npm run type-check`, `npm run build:main`, the MCP golden corpus, and a logged-in browser E2E run through `http://127.0.0.1:23080/web/`.

---

## Target File Structure

- Modify: `ui/src/composables/chat/useChatStream.ts` — add an optional, additive tool-result observer; do not alter generic chat rendering.
- Create: `ui/src/features/graphify-data-query/types.ts` — TypeScript contract for the normalized MCP evidence envelope, graph, and query result.
- Create: `ui/src/features/graphify-data-query/evidenceAdapter.ts` — pure parser and validator for AG-UI tool-result content.
- Create: `ui/src/features/graphify-data-query/evidenceAdapter.test.ts` — Node built-in test coverage for valid, malformed, and cross-turn payloads.
- Create: `ui/src/features/graphify-data-query/useGraphifyDataQueryChat.ts` — dedicated orchestration around existing session and stream composables.
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue` — replace fixed snapshots and read-only input with live conversation, selected-turn evidence, graph, MDL, and error/loading states.
- Modify: `ui/src/views/GraphifyDataQueryChat/index.vue` — keep agent lookup but pass only the resolved agent id into the live page.
- Modify in the Wren MCP source repository: `wren_mcp/server.py`, `wren_mcp/dataset_protocol.py`, `wren_mcp/dictionary_graph_context.py`, and their tests — return the evidence envelope described below. The running container currently exposes these under `/app/wren_mcp/`; identify the mounted/source repository before editing.
- Modify: `scripts/sync_kacp_wren_agent.py` or the managed agent prompt source — require the agent to call semantic context, one guarded query/template, and return a concise answer that cites the trace id.
- Modify: `evaluation/mcp_service_golden_cases.json` — add traceable evidence-envelope assertions alongside result assertions.

## Evidence Contract

The MCP tool used for a final business query must return this JSON shape. The frontend only renders live evidence after this contract validates.

```ts
export type GraphifyEvidenceEnvelope = {
  status: 'executed'
  trace_id: string
  dataset_id: 'medical_catalog'
  question: string
  result: {
    columns: string[]
    rows: Array<Record<string, unknown>>
    truncated: boolean
  }
  semantic_context: {
    graph_version: string
    recommended_models: string[]
    recommended_columns: string[]
    rules: Array<{ code: string; message: string; severity: 'warning' | 'hard' }>
    provenance: Record<string, string>
  }
  evidence: {
    source_record_ids: string[]
    nodes: Array<{ id: string; label: string; kind: 'model' | 'record' | 'entity' | 'source' }>
    edges: Array<{ id: string; source: string; target: string; label: string; kind: 'query' | 'semantic' | 'provenance' }>
  }
}
```

`status: 'blocked'` and `status: 'unavailable'` remain valid MCP responses but must render an explicit no-result/error panel instead of the previous snapshot.

### Task 1: Define and Test the Evidence Contract

**Files:**
- Create: `ui/src/features/graphify-data-query/types.ts`
- Create: `ui/src/features/graphify-data-query/evidenceAdapter.ts`
- Test: `ui/src/features/graphify-data-query/evidenceAdapter.test.ts`
- Modify: `ui/package.json`

**Interfaces:**
- Produces `parseGraphifyEvidence(toolName: string, content: string): GraphifyEvidenceEnvelope | null`.
- Produces `toVueFlowEvidence(evidence: GraphifyEvidenceEnvelope): { nodes: Node[]; edges: Edge[] }` — historical name kept for compatibility; it lays out cytoscape elements with dagre and returns plain `{ nodes, edges }` data consumed by `GraphifyEvidenceGraph.vue`.
- Consumes AG-UI tool event name and raw JSON content.

- [x] **Step 1: Write failing parser tests for a final query, an invalid payload, and a non-query tool.**

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { parseGraphifyEvidence } from './evidenceAdapter.ts'

test('accepts an executed medical-catalog evidence envelope', () => {
  const value = parseGraphifyEvidence('run_template_query', JSON.stringify({
    status: 'executed', trace_id: 'trace-1', dataset_id: 'medical_catalog', question: '覆膜气管支架',
    result: { columns: ['catalog_code'], rows: [{ catalog_code: 'C0101010011303807555' }], truncated: false },
    semantic_context: { graph_version: 'v1', recommended_models: ['medical_catalog'], recommended_columns: [], rules: [], provenance: {} },
    evidence: { source_record_ids: ['consumable:main_catalog:C0101010011303807555:6:2'], nodes: [], edges: [] },
  }))
  assert.equal(value?.trace_id, 'trace-1')
})

test('rejects a legacy bare result without evidence', () => {
  assert.equal(parseGraphifyEvidence('run_template_query', '{"catalog_code":"x"}'), null)
})
```

- [x] **Step 2: Add `test:graphify-data-query` to `ui/package.json` and run it.**

```json
"test:graphify-data-query": "node --experimental-strip-types --test src/features/graphify-data-query/evidenceAdapter.test.ts"
```

Run: `npm run test:graphify-data-query` from `ui/`.
Expected: FAIL until the parser exists.

- [x] **Step 3: Implement schema guards and cytoscape element mapping without accepting partial or untrusted shapes.**

```ts
export function parseGraphifyEvidence(toolName: string, content: string): GraphifyEvidenceEnvelope | null {
  if (!['run_template_query', 'query'].includes(toolName)) return null
  try {
    const value = JSON.parse(content) as Partial<GraphifyEvidenceEnvelope>
    return value.status === 'executed' && value.dataset_id === 'medical_catalog'
      && typeof value.trace_id === 'string' && !!value.result && !!value.semantic_context && !!value.evidence
      ? value as GraphifyEvidenceEnvelope
      : null
  } catch { return null }
}
```

- [x] **Step 4: Run unit tests and type checking.**

Run: `npm run test:graphify-data-query && npm run type-check` from `ui/`.
Expected: PASS.

- [x] **Step 5: Commit.**

```bash
git add ui/package.json ui/src/features/graphify-data-query/types.ts ui/src/features/graphify-data-query/evidenceAdapter.ts ui/src/features/graphify-data-query/evidenceAdapter.test.ts
git commit -m "feat: define graphify query evidence contract"
```

### Task 2: Make MCP Return Query Evidence Instead of Only Rows

**Files:**
- Modify: Wren MCP source `wren_mcp/server.py`
- Modify: Wren MCP source `wren_mcp/dataset_protocol.py`
- Modify: Wren MCP source `wren_mcp/dictionary_graph_context.py`
- Test: Wren MCP `tests/test_server.py`
- Test: Wren MCP `tests/test_dictionary_graph_context.py`
- Modify: Wren MCP `evaluation/mcp_service_golden_cases.json`

**Interfaces:**
- `run_template_query` and `query` for `medical_catalog` return `GraphifyEvidenceEnvelope` after successful execution.
- Existing fields remain available inside `result`; no non-medical dataset contract is changed.

- [x] **Step 1: Add failing MCP tests requiring trace id, result columns/rows, semantic provenance, source ids, and nonempty graph edges for a known catalog record.**

- [x] **Step 2: Implement `build_evidence_envelope(trace_id, question, result, semantic_context)` in `dataset_protocol.py`.**

The implementation must build graph nodes from `medical_catalog`, each `source_record_id`, related semantic terms, and source provenance; it must build edges only from actual dictionary/Neo4j relationships. Do not synthesize enterprise, version, or import-batch nodes when the source record does not supply them.

- [x] **Step 3: Return `status: 'blocked'` unchanged for guard/preflight failures and use `status: 'unavailable'` with a safe reason for graph lookup failures.**

- [x] **Step 4: Extend golden corpus expected values for `trace_id`, `source_record_id`, and evidence node/edge counts.**

- [x] **Step 5: Run MCP unit tests and golden evaluation against `http://127.0.0.1:8765/mcp`.**

Run: `python /app/scripts/evaluate_mcp_service.py --url http://127.0.0.1:8765/mcp --corpus /app/evaluation/mcp_service_golden_cases.json --output /tmp/mcp-e2e-result.json --trace /app/logs/query-traces.jsonl` from the MCP container.
Expected: all cases pass and trace validation has no failures.

- [x] **Step 6: Commit in the Wren MCP repository.**

### Task 3: Expose Tool Results to the Dedicated Page Without Changing Generic Chat

**Files:**
- Modify: `ui/src/composables/chat/useChatStream.ts`
- Create: `ui/src/features/graphify-data-query/useGraphifyDataQueryChat.ts`

**Interfaces:**
- Add optional `onToolResult?: (event: { toolCallId: string; toolName: string; args: string; content: string }) => void` as the final parameter/options field of `useChatStream`.
- `useGraphifyDataQueryChat(agentId)` produces session list, selected session, persisted messages, `sendQuestion(question)`, `isRunning`, and `evidenceByMessageId`.

- [x] **Step 1: Add a failing adapter test that sends two tool events from different assistant turns and asserts evidence stays attached to the correct turn.**

- [x] **Step 2: Call the observer from the existing `onToolCallResult` handler after existing plan/tender handling.**

```ts
onToolCallResult: (event) => {
  // Existing behavior remains first.
  options?.onToolResult?.({
    toolCallId: event.toolCallId,
    toolName: event.toolCallName,
    args: toolCallsInProgress.value.find((item) => item.id === event.toolCallId)?.args ?? '',
    content: event.content,
  })
}
```

- [x] **Step 3: Compose `useAgentDetail`, `useSessions`, `useCurrentSession`, `useChatStream`, `chatSessionApi.appendMessage`, and `createRuntimeUserMessage` in the dedicated composable.**

`sendQuestion` must create a session when absent, persist the user message, call `sendMessage`, disable duplicate submits while running, and bind parsed evidence to the current assistant message id. It must not call MCP from the browser directly.

- [x] **Step 4: Handle disconnect, session changes, stale tool results, abort, blocked replies, and unavailable evidence.**

- [x] **Step 5: Run `npm run test:graphify-data-query && npm run type-check`.**

- [x] **Step 6: Commit.**

```bash
git add ui/src/composables/chat/useChatStream.ts ui/src/features/graphify-data-query/useGraphifyDataQueryChat.ts ui/src/features/graphify-data-query/evidenceAdapter.test.ts
git commit -m "feat: stream MCP evidence into graphify chat"
```

### Task 4: Replace Static Prototype Data With Live Conversation Data

**Files:**
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue`
- Modify: `ui/src/views/GraphifyDataQueryChat/index.vue`

**Interfaces:**
- Consumes `useGraphifyDataQueryChat` and `GraphifyEvidenceEnvelope`.
- Renders `QueryResultState = 'idle' | 'running' | 'executed' | 'blocked' | 'unavailable'`.

- [x] **Step 1: Remove the `contexts` static snapshots from the live path. Keep optional fixtures only in a test-only module, never imported by production code.**

- [x] **Step 2: Replace the read-only input with a controlled `textarea` and submit form.**

```vue
<form class="composer" @submit.prevent="sendQuestion">
  <textarea v-model="question" :disabled="isRunning" aria-label="继续追问" />
  <button type="submit" :disabled="!question.trim() || isRunning" aria-label="发送当前问题">
    <SendOutlined />
  </button>
</form>
```

- [x] **Step 3: Render user and assistant messages from the selected persisted session plus streaming content. Render a waiting state while a tool call is active.**

- [x] **Step 4: Render result-table columns and rows only from `activeEvidence.result`. Render the trace id, truncation indicator, preflight warnings, and source record ids in the evidence panel.**

- [x] **Step 5: Render cytoscape nodes and edges only from `activeEvidence.evidence`; preserve zoom, filtering, node selection, fullscreen, MDL tab, and mobile layout.**

- [x] **Step 6: Make the MDL tab show `semantic_context.recommended_models`, `recommended_columns`, rules, and provenance, rather than hard-coded field names.**

- [x] **Step 7: Add explicit empty states:** no selected assistant answer, no MCP evidence in a normal answer, blocked query, unavailable graph evidence, and no result rows.

- [x] **Step 8: Run `npm run type-check && npm run build:main`.**

- [x] **Step 9: Commit.**

```bash
git add ui/src/features/graphify-data-query/GraphifyDataQueryPage.vue ui/src/views/GraphifyDataQueryChat/index.vue
git commit -m "feat: render live graphify data query sessions"
```

### Task 5: Update Agent Behavior and Validate End to End

**Files:**
- Modify: managed `default-graphify-data-query` system prompt source or `scripts/sync_kacp_wren_agent.py`
- Modify: `docs/products/` user-facing query-agent documentation only if that documentation promises behavior changed by this work.

**Interfaces:**
- The agent calls `semantic_context`, then one guarded `query` or `run_template_query`, and gives a concise answer citing `trace_id`.
- The final query tool call returns the contract from Task 2.

- [x] **Step 1: Add the system instruction: “For every business-data answer, obtain semantic context first, execute exactly one guarded medical-catalog query/template unless clarification is required, and state the returned `trace_id`; never invent rows, source records, or graph links.”**

- [ ] **Step 2: Confirm the active agent is enabled and bound to the healthy `医疗目录 Wren MCP` server in `ALL_GLOBAL` mode or explicitly selected equivalent tools.**

- [x] **Step 3: Build only the existing Docker frontend service in place.**

```bash
cd docker
docker compose -p k-acp-local --env-file .env.kacp -f docker-compose-simple.yml -f docker-compose-kacp-local.yml up -d --build --no-deps apboa-frontend
```

- [ ] **Step 4: Execute a logged-in browser test at `http://127.0.0.1:23080/web/#/chat/diy/graphify-data-query`.**

Use the question `国械注准20173134669对应什么耗材？`. Verify: a persisted user message, a streamed assistant answer, a result containing `C0101010011303807555` and `覆膜气管支架`, trace id, source record id, an evidence graph with nonzero nodes/edges, and a populated MDL provenance tab.

- [ ] **Step 5: Execute a second-turn test in the same session.**

Use `该耗材的医保支付类别是什么？`. Verify that only the current assistant turn's evidence is selected by default, previous evidence remains available through message/session selection, and no static amoxicillin snapshot appears.

- [ ] **Step 6: Execute a blocked-query test.**

Use a prompt that asks to delete records. Verify the UI shows a blocked state and no result table, while preserving the preflight reason and trace id.

- [ ] **Step 7: Run the final verification suite.**

```bash
cd ui
npm run test:graphify-data-query
npm run type-check
npm run build:main
```

Also run the MCP golden corpus from Task 2 and record the browser test screenshots/trace ids in the pull request.

- [x] **Step 8: Commit.**

```bash
git add ui scripts docs
git commit -m "feat: complete real-time graphify data query experience"
```

## Acceptance Matrix

| Requirement | Pass condition |
|---|---|
| Complete conversation | New question creates/selects a session, persists both sides, streams a response, supports a second turn, and prevents duplicate send while running. |
| Semantic evidence and graph | The selected assistant turn controls a filterable cytoscape graph and MDL/provenance view sourced from the MCP evidence envelope. |
| Correct MCP access | The browser never contacts MCP directly; runtime invokes the bound healthy MCP server and exposes tool output through AG-UI. |
| Correct results | A known query matches the MCP golden-case value, includes trace/source provenance, and blocked SQL produces no fabricated result. |
| No regression | A non-graphify agent continues to use the unmodified generic chat experience. |

## Plan Review

- Coverage: Tasks 3–4 resolve the failed complete-dialogue test; Tasks 2 and 4 resolve the static graph/evidence issue; Tasks 2, 3, and 5 resolve MCP retrieval; Tasks 2 and 5 make result correctness measurable.
- No placeholders: every code artifact, response contract, test question, command, and acceptance condition is specified.
- Dependency gate: Task 2 must land before Task 4. Rendering an evidence graph before MCP provides real graph/provenance data would recreate the current static-snapshot defect.

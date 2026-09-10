# 医疗目录问数 —— 表格 / 图谱不显示：根因与修复方案

> 状态：待评审（未改代码）
> 关联：乙类耗材最高限额查询，前端「智能问数结果」只有文本答案、无结构化表格与证据图谱。
> 证据来源：登录前真实界面（admin）实测 + 后端 query trace + chat_message 落库检查。

## 一、结论（先看这个）

**查询本身 100% 成功**：乙类问题（trace `165c0b9b`）Wren 返回 `result_bytes=44155`，几百条真实乙类耗材（含最高限额），智能体的文本答案也照实列出了正确数据。

**界面没表格/图谱，是因为前端 `activeEvidence` 为空**，页面直接显示「该回答未返回可验证的 MCP 查询证据」→ 表格区块（`result-table`）与图谱（`evidence-graph`）都不渲染。

根因拆成两条独立的坑：

| | 问题 | 证据 |
|---|---|---|
| **A** | 智能体**不稳定执行最终查询**：拿到语义上下文后有时不调 `query`/`run_template_query` 就自行组织文本回答 | 实时提问「耗材的最高限额」trace `76b1a29f` 事件序列 `question→context` 即止，无 `preflight`/`query`。|
| **B** | 查询结果**没能回到前端的 `activeEvidence`**（即使执行了 query） | ① DB `chat_message` 里该轮只有 2 条 tool 消息（semantic_context、query_preflight），**没有 query 执行的 tool 消息**；② 落库 tool 消息格式为 `{args, result}`，**缺顶层 `name`**。|

## 二、问题 B 的精确根因

前端证据只有两类来源，两条都断了：

**B1 · 实时路径（`onToolResult`）**
`useGraphifyDataQueryChat.ts` 的 `onToolResult` 调 `parseGraphifyEvidence(toolName, content)`（见 `evidenceAdapter.ts`）。
- 只有 `toolName ∈ {'run_template_query','query'}` 才会尝试解析（`FINAL_QUERY_TOOLS`）。
- `toolName` 来自 `useChatStream.onToolCallResult` 的 `activeTool?.name ?? ''`：若工具「开始事件」未稳定上报，`name` 为空；或实际执行工具名与白名单不匹配，则该条结果被丢弃。
- **当前即使 query 真执行了，前端也未落下 evidence**（实测实时提问 `activeEvidence` 为空），指向该值未对 / 未触发。

**B2 · 恢复路径（`parsePersistedToolResult`）**
历史/重放会话靠 `restorePersistedEvidence` 从落库的 `role='tool'` 消息恢复。
- 落库 tool 消息 content：`{"args":"…","result":"…"}`（实测自 `chat_message`，**没有顶层 `name`**）。
- 但 `parsePersistedToolResult` 第一行就是 `if (typeof record.name !== 'string') return null` → **必然失败** → 重放进不来 evidence。
- 注：前端 `utils/chat/format.ts` 的 `buildToolCallsContent` 生成的是 `{name, totalTimes, args, result}`（**带 name**）。DB 里却是 `{args, result}`，说明落库走的不是这条前端路径，而是后端/运行时另有一条持久化把 name 丢掉了。

## 三、修复方案

### 修复 A —— 让智能体“先查后答”
- **文件**：`wren-medical-catalog/scripts/sync_kacp_wren_agent.py` 的 `SYSTEM_PROMPT`（同步后写入 K-ACP `agent_definition.system_prompt`）。
- **改动**：在「执行与回答」节强化一条硬规则——涉及具体业务数据的作答，必须先调用 `run_template_query` 或 `query` 取得实际行数据，禁止仅凭 `semantic_context`/字段推荐就罗列具体目录项；语义上下文只用于选字段与模板。
- **风险**：低（指令层修正），需同步后再验证。

### 修复 B —— 让查询结果稳定回到前端
- **B1 前端**：让 evidence 解析不依赖不可靠的工具名。
  - `evidenceAdapter.ts`：新增一个「内容兜底」——当 `toolName` 不在白名单但 `content` 本身满足 executed/evidence envelope 约束时，同样返回 envelope（以 `status==='executed'`、`dataset_id==='medical_catalog'`、`result.rows` 存在为准）。
  - `useGraphifyDataQueryChat.ts`：`onToolResult` 里 toolName 为空时也尝试走该兜底。
- **B2 后端/解析**：
  - 定位把 tool 消息落成 `{args, result}`（丢 name）的那条持久化路径，补回 `name`（或至少是 `query`）；若它来自后端运行时，则改后端；若是另一处前端逻辑，则改前端。
  - `evidenceAdapter.ts` 的 `parsePersistedToolResult`：改为在 `name` 缺失时，直接尝试把 `result` 解析成 evidence/outcome（依据 `status`/`dataset_id` 判断），不再依赖顶层 `name`。

### 验证（修复后必做）
1. 部署后登录前端（admin），发「乙类耗材里有最高限价的目录项」等，断言出现带列头的表格 + 图谱渐进展开。
2. 新建会话重放同一问题，断言表格仍恢复显示。
3. 观察 trace：智能体对「耗材最高限额」也会走 `preflight→query`。

## 四、待确认/需进一步定位（实施时逐项验证）
- 把 tool 消息落成 `{args, result}` 的具体代码位置（前端 vs 后端运行时）。
- query 的执行结果为何没有额外落一条 tool 消息（可能与 B1 工具名/时序有关）。
- 智能体不执行最终 query 的触发率（在 prompt 修正后复测）。

---
*方案供评审；确认后再按其逐步实施，改动均可单独回滚。*

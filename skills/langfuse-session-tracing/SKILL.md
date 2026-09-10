---
name: langfuse-session-tracing
description: 通过 Langfuse 公共 API 自动发现 K-ACP 新 session，拉取一次 session 的完整 tracing 对象，抽取用户问题、智能体最终回答、完整 observations、scores、trace tree、中间工具调用，并按租约/幂等规则写入 K-ACP 数据库。用于定时总结新的 Langfuse/K-ACP session、排查对话链路、构建 session tracing envelope、或手动指定 sessionId 做完整追踪分析；不修改服务代码。
---

# Langfuse Session Tracing

本 skill 的目标是让智能体在不知道 `sessionId` 的情况下，自动发现新的 Langfuse session，拉完整 tracing 数据，总结后写入 K-ACP 数据库。工作流必须同时支持两种模式：

- `AUTO`：`sessionId` 为空，定时发现并抢占一个新的成熟 session。
- `SESSION`：显式传入 `sessionId`，手动重跑指定 session。

## 生产约束

- 使用 Langfuse 只读公共 API；不要修改 Langfuse 或 K-ACP 服务代码。
- 不使用 legacy `/api/public/sessions`、`/api/public/traces` 作为主路径；Langfuse v4 `events_only` 下这些端点可能返回 404。
- 不要把 `Authorization` header、`LANGFUSE_SECRET_KEY` 或 Basic token 写入日志、文件或最终回复。
- 读取 observations 时必须传 `fields=core,basic,time,io,metadata,model,usage,prompt,metrics,trace_context`，否则无法稳定抽取用户对话。
- 不要只依赖 `GET /api/public/v2/observations?sessionId=...` 作为完整 session；必须再按每个 `traceId` 拉全量 observations，因为 K-ACP/AgentScope 的子 observation 顶层 `sessionId` 可能为空。
- 自动模式每次默认只处理 1 个 session，避免定时任务重叠和 LLM 成本失控。
- 自动发现只处理真实用户对话 session：候选 observations 必须带 `attributes.user.id` 或 `attributes.langfuse.user.id`，并且必须排除 `workflow-agent-*`、trace 分析工作流、自身总结工作流等系统生成 session，避免工作流递归总结自己的 trace。

## 配置

按以下优先级读取：

1. 工作流/工具入参。
2. Runtime 环境变量。
3. K-ACP 本地配置 `/Users/rocket/kingsware/k-acp/docker/.env.kacp`（只按 `KEY=VALUE` 文本解析，不要 `source`）。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `langfuseBaseUrl` / `LANGFUSE_BASE_URL` | `http://langfuse-web:3000` | K-ACP 与 Langfuse 在同一 Docker 网络时使用内部服务名 |
| `LANGFUSE_PUBLIC_KEY` | 从运行环境读取 | HTTP Basic 用户名 |
| `LANGFUSE_SECRET_KEY` | 从运行环境读取 | HTTP Basic 密码 |
| `limit` | `100` | Langfuse 单页大小；本地 v4 建议不超过 100 |
| `maxPages` | `100` | 单次完整 session 拉取的分页上限 |
| `lookbackMinutes` | `1440` | 自动发现回看窗口；必须重叠以防漏处理 |
| `cooldownSeconds` | `180` | session 最后一条 observation 距今超过该值才处理，避免总结仍在进行中的对话 |
| `leaseSeconds` | `900` | 抢占租约时长；工作流异常退出后可自动重试 |
| `maxDiscoverPages` | `20` | 自动发现阶段最多扫描 observation 页数 |
| `maxAttempts` | `5` | 单个 session 自动处理最大重试次数 |
| `tenantId` | `1` | K-ACP 租户 ID |

认证方式：

```text
Authorization: Basic base64("${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}")
```

## Langfuse 接口

### Health

```http
GET {langfuseBaseUrl}/api/public/health
```

成功响应：

```json
{
  "status": "OK",
  "version": "4.6.0"
}
```

### 自动发现候选 session

```http
GET {langfuseBaseUrl}/api/public/v2/observations
```

Query：

```json
{
  "fromStartTime": "2026-08-10T16:00:00.000Z",
  "limit": 100,
  "cursor": "<meta.cursor>",
  "fields": "core,basic,time,metadata,trace_context"
}
```

处理规则：

1. 从顶层 `sessionId` 提取 session；为空时 fallback 到 `metadata["attributes.session.id"]` 和 `metadata["attributes.langfuse.session.id"]`。
2. 丢弃空 session。
3. 仅保留真实用户对话 session：至少一个 observation metadata 中存在 `attributes.user.id` 或 `attributes.langfuse.user.id`。
4. 排除系统/工作流自循环 session：任一 observation 的 `attributes.gen_ai.agent.name` 或 `name` 命中 `workflow-agent-*` 时，不写入租约表。
5. 按 session 聚合 `projectId`、`firstSeenAt`、`lastSeenAt`、`observationCount`。
6. `lastSeenAt` 必须早于 `now - cooldownSeconds` 才能进入抢占队列。
7. 扫描窗口必须与调度频率重叠；不要只查“上次运行之后”的窗口。
8. 输出 discovery 统计时包含 `skippedNonUserSessionCount`，用于排查新 trace 明明出现但未进入处理队列的情况。

### Seed observations：按 session 找 traceIds

```http
GET {langfuseBaseUrl}/api/public/v2/observations
```

Query：

```json
{
  "sessionId": "2086646039368425474",
  "limit": 100,
  "cursor": "<meta.cursor>",
  "fields": "core,basic,time,io,metadata,model,usage,prompt,metrics,trace_context",
  "fromStartTime": "2026-08-10T00:00:00.000Z",
  "toStartTime": "2026-08-11T00:00:00.000Z"
}
```

响应形态：

```json
{
  "data": [
    {
      "id": "ee7fc602e1e5a3ec",
      "traceId": "ef22d7113e6621d61d4eeabd9c4e7cfa",
      "projectId": "prj_k_acp_local",
      "parentObservationId": null,
      "type": "AGENT",
      "sessionId": "2086646039368425474",
      "input": "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"...\"}]}]",
      "output": "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"...\"}]}]",
      "metadata": {
        "attributes.session.id": "2086646039368425474",
        "attributes.langfuse.session.id": "2086646039368425474",
        "attributes.agentscope.function.input": {
          "msgs": []
        },
        "attributes.agentscope.function.output": {}
      }
    }
  ],
  "meta": {
    "limit": 100,
    "cursor": "base64-next-cursor"
  }
}
```

### Full observations：按 traceId 补全 trace

对 seed 中每个唯一 `traceId` 分页查询：

```json
{
  "traceId": "ef22d7113e6621d61d4eeabd9c4e7cfa",
  "limit": 100,
  "cursor": "<meta.cursor>",
  "fields": "core,basic,time,io,metadata,model,usage,prompt,metrics,trace_context"
}
```

按 `observation.id` 去重，保留字段更完整的对象。该步骤用于补齐顶层 `sessionId` 为空但同 trace 下属于本 session 的 `GENERATION`、`TOOL`、`SPAN` 等中间调用。

### Scores

先按 session 拉取，再按 traceId 补充：

```json
{
  "sessionId": "2086646039368425474",
  "limit": 100
}
```

```json
{
  "traceId": "ef22d7113e6621d61d4eeabd9c4e7cfa",
  "limit": 100
}
```

`traceId`、`sessionId`、`experimentId` 在 Scores API v3 中互斥；不要放在同一个请求里。

## 数据库契约

### 租约表

工作流必须使用独立租约表，防止漏处理和重复处理：

```sql
CREATE TABLE IF NOT EXISTS langfuse_session_trace_cursor (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  project_id VARCHAR(128) NULL,
  first_seen_at VARCHAR(64) NULL,
  last_seen_at VARCHAR(64) NULL,
  observation_count INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'DISCOVERED',
  attempt_count INT NOT NULL DEFAULT 0,
  leased_until DATETIME(3) NULL,
  lease_owner VARCHAR(128) NULL,
  last_error TEXT NULL,
  discovered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processed_at DATETIME(3) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_langfuse_session_trace_cursor_tenant_session (tenant_id, session_id),
  KEY idx_langfuse_session_trace_cursor_status (tenant_id, status, leased_until),
  KEY idx_langfuse_session_trace_cursor_last_seen (tenant_id, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

状态流转：

```text
DISCOVERED -> PROCESSING -> COMPLETE
DISCOVERED -> PROCESSING -> FAILED -> DISCOVERED/PROCESSING
PROCESSING + leased_until < NOW(3) -> 可重新抢占
```

### 结果表

结果表按 `(tenant_id, session_id)` 唯一键 upsert：

```text
langfuse_session_tracing
```

必须保存：

- `session_id`、`project_id`、`status`
- `trace_count`、`seed_observation_count`、`full_observation_count`、`score_count`、`qa_pair_count`
- `type_counts_json`、`qa_pairs_json`、`conversation_json`
- `envelope_json`：完整原始 tracing envelope
- `llm_analysis_json`：简化后的多轮问答结果，只包含 `sessionId` 和 `turns[]`，每轮保留 `turn`、`userQuestion`、`agentAnswer`、`userTimestamp`、`agentTimestamp`；不要写完整性评估、风险分析、trace 计数或下一步建议
- `warnings_json`、`source_hash`、`processed_at`

`llm_analysis_json` 标准格式：

```json
{
  "sessionId": "2086980254552993793",
  "turns": [
    {
      "turn": 1,
      "userQuestion": "你好",
      "agentAnswer": "你好，请问想查什么数据？",
      "userTimestamp": "2026-08-11 08:57:21.710",
      "agentTimestamp": "2026-08-11 08:57:23.757"
    }
  ]
}
```

多轮对话必须按发生顺序写入 `turns`，一轮只放用户问题和 agent 最终回答。完整 observations、scores、trace tree、计数、风险、完整性判断继续保存在 `envelope_json`、`qa_pairs_json`、`conversation_json`、`warnings_json`，不要塞进 `llm_analysis_json`。

## 自动处理流程

1. 解析配置和鉴权；调用 health，失败则返回 `ERROR`，不要入结果表。
2. `sessionId` 为空时进入 `AUTO` 模式：扫描最近 `lookbackMinutes` observations。
3. 聚合候选 session，仅将真实用户对话 session 写入/刷新租约表的 `DISCOVERED` 行；系统工作流 trace 只计入跳过统计，不入队。
4. 按 `last_seen_at ASC` 抢占一个满足条件的 session：
   - 已静默超过 `cooldownSeconds`。
   - 结果表不存在同 tenant/session。
   - 状态为 `DISCOVERED` / `FAILED` / 租约过期的 `PROCESSING`。
   - `attempt_count < maxAttempts`。
5. 抢占成功后设置 `status='PROCESSING'`、`attempt_count += 1`、`leased_until=NOW()+leaseSeconds`。
6. 拉完整 session tracing：seed observations -> traceId expansion -> scores -> 去重 -> QA 抽取。
7. 基于 `qaPairsJson` 确定性生成简洁 `llm_analysis_json`，不要把完整 observations、完整性评估、风险分析或 trace 计数塞进该字段。
8. 写入 `langfuse_session_tracing`。
9. 入库成功后把租约行标记为 `COMPLETE`，清空 `leased_until` 和 `last_error`。
10. 如果没有候选，返回 `status="NO_SESSION"` 和 `noSessionAvailable=true`，工作流直接结束，不调用 LLM，不写结果表。

## 工作流频率

- 默认 cron：`0 */1 * * * ?`（每分钟）。
- 每次只处理一个 session；吞吐不足时优先缩短 cooldown 或增加并行工作流实例，但必须保留租约抢占。
- `lookbackMinutes` 默认 24 小时，必须远大于调度间隔，避免任务短暂失败或服务重启造成漏处理。
- `cooldownSeconds` 默认 180 秒；如果 K-ACP 对话可能持续更久，调大到 300-600 秒。

## 输出格式

工具输出必须是 JSON object：

```json
{
  "success": true,
  "status": "COMPLETE",
  "noSessionAvailable": false,
  "skipPersistence": false,
  "autoDiscovered": true,
  "sessionId": "2086646039368425474",
  "projectId": "prj_k_acp_local",
  "langfuseBaseUrl": "http://langfuse-web:3000",
  "method": "v2_observations_session_seed_then_trace_expansion_plus_v3_scores",
  "seedObservationCount": 6,
  "fullObservationCount": 16,
  "traceCount": 2,
  "scoreCount": 0,
  "qaPairCount": 2,
  "qaPairs": [
    {
      "traceId": "trace-id",
      "agentObservationId": "observation-id",
      "userQuestion": "用户问题",
      "finalAnswer": "智能体最终回答"
    }
  ],
  "llmInputJson": "{\"sessionId\":\"...\"}",
  "envelopeJson": "{\"observations\":[...]}",
  "sourceHash": "sha256"
}
```

无新 session：

```json
{
  "success": true,
  "status": "NO_SESSION",
  "noSessionAvailable": true,
  "skipPersistence": true,
  "message": "No eligible Langfuse session found in the discovery window."
}
```

## 对话抽取规则

只把根级智能体 observation 作为“用户问题 -> 智能体最终回答”的候选，不要把中间 tool call、LLM generation 或 SQL 参数当成最终回答。

根级智能体候选：

- `type == "AGENT"`。
- `parentObservationId` 为空。
- 如果根级为空，再 fallback 到所有 `AGENT` observation。

用户问题来源优先级：

1. `metadata["attributes.agentscope.function.input"].msgs[]` 中 `role` 包含 `USER` 的消息。
2. 解析 `observation.input` 中 `role` 包含 `USER` 的消息。
3. 其他 input JSON/text 作为 fallback。

最终回答来源优先级：

1. `metadata["attributes.agentscope.function.output"]`。
2. 解析 `observation.output` 中 `role` 包含 `ASSISTANT` 的消息。
3. 其他 output JSON/text 作为 fallback。

## 异常处理

- HTTP 401/403：返回 `ERROR`，停止；检查 public/secret key。
- HTTP 404：确认端点是否使用 `/api/public/v2/observations` 或 `/api/public/v3/scores`。
- HTTP 429/5xx/网络超时：当前 session 标记 `FAILED`，清空租约，等待下次调度重试。
- 分页达到 `maxPages`：返回 `PARTIAL`，在 `warnings` 写入 `pagination_truncated`。
- 自动发现无候选：返回 `NO_SESSION`，不入库。
- 工具或 DB 入库失败：租约保持 `PROCESSING` 直到过期；下次可重新抢占，不应写 `COMPLETE`。
- 结果表重复：依赖唯一键 upsert；重复运行只更新同一行，不新增重复数据。
- session 仍在活跃：不抢占，等待 `cooldownSeconds` 后再处理。

## 验收清单

- `sessionId` 为空时，工作流能自动发现候选 session。
- 无候选时，工作流返回 `NO_SESSION`，不调用 LLM，不写 `langfuse_session_tracing`。
- 有候选时，租约表出现 `PROCESSING`，成功入库后变为 `COMPLETE`。
- 同一个 session 重跑不会新增重复结果行。
- 结果表中能看到用户问题、智能体最终回答、完整 envelope；`llm_analysis_json` 必须是简洁多轮问答 JSON，不包含完整性/风险/计数字段。
- `warnings_json` 明确记录分页截断、部分失败或 fallback 行为。

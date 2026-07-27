# Commercial Tender High-Recall Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver and activate an application-layer high-recall tender-search workflow for `default-tender` that preserves hard filters, executes controlled multi-round retrieval, returns stable A/B/C relevance tiers, and forces verified source-link resolution for each displayed batch.

**Architecture:** A new agent-only Skill produces and governs a versioned `QueryPlan`; a published K-ACP workflow uses restricted Agent nodes plus deterministic Java CODE nodes to execute, merge, deduplicate, rank, slice and continue results. A workflow-only custom tool `resolve_tender_source_urls_v2` resolves original links by `record_key`, validates identity and reachability, and falls back to validated 知了 aggregation pages. Deployment is an idempotent application-configuration package that creates new rows and changes only the `default-tender` prompt and bindings.

**Tech Stack:** K-ACP workflow JSON, K-ACP Skill packages, K-ACP dynamic Java tools, MySQL application configuration, Java 21 HTTP client/Jackson, shell-based local deployment checks.

## Global Constraints

- Accuracy and coverage take priority over response speed; normal searches use two to four controlled rounds.
- Main results contain A strict and B high-relevance records; C possible-relevance records are isolated in a collapsed section.
- Do not modify Java backend source, database schema, the external tender API, shared `tender-search`, the old `resolve_tender_source_urls`, model configuration, or unrelated agents.
- Keep `deepseek-v4-flash`, `context_window=40960`, `max_tokens=20480`, streaming, thinking, temperature and current agent model settings unchanged.
- The answer model receives only the current projected display batch, never raw pages, full result bodies or source HTML.
- Each visible batch contains at most 20 records; this limit must not truncate total retrieval.
- All hard filters are copied unchanged into every search round.
- Link results are merged by `record_key`, never by array position.
- Original-link regression targets are zero wrong original links and zero record-key mapping errors.

## File Map

- Create `.codex/skills/tender-high-recall-search/SKILL.md`: routing, invariants and reference selection.
- Create `.codex/skills/tender-high-recall-search/references/query-plan.md`: exact `QueryPlan` contract and follow-up merge rules.
- Create `.codex/skills/tender-high-recall-search/references/retrieval-ranking.md`: round construction, pagination, dedupe, A/B/C and correction rules.
- Create `.codex/skills/tender-high-recall-search/references/output-continuation.md`: display projection, link state and continuation contract.
- Create `docs/operations/commercial-tender-high-recall/TenderSourceUrlResolverV2Tool.java`: dedicated dynamic-tool implementation.
- Create `docs/operations/commercial-tender-high-recall/fixtures/resolver-cases.json`: deterministic extraction, status and mapping cases.
- Create `docs/operations/commercial-tender-high-recall/workflow.json`: workflow definition with forced resolver node.
- Create `docs/operations/commercial-tender-high-recall/prompt.md`: replacement prompt for `default-tender` only.
- Create `docs/operations/commercial-tender-high-recall/apply-local.sh`: idempotent application-config install and publish.
- Create `docs/operations/commercial-tender-high-recall/verify-local.sh`: database, compile, workflow and isolation assertions.
- Create `docs/operations/commercial-tender-high-recall/rollback-local.sh`: restore captured prompt/bindings and remove only new resources.
- Create `docs/operations/commercial-tender-high-recall/README.md`: operator instructions and acceptance criteria.

---

### Task 1: High-recall Skill contract

**Files:**
- Create: `.codex/skills/tender-high-recall-search/SKILL.md`
- Create: `.codex/skills/tender-high-recall-search/references/query-plan.md`
- Create: `.codex/skills/tender-high-recall-search/references/retrieval-ranking.md`
- Create: `.codex/skills/tender-high-recall-search/references/output-continuation.md`

**Interfaces:**
- Consumes: user `question`, optional `priorState`, optional `companyProfile`, and API semantics from unchanged `tender-search`.
- Produces: `QueryPlanV1`, round requests, normalized `TenderRecordV1[]`, and `ContinuationStateV1` as defined in the references.

- [ ] **Step 1: Write contract assertions**

Run:

```bash
rtk rg -n 'query_plan_version|hard_filters|concept_groups|A 严格匹配|B 高相关|C 可能相关|continuationState|record_key' .codex/skills/tender-high-recall-search
```

Expected before creation: no files or no complete set of required contract terms.

- [ ] **Step 2: Write the Skill and three focused references**

The Skill frontmatter must be:

```yaml
---
name: tender-high-recall-search
description: 商业标书智能体专用的高召回标讯检索规划与结果治理技能。普通招中标搜索、市场扫描、继续查看、修改地区/阶段/排除词时使用；不用于单项目深度分析。
---
```

The references must define these exact top-level fields:

```json
{
  "query_plan_version": "tender-query-plan-v1",
  "original_question": "string",
  "subject": "string",
  "hard_filters": {},
  "concept_groups": [],
  "exclude_keywords": [],
  "inherited_fields": [],
  "assumptions": [],
  "correction_allowed": true
}
```

They must also require `bid_id -> uniq_key -> aggregate_url -> fingerprint` same-notice dedupe, separate lifecycle grouping, 100-page safety limits, `is_complete=false` on any failed or capped range, and stable ordering A then B then C.

- [ ] **Step 3: Validate the Skill structure**

Run:

```bash
rtk find .codex/skills/tender-high-recall-search -maxdepth 3 -type f
rtk rg -n 'API Key|X-API-Key|mcp-server.zhiliaobiaoxun.com' .codex/skills/tender-high-recall-search
```

Expected: four files; the second command returns no matches because authentication and endpoint details remain in shared `tender-search`.

### Task 2: Dedicated source-link resolver v2

**Files:**
- Create: `docs/operations/commercial-tender-high-recall/TenderSourceUrlResolverV2Tool.java`
- Create: `docs/operations/commercial-tender-high-recall/fixtures/resolver-cases.json`

**Interfaces:**
- Consumes: `{ "items": [{ "record_key", "bid_id", "uniq_key", "title", "aggregate_url", "source_url" }] }`, 1 to 20 items.
- Produces: `{ "success", "total", "resolved", "link_resolution_complete", "items": [{ "record_key", "original_url", "aggregate_url", "display_url", "link_type", "source_status", "source_domain", "resolve_method", "status_reason" }] }`.

- [ ] **Step 1: Add failing resolver fixtures**

The fixture set must include explicit source URL, Unicode-escaped `sourceUrl`, snake-case `source_url`, nested detail field, deleted source, auth page, rate limit, temporary failure, invalid/private target, valid aggregate fallback, duplicate/missing `record_key`, and reordered output mapping.

- [ ] **Step 2: Implement the resolver**

The class must implement:

```java
public final class TenderSourceUrlResolverV2Tool implements IDynamicAgentTool {
    @Override
    public Object execute(AgentContext context, Map<String, Object> params);
}
```

Implementation requirements are exact: maximum 20 inputs; unique nonblank `record_key`; recursive controlled-field extraction; SSR/structured-state extraction; JSON Unicode and HTML entity decoding; public HTTP/HTTPS only; five redirects maximum with DNS validation after every hop; HEAD followed by bounded GET for 400/401/403/405/406/429/5xx; 404/410 deleted, 401/403 auth-required, 429 rate-limited, timeout/DNS/5xx temporary-unreachable; explicit source/detail candidates may verify after error-page rejection; page-extracted candidates require exact project/purchase identifier or title similarity at least `0.72`; aggregate fallback is accepted only for a valid 知了 `/content/` page; merge output by key.

- [ ] **Step 3: Compile against the running K-ACP runtime classpath**

Run:

```bash
rtk test docs/operations/commercial-tender-high-recall/verify-local.sh --compile-only
```

Expected: `PASS resolver-v2 compile`.

### Task 3: Workflow definition and deterministic projection

**Files:**
- Create: `docs/operations/commercial-tender-high-recall/workflow.json`

**Interfaces:**
- Consumes workflow params `question`, `priorState`, `companyProfile`.
- Produces `answer`, `queryPlan`, `resultStatus`, `continuationState`, `metrics`.

- [ ] **Step 1: Write structural workflow assertions**

The verifier must assert one START and END, query-planner AGENT with structured output, plan-validation CODE, three restricted search AGENT nodes, deterministic normalization CODE, correction routing, display slicing CODE, exactly one resolver TOOL_EXECUTE node using `resolve_tender_source_urls_v2`, key-based link merge CODE, continuation CODE, and answer AGENT.

- [ ] **Step 2: Write the workflow JSON**

Every search node must bind only `tender-search` and `http_request`, receive a deterministic request object, preserve hard filters, use `page_size=50`, stop at the declared total/no next page/page 100, and return only normalized JSON without fulltext. The normalization node must emit stable `record_key`, `lifecycle_key`, `tier`, `match_evidence`, `sort_key`, `needs_correction`, completion metrics and link input projection. The answer node must receive only the current maximum-20 display records and resolved links.

- [ ] **Step 3: Validate graph references locally**

Run:

```bash
rtk test docs/operations/commercial-tender-high-recall/verify-local.sh --workflow-only
```

Expected: `PASS workflow graph`, `PASS forced resolver`, `PASS answer context isolation`.

### Task 4: Idempotent application-layer deployment

**Files:**
- Create: `docs/operations/commercial-tender-high-recall/prompt.md`
- Create: `docs/operations/commercial-tender-high-recall/apply-local.sh`
- Create: `docs/operations/commercial-tender-high-recall/rollback-local.sh`
- Create: `docs/operations/commercial-tender-high-recall/README.md`

**Interfaces:**
- Consumes: running `k-acp-mysql`, existing agent code `default-tender`, existing model ID, `tender-search` Skill ID and `http_request` tool ID.
- Produces: one new Skill package, one new custom tool, one published workflow, one workflow binding, one Skill binding and one dedicated prompt binding for `default-tender`.

- [ ] **Step 1: Capture before-state and collision checks**

The apply script must abort unless exactly one enabled `default-tender`, `tender-search`, `http_request` and target model exist; the new IDs/names must be absent or already owned by this package. Before mutation it must export the agent prompt-template ID and existing bindings to a local ignored JSON snapshot consumed by the rollback script; it must not create or alter a database table.

- [ ] **Step 2: Upsert application resources**

Use fixed package identifiers and idempotent `INSERT ... ON DUPLICATE KEY UPDATE`. Insert Java code from the checked-in resolver file as base64-decoded text, Skill files from checked-in Markdown, and workflow config from checked-in JSON. Never include credentials in files or SQL output.

- [ ] **Step 3: Publish and bind**

Create a published workflow version, link it only through `agent_workflows.agent_definition_id` for `default-tender`, bind the new Skill only through `agent_skill_packages`, and bind the new dedicated prompt only by updating that agent's `system_prompt_template_id`. Do not add resolver v2 to shared `skill_tools` or another agent.

- [ ] **Step 4: Provide scoped rollback**

Rollback must restore the captured `default-tender` prompt binding, remove only this package's agent bindings, workflow/version, Skill/files and resolver-v2 tool, and leave old/shared rows untouched.

### Task 5: Live verification and regression evidence

**Files:**
- Create: `docs/operations/commercial-tender-high-recall/verify-local.sh`

**Interfaces:**
- Consumes: the deployed local application configuration.
- Produces: machine-checkable PASS/FAIL output and saved bounded test evidence without secrets.

- [ ] **Step 1: Run pre-deployment static checks**

Run:

```bash
rtk test docs/operations/commercial-tender-high-recall/verify-local.sh --static
```

Expected: resolver compiles, JSON parses, Skill contracts pass, prompt contains workflow routing, no credential patterns are present.

- [ ] **Step 2: Apply the package**

Run:

```bash
rtk proxy bash docs/operations/commercial-tender-high-recall/apply-local.sh
```

Expected: `APPLIED commercial-tender-high-recall v1` with no model updates.

- [ ] **Step 3: Validate published resources and isolation**

Run:

```bash
rtk test docs/operations/commercial-tender-high-recall/verify-local.sh --live
```

Expected: one published workflow and version, correct `default-tender` bindings, dedicated resolver not shared, old resolver checksum unchanged, shared `tender-search` file checksums unchanged, agent model row unchanged.

- [ ] **Step 4: Run functional search cases**

Execute the workflow for these exact cases:

```text
查询广东最近一个月关于服务器采购的招标项目
查询广东最近一个月统一运维全部阶段的项目
只看在招
继续
```

Expected: absolute Beijing dates; hard filters preserved across all rounds; A/B main and C isolated; distinct notice dedupe with lifecycle grouping retained; visible batch at most 20; `continue` advances stable position; `is_complete=false` on any failed/capped page; all visible records contain resolver output keyed by `record_key`; no HTML/fulltext in answer-node inputs.

- [ ] **Step 5: Run source-link regression**

Run at least 200 saved or replayable link cases, including the observed `sourceUrl:"https:\\u002F\\u002F..."` form. Expected: zero wrong `original_url`, zero record-key mapping errors, every non-verified item has a validated aggregate fallback or `link_type=NONE`, and status taxonomy matches the contract.

- [ ] **Step 6: Update Graphify and commit only task files**

Run:

```bash
rtk graphify update .
rtk git status --short
rtk git add docs/superpowers/plans/2026-07-22-commercial-tender-high-recall-search.md .codex/skills/tender-high-recall-search docs/operations/commercial-tender-high-recall
rtk git commit -m "feat: add commercial tender high-recall workflow"
```

Expected: unrelated UI, `.mindfs/`, `.superpowers/` and `tmp/` changes remain unstaged.

## Self-Review

- Spec coverage: QueryPlan, hard-filter inheritance, three base rounds, conditional correction, pagination caps, dedupe, lifecycle grouping, A/B/C, stable continuation, forced source-link resolution, context isolation, deployment isolation and regression targets each map to Tasks 1-5.
- Placeholder scan: no `TBD`, `TODO`, “implement later”, generic error-handling instruction, or undefined “similar to” step is present.
- Type consistency: `QueryPlanV1`, `TenderRecordV1`, `ContinuationStateV1`, `record_key`, resolver input/output fields and workflow inputs/outputs are named consistently across all tasks.

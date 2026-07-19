# Tender Original Source URL Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a live K-ACP custom Tool that resolves at most 20 tender aggregation URLs to verified original-source URLs, then require the commercial tender agent to use it before rendering search results.

**Architecture:** Store the implementation only in `tool_config` as a `CUSTOM/JAVA` Tool; do not modify Java, Vue, build, or deployment source files. Bind the Tool to the existing commercial tender agent and `tender-search` skill, then update the live skill and prompt configuration so each search page contains at most 20 records and resolves links in one batch.

**Tech Stack:** K-ACP dynamic Java Tool, JDK `HttpClient`, Jackson, MySQL configuration tables, Redis agent re-registration.

## Global Constraints

- Do not modify, build, or commit K-ACP application source code.
- Process at most 20 records per batch; “continue” moves to the next search page.
- Prefer a real API `source_url`; parse the aggregation page only when it is absent.
- Never invent an original URL or label an aggregation URL as original.
- Treat HTTP 404/410 from the original site as `SOURCE_DELETED` and stop.
- Preserve the existing model, template binding, skills, tools, memory, and agent settings.
- Make every live configuration change reversible from a pre-change snapshot.

---

### Task 1: Create the disabled custom Tool configuration

**Live configuration:**
- Create: `tool_config` row with `tool_id=resolve_tender_source_urls`
- No repository source files are created or modified.

**Interfaces:**
- Consumes: Tool parameter `items`, an array of 1–20 objects containing `bid_id`, `title`, `url` or `aggregate_url`, and optional `source_url`.
- Produces: `{total, resolved, items[]}` with one ordered result per input and statuses `VERIFIED`, `SOURCE_DELETED`, `NOT_FOUND`, `UNREACHABLE`, or `INVALID_INPUT`.

- [ ] **Step 1: Capture a reversible snapshot**

Run read-only SQL for the target template, skill file, agent-tool binding, skill-tool binding, and any pre-existing Tool row. Store the result outside the repository in the current task transcript; do not print secrets.

- [ ] **Step 2: Verify the precondition**

Run:

```sql
SELECT COUNT(*)
FROM tool_config
WHERE tenant_id = 1 AND tool_id = 'resolve_tender_source_urls';
```

Expected: `0`. Any non-zero result stops the task for an update/rollback decision.

- [ ] **Step 3: Insert the Tool disabled**

Insert one `CUSTOM`, `JAVA`, `TENANT` row with:

```json
[
  {
    "name": "items",
    "type": "array",
    "description": "最多20条标讯记录。每项包含 bid_id、title、url或aggregate_url，可选source_url",
    "required": true,
    "defaultValue": "[]"
  }
]
```

The stored Java class must implement `IDynamicAgentTool` and provide these exact behaviors:

```java
public Object execute(AgentContext context, Map<String, Object> params)
```

1. Convert `params.get("items")` through Jackson into an array and reject empty or more than 20 entries.
2. Use an executor with exactly 5 workers and preserve result order through indexed futures.
3. If `source_url` is non-empty, validate it and skip aggregation-page parsing.
4. Otherwise accept only `https://www.zhiliaobiaoxun.com/content/` aggregation URLs, download no more than 2 MiB, and extract `sourceUrl:"..."`.
5. Decode the extracted JSON string with Jackson so `\u002F` becomes `/`.
6. Accept only public `http`/`https` destinations. Reject loopback, link-local, private, multicast, carrier-grade NAT, benchmark, and documentation ranges; apply the same check to every redirect.
7. Probe the original URL with `HEAD`; retry with a range `GET` when `HEAD` returns 405. Follow at most five validated redirects.
8. Map 2xx/3xx to `VERIFIED`, 404/410 to `SOURCE_DELETED`, absent `sourceUrl` to `NOT_FOUND`, timeout/401/403/429/5xx to `UNREACHABLE`, and invalid input to `INVALID_INPUT`.
9. Include `original_url` only when actually extracted or supplied; never replace it with the aggregation URL.
10. Return aggregate counts plus exactly one result for every input.

Insert with `enabled=0` so no agent can call it before binding and verification.

- [ ] **Step 4: Verify persisted metadata**

Run a read-only query confirming one disabled row, `CUSTOM/JAVA`, version `1.0.0`, tenant `1`, array input schema, and non-empty code.

### Task 2: Bind and activate the Tool without changing existing bindings

**Live configuration:**
- Modify: `agent_tools`
- Modify: `skill_tools`
- Modify: the new `tool_config` row only

**Interfaces:**
- Consumes: the Tool ID created in Task 1.
- Produces: Tool availability in agent `2078675601634549762` and skill `2078674849222549505`.

- [ ] **Step 1: Add idempotent bindings**

Within one transaction, insert an `agent_tools` row for agent `2078675601634549762` and a `skill_tools` row for skill `2078674849222549505` only when each relation does not already exist. Preserve the current `http_request` and `get_current_datetime` relations.

- [ ] **Step 2: Enable and re-register**

Set only the new Tool to `enabled=1`, then publish agent ID `2078675601634549762` to Redis channel `apboa:agent:cluster:reRegister`.

- [ ] **Step 3: Verify runtime registration**

Start a fresh agent session and confirm runtime logs contain:

```text
Registered tool 'resolve_tender_source_urls'
```

If compilation or registration fails, immediately disable the Tool, remove only its two new bindings, re-register the agent, and keep the pre-existing state untouched.

### Task 3: Update live prompt and skill configuration

**Live configuration:**
- Modify: `system_prompt_template.content` for template `2078740664437366785`
- Modify: `skill_file.content` for `tender-search` `SKILL.md` (`skill_id=2078674849222549505`)

**Interfaces:**
- Consumes: `resolve_tender_source_urls` output.
- Produces: one mandatory batch call before displaying up to 20 search records.

- [ ] **Step 1: Change the template record limit surgically**

Replace only the existing 30/50 rule with:

```markdown
- 每次最多展示 20 条记录。结果超过 20 条时说明总数、已展示数和剩余数；用户说“继续”时从下一页续发，每批仍最多 20 条，不静默截断。
```

- [ ] **Step 2: Replace the skill link-output rules**

Update the `tender-search` output section to require `page_size: 20` and one batch call to `resolve_tender_source_urls` before rendering. Use `original_url` only for `VERIFIED`; render plain project text plus the returned status for all other states. Do not expose Tool input, HTML, or aggregation URLs as original links.

- [ ] **Step 3: Synchronize and re-register**

Update the two live records, publish the agent re-registration message, and verify the loaded AgentScope `agent_meta.systemPrompt` and `tender-search` skill content include the new 20-record and resolver rules.

### Task 4: Live acceptance and rollback check

**Live state only:** No repository files.

- [ ] **Step 1: Verify a known original-source sample**

Input bid `599180408` with its known aggregation URL. Expected result:

```json
{
  "status": "VERIFIED",
  "source_domain": "ecsg.com.cn",
  "original_url": "https://ecsg.com.cn/cms/NoticeDetail.html?objectId=936E72F73D4746EBA765B98C7263BAB5&objectType=1&typeid=4"
}
```

- [ ] **Step 2: Verify batch invariants**

Run a 20-item request containing duplicates and one invalid URL. Expected: 20 ordered output items, duplicate downloads deduplicated within the invocation, invalid input isolated to one item, and no whole-batch failure.

- [ ] **Step 3: Verify an end-to-end search**

Ask the commercial tender agent for a real tender search. Expected: at most 20 table rows, each row processed by the Tool, verified project names linked to non-zhiliaobiaoxun original domains, and no aggregation URL presented as original.

- [ ] **Step 4: Verify continuation**

When total results exceed 20, send “继续”. Expected: the next search page is used, no duplicate first-page rows appear, and no more than 20 new rows are resolved.

- [ ] **Step 5: Verify rollback material**

Confirm the captured snapshot contains enough data to remove the new Tool and bindings and restore the exact prior template and skill content. Do not perform rollback when acceptance passes.

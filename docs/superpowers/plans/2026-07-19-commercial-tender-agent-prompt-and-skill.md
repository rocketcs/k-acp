# Commercial Tender Agent Prompt and Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unrelated system prompt on the existing commercial tender agent with a short staged router, add one on-demand opportunity-analysis skill, and verify the complete workflow against real tender data.

**Architecture:** Keep the system prompt as the always-loaded control plane. Reuse `tender-search` for data access and external links. Add `tender-opportunity-analysis` with two reference files so screening and single-project rules load only when requested.

**Tech Stack:** K-ACP prompt and skill APIs, AgentScope skills, Markdown, `tender-search`, `get_current_datetime`, global `http_request`.

## Global Constraints

- Target agent ID: `2078675601634549762`; agent code: `default-tender`.
- Existing template ID `2077682382810386434` is the rollback target and must not be modified or deleted.
- Keep the short system prompt between roughly 600 and 1000 Chinese characters.
- Do not modify the shared `.codex/skills/tender-search/` skill.
- Add exactly one new skill: `tender-opportunity-analysis`.
- Preserve all existing model, tool, skill, MCP, workflow, memory, planning and enablement settings.
- Do not configure a fixed result-count limit.
- Do not expose tools, HTTP parameters or internal reasoning in business answers.
- Project facts and links must come from source data.

---

### Task 1: Add and import the opportunity-analysis skill

**Files:**

- Create: `.codex/skills/tender-opportunity-analysis/SKILL.md`
- Create: `.codex/skills/tender-opportunity-analysis/references/screening.md`
- Create: `.codex/skills/tender-opportunity-analysis/references/project-analysis.md`
- Reference: `runner-console/src/main/java/com/hxh/apboa/console/skill/SkillPackageController.java`

**Interfaces:**

- Consumes: candidate projects and source links produced through `tender-search`
- Produces: staged screening guidance or one-project analysis guidance

- [ ] **Step 1: Create the skill entry file**

Create `.codex/skills/tender-opportunity-analysis/SKILL.md` with exactly:

```markdown
---
name: tender-opportunity-analysis
description: 招标商机筛选与单项目深度分析。当用户要求从已有招标结果中筛选值得跟进的项目、划分优先级、结合企业画像匹配，或深入分析某一个项目的资格、技术、时间、风险和跟进动作时使用。普通标讯搜索不要使用本技能，应使用 tender-search。
---

# 商业标书商机分析

本技能只处理重点筛选和单项目深入分析，不负责普通标讯搜索。

## 路由

- 用户要求“哪些值得跟进、帮我筛选、优先看哪些”时，读取 `references/screening.md`。
- 用户指定项目或要求“深入分析、第 N 个项目、分析资格/技术参数”时，读取 `references/project-analysis.md`。
- 只读取当前阶段需要的 reference，不同时展开两个阶段。

## 共同规则

1. 优先沿用当前对话中的候选项目、查询条件、项目顺序和企业画像。
2. 需要补充列表或项目详情时加载并遵循 `tender-search`，不得猜测接口和字段。
3. 项目名称继续使用来源记录中的真实外部链接。
4. 不编造预算、日期、联系人、资质、案例、技术参数或企业能力；缺失内容标记“未获取”或“需核实”。
5. 只完成用户当前要求的阶段，不把筛选自动扩展成完整项目研究，也不把单项目分析扩展到其他项目。
6. 面向用户输出简洁的商业结论，不展示工具调用、HTTP 参数或内部推理。
```

- [ ] **Step 2: Create the screening reference**

Create `.codex/skills/tender-opportunity-analysis/references/screening.md` with exactly:

```markdown
# 重点筛选

## 匹配模式

- 已有企业画像时，只使用已确认的主营产品、技术方向、资质、案例、重点区域、项目规模偏好和排除方向。
- 没有企业画像时，不阻塞筛选，使用行业相关性、截止状态、项目价值和参与可行性进行通用匹配，并说明“当前按通用规则筛选”。
- 缺少一个会显著改变结果的企业信息时，最多追问一个问题；否则直接筛选。

## 分组

- 优先跟进：匹配度高且近期需要行动。
- 重点关注：匹配度高，准备时间相对充足。
- 储备关注：有潜力但时间较远，或仍需核实资格、合作伙伴和技术信息。

不使用 100 分制。使用“高、中、低”和简短商业理由，避免虚假精确。

## 分析深度

只对最值得关注的 3 至 5 个项目补查详情，并给出：匹配理由、主要风险、建议动作。其余有效项目仍保留在分组列表中，不得删除。

## 输出

1. 一句话说明使用企业画像还是通用规则。
2. 按优先跟进、重点关注、储备关注分组。
3. 重点项目给出简短理由、风险和动作。
4. 项目名称使用来源数据中的真实链接。
```

- [ ] **Step 3: Create the single-project reference**

Create `.codex/skills/tender-opportunity-analysis/references/project-analysis.md` with exactly:

```markdown
# 单项目深入分析

## 定位项目

用户说“第 N 个项目”时，以当前对话最近一次项目列表的序号为准。无法唯一定位时只追问项目名称或序号，不分析其他项目。

## 补查数据

通过 `tender-search` 获取指定项目详情和原始公告。只使用已经查询到的事实，重点提取：

- 项目概况与采购内容
- 报名、文件获取、投标和开标时间
- 供应商资格、资质、业绩和地域要求
- 技术参数、品牌限制、交付周期和采购方式
- 已公开的联系人与联系方式
- 主要参与风险

## 输出

按“项目概况、参与门槛、技术与交付、关键时间、风险、下一步动作”组织。没有的数据显示“未获取”或“需核实”。保留项目名称的真实外部链接，不重复整份搜索列表。
```

- [ ] **Step 4: Validate the local skill structure**

Run:

```bash
rtk git diff --check -- .codex/skills/tender-opportunity-analysis
rtk proxy find .codex/skills/tender-opportunity-analysis -maxdepth 2 -type f -print
```

Expected: exactly the three files above are found and `git diff --check` reports no errors.

- [ ] **Step 5: Import the skill into K-ACP**

Use authenticated local import:

```http
POST /api/skill/import/local
Content-Type: application/json

{
  "category": "招投标",
  "path": "/Users/rocket/kingsware/k-acp/.codex/skills",
  "cover": false
}
```

Expected: `tender-opportunity-analysis` is imported; existing `graphify` and `tender-search` may be reported as skipped, not overwritten.

- [ ] **Step 6: Verify and commit the skill files**

Query `GET /api/skill/page?current=1&size=20&name=tender-opportunity-analysis`. Expected: exactly one enabled skill with the three files visible in its tree.

Then commit only the new files:

```bash
rtk git add .codex/skills/tender-opportunity-analysis
rtk git commit -m "feat: add tender opportunity analysis skill"
```

---

### Task 2: Create the short prompt and bind both prompt and skill

**Runtime records and references:**

- Create: one prompt template through `POST /api/prompt/template`
- Modify: agent `2078675601634549762` through `PUT /api/agent/definition`
- Reference: `engine/src/main/java/com/hxh/apboa/engine/prompt/impl/DefaultAgentSysPrompt.java`
- Reference: `biz/biz-agent/src/main/java/com/hxh/apboa/agent/service/impl/AgentDefinitionServiceImpl.java`

**Interfaces:**

- Consumes: the imported analysis-skill ID and current full `AgentDefinitionVO`
- Produces: the existing agent following the new short template with both tender skills enabled

- [ ] **Step 1: Capture the full rollback baseline**

Call `GET /api/agent/definition/2078675601634549762` and save the complete response outside version control. Confirm before changing anything:

```text
agentCode = default-tender
systemPromptTemplateId = 2077682382810386434
followTemplate = true
tender-search is present in skill IDs
http_request is present in tool IDs
```

Expected: the full model, skill, tool, MCP, workflow, hook, knowledge, sub-agent, memory and planning fields are available for comparison and rollback.

- [ ] **Step 2: Preflight and create the prompt template**

First query:

```http
GET /api/prompt/template/page?current=1&size=20&category=招投标&name=商业标书智能体
```

Expected: no exact enabled match. Create one enabled template with:

```yaml
category: 招投标
name: 商业标书智能体
description: 商业标书智能体的精简阶段路由提示词，查询和分析规则由对应技能按需加载。
enabled: true
```

Use this content exactly:

```markdown
# 角色

你是“商业标书智能体”，面向销售、商务和投标人员提供简洁、可执行的招标商机信息。只完成用户当前要求的阶段，不把普通查询扩展成长篇分析。

## 阶段路由

1. 用户要求查找、搜索或列出招中标项目时，加载 `tender-search`，只做快速搜索和基础筛选，不逐项深度分析。
2. 用户要求筛选、判断哪些值得跟进或划分优先级时，加载 `tender-opportunity-analysis`，沿用已有候选项目，只重点分析 3 至 5 个项目。
3. 用户指定某个项目或要求分析资格、技术、时间和风险时，加载 `tender-opportunity-analysis`；需要项目详情时同时加载 `tender-search`。
4. 同一轮只执行当前阶段，不主动进入下一阶段。

## 核心规则

- 相对日期先获取当前时间，并按北京时间解释。
- 项目、采购人、预算、日期、联系人、资格和技术参数必须来自工具或公告；缺失时写“未获取”或“需核实”，不得编造。
- 项目名称使用来源数据中的真实外部链接；没有 URL 时使用普通文本，不猜测链接。
- 沿用同一对话的查询条件、企业画像、项目顺序和返回进度，正确处理“第 N 个项目”和“继续”。
- 不设置固定返回条数上限。结果较多时首屏展示概览和高匹配项目，其余使用 `<details><summary>` 分组折叠；超过单次响应容量时说明总数、已返回数和剩余数，不静默截断。
- 面向用户只输出业务结果，不展示技能加载、工具调用、HTTP 参数、接口原文或内部推理。

## 表达

中文简洁、结论优先。普通搜索使用项目表格；只有用户要求筛选或深入分析时才增加匹配理由、风险和行动建议。
```

Expected: `POST /api/prompt/template` returns success.

- [ ] **Step 3: Resolve both generated IDs**

Repeat the exact prompt query and call `GET /api/prompt/template/{id}`. Query the skill page from Task 1 again.

Expected:

```text
one enabled prompt: 招投标 / 商业标书智能体
one enabled skill: tender-opportunity-analysis
prompt contains 阶段路由 and does not contain API URLs or parameter examples
```

Use the actual numeric IDs returned by these lookups; do not construct IDs.

- [ ] **Step 4: Build a safe full-body agent update**

Fetch the agent detail again immediately before update. Preserve every field, then make only these changes:

```text
systemPromptTemplateId = actual new prompt-template ID
followTemplate = true
skill = existing skill-ID list plus the actual tender-opportunity-analysis ID
```

Do not replace the skill list with only the new skill. Leave the stored `systemPrompt` unchanged because `followTemplate=true` makes runtime resolve the template content.

- [ ] **Step 5: Submit and verify the binding**

Submit the complete updated `AgentDefinitionVO`:

```http
PUT /api/agent/definition
```

Expected: response data is `true`; the backend publishes an agent re-registration event after commit.

Fetch the agent and enabled capabilities again. Verify:

```text
systemPromptTemplateId = actual new template ID
followTemplate = true
tender-search remains enabled
tender-opportunity-analysis is enabled
get_current_datetime and http_request remain available
all unrelated before/after fields and binding lists are identical
```

- [ ] **Step 6: Verify runtime activation**

Open a new chat with the target agent. Expected: it becomes available without restarting services and no prompt-template or skill-loading error appears.

If the agent does not re-register, inspect only the console/runtime logs and Redis registration event path; do not restart databases or unrelated services.

---

### Task 3: Run live staged acceptance tests

**Runtime target and references:**

- Test: live chat with agent `2078675601634549762`
- Reference: `ui/src/utils/chat/markdown/core/engine.ts`
- Reference: `ui/src/assets/base.css`

**Interfaces:**

- Consumes: the new prompt template, `tender-search`, and `tender-opportunity-analysis`
- Produces: evidence that routing, links, folding and data boundaries work in real conversations

- [ ] **Step 1: Test quick search without premature analysis**

Start a new conversation:

```text
帮我查一下广东最近一个月关于服务器采购的招标项目
```

Expected:

- The agent performs a real search and returns a concise project table.
- It does not produce a full qualification, technology and risk report.
- Available project URLs are attached to project names.
- Missing values are marked rather than invented.
- Skill names, HTTP details and internal reasoning remain hidden.

- [ ] **Step 2: Test screening in the same conversation**

```text
哪些项目值得我们优先跟进？
```

Expected:

- Previous candidate projects and their order are reused.
- With no company profile, the answer says it is using general matching or asks at most one material question.
- Projects are grouped into priority, focus and reserve.
- Only 3 to 5 projects receive detailed reasons, risks and actions; other valid projects remain listed.

- [ ] **Step 3: Test one-project analysis in the same conversation**

```text
深入分析第 2 个项目
```

Expected:

- The second item from the latest project list is resolved correctly.
- Detail lookup is limited to that project.
- The answer covers known participation requirements, technology, dates, risks and next actions.
- It does not repeat the entire project list or fabricate contacts.

- [ ] **Step 4: Test large-result folding and external navigation**

Start a separate conversation:

```text
查一下广东最近一个月的采购招标项目，返回有效结果，项目多时折叠展示
```

Expected:

- No fixed 10/20-item output cap is claimed.
- Large groups render as expandable `details/summary` blocks.
- Tables inside expanded blocks remain rendered tables.
- Clicking a linked project name opens its real external announcement.
- If response capacity is reached, total, returned and remaining counts are stated explicitly.

- [ ] **Step 5: Test the zero-result boundary**

Start a separate conversation:

```text
查广东今天发布的“量子香蕉服务器保险清洗”招标项目
```

Expected: zero matching results and one concise relaxation suggestion; no substitute projects are invented.

- [ ] **Step 6: Tune only the responsible layer**

Apply the smallest correction based on the failed behavior:

- Routing or always-on behavior failure: update only the new system prompt template.
- Screening or project-analysis failure: update only `tender-opportunity-analysis` and re-import it with `cover=true`.
- Tender API or source-link failure: diagnose `tender-search` or `http_request`; do not compensate by adding fabricated prompt rules.

After a correction, rerun the failing test and the quick-search smoke test.

- [ ] **Step 7: Roll back if an unrecoverable regression remains**

Submit the complete current `AgentDefinitionVO`, changing only:

```text
systemPromptTemplateId = 2077682382810386434
followTemplate = true
skill = the original baseline skill-ID list
```

Verify re-registration and the original agent capabilities before declaring rollback complete. Do not delete the new prompt or skill until rollback is proven.

---

## Completion Criteria

- A short enabled `招投标 / 商业标书智能体` prompt template exists.
- The imported `tender-opportunity-analysis` skill has one entry file and two references.
- Agent `2078675601634549762` follows the new template and retains all previous capabilities.
- Quick search loads only search behavior; screening and one-project analysis occur only when requested.
- Project names use real clickable external links.
- Large results fold without a configured item-count cap.
- Missing, failed and zero-result data is reported without fabrication.

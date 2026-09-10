# Commercial Tender Followup Curator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `commercial-tender-followup-curator` the only source of actionable, evidence-grounded tender follow-up cards.

**Architecture:** The high-recall workflow returns factual answer text and continuation state only. The outer tender agent invokes the curator after every substantive business answer. The UI preserves high-recall facts and appends at most one validated UIP card from the outer curator response; it never injects a fixed menu.

**Tech Stack:** K-ACP Skill packages and MySQL-backed agent configuration, Vue 3, TypeScript, Node built-in test runner, Docker Compose.

## Global Constraints

- Scope is `default-tender` only. Do not modify shared tender APIs, Java backend code, database schema, or remote environments.
- Allowed cards: continuation, query refinement, similar search, project detail/feasibility, buyer history, winner analysis, and confirmed-profile screening.
- Never suggest monitoring, notification, export, CRM write, contact outreach, or any other side effect.
- Every option `value` is the complete natural-language request submitted on click; never an English action enum.
- The static `TENDER_FOLLOW_UP_CARD` must be removed. Missing or invalid curator output means no tender card.
- All MySQL commands use `./scripts/with-environment.sh local --require mysql -- ...`; no test or production access.

---

### Task 1: Source high-recall cards only from the curator

**Files:**

- Modify: `ui/src/utils/chat/uip.ts`
- Modify: `ui/src/utils/chat/uip.test.ts`
- Modify: `ui/src/composables/chat/useChatStream.ts`
- Modify: `ui/src/components/markdown/uip/ChoiceRenderer.vue`

**Interfaces:**

- Produces `composeTenderResponse(primaryContent: string, curatorContent: string): string`.
- The primary high-recall answer supplies factual text; the outer agent/curator response supplies the sole UIP card.

- [ ] Add failing tests for three cases: valid curator card appended to factual text; legacy card in the primary answer removed; malformed/absent curator UIP produces no card.

```ts
test('高召回答案只附加策展 Skill 产出的合法卡片', () => {
  const result = composeTenderResponse('已找到 2 条。', `说明\n\`\`\`uip\n${validChoice('tender-followups')}\n\`\`\``)
  assert.equal(result.startsWith('已找到 2 条。'), true)
  assert.equal(normalizeUIPContent(result, 'tenderStrict').validBlocks.length, 1)
})
```

- [ ] Run `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts`; expect the test to fail before the helper exists.
- [ ] Implement `composeTenderResponse`: normalize both strings with `tenderStrict`; strip every UIP block from the primary answer; append a serialized first valid UIP message from curator content; if there is no primary answer return normalized curator content.
- [ ] In `useChatStream.ts`, pass `pendingHighRecallAnswer` and the final outer response to `composeTenderResponse`. Remove `TENDER_FOLLOW_UP_CARD`, `needsTenderFallback`, `runHasTenderFollowups`, `fallbackFollowupSaved`, and every static-card append path. Keep pending tool answer handling and existing UIP normalization.
- [ ] In `ChoiceRenderer.vue`, make auto-submit depend only on `interaction.autoSubmit === true`; do not special-case `tender-followups`.
- [ ] Run `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts && npm run type-check`; expect both commands to exit 0.
- [ ] Commit only the four files: `git add ui/src/utils/chat/uip.ts ui/src/utils/chat/uip.test.ts ui/src/composables/chat/useChatStream.ts ui/src/components/markdown/uip/ChoiceRenderer.vue && git commit -m "feat: source tender followups from curator skill"`.

### Task 2: Add a capability-safe curator Skill source

**Files:**

- Create: `.codex/skills/commercial-tender-followup-curator/SKILL.md`
- Create: `.codex/skills/commercial-tender-followup-curator/references/question-patterns.md`

**Interfaces:**

- Consumes confirmed conversation facts, result state, company profile, and the action whitelist.
- Produces exactly one 1–4 option UIP choice card or no card.

- [ ] Write `SKILL.md` so it runs after substantive search, screening, project, customer, or market results; it must cover decision, customer/competition, opportunity-expansion, and profile-match directions without searching or inventing facts.
- [ ] Require the UIP shell `id: "tender-followups"`, `type: "choice"`, `multiple: false`, `allowCustom: true`, `autoSubmit: true`; each option uses the same complete natural-language request for `value` and `label`.
- [ ] Write `question-patterns.md` with distinct patterns for multi-project search, single project, purchaser insight, screening, market analysis, and weak/zero results. Each pattern must name supported actions and forbid unsupported claims.
- [ ] Run `rg -n '导出|订阅|监控|自动推送|打电话|联系采购方|英文动作' .codex/skills/commercial-tender-followup-curator`; expected output only describes prohibitions, never a recommendation.
- [ ] Commit the new Skill only: `git add .codex/skills/commercial-tender-followup-curator && git commit -m "feat: add capability-safe tender followup curator"`.

### Task 3: Route local prompt, workflow, Skill, and tool configuration through the curator

**Files:**

- Create: `docs/operations/commercial-tender-followup-curator/system-prompt.md`
- Create: `docs/operations/commercial-tender-followup-curator/high-recall-answer-prompt.md`
- Create: `docs/operations/commercial-tender-followup-curator/apply-local.sh`
- Create: `docs/operations/commercial-tender-followup-curator/verify-local.sh`

**Interfaces:**

- Consumes the local `default-tender` agent, its prompt template, curator/high-recall Skill rows, workflow `2079122200000000401`, and high-recall tool configuration.
- Produces a local runtime where the workflow never authors a UIP card and the agent invokes the curator as its final business step.

- [ ] Write `system-prompt.md`: retain high-recall, company/market, screening and project-analysis routing; require the curator after every substantive answer; restrict cards to the action whitelist; treat `已选择：{完整请求}` as the next user request.
- [ ] Write `high-recall-answer-prompt.md`: retain table, source-link, A/B/C, completeness and missing-data rules; explicitly forbid UIP output, option generation and English action values.
- [ ] Implement an idempotent `apply-local.sh`. It must assert that one `default-tender` agent and all target records exist, load the canonical files as base64 SQL values, update the prompt and curator package/files, replace the workflow `answer-generator` `systemPrompt`, remove the high-recall Skill UIP instruction, update the high-recall tool description, and restart only `apboa-console` and `apboa-runtime`.
- [ ] Implement read-only `verify-local.sh`. It must fail unless the agent has the curator enabled, the system prompt names the whitelist, the high-recall answer-generator has no UIP instruction, curator files demand natural-language values, and the tool description assigns cards to the curator.
- [ ] Run `docs/operations/commercial-tender-followup-curator/apply-local.sh && docs/operations/commercial-tender-followup-curator/verify-local.sh`; expected exit code 0 and only local containers restart.
- [ ] Commit the repeatable configuration assets: `git add docs/operations/commercial-tender-followup-curator && git commit -m "feat: route tender followups through curator"`.

### Task 4: Build and test the local tender agent

**Files:**

- Modify only if a verified failing test identifies its responsible component.

- [ ] Run `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts && npm run type-check && npm run build`; expected exit code 0.
- [ ] Rebuild only local frontend: `./scripts/with-environment.sh local --require mysql -- docker compose --project-name k-acp-local --env-file docker/.env.kacp -f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml up -d --build apboa-frontend`.
- [ ] In local UI, verify a multi-project search yields factual results plus one entity-specific card; clicking sends a complete Chinese request; a project-detail response offers analysis/competition/expansion rather than unsupported operations; and a weak/zero query produces only bounded query adjustment.
- [ ] Run `graphify update . && git diff --check && git status --short`; expected current graph, no whitespace errors, and no modification to unrelated user work.

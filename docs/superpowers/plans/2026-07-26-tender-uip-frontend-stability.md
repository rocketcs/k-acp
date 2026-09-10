# Tender UIP Frontend Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure that `default-tender` replies always end with one usable verified UIP card when a business answer exists, while invalid protocol data is never exposed to users.

**Architecture:** Keep all protocol handling in `ui/`. A pure utility validates and normalizes Markdown UIP blocks; `useChatStream` applies `tenderStrict` before saving finished tender messages and appends the existing deterministic fallback card only when no verified card remains. The shared renderer silently suppresses invalid blocks, so historical messages and non-tender messages cannot display raw JSON.

**Tech Stack:** Vue 3 `<script setup>`, TypeScript, Node built-in test runner, Vite, Docker Compose.

## Global Constraints

- Do not modify `engine/`, `workflow/`, database schema, prompts, API contracts, or compose configuration.
- `agentCode === 'default-tender'` is the only trigger for the tender-specific strict policy and fallback card.
- Never repair malformed JSON in the browser; drop it and use the existing deterministic tender fallback where applicable.
- Preserve normal Markdown text and support existing UIP cards from non-tender agents.
- Do not expose raw UIP JSON, parser errors, or a perpetual UIP skeleton after a stream has ended.
- Rebuild only the local `apboa-frontend` service after verification.

---

### Task 1: Complete policy-aware UIP validation and normalization

**Files:**
- Modify: `ui/src/utils/chat/uip.ts`
- Modify: `ui/src/utils/chat/uip.test.ts`

**Interfaces:**
- Produces: `type UIPPolicy = 'default' | 'tenderStrict'`
- Produces: `validateUIP(code: string, policy?: UIPPolicy): UIPValidationResult`
- Produces: `normalizeUIPContent(content: string, policy?: UIPPolicy): NormalizedUIPContent`
- Produces: `needsTenderFallback(normalized: NormalizedUIPContent): boolean`

- [x] **Step 1: Establish the UIP normalization tests**

```ts
test('仅有损坏 UIP 的问标回复仍需要默认卡', () => {
  const normalized = normalizeUIPContent('```uip\n{"interaction":\n```', 'tenderStrict')
  assert.equal(needsTenderFallback(normalized), true)
})
```

- [x] **Step 2: Run the UIP unit tests**

Run: `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts`

Expected: tests cover valid cards, malformed cards, duplicate tender cards, and fallback decisions.

- [ ] **Step 3: Implement policy separation without JSON repair**

`validateUIP` must always reject invalid JSON, non-object roots, missing interaction IDs, unknown interaction types, and cards that the existing renderer cannot safely render. `tenderStrict` additionally limits the tender answer to its first valid card.

```ts
if (interaction.type === 'choice' && !areValidOptions(interaction.options)) {
  return { message: null, reason: 'invalid_choice_options' }
}
```

`normalizeUIPContent` must remove invalid and unclosed blocks, retain at most one valid block in `tenderStrict`, and preserve all ordinary text.

- [x] **Step 4: Run the complete UIP unit suite**

Run: `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts`

Expected: all valid, malformed, duplicate, strict-fallback, and default-compatibility cases pass.

- [ ] **Step 5: Commit the isolated utility change with the remaining frontend changes**

```bash
git add ui/src/utils/chat/uip.ts ui/src/utils/chat/uip.test.ts
git commit -m "feat: normalize tender UIP cards"
```

### Task 2: Normalize completed tender messages before saving them

**Files:**
- Modify: `ui/src/composables/chat/useChatStream.ts`
- Test: `ui/src/utils/chat/uip.test.ts`

**Interfaces:**
- Consumes: `normalizeUIPContent`, `needsTenderFallback`, and `TENDER_FOLLOW_UP_CARD`
- Produces: saved tender assistant messages containing ordinary text plus exactly one valid UIP card when a business answer exists

- [ ] **Step 1: Add failing tests for the final-message decision helper**

Extract a pure helper in `uip.ts` if necessary so the test proves these cases:

```ts
assert.equal(needsTenderFallback(normalizeUIPContent('正文\n```uip\n{"interaction":\n```', 'tenderStrict')), true)
assert.equal(needsTenderFallback(normalizeUIPContent(validTenderCard, 'tenderStrict')), false)
```

- [ ] **Step 2: Run the test before connecting it to the stream**

Run: `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts`

Expected: tests pass for the utility, demonstrating the stream can make its decision from validated blocks rather than a string search.

- [ ] **Step 3: Replace string-based tender-card detection in `onTextMessageEnd`**

Replace the `displayText.includes('```uip')` branch with a normalization step before `onMessageSaved`:

```ts
const isTender = agentDetail.value?.agentCode === 'default-tender'
const normalized = normalizeUIPContent(displayText, isTender ? 'tenderStrict' : 'default')
const savedText = isTender && needsTenderFallback(normalized)
  ? `${normalized.content}\n\n${TENDER_FOLLOW_UP_CARD}`.trim()
  : normalized.content
runHasTenderFollowups.value = normalized.validBlocks.length > 0
```

Use `savedText` for the persisted message and use it to determine `runHasAssistantAnswer`. Do not append a fallback when `displayText` is empty.

- [ ] **Step 4: Ensure the run-finished fallback cannot duplicate the card**

After saving a strict-mode fallback, set `runHasTenderFollowups.value = true`. This lets the existing `onRunFinished` fallback branch remain as a secondary guard without adding a second card.

- [ ] **Step 5: Run type checking and UIP tests**

Run: `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts && npm run type-check`

Expected: all tests pass and `vue-tsc --build` exits successfully.

- [ ] **Step 6: Commit the stream integration**

```bash
git add ui/src/composables/chat/useChatStream.ts ui/src/utils/chat/uip.ts ui/src/utils/chat/uip.test.ts
git commit -m "fix: fallback from invalid tender UIP output"
```

### Task 3: Make the shared UIP renderer silent on invalid terminal content

**Files:**
- Modify: `ui/src/components/markdown/uip/UIPRenderer.vue`
- Modify: `ui/src/components/markdown/MarkdownRenderer.vue`

**Interfaces:**
- Consumes: `validateUIP(props.code, 'default')`
- Produces: no user-visible protocol source, failure label, or retry control for invalid non-streaming UIP blocks

- [ ] **Step 1: Add an invalid-card terminal rendering assertion**

Add a small pure utility assertion in `uip.test.ts` that an unknown type returns `message: null` from `validateUIP`. This is the renderer's terminal-input contract.

- [ ] **Step 2: Run the test before replacing the fallback UI**

Run: `cd ui && node --experimental-strip-types --test src/utils/chat/uip.test.ts`

Expected: the parser contract is explicit; the existing UI still contains the raw-code fallback until the next step.

- [ ] **Step 3: Render only verified cards**

In `UIPRenderer.vue`, derive `parsed` from `validateUIP(props.code).message`. Keep `UipSkeleton` only while `isStreaming` is true. Replace each invalid non-streaming fallback block with a hidden span:

```vue
<span v-else-if="!parsed" class="uip-invalid" aria-hidden="true" />
```

```css
.uip-invalid { display: none; }
```

Remove raw code, error wording, retry state, and `ReloadOutlined` from the UIP renderer. `MarkdownRenderer.vue` continues to extract blocks; invalid blocks are now harmless for old persisted messages and all agents.

- [ ] **Step 4: Run type checking and production build**

Run: `cd ui && npm run type-check && npm run build`

Expected: both commands exit 0. Existing component-name and chunk-size warnings may remain, but no new errors are allowed.

- [ ] **Step 5: Commit the renderer safety change**

```bash
git add ui/src/components/markdown/uip/UIPRenderer.vue ui/src/components/markdown/MarkdownRenderer.vue ui/src/utils/chat/uip.test.ts
git commit -m "fix: hide invalid UIP protocol output"
```

### Task 4: Build and deploy only the local frontend container

**Files:**
- No source changes

- [ ] **Step 1: Verify the local target and current service state**

Run:

```bash
docker compose --project-name k-acp-local --env-file docker/.env.kacp -f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml ps apboa-frontend
```

Expected: only local Docker state is inspected; no remote host or backend service is changed.

- [ ] **Step 2: Build and recreate only `apboa-frontend`**

Run:

```bash
docker compose --project-name k-acp-local --env-file docker/.env.kacp -f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml up -d --build --no-deps --force-recreate apboa-frontend
```

Expected: image rebuild completes and only `k-acp-frontend` is recreated.

- [ ] **Step 3: Health-check the local frontend**

Run: `curl --fail --silent --show-error --max-time 10 http://127.0.0.1:23080/web/`

Expected: HTTP 200 and HTML response.

- [ ] **Step 4: Manual acceptance checks**

Verify with the tender assistant:

1. A normal answer with a valid UIP displays one interactive card.
2. A malformed UIP displays no JSON and ends with the fallback card.
3. A malformed plus valid UIP displays only the valid card.
4. A non-tender agent never receives the tender fallback card.

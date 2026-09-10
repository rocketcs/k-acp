# Chat Ephemeral Model Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user select a configured chat model for the active Chat page, use it only for requests in that active session view, and reset to the agent default whenever the user creates or selects a session.

**Architecture:** The Chat view keeps the selected model only in a Vue `ref`; it never writes the value to `chat_session`, browser storage, or an agent definition. Every AGUI run includes that ID in `forwardedProps.modelConfigId`; `AgentContext` parses it, and `ReActAgentHelper` passes it to a new overload on `ChatModelFactory`. The existing `ModelConfigService.getModelWrapperById` remains the authorization and enabled-state gate after the agent factory establishes the tenant context.

**Tech Stack:** Vue 3 + TypeScript + Ant Design Vue, AGUI SSE, Spring Boot, Java 21, JUnit 5, Mockito, Node built-in test runner.

## Global Constraints

- The selection applies only to the active Chat page state; do not add columns, migrations, session DTO fields, or persistent browser storage.
- Reset the selection to `agentDetail.modelConfigId` on every new-session action and every session-selection action, including when returning to a previously visited session.
- Do not mutate `AgentDefinition.modelConfigId`; the agent default remains the fallback for requests without a valid temporary selection.
- Only list enabled model configurations with `CHAT` in `modelType`; preserve multimodal chat models whose `modelType` includes `CHAT`.
- Reject disabled models, disabled providers, missing models, and models outside the active tenant through the existing `ModelConfigService.getModelWrapperById` path; do not trust the browser-provided ID without that lookup.
- Disable the selector while an AGUI run is active; a running agent keeps the model chosen at run creation.
- Preserve every existing `forwardedProps` value and all session, memory, planning, tool-process, upload, reconnect, and DIY behavior.
- Do not expose provider credentials, request secrets, or model configuration credentials in the UI, logs, API response, or documentation.

---

## File Structure

- Create: `ui/src/utils/chat/modelSelection.ts` — pure helpers for filtering selectable chat models and resetting an ephemeral selection to the agent default.
- Create: `ui/src/utils/chat/modelSelection.test.ts` — Node tests for the pure helper behavior.
- Modify: `ui/src/views/Chat/index.vue` — loads selectable models, owns the non-persistent selection, resets it on session transitions, and passes it to `ChatMain` and `useChatStream`.
- Modify: `ui/src/components/chat/ChatMain.vue` — renders the compact header selector and forwards model-selection events.
- Modify: `ui/src/composables/chat/useChatStream.ts` — adds the selected ID to every normal, tool-continuation, retry, and file-upload AGUI run via `forwardedProps`.
- Modify: `engine/src/main/java/com/hxh/apboa/engine/agui/AgentContext.java` — reads the optional `forwardedProps.modelConfigId` as a positive `Long` for the current request context.
- Modify: `engine/src/main/java/com/hxh/apboa/engine/model/ChatModelFactory.java` — resolves a requested temporary configuration through the existing enabled/tenant-scoped model service, falling back to the agent configuration.
- Modify: `engine/src/main/java/com/hxh/apboa/engine/agent/ReActAgentHelper.java` — uses the context’s temporary model ID only while constructing the current `ReActAgent`.
- Create: `engine/src/test/java/com/hxh/apboa/engine/model/ChatModelFactoryTest.java` — verifies fallback and override selection at the model factory boundary.
- Modify: `ui/src/doc/content/chat/index.md` — documents the current-page-only reset semantics and model eligibility.

## Task 1: Define and test the backend’s temporary model-resolution boundary

**Files:**
- Create: `engine/src/test/java/com/hxh/apboa/engine/model/ChatModelFactoryTest.java`
- Modify: `engine/src/main/java/com/hxh/apboa/engine/model/ChatModelFactory.java:47-106`

**Interfaces:**
- Consumes: `AgentDefinition#getModelConfigId()`, `ModelConfigService#getModelWrapperById(Long)`, and `IChatModel#getModel(ModelConfigWrapper)`.
- Produces: `Model getModel(AgentDefinition agentDefinition, Long temporaryModelConfigId)`, where a non-null temporary ID takes precedence and `null` preserves the default-agent behavior.

- [ ] **Step 1: Write the failing model-factory tests**

```java
package com.hxh.apboa.engine.model;

import com.hxh.apboa.common.entity.AgentDefinition;
import com.hxh.apboa.common.entity.ModelConfig;
import com.hxh.apboa.common.entity.ModelProvider;
import com.hxh.apboa.common.enums.ModelProviderType;
import com.hxh.apboa.common.wrapper.ModelWrapper;
import com.hxh.apboa.model.service.ModelConfigService;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModelFactoryTest {

    @Test
    void usesTemporaryModelConfigWhenRequestSuppliesOne() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        IChatModel chatModel = mock(IChatModel.class);
        Model expected = mock(Model.class);
        when(chatModel.getProvider()).thenReturn(ModelProviderType.OPEN_AI);
        when(chatModel.getModel(any())).thenReturn(expected);
        when(modelConfigService.getModelWrapperById(22L)).thenReturn(modelWrapper());

        AgentDefinition definition = new AgentDefinition();
        definition.setModelConfigId(11L);

        ChatModelFactory factory = new ChatModelFactory(List.of(chatModel), modelConfigService);

        assertSame(expected, factory.getModel(definition, 22L));
        verify(modelConfigService).getModelWrapperById(22L);
    }

    @Test
    void fallsBackToAgentModelConfigWhenRequestDoesNotSupplyOne() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        IChatModel chatModel = mock(IChatModel.class);
        Model expected = mock(Model.class);
        when(chatModel.getProvider()).thenReturn(ModelProviderType.OPEN_AI);
        when(chatModel.getModel(any())).thenReturn(expected);
        when(modelConfigService.getModelWrapperById(11L)).thenReturn(modelWrapper());

        AgentDefinition definition = new AgentDefinition();
        definition.setModelConfigId(11L);

        ChatModelFactory factory = new ChatModelFactory(List.of(chatModel), modelConfigService);

        assertSame(expected, factory.getModel(definition, null));
        verify(modelConfigService).getModelWrapperById(11L);
    }

    private static ModelWrapper modelWrapper() {
        ModelConfig config = new ModelConfig();
        config.setModelId("gpt-test");
        config.setEnabled(true);
        ModelProvider provider = new ModelProvider();
        provider.setType(ModelProviderType.OPEN_AI);
        provider.setEnabled(true);
        return ModelWrapper.builder().config(config).provider(provider).build();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails because the overload is absent**

Run: `./mvnw -pl engine -am -Dtest=ChatModelFactoryTest test`

Expected: compilation failure stating that `getModel(AgentDefinition, Long)` does not exist.

- [ ] **Step 3: Add the overload without weakening existing validation**

```java
public Model getModel(AgentDefinition agentDefinition) {
    return getModel(agentDefinition, null, false);
}

public Model getModel(AgentDefinition agentDefinition, Long temporaryModelConfigId) {
    return getModel(agentDefinition, temporaryModelConfigId, false);
}

public Model getModel(AgentDefinition agentDefinition, boolean multi) {
    return getModel(agentDefinition, null, multi);
}

private Model getModel(
        AgentDefinition agentDefinition, Long temporaryModelConfigId, boolean multi) {
    ModelConfigWrapper configWrapper = ModelConfigWrapper.builder().build();
    // Keep the existing modelParamsOverride parsing here unchanged.

    Long effectiveModelConfigId = temporaryModelConfigId != null
            ? temporaryModelConfigId
            : agentDefinition.getModelConfigId();
    ModelWrapper config = modelConfigService.getModelWrapperById(effectiveModelConfigId);
    // Keep the existing config/provider fill, multi, tool-strategy, and provider dispatch unchanged.
}
```

Do not add a direct mapper call or bypass `getModelWrapperById`: that method already rejects a missing or disabled model and a missing or disabled provider, while tenant context is established by `IAgentFactory` before this helper runs.

- [ ] **Step 4: Run the focused test and the engine compile**

Run: `./mvnw -pl engine -am -Dtest=ChatModelFactoryTest test`

Expected: `Tests run: 2, Failures: 0, Errors: 0` and Maven `BUILD SUCCESS`.

- [ ] **Step 5: Commit the focused backend model-resolution boundary**

```bash
git add engine/src/main/java/com/hxh/apboa/engine/model/ChatModelFactory.java engine/src/test/java/com/hxh/apboa/engine/model/ChatModelFactoryTest.java
git commit -m "feat: support temporary chat model selection"
```

## Task 2: Carry a validated one-run override from AGUI context into ReAct construction

**Files:**
- Modify: `engine/src/main/java/com/hxh/apboa/engine/agui/AgentContext.java:24-64`
- Modify: `engine/src/main/java/com/hxh/apboa/engine/agent/ReActAgentHelper.java:62-67`

**Interfaces:**
- Consumes: `RunAgentInput#getForwardedProp(String)` and `ChatModelFactory#getModel(AgentDefinition, Long)` from Task 1.
- Produces: `AgentContext#getModelConfigId()` returning either a positive model configuration ID for the current request or `null` for the agent default.

- [ ] **Step 1: Write the failing context parsing tests in the existing backend test class**

Add these imports and tests to `engine/src/test/java/com/hxh/apboa/engine/model/ChatModelFactoryTest.java` so parsing is tested without a Spring context:

```java
import com.hxh.apboa.engine.agui.AgentContext;
import io.agentscope.core.agui.model.RunAgentInput;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Test
void contextReadsPositiveTemporaryModelConfigIdFromForwardedProps() {
    RunAgentInput input = RunAgentInput.builder()
            .runId("run-1")
            .forwardedProps(Map.of("modelConfigId", "22"))
            .build();

    AgentContext.init(input, "thread-1");
    assertEquals(22L, AgentContext.get().getModelConfigId());
    AgentContext.clean();
}

@Test
void contextLeavesTemporaryModelConfigUnsetWhenNotProvided() {
    RunAgentInput input = RunAgentInput.builder().runId("run-1").build();

    AgentContext.init(input, "thread-1");
    assertNull(AgentContext.get().getModelConfigId());
    AgentContext.clean();
}

@Test
void contextRejectsNonPositiveTemporaryModelConfigId() {
    RunAgentInput input = RunAgentInput.builder()
            .runId("run-1")
            .forwardedProps(Map.of("modelConfigId", 0))
            .build();

    assertThrows(IllegalArgumentException.class, () -> AgentContext.init(input, "thread-1"));
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./mvnw -pl engine -am -Dtest=ChatModelFactoryTest test`

Expected: compilation errors for missing `getModelConfigId()` or the current context accepting the invalid value.

- [ ] **Step 3: Parse the optional request value and consume it once**

In `AgentContext`, add the field and initialization:

```java
private Long modelConfigId;

agentContext.setModelConfigId(toPositiveLong(input.getForwardedProp("modelConfigId"), "modelConfigId"));

private static Long toPositiveLong(Object value, String fieldName) {
    if (value == null) {
        return null;
    }
    try {
        long parsed = value instanceof Number number
                ? number.longValue()
                : Long.parseLong(value.toString());
        if (parsed <= 0) {
            throw new IllegalArgumentException(fieldName + " must be a positive integer");
        }
        return parsed;
    } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(fieldName + " must be a positive integer", exception);
    }
}
```

In `ReActAgentHelper#getReActAgent`, replace the model construction with:

```java
Long temporaryModelConfigId = AgentContext.getIfExists()
        .map(AgentContext::getModelConfigId)
        .orElse(null);
Model model = chatModelFactory.getModel(definition, temporaryModelConfigId);
```

Do not apply this override in `A2aAgentHelper` or workflow-node execution: the requested feature is limited to the interactive custom-agent Chat route, and those execution paths must keep their configured models.

- [ ] **Step 4: Run all focused backend tests**

Run: `./mvnw -pl engine -am -Dtest=ChatModelFactoryTest test`

Expected: `Tests run: 5, Failures: 0, Errors: 0` and Maven `BUILD SUCCESS`.

- [ ] **Step 5: Commit AGUI context propagation**

```bash
git add engine/src/main/java/com/hxh/apboa/engine/agui/AgentContext.java engine/src/main/java/com/hxh/apboa/engine/agent/ReActAgentHelper.java engine/src/test/java/com/hxh/apboa/engine/model/ChatModelFactoryTest.java
git commit -m "feat: pass temporary model through chat context"
```

## Task 3: Add a testable non-persistent model selection state to the Chat frontend

**Files:**
- Create: `ui/src/utils/chat/modelSelection.ts`
- Create: `ui/src/utils/chat/modelSelection.test.ts`
- Modify: `ui/src/views/Chat/index.vue:1-100, 609-655`
- Modify: `ui/src/composables/chat/useChatStream.ts:35-55, 306-405`

**Interfaces:**
- Consumes: `ModelConfigVO`, the existing `modelApi.configPage({ page: 1, size: 1000, enabled: true })`, Chat’s `currentSessionId`, and `agentDetail.modelConfigId`.
- Produces: `selectedModelConfigId: Ref<string>` that always resets to the agent default when `currentSessionId` changes, plus `forwardedProps.modelConfigId` on every new AGUI run.

- [ ] **Step 1: Write the failing pure frontend tests**

```typescript
import test from 'node:test'
import assert from 'node:assert/strict'
import { getSelectableChatModels, resetEphemeralModelSelection } from './modelSelection.ts'

test('只显示启用的 CHAT 模型，并保留同时支持多模态的 CHAT 模型', () => {
  const models = [
    { id: 'chat', enabled: true, modelType: ['CHAT'] },
    { id: 'multi', enabled: true, modelType: ['CHAT', 'IMAGE'] },
    { id: 'image', enabled: true, modelType: ['IMAGE'] },
    { id: 'disabled', enabled: false, modelType: ['CHAT'] },
  ] as any[]

  assert.deepEqual(getSelectableChatModels(models).map(model => model.id), ['chat', 'multi'])
})

test('新建或切换会话时始终恢复智能体默认模型', () => {
  assert.equal(resetEphemeralModelSelection('temporary-model', 'agent-default'), 'agent-default')
  assert.equal(resetEphemeralModelSelection('', 'agent-default'), 'agent-default')
})
```

- [ ] **Step 2: Run the test to verify it fails because the helper is absent**

Run: `cd ui && node --experimental-strip-types --test src/utils/chat/modelSelection.test.ts`

Expected: module-not-found failure for `modelSelection.ts`.

- [ ] **Step 3: Implement the pure helpers**

```typescript
import type { ModelConfigVO } from '@/types'

function includesChatModelType(modelType: unknown): boolean {
  if (Array.isArray(modelType)) return modelType.includes('CHAT')
  return modelType === 'CHAT'
}

export function getSelectableChatModels(models: ModelConfigVO[]): ModelConfigVO[] {
  return models.filter(model => model.enabled === true && includesChatModelType(model.modelType))
}

export function resetEphemeralModelSelection(
  _currentSelection: string,
  agentDefaultModelConfigId: string | number | null | undefined,
): string {
  return agentDefaultModelConfigId == null ? '' : String(agentDefaultModelConfigId)
}
```

- [ ] **Step 4: Wire the ephemeral state into `Chat/index.vue`**

Add imports for `ModelConfigVO`, `modelApi`, and the two helpers. After `currentSessionId` is created, add:

```typescript
const selectableModels = ref<ModelConfigVO[]>([])
const selectedModelConfigId = ref('')

const resetSelectedModel = () => {
  selectedModelConfigId.value = resetEphemeralModelSelection(
    selectedModelConfigId.value,
    agentDetail.value?.modelConfigId,
  )
}

const loadSelectableModels = async () => {
  const response = await modelApi.configPage({ page: 1, size: 1000, enabled: true })
  selectableModels.value = getSelectableChatModels(response.data?.data?.records ?? [])
}

watch(agentDetail, resetSelectedModel, { immediate: true })
watch(currentSessionId, resetSelectedModel)

onMounted(() => { loadSelectableModels().catch(() => { selectableModels.value = [] }) })
```

Pass `selectedModelConfigId` into `useChatStream`, and pass `selectableModels` plus `selectedModelConfigId` to `ChatMain`. Do not call `chatSessionApi` and do not change `useSessions#createSession`; the reset watcher must be the only session-bound behavior.

In `useChatStream`, accept `modelConfigId?: Ref<string>` and append one field to `getForwardedProps`:

```typescript
modelConfigId: modelConfigId?.value || agentDetail.value?.modelConfigId || undefined,
```

Because `getForwardedProps()` is already used by `sendMessage`, `sendToolContent`, file-upload retries, and UIP retries, every new run receives the current selection without modifying reconnect behavior. Do not add it to the reconnect URL: reconnect resumes an already-created run and must not change its model.

- [ ] **Step 5: Run frontend unit tests and type checking**

Run: `cd ui && node --experimental-strip-types --test src/utils/chat/modelSelection.test.ts && npm run type-check`

Expected: both Node tests pass and the TypeScript check exits with status 0.

- [ ] **Step 6: Commit frontend state and request propagation**

```bash
git add ui/src/utils/chat/modelSelection.ts ui/src/utils/chat/modelSelection.test.ts ui/src/views/Chat/index.vue ui/src/composables/chat/useChatStream.ts
git commit -m "feat: send temporary model selection from chat"
```

## Task 4: Render the selector and document reset semantics

**Files:**
- Modify: `ui/src/components/chat/ChatMain.vue:18-61, 230-261`
- Modify: `ui/src/doc/content/chat/index.md`

**Interfaces:**
- Consumes: `ModelConfigVO[]`, `selectedModelConfigId`, and the existing `isRunning` state from Task 3.
- Produces: A header selector that can change the active-page selection only before a run starts, plus user documentation that makes the reset behavior explicit.

- [ ] **Step 1: Add selector props and event to `ChatMain`**

Add the following props and event declarations:

```typescript
import type { DisplayMessage, UploadedFileItem, PlanInfo, DiyOutputFormat, DiyPageConfig, ModelConfigVO } from '@/types'

modelOptions?: ModelConfigVO[]
modelConfigId?: string

(e: 'update:modelConfigId', value: string): void
```

Insert this element immediately after the title in `.chat-main-header`:

```vue
<ASelect
  v-if="modelOptions?.length"
  :value="modelConfigId"
  :disabled="isRunning"
  size="small"
  style="width: 180px; margin-left: 12px"
  aria-label="当前对话模型"
  @update:value="$emit('update:modelConfigId', String($event))"
>
  <ASelectOption v-for="model in modelOptions" :key="model.id" :value="String(model.id)">
    {{ model.name }}
  </ASelectOption>
</ASelect>
```

In `Chat/index.vue`, bind it exactly once:

```vue
:model-options="selectableModels"
:model-config-id="selectedModelConfigId"
@update:model-config-id="selectedModelConfigId = $event"
```

The component must not use `localStorage`, Pinia persistence, or a route query parameter. When no selectable models load, omit the selector and continue using the agent’s default configuration.

- [ ] **Step 2: Add a concise Chat guide section**

Append this text under the Chat operation instructions in `ui/src/doc/content/chat/index.md`:

```markdown
### 临时切换模型

在对话顶部可选择当前运行使用的模型。该选择仅保存在当前页面内存中：创建新对话、切换到其他会话或刷新页面后，系统恢复该智能体的默认模型。选择不会修改智能体配置、不会写入会话历史，也不会影响其他用户或会话。

只有已启用且支持 `CHAT` 的模型会显示。模型正在生成回复时无法切换；等待本次回复结束后再选择模型并发送下一条消息。
```

- [ ] **Step 3: Run the complete verification set**

Run:

```bash
./mvnw -pl engine -am -Dtest=ChatModelFactoryTest test
cd ui && node --experimental-strip-types --test src/utils/chat/modelSelection.test.ts && npm run type-check && npm run build
```

Expected: backend tests pass, frontend tests pass, type check passes, and the UI production build completes successfully.

- [ ] **Step 4: Perform manual browser acceptance checks**

1. Open a custom agent with at least two enabled `CHAT` model configurations; verify both appear in the header selector and the agent-default model is preselected.
2. Select the other model, send a message, and verify the backend builds the run with that ID without changing the agent’s configured `modelConfigId`.
3. While the response is streaming, verify the selector is disabled; after the response completes, verify it is enabled again.
4. Create a new session and verify the selector returns to the agent default.
5. Select an existing session, choose a temporary model, switch to another session, then return; verify it has returned to the agent default rather than the earlier temporary choice.
6. Refresh the Chat page and verify the selector returns to the agent default.
7. Disable the temporarily selected model in another browser session, then send a message; verify the request fails cleanly through the existing `model config is disabled` validation and does not fall back silently to a different model.
8. Repeat one message with memory, planning, file upload, tool continuation, and DIY quick-send enabled as applicable; verify their existing behavior remains unchanged.

- [ ] **Step 5: Commit the UI and documentation**

```bash
git add ui/src/components/chat/ChatMain.vue ui/src/views/Chat/index.vue ui/src/doc/content/chat/index.md
git commit -m "feat: add chat model switcher"
```

## Plan Review

**Spec coverage:** The plan covers a selector in Chat, a one-request AGUI override, no persistence, reset on new or selected sessions, defaults on refresh, enabled/tenant-scoped model validation, and protection against switching a model during a run. It deliberately excludes agent-definition edits, database migrations, session API changes, A2A behavior, workflow nodes, and reconnect mutation.

**Placeholder scan:** No task relies on an unspecified interface, database change, or follow-up implementation. Model override name, types, tests, commands, expected outcomes, and verification steps are specified explicitly.

**Type consistency:** `modelConfigId` is a string in Vue state and `forwardedProps`, is parsed to positive `Long` in `AgentContext`, and is passed as nullable `Long` to `ChatModelFactory#getModel(AgentDefinition, Long)`. A missing value is consistently represented as `''` in the UI and `null` in Java.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-24-chat-ephemeral-model-switching.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?

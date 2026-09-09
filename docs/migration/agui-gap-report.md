# AG-UI vendored 代码 vs AgentScope 2.0.2 官方实现 —— 迁移差距报告

> 只读侦察产出（分支 `asv2`）。对比基线：
> - vendored：`runner-runtime/src/main/java/io/agentscope/**`（14 个文件，共 4942 行）
> - 官方：`io.agentscope:agentscope-extensions-agui:2.0.2`、`agentscope-agui-spring-boot-starter:2.0.2`、`agentscope-core:2.0.2`（sources 已拉取，逐文件 diff）
>
> **总判断**：vendored 代码是一份「**1.0.12 时代 core + 2.0.2-dev 早快照（约 2026-07-16）**」的源码拷贝，叠加了大量 Apboa 本地补丁（多租户、HITL 自研暂停/恢复、RunTracker 事件缓冲、多模态附件、shell-proxy 等）。官方 2.0.2 GA（2026-08-04 构建）在 AG-UI 层做了大重构：adapter 策略化（`AgentEventConverterRegistry` + `AguiEventEnricher`）、`RuntimeContext` 贯穿、HITL 改为 **interrupt/resume 官方契约**（`AguiResumeCoordinator` + `AguiResume`）。vendored 中**约 60% 的行数需要删除或重写**，本地补丁需迁到官方扩展点。

---

## 0. 版本与 API 基线事实（影响所有结论）

| 事实 | 证据 |
|---|---|
| 根 pom 已声明 2.0.2：`<agentscope.version>2.0.2</agentscope.version>`（pom.xml:37） | 但 vendored 代码全部按 **1.0.12 core API** 编写 |
| 1.0.12 core 有 `io.agentscope.core.session.{Session,InMemorySession}`、`state.SimpleSessionKey`、`ReActAgent.loadFrom/saveTo(Session, SessionKey)`、`getMemory()`、`Event/EventType/stream()` | `javap` 1.0.12 jar 确认 |
| 2.0.2 core **删除**了上述全部：`io.agentscope.core.session` 包不存在；状态统一为 `io.agentscope.core.state.{AgentState,AgentStateStore}`；流式统一为 `streamEvents(List<Msg>, RuntimeContext)` 返回 `Flux<AgentEvent>`；`ReActAgent.interrupt(String userId, String sessionId)` / `interrupt(RuntimeContext)` | unzip + 源码确认 |
| 2.0.2 `GenerateReason.REASONING_STOP_REQUESTED` 仍存在；`ToolResultBlock.suspended(toolCall, ToolSuspendException)` 仍存在（HITL 挂起的正式出口） | core 2.0.2 源码 |
| ⚠️ 本地 `~/.m2` 的 `com.hxh.apboa.next:apboa-next:1.0-SNAPSHOT` 父 POM 是旧的（`agentscope.version=1.0.12`），`mvn -pl runner-runtime` 单模块构建会解析到 1.0.12；全量 reactor 构建才用 2.0.2 属性 | `mvn help:evaluate` + `dependency:tree` 实测 |

---

## 1. 逐文件本地补丁点（vendored → 2.0.2 差距）

### 1.1 `core/agui/adapter/AguiAgentAdapter.java`（543 行；官方 2.0.2 为 759 行，结构性重写）

| 补丁点 | 位置（vendored 行号） | 内容 | 2.0.2 现状 |
|---|---|---|---|
| HITL 挂起标记 | L65-67, L151-152 | `volatile boolean suspended` + 检测 `GenerateReason.REASONING_STOP_REQUESTED` 置位，供 processor 决定「无条件保存暂停态」 | 官方无此机制；挂起由 **`ToolResultBlock.suspended` → `ToolResultEndEvent(state=RUNNING)` → `ToolResultEventConverter.markToolCallSuspended()` → `RunFinished.outcome=interrupt`** 官方链路表达 |
| HITL 恢复入口 | L107 `runWithMessages(List<Msg>, threadId, runId)` | 绕过 AG-UI 消息转换直接喂 `Msg` 列表续跑 | 官方**无此方法**；恢复 = 下一轮 `RunAgentInput.resume[]`，`AguiMessageConverter.toMsgList(input, resumeToolCallIds)`（L170）负责把 `AguiResume` 转成 `ToolResultBlock` |
| 下载链接归一化 | L19, L159 `DownloadLinkMarkdownNormalizer.normalize(textBlock.getText())` | Apboa 业务 | 官方无；需经 `AguiEventEnricher` 或自定义 `TextBlockEventConverter` 等价实现 |
| 文本消息 ID 复用 | L165-176, L190-201（两处 `hasActiveTextMessage()` 分支）+ `EventConversionState.currentTextMessageId`（L497-511） | 避免产生多个 `TEXT_MESSAGE_START` | 官方 `AguiStreamContext` 已内建活跃消息状态机（`TextBlockEventConverter` 统一处理） |
| 工作流进度自定义事件 | L271-290 | `ToolResultBlock.metadata["workflow_node_progress"]` → `AguiEvent.Custom("WORKFLOW_NODE_PROGRESS", …)` | 官方 `CustomAgentEventConverter` 支持 custom 透出；此逻辑需做成自定义 `AgentEventConverter`（监听 `ToolResultEvent` 变体）或 `AguiEventEnricher` |
| `TOOL_CONFIRM_REQUIRED` 自定义事件 | L345-370（`AGENT_RESULT` + `REASONING_STOP_REQUESTED` + `IConfirmationHook.isNeedConfirm` 过滤） | Apboa 自研 HITL 前端协议 | 官方等价物 = `RunFinished.outcome.interrupts[]`（`AguiEvent.Interrupt{id, reason="tool_call", toolCallId, responseSchema,…}`，AguiEvent.java:1669） |
| 子 Agent 流式转发 | L316-333（注释掉的 TODO 块） | 未启用 | 官方 2.0.2 已内建 `SubagentEventConverter`（strategy/SubagentEventConverter.java:37，产出 `AguiEvent.Custom`，L133） |
| 整体架构 | 全文件 | 1.x `agent.stream(msgs, options)` + `Event/EventType` + 手写状态机 | 2.0.2：`ReActAgent.streamEvents(msgs, runtimeContext)` + 9 个 `AgentEventConverter` 策略 + `AguiStreamContext` + `finishPendingEvents`（AguiAgentAdapter.java:212-232, 273-281）；另支持 HarnessAgent 反射探测（L244-269） |

**迁移结论**：整体删除，换官方实现。本地补丁去往：① 下载链接归一化 → 自定义 `AguiEventEnricher`（函数式接口 `AguiEvent enrich(AguiEvent event, AguiStreamContext context)`）；② `WORKFLOW_NODE_PROGRESS` → 自定义 `AgentEventConverter implements AgentEventConverter`（`Set<Class<? extends AgentEvent>> eventTypes()` + `convert(event, context)`），经 `AguiAdapterConfig.builder().eventConverters(...)` 注入；③ `suspended`/`TOOL_CONFIRM_REQUIRED`/`runWithMessages` → 官方 interrupt/resume 契约（见 §8）。

### 1.2 `core/agui/processor/AguiRequestProcessor.java`（563 行；官方 361 行，重写）

| 补丁点 | 位置 | 内容 | 2.0.2 现状 |
|---|---|---|---|
| 注入 `JdbcTemplate` | L75, L86, L541-548（Builder） | 查 `chat_session`/`agent_definition` 判断 `enable_memory` | 官方 Builder 无 session/jdbcTemplate（L312-346：只有 `agentResolver/config/adapterFactory`） |
| 租户上下文 + 元数据 | L88-101（`AgentMetadataStore.put(tenantId/tenantCode/threadId/toolProcessActive)`）、L145-152（`TenantUtils.setCurrentTenant`） | 多租户 | 2.0.2 官方等价扩展点 = **`AgentResolver.resolveAgent(agentId, threadId)` 实现类内** + **`AguiRuntimeContextResolver.resolve(AguiRuntimeContextRequest)`**（starter，函数式接口） |
| 记忆开关 | L102-142（`memoryActive` forwardedProp + `getAgentDefinition` SQL + `isUIP` 判断 + `memory.clear()`） | 服务端/前端记忆二态 | 官方仅保留 `agentResolver.hasMemory(threadId)` → `extractLatestUserMessage`（L124-130）；「清空记忆」语义由 `DefaultAgentResolver`/会话层承担 |
| **无条件保存暂停态** | L156-176（`doFinally`：`shouldSave = agent instanceof ReActAgent && (memoryActive \|\| adapter.isSuspended())` → `saveTo(session, threadId)`） | HITL 跨实例恢复的核心 | 2.0.2：状态持久化职责移交 **`AgentStateStore`**（agent 构建期注入，如 `MysqlAgentStateStore`），processor 层不再管 saveTo；interrupt 挂起本身不丢（`enablePendingToolRecovery` + stateStore） |
| **`resume()`** | L190-227 | 按 threadId 查 `chat_session` 反查 agentCode/租户 → `loadFrom` → `buildResumeInput`（拒绝工具喂错误结果）→ `runWithMessages` | 官方 2.0.2 **无自定义 resume 方法**；恢复 = 同 threadId 新 run 携带 `RunAgentInput.resume[]`，由 `AguiResumeCoordinator.beginRun/validate/addResumeToolCallIds`（AguiResumeCoordinator.java:53-178）校验并转换 |
| **`getPendingConfirms()`** | L246-296 | 从持久 Session 重建「待确认工具」列表（结构判据：末条 ASSISTANT 含 ToolUseBlock + `IConfirmationHook.isNeedConfirm`） | 官方无（interrupt 状态在内存 `pendingInterruptsByThread`，AguiResumeCoordinator.java:44）；跨重启恢复依赖 AgentStateStore 的持久化 + 前端回放，见 §8.3 |
| 拒绝文案 | L302-321（`REJECT_RESULT_TEXT` + `buildResumeInput`） | 拒绝语义防模型重试 | 2.0.2：拒绝 = `AguiResume(status="resolved", payload={"approved":false,…})` → `toToolResultMsg` 转成 ToolResultBlock（AguiMessageConverter.java:170-191）；文案定制点在 payload 约定，不硬编码 |
| `resolveAgentId` / `extractLatestUserMessage` | L402-462 / L464-502 | 与官方一致 | 官方同名方法保留（L104-191），仅 `RunAgentInput.builder()` 增加 `.state(...).resume(...)`（L182-183） |
| `ResumeDecision` record | L323 | `(toolUseId, name, approved)` | 官方契约：`AguiResume(interruptId, status, payload)`（model/AguiResume.java:30-37） |

**迁移结论**：整体删除重写。租户/元数据 → 自定义 `AgentResolver`（建议实现类 `ApboaAgentResolver implements AgentResolver`，包装 `DefaultAgentResolver`）；租户注入 → `AguiRuntimeContextResolver`（把 `TenantUtils` 放进 `RuntimeContext`，配合 ToolExecutor 侧从 `ToolExecutionContext`/`RuntimeContext` 取）；`memoryActive`/`enable_memory` 判定 → `AgentResolver.hasMemory()` 内实现。

### 1.3 `core/agui/registry/AguiAgentRegistry.java`（163 行 vs 官方 150 行，微补丁）

- L53-57 `setSessionManager(ThreadSessionManager)`；L135-140 `unregister()` 里先 `sessionManager.removeSessionsByAgentId(agentId)`；L147-152 `clear()` 同步清 session —— 官方 Registry 无 ThreadSessionManager 联动（diff 仅此 15 行）。
- **迁移结论**：删除 vendored，直接用官方 `io.agentscope.core.agui.registry.AguiAgentRegistry`（extensions-agui jar）。`unregister` 联动清会话的需求改在调用侧做：`AguiAgentConfiguration.unregisterAgent()`（engine/agui/AguiAgentConfiguration.java:75-82）在 `registry.unregister(agentCode)` 前后自行调用 `sessionManager.removeSession(...)`；或用官方 `AguiAgentRegistryCustomizer`（starter common/AguiAgentRegistryCustomizer.java）包装。

### 1.4 `core/agui/converter/AguiMessageConverter.java`（341 行 vs 官方 415 行）

| 补丁点 | 位置 | 内容 | 2.0.2 现状 |
|---|---|---|---|
| `AttachService` 多模态注入 | L50-55, L141-146（`toMsgList` 末尾 `fullMultimodalMsg`）, L156-243 | 附件 → Image/Video/Audio Block + 文档 hint（`@==##::::##==@` 分隔符） | 官方无；`toMsgList` 已改为 `toMsgList(RunAgentInput)` / `toMsgList(input, resumeToolCallIds)`（L148-191），无钩子。多模态需迁移到 `AgentResolver.resolveAgent` 返回前改造 `RunAgentInput`，或自定义 `AguiAgentAdapterFactory.create(agent, config)` 返回子类覆写 `buildRuntimeContext` 前/后处理 |

**迁移结论**：删除 vendored；多模态附件逻辑搬到 `AguiAgentAdapterFactory` 自定义实现（官方接口：`AguiAgentAdapter create(Agent agent, AguiAdapterConfig config)`，adapter/AguiAgentAdapterFactory.java:22），在自定义 `AguiAgentAdapter` 子类中覆写 `run(input, runtimeContext)`，先对 `input.getMessages()` 做附件展开再调 `super.run`。

### 1.5 `spring/boot/agui/common/ThreadSessionManager.java`（264 行 vs 官方 244 行）

- vendored = 1.0.12 版 + `removeSessionsByAgentId(agentId)`（L146-164，Apboa 增）。hasMemory 判据差异：vendored L124-142 用 `getMemory().getMessages()`（1.x），官方 L128-142 用 `getAgentState().getContext()`（2.x）。
- **迁移结论**：删除 vendored，用官方 2.0.2（starter jar 内含同名类，已带 2.x API 与 `removeSessionsByAgentId`——注意：官方 2.0.2 无该方法，diff 显示这是 Apboa 增量，需在调用侧（`AguiAgentConfiguration.unregisterAgent`、`ClearAgentMetadataStore`）改为遍历 `getSession(threadId)` 判 agentId 或自行维护映射）。

### 1.6 `spring/boot/agui/mvc/AguiMvcController.java`（509 行 vs 官方 449 行，大改）

vendored 独有（官方 2.0.2 全部没有）：

| 方法 | 行号 | 说明 |
|---|---|---|
| `handleResume(threadId, decisions, memoryActive)` | L152-186 | HITL 恢复端点后端 |
| `getPendingConfirms(threadId)` | L203-215 | 刷新重建确认 UI |
| `subscribeAndTrack(...)` + RunTracker 管道 | L229-296 | 事件缓冲 + 多连接回放（SSE 断连不中断 Agent） |
| `reconnect(threadId)` / `getStatus` / `stop` / `getActiveRuns` / `getRunTracker` | L329-370 | 管理端点 |
| Builder `.session(Session)` / `.jdbcTemplate(JdbcTemplate)` | L493-513 | 官方已删除（2.0.2 Builder：L368-437，新增 `runtimeContextResolver` / `adapterFactory`） |

官方 2.0.2 行为差异：SSE `onTimeout/onError` 会 **`result.agent().interrupt()`**（AguiMvcController.java:191,200）——与 vendored「断连不中断、后台续跑」语义**相反**；官方同 thread 并发 run 被 `AguiResumeCoordinator.beginRun` 拒绝（contract error），vendored 是 `registerRun` 时 stop 旧 run（RunTracker.java:74-79）。

**迁移结论**：**不能直接删除** —— vendored 的 RunTracker/reconnect/断连不中断/stop 是产品行为。两条路：
1. 保留 vendored `AguiMvcController`（连同 RunTracker）作为 Apboa 定制壳，内部把 `processor.process(...)` 换成 2.0.2 签名 `process(input, headerAgentId, pathAgentId, RuntimeContext)`，删掉 `session/jdbcTemplate` builder 项；或
2. 删除 vendored，行为差异用官方扩展点补：断连不中断 → 不复用官方 `onTimeout→interrupt` 逻辑就得自定义（官方无扩展点，建议方案 1）；`RuntimeContext` 注入 → `AguiRuntimeContextResolver`；adapter 定制 → `AguiAgentAdapterFactory`。

### 1.7 `spring/boot/agui/mvc/AguiRestController.java`（219 行 vs 官方 108 行）

- 官方 2.0.2 只有 `POST /run`、`POST /run/{agentId}`（L65-108），并把 `HttpServletRequest` 传给 controller。
- vendored 额外端点（前端已依赖，见 §7）：`GET /reconnect/{threadId}`（L137-141）、`POST /resume/{threadId}`（L155-165）、`GET /pending/{threadId}`（L173-180）、`GET /status/{threadId}`（L188-193）、`POST /stop/{threadId}`（L202-208）、`GET /active-runs`（L215-219）；以及 `runWithAgentId` 里的「会话归档不可续聊」校验（L127-131，`ChatSessionService.getById` + `messageTable` 判空）。
- **迁移结论**：保留 vendored（或把它改名为 `ApboaAguiRestController`），改调 2.0.2 controller；`resume` 端点重写为官方契约（body 从 `ResumeRequest{decisions[], memoryActive}` 变为触发新一轮 `RunAgentInput`，其 `resume[]` 填 `AguiResume`），见 §8。

### 1.8 `spring/boot/agui/mvc/RunTracker.java`（320 行，纯 Apboa 新增，官方无对应物）

- 事件缓冲（`MAX_BUFFER_SIZE=10000`，L43）+ 多 emitter 广播 + 断线回放 + `REPLAY_CAUGHT_UP` 标记（L134-194）+ 30 分钟延迟清理（L46, L258-280）。
- **迁移结论**：保留（纯自定义层，不依赖被删 API；仅 `AguiEventEncoder`/`AguiEvent` 类型 import 随 2.0.2 包名不变可直接编译）。注意 2.0.2 下 interrupt 发生时 `RUN_FINISHED(outcome=interrupt)` 也会进入 buffer，回放语义不变。

### 1.9 `spring/boot/agui/mvc/ResumeRequest.java`（27 行，纯 Apboa）

- `record ResumeRequest(List<ResumeDecision> decisions, boolean memoryActive)`。
- **迁移结论**：删除；官方契约下恢复请求就是标准 `RunAgentInput`（含 `resume[]`）。若要保留独立 resume 端点，body 改为 `{resumes:[{interruptId,status,payload}], memoryActive}` 或直接复用 run 端点。

### 1.10 `spring/boot/agui/mvc/AgentscopeAguiMvcAutoConfiguration.java`（114 行 vs 官方 127 行）

- vendored 与官方 2.0.2 差异（diff 27 行）：官方 `aguiMvcController` 注入 `ObjectProvider<AgentEventConverter/AguiEventEnricher/AguiRuntimeContextResolver/AguiAgentAdapterFactory>` 并传入 builder + `AguiAdapterConfig.eventConverters/eventEnrichers`（L77-105）；官方 `aguiRestController` 不再需要 `ChatSessionService`（L115-120）。vendored 的 `AguiAdapterConfig` 缺 `emitTokenUsage`。
- **迁移结论**：**直接删除 vendored**，官方 starter 自动配置覆盖。但注意：项目在 `ApboaAgentSessionConfig.aguiMvcController`（runner-runtime/.../ApboaAgentSessionConfig.java:219-241）手工构建 controller 且 starter 的 `@ConditionalOnMissingBean` 会让官方 bean 退位 —— 该类需按 §8/§1.2 重写。

### 1.11 `spring/boot/a2a/controller/A2aJsonRpcController.java`（90 行，**整文件已注释**）

- 依赖 1.x 的 `io.agentscope.core.a2a.server.AgentScopeA2aServer` + `JsonRpcTransportWrapper`；`@RequestBody JsonNode` 的 hack 注释（L40）。
- **迁移结论**：直接删除。官方 `agentscope-a2a-spring-boot-starter:2.0.2` 已自带 `io.agentscope.spring.boot.a2a.controller.A2aJsonRpcController` + `AgentCardController` + `A2aCommonProperties/JSONRPCProperties`（jar 内容实测，见 §5）。
- 附带发现：`runner-runtime/src/main/java/io/a2a/client/transport/jsonrpc/sse/SSEEventListener.java`（85 行）也是 vendored a2a-client 补丁，本次任务范围外，迁移 a2a-client 时需单独评估。

---

## 2. 官方 2.0.2 对应能力清单

| 能力 | 类 / 位置（2.0.2） | 说明 |
|---|---|---|
| **HITL 暂停/恢复契约** | `processor/AguiResumeCoordinator.java`（包私有 final 类；validate L53 / beginRun L110 / trackPendingInterrupts L180）+ `model/AguiResume.java` + `AguiEvent.RunFinished.outcome`（`RunFinishedSuccessOutcome` L1644 / `RunFinishedInterruptOutcome(List<Interrupt>)` L1654 / `Interrupt` L1669，AguiEvent.java） | 挂起工具 → `Interrupt{id, reason="tool_call", toolCallId, responseSchema, expiresAt, metadata}`；恢复请求 `RunAgentInput.resume[]` 必须覆盖全部未决 interrupt（unresolved/missing/unknown 校验，AguiResumeCoordinator.java:60-91）；status 只允许 `resolved`/`cancelled`（AguiResume.java:24-25） |
| Processor 新签名 | `AguiRequestProcessor.process(input, headerAgentId, pathAgentId, RuntimeContext)`（AguiRequestProcessor.java:113） | Builder 只有 `agentResolver/config/adapterFactory`（L312-346）；错误统一产出 `RUN_STARTED+RUN_ERROR(code)+RUN_FINISHED` 生命周期（L183-197，`mapErrorCode` L198） |
| Registry | `registry/AguiAgentRegistry.java`（extensions-agui）+ starter `AguiAgentRegistryAutoConfiguration`（`@ConditionalOnMissingBean` + `AguiAgentRegistryCustomizer` 列表）+ `AguiAgentAutoRegistration`/`AguiAgentId` | 注册方式不变（`register/registerFactory/getAgent/hasAgent/unregister/clear/size`） |
| MVC 层 | `AguiMvcController`（新增 `HttpServletRequest` 重载 + `AguiRuntimeContextResolver` + `adapterFactory`）、`AguiRestController`（仅 2 端点）、`AgentscopeAguiMvcAutoConfiguration`（ObjectProvider 注入 4 类扩展）+ WebFlux 侧 `AguiWebFluxHandler` | SSE 断连→`agent.interrupt()`；同 thread 串行化 |
| **AguiProperties** | `common/AguiProperties.java`（`agentscope.agui.*`） | 新增 `corsEnabled`、`corsAllowedOrigins`、`emitTokenUsage`；`runTimeout` 变 `Duration`；其余与 vendored 相同（pathPrefix/enableReasoning/serverSideMemory/maxThreadSessions/sessionTimeoutMinutes/agentIdHeader/enablePathRouting/sseTimeout）。项目现用 `path-prefix: /runtime/agui`（application-dev.yml:5-9）继续有效 |
| 扩展点 1 | `processor/AgentResolver`（接口：`resolveAgent(agentId, threadId)` / `hasMemory(threadId)`）+ starter `DefaultAgentResolver` | 租户/元数据/记忆判定的正确挂点 |
| 扩展点 2 | `adapter/AguiAgentAdapterFactory`（函数式：`AguiAgentAdapter create(Agent, AguiAdapterConfig)`；`defaultFactory()`） | 替换/包装 adapter（多模态、暂停态增强） |
| 扩展点 3 | `adapter/strategy/AguiEventEnricher`（`AguiEvent enrich(AguiEvent, AguiStreamContext)`）+ `AgentEventConverter`（`eventTypes()` + `convert()`）+ `AguiAdapterConfig.builder().eventConverters(...).eventEnrichers(...)` | 文本归一化、WORKFLOW_NODE_PROGRESS、token 用量等 |
| 扩展点 4 | `common/AguiRuntimeContextResolver` + `AguiRuntimeContextRequest`（input/headerAgentId/pathAgentId/Transport.MVC/method/path/headers/queryParams/nativeRequest） | 每请求构造 `RuntimeContext`（租户、鉴权等） |
| 内建 9 策略转换器 | `TextBlockEventConverter`、`ThinkingBlockEventConverter`、`ToolCallEventConverter`、`ToolResultEventConverter`（RUNNING→suspended interrupt）、`AgentLifecycleEventConverter`（组织 `RunFinished.outcome`）、`ModelCallUsageEventConverter`（CUSTOM token usage）、`SubagentEventConverter`（CUSTOM 子代理事件）、`RawAgentEventConverter`、`CustomAgentEventConverter` | vendored 手写状态机的官方替代 |
| 状态事件 | `converter/AguiStateConverter`（`StateSnapshot/StateDelta` JSON Patch）+ `ToolInjection`（前端工具 `SchemaOnlyTool` 注入 + `ToolMergeMode`） | `RunAgentInput.state/tools` 全链路 |
| Resume 消息转换 | `AguiMessageConverter.toMsgList(input, resumeToolCallIds)`（L170） | `interruptId → toolCallId` 由 `AguiResumeCoordinator.addResumeToolCallIds` 放进 `RuntimeContext`（key=`agui.resume.toolCallIds`，adapter/AguiAgentAdapter.java:88） |

---

## 3. 逐文件迁移建议汇总

| # | vendored 文件 | 处置 | 官方替代 / 重新实现位置 |
|---|---|---|---|
| 1 | `core/agui/adapter/AguiAgentAdapter.java` | **删除** | 官方 2.0.2 同名类；定制经 `AguiAgentAdapterFactory`（子类覆写 `run(RunAgentInput, RuntimeContext)`）+ 自定义 `AgentEventConverter`/`AguiEventEnricher` |
| 2 | `core/agui/converter/AguiMessageConverter.java` | **删除** | 官方同名类；多模态附件移入自定义 `AguiAgentAdapterFactory` |
| 3 | `core/agui/processor/AguiRequestProcessor.java` | **删除重写** | 官方 2.0.2 + `ApboaAgentResolver implements AgentResolver`（租户/元数据/enable_memory/isUIP 语义）+ `AguiRuntimeContextResolver`（TenantUtils）+ §8 HITL 方案 |
| 4 | `core/agui/registry/AguiAgentRegistry.java` | **删除** | 官方同名类；`unregister` 联动清会话放调用侧（AguiAgentConfiguration） |
| 5 | `core/tool/ToolExecutor.java` | **删除**（jar 优先级遮蔽手法随版本升级失效） | 官方 2.0.2 已含 `isConcurrencySafe` 分批并行、externalTool 短路、RuntimeContext 合并、`switchIfEmpty` 兜底；租户注入改用 2.0.2 正道：`Toolkit` 构建时 `ToolExecutionContext`/`RuntimeContext` 携带 `AgentContext`，或工具实现内 `Mono.deferContextual` |
| 6 | `core/tool/coding/ShellCommandTool.java` | **保留 vendored**（补丁小：L116-121 proxy 静态字段、L456 幻觉纠正、L570-576 ShellProxyClient 分支；基类与官方 2.0.2 仅 27 行 diff） | 无官方等价 proxy；升级后需 rebase 到 2.0.2 版 ShellCommandTool 之上 |
| 7 | `core/tool/subagent/SubAgentTool.java` | **删除** | 官方 2.0.2 版（AgentStateStore + RuntimeContext + `doFinally(CANCEL)→interruptAgent` 防孤儿代理）；Apboa 补丁（AgentContext.init/租户、AgentMetadataStore childAgent 标记 L151-217）迁到 `SubAgentProvider.provide()` 内部或 `SubAgentConfig` 侧 |
| 8 | `spring/boot/a2a/controller/A2aJsonRpcController.java` | **删除**（已全注释） | `agentscope-a2a-spring-boot-starter:2.0.2` 自带 |
| 9 | `spring/boot/agui/mvc/AguiMvcController.java` | **保留并改造**（产品行为壳） | 内部改调 2.0.2 `process(input,h,p,RuntimeContext)`；删 session/jdbcTemplate builder；`handleResume` 重写为 §8 契约 |
| 10 | `spring/boot/agui/mvc/AguiRestController.java` | **保留并改造** | 保留全部自定义端点；`/resume` body 换官方 `AguiResume` 结构 |
| 11 | `spring/boot/agui/mvc/RunTracker.java` | **保留**（零 API 依赖变化） | — |
| 12 | `spring/boot/agui/mvc/ResumeRequest.java` | **删除/重定义** | 官方 `AguiResume`；或重定义为 `{resumes:[AguiResume], memoryActive}` |
| 13 | `spring/boot/agui/mvc/AgentscopeAguiMvcAutoConfiguration.java` | **删除** | 官方 2.0.2 同名类（多 4 个 ObjectProvider 扩展点注入） |
| 14 | `spring/boot/agui/common/ThreadSessionManager.java` | **删除** | 官方 2.0.2（2.x API）；`removeSessionsByAgentId` 移调用侧 |

---

## 4. vendored tool 包的用途与 2.0 等价物

| 文件 | 用途（项目内证据） | 2.0.2 等价物 |
|---|---|---|
| `ToolExecutor` | Toolkit 内部统一执行器（包私有）。vendored 目的：① workspace hook 错误短路（L190-194，`SysConst.WORKSPACE_HOOK_ERROR_KEY`，配 engine WorkspaceHook）；② 工具执行线程注入租户（L253-276）。**用法是 classpath 遮蔽**：同 FQCN 覆盖 jar 中的类 | 官方 2.0.2 自带且更强（并发安全分批、externalTool 短路、`ToolResultBlock.suspended`、shutdown guard）。租户走 `RuntimeContext`（`ToolCallParam.getRuntimeContext()`，官方 executeCore L222-236 已合并）——项目 `AgentContext` 需放进 `ToolExecutionContext`（2.0.2 `RuntimeContext.asToolExecutionContext()` 桥接） |
| `coding/ShellCommandTool` | Skill/shell 工具白名单执行；Apboa 加了 shell-proxy 容器代理执行（`ShellProxyConfig` 启动注入 `proxyEnabled/proxyBaseUrl`，runner-runtime/.../shellproxy/ShellProxyConfig.java:31-32；SkillBoxFactory.java:260 构造） | 官方 2.0.2 同名类（无 proxy）；**保留 vendored 并 rebase**，proxy 3 处补丁照搬 |
| `subagent/SubAgentTool` | 子智能体即工具：`ToolkitFactory.java:236-245` 经 `toolkit.registration().subAgent(provider, SubAgentConfig)` 注册（REACT/A2A 两类）；`forwardEvents(true)` | 官方 2.0.2 版同名类：状态用 `AgentStateStore`（`config.getStateStore()`）、调用带 `RuntimeContext`、**新增订阅取消即 `interrupt(RuntimeContext)` 防孤儿**（SubAgentTool.java:186-206）。Apboa 的 AgentMetadataStore/租户补丁迁到 provider |

> 附：`ToolSuspendException`/`ToolResultBlock.suspended` 在 1.0.12 与 2.0.2 都存在，是两代 HITL 的共同底层原语 —— 2.0.2 只是把「suspended → 前端确认」的协议层标准化了。

---

## 5. A2aJsonRpcController 用途与官方 a2a starter 结论

- **用途**：1.x 时代把 `AgentScopeA2aServer`（extensions-a2a-server）的 JSON-RPC transport 包装成 Spring MVC 端点（`POST /`，支持 SSE 流式 `Flux<JSONRPCResponse>`）。当前**整文件被注释**，运行时不生效；项目对 A2A 的实际使用走 **a2a-client** 方向（engine/A2aAgentHelper.java:14 `io.agentscope.core.a2a.agent.A2aAgent`，runner-console/AgentA2aController 仅配置管理）。
- **Maven Central 实测（curl maven-metadata）**：
  - `io.agentscope:agentscope-a2a-spring-boot-starter`：**存在 2.0.2**（版本线 1.0.3→1.0.12→1.1.0-RC1/RC2→2.0.0-RC2…→2.0.2，latest=`2.0.2-subagent-bugfix`）。其 2.0.2 jar 内含官方 `io.agentscope.spring.boot.a2a.controller.{A2aJsonRpcController, AgentCardController}` + `AgentscopeA2aAutoConfiguration` + `properties.{A2aCommonProperties, JSONRPCProperties, A2aAgentCardProperties}`。
  - `io.agentscope:agentscope-extensions-a2a-server`：**同样存在 2.0.2**（依赖 core 2.0.2 + a2a-java-sdk 0.3.3.Final jsonrpc transport）。关系：extensions-a2a-server 是服务端核心库（`AgentScopeA2aServer`/transport wrapper 所在层），a2a-spring-boot-starter 是它的 Spring Boot 自动装配壳（内含官方 controller）。
- **建议**：删 vendored controller；后续如需「作为 A2A server 暴露智能体」，直接引入 `agentscope-a2a-spring-boot-starter:2.0.2`（注意当前 common/pom.xml 只有 a2a-client）。另注意 `runner-runtime/.../io/a2a/client/transport/jsonrpc/sse/SSEEventListener.java` 这个额外 vendored 文件不在本报告 14 文件清单内，但同样需要迁移评估。

---

## 6. 项目侧引用清单（grep 全仓库，排除 vendored 自身）

| 符号 | 引用文件:行号 |
|---|---|
| `ThreadSessionManager` | `runner-runtime/.../com/hxh/apboa/runtime/ApboaAgentSessionConfig.java:9,223`；`engine/.../ClearAgentMetadataStore.java:4,23,48-49(uses),60`；`biz/biz-agent/.../ChatSessionServiceImpl.java:27,48,384-386`（删会话时 `removeSession`） |
| `AguiAgentRegistry` | `engine/.../agui/AguiAgentConfiguration.java:4,22,57,72-82`（启动全量重注册 `registerFactory(agentCode, …)`；Redis 频道 `AGENT_REREGISTER_CHANNEL` 经 `AgentReRegisterMessageSubscriber.java:12-24` 触发）；`ApboaAgentSessionConfig.java:5,222,228,232（setSessionManager）` |
| `AguiMvcController` | `ApboaAgentSessionConfig.java:10,214-241`（手工构建，覆盖 starter 自动配置） |
| `AguiRequestProcessor` | 无代码引用，仅注释：`engine/.../agent/ReActAgentHelper.java:143` |
| `RunTracker` | 无代码引用，仅前端注释：`ui/src/views/Chat/index.vue:448` |
| `ResumeRequest` | 无 vendored 外引用（仅被 vendored AguiRestController 使用） |
| `MysqlSession`（`io.agentscope.core.session.mysql`，1.0.12 extensions-session-mysql） | `ApboaAgentSessionConfig.java:7,38,53`（`new MysqlSession(dataSource, databaseName, TableConst.AGENT_SCOPE_SESSIONS, true)`，@Primary `Session` bean） |
| `InMemorySession` | 仅注释提及：`ApboaAgentSessionConfig.java:26`、vendored processor L534 / controller L466；运行时为 processor Builder 缺省值 |
| `AguiAdapterConfig` / `AguiProperties` | `ApboaAgentSessionConfig.java:4,8,253-264`（buildAguiAdapterConfig） |
| 2.0.2 替换 | `MysqlSession` → `io.agentscope.extensions.mysql.state.MysqlAgentStateStore(DataSource)`（pom 注释已写明：common/pom.xml「mysql 状态存储扩展（v2 AgentStateStore）」；metadata 确认 extensions-mysql 有 2.0.2） |

---

## 7. 前端 AG-UI 事件解析兼容性风险（只读盘点）

**处理事件的前端文件**：

| 文件 | 职责 / 关键差异点 |
|---|---|
| `ui/src/types/agui.ts`（212 行） | 全量 AG-UI 事件类型。**风险**：`RunFinishedEvent` 只有 `result?: unknown`（L23-30），未定义 2.0.2 新增的 `outcome?: {type:'success'|'interrupt', interrupts:[Interrupt]}`；`RunStartedEvent.input` 已预留（L14-21）✓；无 `RawEvent.event` 字段定义（只有 `event: unknown`? 实为 L165-170 `event: unknown; source?` ✓ 但读取处错位，见下） |
| `ui/src/api/agui/agent-client.ts`（740 行） | SSE 解析 + 事件 switch。差异点：① `run()` POST 到 `url + "/" + forwardedProps.agentCode`（L148，路径路由）✓ 2.0.2 仍支持；② **错误显示依赖 `RAW` 事件 payload 在 `rawEvent` 字段**（L612-613 `e.rawEvent.error`，onRaw 处理在 useChatStream.ts:336-360）——2.0.2 `AguiEvent.Raw` 的 payload 序列化在 **`event`** 字段（AguiEvent.java:729-745 `@JsonProperty("event")`），vendored 1.0.2 基线是 `rawEvent`（1.0.12 AguiEvent.java:509-515）→ **迁移后错误提示会静默失效**；③ `REPLAY_CAUGHT_UP` 伪事件（L604-607，配套 RunTracker）须保留；④ `TEXT_MESSAGE_CHUNK` 兼容分支（L386-434）2.0.2 不再产生，可留 |
| `ui/src/composables/useAgentClient.ts`（179 行） | run/reconnect/resume 的 Vue 封装（L87-123, L170） |
| `ui/src/composables/chat/useChatStream.ts`（550 行） | 业务层 HITL：`pendingConfirms`（L159）、`onCustom` 处理 `TOOL_CONFIRM_REQUIRED`（L364-367）/`WORKFLOW_NODE_PROGRESS`（L369）；`onRunFinished` 明确注释「不再全标记 needConfirm」；`onRaw` 错误落库（L336-360）；`resume()` 汇总逐工具决策调后端（L430-440） |
| `ui/src/api/agui/request.ts` | URL 构造：`/api/runtime/agui`（run/reconnect/resume/pending/status/stop/active-runs 全套，L10,57-97）——依赖 §1.7 的全部自定义端点 |
| `ui/src/api/agui/index.ts` | 导出 `getReconnectURL/getResumeURL/getPendingURL/getStatusURL/getStopURL/getActiveRunsURL`（L5,44） |
| `ui/src/views/Chat/index.vue` | `restoreConfirm(sid)`：切会话时 `GET /agui/pending` 重建确认 UI（L448-462）；运行中会话 `reconnectStream` |
| `ui/src/stores/modules/chat.ts` | `memoryActive` 持久化偏好（L17,48,63）→ forwardedProps |

**关键差异点汇总（迁移必须处理）**：
1. **`RAW.rawEvent` → `RAW.event` / `RUN_ERROR`**：错误链路字段改名 + 2.0.2 语义上应改听 `RUN_ERROR`（message/code 已在 `RunErrorEvent` 类型里，前端 switch 已支持 case 'RUN_ERROR'，agent-client.ts:57-62 ✓ 只是业务层在 onRaw 里读错了字段名）。
2. **`RUN_FINISHED.outcome.interrupts[]`**：2.0.2 HITL 暂停不再发 `TOOL_CONFIRM_REQUIRED` CUSTOM 事件，而是 `RunFinished(outcome=interrupt)`；前端需新增 outcome 解析（interruptId/reason/toolCallId/responseSchema），并把「逐工具 approved/rejected」映射成 `resume:[{interruptId, status:'resolved', payload:{approved…}}]`。
3. `REASONING_*`、`TEXT_MESSAGE_*`、`TOOL_CALL_*`（start/args/end/result）、`CUSTOM{name,value}`、`STATE_*/MESSAGES_SNAPSHOT/ACTIVITY_*` 的事件形状 2.0.2 与 vendored 兼容（`ThinkingBlock→REASONING_*`、`AguiEvent.Custom(threadId,runId,name,value,…)` 字段名不变）；`StepStarted/StepFinished` 为 2.0.2 新增，前端 default 分支仅 `console.warn`（agent-client.ts:608），无阻断。
4. 自定义端点（pending/status/stop/active-runs/reconnect/REPLAY_CAUGHT_UP）无官方对应 → 保留 vendored 控制器层即可（§1.6/§1.7）。

---

## 8. HITL 恢复链路在 2.0.2 的完整等价实现方案

### 8.1 官方链路（无需自研的部分）

```
工具执行 → 抛 ToolSuspendException（或 externalTool 短路）
  → ToolExecutor: ToolResultBlock.suspended(toolCall, e)            [2.0.2 core 内建]
  → AgentEvent: ToolResultEndEvent(state=RUNNING)
  → ToolResultEventConverter.markToolCallSuspended(toolCallId)      [strategy/ToolResultEventConverter.java:48]
  → AgentLifecycleEventConverter: RunFinished.outcome=RunFinishedInterruptOutcome(List<Interrupt>)
     Interrupt{id, reason="tool_call", toolCallId, responseSchema, expiresAt, metadata}  [AgentLifecycleEventConverter.java:70-130]
  → AguiRequestProcessor.doOnNext: resumeCoordinator.trackPendingInterrupts(threadId, runId, event, runErrorSeen)
     → pendingInterruptsByThread[threadId] = {interruptId → Interrupt}   [AguiResumeCoordinator.java:180-205]
```

恢复（同一 thread 的新 run）：

```
POST /agui/run  body: { threadId, runId, messages:[...], resume:[{interruptId, status:"resolved"|"cancelled", payload:{...}}] }
  → AguiResumeCoordinator.beginRun(input)                                     [AguiResumeCoordinator.java:110-131]
      validate: resume 必须覆盖全部 pending interrupt（unresolved L66、missing/unknown L91，validate 整体 L53-108）
                + 同 thread 只允许一个 active run（L121）
  → addResumeToolCallIds(input, runtimeContext): interruptId→toolCallId 放入
     RuntimeContext["agui.resume.toolCallIds"]                                  [AguiResumeCoordinator.java:143-178]
  → AguiMessageConverter.toMsgList(input, resumeToolCallIds):
     每条 AguiResume → ToolResultBlock(toolCallId, payload) 追加为 TOOL 消息      [AguiMessageConverter.java:170-191]
  → agent.streamEvents(...) 续跑；结束后 finishRun 清理 active marker           [AguiRequestProcessor.java:128-178；finishRun 清理 L171-176]
```

### 8.2 Apboa 补丁 → 2.0.2 映射表

| vendored 机制 | 2.0.2 等价实现 |
|---|---|
| `adapter.isSuspended()` + processor `doFinally` 无条件 `saveTo(session, threadId)`（AguiRequestProcessor.java:156-176） | agent 构建期注入持久化：`stateStore(MysqlAgentStateStore)` + `enablePendingToolRecovery(true)` + `statePersistence(memoryManaged=true…)`（ReActAgentHelper.java:143-151 已是此方向）。暂停态落库由 core 的 call 结束钩子完成（ReActAgent.java:334 注释：interrupt 场景直接持久化 session），processor 不再手工 saveTo |
| `ResumeRequest{decisions[{toolUseId,name,approved}], memoryActive}` → `processor.resume()` | 前端把决策映射为 `RunAgentInput.resume[]`：全允许 = 每个 interrupt `status:"resolved", payload:{approved:true}`；拒绝 = `payload:{approved:false}`（仍属 resolved；**cancelled 仅用于无意义输入**，AguiResume.java:15-17 Javadoc 明确）。`memoryActive` 语义移到 `AgentResolver`/会话管理层（恢复完成后是否删除 stateStore 记录） |
| `REJECT_RESULT_TEXT` 拒绝文案（防模型重试，AguiRequestProcessor.java:302-321） | 2.0.2 由 `payload` 原样转成 ToolResultBlock 文本：约定 `payload = {"approved": false, "message": "<REJECT_RESULT_TEXT 原文案>"}`，在自定义 `AgentResolver`/adapter 层把 payload.message 写成同样的错误语义文案 |
| `getPendingConfirms(threadId)`（刷新/跨实例重建确认 UI，从持久态结构判据） | 短期方案：保留该能力——把它移入自定义 `AgentResolver` 或新的 `ApboaPendingConfirmService`，读 `MysqlAgentStateStore`（2.0.2 `get(userId, sessionId, …)`/`listSessionIds`）而非 1.0.12 `Session`。长期方案：官方 interrupt 元数据（`expiresAt`、`metadata`）持久化后由 stateStore 直接给出结构化 interrupts，无需结构判据 |
| RunTracker 事件缓冲 + resume 续流 | 保留 vendored RunTracker；resume 改为「同一 threadId 新 run」，天然复用 registerRun/reconnect 管道（同 thread 串行化语义与官方 beginRun 一致） |
| `IConfirmationHook.isNeedConfirm` 过滤（排除 MCP/普通工具误标） | interrupt 的产生本身已精确到「被挂起的那个 toolCallId」（ToolResultState.RUNNING），不需要按工具名过滤；工具白名单逻辑保留在 hook 注册侧 |

### 8.3 落地改动清单（建议顺序）

1. **`ApboaAgentResolver implements AgentResolver`**（engine 层新类）：包装 `DefaultAgentResolver`，在 `resolveAgent` 里做租户回填（`TenantUtils` + `AgentMetadataStore`，等价 vendored processor L88-101/145-152）；`hasMemory()` 实现 `memoryActive` + `agent_definition.enable_memory` + `isUIP` 判定（等价 L102-142）。
2. **`ApboaRuntimeContextResolver implements AguiRuntimeContextResolver`**：把租户塞进 `RuntimeContext`，供 ToolExecutor 2.0.2 路径取用（替代 vendored ToolExecutor 的 `Mono.defer` 租户注入）。
3. **`ApboaAgentAdapterFactory`**（可选）：子类化 `AguiAgentAdapter`，附加 `WORKFLOW_NODE_PROGRESS` 转换器 + `DownloadLinkMarkdownNormalizer` enricher（或直接以 Bean 形式提供 `AgentEventConverter`/`AguiEventEnricher`，官方 AutoConfiguration 会自动收集）。
4. **重写 `AguiRestController.resume`**：body 改 `{threadId, resumes:[AguiResume…], memoryActive}`，内部构造 `RunAgentInput`（messages 可为空列表）走 `aguiMvcController.handle(input, headerAgentId)`（2.0.2 controller 已按 thread 串行化 + contract 校验）。
5. **重写 `getPendingConfirms`**：改读 `MysqlAgentStateStore`；控制器 `AguiMvcController.getPendingConfirms` 保留对外签名。
6. **删 `ResumeRequest`/vendored processor/adapter/converter/registry/ThreadSessionManager/AutoConfiguration/A2aJsonRpcController/SubAgentTool/ToolExecutor**，保留并 rebase `ShellCommandTool`、`RunTracker`、`AguiMvcController`（壳）、`AguiRestController`（壳）。
7. **前端**（§7）：`RunFinished.outcome` 解析 + 决策→`resume[]` 映射 + 错误链路改 `RUN_ERROR`（`RAW.event` 字段兜底）。

---

## 附录 A：对比方法与产物

- 官方 sources：`mvn dependency:get -Dartifact=io.agentscope:agentscope-extensions-agui|agentscope-agui-spring-boot-starter|agentscope-core:2.0.2:jar:sources`（成功），解包于 `/tmp/agui-src/{ext,starter,core}`；1.0.12 sources 解包于 `/tmp/agui-src/ext-1012`（用于剥离 Apboa 补丁归属）。
- diff 规模（official 2.0.2 → vendored）：AguiAgentAdapter 570 diff 行 / AguiRequestProcessor 510 / AguiMvcController 464 / AguiMessageConverter 316 / SubAgentTool 205 / AguiRestController 131 / ToolExecutor 146 / ThreadSessionManager 32 / AutoConfiguration 27 / ShellCommandTool 27 / AguiAgentRegistry 15。
- Maven Central metadata：`agentscope-a2a-spring-boot-starter` 与 `agentscope-extensions-a2a-server` 均有 2.0.2（latest 2.0.2-subagent-bugfix）；`agentscope-extensions-mysql` 有 2.0.2（`MysqlAgentStateStore`）；1.x 的 `agentscope-extensions-session-mysql`（MysqlSession）止步于 2.0.0-RC1。
- 建议同步参考：`docs/migration/agentscope-2.0-upgrade-plan.md`（模块级迁移计划，本报告是其 AG-UI 章节的展开与修正——其中「对照检查本地 vendored 补丁点」一条即本报告 §1/§8）。

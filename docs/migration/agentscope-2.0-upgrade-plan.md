# AgentScope 1.0.12 → 2.0.2 升级迁移计划（asv2 分支）

> **执行进度（2026-08-29）—— 全部完成**
> - ✅ P1 POM：version→2.0.2；common（+harness/extensions-agui/mysql/model×5/rag×3/ltm×3）；engine（extensions-mysql 编译依赖）
> - ✅ P2 模型层：7 文件 import 迁移（io.agentscope.extensions.model.*），0 报错
> - ✅ P3 HarnessAgentHelper：替代 ReActAgentHelper（stateStore/compaction/planMode/skillRepository/RAG/LTM 桥接）；调用方同步适配；删除 SkillBoxFactory/ReActAgentHelper/IMemoryFactory(死代码)
> - ✅ P4 Hook 决策：保留 v1 Hook 体系（LegacyHookDispatcher 官方桥接）
> - ✅ P5 AGUI：删 10 vendored；保留 RunTracker/MvcController壳/RestController/ShellCommandTool rebased；新建 ApboaAgentResolver/ApboaAguiHitlService；重写 ApboaAgentSessionConfig；排除官方 AgentscopeAguiMvcAutoConfiguration 并接管 bean
> - ✅ P6/P7 全量编译 + 全量单测 BUILD SUCCESS；三服务本地启动正常
> - ✅ P8 集成验证：AGUI 全链路（RUN_STARTED→流式→RUN_FINISHED）、记忆双语义、MysqlAgentStateStore 持久化、ChatLogHook 落库、HITL 端点可用。**详见 agentscope-2.0-migration-report.md**

# AgentScope 1.0.12 → 2.0.2 升级迁移计划（asv2 分支）

> 目标版本：`io.agentscope:*:2.0.2`（2026-07-10 GA，最新 2.0.2）
> 策略：**方案B 一步到位全面迁移 v2 架构** —— HarnessAgent + Middleware + AgentStateStore + 官方 AG-UI starter
> 基线：asv2 分支全模块 `mvn compile` 通过（2026 当前）

## 一、版本与依赖矩阵

| 1.0.12 | 2.0.2 | 说明 |
|---|---|---|
| agentscope-core | agentscope-core | 模型提供者/RAG/记忆部分移出或废弃 |
| agentscope-agui-spring-boot-starter | agentscope-agui-spring-boot-starter + **agentscope-extensions-agui** | AG-UI v2（streamEvents + AgentEvent） |
| agentscope-extensions-session-mysql | **agentscope-extensions-mysql**（`MysqlAgentStateStore`） | Session 接口已删除，统一 AgentStateStore |
| agentscope-extensions-a2a-client | agentscope-extensions-a2a-client | 包名不变 `io.agentscope.core.a2a.agent.*` |
| agentscope-extensions-studio | agentscope-extensions-studio | 包名不变 `io.agentscope.core.studio.*`（StudioMessageHook 为 v1 Hook，deprecated 可用） |
| — | **agentscope-harness** | HarnessAgent / CompactionConfig / MemoryConfig / workspace / plan mode |
| core 内置模型提供者 | **agentscope-extensions-model-{openai,dashscope,anthropic,gemini,ollama}** | 包 `io.agentscope.extensions.model.*` |
| core 内置 AutoContextMemory | （无 2.0 版本，扩展停在 1.1.0-RC2） | 用 HarnessAgent `.compaction(CompactionConfig)` 替代 |
| extensions-mem0 / memory-bailian / reme | 同名 2.0.2（deprecated） | LongTermMemoryTools + StaticLongTermMemoryHook 方式挂载 |
| extensions-rag-ragflow / bailian / dify | 同名 2.0.2（deprecated） | Knowledge 仍可用，挂载方式见下 |

## 二、2.0.2 真实 API 事实（javap 确认）

### 已删除（Part A，编译错误）
- `io.agentscope.core.plan.*`（PlanNotebook/Plan/SubTask 全部）
- `io.agentscope.core.session.*`（SessionManager/MysqlSession/InMemorySession）
- `Builder.memory(Memory)` / `.statePersistence(StatePersistence)` / `.structuredOutputReminder(...)`
- `io.agentscope.core.agui.*`（从 core 移到 extensions-agui，且 API 重构）
- core 中 OpenAIChatModel/DashScopeChatModel/AnthropicChatModel/GeminiChatModel/OllamaChatModel 及 formatter.<provider>.*
- `io.agentscope.core.memory.autocontext.*`（AutoContextMemory/AutoContextHook）

### 保留但 deprecated（Part B，@Deprecated(forRemoval=true)）
- `io.agentscope.core.hook.*`（Hook/HookEvent 全家）+ Builder.hook/.hooks（LegacyHookDispatcher 桥接）
- `io.agentscope.core.memory.Memory/InMemoryMemory/LongTermMemory/LongTermMemoryMode/LongTermMemoryTools`
- `io.agentscope.core.rag.Knowledge/KnowledgeRetrievalTools/RAGMode/GenericRAGHook`
- `io.agentscope.core.skill.SkillBox`
- `ReActAgent.Builder.longTermMemory/.knowledge/.ragMode/.retrieveConfig/.skillBox`
- `agent.Event/EventType/StreamOptions`（stream() 系列废弃 → streamEvents()）

### v2 新架构（迁移目标）
- `io.agentscope.core.state`：`AgentState` / `AgentStateStore` / `InMemoryAgentStateStore` / `JsonFileAgentStateStore`
- `io.agentscope.core.middleware.MiddlewareBase`：五阶段 `onAgent/onReasoning/onActing/onModelCall/onSystemPrompt` + `order()`
- `io.agentscope.core.event`：28 个 AgentEvent 类型（`streamEvents()` 返回 `Flux<AgentEvent>`）
- `io.agentscope.harness.agent.HarnessAgent`（implements core.agent.Agent）Builder 关键方法：
  - `stateStore(AgentStateStore)` / `distributedStore(DistributedStore)` / `defaultSessionId(String)`
  - `middleware(...)` / `middlewares(...)` / `hook(...)`（deprecated 桥接仍在）
  - `skillRepository(...)` / `skillRepositories(...)` / `skillFilter(...)` / `skillsEnabled(boolean)`
  - `workspace(Path)` / `filesystem(LocalFilesystemSpec|RemoteFilesystemSpec|SandboxFilesystemSpec)` / `abstractFilesystem(...)`
  - `compaction(CompactionConfig)` / `disableCompaction()` / `toolResultEviction(...)`
  - `memory(MemoryConfig)`（MEMORY.md 工作区记忆，非 v1 LTM）
  - `enablePlanMode(boolean)` / `planFileDirectory(String)` / `allowShellInPlanMode(boolean)`
  - `enableTaskList(boolean)` / `enablePendingToolRecovery(boolean)` / `toolExecutionContext(...)` / `toolkit(...)` / `maxIters` / `sysPrompt` / `model(Model)`
  - `disableFilesystemTools()` / `disableShellTool()` / `disableDynamicSkills()` / `disableDefaultWorkspaceSkills()` / `disableSubagents()` / `disableMemoryTools()` / `disableMemoryHooks()` / `disableSessionPersistence()` / `disableWorkspaceContext()`
- `io.agentscope.extensions.mysql.state.MysqlAgentStateStore(DataSource)`：`save/get/getList/exists/delete/listSessionIds`，按 (userId, sessionId) 分区
- extensions-agui：`AguiAgentAdapter`（strategy 转换器）/ `AguiRequestProcessor` / `AguiResumeCoordinator`（HITL 恢复）/ `AguiAgentRegistry` / `RunAgentInput` / `AguiResume`
- starter：`AguiMvcController`（SSE）/ `AgentscopeAguiMvcAutoConfiguration` / `ThreadSessionManager` / `AguiRuntimeContextResolver` / `DefaultAgentResolver`
- `Msg` 构造时校验 role/content：USER 只允许 Text/Data/Image/Audio/Video，SYSTEM 只允许 TextBlock → 用 `UserMessage/AssistantMessage/SystemMessage/ToolResultMessage`
- Toolkit 默认**并行**执行工具（行为变化）

## 三、项目迁移映射（逐文件）

### P1 POM（pom.xml / common / engine / runner-runtime）
1. `<agentscope.version>2.0.2</agentscope.version>`
2. common/pom.xml：core + agui-starter + **extensions-agui** + harness + 5×model 扩展 + extensions-mysql(替换 session-mysql) + a2a-client + studio + mem0/memory-bailian/reme(可选 deprecated LTM) + rag-{ragflow,bailian,dify}
3. engine/pom.xml：test 的 session-mysql → extensions-mysql

### P2 模型层（engine/model/impl/ 5 文件 + engine/formatter/）
- `io.agentscope.core.model.OpenAIChatModel` → `io.agentscope.extensions.model.openai.OpenAIChatModel`（其余 4 家同理）
- `io.agentscope.core.formatter.<p>.*` → `io.agentscope.extensions.model.<p>.formatter.*`
- `EndpointType`/`OllamaOptions` 等随包迁移（编译修复循环确认具体位置）

### P3 Agent 构建（engine/agent/ReActAgentHelper.java → HarnessAgentHelper）
v1 → v2：
- `builder.memory(new InMemoryMemory())` → `.stateStore(stateStore)`（无记忆开关时也用 stateStore；关闭持久化用 InMemoryAgentStateStore 或 disableSessionPersistence）
- `AutoContextMemory + AutoContextHook` → `.compaction(CompactionConfig)`（maxToken→maxContextTokens 近似映射，其余参数对照 CompactionConfig 实际字段）
- `StatePersistence(...)` → `.stateStore(mysqlStateStore)`（HITL 暂停态持久化由 AgentStateStore 承担）
- `PlanNotebook` → `.enablePlanMode(true)`（isPlanActive 时）+ `.planFileDirectory("plans")`
- `LongTermMemory`（mem0/bailian/reme）→ deprecated 桥接：Toolkit 注册 `LongTermMemoryTools` + hooks 加 `StaticLongTermMemoryHook`（保留功能，标注后续迁移 v2 MemoryConfig）
- `Knowledge/RAGMode/RetrieveConfig` → deprecated 桥接：`GenericRAGHook`（agent 模式）或 Toolkit 注册 `KnowledgeRetrievalTools`（tool 模式），按 RAGMode 分派
- `skillBox(...)` → `.skillRepository(FileSystemSkillRepository/...)`（SkillBoxFactory → SkillRepositoryFactory）
- `structuredOutputReminder(...)` → 删除；如需配置走 GenerateOptions / `Model.supportsNativeStructuredOutput()`
- `hooks(hooksFactory.getHooks())` → `.middlewares(...)`（自定义 Hook 全部 Middleware 化）
- `studio` → StudioMessageHook deprecated 挂 hooks（或包一层 MiddlewareBridge）
- `toolExecutionContext(...)` 保留
- HarnessAgent 默认注册文件/shell 工具：用 `.disableFilesystemTools()` `.disableShellTool()`（平台自有 Toolkit，避免重复与越权）→ 视产品需求定
- RuntimeContext：调用时传 `(userId, sessionId)`（agent 无状态化）

### P4 Hook → Middleware（engine/hook/**, engine/log, engine/workspace/hook）
- 全部自定义 Hook 改为实现 `MiddlewareBase`（五阶段选合适的）：
  - WorkspaceHook（PreReasoning 注入）→ `onSystemPrompt` 或 `onReasoning`
  - WebsocketHook（流式事件推送）→ `onAgent` 包裹事件流
  - ChatLogHook（记录）→ `onAgent`/`onReasoning` 事后
  - IConfirmationHook / IExcludeThinkingBlockHook → 对应 stage
- `HookInstanceLoadFactory`（Groovy 动态 Hook，用户 DB 代码实现 v1 `Hook` 接口）→ 写 `HookMiddlewareBridge implements MiddlewareBase` 包装 v1 Hook 实例，保持存量用户代码兼容；HooksFactory 返回 `List<MiddlewareBase>`
- HooksRegister/HooksSyncToDatabase 同步适配

### P5 AGUI 层（runner-runtime vendored 删除 → 官方）
- 删除 `runner-runtime/src/main/java/io/agentscope/**`（14 文件）
- 官方 starter 提供 `AguiMvcController`/`ThreadSessionManager`/自动配置
- `AguiAgentRegistry` 注册方式、`RunAgentInput`、HITL 恢复用官方 `AguiResumeCoordinator`/`AguiResume`
- 对照检查本地 vendored 补丁点：RunTracker、ResumeRequest、IConfirmationHook 集成、无条件保存暂停态逻辑 → 在官方 processor 上等价落地（必要时 AguiAgentAdapterFactory/AguiEventEnricher 扩展点）
- 前端 SSE 事件格式：AG-UI v2 与 v1 兼容性核对（ui/src 中事件解析）
- biz-agent `ChatSessionServiceImpl`（ThreadSessionManager 引用）适配

### P6 周边
- `WorkflowAgentNodeExecutor`：agent.call(...) 保留（call 在 2.0 仍是主 API）；structuredOutput 调用签名确认
- A2A（biz/biz-a2a、runner-runtime/a2a/controller vendored）：A2aAgent 包不变；删除的 vendored A2aJsonRpcController 用官方 a2a starter 或按需保留适配
- scheduler（Quartz 初始消息）
- biz-mcp（McpClientWrapper/McpClientBuilder 保持）、biz-skill（GitSkillRepository/FileSystemSkillRepository → skill.repository 包仍在 core）
- common（ModelConfigWrapper/AgentDefinition/LongTermMemoryConfig 等 entity/vo 大多不依赖 agentscope 运行时，仅 import 处适配）
- engine/studio、engine/log、engine/workspace、engine/tool/dynamices、engine/security 等

### P7 编译修复循环
`mvn -DskipTests -pl common,engine,runner-console,runner-runtime,runner-websocket -am compile` 直到 0 error，再全模块。

### P8 测试
1. 单测：`mvn test -pl engine,common,runner-runtime`（现有 engine 测试适配）
2. 集成（本地中间件 k-acp-local，勿 down）：
   - 启动 runner-console + runner-runtime（DEVELOPMENT.md：必须 `-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost=` 清空代理）
   - 用例：流式对话 / 工具调用 / HITL 确认与恢复 / 计划模式 / 记忆压缩 / RAG 检索 / 会话持久化(MySQL) / 技能 / MCP / A2A / WebSocket 推送
3. `graphify update .`

## 四、风险清单
- R1 HarnessAgent 默认注册文件/shell/子代理工具 → 必须显式 disable，防止越权（安全）
- R2 Msg role 校验变严格 → 消息构造点运行时异常（测试覆盖）
- R3 Toolkit 并行执行 → 有状态工具/上下文竞争（ToolExecutionContext 传递检查）
- R4 AG-UI 事件格式差异 → 前端联调
- R5 deprecated 桥接（RAG/LTM/Studio/动态Hook）在后续 minor 移除 → 记录 TODO
- R6 记忆压缩参数 AutoContextConfig → CompactionConfig 映射非 1:1，需要压测验证

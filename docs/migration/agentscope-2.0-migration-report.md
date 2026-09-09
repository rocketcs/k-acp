# AgentScope 2.0 迁移完成报告

> 分支：`asv2` ｜ 迁移周期：2026-08-29 ｜ 结果：**全模块编译通过 + 全量单测通过 + 三服务本地启动正常 + AGUI 全链路功能验证通过**

## 一、执行摘要

| 阶段 | 内容 | 结果 |
|---|---|---|
| P1 | POM 版本 2.0.2 + 依赖矩阵切换（harness/extensions-agui/extensions-mysql/model×5/rag×3/ltm×3） | ✅ |
| P2 | 模型层 7 文件迁移（core 内置模型提供者 → `io.agentscope.extensions.model.*`） | ✅ 0 报错 |
| P3 | Agent 构建 HarnessAgent 化：`HarnessAgentHelper` 替代 `ReActAgentHelper`；IAgentFactory/AgentBuilderWrapper/ToolkitFactory/AgentScheduler/WorkflowAgentNodeExecutor 同步适配；删除 SkillBoxFactory/ReActAgentHelper/IMemoryFactory | ✅ |
| P4 | Hook 体系决策：保留 v1 Hook（官方 LegacyHookDispatcher 桥接，因 v2 的 RAG/LTM/Studio 官方桥接本身仍是 v1 Hook 形态） | ✅ |
| P5 | AGUI 层：删除 10 个 vendored 文件；保留并 rebase AguiMvcController壳/AguiRestController/RunTracker/ShellCommandTool；新建 ApboaAgentResolver/ApboaAguiHitlService；重写 ApboaAgentSessionConfig | ✅ |
| P6 | `mvn compile` 全模块 + `mvn test` 全模块 | ✅ BUILD SUCCESS |
| P7 | 集成测试：console/runtime/websocket 三服务启动 + AGUI 对话/记忆/状态持久化/Hook 落库验证 | ✅ |

## 二、核心架构映射（v1 → v2）

| v1 (1.0.12) | v2 (2.0.2) | 实现位置 |
|---|---|---|
| `ReActAgent.builder().memory(...)` | `HarnessAgent.builder().stateStore(AgentStateStore)` | HarnessAgentHelper |
| `AutoContextMemory + AutoContextHook`（扩展已死：1.1.0-RC2） | `.compaction(CompactionConfig)` + `.toolResultEviction(...)`（参数 1:1 映射） | HarnessAgentHelper.configureCompaction |
| `StatePersistence(StatePersistence)` | `stateStore(MysqlAgentStateStore)`（agentscope 库自动创建） | AgentStateStoreFactory |
| `PlanNotebook`（v2 整包删除） | `.enablePlanMode(true)`（v2 Plan Mode：只读调查 + markdown 计划 + HITL 门控） | HarnessAgentHelper |
| `SkillBox`（deprecated） | 自研 `DbAgentSkillRepository implements AgentSkillRepository`（DB 技能包+内置技能+代码执行工具注册进 Toolkit） | SkillRepositoryFactory + DbAgentSkillRepository |
| `structuredOutputReminder(...)` | 移除（v2 原生结构化输出） | — |
| `Knowledge/RAGMode/RetrieveConfig` builder 方法 | deprecated 桥接：RAGMode.AGENTIC→`KnowledgeRetrievalTools`(tool)；GENERIC→`GenericRAGHook`(hook) | HarnessAgentHelper.configureRag |
| `longTermMemory(...)/longTermMemoryAsyncRecord` | deprecated 桥接：`StaticLongTermMemoryHook(ltm, AgentStateMemoryView, true)` + `LongTermMemoryTools`(@Tool 注册) | HarnessAgentHelper.configureLongTermMemory |
| `AgentMetadataStore`（项目自有类） | 保留（ApboaAgentResolver 在 agent 解析后回填 tenantId/tenantCode/threadId/toolProcessActive） | ApboaAgentResolver |
| `MysqlSession`（session-mysql，止步 2.0.0-RC1） | `MysqlAgentStateStore(DataSource, true)`（extensions-mysql，按 userId:sessionId 分区） | AgentStateStoreFactory |
| vendored `AguiRequestProcessor.resume/getPendingConfirms` | `ApboaAguiHitlService`（拒绝工具→`AguiMessage.toolMessage` 错误结果；状态读取 `agent_state` 键） | ApboaAguiHitlService |
| vendored `ToolExecutor`（租户注入/classpath 遮蔽） | 删除；租户经 RuntimeContext/ToolExecutionContext 正道传递 | — |
| `agent.stream(msgs, StreamOptions)` | 官方 adapter 内部 `streamEvents(msgs, RuntimeContext)`（业务无感） | extensions-agui 2.0.2 |
| `memoryActive=false → memory.clear()` | run 前置 `stateStore.delete(userId, threadId)`（UIP/tool 消息不触发） | AguiMvcController.isFreshUserRun |

## 三、验证结果（本地集成）

1. **编译**：`mvn -DskipTests compile` 全模块 0 error；`mvn test` 全模块 BUILD SUCCESS
2. **服务启动**：console(3060)/runtime(3061)/websocket(3064) 全部 health UP，0 启动失败
3. **AGUI 全链路**（POST /runtime/agui/run，X-Agent-Id 路由 + forwardedProps）：
   - `REPLAY_CAUGHT_UP → RUN_STARTED → REASONING_MESSAGE_* → TEXT_MESSAGE_*(流式) → RUN_FINISHED` 完整事件链 ✅
   - 模型真实调用（gpt-5.6-luna，DashScope 兼容端点）✅
   - RunTracker 事件缓冲/回放 ✅（REPLAY_CAUGHT_UP 首事件）
4. **记忆语义**：
   - memoryActive=false 连续两轮 → 第二轮答"不知道"（状态清空生效）✅
   - memoryActive=true → `agentscope.agentscope_sessions` 表按 `userId:sessionId` 分区完整持久化（context/thinking/usage/permission_context/plan_mode_context 全序列化）✅
5. **ChatLogHook**：assistant/thinking 消息落库 chat_message ✅（会话不存在时跳过逻辑保留）
6. **端点**：status/pending/active-runs/reconnect 全部可用 ✅
7. **状态存储**：MysqlAgentStateStore 自动建库建表，异常时降级内存（AgentStateStoreFactory 兜底）✅

## 四、遗留事项（按优先级）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **前端 HITL 事件适配（待联调）** | v2 官方 HITL 用 `RunFinished.outcome=interrupts[]` 表达暂停；当前实现保留了 v1 的 stopAgent 桥接路径（IConfirmationHook via LegacyHookDispatcher），`TOOL_CONFIRM_REQUIRED` 自定义事件能否继续产生**需用一个配置了确认工具的智能体实测**。若失效：前端改解析 outcome.interrupts + resume[] 映射（方案见 agui-gap-report.md §7/§8） |
| 2 | deprecated API 清单（后续 minor 移除风险） | GenericRAGHook / StaticLongTermMemoryHook / LongTermMemoryTools / StudioMessageHook / IConfirmationHook(v1 Hook) / Knowledge 系列。官方 v2 RAG（extensions-rag）与记忆体系成熟后统一切换 |
| 3 | `REJECT_RESULT_TEXT` 恢复语义 | 已保留在 ApboaAguiHitlService；若切官方 AguiResume 契约，需把文案放入 resume payload |
| 4 | 记忆压缩参数非 1:1 | AutoContextConfig→CompactionConfig 映射为近似（triggerTokens/keepMessages/keepTokensRatio），压缩效果建议压测验证 |
| 5 | Toolkit 默认并行 | 项目 `CustomToolkitConfig.isParallel()` 开关保留，存量行为不变；有状态工具需复查并发安全 |
| 6 | 消息角色校验 | Msg 构造已全部走 UserMessage/role 显式（AgentScheduler/WorkflowAgentNodeExecutor 已改）；其余动态构造点靠运行期覆盖 |
| 7 | vendored ShellCommandTool | 保留 proxy 补丁并 rebase 到 2.0.2；升级 AgentScope 版本时需再次 rebase |
| 8 | api 余额 | 迁移过程中两个后台 worker 因 jalapeno-cloud 余额不足 403 终止（模型层/侦察任务已完成，AGUI 实施由主线程完成） |

## 五、回滚方案

`git checkout dev`（或 asv2 前的 commit）即可完整回滚；数据库无破坏性变更（新增 agentscope 库由扩展自动创建，原 agent_scope_sessions 表保留未动）。

package com.hxh.apboa.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.agent.service.AgentCodeExecutionService;
import com.hxh.apboa.agent.service.CodeExecutionConfigService;
import com.hxh.apboa.common.entity.AgentDefinition;
import com.hxh.apboa.common.entity.CodeExecutionConfig;
import com.hxh.apboa.common.util.FuncUtils;
import com.hxh.apboa.common.util.JsonUtils;
import com.hxh.apboa.common.wrapper.KnowledgeWrapper;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.engine.hook.HooksFactory;
import com.hxh.apboa.engine.knowledge.KnowledgeFactory;
import com.hxh.apboa.engine.model.ChatModelFactory;
import com.hxh.apboa.engine.memory.LongTermMemoryFactory;
import com.hxh.apboa.engine.prompt.AgentSysPromptFactory;
import com.hxh.apboa.engine.skill.SkillRepositoryFactory;
import com.hxh.apboa.engine.studio.StudioService;
import com.hxh.apboa.engine.tool.ToolkitFactory;
import com.hxh.apboa.engine.workspace.tool.WorkspaceGuardDenyTool;
import com.hxh.apboa.engine.workspace.tool.WorkspaceGuardMiddleware;
import io.agentscope.core.memory.AgentStateMemoryView;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.model.Model;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 描述：Harness 智能体 Helper（AgentScope 2.0）
 * <p>
 * 替代 v1 的 ReActAgentHelper：基于 v2 {@link HarnessAgent} 构建，
 * 核心映射关系：
 * <ul>
 *   <li>{@code memory(InMemoryMemory/AutoContextMemory)} → {@code stateStore(AgentStateStore)} + {@code compaction(CompactionConfig)}</li>
 *   <li>{@code statePersistence(StatePersistence)} → {@code stateStore(AgentStateStore)}（统一状态存储）</li>
 *   <li>{@code planNotebook(PlanNotebook)} → {@code enablePlanMode(true)}（v2 计划模式）</li>
 *   <li>{@code skillBox(SkillBox)} → {@code skillRepository(AgentSkillRepository)}（DB 技能仓库）</li>
 *   <li>{@code structuredOutputReminder(...)} → 移除（v2 原生结构化输出）</li>
 *   <li>{@code knowledge/ragMode/retrieveConfig} → deprecated 桥接（GenericRAGHook / KnowledgeRetrievalTools，官方 RAG v2 重写中）</li>
 *   <li>{@code longTermMemory(...)} → deprecated 桥接（StaticLongTermMemoryHook + LongTermMemoryTools，官方 LTM v2 重写中）</li>
 *   <li>平台 Hook 体系 → 通过 LegacyHookDispatcher 桥接（官方支持路径）</li>
 * </ul>
 *
 * @author huxuehao
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessAgentHelper {
    private final HooksFactory hooksFactory;
    private final ChatModelFactory chatModelFactory;
    private final AgentSysPromptFactory agentSysPromptFactory;
    private final SkillRepositoryFactory skillRepositoryFactory;
    private final ToolkitFactory toolkitFactory;
    private final KnowledgeFactory knowledgeFactory;
    private final StudioService studioService;
    private final LongTermMemoryFactory longTermMemoryFactory;
    private final AgentCodeExecutionService agentCodeExecutionService;
    private final CodeExecutionConfigService codeExecutionConfigService;
    private final AgentStateStoreFactory agentStateStoreFactory;

    /**
     * 获取 HarnessAgent
     * @param definition agent 定义
     */
    public HarnessAgent getHarnessAgent(AgentDefinition definition) {
        return getHarnessAgentBuilder(definition).build();
    }

    /**
     * 获取 HarnessAgent.Builder
     * @param definition  agent 定义
     */
    public HarnessAgent.Builder getHarnessAgentBuilder(AgentDefinition definition) {
        Model model = chatModelFactory.getModel(definition);
        Toolkit toolkit = toolkitFactory.getToolkit(definition);
        CodeExecutionConfig codeExecutionConfig = getCodeExecutionConfig(definition.getId());
        AtomicReference<io.agentscope.core.agent.Agent> agentRef = new AtomicReference<>();

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(definition.getAgentCode())
                .description(FuncUtils.isEmpty(definition.getDescription()) ? definition.getName() : definition.getDescription())
                .maxIters(definition.getMaxIterations())
                .model(model)
                .sysPrompt(agentSysPromptFactory.getAgentSysPrompt(definition, codeExecutionConfig != null))
                .toolkit(toolkit)
                // 平台自管工具与上下文：关闭 Harness 默认注册（安全：防止文件/shell/子代理工具越权注入）
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableSubagents()
                .disableDefaultWorkspaceSkills()
                // v2 统一状态存储：HITL 暂停态与会话持久化（替代 v1 StatePersistence + Session）
                .stateStore(agentStateStoreFactory.getStore())
                // v2 技能仓库（替代 v1 SkillBox）
                .skillRepository(skillRepositoryFactory.getSkillRepository(definition, toolkit, codeExecutionConfig));

        // 工作空间：压缩 offload / 计划文件落点（平台工作空间目录，按租户+会话隔离）
        String workspacePath = resolveWorkspacePath(definition);
        if (workspacePath != null) {
            java.io.File workspaceDir = new java.io.File(workspacePath);
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs();
            }
            builder.workspace(Paths.get(workspacePath));
        }

        // 配置记忆压缩（v2 Compaction 替代 v1 AutoContextMemory）
        Boolean isMemoryActive = AgentContext.getIfExists().map(AgentContext::isMemoryActive).orElse(false);
        if (definition.getEnableMemory() && isMemoryActive && definition.getEnableMemoryCompression()) {
            configureCompaction(builder, definition, model);
        } else {
            builder.disableCompaction();
            builder.disableToolResultEviction();
        }

        // 计划模式（v2 Plan Mode 替代 v1 PlanNotebook）
        Boolean isPlanActive = AgentContext.getIfExists().map(AgentContext::isPlanActive).orElse(false);
        if (definition.getEnablePlanning() && isPlanActive) {
            builder.enablePlanMode(true);
        }

        // HITL：无条件开启「待确认工具恢复」，
        // 让工具暂停态可经 AgentStateStore save/load，且不依赖 memoryActive。
        builder.enablePendingToolRecovery(true);

        // 平台工作空间安全守卫（v2 Middleware 替代 v1 vendored ToolExecutor 短路）：
        // 注册拒绝工具 + onActing 校验中间件，拦截工作空间敏感工具的违规调用
        toolkit.registerTool(new WorkspaceGuardDenyTool());
        builder.middleware(new WorkspaceGuardMiddleware());


        // 平台 Hook 体系（WorkspaceHook/WebsocketHook/ChatLogHook/内置Hook/动态Hook/StudioHook）
        // v2 通过 LegacyHookDispatcher 桥接（官方支持的 v1 兼容路径）；
        // 待官方 v2 RAG/长期记忆/HITL 体系完备后统一切换 Middleware。
        List<io.agentscope.core.hook.Hook> hooks = new ArrayList<>(hooksFactory.getHooks(definition));

        // RAG 知识检索（deprecated 桥接：官方 v2 RAG 重写中）
        KnowledgeWrapper knowledgeWrapper = knowledgeFactory.getKnowledge(definition);
        if (knowledgeWrapper != null && knowledgeWrapper.getKnowledge() != null) {
            JsonNode retrievalConfigNode = knowledgeWrapper.getRetrievalConfig();
            int limit = JsonUtils.getIntValue(retrievalConfigNode, "topK", 5);
            double scoreThreshold = JsonUtils.getDoubleValue(retrievalConfigNode, "scoreThreshold", 0.5);
            RetrieveConfig retrieveConfig = RetrieveConfig.builder()
                    .limit(limit)
                    .scoreThreshold(scoreThreshold)
                    .build();
            configureRag(builder, toolkit, hooks, knowledgeWrapper, retrieveConfig);
        }

        // 长期记忆（deprecated 桥接：官方 v2 记忆体系重写中）
        LongTermMemory longTermMemory = longTermMemoryFactory.createLongTermMemory(definition);
        if (longTermMemory != null) {
            configureLongTermMemory(builder, toolkit, hooks, longTermMemory, agentRef);
        }

        // Studio（StudioMessageHook 为官方 deprecated 桥接 Hook）
        if (studioService.init(definition)) {
            hooks.add(new StudioMessageHook(StudioManager.getClient()));
        }

        // 注册 Hook（v1 桥接）
        if (!hooks.isEmpty()) {
            builder.hooks(hooks);
        }

        // 保存Agent定义到上下文
        AgentContext.get().setAgentDefinition(definition);

        // 注册工具执行上下文
        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(AgentContext.get())
                .build();
        builder.toolExecutionContext(context);

        return builder;
    }

    /**
     * 配置 RAG：agent 检索模式走 GenericRAGHook，tool 检索模式注册 KnowledgeRetrievalTools
     */
    private void configureRag(HarnessAgent.Builder builder, Toolkit toolkit,
                              List<io.agentscope.core.hook.Hook> hooks,
                              KnowledgeWrapper knowledgeWrapper, RetrieveConfig retrieveConfig) {
        RAGMode ragMode = knowledgeWrapper.getRagMode();
        if (ragMode == RAGMode.AGENTIC) {
            toolkit.registerTool(new KnowledgeRetrievalTools(knowledgeWrapper.getKnowledge(), retrieveConfig));
        } else {
            hooks.add(new GenericRAGHook(knowledgeWrapper.getKnowledge(), retrieveConfig));
        }
    }

    /**
     * 配置长期记忆桥接：
     * - StaticLongTermMemoryHook：会话后异步记录 + 会话前检索（v1 longTermMemoryAsyncRecord 语义）
     * - LongTermMemoryTools：向模型暴露 record/retrieve 工具（@Tool 注解，直接注册）
     */
    private void configureLongTermMemory(HarnessAgent.Builder builder, Toolkit toolkit,
                                         List<io.agentscope.core.hook.Hook> hooks,
                                         LongTermMemory longTermMemory,
                                         AtomicReference<io.agentscope.core.agent.Agent> agentRef) {
        // AgentStateMemoryView: 以 AgentState 为后端的 Memory 视图（v2 会话上下文）
        // agentRef 在 build() 后由框架回填；未回填时返回空状态兜底
        AgentStateMemoryView memoryView = new AgentStateMemoryView(() -> {
            io.agentscope.core.agent.Agent agent = agentRef.get();
            return agent != null ? agent.getAgentState() : io.agentscope.core.state.AgentState.builder().build();
        });
        hooks.add(new StaticLongTermMemoryHook(longTermMemory, memoryView, true));
        toolkit.registerTool(new LongTermMemoryTools(longTermMemory));
    }

    /**
     * 配置记忆压缩（v1 AutoContextConfig → v2 CompactionConfig/ToolResultEvictionConfig 映射）
     */
    private void configureCompaction(HarnessAgent.Builder builder, AgentDefinition definition, Model model) {
        JsonNode config = definition.getMemoryCompressionConfig();
        CompactionConfig compactionConfig = CompactionConfig.builder()
                .triggerTokens(JsonUtils.getIntValue(config, "maxToken", 131072))
                .triggerMessages(JsonUtils.getIntValue(config, "msgThreshold", 100))
                .keepMessages(JsonUtils.getIntValue(config, "lastKeep", 50))
                .keepTokensRatio(JsonUtils.getDoubleValue(config, "tokenRatio", 0.75F))
                .model(model)
                .build();
        builder.compaction(compactionConfig);

        // 大工具结果 offload（v1 largePayloadThreshold/offloadSinglePreview）
        ToolResultEvictionConfig evictionConfig = ToolResultEvictionConfig.builder()
                .maxResultChars(JsonUtils.getIntValue(config, "largePayloadThreshold", 5120))
                .previewChars(JsonUtils.getIntValue(config, "offloadSinglePreview", 200))
                .build();
        builder.toolResultEviction(evictionConfig);
    }

    /**
     * 解析当前请求的工作空间路径（租户 + 会话隔离）
     */
    private String resolveWorkspacePath(AgentDefinition definition) {
        try {
            AgentContext context = AgentContext.getIfExists().orElse(null);
            if (context == null) {
                return null;
            }
            String tenantCode = context.getTenantCode();
            String threadId = context.getThreadId();
            if (FuncUtils.isEmpty(tenantCode) || FuncUtils.isEmpty(threadId)) {
                return null;
            }
            return String.format("%s/%s", com.hxh.apboa.common.consts.SysConst.getWorkspacePath(tenantCode), threadId);
        } catch (Exception e) {
            log.warn("解析工作空间路径失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取代码执行配置
     *
     * @param agentDefinitionId 智能体定义ID
     * @return 代码执行配置
     */
    private CodeExecutionConfig getCodeExecutionConfig(Long agentDefinitionId) {
        Long codeExecutionId = agentCodeExecutionService.getCodeExecutionIdByAgentId(agentDefinitionId);
        if (codeExecutionId == null) {
            return null;
        }
        return codeExecutionConfigService.getById(codeExecutionId);
    }
}

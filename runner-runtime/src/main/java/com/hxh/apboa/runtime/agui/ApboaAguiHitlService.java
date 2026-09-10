package com.hxh.apboa.runtime.agui;

import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.engine.hook.builtins.IConfirmationHook;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 描述：Apboa HITL（人机协同）服务 —— v1 vendored AguiRequestProcessor 的
 * {@code resume()} / {@code getPendingConfirms()} 能力在 v2 上的等价实现。
 * <p>
 * v2 权限 ASK 恢复契约：
 * <ul>
 *   <li>挂起：v2 权限系统对 ASK 行为的工具调用发出 {@code RequireUserConfirmEvent} 并挂起，
 *       待确认工具调用以 ASKING 状态持久化到 AgentStateStore</li>
 *   <li>恢复：{@code agent.streamEvents(msgs)}，msgs 携带
 *       {@code Msg.METADATA_CONFIRM_RESULTS}（{@link ConfirmResult} 列表），
 *       approved 的工具调用被提升为 ALLOWED 并<b>重新执行</b>，denied 的按官方拒绝路径处理</li>
 *   <li>事件流经官方 {@link AgentEventConverterRegistry} 转换为 AG-UI 事件（前端协议不变）</li>
 * </ul>
 *
 * @author huxuehao
 **/
@Slf4j
public class ApboaAguiHitlService {

    private final JdbcTemplate jdbcTemplate;
    private final ApboaAgentResolver apboaAgentResolver;
    private final AgentStateStore agentStateStore;
    private final AguiAdapterConfig adapterConfig;
    private final AgentEventConverterRegistry eventConverterRegistry = new AgentEventConverterRegistry();

    public ApboaAguiHitlService(JdbcTemplate jdbcTemplate,
                                ApboaAgentResolver apboaAgentResolver,
                                AgentStateStore agentStateStore,
                                AguiAdapterConfig adapterConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.apboaAgentResolver = apboaAgentResolver;
        this.agentStateStore = agentStateStore;
        this.adapterConfig = adapterConfig;
    }

    /**
     * resume 逐工具决策。
     */
    public record ResumeDecision(String toolUseId, String name, boolean approved) {}

    /**
     * HITL resume：根据用户确认决策恢复暂停的 agent（v2 权限 ASK 流）。
     *
     * @param threadId     会话 ID
     * @param decisions    逐工具决策（toolUseId/name/approved）；空表示全部允许
     * @param memoryActive 是否开启长期记忆（决定 resume 完成后保留还是删除状态记录）
     * @return 恢复运行的 ProcessResult（agent + AG-UI 事件流）
     */
    public AguiRequestProcessResult resume(
            String threadId, List<ResumeDecision> decisions, boolean memoryActive) {
        ApboaAgentResolver.ResumeContext rc = apboaAgentResolver.loadResumeContext(threadId);
        if (rc == null) {
            throw new IllegalStateException("找不到会话或暂停态: " + threadId);
        }
        TenantUtils.setCurrentTenant(rc.tenantId(), rc.tenantCode());

        // 初始化最小上下文（agent 解析时经 setTenantInfo 回填，但 ThreadLocal 需先行建立）
        AgentContext agentContext = new AgentContext();
        agentContext.setThreadId(threadId);
        agentContext.setRunId(UUID.randomUUID().toString());
        agentContext.setMemoryActive(memoryActive);
        agentContext.setTenantId(rc.tenantId());
        agentContext.setTenantCode(rc.tenantCode());
        AgentContext.init(agentContext);

        // 重建 agent（toolkit 重建会重新登记 need_confirm 工具）
        io.agentscope.core.agent.Agent agent =
                apboaAgentResolver.resolveAgent(rc.agentCode(), threadId);

        // 从持久化状态读取 ASKING 工具调用，构造确认结果消息
        List<ToolUseBlock> asking = readAskingToolCalls(resolveUserId(rc), threadId);
        if (asking.isEmpty()) {
            throw new IllegalStateException("会话无待确认工具调用: " + threadId);
        }
        Msg confirmMsg = buildConfirmResultMsg(asking, decisions);

        String runId = UUID.randomUUID().toString();
        RuntimeContext runtimeContext = buildRuntimeContext(rc, threadId);

        // streamEvents 声明在 ReActAgent/HarnessAgent 上（Agent 接口未暴露）
        reactor.core.publisher.Flux<AgentEvent> agentEvents;
        if (agent instanceof io.agentscope.harness.agent.HarnessAgent harnessAgent) {
            agentEvents = harnessAgent.streamEvents(List.of(confirmMsg), runtimeContext);
        } else if (agent instanceof io.agentscope.core.ReActAgent reActAgent) {
            agentEvents = reActAgent.streamEvents(List.of(confirmMsg), runtimeContext);
        } else {
            throw new IllegalStateException(
                    "不支持的智能体类型，无法恢复: " + agent.getClass().getName());
        }

        // 官方策略转换器：AgentEvent → AG-UI 事件（与正常 run 同一套转换，前端协议不变）
        AguiStreamContext streamContext = new AguiStreamContext(threadId, runId, adapterConfig, null);
        reactor.core.publisher.Flux<AguiEvent> events = agentEvents
                .concatMapIterable(event -> eventConverterRegistry.convert(event, streamContext))
                .doFinally(signal -> {
                    try {
                        if (!memoryActive) {
                            // 临时暂存语义：恢复完成后删除状态记录（等价 v1 session.delete）
                            agentStateStore.delete(resolveUserId(rc), threadId);
                        }
                    } catch (Exception ex) {
                        log.error("resume 后清理状态失败 threadId={}: {}", threadId, ex.getMessage(), ex);
                    } finally {
                        TenantUtils.clear();
                        AgentContext.clean();
                    }
                });

        return new AguiRequestProcessResult(agent, events);
    }

    /**
     * HITL 刷新恢复：从 AgentStateStore 的暂停态重建「待确认工具」列表。
     *
     * <p>用于前端刷新页面 / 重新进入会话时重建确认 UI。结构判据与 v1 一致：
     * 末条消息为 ASSISTANT 且含 {@link ToolUseBlock}，再经
     * {@link IConfirmationHook#isNeedConfirm} 过滤。
     *
     * @param threadId 会话 ID
     * @return 待确认工具 [{toolUseId,name,input}]；无暂停态返回空列表
     */
    public List<Map<String, Object>> getPendingConfirms(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return List.of();
        }
        ApboaAgentResolver.ResumeContext rc;
        try {
            rc = apboaAgentResolver.loadResumeContext(threadId);
        } catch (Exception e) {
            log.warn("加载恢复上下文失败 threadId={}: {}", threadId, e.getMessage());
            return List.of();
        }
        if (rc == null) {
            return List.of();
        }
        TenantUtils.setCurrentTenant(rc.tenantId(), rc.tenantCode());
        try {
            // 智能体解析会重建 toolkit → 重新登记 need_confirm 工具（isNeedConfirm 才有依据）
            apboaAgentResolver.resolveAgent(rc.agentCode(), threadId);

            List<ToolUseBlock> asking = readAskingToolCalls(resolveUserId(rc), threadId);
            List<Map<String, Object>> pending = new ArrayList<>();
            for (ToolUseBlock toolUse : asking) {
                if (IConfirmationHook.isNeedConfirm(toolUse.getName())) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("toolUseId", toolUse.getId());
                    item.put("name", toolUse.getName());
                    item.put("input", toolUse.getInput());
                    pending.add(item);
                }
            }
            return pending;
        } catch (Exception e) {
            // 恢复失败不应阻断页面加载：降级为「无待确认」
            log.warn("恢复暂停态待确认列表失败 threadId={}: {}", threadId, e.getMessage());
            return List.of();
        } finally {
            TenantUtils.clear();
            AgentContext.clean();
        }
    }

    /**
     * 从状态存储读取待确认（ASKING）的工具调用。
     * 结构判据：末条消息为 ASSISTANT 且含 ToolUseBlock（确认暂停发生在工具执行前）。
     */
    private List<ToolUseBlock> readAskingToolCalls(String userId, String threadId) {
        Optional<AgentState> stateOpt =
                agentStateStore.get(userId, threadId, "agent_state", AgentState.class);
        if (stateOpt.isEmpty()) {
            return List.of();
        }
        List<Msg> messages = stateOpt.get().getContext();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Msg last = messages.getLast();
        if (last.getRole() != MsgRole.ASSISTANT) {
            return List.of();
        }
        return last.getContentBlocks(ToolUseBlock.class);
    }

    /**
     * 构造携带确认结果的恢复消息：
     * decisions 为空 = 全部允许（v1 语义）；拒绝的工具由官方 denied 路径处理。
     */
    private Msg buildConfirmResultMsg(List<ToolUseBlock> asking, List<ResumeDecision> decisions) {
        Map<String, ResumeDecision> byId = new LinkedHashMap<>();
        if (decisions != null) {
            for (ResumeDecision d : decisions) {
                if (d != null && d.toolUseId() != null) {
                    byId.put(d.toolUseId(), d);
                }
            }
        }
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock toolUse : asking) {
            ResumeDecision d = byId.get(toolUse.getId());
            boolean approved = d == null || d.approved();
            results.add(new ConfirmResult(approved, toolUse));
        }
        return Msg.builder()
                .role(MsgRole.TOOL)
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    /**
     * 构建恢复运行的 RuntimeContext（会话分区 key 必须与原 run 一致）
     */
    private RuntimeContext buildRuntimeContext(ApboaAgentResolver.ResumeContext rc, String threadId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(threadId)
                .userId(resolveUserId(rc))
                .build();
        ctx.put("threadId", threadId);
        ctx.put("tenantId", rc.tenantId());
        ctx.put("tenantCode", rc.tenantCode());
        return ctx;
    }

    /**
     * 解析状态存储的 userId 分区键（与 RuntimeContextResolver 的口径保持一致）
     */
    private String resolveUserId(ApboaAgentResolver.ResumeContext rc) {
        return rc.userId() != null && rc.userId() > 0 ? String.valueOf(rc.userId()) : "anonymous";
    }

    /**
     * resume 上下文（会话 → 智能体编码/租户/用户）
     */
    public record ResumeContext(String agentCode, Long tenantId, String tenantCode, Long userId) {}

    /** 与官方 AguiRequestProcessor.ProcessResult 等价的结果结构（避免依赖其内部构造） */
    public record AguiRequestProcessResult(
            io.agentscope.core.agent.Agent agent,
            reactor.core.publisher.Flux<AguiEvent> events) {}
}

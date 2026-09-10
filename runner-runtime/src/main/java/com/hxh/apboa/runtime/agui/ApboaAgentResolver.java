package com.hxh.apboa.runtime.agui;

import com.hxh.apboa.common.consts.TableConst;
import com.hxh.apboa.common.util.AgentMetadataStore;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.engine.agui.AgentContext;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agent.Agent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 描述：Apboa AgentResolver —— 官方 {@link AgentResolver} 的多租户适配实现。
 * <p>
 * 职责（等价 v1 vendored AguiRequestProcessor 的租户/元数据回填逻辑）：
 * <ul>
 *   <li>委托官方 DefaultAgentResolver 完成智能体解析</li>
 *   <li>解析成功后回填 {@link AgentMetadataStore}（tenantId/tenantCode/threadId/toolProcessActive），
 *       供平台 Hook（WorkspaceHook/ChatLogHook/WebsocketHook）在运行期读取</li>
 *   <li>重建 {@link AgentContext}（异步线程 ThreadLocal 已丢，resume 等路径必须重建）</li>
 * </ul>
 *
 * @author huxuehao
 **/
@Slf4j
@RequiredArgsConstructor
public class ApboaAgentResolver implements AgentResolver {

    private final AgentResolver delegate;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Agent resolveAgent(String agentId, String threadId) {
        Agent agent = delegate.resolveAgent(agentId, threadId);

        // 回填运行期元数据（等价 v1 processor L88-101）
        if (AgentContext.getIfExists().isPresent()) {
            AgentContext context = AgentContext.get();
            if (AgentMetadataStore.get(agent.getAgentId(), "tenantId") == null) {
                AgentMetadataStore.put(agent.getAgentId(), "tenantId", context.getTenantId());
                AgentMetadataStore.put(agent.getAgentId(), "tenantCode", context.getTenantCode());
                AgentMetadataStore.put(agent.getAgentId(), "threadId", threadId);
            }
            AgentMetadataStore.put(agent.getAgentId(), "toolProcessActive", context.isToolProcessActive());
        } else {
            // 最小上下文缺失（如跨实例 resume）：从 chat_session 回填租户
            ResumeContext rc = loadResumeContext(threadId);
            if (rc != null) {
                TenantUtils.setCurrentTenant(rc.tenantId(), rc.tenantCode());
                AgentContext agentContext = new AgentContext();
                agentContext.setThreadId(threadId);
                agentContext.setTenantId(rc.tenantId());
                agentContext.setTenantCode(rc.tenantCode());
                AgentContext.init(agentContext);

                if (AgentMetadataStore.get(agent.getAgentId(), "tenantId") == null) {
                    AgentMetadataStore.put(agent.getAgentId(), "tenantId", rc.tenantId());
                    AgentMetadataStore.put(agent.getAgentId(), "tenantCode", rc.tenantCode());
                    AgentMetadataStore.put(agent.getAgentId(), "threadId", threadId);
                }
            }
        }

        return agent;
    }

    @Override
    public boolean hasMemory(String threadId) {
        return delegate.hasMemory(threadId);
    }

    /**
     * 从 chat_session 查 agentCode + 租户 + 用户（threadId 全局唯一，故不加租户过滤）。
     */
    public ResumeContext loadResumeContext(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return null;
        }
        // registry 注册 key 是 agent_code（不是数字 id），故 JOIN agent_definition 取 code
        String sql = String.format(
                "SELECT ad.agent_code AS agentCode, cs.tenant_id AS tenantId, t.code AS tenantCode, cs.user_id AS userId "
                        + "FROM %s cs JOIN tenant t ON t.id = cs.tenant_id "
                        + "JOIN %s ad ON ad.id = cs.agent_id "
                        + "WHERE cs.id = %s",
                TableConst.CHAT_SESSION, TableConst.AGENT, threadId);
        List<ResumeContext> list = jdbcTemplate.query(sql, (rs, n) -> new ResumeContext(
                rs.getString("agentCode"),
                rs.getLong("tenantId"),
                rs.getString("tenantCode"),
                rs.getLong("userId")));
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * resume 上下文（会话 → 智能体编码/租户/用户）
     */
    public record ResumeContext(String agentCode, Long tenantId, String tenantCode, Long userId) {}
}

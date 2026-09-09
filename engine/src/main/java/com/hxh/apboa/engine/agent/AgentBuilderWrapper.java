package com.hxh.apboa.engine.agent;

import com.hxh.apboa.common.entity.AgentDefinition;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.Builder;
import lombok.Data;

/**
 * 描述：Agent Builder包装类
 *
 * @author huxuehao
 **/
@Data
@Builder
public class AgentBuilderWrapper {
    AgentDefinition definition;
    HarnessAgent.Builder harnessAgentBuilder;
    A2aAgent.Builder a2aAgentBuilder;
}

package com.hxh.apboa.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.entity.AgentDiyPageConfig;

public interface AgentDiyPageConfigService extends IService<AgentDiyPageConfig> {
    AgentDiyPageConfig getByAgentId(Long agentId);

    JsonNode getPublishedConfig(Long agentId);

    AgentDiyPageConfig saveDraft(Long agentId, JsonNode draftConfig);

    Boolean publish(Long agentId);

    Boolean setEnabled(Long agentId, Boolean enabled);
}

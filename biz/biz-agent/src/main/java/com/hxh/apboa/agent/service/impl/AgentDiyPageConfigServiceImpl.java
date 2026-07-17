package com.hxh.apboa.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.agent.mapper.AgentDiyPageConfigMapper;
import com.hxh.apboa.agent.service.AgentDiyPageConfigService;
import com.hxh.apboa.common.entity.AgentDiyPageConfig;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AgentDiyPageConfigServiceImpl
        extends ServiceImpl<AgentDiyPageConfigMapper, AgentDiyPageConfig>
        implements AgentDiyPageConfigService {

    public AgentDiyPageConfigServiceImpl(AgentDiyPageConfigMapper mapper) {
        this.baseMapper = mapper;
    }

    @Override
    public AgentDiyPageConfig getByAgentId(Long agentId) {
        return baseMapper.selectOne(
                Wrappers.<AgentDiyPageConfig>lambdaQuery()
                        .eq(AgentDiyPageConfig::getAgentDefinitionId, agentId)
                        .last("LIMIT 1"));
    }

    @Override
    public JsonNode getPublishedConfig(Long agentId) {
        AgentDiyPageConfig config = getByAgentId(agentId);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return null;
        }
        return config.getPublishedConfig();
    }

    @Override
    public AgentDiyPageConfig saveDraft(Long agentId, JsonNode draftConfig) {
        AgentDiyPageConfig config = getByAgentId(agentId);
        if (config == null) {
            config = new AgentDiyPageConfig();
            config.setAgentDefinitionId(agentId);
            config.setDraftConfig(draftConfig);
            config.setEnabled(false);
            baseMapper.insert(config);
            return config;
        }
        config.setDraftConfig(draftConfig);
        baseMapper.updateById(config);
        return config;
    }

    @Override
    public Boolean publish(Long agentId) {
        AgentDiyPageConfig config = getByAgentId(agentId);
        if (config == null || config.getDraftConfig() == null) {
            return false;
        }
        config.setPublishedConfig(config.getDraftConfig().deepCopy());
        config.setPublishedAt(LocalDateTime.now());
        config.setEnabled(true);
        return baseMapper.updateById(config) > 0;
    }

    @Override
    public Boolean setEnabled(Long agentId, Boolean enabled) {
        AgentDiyPageConfig config = getByAgentId(agentId);
        if (config == null) {
            return false;
        }
        config.setEnabled(Boolean.TRUE.equals(enabled));
        return baseMapper.updateById(config) > 0;
    }
}

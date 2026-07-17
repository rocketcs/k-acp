package com.hxh.apboa.common.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 智能体 DIY 页面配置 VO。
 */
@Getter
@Setter
public class AgentDiyPageConfigVO {
    private Long id;
    private Long agentDefinitionId;
    private JsonNode draftConfig;
    private JsonNode publishedConfig;
    private LocalDateTime publishedAt;
    private Boolean enabled;
    private LocalDateTime updatedAt;
}

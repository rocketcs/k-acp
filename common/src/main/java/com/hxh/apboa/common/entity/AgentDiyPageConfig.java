package com.hxh.apboa.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.config.mybatis.JsonNodeTypeHandler;
import com.hxh.apboa.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 智能体 DIY 页面配置。
 */
@Getter
@Setter
@TableName(value = TableConst.AGENT_DIY_PAGE_CONFIG, autoResultMap = true)
public class AgentDiyPageConfig extends BaseTenantEntity {
    private Long agentDefinitionId;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode draftConfig;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode publishedConfig;

    private LocalDateTime publishedAt;
}

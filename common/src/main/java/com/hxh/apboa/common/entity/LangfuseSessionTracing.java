package com.hxh.apboa.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.config.mybatis.JsonNodeTypeHandler;
import com.hxh.apboa.common.consts.TableConst;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Langfuse session tracing result read model.
 */
@Getter
@Setter
@TableName(value = TableConst.LANGFUSE_SESSION_TRACING, autoResultMap = true)
public class LangfuseSessionTracing extends BaseTenantEntity {
    private String sessionId;
    private String projectId;
    private String langfuseBaseUrl;
    private String retrievalMethod;
    private String status;
    private Integer traceCount;
    private Integer seedObservationCount;
    private Integer fullObservationCount;
    private Integer scoreCount;
    private Integer qaPairCount;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode typeCountsJson;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode qaPairsJson;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode conversationJson;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode envelopeJson;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode llmAnalysisJson;

    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode warningsJson;

    private String sourceHash;
    private String firstObservationStartTime;
    private String lastObservationEndTime;
    private LocalDateTime processedAt;
}

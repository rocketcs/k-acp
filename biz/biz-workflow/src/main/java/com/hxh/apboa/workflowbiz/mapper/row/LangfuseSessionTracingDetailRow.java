package com.hxh.apboa.workflowbiz.mapper.row;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class LangfuseSessionTracingDetailRow {
    private String id;
    private String sessionId;
    private String projectId;
    private String userId;
    private String nickname;
    private String username;
    private String email;
    private String status;
    private JsonNode llmAnalysisJson;
    private Integer traceCount;
    private Integer seedObservationCount;
    private Integer fullObservationCount;
    private Integer scoreCount;
    private Integer qaPairCount;
    private JsonNode typeCountsJson;
    private JsonNode warningsJson;
    private String firstObservationStartTime;
    private String lastObservationEndTime;
    private LocalDateTime processedAt;
}

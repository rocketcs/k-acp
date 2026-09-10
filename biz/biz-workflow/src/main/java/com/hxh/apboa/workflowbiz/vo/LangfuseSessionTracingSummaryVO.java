package com.hxh.apboa.workflowbiz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class LangfuseSessionTracingSummaryVO {
    private Integer traceCount;
    private Integer seedObservationCount;
    private Integer fullObservationCount;
    private Integer scoreCount;
    private Integer qaPairCount;
    private Map<String, Integer> typeCounts;
    private List<String> warnings;
    private String firstObservationStartTime;
    private String lastObservationEndTime;
    private LocalDateTime processedAt;

    private Map<String, Integer> resultStatusCounts;
    private Map<String, Integer> cursorStatusCounts;
    private Integer staleProcessingCount;
    private LocalDateTime lastProcessedAt;
}

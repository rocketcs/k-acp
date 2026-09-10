package com.hxh.apboa.workflowbiz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class LangfuseSessionTracingListVO {
    private String id;
    private String userId;
    private String nickname;
    private String username;
    private String email;
    private String status;
    private Integer turnCount;
    private String firstUserQuestion;
    private String lastAgentAnswer;
    private Integer traceCount;
    private Integer fullObservationCount;
    private LocalDateTime processedAt;
}

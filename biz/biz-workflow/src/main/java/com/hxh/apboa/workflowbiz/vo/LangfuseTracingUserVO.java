package com.hxh.apboa.workflowbiz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class LangfuseTracingUserVO {
    private String userId;
    private String nickname;
    private String username;
    private String email;
    private Integer conversationCount;
    private LocalDateTime lastProcessedAt;
}

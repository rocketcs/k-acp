package com.hxh.apboa.workflowbiz.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LangfuseConversationTurnVO {
    private Integer turn;
    private String userQuestion;
    private String agentAnswer;
    private String userTimestamp;
    private String agentTimestamp;
}

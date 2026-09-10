package com.hxh.apboa.workflowbiz.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LangfuseSessionTracingDetailVO {
    private String id;
    private String sessionId;
    private String projectId;
    private LangfuseTracingUserVO user;
    private String status;
    private List<LangfuseConversationTurnVO> turns;
    private LangfuseSessionTracingSummaryVO traceSummary;
}

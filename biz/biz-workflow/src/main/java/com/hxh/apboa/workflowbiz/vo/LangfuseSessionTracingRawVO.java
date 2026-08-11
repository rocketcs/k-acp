package com.hxh.apboa.workflowbiz.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LangfuseSessionTracingRawVO {
    private JsonNode llmAnalysisJson;
    private JsonNode qaPairsJson;
    private JsonNode conversationJson;
    private JsonNode envelopeJson;
    private JsonNode warningsJson;
}

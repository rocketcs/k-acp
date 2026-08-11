package com.hxh.apboa.workflowbiz.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hxh.apboa.workflowbiz.vo.LangfuseConversationTurnVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingDetailVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingListVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingSummaryVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseTracingUserVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangfuseSessionTracingAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LangfuseSessionTracingAssembler assembler = new LangfuseSessionTracingAssembler();

    @Test
    void parsesAllTurnsInStoredOrder() throws Exception {
        JsonNode json = parse("""
            {"turns":[
              {"turn":1,"userQuestion":"问题一","agentAnswer":"回答一","userTimestamp":"u1","agentTimestamp":"a1"},
              {"turn":2,"userQuestion":"问题二","agentAnswer":"回答二","userTimestamp":"u2","agentTimestamp":"a2"}
            ]}
            """);

        List<LangfuseConversationTurnVO> turns = assembler.parseTurns(json);

        assertEquals(List.of("问题一", "问题二"), turns.stream()
            .map(LangfuseConversationTurnVO::getUserQuestion)
            .toList());
        assertEquals(List.of("回答一", "回答二"), turns.stream()
            .map(LangfuseConversationTurnVO::getAgentAnswer)
            .toList());
    }

    @Test
    void malformedOrMissingTurnsReturnEmptyList() throws Exception {
        assertTrue(assembler.parseTurns(null).isEmpty());
        assertTrue(assembler.parseTurns(parse("{}")).isEmpty());
        assertTrue(assembler.parseTurns(parse("{\"turns\":{}}" )).isEmpty());
        assertTrue(assembler.parseTurns(parse("{\"turns\":null}" )).isEmpty());
        assertTrue(assembler.parseTurns(parse("{\"turns\":\"unexpected\"}" )).isEmpty());
    }

    @Test
    void anyNonObjectTurnMakesWholePayloadInvalid() throws Exception {
        assertTrue(assembler.parseTurns(parse("{\"turns\":[{\"turn\":1},null]}" )).isEmpty());
        assertTrue(assembler.parseTurns(parse("{\"turns\":[{\"turn\":1},\"unexpected\"]}" )).isEmpty());
        assertTrue(assembler.parseTurns(parse("{\"turns\":[{\"turn\":1},42]}" )).isEmpty());
        assertTrue(assembler.parseTurns(parse("{\"turns\":[{\"turn\":1},true]}" )).isEmpty());
        assertTrue(assembler.parseTurns(parse("{\"turns\":[{\"turn\":1},[]]}" )).isEmpty());
    }

    @Test
    void missingQuestionOrAnswerIsKeptAsEmptyText() throws Exception {
        List<LangfuseConversationTurnVO> turns = assembler.parseTurns(
            parse("{\"turns\":[{\"turn\":1}]}") );

        assertEquals("", turns.getFirst().getUserQuestion());
        assertEquals("", turns.getFirst().getAgentAnswer());
        assertNull(turns.getFirst().getUserTimestamp());
        assertNull(turns.getFirst().getAgentTimestamp());
    }

    @Test
    void missingTurnUsesStoredArrayIndex() throws Exception {
        List<LangfuseConversationTurnVO> turns = assembler.parseTurns(parse("""
            {"turns":[{}, {"turn":9, "userQuestion":"问题"}]}
            """));

        assertEquals(1, turns.get(0).getTurn());
        assertEquals(9, turns.get(1).getTurn());
        assertEquals("问题", turns.get(1).getUserQuestion());
    }

    @Test
    void nonTextualTimestampsAreNull() throws Exception {
        List<LangfuseConversationTurnVO> turns = assembler.parseTurns(parse("""
            {"turns":[
              {"userTimestamp":42,"agentTimestamp":42},
              {"userTimestamp":true,"agentTimestamp":false},
              {"userTimestamp":{"value":"unexpected"},"agentTimestamp":{"value":"unexpected"}}
            ]}
            """));

        assertEquals(3, turns.size());
        for (LangfuseConversationTurnVO turn : turns) {
            assertNull(turn.getUserTimestamp());
            assertNull(turn.getAgentTimestamp());
        }
    }

    @Test
    void summaryKeepsNullTimestampFieldsInJson() throws Exception {
        JsonNode summary = parse(objectMapper.writeValueAsString(new LangfuseSessionTracingSummaryVO()));

        assertTrue(summary.has("firstObservationStartTime"));
        assertTrue(summary.get("firstObservationStartTime").isNull());
        assertTrue(summary.has("lastObservationEndTime"));
        assertTrue(summary.get("lastObservationEndTime").isNull());
        assertTrue(summary.has("processedAt"));
        assertTrue(summary.get("processedAt").isNull());
        assertTrue(summary.has("lastProcessedAt"));
        assertTrue(summary.get("lastProcessedAt").isNull());
    }

    @Test
    void parsesObjectCountsOnlyFromAnObjectNode() throws Exception {
        assertEquals(
            Map.of("AGENT", 1, "TOOL", 2),
            assembler.parseObjectCounts(parse("{\"AGENT\":1,\"TOOL\":2}")));
        assertTrue(assembler.parseObjectCounts(null).isEmpty());
        assertTrue(assembler.parseObjectCounts(parse("{}")).isEmpty());
        assertTrue(assembler.parseObjectCounts(parse("[]")).isEmpty());
        assertTrue(assembler.parseObjectCounts(parse("\"unexpected\"" )).isEmpty());
    }

    @Test
    void parsesWarningsOnlyFromAnArrayNode() throws Exception {
        assertEquals(
            List.of("pagination_truncated", "trace_fetch_failed"),
            assembler.parseWarnings(parse("[\"pagination_truncated\",\"trace_fetch_failed\"]")));
        assertTrue(assembler.parseWarnings(null).isEmpty());
        assertTrue(assembler.parseWarnings(parse("{}")).isEmpty());
        assertTrue(assembler.parseWarnings(parse("{\"warning\":\"unexpected\"}" )).isEmpty());
        assertTrue(assembler.parseWarnings(parse("42" )).isEmpty());
    }

    @Test
    void exposedIdentifiersAreStrings() throws Exception {
        assertEquals(String.class, LangfuseTracingUserVO.class.getDeclaredField("userId").getType());
        assertEquals(String.class, LangfuseSessionTracingListVO.class.getDeclaredField("id").getType());
        assertEquals(String.class, LangfuseSessionTracingListVO.class.getDeclaredField("userId").getType());
        assertEquals(String.class, LangfuseSessionTracingDetailVO.class.getDeclaredField("id").getType());
        assertEquals(String.class, LangfuseSessionTracingDetailVO.class.getDeclaredField("sessionId").getType());
        assertEquals(String.class, LangfuseSessionTracingDetailVO.class.getDeclaredField("projectId").getType());
    }

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}

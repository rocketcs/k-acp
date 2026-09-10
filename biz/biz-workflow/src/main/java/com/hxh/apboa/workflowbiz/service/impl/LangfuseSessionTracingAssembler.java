package com.hxh.apboa.workflowbiz.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.workflowbiz.vo.LangfuseConversationTurnVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LangfuseSessionTracingAssembler {

    public List<LangfuseConversationTurnVO> parseTurns(JsonNode root) {
        JsonNode turnsNode = objectField(root, "turns");
        if (turnsNode == null || !turnsNode.isArray()) {
            return List.of();
        }

        for (JsonNode turnNode : turnsNode) {
            if (turnNode == null || !turnNode.isObject()) {
                return List.of();
            }
        }

        List<LangfuseConversationTurnVO> turns = new ArrayList<>(turnsNode.size());
        int index = 0;
        for (JsonNode turnNode : turnsNode) {
            LangfuseConversationTurnVO turn = new LangfuseConversationTurnVO();
            turn.setTurn(readTurnNumber(turnNode, index + 1));
            turn.setUserQuestion(readTextOrEmpty(turnNode, "userQuestion"));
            turn.setAgentAnswer(readTextOrEmpty(turnNode, "agentAnswer"));
            turn.setUserTimestamp(readTextOrNull(turnNode, "userTimestamp"));
            turn.setAgentTimestamp(readTextOrNull(turnNode, "agentTimestamp"));
            turns.add(turn);
            index++;
        }
        return turns;
    }

    public Map<String, Integer> parseObjectCounts(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Map.of();
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            if (entry.getValue().isIntegralNumber() && entry.getValue().canConvertToInt()) {
                counts.put(entry.getKey(), entry.getValue().intValue());
            }
        });
        return counts;
    }

    public List<String> parseWarnings(JsonNode root) {
        if (root == null || !root.isArray()) {
            return List.of();
        }

        List<String> warnings = new ArrayList<>(root.size());
        for (JsonNode warningNode : root) {
            if (warningNode.isTextual()) {
                warnings.add(warningNode.textValue());
            }
        }
        return warnings;
    }

    private JsonNode objectField(JsonNode root, String fieldName) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode field = root.get(fieldName);
        return field == null || field.isNull() ? null : field;
    }

    private int readTurnNumber(JsonNode turnNode, int fallback) {
        if (turnNode == null || !turnNode.isObject()) {
            return fallback;
        }
        JsonNode turn = turnNode.get("turn");
        return turn != null && turn.isIntegralNumber() && turn.canConvertToInt()
            ? turn.intValue()
            : fallback;
    }

    private String readTextOrEmpty(JsonNode node, String fieldName) {
        String value = readTextOrNull(node, fieldName);
        return value == null ? "" : value;
    }

    private String readTextOrNull(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        return value == null || !value.isTextual() ? null : value.textValue();
    }
}

package com.hxh.apboa.mcp.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolServiceImplTest {

    @Test
    void persistsMcpToolAsAnObjectThatCanBeReadBackByTheRuntime() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("question", Map.of("type", "string")),
                List.of("question"),
                false,
                Map.of(),
                Map.of());
        McpSchema.Tool source = new McpSchema.Tool(
                "semantic_context",
                "Semantic context",
                "Returns the semantic context for a question.",
                inputSchema,
                Map.of("type", "object"),
                null,
                Map.of());

        JsonNode persisted = McpToolServiceImpl.canonicalToolSchema(objectMapper, source);
        McpSchema.Tool restored = objectMapper.treeToValue(persisted, McpSchema.Tool.class);

        assertTrue(persisted.isObject());
        assertEquals("semantic_context", restored.name());
        assertEquals("object", restored.inputSchema().type());
        assertEquals(List.of("question"), restored.inputSchema().required());
    }
}

package com.hxh.apboa.mcp.config;

import com.hxh.apboa.common.entity.McpServer;
import com.hxh.apboa.common.enums.McpMode;
import com.hxh.apboa.common.enums.McpProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 描述：MCP客户端配置接口
 *
 * @author huxuehao
 **/
public interface McpClientConfig {
    McpClientWrapper getMcpClient(McpServer mcpServer);

    McpProtocol protocol();

    default McpClientWrapper buildClient(McpClientBuilder builder, McpMode mode) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        // JDK HTTP callbacks can leave Reactor workers with a loader that cannot see Boot's nested SPI providers.
        thread.setContextClassLoader(McpClientConfig.class.getClassLoader());
        try {
            return mode == McpMode.SYNC ? builder.buildSync() : builder.buildAsync().block();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    /**
     * JsonNode 转 Map
     */
    default Map<String, String> jsonNode2Map(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isEmpty()) {
            return Map.of();
        }

        HashMap<String, String> map = new HashMap<>();
        for (JsonNode node : jsonNode) {
            if (node.has("key") && node.has("value")) {
                map.put(node.get("key").asText(), node.get("value").asText());
            }
        }

        return map;
    }
}

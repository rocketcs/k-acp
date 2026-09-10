package com.kacp.nanwang;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 描述：MCP 服务端装配（Streamable HTTP @ /mcp）
 */
@Configuration
public class McpTransportConfig {

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public HttpServletStreamableServerTransportProvider transportProvider(McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public McpSyncServer mcpSyncServer(NanwangMcpServer tools,
                                       HttpServletStreamableServerTransportProvider transportProvider,
                                       McpJsonMapper jsonMapper) {
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("nanwang-semantic", "0.1.0")
                .instructions("南网问数语义服务：只处理 power_inventory 数据集的受治理问数。"
                        + "协议：semantic_context → query_preflight(allowed/warning 才可 query) → query → evidence_subgraph；"
                        + "指标命中时用 run_metric。未建模问题必须拒答并引导登记。")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(tools.buildToolSpecifications())
                .build();
        return server;
    }
}

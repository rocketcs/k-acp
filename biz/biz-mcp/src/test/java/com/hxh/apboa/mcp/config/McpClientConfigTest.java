package com.hxh.apboa.mcp.config;

import com.hxh.apboa.common.enums.McpMode;
import com.hxh.apboa.mcp.config.impl.HttpMcpClientConfig;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpClientConfigTest {
    private final McpClientConfig config = new HttpMcpClientConfig();

    @ParameterizedTest
    @EnumSource(McpMode.class)
    void discoversProvidersWithAnApplicationLoaderAndRestoresCaller(McpMode mode) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader restricted = new ClassLoader(null) { };
        thread.setContextClassLoader(restricted);
        try {
            // createDefault bypasses the SDK cache, so earlier tests cannot hide broken SPI discovery.
            IllegalStateException error = assertThrows(IllegalStateException.class, McpJsonMapper::createDefault);
            assertEquals("No default McpJsonMapper implementation found", error.getMessage());
            McpClientBuilder builder = McpClientBuilder.create("loader-test")
                    .streamableHttpTransport("http://127.0.0.1:1/mcp")
                    .customizeStreamableHttpClient(http -> {
                        assertSame(McpClientConfig.class.getClassLoader(), thread.getContextClassLoader());
                        assertNotNull(McpJsonMapper.createDefault());
                    });
            try (McpClientWrapper client = config.buildClient(builder, mode)) {
                assertNotNull(client);
                assertSame(restricted, thread.getContextClassLoader());
            }
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @ParameterizedTest
    @EnumSource(McpMode.class)
    void restoresCallerWhenConstructionFails(McpMode mode) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader restricted = new ClassLoader(null) { };
        IllegalStateException failure = new IllegalStateException("construction failed");
        McpClientBuilder builder = McpClientBuilder.create("loader-test")
                .streamableHttpTransport("http://127.0.0.1:1/mcp")
                .customizeStreamableHttpClient(http -> {
                    assertSame(McpClientConfig.class.getClassLoader(), thread.getContextClassLoader());
                    throw failure;
                });
        thread.setContextClassLoader(restricted);
        try {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> config.buildClient(builder, mode)));
            assertSame(restricted, thread.getContextClassLoader());
        } finally {
            thread.setContextClassLoader(original);
        }
    }
}

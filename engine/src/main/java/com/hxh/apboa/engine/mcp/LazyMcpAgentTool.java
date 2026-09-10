package com.hxh.apboa.engine.mcp;

import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.mcp.service.McpRuntimeDegradeService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 基于缓存工具目录注册的懒加载 MCP 工具。
 */
public class LazyMcpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(LazyMcpAgentTool.class);

    private final RuntimeDegradeContext degradeContext;
    private final McpSchema.Tool toolSchema;
    private final Supplier<Mono<McpClientWrapper>> initializedClientSupplier;
    private final McpRuntimeDegradeService mcpRuntimeDegradeService;
    private final Runnable invalidateClient;
    private final Map<String, Object> parameters;
    private final Map<String, Object> outputSchema;

    public LazyMcpAgentTool(RuntimeDegradeContext degradeContext,
                            McpSchema.Tool toolSchema,
                            Supplier<Mono<McpClientWrapper>> initializedClientSupplier,
                            McpRuntimeDegradeService mcpRuntimeDegradeService,
                            Runnable invalidateClient) {
        this.degradeContext = degradeContext;
        this.toolSchema = toolSchema;
        this.initializedClientSupplier = initializedClientSupplier;
        this.mcpRuntimeDegradeService = mcpRuntimeDegradeService;
        this.invalidateClient = invalidateClient;
        this.parameters = McpTool.convertMcpSchemaToParameters(toolSchema.inputSchema(), Set.of());
        this.outputSchema = toolSchema.outputSchema() != null
                ? new HashMap<>(toolSchema.outputSchema())
                : null;
    }

    @Override
    public String getName() {
        return toolSchema.name();
    }

    @Override
    public String getDescription() {
        return toolSchema.description() != null ? toolSchema.description() : "";
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return outputSchema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentContext agentContext = param.getContext().get(AgentContext.class);
        if (agentContext == null) {
            return Mono.just(ToolResultBlock.error(unavailableMessage(new Exception("AgentContext is null"))));
        }

        // 保存租户信息到局部变量
        Long tenantId = agentContext.getTenantId();
        String tenantCode = agentContext.getTenantCode();

        // 使用 defer 确保整个执行链都在租户上下文中
        return Mono.defer(() -> {
            // 设置租户上下文
            TenantUtils.setCurrentTenant(tenantId, tenantCode);

            return Mono.defer(() -> initializedClientSupplier.get()
                            .flatMap(client -> client.callTool(getName(), param.getInput())))
                    // Streamable-HTTP sessions can be invalidated by the server while the
                    // cached client remains alive. Drop that client and retry once so a
                    // transient MCP session loss does not strand the whole graph step.
                    .doOnError(error -> {
                        if (isTransportFailure(error)) {
                            try {
                                invalidateClient.run();
                            } catch (Exception invalidateError) {
                                log.debug("Failed to invalidate stale MCP client '{}': {}",
                                        degradeContext.serverName(), invalidateError.getMessage());
                            }
                        }
                    })
                    .retryWhen(Retry.max(1)
                            .filter(LazyMcpAgentTool::isTransportFailure)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure())
                            .doBeforeRetry(signal -> log.info(
                                    "Retrying MCP tool '{}' after transport failure from '{}'",
                                    getName(), degradeContext.serverName())))
                    .doOnSuccess(result -> {
                        mcpRuntimeDegradeService.recordSuccess(
                                degradeContext.serverId(),
                                degradeContext.activationRevision(),
                                degradeContext.configHash(),
                                degradeContext.runtimeFailThreshold(),
                                tenantId);
                    })
                    .map(McpContentConverter::convertCallToolResult)
                    .onErrorResume(e -> {
                        mcpRuntimeDegradeService.recordFailure(
                                degradeContext.serverId(),
                                degradeContext.activationRevision(),
                                degradeContext.configHash(),
                                degradeContext.runtimeFailThreshold(),
                                e,
                                tenantId);
                        log.warn("MCP tool '{}' from '{}' unavailable: {}",
                                getName(), degradeContext.serverName(), e.getMessage());
                        return Mono.just(ToolResultBlock.error(unavailableMessage(e)));
                    })
                    .doFinally(signalType -> TenantUtils.clear());
        });
    }

    private static boolean isTransportFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (className.contains("transport")
                    || className.contains("sessionnotfound")
                    || className.contains("connection")
                    || message.contains("session with server terminated")
                    || message.contains("session not found")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("transport")
                    || message.contains("channel is closed")
                    || message.contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String unavailableMessage(Throwable e) {
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "MCP service '" + degradeContext.serverName() + "' is unavailable. Tool '" + getName()
                + "' cannot be used right now. Reason: " + reason;
    }

    /**
     * 运行时自动降级上下文快照。
     */
    public record RuntimeDegradeContext(Long serverId,
                                        String serverName,
                                        Long activationRevision,
                                        String configHash,
                                        Integer runtimeFailThreshold) {
    }
}

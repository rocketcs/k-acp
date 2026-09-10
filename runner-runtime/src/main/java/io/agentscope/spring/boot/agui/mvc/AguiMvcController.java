package io.agentscope.spring.boot.agui.mvc;

import com.hxh.apboa.engine.agui.AgentContext;
import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.runtime.agui.ApboaAgentResolver;
import com.hxh.apboa.runtime.agui.ApboaAguiHitlService;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

public class AguiMvcController {

    private static final Logger logger = LoggerFactory.getLogger(AguiMvcController.class);

    private static final String DEFAULT_AGENT_ID_HEADER = "X-Agent-Id";

    private final AguiRequestProcessor processor;
    private final AguiEventEncoder encoder;
    private final String agentIdHeader;
    private final long sseTimeout;
    private final ExecutorService executorService;
    private final ThreadSessionManager sessionManager;
    private final RunTracker runTracker;
    private final ApboaAguiHitlService hitlService;
    private final io.agentscope.core.state.AgentStateStore stateStore;

    private AguiMvcController(Builder builder) {
        this.processor =
                builder.processor != null
                        ? builder.processor
                        : AguiRequestProcessor.builder()
                                .agentResolver(
                                        new ApboaAgentResolver(
                                                DefaultAgentResolver.builder()
                                                        .registry(builder.registry)
                                                        .sessionManager(builder.sessionManager)
                                                        .serverSideMemory(builder.serverSideMemory)
                                                        .build(),
                                                builder.jdbcTemplate))
                                        .config(
                                                builder.config != null
                                                        ? builder.config
                                                        : AguiAdapterConfig.defaultConfig())
                                        .build();
        this.encoder = new AguiEventEncoder();
        this.agentIdHeader =
                builder.agentIdHeader != null ? builder.agentIdHeader : DEFAULT_AGENT_ID_HEADER;
        this.sseTimeout = builder.sseTimeout > 0 ? builder.sseTimeout : 600000L;
        this.executorService = Executors.newCachedThreadPool();
        this.sessionManager = builder.sessionManager;
        this.runTracker = builder.runTracker != null ? builder.runTracker : new RunTracker(this.encoder);
        this.hitlService = builder.hitlService;
        this.stateStore = builder.stateStore;
    }

    /**
     * Handle an AG-UI run request.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handle(RunAgentInput input, String headerAgentId) {
        return handleInternal(input, headerAgentId, null);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handleWithAgentId(
            RunAgentInput input, String headerAgentId, String pathAgentId) {
        return handleInternal(input, headerAgentId, pathAgentId);
    }

    private SseEmitter handleInternal(
            RunAgentInput input, String headerAgentId, String pathAgentId) {
        SseEmitter emitter = new SseEmitter(sseTimeout);
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        executorService.submit(
                () -> {
                    try {
                        // 初始化上下文
                        AgentContext.init(input, threadId);

                        // v1 语义保留：未开启记忆时，新用户消息 = 全新会话（清空状态存储）。
                        // UIP（用户交互协议回复）与 tool 消息（HITL 恢复）不触发清空。
                        if (stateStore != null && !isMemoryActive(input) && isFreshUserRun(input)) {
                            try {
                                stateStore.delete(resolveUserId(threadId), threadId);
                            } catch (Exception ex) {
                                logger.warn("清空会话状态失败 threadId={}: {}", threadId, ex.getMessage());
                            }
                        }

                        // Process request - returns both agent and event stream
                        AguiRequestProcessor.ProcessResult result =
                                processor.process(input, headerAgentId, pathAgentId, buildRuntimeContext(threadId));

                        // 注册 RunTracker + 订阅 Flux 管道（与 resume 共用）
                        subscribeAndTrack(emitter, threadId, runId, result);

                    } catch (AguiException.AgentNotFoundException e) {
                        logger.error("Agent not found", e);

                        AgentContext.clean();

                        sendErrorAndComplete(emitter, threadId, runId, safeErrorMessage(e.getMessage()));
                    } catch (Exception e) {
                        logger.error("Error processing AG-UI request", e);

                        AgentContext.clean();

                        sendErrorAndComplete(emitter, threadId, runId, safeErrorMessage(e.getMessage()));
                    } finally {
                        // 在同一个线程中清理上下文
                        AgentContext.clean();
                    }
                });

        return emitter;
    }

    private boolean isMemoryActive(RunAgentInput input) {
        return Boolean.TRUE.equals(input.getForwardedProp("memoryActive"));
    }

    private boolean isFreshUserRun(RunAgentInput input) {
        List<io.agentscope.core.agui.model.AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        io.agentscope.core.agui.model.AguiMessage first = messages.getFirst();
        if (!"user".equalsIgnoreCase(first.getRole())) {
            return false;
        }
        String messageId = first.getId();
        boolean isUIP = messageId != null && messageId.toLowerCase().startsWith("uip");
        return !isUIP;
    }

    private String resolveUserId(String threadId) {
        return AgentContext.getIfExists()
                .map(ctx -> ctx.getUserInfo() != null ? String.valueOf(ctx.getUserInfo().getId()) : "anonymous")
                .orElse("anonymous");
    }

    /**
     * 构建运行期 RuntimeContext（会话分区 key + 平台元数据 extras）。
     */
    private RuntimeContext buildRuntimeContext(String threadId) {
        String userId = AgentContext.getIfExists()
                .map(ctx -> ctx.getUserInfo() != null ? String.valueOf(ctx.getUserInfo().getId()) : "anonymous")
                .orElse("anonymous");
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(threadId)
                .userId(userId)
                .build();
        AgentContext.getIfExists().ifPresent(ac -> {
            ctx.put("threadId", threadId);
            ctx.put("tenantId", ac.getTenantId());
            ctx.put("tenantCode", ac.getTenantCode());
        });
        return ctx;
    }

    /**
     * HITL resume：根据用户确认决策恢复暂停的 agent。
     *
     * <p>与 {@link #handleInternal} 共用 {@link #subscribeAndTrack} 的 SSE 管道，差异在于：
     * <ul>
     *   <li>resume 无 {@link RunAgentInput}，需手动初始化最小 {@link AgentContext}
     *       （threadId/runId/memoryActive）。租户故意留空——由 {@code processor.resume} 内
     *       {@code resolveAgent} 重建 agent 时经 {@code setTenantInfo} 回填进该上下文。
     *       若此处不 init，重建必然在 {@code AgentContext.get()} 抛
     *       "AgentContext not initialized"（异步线程 ThreadLocal 已丢）。</li>
     *   <li>事件流由 {@link AguiRequestProcessor#resume} 产出。</li>
     * </ul>
     *
     * @param threadId 会话 ID（暂停态的 key）
     * @param decisions 逐工具决策（toolUseId/name/approved）；空表示全部允许
     * @param memoryActive 是否开启长期记忆（决定 resume 完成后保留还是删除 session）
     * @return SseEmitter 续接暂停前的事件流
     */
    public SseEmitter handleResume(
            String threadId,
            List<ApboaAguiHitlService.ResumeDecision> decisions,
            boolean memoryActive) {
        SseEmitter emitter = new SseEmitter(sseTimeout);
        String runId = UUID.randomUUID().toString();

        executorService.submit(
                () -> {
                    try {
                        // resume 无 RunAgentInput：手动初始化最小上下文；租户留待 resolveAgent 重建时回填。
                        AgentContext agentContext = new AgentContext();
                        agentContext.setThreadId(threadId);
                        agentContext.setRunId(runId);
                        agentContext.setMemoryActive(memoryActive);
                        AgentContext.init(agentContext);

                        com.hxh.apboa.runtime.agui.ApboaAguiHitlService.AguiRequestProcessResult result =
                                hitlService.resume(threadId, decisions, memoryActive);
                        AguiRequestProcessor.ProcessResult procResult =
                                new AguiRequestProcessor.ProcessResult(result.agent(), result.events());

                        // 注册 RunTracker + 订阅 Flux 管道（与 run 共用）
                        subscribeAndTrack(emitter, threadId, runId, procResult);

                    } catch (Exception e) {
                        logger.error("Error resuming AG-UI request: {}", e.getMessage());

                        AgentContext.clean();

                        sendErrorAndComplete(emitter, threadId, runId, e.getMessage());
                    } finally {
                        // 在同一个线程中清理上下文
                        AgentContext.clean();
                    }
                });

        return emitter;
    }

    /**
     * HITL 刷新恢复：从持久 Session 的暂停态读取「待确认工具」列表（同步返回 JSON，非 SSE）。
     *
     * <p>用于前端刷新页面 / 重新进入会话时重建确认 UI——此时前端内存态（pendingConfirms）已丢，
     * 而暂停态会话在 {@link RunTracker} 里 {@code completed=true}（不在 active-runs），且 RunTracker
     * 是内存态跨重启/实例会丢，故不能靠 reconnect 回放，必须从持久 Session 重建。
     *
     * <p>与 {@link #handleResume} 一样需最小 {@link AgentContext}（resolveAgent 重建时经 setTenantInfo 回填）；
     * 差别在于本方法在 Tomcat 请求线程<b>同步</b>执行（只读、快速），不走 executorService。
     *
     * @param threadId 会话 ID
     * @return 待确认工具 [{toolUseId,name,input}]；无暂停态返回空列表
     */
    public List<Map<String, Object>> getPendingConfirms(String threadId) {
        try {
            return hitlService.getPendingConfirms(threadId);
        } catch (Exception e) {
            logger.warn("获取待确认列表失败 threadId={}: {}", threadId, e.getMessage());
            return List.of();
        } finally {
            AgentContext.clean();
        }
    }

    /**
     * 注册 RunTracker 并订阅 Agent 事件流，将事件推入 RunTracker 缓冲（run / resume 共用）。
     *
     * <p>SSE 回调仅清理连接、不中断 Agent；用 onErrorResume 把 publish 侧异常转为正常完成，
     * 避免 Flux 被取消。不用 doFinally —— cancel 信号（SSE 断连）不应触发 markCompleted，
     * 只有 agent 正常结束或异常→完成时才标记完成。
     *
     * @param emitter 初始 SSE 连接
     * @param threadId 会话 ID
     * @param runId 运行 ID（仅用于日志）
     * @param result processor 产出的 agent + 事件流
     */
    private void subscribeAndTrack(
            SseEmitter emitter,
            String threadId,
            String runId,
            AguiRequestProcessor.ProcessResult result) {
        // 注册到 RunTracker（在订阅前完成，保证事件不丢失）
        runTracker.registerRun(threadId, result.agent(), null);

        // SSE 回调不再中断 Agent，仅清理 SSE 连接
        emitter.onCompletion(
                () -> {
                    logger.debug("SSE connection completed for run {}", runId);
                    runTracker.removeEmitter(threadId, emitter);
                });
        emitter.onTimeout(
                () -> {
                    logger.info("SSE connection timed out for run {}", runId);
                    runTracker.removeEmitter(threadId, emitter);
                });
        emitter.onError(
                (ex) -> {
                    logger.info("SSE connection error for run {}: {}", runId, ex.getMessage());
                    runTracker.removeEmitter(threadId, emitter);
                });

        // 初始 emitter 加入 RunTracker 的 subscriber 列表
        runTracker.reconnect(threadId, emitter);

        // 订阅 Flux 管道：事件推入 RunTracker，而非直接发送 SSE
        Disposable subscription =
                result.events()
                        .onErrorResume(e -> {
                            logger.warn(
                                    "Agent flux error for thread {}: {} -> {}",
                                    threadId,
                                    e != null ? e.getClass().getSimpleName() : "null",
                                    e != null ? e.getMessage() : "null");
                            return Flux.empty();
                        })
                        .subscribe(
                                event -> runTracker.pushEvent(threadId, event),
                                error -> {
                                    // 理论上不应到达这里（已被 onErrorResume 拦截）
                                    logger.error(
                                            "Residual flux error for thread {}: {}",
                                            threadId,
                                            error != null ? error.getMessage() : "null");
                                    runTracker.markCompleted(threadId);
                                },
                                () -> {
                                    logger.debug(
                                            "AG-UI run stream completed for thread {}", threadId);
                                    runTracker.markCompleted(threadId);
                                });

        // 更新 subscription 引用（不替换 ActiveRun，保住 buffer）
        runTracker.updateSubscription(threadId, subscription);
    }

    private void sendErrorAndComplete(
            SseEmitter emitter, String threadId, String runId, String errorMessage) {
        try {
            String errorJson =
                    encoder.encodeToJson(
                            new AguiEvent.Raw(threadId, runId, Map.of("error", errorMessage)));
            String finishJson = encoder.encodeToJson(new AguiEvent.RunFinished(threadId, runId));
            emitter.send(SseEmitter.event().data(errorJson, MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().data(finishJson, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            logger.debug("Failed to send error event: {}", e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                logger.debug("Failed to complete emitter with error: {}", ex.getMessage());
            }
        }
    }

    static String safeErrorMessage(String errorMessage) {
        return errorMessage == null || errorMessage.isBlank()
                ? "An unexpected error occurred"
                : errorMessage;
    }

    /**
     * Get the agent ID header name.
     *
     * @return The header name
     */
    public String getAgentIdHeader() {
        return agentIdHeader;
    }

    /**
     * SSE 重连：创建新的 SseEmitter 并回放缓冲事件。
     *
     * @param threadId 会话 ID
     * @return SseEmitter
     */
    public SseEmitter reconnect(String threadId) {
        SseEmitter emitter = new SseEmitter(sseTimeout);
        emitter.onCompletion(() -> runTracker.removeEmitter(threadId, emitter));
        emitter.onTimeout(() -> runTracker.removeEmitter(threadId, emitter));
        emitter.onError(ex -> runTracker.removeEmitter(threadId, emitter));
        runTracker.reconnect(threadId, emitter);
        return emitter;
    }

    /**
     * 获取指定会话的运行状态。
     *
     * @param threadId 会话 ID
     * @return 是否正在运行
     */
    public boolean getStatus(String threadId) {
        return runTracker.getStatus(threadId);
    }

    /**
     * 强制停止指定会话的智能体。
     *
     * @param threadId 会话 ID
     */
    public void stop(String threadId) {
        runTracker.stopRun(threadId);
    }

    /**
     * 获取所有活跃运行的 threadId 列表。
     *
     * @return 活跃会话 ID 集合
     */
    public java.util.Set<String> getActiveRuns() {
        return runTracker.getActiveRuns();
    }

    /**
     * 获取 RunTracker（供外围 Bean 直接调用）。
     *
     * @return RunTracker 实例
     */
    public RunTracker getRunTracker() {
        return runTracker;
    }

    /**
     * Creates a new builder for AguiMvcController.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for AguiMvcController. */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private AguiAdapterConfig config;
        private boolean serverSideMemory = false;
        private String agentIdHeader;
        private long sseTimeout = 600000L;
        private JdbcTemplate jdbcTemplate;
        private RunTracker runTracker;
        private ApboaAguiHitlService hitlService;
        private AguiRequestProcessor processor;
        private io.agentscope.core.state.AgentStateStore stateStore;

        /**
         * Set the agent registry.
         *
         * @param registry The agent registry
         * @return This builder
         */
        public Builder agentRegistry(AguiAgentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Set the thread session manager for server-side memory support.
         *
         * @param sessionManager The session manager
         * @return This builder
         */
        public Builder sessionManager(ThreadSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /**
         * Enable or disable server-side memory management.
         *
         * @param enabled Whether to enable server-side memory
         * @return This builder
         */
        public Builder serverSideMemory(boolean enabled) {
            this.serverSideMemory = enabled;
            return this;
        }

        /**
         * Set the adapter configuration.
         *
         * @param config The adapter configuration
         * @return This builder
         */
        public Builder config(AguiAdapterConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Set the HTTP header name to read agent ID from.
         *
         * @param agentIdHeader The header name (default: X-Agent-Id)
         * @return This builder
         */
        public Builder agentIdHeader(String agentIdHeader) {
            this.agentIdHeader = agentIdHeader;
            return this;
        }

        /**
         * Set the SSE timeout in milliseconds.
         *
         * @param sseTimeout The timeout value
         * @return This builder
         */
        public Builder sseTimeout(long sseTimeout) {
            this.sseTimeout = sseTimeout;
            return this;
        }

        /**
         * Set the AgentStateStore (v2 统一状态存储，用于 memoryActive=false 时每轮清空会话).
         *
         * @param stateStore The state store
         * @return This builder
         */
        public Builder stateStore(io.agentscope.core.state.AgentStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * Set a prebuilt processor (overrides registry-based construction).
         *
         * @param processor The request processor
         * @return This builder
         */
        public Builder processor(AguiRequestProcessor processor) {
            this.processor = processor;
            return this;
        }

        /**
         * Set the HITL service (resume / pending confirms).
         *
         * @param hitlService The HITL service
         * @return This builder
         */
        public Builder hitlService(ApboaAguiHitlService hitlService) {
            this.hitlService = hitlService;
            return this;
        }

        /**
         * Set the jdbcTemplate (used by ApboaAgentResolver for tenant lookup).
         *
         * @param jdbcTemplate jdbcTemplate
         * @return This builder
         */
        public Builder jdbcTemplate(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            return this;
        }

        /**
         * Set the RunTracker for managing active runs.
         *
         * @param runTracker The RunTracker instance
         * @return This builder
         */
        public Builder runTracker(RunTracker runTracker) {
            this.runTracker = runTracker;
            return this;
        }

        /**
         * Build the controller.
         *
         * @return The built controller
         * @throws IllegalStateException if registry is not set
         */
        public AguiMvcController build() {
            if (registry == null) {
                throw new IllegalStateException("Agent registry must be set");
            }
            return new AguiMvcController(this);
        }
    }
}

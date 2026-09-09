package com.hxh.apboa.runtime;

import com.hxh.apboa.engine.agent.AgentStateStoreFactory;
import com.hxh.apboa.runtime.agui.ApboaAguiHitlService;
import com.hxh.apboa.runtime.agui.ApboaAgentResolver;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.agui.mvc.AguiRestController;
import com.hxh.apboa.agent.service.ChatSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AgentScope 2.0 AGUI 装配配置
 * <p>
 * v2 变更（相对 v1 的 MysqlSession 方案）：
 * <ul>
 *   <li>会话持久化统一为 {@link AgentStateStore}（由 {@link AgentStateStoreFactory} 提供，
 *       内部使用 {@code MysqlAgentStateStore}，按 (userId, sessionId) 分区）</li>
 *   <li>{@code Session}/{@code MysqlSession} 接口在 2.0 已删除</li>
 *   <li>AGUI 控制器使用官方 2.0.2 {@code AguiRequestProcessor} 新签名；
 *       多租户适配经 {@link ApboaAgentResolver}，HITL 经 {@link ApboaAguiHitlService}</li>
 * </ul>
 *
 * @author huxuehao
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AguiProperties.class)
@ConditionalOnClass(AguiMvcController.class)
public class ApboaAgentSessionConfig {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 配置 AguiMvcController（Apboa 定制壳：RunTracker 事件缓冲 / 断连不中断 / HITL 端点）。
     * 覆盖 agentscope-agui-spring-boot-starter 的默认配置。
     */
    @Bean
    @Primary
    public AguiMvcController aguiMvcController(
            @Autowired JdbcTemplate jdbcTemplate,
            @Autowired(required = false) AguiAgentRegistry registry,
            @Autowired(required = false) ThreadSessionManager sessionManager,
            @Autowired(required = false) AgentStateStoreFactory agentStateStoreFactory,
            AguiProperties props) {

        if (registry == null) {
            log.warn("AguiAgentRegistry not found, skip AguiMvcController configuration");
            return null;
        }

        // 官方 2.0.2 AguiAgentRegistry 无 setSessionManager 联动，
        // 会话清理由调用侧（AguiAgentConfiguration.unregisterAgent / ChatSessionServiceImpl）负责。

        ApboaAgentResolver agentResolver = new ApboaAgentResolver(
                DefaultAgentResolver.builder()
                        .registry(registry)
                        .sessionManager(sessionManager)
                        .serverSideMemory(props.isServerSideMemory())
                        .build(),
                jdbcTemplate);

        AguiAdapterConfig adapterConfig = buildAguiAdapterConfig(props);

        AguiRequestProcessor processor = AguiRequestProcessor.builder()
                .agentResolver(agentResolver)
                .config(adapterConfig)
                .build();

        AgentStateStore agentStateStore =
                agentStateStoreFactory != null ? agentStateStoreFactory.getStore() : null;
        ApboaAguiHitlService hitlService = new ApboaAguiHitlService(
                jdbcTemplate, agentResolver, agentStateStore, adapterConfig);

        AguiMvcController controller = AguiMvcController.builder()
                .agentRegistry(registry)
                .sessionManager(sessionManager)
                .serverSideMemory(props.isServerSideMemory())
                .processor(processor)
                .hitlService(hitlService)
                .stateStore(agentStateStore)
                .sseTimeout(600000L)
                .config(buildAguiAdapterConfig(props))
                .build();

        // 注册 Apboa 定制 RestController（官方 AgentscopeAguiMvcAutoConfiguration 已排除，
        // 因为其构造签名与 Apboa 定制端点不一致）
        ChatSessionService chatSessionService = null;
        try {
            chatSessionService = applicationContext.getBean(ChatSessionService.class);
        } catch (Exception e) {
            log.warn("ChatSessionService not available: {}", e.getMessage());
        }
        restControllerRef = new AguiRestController(controller, chatSessionService,
                props.getPathPrefix(), props.isEnablePathRouting());

        return controller;
    }

    @Bean
    public AguiRestController aguiRestController() {
        return restControllerRef;
    }

    /**
     * 官方 AgentscopeAguiMvcAutoConfiguration 已被排除，此处补齐 ThreadSessionManager。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ThreadSessionManager threadSessionManager(AguiProperties props) {
        return new ThreadSessionManager(props.getMaxThreadSessions(), props.getSessionTimeoutMinutes());
    }

    private AguiRestController restControllerRef;

    /**
     * 构建 Agui 适配器配置
     */
    private AguiAdapterConfig buildAguiAdapterConfig(AguiProperties props) {
        return AguiAdapterConfig.builder()
                .toolMergeMode(props.getDefaultToolMergeMode())
                .runTimeout(props.getRunTimeout())
                .emitStateEvents(props.isEmitStateEvents())
                .emitToolCallArgs(props.isEmitToolCallArgs())
                .enableReasoning(props.isEnableReasoning())
                .defaultAgentId(props.getDefaultAgentId())
                .build();
    }
}

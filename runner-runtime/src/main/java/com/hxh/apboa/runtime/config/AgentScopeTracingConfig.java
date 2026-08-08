package com.hxh.apboa.runtime.config;

import com.hxh.apboa.common.vo.AccountVO;
import com.hxh.apboa.engine.agui.AgentContext;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import jakarta.annotation.PostConstruct;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
public class AgentScopeTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeTracingConfig.class);
    private static final String AGENTSCOPE_INSTRUMENTATION_NAME = "agentscope-java";
    private static final String AGENTSCOPE_INSTRUMENTATION_VERSION = "1.0.12";
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> LANGFUSE_SESSION_ID = AttributeKey.stringKey("langfuse.session.id");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    private static final AttributeKey<String> LANGFUSE_USER_ID = AttributeKey.stringKey("langfuse.user.id");
    private static final AttributeKey<String> KACP_RUN_ID = AttributeKey.stringKey("kacp.run_id");

    @Value("${agentscope.tracing.enabled:false}")
    private boolean enabled;

    @Value("${agentscope.tracing.endpoint:http://host.docker.internal:3000/api/public/otel/v1/traces}")
    private String endpoint;

    @Value("${langfuse.public-key:}")
    private String langfusePublicKey;

    @Value("${langfuse.secret-key:}")
    private String langfuseSecretKey;

    @Value("${langfuse.ingestion-version:4}")
    private String langfuseIngestionVersion;

    @PostConstruct
    public void registerTracer() {
        if (!enabled) {
            log.info("AgentScope OpenTelemetry tracing is disabled");
            return;
        }
        if (!StringUtils.hasText(endpoint)) {
            log.warn("AgentScope tracing is enabled but no OTLP endpoint is configured");
            return;
        }
        if (looksLikeLangfuseEndpoint(endpoint)
                && (!StringUtils.hasText(langfusePublicKey) || !StringUtils.hasText(langfuseSecretKey))) {
            log.warn("AgentScope tracing points to Langfuse, but LANGFUSE_PUBLIC_KEY or LANGFUSE_SECRET_KEY is missing");
            return;
        }

        OtlpHttpSpanExporterBuilder exporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint);

        if (StringUtils.hasText(langfusePublicKey) && StringUtils.hasText(langfuseSecretKey)) {
            String encoded = Base64.getEncoder().encodeToString(
                    (langfusePublicKey + ":" + langfuseSecretKey).getBytes(StandardCharsets.UTF_8)
            );
            exporterBuilder.addHeader("Authorization", "Basic " + encoded);
        }
        if (StringUtils.hasText(langfuseIngestionVersion)) {
            exporterBuilder.addHeader("x-langfuse-ingestion-version", langfuseIngestionVersion);
        }

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new AgentContextSpanProcessor())
                .addSpanProcessor(BatchSpanProcessor.builder(exporterBuilder.build()).build())
                .setSampler(Sampler.alwaysOn())
                .build();

        TracerRegistry.register(TelemetryTracer.builder()
                .enabled(true)
                .tracer(tracerProvider.get(AGENTSCOPE_INSTRUMENTATION_NAME, AGENTSCOPE_INSTRUMENTATION_VERSION))
                .build());
        log.info("AgentScope OpenTelemetry tracing registered with endpoint {}", endpoint);
    }

    private boolean looksLikeLangfuseEndpoint(String endpoint) {
        return endpoint.contains("langfuse") || endpoint.contains("/api/public/otel");
    }

    private static class AgentContextSpanProcessor implements SpanProcessor {

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            AgentContext.getIfExists().ifPresent(agentContext -> {
                String threadId = agentContext.getThreadId();
                if (StringUtils.hasText(threadId)) {
                    span.setAttribute(SESSION_ID, threadId);
                    span.setAttribute(LANGFUSE_SESSION_ID, threadId);
                }

                String runId = agentContext.getRunId();
                if (StringUtils.hasText(runId)) {
                    span.setAttribute(KACP_RUN_ID, runId);
                }

                AccountVO userInfo = agentContext.getUserInfo();
                if (userInfo != null && userInfo.getId() != null) {
                    String userId = String.valueOf(userInfo.getId());
                    span.setAttribute(USER_ID, userId);
                    span.setAttribute(LANGFUSE_USER_ID, userId);
                }
            });
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            // No-op. Attributes are attached on span start.
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode forceFlush() {
            return CompletableResultCode.ofSuccess();
        }
    }
}

package com.hxh.apboa.engine.model;

import com.hxh.apboa.common.enums.ModelProviderType;
import com.hxh.apboa.common.enums.ToolChoiceStrategy;
import com.hxh.apboa.common.wrapper.ModelConfigWrapper;
import com.hxh.apboa.engine.model.impl.DefaultOpenAIModelI;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIAgentStreamingTest {
    @Test
    void deliversTextBeforeUpstreamCompletes() throws Exception {
        var releaseResponse = new CountDownLatch(1);
        var firstDelta = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var text = new StringBuilder();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(("data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"first\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                releaseResponse.await(10, TimeUnit.SECONDS);
                output.write(("data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" second\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        var config = ModelConfigWrapper.builder().provider(ModelProviderType.OPEN_AI)
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key").modelCode("test-model").streaming(true)
                .thinking(false).toolChoiceStrategy(ToolChoiceStrategy.AUTO).build();
        var agent = ReActAgent.builder().name("stream-test")
                .model(new DefaultOpenAIModelI().getModel(config)).build();
        var subscription = agent.streamEvents("hello").subscribe(event -> {
            if (event instanceof TextBlockDeltaEvent delta) {
                text.append(delta.getDelta());
                firstDelta.countDown();
            }
        }, error -> { failure.set(error); finished.countDown(); }, finished::countDown);
        try {
            assertTrue(firstDelta.await(5, TimeUnit.SECONDS), "First delta must arrive while upstream is still open");
            assertEquals(1, finished.getCount());
            releaseResponse.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("first second", text.toString());
        } finally {
            releaseResponse.countDown();
            subscription.dispose();
            server.stop(0);
        }
    }
}

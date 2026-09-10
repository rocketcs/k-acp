package com.hxh.apboa.workflow.core;

import com.hxh.apboa.common.enums.NodeType;
import com.hxh.apboa.node.base.Node;
import com.hxh.apboa.node.base.NodeOutput;
import com.hxh.apboa.node.base.context.NodeContext;
import com.hxh.apboa.node.base.context.VariableContext;
import com.hxh.apboa.node.base.inputout.InputConfig;
import com.hxh.apboa.node.base.template.impl.JacksonTemplateFormatter;
import com.hxh.apboa.node.base.verify.VerifyResult;
import com.hxh.apboa.node.http.HttpExternalNode;
import com.hxh.apboa.node.http.HttpMethod;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSubWorkflowTest {

    @Test
    void httpNode_keepsUrlsAsTextWhenRenderingAJacksonBody() throws Exception {
        com.hxh.apboa.node.http.Config config = new com.hxh.apboa.node.http.Config();
        com.hxh.apboa.node.http.HttpRequest request = new com.hxh.apboa.node.http.HttpRequest();
        request.setUrl("https://example.test");
        request.setMethod(HttpMethod.POST);
        request.setBody("\"${context}\"");
        config.setRequest(request);
        config.setFormatterType(com.hxh.apboa.node.base.template.FormatterType.JACKSON);

        HttpExternalNode node = new HttpExternalNode("http", "HTTP", config);
        InputConfig input = new InputConfig();
        input.setName("context");
        input.setClassify(InputConfig.InputClassify.VARIABLE);
        input.setVariableName("context");
        node.addInputConfig(input);
        NodeContext context = new NodeContext("test");
        context.getVariables().storeVariable("context", Map.of("agent_kind", "security-audit-trace"));

        Field inputsMap = HttpExternalNode.class.getDeclaredField("inputsMap");
        inputsMap.setAccessible(true);
        inputsMap.set(node, Map.of("context", Map.of("agent_kind", "security-audit-trace")));
        Method buildRequest = HttpExternalNode.class.getDeclaredMethod("buildRequest", com.hxh.apboa.node.http.HttpRequest.class);
        buildRequest.setAccessible(true);

        Request built = (Request) assertDoesNotThrow(() -> buildRequest.invoke(node, request));
        Buffer body = new Buffer();
        built.body().writeTo(body);

        assertEquals("{\"agent_kind\":\"security-audit-trace\"}", body.readUtf8());
    }

    @Test
    void jacksonTemplateFormatter_serializesAPureObjectVariable() {
        Object actual = new JacksonTemplateFormatter().format("\"${context}\"", Map.of(
                "context", Map.of("agent_kind", "security-audit-trace")), false);

        assertEquals("{\"agent_kind\":\"security-audit-trace\"}", actual);
    }

    @Test
    void jacksonTemplateFormatter_serializesAnUnquotedPureObjectVariable() {
        Object actual = new JacksonTemplateFormatter().format("${context}", Map.of(
                "context", Map.of("agent_kind", "security-audit-trace")), false);

        assertEquals("{\"agent_kind\":\"security-audit-trace\"}", actual);
    }

    @Test
    void executeSubWorkflow_throwsTheFailingNodeError() {
        Node failingNode = new StaticNode("http", NodeOutput.ExecutionStatus.FAILED, "HTTP 500");
        Node succeedingNode = new StaticNode("code", NodeOutput.ExecutionStatus.SUCCESS, null);

        RuntimeException error = assertThrows(RuntimeException.class, () -> new TestWorkflow().executeSubWorkflow(
                List.of(failingNode, succeedingNode),
                List.of(new Edge("http-to-code", "http", "code")),
                "http",
                new VariableContext()));

        assertTrue(error.getMessage().contains("HTTP 500"));
    }

    private static final class TestWorkflow extends Workflow {
        private TestWorkflow() {
            super("test");
        }

        @Override
        public Object execute(NodeContext context) {
            return null;
        }
    }

    private static final class StaticNode implements Node {
        private final String id;
        private final NodeOutput.ExecutionStatus status;
        private final String error;

        private StaticNode(String id, NodeOutput.ExecutionStatus status, String error) {
            this.id = id;
            this.status = status;
            this.error = error;
        }

        @Override public String getId() { return id; }
        @Override public String getName() { return id; }
        @Override public NodeType getType() { return NodeType.CODE; }
        @Override public VerifyResult verifyConfig(Map<String, Object> inputs) { return VerifyResult.valid(); }
        @Override public void addInEdgeId(String edgeId) { }
        @Override public void addOutEdgeId(String edgeId) { }
        @Override public List<String> getInEdgeIds() { return List.of(); }
        @Override public List<String> getOutEdgeIds() { return List.of(); }
        @Override public boolean isInEdge(String edgeId) { return false; }
        @Override public boolean isOutEdge(String edgeId) { return false; }

        @Override
        public NodeOutput execute(NodeContext context) {
            NodeOutput output = new NodeOutput(id, id, NodeType.CODE);
            if (status == NodeOutput.ExecutionStatus.SUCCESS) {
                output.addOutput("output", "done");
                output.markComplete();
            } else {
                output.markFailed(error);
            }
            return output;
        }
    }
}

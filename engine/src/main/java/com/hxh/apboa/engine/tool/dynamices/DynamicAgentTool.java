package com.hxh.apboa.engine.tool.dynamices;

import com.hxh.apboa.common.entity.ToolConfig;
import com.hxh.apboa.common.util.FuncUtils;
import com.hxh.apboa.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.engine.agui.AgentContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 描述：动态工具构建类
 *
 * @author huxuehao
 **/
public class DynamicAgentTool extends ToolBase {
    private final ToolConfig toolConfig;

    public DynamicAgentTool(ToolConfig toolConfig) {
        super(toolConfig.getToolId(),
                toolConfig.getDescription(),
                buildSchemaStatic(toolConfig),
                false,   // readOnly
                true,    // concurrencySafe
                false,   // mcp
                null,    // mcpName
                false,   // externalTool
                false);  // stateInjected
        this.toolConfig = toolConfig;
    }

    /**
     * HITL：need_confirm 工具返回 ASK，进入 v2 官方权限确认流
     * （RequireUserConfirmEvent → 挂起 → ConfirmResult 恢复后重新执行原工具）。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (com.hxh.apboa.engine.hook.builtins.IConfirmationHook.isNeedConfirm(getName())) {
            return Mono.just(PermissionDecision.ask("该工具需要用户确认后执行"));
        }
        return Mono.just(PermissionDecision.passthrough(getName()));
    }

    private static Map<String, Object> buildSchemaStatic(ToolConfig config) {
        JsonNode inputSchema = config.getInputSchema();
        if (inputSchema == null || inputSchema.isNull()) {
            return Map.of("type", "object");
        }

        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        for (JsonNode jsonNode : inputSchema) {
            String name = JsonUtils.getStringValue(jsonNode, "name", true);
            String type = JsonUtils.getStringValue(jsonNode, "type", true);
            String description = JsonUtils.getStringValue(jsonNode, "description", false);
            String defaultValue = JsonUtils.getStringValue(jsonNode, "defaultValue", false);
            boolean required_ = JsonUtils.getBooleanValue(jsonNode, "required", false);

            Map<String, Object> prop = new HashMap<>();
            prop.put("type", type);
            prop.put("description", FuncUtils.isEmpty(description) ? name : description);
            if (!FuncUtils.isEmpty(defaultValue)) {
                prop.put("defaultValue", defaultValue);
            }
            properties.put(name, prop);

            if (required_) {
                required.add(name);
            }
        }

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            try {
                // 拿到动态工具的实例
                IDynamicAgentTool dynamicAgentTool = ToolInstanceLoadFactory
                        .getInstanceLoader(toolConfig.getLanguage()).loadInstance(toolConfig.getCode());

                Map<String, Object> params = getParams(param);

                // 获取 AgentContext
                AgentContext agentContext = param.getContext().get(AgentContext.class);
                Object result = dynamicAgentTool.execute(agentContext, params);

                return ToolResultBlock.of(
                        param.getToolUseBlock().getId(),
                        param.getToolUseBlock().getName(),
                        TextBlock.builder().text(JsonUtils.toJsonStr(result)).build()
                );
            } catch (Exception e) {
                return ToolResultBlock.of(
                        param.getToolUseBlock().getId(),
                        param.getToolUseBlock().getName(),
                        TextBlock.builder().text(e.getMessage()).build()
                );
            }
        });
    }

    /**
     * 获取工具执行参数（Map 形式，按参数名取值）
     *
     * @param param 工具调用参数
     * @return 工具执行参数 Map
     */
    private Map<String, Object> getParams(ToolCallParam param) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> input = param.getInput();
        JsonNode inputSchema = toolConfig.getInputSchema();

        if (inputSchema != null && !inputSchema.isEmpty()) {
            inputSchema.forEach(jsonNode -> {
                if (jsonNode.has("name") && !jsonNode.get("name").asText().trim().isEmpty()) {
                    String name = jsonNode.get("name").textValue();

                    // 如果请求参数中包含该参数，则使用请求中的值
                    if (input != null && input.containsKey(name)) {
                        params.put(name, input.get(name));
                    } else if (JsonUtils.getBooleanValue(jsonNode, "required", false)) {
                        // 如果该参数被定义为必传字段，但请求参数中不包含该参数，则使用默认值
                        String defaultValue = JsonUtils.getStringValue(jsonNode, "defaultValue", false);
                        params.put(name, defaultValue);
                    }
                }
            });
        }

        return params;
    }
}

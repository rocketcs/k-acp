package com.hxh.apboa.engine.workspace.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;

import java.util.Map;

/**
 * 描述：工作空间违规拒绝工具（合成工具）
 * <p>
 * {@link WorkspaceGuardMiddleware} 在工具执行前校验工作空间敏感工具调用，
 * 校验违规时把原调用替换为对本工具的调用（保持 toolUse id 不变，保证结果配对），
 * 本工具不执行任何操作，直接向模型返回违规原因（error 结果），
 * 实现与 v1 vendored ToolExecutor（WORKSPACE_HOOK_ERROR_KEY 短路）等价的阻断语义。
 *
 * @author huxuehao
 **/
public class WorkspaceGuardDenyTool implements AgentTool {

    public static final String NAME = "workspace_guard_denied";

    /** 违规原因在替换后 ToolUseBlock metadata 中的键 */
    public static final String DENY_MESSAGE_KEY = "workspace_guard_message";

    /** 原工具名在替换后 metadata 中的键 */
    public static final String ORIGINAL_TOOL_KEY = "workspace_guard_original_tool";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Internal guard tool: called when a workspace-sensitive tool call was blocked "
                + "by the workspace security policy. Returns the block reason to the model.";
    }

    @Override
    public Map<String, Object> getParameters() {
        // 宽松 schema：接受任意入参（替换后的块携带原工具入参）
        return Map.of("type", "object");
    }

    @Override
    public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> metadata = param.getToolUseBlock().getMetadata();
        String message = metadata != null ? (String) metadata.get(DENY_MESSAGE_KEY) : null;
        String reason = message != null && !message.isBlank()
                ? message
                : "This operation has been blocked by the workspace security policy.";
        return reactor.core.publisher.Mono.just(ToolResultBlock.error(reason));
    }
}

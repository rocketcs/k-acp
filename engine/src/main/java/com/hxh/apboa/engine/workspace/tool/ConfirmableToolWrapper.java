package com.hxh.apboa.engine.workspace.tool;

import com.hxh.apboa.engine.hook.builtins.IConfirmationHook;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 描述：可确认工具包装器（ToolBase 装饰器）
 * <p>
 * 将平台注册的裸 {@link AgentTool}（如官方 ShellCommandTool/ReadFileTool/WriteFileTool）
 * 升级为 {@link ToolBase}，使其进入 v2 权限判定链：
 * <ul>
 *   <li>need_confirm 配置的工具 → {@code checkPermissions} 返回 ASK，
 *       进入官方 HITL 确认流（挂起 → 用户确认 → 重新执行/拒绝）</li>
 *   <li>其余工具 → passthrough，行为不变</li>
 * </ul>
 *
 * @author huxuehao
 **/
public class ConfirmableToolWrapper extends ToolBase {

    private final AgentTool delegate;

    public ConfirmableToolWrapper(AgentTool delegate) {
        super(delegate.getName(),
                delegate.getDescription(),
                delegate.getParameters() == null ? Map.of("type", "object") : delegate.getParameters(),
                Boolean.TRUE.equals(delegate.isReadOnly()),
                true,    // concurrencySafe
                false,   // mcp
                null,    // mcpName
                false,   // externalTool
                false);  // stateInjected
        this.delegate = delegate;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (IConfirmationHook.isNeedConfirm(getName())) {
            return Mono.just(PermissionDecision.ask("该工具需要用户确认后执行"));
        }
        return Mono.just(PermissionDecision.passthrough(getName()));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param);
    }
}

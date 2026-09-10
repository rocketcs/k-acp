package com.hxh.apboa.engine.workspace.tool;

import com.hxh.apboa.common.consts.SysConst;
import com.hxh.apboa.common.util.FolderUtils;
import com.hxh.apboa.engine.security.script.ScriptSecurityService;
import com.hxh.apboa.engine.security.script.model.ScriptType;
import com.hxh.apboa.engine.security.script.model.SecurityReport;
import com.hxh.apboa.engine.workspace.hook.CapacityValidator;
import com.hxh.apboa.engine.workspace.hook.PathValidator;
import com.hxh.apboa.engine.workspace.hook.ShellValidator;
import com.hxh.apboa.engine.workspace.hook.ToolConstants;
import com.hxh.apboa.engine.workspace.hook.WorkspaceSecurityException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 描述：工作空间安全守卫中间件（v2 Middleware）
 * <p>
 * 在工具执行阶段（onActing）对工作空间敏感工具
 * （execute_shell_command / view_text_file / insert_text_file / write_text_file /
 * list_directory / search_replace_file）执行与 v1 WorkspaceHook 相同的校验：
 * <ul>
 *   <li>{@link CapacityValidator} —— 存储容量（写类工具）</li>
 *   <li>{@link PathValidator} —— 路径合法性</li>
 *   <li>{@link ShellValidator} —— Shell 命令安全</li>
 *   <li>{@link ScriptSecurityService} —— 脚本/内联代码语义安全</li>
 * </ul>
 * 校验违规的调用被替换为 {@link WorkspaceGuardDenyTool}（保持 toolUse id 不变，
 * 结果配对完整），由其向模型返回违规原因，实现与 v1 vendored ToolExecutor
 * （WORKSPACE_HOOK_ERROR_KEY 短路）等价的阻断语义。
 *
 * @author huxuehao
 **/
@Slf4j
public class WorkspaceGuardMiddleware implements MiddlewareBase {

    /** 匹配解释器内联代码调用：python -c "..."、node -e "..."、bash -c "..." 等 */
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile(
            "(python3?|python|node|nodejs|bash|sh|zsh|perl|ruby|php)\\s+" +
                    "(-[cer]|--eval)\\s+" +
                    "(\"([^\"]*)\"|'([^']*)')",
            Pattern.CASE_INSENSITIVE);

    /** 解释器名 → ScriptType 映射 */
    private static final Map<String, ScriptType> INTERPRETER_TYPE_MAP = Map.of(
            "python", ScriptType.PYTHON,
            "python3", ScriptType.PYTHON,
            "node", ScriptType.NODEJS,
            "nodejs", ScriptType.NODEJS,
            "bash", ScriptType.SHELL,
            "sh", ScriptType.SHELL,
            "zsh", ScriptType.SHELL);

    private final PathValidator pathValidator = new PathValidator();
    private final ShellValidator shellValidator = new ShellValidator(pathValidator);
    private final ScriptSecurityService scriptSecurity = new ScriptSecurityService();

    @Override
    public reactor.core.publisher.Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext runtimeContext, ActingInput input,
            Function<ActingInput, reactor.core.publisher.Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }

        List<ToolUseBlock> toolCalls = input.toolCalls();
        boolean hasSensitive = toolCalls.stream()
                .anyMatch(t -> ToolConstants.PATH_SENSITIVE_TOOLS.contains(t.getName()));
        if (!hasSensitive) {
            return next.apply(input);
        }

        String threadId = resolveThreadId(agent, runtimeContext);
        String tenantCode = resolveTenantCode(agent, runtimeContext);
        if (threadId == null) {
            return next.apply(input);
        }
        String workspacePath = String.format("%s/%s",
                SysConst.getWorkspacePath(tenantCode), threadId);

        boolean modified = false;
        List<ToolUseBlock> guarded = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock toolUse : toolCalls) {
            String name = toolUse.getName();
            if (!ToolConstants.PATH_SENSITIVE_TOOLS.contains(name)) {
                guarded.add(toolUse);
                continue;
            }
            String violation = validate(toolUse, name, workspacePath);
            if (violation == null) {
                guarded.add(toolUse);
                continue;
            }
            log.warn("WorkspaceGuard 拦截工具调用 tool={} threadId={} 原因={}", name, threadId, violation);
            Map<String, Object> metadata = new HashMap<>(toolUse.getMetadata());
            metadata.put(WorkspaceGuardDenyTool.DENY_MESSAGE_KEY, violation);
            metadata.put(WorkspaceGuardDenyTool.ORIGINAL_TOOL_KEY, name);
            guarded.add(ToolUseBlock.builder()
                    .id(toolUse.getId())
                    .name(WorkspaceGuardDenyTool.NAME)
                    .input(toolUse.getInput())
                    .content(toolUse.getContent())
                    .metadata(metadata)
                    .build());
            modified = true;
        }

        return modified ? next.apply(new ActingInput(guarded)) : next.apply(input);
    }

    /**
     * 执行校验，返回 null 表示通过，否则返回违规原因
     */
    private String validate(ToolUseBlock toolUse, String name, String workspacePath) {
        Map<String, Object> input = toolUse.getInput();
        try {
            if (ToolConstants.WRITE_TOOLS.contains(name)) {
                boolean ok = CapacityValidator.validateCapacity(
                        workspacePath,
                        Double.parseDouble(System.getenv().getOrDefault(
                                "WORKSPACE_CAPACITY_MB",
                                String.valueOf(SysConst.WORKSPACE_CAPACITY_MB))));
                if (!ok) {
                    return "Insufficient storage space. Operation denied.";
                }
            }

            FolderUtils.mkdirsByRelativePath(workspacePath);

            switch (name) {
                case "list_directory":
                    pathValidator.validatePathParam(input, "dir_path", false);
                    break;
                case "view_text_file":
                case "insert_text_file":
                case "write_text_file":
                case "search_replace_file":
                    pathValidator.validatePathParam(input, "file_path", false);
                    checkScriptFile(input, "file_path", "content");
                    break;
                case "execute_shell_command":
                    shellValidator.validateShellCommand(input);
                    checkInlineCodeSecurity(input);
                    break;
                default:
                    break;
            }
            return null;
        } catch (WorkspaceSecurityException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.debug("WorkspaceGuard 校验异常 tool={}", name, e);
            return e.getMessage() != null ? e.getMessage() : "workspace validation failed";
        }
    }

    private void checkScriptFile(Map<String, Object> input, String filePathParamName, String contentParamName) {
        if (input.get(filePathParamName) != null && input.get(contentParamName) != null) {
            String fileName = (String) input.get(filePathParamName);
            String content = (String) input.get(contentParamName);
            SecurityReport report = scriptSecurity.check(fileName, content);
            if (!report.safe()) {
                throw new WorkspaceSecurityException(buildSecurityViolationMessage(report));
            }
        }
    }

    private void checkInlineCodeSecurity(Map<String, Object> input) {
        Object raw = input.get("command");
        if (raw == null) return;
        String command = raw.toString().trim();

        Matcher matcher = INLINE_CODE_PATTERN.matcher(command);
        while (matcher.find()) {
            String interpreter = matcher.group(1).toLowerCase();
            String code = matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
            if (code == null || code.isBlank()) continue;

            ScriptType type = INTERPRETER_TYPE_MAP.get(interpreter);
            if (type == null) {
                continue;
            }
            String virtualFileName = "inline." + type.getExtensions().stream().findFirst().orElse("txt");
            SecurityReport report = scriptSecurity.check(virtualFileName, code);
            if (!report.safe()) {
                throw new WorkspaceSecurityException(buildSecurityViolationMessage(report));
            }
        }
    }

    private String buildSecurityViolationMessage(SecurityReport report) {
        StringBuilder msg = new StringBuilder();
        msg.append("SECURITY VIOLATION DETECTED — This operation has been BLOCKED.\n");
        msg.append(String.format("File: %s | Total findings: %d\n", report.fileName(), report.totalCount()));
        msg.append("INSTRUCTIONS FOR YOU (the AI assistant):\n");
        msg.append("1. DO NOT attempt to write, modify, or create this file again.\n");
        msg.append("2. DO NOT attempt to bypass this check (changing extension, encoding, splitting, alternatives).\n");
        msg.append("3. This block is FINAL and cannot be appealed within this conversation.\n");
        msg.append("Politely inform the user the request cannot be completed due to security policy,\n");
        msg.append("without listing every rule match and without revealing detection patterns.\n");
        return msg.toString();
    }

    private String resolveThreadId(Agent agent, RuntimeContext ctx) {
        if (ctx != null) {
            String v = ctx.get("threadId");
            if (v != null) return v;
        }
        if (agent != null) {
            Object v = com.hxh.apboa.common.util.AgentMetadataStore.get(agent.getAgentId(), "threadId");
            return v != null ? v.toString() : null;
        }
        return null;
    }

    private String resolveTenantCode(Agent agent, RuntimeContext ctx) {
        if (ctx != null) {
            String v = ctx.get("tenantCode");
            if (v != null) return v;
        }
        if (agent != null) {
            Object v = com.hxh.apboa.common.util.AgentMetadataStore.get(agent.getAgentId(), "tenantCode");
            return v != null ? v.toString() : null;
        }
        return null;
    }
}

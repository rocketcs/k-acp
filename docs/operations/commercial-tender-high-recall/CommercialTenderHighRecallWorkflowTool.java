import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.node.base.spring.SpringContextHolder;
import com.hxh.apboa.node.base.request.ParamItem;
import com.hxh.apboa.workflowbiz.dto.WorkflowRunRequest;
import com.hxh.apboa.workflowbiz.service.WorkflowRunService;
import com.hxh.apboa.workflowbiz.vo.WorkflowRunResult;
import com.hxh.apboa.common.UserDetail;
import com.hxh.apboa.common.vo.AccountVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommercialTenderHighRecallWorkflowTool implements IDynamicAgentTool {
    private static final long WORKFLOW_ID = 2079122200000000401L;

    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        Map<String, Object> failure = new LinkedHashMap<>();
        try {
            if (context == null || context.getUserInfo() == null) {
                throw new IllegalArgumentException("Agent context is unavailable");
            }
            String question = string(params == null ? null : params.get("question"));
            if (question.isBlank()) throw new IllegalArgumentException("question is required");
            Object priorState = params == null ? Map.of() : params.getOrDefault("prior_state", Map.of());
            Object companyProfile = params == null ? Map.of() : params.getOrDefault("company_profile", Map.of());

            WorkflowRunRequest request = new WorkflowRunRequest();
            List<ParamItem> requestParams = new ArrayList<>();
            requestParams.add(ParamItem.builder().name("question").value(question).build());
            requestParams.add(ParamItem.builder().name("priorState").value(priorState).build());
            requestParams.add(ParamItem.builder().name("companyProfile").value(companyProfile).build());
            request.setParams(requestParams);

            AccountVO account = context.getUserInfo();
            UserDetail user = UserDetail.builder()
                .id(account.getId())
                .name(account.getNickname())
                .username(account.getUsername())
                .email(account.getEmail())
                .tenantId(context.getTenantId())
                .tenantCode(context.getTenantCode())
                .tenantRole(account.getTenantRole())
                .build();

            WorkflowRunService service = SpringContextHolder.getBean(WorkflowRunService.class);
            WorkflowRunResult result = service.run(WORKFLOW_ID, request, user);
            if (result.getRun() == null || result.getRun().getStatus() == null
                    || !"SUCCESS".equals(result.getRun().getStatus().name())) {
                String error = result.getRun() == null ? "Workflow did not return a run"
                    : string(result.getRun().getError());
                throw new IllegalStateException(error.isBlank() ? "Workflow execution failed" : error);
            }
            return result.getOutput();
        } catch (Exception e) {
            failure.put("success", false);
            failure.put("error", safeMessage(e));
            failure.put("answer", "高召回检索暂时无法完成，请稍后重试。");
            return failure;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return "Workflow execution failed";
        return message.replaceAll("(?i)(x-api-key|authorization|token)\\s*[:=]\\s*[^,;\\s]+", "[redacted]");
    }
}

package com.hxh.apboa.console.ops;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hxh.apboa.common.config.auth.RoleNeed;
import com.hxh.apboa.common.enums.TenantRole;
import com.hxh.apboa.common.r.R;
import com.hxh.apboa.workflowbiz.dto.LangfuseSessionTracingQuery;
import com.hxh.apboa.workflowbiz.service.LangfuseSessionTracingService;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingDetailVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingListVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingRawVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingSummaryVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseTracingUserVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/langfuse/session-tracing")
@RequiredArgsConstructor
public class LangfuseSessionTracingController {

    private final LangfuseSessionTracingService service;

    @RoleNeed({TenantRole.TENANT_ADMIN})
    @GetMapping("/users")
    public R<List<LangfuseTracingUserVO>> users() {
        return R.data(service.users());
    }

    @RoleNeed({TenantRole.TENANT_ADMIN})
    @GetMapping("/page")
    public R<IPage<LangfuseSessionTracingListVO>> page(LangfuseSessionTracingQuery query) {
        return R.data(service.page(query));
    }

    @RoleNeed({TenantRole.TENANT_ADMIN})
    @GetMapping("/{id}")
    public R<LangfuseSessionTracingDetailVO> detail(@PathVariable("id") Long id) {
        return R.data(service.detail(id));
    }

    @RoleNeed({TenantRole.TENANT_ADMIN})
    @GetMapping("/{id}/raw")
    public R<LangfuseSessionTracingRawVO> raw(
        @PathVariable("id") Long id,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return R.data(service.raw(id));
    }

    @RoleNeed({TenantRole.TENANT_ADMIN})
    @GetMapping("/summary")
    public R<LangfuseSessionTracingSummaryVO> summary() {
        return R.data(service.summary());
    }
}

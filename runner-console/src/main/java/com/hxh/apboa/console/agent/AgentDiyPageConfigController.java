package com.hxh.apboa.console.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.agent.service.AgentDiyPageConfigService;
import com.hxh.apboa.common.config.auth.ChatKeyAccess;
import com.hxh.apboa.common.config.auth.RoleNeed;
import com.hxh.apboa.common.config.auth.SkAccess;
import com.hxh.apboa.common.entity.AgentDiyPageConfig;
import com.hxh.apboa.common.enums.TenantRole;
import com.hxh.apboa.common.r.R;
import com.hxh.apboa.common.util.BeanUtils;
import com.hxh.apboa.common.vo.AgentDiyPageConfigVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent/diy-page")
@RequiredArgsConstructor
public class AgentDiyPageConfigController {
    private final AgentDiyPageConfigService service;

    @GetMapping("/{agentId}")
    public R<AgentDiyPageConfigVO> detail(@PathVariable("agentId") Long agentId) {
        AgentDiyPageConfig entity = service.getByAgentId(agentId);
        return R.data(entity == null ? null : BeanUtils.copy(entity, AgentDiyPageConfigVO.class));
    }

    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{agentId}/published")
    public R<JsonNode> published(@PathVariable("agentId") Long agentId) {
        return R.data(service.getPublishedConfig(agentId));
    }

    @PutMapping("/{agentId}/draft")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<AgentDiyPageConfigVO> saveDraft(@PathVariable("agentId") Long agentId, @RequestBody JsonNode config) {
        AgentDiyPageConfig saved = service.saveDraft(agentId, config);
        return R.data(BeanUtils.copy(saved, AgentDiyPageConfigVO.class));
    }

    @PostMapping("/{agentId}/publish")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<Boolean> publish(@PathVariable("agentId") Long agentId) {
        return R.data(service.publish(agentId));
    }

    @PutMapping("/{agentId}/enabled")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<Boolean> setEnabled(
            @PathVariable("agentId") Long agentId,
            @RequestParam("enabled") Boolean enabled) {
        return R.data(service.setEnabled(agentId, enabled));
    }
}

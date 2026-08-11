package com.hxh.apboa.workflowbiz.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hxh.apboa.common.exception.BusinessException;
import com.hxh.apboa.common.mp.support.MP;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.workflowbiz.dto.LangfuseSessionTracingQuery;
import com.hxh.apboa.workflowbiz.mapper.LangfuseSessionTracingMapper;
import com.hxh.apboa.workflowbiz.mapper.row.LangfuseSessionTracingDetailRow;
import com.hxh.apboa.workflowbiz.mapper.row.LangfuseStatusCountRow;
import com.hxh.apboa.workflowbiz.service.LangfuseSessionTracingService;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingDetailVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingListVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingRawVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingSummaryVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseTracingUserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LangfuseSessionTracingServiceImpl implements LangfuseSessionTracingService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int MAX_USER_ID_LENGTH = 128;
    private static final List<String> RESULT_STATUSES = List.of("COMPLETE", "PARTIAL", "ERROR");
    private static final List<String> CURSOR_STATUSES = List.of("DISCOVERED", "PROCESSING", "COMPLETE", "FAILED");
    private static final Set<String> ALLOWED_RESULT_STATUSES = Set.copyOf(RESULT_STATUSES);

    private final LangfuseSessionTracingMapper mapper;
    private final LangfuseSessionTracingAssembler assembler;

    @Override
    public List<LangfuseTracingUserVO> users() {
        List<LangfuseTracingUserVO> users = mapper.selectUsers(currentTenantId());
        return users == null ? List.of() : users;
    }

    @Override
    public IPage<LangfuseSessionTracingListVO> page(LangfuseSessionTracingQuery query) {
        Long tenantId = currentTenantId();
        LangfuseSessionTracingQuery normalized = normalize(query);
        IPage<LangfuseSessionTracingListVO> page = MP.getPage(normalized);
        return mapper.selectTracingPage(page, tenantId, normalized);
    }

    @Override
    public LangfuseSessionTracingDetailVO detail(Long id) {
        LangfuseSessionTracingDetailRow row = mapper.selectDetail(currentTenantId(), id);
        if (row == null) {
            throw new BusinessException("会话追踪记录不存在");
        }

        LangfuseSessionTracingDetailVO detail = new LangfuseSessionTracingDetailVO();
        detail.setId(row.getId());
        detail.setSessionId(row.getSessionId());
        detail.setProjectId(row.getProjectId());
        detail.setUser(toUser(row));
        detail.setStatus(row.getStatus());
        detail.setTurns(assembler.parseTurns(row.getLlmAnalysisJson()));
        detail.setTraceSummary(toTraceSummary(row));
        return detail;
    }

    @Override
    public LangfuseSessionTracingRawVO raw(Long id) {
        LangfuseSessionTracingRawVO raw = mapper.selectRaw(currentTenantId(), id);
        if (raw == null) {
            throw new BusinessException("会话追踪记录不存在");
        }
        return raw;
    }

    @Override
    public LangfuseSessionTracingSummaryVO summary() {
        Long tenantId = currentTenantId();
        LangfuseSessionTracingSummaryVO summary = new LangfuseSessionTracingSummaryVO();
        summary.setResultStatusCounts(statusCounts(
            RESULT_STATUSES,
            mapper.selectResultStatusCounts(tenantId)
        ));
        summary.setCursorStatusCounts(statusCounts(
            CURSOR_STATUSES,
            mapper.selectCursorStatusCounts(tenantId)
        ));
        summary.setStaleProcessingCount(Math.toIntExact(mapper.countStaleProcessing(tenantId)));
        summary.setLastProcessedAt(mapper.selectLastProcessedAt(tenantId));
        return summary;
    }

    private Long currentTenantId() {
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (tenantId == null) {
            throw new BusinessException("缺少租户上下文");
        }
        return tenantId;
    }

    private LangfuseSessionTracingQuery normalize(LangfuseSessionTracingQuery query) {
        LangfuseSessionTracingQuery normalized = query == null ? new LangfuseSessionTracingQuery() : query;

        normalized.setPage(Math.max(DEFAULT_PAGE, normalized.getPage() == null ? DEFAULT_PAGE : normalized.getPage()));
        int requestedSize = normalized.getSize() == null ? DEFAULT_SIZE : normalized.getSize();
        normalized.setSize(Math.min(MAX_SIZE, Math.max(1, requestedSize)));

        String userId = normalized.getUserId();
        if (userId != null && userId.length() > MAX_USER_ID_LENGTH) {
            throw new BusinessException("用户 ID 长度不能超过 128");
        }
        normalized.setUserId(StringUtils.hasText(userId) ? userId.trim() : null);

        String status = normalized.getStatus();
        if (!StringUtils.hasText(status)) {
            normalized.setStatus(null);
        } else {
            String trimmedStatus = status.trim();
            if (!ALLOWED_RESULT_STATUSES.contains(trimmedStatus)) {
                throw new BusinessException("不支持的 tracing 状态");
            }
            normalized.setStatus(trimmedStatus);
        }
        return normalized;
    }

    private LangfuseTracingUserVO toUser(LangfuseSessionTracingDetailRow row) {
        LangfuseTracingUserVO user = new LangfuseTracingUserVO();
        user.setUserId(row.getUserId());
        boolean accountMissing = !StringUtils.hasText(row.getNickname())
            && !StringUtils.hasText(row.getUsername())
            && !StringUtils.hasText(row.getEmail());
        if (accountMissing) {
            user.setNickname("未知用户");
            user.setUsername(row.getUserId());
            user.setEmail(null);
        } else {
            user.setNickname(row.getNickname());
            user.setUsername(row.getUsername());
            user.setEmail(row.getEmail());
        }
        return user;
    }

    private LangfuseSessionTracingSummaryVO toTraceSummary(LangfuseSessionTracingDetailRow row) {
        LangfuseSessionTracingSummaryVO summary = new LangfuseSessionTracingSummaryVO();
        summary.setTraceCount(row.getTraceCount());
        summary.setSeedObservationCount(row.getSeedObservationCount());
        summary.setFullObservationCount(row.getFullObservationCount());
        summary.setScoreCount(row.getScoreCount());
        summary.setQaPairCount(row.getQaPairCount());
        summary.setTypeCounts(assembler.parseObjectCounts(row.getTypeCountsJson()));
        summary.setWarnings(assembler.parseWarnings(row.getWarningsJson()));
        summary.setFirstObservationStartTime(row.getFirstObservationStartTime());
        summary.setLastObservationEndTime(row.getLastObservationEndTime());
        summary.setProcessedAt(row.getProcessedAt());
        return summary;
    }

    private Map<String, Integer> statusCounts(
        List<String> standardStatuses,
        List<LangfuseStatusCountRow> rows
    ) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String status : standardStatuses) {
            values.put(status, 0);
        }
        if (rows == null) {
            return values;
        }
        for (LangfuseStatusCountRow row : rows) {
            if (row != null && values.containsKey(row.getStatus())) {
                values.put(row.getStatus(), row.getCount() == null ? 0 : row.getCount());
            }
        }
        return values;
    }
}

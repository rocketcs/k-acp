package com.hxh.apboa.workflowbiz.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hxh.apboa.common.exception.BusinessException;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.workflowbiz.dto.LangfuseSessionTracingQuery;
import com.hxh.apboa.workflowbiz.mapper.LangfuseSessionTracingMapper;
import com.hxh.apboa.workflowbiz.mapper.row.LangfuseSessionTracingDetailRow;
import com.hxh.apboa.workflowbiz.mapper.row.LangfuseStatusCountRow;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingDetailVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingRawVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingSummaryVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseTracingUserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangfuseSessionTracingServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LangfuseSessionTracingMapper mapper;
    private LangfuseSessionTracingServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantUtils.setCurrentTenant(100L, "test");
        mapper = mock(LangfuseSessionTracingMapper.class);
        service = new LangfuseSessionTracingServiceImpl(mapper, new LangfuseSessionTracingAssembler());
    }

    @AfterEach
    void tearDown() {
        TenantUtils.clear();
    }

    @Test
    void allOperationsPassCurrentTenantToMapper() {
        LangfuseTracingUserVO user = new LangfuseTracingUserVO();
        when(mapper.selectUsers(100L)).thenReturn(List.of(user));
        when(mapper.selectTracingPage(any(), eq(100L), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.selectDetail(100L, 7L)).thenReturn(detailRow());
        LangfuseSessionTracingRawVO raw = new LangfuseSessionTracingRawVO();
        when(mapper.selectRaw(100L, 7L)).thenReturn(raw);
        when(mapper.selectResultStatusCounts(100L)).thenReturn(List.of());
        when(mapper.selectCursorStatusCounts(100L)).thenReturn(List.of());
        when(mapper.countStaleProcessing(100L)).thenReturn(0L);

        assertSame(user, service.users().getFirst());
        service.page(null);
        service.detail(7L);
        assertSame(raw, service.raw(7L));
        service.summary();

        verify(mapper).selectUsers(100L);
        verify(mapper).selectTracingPage(any(), eq(100L), any());
        verify(mapper).selectDetail(100L, 7L);
        verify(mapper).selectRaw(100L, 7L);
        verify(mapper).selectResultStatusCounts(100L);
        verify(mapper).selectCursorStatusCounts(100L);
        verify(mapper).countStaleProcessing(100L);
        verify(mapper).selectLastProcessedAt(100L);
    }

    @Test
    void pageDefaultsAndClampsPagination() {
        when(mapper.selectTracingPage(any(), eq(100L), any())).thenAnswer(invocation -> invocation.getArgument(0));

        IPage<?> defaultPage = service.page(null);
        assertEquals(1L, defaultPage.getCurrent());
        assertEquals(20L, defaultPage.getSize());

        LangfuseSessionTracingQuery low = new LangfuseSessionTracingQuery();
        low.setPage(0);
        low.setSize(0);
        IPage<?> lowPage = service.page(low);
        assertEquals(1L, lowPage.getCurrent());
        assertEquals(1L, lowPage.getSize());

        LangfuseSessionTracingQuery high = new LangfuseSessionTracingQuery();
        high.setPage(2);
        high.setSize(500);
        IPage<?> highPage = service.page(high);
        assertEquals(2L, highPage.getCurrent());
        assertEquals(100L, highPage.getSize());
    }

    @Test
    void pageRejectsInvalidUserIdAndStatus() {
        LangfuseSessionTracingQuery longUserId = new LangfuseSessionTracingQuery();
        longUserId.setUserId("x".repeat(129));
        BusinessException userError = assertThrows(BusinessException.class, () -> service.page(longUserId));
        assertEquals("用户 ID 长度不能超过 128", userError.getMessage());

        LangfuseSessionTracingQuery invalidStatus = new LangfuseSessionTracingQuery();
        invalidStatus.setStatus("PROCESSING");
        BusinessException statusError = assertThrows(BusinessException.class, () -> service.page(invalidStatus));
        assertEquals("不支持的 tracing 状态", statusError.getMessage());
    }

    @Test
    void detailAndRawRejectMissingRows() {
        BusinessException detailError = assertThrows(BusinessException.class, () -> service.detail(9L));
        BusinessException rawError = assertThrows(BusinessException.class, () -> service.raw(9L));

        assertEquals("会话追踪记录不存在", detailError.getMessage());
        assertEquals("会话追踪记录不存在", rawError.getMessage());
    }

    @Test
    void detailUsesStoredTurnsAndUnknownAccountFallback() throws Exception {
        LangfuseSessionTracingDetailRow row = detailRow();
        row.setLlmAnalysisJson(objectMapper.readTree("""
            {"turns":[
              {"turn":1,"userQuestion":"问题一","agentAnswer":"回答一"},
              {"turn":2,"userQuestion":"问题二","agentAnswer":"回答二"}
            ]}
            """));
        row.setTypeCountsJson(objectMapper.readTree("{\"AGENT\":1,\"TOOL\":2}"));
        row.setWarningsJson(objectMapper.readTree("[\"pagination_truncated\"]"));
        when(mapper.selectDetail(100L, 7L)).thenReturn(row);

        LangfuseSessionTracingDetailVO result = service.detail(7L);

        assertEquals("7", result.getId());
        assertEquals("未知用户", result.getUser().getNickname());
        assertEquals("2080214710511923201", result.getUser().getUsername());
        assertNull(result.getUser().getEmail());
        assertEquals(List.of("问题一", "问题二"), result.getTurns().stream()
            .map(turn -> turn.getUserQuestion()).toList());
        assertEquals(List.of("回答一", "回答二"), result.getTurns().stream()
            .map(turn -> turn.getAgentAnswer()).toList());
        assertEquals(2, result.getTraceSummary().getTypeCounts().get("TOOL"));
        assertEquals(List.of("pagination_truncated"), result.getTraceSummary().getWarnings());
    }

    @Test
    void summaryFillsStandardStatusesInStableOrder() {
        when(mapper.selectResultStatusCounts(100L)).thenReturn(List.of(status("COMPLETE", 3)));
        when(mapper.selectCursorStatusCounts(100L)).thenReturn(List.of(
            status("PROCESSING", 2), status("FAILED", 1)));
        when(mapper.countStaleProcessing(100L)).thenReturn(1L);
        LocalDateTime processedAt = LocalDateTime.of(2026, 8, 11, 10, 0);
        when(mapper.selectLastProcessedAt(100L)).thenReturn(processedAt);

        LangfuseSessionTracingSummaryVO result = service.summary();

        assertEquals(List.of("COMPLETE", "PARTIAL", "ERROR"), List.copyOf(result.getResultStatusCounts().keySet()));
        assertEquals(List.of("DISCOVERED", "PROCESSING", "COMPLETE", "FAILED"),
            List.copyOf(result.getCursorStatusCounts().keySet()));
        assertEquals(0, result.getResultStatusCounts().get("PARTIAL"));
        assertEquals(2, result.getCursorStatusCounts().get("PROCESSING"));
        assertEquals(1, result.getStaleProcessingCount());
        assertEquals(processedAt, result.getLastProcessedAt());
    }

    @Test
    void missingTenantFailsBeforeAnyQuery() {
        TenantUtils.clear();

        BusinessException error = assertThrows(BusinessException.class, service::users);

        assertEquals("缺少租户上下文", error.getMessage());
    }

    @Test
    void mapperContractExposesOnlyReadOperations() {
        Set<String> methodNames = Set.of(LangfuseSessionTracingMapper.class.getDeclaredMethods()).stream()
            .map(method -> method.getName())
            .collect(Collectors.toSet());

        assertEquals(Set.of(
            "selectUsers",
            "selectTracingPage",
            "selectDetail",
            "selectRaw",
            "selectResultStatusCounts",
            "selectCursorStatusCounts",
            "countStaleProcessing",
            "selectLastProcessedAt"
        ), methodNames);
    }

    private LangfuseSessionTracingDetailRow detailRow() {
        LangfuseSessionTracingDetailRow row = new LangfuseSessionTracingDetailRow();
        row.setId("7");
        row.setSessionId("session-7");
        row.setProjectId("project-1");
        row.setUserId("2080214710511923201");
        row.setStatus("COMPLETE");
        row.setTraceCount(1);
        row.setSeedObservationCount(2);
        row.setFullObservationCount(3);
        row.setScoreCount(0);
        row.setQaPairCount(2);
        row.setFirstObservationStartTime("start");
        row.setLastObservationEndTime("end");
        row.setProcessedAt(LocalDateTime.of(2026, 8, 11, 9, 0));
        return row;
    }

    private LangfuseStatusCountRow status(String name, int count) {
        LangfuseStatusCountRow row = new LangfuseStatusCountRow();
        row.setStatus(name);
        row.setCount(count);
        return row;
    }
}

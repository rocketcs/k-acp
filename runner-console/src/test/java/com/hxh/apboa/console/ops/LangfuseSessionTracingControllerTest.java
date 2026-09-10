package com.hxh.apboa.console.ops;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hxh.apboa.common.config.auth.ChatKeyAccess;
import com.hxh.apboa.common.config.auth.RoleNeed;
import com.hxh.apboa.common.config.auth.SkAccess;
import com.hxh.apboa.common.enums.TenantRole;
import com.hxh.apboa.workflowbiz.dto.LangfuseSessionTracingQuery;
import com.hxh.apboa.workflowbiz.service.LangfuseSessionTracingService;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingDetailVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingListVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingRawVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingSummaryVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseTracingUserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangfuseSessionTracingControllerTest {

    private LangfuseSessionTracingService service;
    private LangfuseSessionTracingController controller;

    @BeforeEach
    void setUp() {
        service = mock(LangfuseSessionTracingService.class);
        controller = new LangfuseSessionTracingController(service);
    }

    @Test
    void delegatesAllFiveReadEndpoints() {
        List<LangfuseTracingUserVO> users = List.of(new LangfuseTracingUserVO());
        LangfuseSessionTracingQuery query = new LangfuseSessionTracingQuery();
        IPage<LangfuseSessionTracingListVO> page = new Page<>();
        LangfuseSessionTracingDetailVO detail = new LangfuseSessionTracingDetailVO();
        LangfuseSessionTracingRawVO raw = new LangfuseSessionTracingRawVO();
        LangfuseSessionTracingSummaryVO summary = new LangfuseSessionTracingSummaryVO();
        when(service.users()).thenReturn(users);
        when(service.page(query)).thenReturn(page);
        when(service.detail(7L)).thenReturn(detail);
        when(service.raw(7L)).thenReturn(raw);
        when(service.summary()).thenReturn(summary);

        MockHttpServletResponse response = new MockHttpServletResponse();

        assertSame(users, controller.users().getData());
        assertSame(page, controller.page(query).getData());
        assertSame(detail, controller.detail(7L).getData());
        assertSame(raw, controller.raw(7L, response).getData());
        assertSame(summary, controller.summary().getData());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void everyEndpointIsGetOnlyAndTenantAdminProtected() {
        Method[] methods = LangfuseSessionTracingController.class.getDeclaredMethods();
        assertEquals(5, methods.length);

        for (Method method : methods) {
            assertTrue(method.isAnnotationPresent(GetMapping.class), method.getName());
            RoleNeed roleNeed = method.getAnnotation(RoleNeed.class);
            assertArrayEquals(new TenantRole[]{TenantRole.TENANT_ADMIN}, roleNeed.value(), method.getName());
            assertFalse(method.isAnnotationPresent(PostMapping.class), method.getName());
            assertFalse(method.isAnnotationPresent(PutMapping.class), method.getName());
            assertFalse(method.isAnnotationPresent(PatchMapping.class), method.getName());
            assertFalse(method.isAnnotationPresent(DeleteMapping.class), method.getName());
            assertNull(method.getAnnotation(SkAccess.class), method.getName());
            assertNull(method.getAnnotation(ChatKeyAccess.class), method.getName());
        }
    }

    @Test
    void exposesOnlyTheExpectedPaths() {
        RequestMapping root = LangfuseSessionTracingController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/langfuse/session-tracing"}, root.value());

        List<String> paths = Arrays.stream(LangfuseSessionTracingController.class.getDeclaredMethods())
            .flatMap(method -> Arrays.stream(method.getAnnotation(GetMapping.class).value()))
            .sorted()
            .toList();
        assertEquals(List.of("/page", "/summary", "/users", "/{id}", "/{id}/raw"), paths);
    }
}

package com.hxh.apboa.workflowbiz.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hxh.apboa.workflowbiz.dto.LangfuseSessionTracingQuery;
import com.hxh.apboa.workflowbiz.mapper.row.LangfuseSessionTracingDetailRow;
import com.hxh.apboa.workflowbiz.mapper.row.LangfuseStatusCountRow;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingListVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingRawVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseTracingUserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LangfuseSessionTracingMapper {

    @InterceptorIgnore(tenantLine = "true")
    List<LangfuseTracingUserVO> selectUsers(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    IPage<LangfuseSessionTracingListVO> selectTracingPage(
        IPage<LangfuseSessionTracingListVO> page,
        @Param("tenantId") Long tenantId,
        @Param("query") LangfuseSessionTracingQuery query
    );

    @InterceptorIgnore(tenantLine = "true")
    LangfuseSessionTracingDetailRow selectDetail(
        @Param("tenantId") Long tenantId,
        @Param("id") Long id
    );

    LangfuseSessionTracingRawVO selectRaw(
        @Param("tenantId") Long tenantId,
        @Param("id") Long id
    );

    List<LangfuseStatusCountRow> selectResultStatusCounts(@Param("tenantId") Long tenantId);

    List<LangfuseStatusCountRow> selectCursorStatusCounts(@Param("tenantId") Long tenantId);

    long countStaleProcessing(@Param("tenantId") Long tenantId);

    LocalDateTime selectLastProcessedAt(@Param("tenantId") Long tenantId);
}

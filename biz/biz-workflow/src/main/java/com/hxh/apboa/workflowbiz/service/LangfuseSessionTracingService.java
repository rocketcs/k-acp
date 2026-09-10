package com.hxh.apboa.workflowbiz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hxh.apboa.workflowbiz.dto.LangfuseSessionTracingQuery;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingDetailVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingListVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingRawVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseSessionTracingSummaryVO;
import com.hxh.apboa.workflowbiz.vo.LangfuseTracingUserVO;

import java.util.List;

public interface LangfuseSessionTracingService {

    List<LangfuseTracingUserVO> users();

    IPage<LangfuseSessionTracingListVO> page(LangfuseSessionTracingQuery query);

    LangfuseSessionTracingDetailVO detail(Long id);

    LangfuseSessionTracingRawVO raw(Long id);

    LangfuseSessionTracingSummaryVO summary();
}

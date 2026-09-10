package com.hxh.apboa.workflowbiz.dto;

import com.hxh.apboa.common.mp.support.PageParams;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LangfuseSessionTracingQuery extends PageParams {
    private String userId;
    private String status;
}

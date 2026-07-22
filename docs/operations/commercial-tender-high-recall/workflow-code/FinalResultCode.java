package com.hxh.apboa.node.code.load;

import com.hxh.apboa.node.code.CodeExecutor;
import java.util.*;

public class CodeExecute implements CodeExecutor {
    @Override
    public Object execute(Map<String, Object> inputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object packageValue = inputs.get("package");
        if (packageValue instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) packageValue;
            result.put("queryPlan", map.get("query_plan"));
            result.put("continuationState", map.get("continuation_state"));
            result.put("resultStatus", map.get("result_status"));
            result.put("metrics", map.get("metrics"));
        }
        result.put("answer", String.valueOf(inputs.getOrDefault("answer", "")));
        return result;
    }
}

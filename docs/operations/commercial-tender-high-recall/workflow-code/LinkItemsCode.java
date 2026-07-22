package com.hxh.apboa.node.code.load;

import com.hxh.apboa.node.code.CodeExecutor;
import java.util.*;

public class CodeExecute implements CodeExecutor {
    @Override
    public Object execute(Map<String, Object> inputs) {
        Object batch = inputs.get("batch");
        if (!(batch instanceof Map)) return List.of();
        Map<?, ?> map = (Map<?, ?>) batch;
        Object items = map.get("link_items");
        return items instanceof List ? items : List.of();
    }
}

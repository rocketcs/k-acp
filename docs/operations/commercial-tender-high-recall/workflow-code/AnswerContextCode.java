package com.hxh.apboa.node.code.load;

import com.hxh.apboa.node.code.CodeExecutor;
import java.util.*;

public class CodeExecute implements CodeExecutor {
    @Override
    public Object execute(Map<String, Object> inputs) {
        Object value = inputs.get("package");
        if (!(value instanceof Map)) return Map.of();
        Map<?, ?> map = (Map<?, ?>) value;
        Object context = map.get("answer_context");
        return context == null ? Map.of() : context;
    }
}

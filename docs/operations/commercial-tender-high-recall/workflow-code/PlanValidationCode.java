package com.hxh.apboa.node.code.load;

import com.hxh.apboa.node.code.CodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class CodeExecute implements CodeExecutor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object execute(Map<String, Object> inputs) throws Exception {
        Map<String, Object> prior = asMap(inputs.get("priorState"));
        String question = String.valueOf(inputs.getOrDefault("question", "")).trim();
        Map<String, Object> plan;
        if (isContinue(question) && prior.get("query_plan") != null) {
            plan = asMap(prior.get("query_plan"));
        } else {
            plan = asMap(inputs.get("plan"));
        }
        List<String> errors = new ArrayList<>();
        if (!"tender-query-plan-v1".equals(String.valueOf(plan.get("query_plan_version")))) {
            errors.add("query_plan_version must be tender-query-plan-v1");
        }
        if (String.valueOf(plan.getOrDefault("subject", "")).isBlank()) errors.add("subject is required");
        if (!(plan.get("hard_filters") instanceof Map)) errors.add("hard_filters must be an object");
        if (!(plan.get("concept_groups") instanceof List)) plan.put("concept_groups", new ArrayList<>());
        if (!(plan.get("exclude_keywords") instanceof List)) plan.put("exclude_keywords", new ArrayList<>());
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        plan.put("query_plan_version", "tender-query-plan-v1");
        return plan;
    }

    private static boolean isContinue(String question) {
        String cleaned = question.replace("。", "").replace("！", "").replace("!", "").trim();
        return Set.of("继续", "下一批", "下一页", "继续查看").contains(cleaned);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) throws Exception {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map) return new LinkedHashMap<>((Map<String, Object>) value);
        if (value instanceof String && !((String) value).isBlank()) {
            return MAPPER.readValue((String) value, LinkedHashMap.class);
        }
        return MAPPER.convertValue(value, LinkedHashMap.class);
    }
}

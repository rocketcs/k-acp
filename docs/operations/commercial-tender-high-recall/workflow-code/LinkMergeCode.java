package com.hxh.apboa.node.code.load;

import com.hxh.apboa.node.code.CodeExecutor;
import java.util.*;

public class CodeExecute implements CodeExecutor {
    @Override
    public Object execute(Map<String, Object> inputs) {
        Map<String, Object> batch = map(inputs.get("batch"));
        Map<String, Object> linkResult = map(inputs.get("linkResult"));
        Map<String, Map<String, Object>> links = new LinkedHashMap<>();
        for (Map<String, Object> item : mapList(linkResult.get("items"))) {
            links.put(String.valueOf(item.get("record_key")), item);
        }
        List<Map<String, Object>> display = new ArrayList<>();
        boolean mappingComplete = Boolean.TRUE.equals(linkResult.get("link_resolution_complete"));
        for (Map<String, Object> source : mapList(batch.get("display_records"))) {
            Map<String, Object> record = new LinkedHashMap<>(source);
            String key = String.valueOf(record.get("record_key"));
            Map<String, Object> link = links.get(key);
            if (link == null) {
                mappingComplete = false;
                record.put("original_url", null);
                record.put("display_url", null);
                record.put("link_type", "NONE");
                record.put("source_status", "NOT_FOUND");
                record.put("status_reason", "Resolver result missing for record_key");
            } else {
                copy(link, record, "original_url", "aggregate_url", "display_url", "link_type",
                    "source_status", "source_domain", "resolve_method", "status_reason");
            }
            display.add(record);
        }

        Map<String, Object> answerContext = new LinkedHashMap<>();
        answerContext.put("query_plan", batch.get("query_plan"));
        answerContext.put("records", display);
        answerContext.put("displayed_count", batch.get("displayed_count"));
        answerContext.put("loaded_count", batch.get("loaded_count"));
        answerContext.put("remaining_count", batch.get("remaining_count"));
        answerContext.put("is_complete", batch.get("is_complete"));
        answerContext.put("incomplete_reason", batch.get("incomplete_reason"));
        answerContext.put("failures", batch.get("failures"));
        answerContext.put("correction", batch.get("correction"));
        answerContext.put("link_resolution_complete", mappingComplete);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer_context", answerContext);
        result.put("query_plan", batch.get("query_plan"));
        result.put("continuation_state", batch.get("continuation_state"));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("is_complete", batch.get("is_complete"));
        status.put("link_resolution_complete", mappingComplete);
        status.put("loaded_count", batch.get("loaded_count"));
        status.put("displayed_count", batch.get("displayed_count"));
        status.put("remaining_count", batch.get("remaining_count"));
        result.put("result_status", status);
        result.put("metrics", batch.get("metrics"));
        return result;
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... fields) {
        for (String field : fields) target.put(field, source.get(field));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List)) return new ArrayList<>();
        List<?> list = (List<?>) value;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map) result.add(new LinkedHashMap<>((Map<String, Object>) item));
        return result;
    }
}

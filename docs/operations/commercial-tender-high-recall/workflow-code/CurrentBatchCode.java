package com.hxh.apboa.node.code.load;

import com.hxh.apboa.node.code.CodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class CodeExecute implements CodeExecutor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DISPLAY_SIZE = 20;

    @Override
    public Object execute(Map<String, Object> inputs) throws Exception {
        Map<String, Object> search = asMap(inputs.get("searchResult"));
        Map<String, Object> prior = asMap(inputs.get("priorState"));
        Map<String, Object> queryPlan = asMap(inputs.get("queryPlan"));
        String question = String.valueOf(inputs.getOrDefault("question", "")).trim();
        List<Map<String, Object>> records = asMapList(search.get("records"));
        int position = isContinue(question) ? intValue(prior.get("position"), 0) : 0;
        if (position < 0 || position > records.size()) position = 0;
        int end = Math.min(records.size(), position + DISPLAY_SIZE);
        List<Map<String, Object>> display = new ArrayList<>();
        List<Map<String, Object>> linkItems = new ArrayList<>();
        for (int i = position; i < end; i++) {
            Map<String, Object> record = new LinkedHashMap<>(records.get(i));
            record.put("display_index", i + 1);
            display.add(record);
            Map<String, Object> link = new LinkedHashMap<>();
            copy(record, link, "record_key", "bid_id", "uniq_key", "title", "aggregate_url", "source_url");
            linkItems.add(link);
        }

        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("state_version", "tender-continuation-v1");
        continuation.put("query_plan_version", "tender-query-plan-v1");
        continuation.put("query_plan", queryPlan);
        continuation.put("position", end);
        continuation.put("page_size", DISPLAY_SIZE);
        continuation.put("total", records.size());
        continuation.put("remaining", Math.max(0, records.size() - end));
        continuation.put("stable_keys_gzip_b64", search.get("stable_keys_gzip_b64"));
        continuation.put("stable_keys_sha256", search.get("stable_keys_sha256"));
        continuation.put("query_end_boundary", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        continuation.put("is_complete", Boolean.TRUE.equals(search.get("is_complete")));
        continuation.put("failures", search.getOrDefault("failures", List.of()));

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("display_records", display);
        batch.put("link_items", linkItems);
        batch.put("displayed_from", position);
        batch.put("displayed_count", display.size());
        batch.put("loaded_count", records.size());
        batch.put("remaining_count", Math.max(0, records.size() - end));
        batch.put("is_complete", search.get("is_complete"));
        batch.put("incomplete_reason", search.get("incomplete_reason"));
        batch.put("metrics", search.getOrDefault("metrics", Map.of()));
        batch.put("failures", search.getOrDefault("failures", List.of()));
        batch.put("correction", search.getOrDefault("correction", Map.of()));
        batch.put("query_plan", queryPlan);
        batch.put("continuation_state", continuation);
        return batch;
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... fields) {
        for (String field : fields) target.put(field, source.get(field));
    }

    private static boolean isContinue(String question) {
        String cleaned = question.replace("。", "").replace("！", "").replace("!", "").trim();
        return Set.of("继续", "下一批", "下一页", "继续查看").contains(cleaned);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) throws Exception {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map) return new LinkedHashMap<>((Map<String, Object>) value);
        if (value instanceof String && !((String) value).isBlank()) return MAPPER.readValue((String) value, LinkedHashMap.class);
        return MAPPER.convertValue(value, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List)) return new ArrayList<>();
        List<?> list = (List<?>) value;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map) result.add(new LinkedHashMap<>((Map<String, Object>) item));
        return result;
    }

}

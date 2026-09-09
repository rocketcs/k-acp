package com.kacp.nanwang.trace;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：trace 存储（问数轨迹），供 query → evidence_subgraph 传递结果锚点
 */
@Component
public class TraceStore {

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows,
                              int requestedLimit, boolean truncated) {}

    public record Trace(String datasetId, String question, long createdAt) {}

    private final Map<String, Trace> traces = new ConcurrentHashMap<>();
    private final Map<String, QueryResult> results = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();
    private static final int CAPACITY = 200;

    public void put(String traceId, String datasetId, String question) {
        traces.put(traceId, new Trace(datasetId, question, System.currentTimeMillis()));
        results.remove(traceId); // 新一轮同 id 覆盖旧结果
        synchronized (order) {
            order.addLast(traceId);
            while (order.size() > CAPACITY) {
                String evict = order.pollFirst();
                traces.remove(evict);
                results.remove(evict);
            }
        }
    }

    public Trace get(String traceId) {
        Trace t = traces.get(traceId);
        if (t == null) return null;
        if (System.currentTimeMillis() - t.createdAt() > 3_600_000) { // 1h 过期
            traces.remove(traceId);
            results.remove(traceId);
            return null;
        }
        return t;
    }

    public void putResult(String traceId, QueryResult result) { results.put(traceId, result); }

    public QueryResult getResult(String traceId) { return results.get(traceId); }
}

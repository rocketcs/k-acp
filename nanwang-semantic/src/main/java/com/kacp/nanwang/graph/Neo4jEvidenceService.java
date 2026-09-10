package com.kacp.nanwang.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 描述：Neo4j 证据子图服务
 * <p>
 * 固定、单语句、参数化只读 Cypher（禁止拼接用户输入，禁止写操作）。
 * 锚点来自同一 trace_id 的 DM8 查询结果（实体名 + 库存记录 URN），有界投影。
 */
@Service
public class Neo4jEvidenceService {

    @Value("${nanwang.neo4j.uri:bolt://127.0.0.1:7689}")
    private String uri;
    @Value("${nanwang.neo4j.username:neo4j}")
    private String username;
    @Value("${nanwang.neo4j.password:MockGraph2026!}")
    private String password;

    private volatile Driver driver;

    private Driver driver() {
        if (driver == null) {
            synchronized (this) {
                if (driver == null) driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
            }
        }
        return driver;
    }

    /** 图谱数据版本（来自知识定义）。 */
    public String graphVersion() {
        return "v0.2";
    }

    /** Neo4j 全库规模统计；只读，用于数据管理页顶部展示。 */
    public Map<String, Object> stats() {
        try (Session session = driver().session()) {
            Record nodeRecord = session.run("""
                    MATCH (n)
                    RETURN count(n) AS node_count,
                           coalesce(sum(size(keys(n))), 0) AS property_count
                    """).single();
            Record edgeRecord = session.run("""
                    MATCH ()-[r]->()
                    RETURN count(r) AS edge_count
                    """).single();

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("nodeCount", nodeRecord.get("node_count").asLong());
            output.put("edgeCount", edgeRecord.get("edge_count").asLong());
            output.put("propertyCount", nodeRecord.get("property_count").asLong());
            return output;
        }
    }

    /**
     * 全局关键词候选查询。只返回候选的展示字段，节点属性在用户选中后由 view 返回。
     * 查询字段由图谱节点的全部属性和值组成，浏览器不能提交 Cypher。
     */
    public Map<String, Object> search(String keyword, String kind, int requestedLimit) {
        String term = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String requestedKind = kind == null ? "" : kind.trim();
        int limit = Math.min(Math.max(requestedLimit, 1), 50);

        List<Map<String, Object>> results = new ArrayList<>();
        if (!term.isBlank()) {
            String cypher = """
                    MATCH (n)
                    WITH n, [key IN keys(n) | toLower(key) + ' ' + coalesce(toLower(toString(n[key])), '')] AS values
                    WHERE ($kind = '' OR $kind IN labels(n))
                      AND (any(value IN values WHERE value CONTAINS $keyword)
                           OR any(label IN labels(n) WHERE toLower(label) CONTAINS $keyword))
                    RETURN elementId(n) AS element_id, labels(n) AS node_labels, properties(n) AS node_properties
                    ORDER BY CASE
                               WHEN toLower(coalesce(toString(n.`名称`), '')) = $keyword THEN 0
                               WHEN toLower(coalesce(toString(n.`名称`), '')) CONTAINS $keyword THEN 1
                               WHEN toLower(coalesce(toString(n.`标识`), '')) CONTAINS $keyword THEN 2
                               ELSE 3
                             END,
                             coalesce(toString(n.`名称`), toString(n.`标识`))
                    LIMIT $limit
                    """;
            try (Session session = driver().session()) {
                List<Record> records = session.run(cypher, Map.of(
                        "keyword", term,
                        "kind", requestedKind,
                        "limit", limit)).list();
                for (Record record : records) {
                    List<String> labels = record.get("node_labels").asList(Value -> Value.asString());
                    Map<String, Object> properties = record.get("node_properties").asMap();
                    String elementId = record.get("element_id").asString();
                    String id = logicalId(elementId, properties);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id);
                    item.put("label", displayLabel(labels, id, properties));
                    item.put("kind", displayKind(labels));
                    results.add(item);
                }
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("keyword", keyword == null ? "" : keyword.trim());
        output.put("kind", requestedKind);
        output.put("results", results);
        return output;
    }

    /**
     * 查看一个全局 Neo4j 节点及其有界一跳关系。
     */
    public Map<String, Object> view(String requestedId, int requestedHops, int requestedLayerLimit) {
        String id = requestedId == null ? "" : requestedId.trim();
        int hops = Math.min(Math.max(requestedHops, 1), 3);
        int layerLimit = Math.min(Math.max(requestedLayerLimit, 1), 80);
        if (id.isBlank()) return Map.of("found", false);

        String anchorCypher = """
                MATCH (n)
                WHERE n.`标识` = $id
                RETURN elementId(n) AS element_id, labels(n) AS node_labels, properties(n) AS node_properties
                LIMIT 1
                """;
        String neighborsCypher = """
                MATCH (n)-[r]-(m)
                WHERE n.`标识` IN $frontier
                  AND NOT m.`标识` IN $seen
                RETURN elementId(n) AS source_element_id, labels(n) AS source_labels,
                       properties(n) AS source_properties,
                       elementId(m) AS target_element_id, labels(m) AS target_labels,
                       properties(m) AS target_properties, type(r) AS relation_type,
                       elementId(startNode(r)) = elementId(n) AS forward
                LIMIT $limit
                """;

        try (Session session = driver().session()) {
            List<Record> anchorRecords = session.run(anchorCypher, Map.of("id", id)).list();
            if (anchorRecords.isEmpty()) return Map.of("found", false);

            Record anchorRecord = anchorRecords.get(0);
            List<String> anchorLabels = anchorRecord.get("node_labels").asList(Value -> Value.asString());
            Map<String, Object> anchorProperties = anchorRecord.get("node_properties").asMap();
            Map<String, Object> anchor = node(
                    logicalId(anchorRecord.get("element_id").asString(), anchorProperties),
                    anchorLabels,
                    anchorProperties,
                    anchorRecord.get("element_id").asString());

            Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
            nodes.put(String.valueOf(anchor.get("id")), anchor);
            Set<String> edgeIds = new LinkedHashSet<>();
            List<Map<String, Object>> edges = new ArrayList<>();

            Set<String> seen = new LinkedHashSet<>();
            Set<String> frontier = new LinkedHashSet<>();
            String anchorId = String.valueOf(anchor.get("id"));
            seen.add(anchorId);
            frontier.add(anchorId);

            // 分层扩展：每一跳只从上一层新节点继续，并限制每层边数，避免高扇出库存节点撑爆浏览器。
            for (int depth = 0; depth < hops && !frontier.isEmpty(); depth++) {
                List<Record> records = session.run(neighborsCypher, Map.of(
                        "frontier", frontier,
                        "seen", seen,
                        "limit", layerLimit)).list();
                Set<String> nextFrontier = new LinkedHashSet<>();
                for (Record record : records) {
                    List<String> sourceLabels = record.get("source_labels").asList(Value -> Value.asString());
                    Map<String, Object> sourceProperties = record.get("source_properties").asMap();
                    List<String> targetLabels = record.get("target_labels").asList(Value -> Value.asString());
                    Map<String, Object> targetProperties = record.get("target_properties").asMap();
                    Map<String, Object> source = node(
                            logicalId(record.get("source_element_id").asString(), sourceProperties),
                            sourceLabels,
                            sourceProperties,
                            record.get("source_element_id").asString());
                    Map<String, Object> target = node(
                            logicalId(record.get("target_element_id").asString(), targetProperties),
                            targetLabels,
                            targetProperties,
                            record.get("target_element_id").asString());
                    boolean forward = record.get("forward").asBoolean();
                    Map<String, Object> from = forward ? source : target;
                    Map<String, Object> to = forward ? target : source;
                    String fromId = String.valueOf(from.get("id"));
                    String toId = String.valueOf(to.get("id"));
                    nodes.put(fromId, from);
                    nodes.put(toId, to);

                    // target 是查询方向上的邻居 m，关系方向与展示方向无关。
                    String neighborId = String.valueOf(target.get("id"));
                    if (seen.add(neighborId)) nextFrontier.add(neighborId);

                    String relation = record.get("relation_type").asString();
                    String edgeId = fromId + ":" + relation + ":" + toId;
                    if (edgeIds.add(edgeId)) {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", edgeId);
                        edge.put("source", fromId);
                        edge.put("target", toId);
                        edge.put("label", relation);
                        edges.add(edge);
                    }
                }
                frontier = nextFrontier;
            }

            // 库存记录是高频结果节点。即使分层边数先命中了仓库关系，也补齐每条库存对应的物资，
            // 让表格能够展示物资名称和型号，而不是只显示数量、金额等库存字段。
            List<String> inventoryIds = nodes.values().stream()
                    .filter(value -> "库存记录".equals(value.get("kind")))
                    .map(value -> String.valueOf(value.get("id")))
                    .toList();
            if (!inventoryIds.isEmpty()) {
                String materialCypher = """
                        MATCH (rec:库存记录)-[r]-(material:物资)
                        WHERE rec.`标识` IN $inventory_ids
                        RETURN elementId(rec) AS source_element_id, labels(rec) AS source_labels,
                               properties(rec) AS source_properties,
                               elementId(material) AS target_element_id, labels(material) AS target_labels,
                               properties(material) AS target_properties, type(r) AS relation_type,
                               elementId(startNode(r)) = elementId(rec) AS forward
                        LIMIT $limit
                        """;
                int materialLimit = Math.min(inventoryIds.size(), layerLimit * 2);
                List<Record> materialRecords = session.run(materialCypher, Map.of(
                        "inventory_ids", inventoryIds,
                        "limit", materialLimit)).list();
                for (Record record : materialRecords) {
                    List<String> sourceLabels = record.get("source_labels").asList(Value -> Value.asString());
                    Map<String, Object> sourceProperties = record.get("source_properties").asMap();
                    List<String> targetLabels = record.get("target_labels").asList(Value -> Value.asString());
                    Map<String, Object> targetProperties = record.get("target_properties").asMap();
                    Map<String, Object> source = node(
                            logicalId(record.get("source_element_id").asString(), sourceProperties),
                            sourceLabels,
                            sourceProperties,
                            record.get("source_element_id").asString());
                    Map<String, Object> target = node(
                            logicalId(record.get("target_element_id").asString(), targetProperties),
                            targetLabels,
                            targetProperties,
                            record.get("target_element_id").asString());
                    boolean forward = record.get("forward").asBoolean();
                    Map<String, Object> from = forward ? source : target;
                    Map<String, Object> to = forward ? target : source;
                    String fromId = String.valueOf(from.get("id"));
                    String toId = String.valueOf(to.get("id"));
                    nodes.put(fromId, from);
                    nodes.put(toId, to);

                    String relation = record.get("relation_type").asString();
                    String edgeId = fromId + ":" + relation + ":" + toId;
                    if (edgeIds.add(edgeId)) {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", edgeId);
                        edge.put("source", fromId);
                        edge.put("target", toId);
                        edge.put("label", relation);
                        edges.add(edge);
                    }
                }
            }

            double amount = nodes.values().stream()
                    .map(node -> node.get("properties"))
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .mapToDouble(properties -> number(properties.get("金额")))
                    .sum();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("nodeCount", nodes.size());
            stats.put("edgeCount", edges.size());
            stats.put("amount", amount);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("found", true);
            output.put("title", anchor.get("label"));
            output.put("subtitle", "Neo4j 全局查询 · " + hops + " 跳关系");
            output.put("source", "Neo4j 全局查询结果");
            output.put("stats", stats);
            output.put("nodes", nodes.values());
            output.put("edges", edges);
            return output;
        }
    }

    public record GraphRow(String sourceId, List<String> sourceLabels, Map<String, Object> sourceProperties,
                           String relationType, String targetId, List<String> targetLabels,
                           Map<String, Object> targetProperties) {
        /** relationType == null 表示独立锚点节点（无扩展边）。 */
        public boolean standalone() { return relationType == null; }
    }

    private Map<String, Object> node(String id, List<String> labels, Map<String, Object> properties,
                                     String fallbackId) {
        String logicalId = logicalId(fallbackId, properties);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", logicalId);
        output.put("label", displayLabel(labels, logicalId, properties));
        output.put("kind", displayKind(labels));
        output.put("properties", properties);
        return output;
    }

    private String logicalId(String fallbackId, Map<String, Object> properties) {
        Object value = properties == null ? null : properties.get("标识");
        return value == null || String.valueOf(value).isBlank() ? fallbackId : String.valueOf(value);
    }

    private String displayKind(List<String> labels) {
        if (labels == null || labels.isEmpty()) return "实体";
        return labels.stream()
                .filter(label -> Set.of("仓库", "库存记录", "物资", "供应商", "项目", "区域", "资料来源").contains(label))
                .findFirst()
                .orElse(labels.get(0));
    }

    private String displayLabel(List<String> labels, String id, Map<String, Object> properties) {
        String name = properties == null ? null : str(properties.get("名称"));
        if (name != null && !name.isBlank()) return name;
        String kind = displayKind(labels);
        if ("库存记录".equals(kind)) {
            int separator = id.lastIndexOf(':');
            return "库存记录 · " + (separator >= 0 ? id.substring(separator + 1) : id);
        }
        return kind.equals("实体") ? id : kind;
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 证据投影：锚点 = 查询结果行提取的实体名与库存记录 URN。
     * 参数只来自服务端结果行，绝不拼接用户输入。
     */
    public List<GraphRow> evidenceSubgraph(Map<String, List<String>> anchors) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : anchors.entrySet()) {
            for (String v : e.getValue()) {
                if (v == null || v.isBlank()) continue;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", v);
                // DM8 的 WAREHOUSE_DISTR_ID 已含完整 URN；其他锚点按名称匹配
                a.put("urn", v.startsWith("urn:") ? v : "urn:kacp:mock:stock:" + v);
                params.add(a);
            }
        }
        if (params.isEmpty()) return List.of();

        // 固定只读投影：锚点节点 → 一跳邻居（有界）
        // 有界投影：锚点节点（≤12，去重）；仅库存记录节点做 1-hop 扩展（其它实体只取自身），
        // 避免高扇出节点（物资/供应商 → 数千条库存）淹没子图。
        String cypher = """
                UNWIND $anchors AS a
                MATCH (n)
                WHERE (n:库存记录 AND n.标识 = a.urn)
                   OR (n:物资 AND n.名称 = a.name)
                   OR (n:仓库 AND n.名称 = a.name)
                   OR (n:供应商 AND n.名称 = a.name)
                   OR (n:项目 AND n.名称 = a.name)
                WITH DISTINCT n LIMIT 12
                OPTIONAL MATCH (n)-[r]-(m) WHERE n:库存记录
                WITH n, r, m LIMIT 120
                RETURN elementId(n) AS source_id, labels(n) AS source_labels, properties(n) AS source_properties,
                       type(r) AS relation_type,
                       elementId(m) AS target_id, labels(m) AS target_labels, properties(m) AS target_properties
                """;

        List<GraphRow> rows = new ArrayList<>();
        try (Session session = driver().session()) {
            List<Record> records = session.run(cypher, Map.of("anchors", params)).list();
            // 无库存记录锚点时：为实体锚点回链最多 2 条库存记录并做 1-hop 扩展，
            // 保证证据子图始终有可追溯的业务关系边。
            boolean hasRecordAnchor = params.stream().anyMatch(a ->
                    String.valueOf(a.get("urn")).startsWith("urn:kacp:mock:stock:"));
            if (!hasRecordAnchor && !records.isEmpty()) {
                String backlink = """
                        UNWIND $anchors AS a
                        MATCH (n)
                        WHERE (n:物资 AND n.名称 = a.name)
                           OR (n:仓库 AND n.名称 = a.name)
                           OR (n:供应商 AND n.名称 = a.name)
                           OR (n:项目 AND n.名称 = a.name)
                        WITH DISTINCT n LIMIT 10
                        MATCH (n)-[r0]-(rec:库存记录)
                        WITH n, r0, rec LIMIT 12
                        OPTIONAL MATCH (rec)-[r2]-(m2) WHERE m2:物资 OR m2:仓库 OR m2:供应商 OR m2:项目
                        RETURN elementId(n) AS source_id, labels(n) AS source_labels, properties(n) AS source_properties,
                               type(r0) AS relation_type,
                               elementId(rec) AS target_id, labels(rec) AS target_labels, properties(rec) AS target_properties,
                               elementId(m2) AS m2_id, labels(m2) AS m2_labels, properties(m2) AS m2_properties, type(r2) AS m2_rel
                        LIMIT 90
                        """;
                records = session.run(backlink, Map.of("anchors", params)).list();
                for (Record rec : records) {
                    boolean standalone = rec.get("relation_type").isNull();
                    rows.add(new GraphRow(
                            rec.get("source_id").asString(),
                            rec.get("source_labels").asList(v -> v.asString()),
                            rec.get("source_properties").asMap(),
                            standalone ? null : rec.get("relation_type").asString(),
                            standalone ? null : rec.get("target_id").asString(),
                            standalone ? List.of() : rec.get("target_labels").asList(v -> v.asString()),
                            standalone ? Map.of() : rec.get("target_properties").asMap()));
                    // 库存记录 → 物资/仓库/供应商 二跳
                    if (!rec.get("m2_id").isNull()) {
                        rows.add(new GraphRow(
                                rec.get("target_id").asString(),
                                rec.get("target_labels").asList(v -> v.asString()),
                                rec.get("target_properties").asMap(),
                                rec.get("m2_rel").asString(),
                                rec.get("m2_id").asString(),
                                rec.get("m2_labels").asList(v -> v.asString()),
                                rec.get("m2_properties").asMap()));
                    }
                }
                return rows;
            }
            for (Record rec : records) {
                boolean standalone = rec.get("relation_type").isNull();
                rows.add(new GraphRow(
                        rec.get("source_id").asString(),
                        rec.get("source_labels").asList(v -> v.asString()),
                        rec.get("source_properties").asMap(),
                        standalone ? null : rec.get("relation_type").asString(),
                        standalone ? null : rec.get("target_id").asString(),
                        standalone ? List.of() : rec.get("target_labels").asList(v -> v.asString()),
                        standalone ? Map.of() : rec.get("target_properties").asMap()));
            }
        }
        return rows;
    }
}

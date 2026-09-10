package com.kacp.nanwang.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 描述：元模型库读取服务（只读 published 五类定义）
 * <p>
 * 真源在平台 MySQL（apboa_next.semantic_*），服务启动后按 TTL 缓存加载，
 * 问答引擎只消费 published 版本。
 */
@Service
public class MetadataService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${nanwang.meta.url:jdbc:mysql://127.0.0.1:23306/apboa_next?useSSL=false&characterEncoding=utf-8&allowPublicKeyRetrieval=true}")
    private String metaUrl;

    @Value("${nanwang.meta.username:root}")
    private String metaUser;

    @Value("${nanwang.meta.password}")
    private String metaPassword;

    private volatile Cache cache = new Cache(0, List.of(), Map.of(), List.of(), List.of());
    private static final long TTL_MS = 30_000;

    public record TableDef(String defId, String name, String physicalTable, String entityType,
                           String description, List<String> synonyms, List<FieldDef> fields) {}
    public record FieldDef(String defId, String columnName, String businessName, String dataType,
                           String unit, String description, List<String> synonyms,
                           List<String> enumValues, boolean publishedColumn) {}
    public record RelationDef(String defId, String name, String fromEntity, String toEntity,
                              String joinCondition, String neo4jRelation, String description) {}
    public record MetricDef(String defId, String name, String metricType, String expression,
                            String compiledSql, String compiledCypher, List<String> assumptions) {}

    private record Cache(long loadedAt, List<TableDef> tables,
                         Map<String, String> columnLabels,
                         List<RelationDef> relations, List<MetricDef> metrics) {}

    public boolean publishedAvailable() {
        return load().tables.stream().anyMatch(t -> t.fields().stream().anyMatch(FieldDef::publishedColumn));
    }

    public List<TableDef> tables() { return load().tables(); }

    public Map<String, String> columnLabels() { return load().columnLabels(); }

    public List<RelationDef> relations() { return load().relations(); }

    public List<MetricDef> metrics() { return load().metrics(); }

    private Cache load() {
        Cache c = cache;
        if (System.currentTimeMillis() - c.loadedAt() < TTL_MS) return c;
        synchronized (this) {
            c = cache;
            if (System.currentTimeMillis() - c.loadedAt() < TTL_MS) return c;
            Cache fresh = doLoad();
            cache = fresh;
            return fresh;
        }
    }

    private Cache doLoad() {
        try {
            List<TableDef> tables = new ArrayList<>();
            Map<String, String> columnLabels = new LinkedHashMap<>();
            List<RelationDef> relations = new ArrayList<>();
            List<MetricDef> metrics = new ArrayList<>();
            try (Connection conn = DriverManager.getConnection(metaUrl, metaUser, metaPassword)) {
                // 表
                Map<String, JsonNode> payloads = new LinkedHashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT def_id, name, physical_table, entity_type, description, synonyms, payload FROM semantic_table WHERE status='published' ORDER BY def_id");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonNode payload = mapper.readTree(rs.getString("payload"));
                        payloads.put(rs.getString("def_id"), payload);
                        tables.add(new TableDef(
                                rs.getString("def_id"), rs.getString("name"), rs.getString("physical_table"),
                                rs.getString("entity_type"), rs.getString("description"),
                                readStringList(payload.get("synonyms")), List.of()));
                    }
                }
                // 字段
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT table_def_id, def_id, name, column_name, data_type, synonyms, enum_values, is_published_column, payload "
                                + "FROM semantic_field WHERE status='published' ORDER BY table_def_id, column_name");
                     ResultSet rs = ps.executeQuery()) {
                    Map<String, List<FieldDef>> byTable = new LinkedHashMap<>();
                    while (rs.next()) {
                        String tableId = rs.getString("table_def_id");
                        JsonNode payload = mapper.readTree(rs.getString("payload"));
                        FieldDef f = new FieldDef(
                                rs.getString("def_id"), rs.getString("column_name"), rs.getString("name"),
                                rs.getString("data_type"), payload.path("unit").asText(null),
                                payload.path("description").asText(null),
                                readStringList(payload.get("synonyms")),
                                readStringList(rs.getString("enum_values") == null ? null : mapper.readTree(rs.getString("enum_values"))),
                                rs.getInt("is_published_column") == 1);
                        byTable.computeIfAbsent(tableId, k -> new ArrayList<>()).add(f);
                        if (f.publishedColumn()) columnLabels.putIfAbsent(f.columnName(), f.businessName());
                    }
                    tables = tables.stream()
                            .map(t -> new TableDef(t.defId(), t.name(), t.physicalTable(), t.entityType(),
                                    t.description(), t.synonyms(),
                                    byTable.getOrDefault(t.defId(), List.of())))
                            .toList();
                }
                // 关系
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT def_id, name, from_entity, to_entity, join_condition, neo4j_relation, description "
                                + "FROM semantic_relation WHERE status='published' ORDER BY def_id");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        relations.add(new RelationDef(rs.getString("def_id"), rs.getString("name"),
                                rs.getString("from_entity"), rs.getString("to_entity"),
                                rs.getString("join_condition"), rs.getString("neo4j_relation"),
                                rs.getString("description")));
                    }
                }
                // 指标
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT def_id, name, metric_type, expression, compiled_sql, compiled_cypher, assumptions "
                                + "FROM semantic_metric WHERE status='published' ORDER BY def_id");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        metrics.add(new MetricDef(rs.getString("def_id"), rs.getString("name"),
                                rs.getString("metric_type"), rs.getString("expression"),
                                rs.getString("compiled_sql"), rs.getString("compiled_cypher"),
                                readStringList(rs.getString("assumptions") == null ? null : mapper.readTree(rs.getString("assumptions")))));
                    }
                }
            }
            return new Cache(System.currentTimeMillis(), tables, columnLabels, relations, metrics);
        } catch (Exception e) {
            throw new IllegalStateException("元模型库加载失败: " + e.getMessage(), e);
        }
    }

    private List<String> readStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(n.asText()));
        return out;
    }
}

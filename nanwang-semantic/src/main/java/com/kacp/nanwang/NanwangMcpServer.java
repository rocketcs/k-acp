package com.kacp.nanwang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kacp.nanwang.dm.DmQueryService;
import com.kacp.nanwang.graph.Neo4jEvidenceService;
import com.kacp.nanwang.metadata.MetadataService;
import com.kacp.nanwang.trace.TraceStore;
import com.kacp.nanwang.validate.SqlValidator;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import static io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * 描述：南网问数 MCP 工具面（与医保问数治理协议同构）
 * <p>
 * 工具：list_datasets / describe_dataset / semantic_context / query_preflight /
 * query / run_metric / evidence_subgraph。治理在服务端：Agent 不得杜撰列名、
 * 不得跳过预检、不得自行发 Cypher。
 */
@Configuration
public class NanwangMcpServer {

    public static final String DATASET_ID = "power_inventory";
    private static final int DEFAULT_LIMIT = 1000;

    private final MetadataService metadata;
    private final DmQueryService dm;
    private final Neo4jEvidenceService neo4j;
    private final TraceStore traces;
    private final ObjectMapper mapper = new ObjectMapper();
    private final io.modelcontextprotocol.json.McpJsonMapper jsonMapper;

    public NanwangMcpServer(MetadataService metadata, DmQueryService dm,
                            Neo4jEvidenceService neo4j, TraceStore traces,
                            io.modelcontextprotocol.json.McpJsonMapper jsonMapper) {
        this.metadata = metadata;
        this.dm = dm;
        this.neo4j = neo4j;
        this.traces = traces;
        this.jsonMapper = jsonMapper;
    }

    // ------------------------------------------------------------------ 工具注册

    public List<McpServerFeatures.SyncToolSpecification> buildToolSpecifications() {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        specs.add(tool("list_datasets",
                "列出可通过本服务查询的已发布语义数据集。",
                """
                {"type":"object","properties":{},"required":[]}""",
                (ex, req) -> safeJson(listDatasets())));
        specs.add(tool("describe_dataset",
                "描述一个已启用数据集的模型、字段（业务名/同义词/枚举）、关系与口径来源。",
                """
                {"type":"object","properties":{"dataset_id":{"type":"string"}},"required":["dataset_id"]}""",
                (ex, req) -> safeJson(describeDataset(arg(req, "dataset_id")))));
        specs.add(tool("semantic_context",
                "自然语言问数第一步：返回有界的语义上下文（字段白名单、指标、规则）与 trace_id；后续预检/查询需回传该 trace_id。",
                """
                {"type":"object","properties":{"dataset_id":{"type":"string"},"question":{"type":"string"}},"required":["dataset_id","question"]}""",
                (ex, req) -> {
                    String traceId = UUID.randomUUID().toString();
                    String question = arg(req, "question");
                    traces.put(traceId, DATASET_ID, question);
                    return safeJson(semanticContext(question, traceId));
                }));
        specs.add(tool("query_preflight",
                "执行前必调：对只读 SQL 做白名单/只读/黑名单/LIMIT 校验与 EXPLAIN 成本预检。仅 allowed/warning 才允许 query。",
                """
                {"type":"object","properties":{"dataset_id":{"type":"string"},"question":{"type":"string"},"sql":{"type":"string"},"trace_id":{"type":"string","default":null}},"required":["dataset_id","question","sql"]}""",
                (ex, req) -> safeJson(preflight(arg(req, "question"), arg(req, "sql"), argOpt(req, "trace_id")))));
        specs.add(tool("query",
                "执行已预检的只读 MDL SQL 并返回结果表格；仅在 query_preflight 返回 allowed/warning 后调用。",
                """
                {"type":"object","properties":{"dataset_id":{"type":"string"},"sql":{"type":"string"},"limit":{"type":"integer","default":1000},"trace_id":{"type":"string","default":null}},"required":["dataset_id","sql"]}""",
                (ex, req) -> {
                    String traceId = argOpt(req, "trace_id");
                    int limit = req.get("limit") instanceof Number num && num.intValue() > 0
                            ? Math.min(num.intValue(), 10_000) : DEFAULT_LIMIT;
                    return safeJson(query(arg(req, "sql"), limit, traceId, null));
                }));
        specs.add(tool("run_metric",
                "命中已发布指标（计算定义）时优先调用：按指标 DSL 的编译形态执行，口径唯一。",
                """
                {"type":"object","properties":{"dataset_id":{"type":"string"},"metric_id":{"type":"string"},"params":{"type":"object","default":{}},"limit":{"type":"integer","default":1000},"trace_id":{"type":"string","default":null}},"required":["dataset_id","metric_id"]}""",
                (ex, req) -> {
                    String traceId = argOpt(req, "trace_id");
                    int limit = req.get("limit") instanceof Number num && num.intValue() > 0
                            ? Math.min(num.intValue(), 10_000) : DEFAULT_LIMIT;
                    return safeJson(runMetric(arg(req, "metric_id"), limit, traceId));
                }));
        specs.add(tool("evidence_subgraph",
                "有真实查询结果后的必经步骤：以同一 trace_id 的结果行为锚点，投影有界的 Neo4j 只读证据子图（来源/关系/追溯）；不得自行摘取参数或调用裸 Cypher。",
                """
                {"type":"object","properties":{"dataset_id":{"type":"string"},"trace_id":{"type":"string"}},"required":["dataset_id","trace_id"]}""",
                (ex, req) -> safeJson(evidenceSubgraph(arg(req, "trace_id")))));
        return specs;
    }

    @FunctionalInterface
    interface BiFunctionEx {
        String apply(Object exchange, Map<String, Object> args);
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description,
                                                         String schemaJson, BiFunctionEx handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(jsonMapper, schemaJson)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String result = handler.apply(exchange, request.arguments());
                        return CallToolResult.builder().addTextContent(result).build();
                    } catch (Exception e) {
                        // 工具级异常也以结构化 outcome 返回，便于前端展示
                        Map<String, Object> outcome = new LinkedHashMap<>();
                        outcome.put("status", "unavailable");
                        outcome.put("reason", "服务内部错误: " + e.getMessage());
                        return CallToolResult.builder().addTextContent(safeJson(outcome)).build();
                    }
                })
                .build();
    }

    // ------------------------------------------------------------------ 工具实现

    private Map<String, Object> listDatasets() {
        int tables = metadata.tables().size();
        int fields = metadata.tables().stream().mapToInt(t -> t.fields().size()).sum();
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("dataset_id", DATASET_ID);
        ds.put("name", "南网电力物资库存");
        ds.put("status", tables > 0 ? "published" : "empty");
        ds.put("models", tables);
        ds.put("published_columns", fields);
        ds.put("metrics", metadata.metrics().size());
        ds.put("description", "DM8 MOCK_APP 电力物资域（仓库/库存/物资/供应商/项目/区域），五类定义语义层治理");
        return Map.of("datasets", List.of(ds));
    }

    private Map<String, Object> describeDataset(String datasetId) {
        if (!DATASET_ID.equals(datasetId)) return outcome("unavailable", null, "未知数据集: " + datasetId);
        List<Map<String, Object>> models = new ArrayList<>();
        for (MetadataService.TableDef t : metadata.tables()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model_id", t.defId());
            m.put("business_name", t.name());
            m.put("physical_table", t.physicalTable());
            m.put("description", t.description());
            List<Map<String, Object>> cols = new ArrayList<>();
            for (MetadataService.FieldDef f : t.fields()) {
                if (!f.publishedColumn()) continue;
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column", f.columnName());
                col.put("business_name", f.businessName());
                col.put("data_type", f.dataType());
                if (f.unit() != null) col.put("unit", f.unit());
                if (!f.synonyms().isEmpty()) col.put("synonyms", f.synonyms());
                if (!f.enumValues().isEmpty()) col.put("enum_values", f.enumValues());
                cols.add(col);
            }
            m.put("columns", cols);
            models.add(m);
        }
        List<Map<String, Object>> rels = new ArrayList<>();
        for (MetadataService.RelationDef r : metadata.relations()) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("name", r.name());
            rel.put("join", r.joinCondition());
            rels.add(rel);
        }
        List<Map<String, Object>> mets = new ArrayList<>();
        for (MetadataService.MetricDef m : metadata.metrics()) {
            Map<String, Object> met = new LinkedHashMap<>();
            met.put("metric_id", m.defId());
            met.put("name", m.name());
            met.put("expression", m.expression());
            if (!m.assumptions().isEmpty()) met.put("assumptions", m.assumptions());
            mets.add(met);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset_id", DATASET_ID);
        out.put("models", models);
        out.put("relations", rels);
        out.put("metrics", mets);
        out.put("provenance", Map.of("graph_version", neo4j.graphVersion(),
                "data_source", "DM8 MOCK_APP（只读账号 NANWANG_RO）"));
        return out;
    }

    private Map<String, Object> semanticContext(String question, String traceId) {
        List<Map<String, Object>> fields = new ArrayList<>();
        List<String> recommendedColumns = new ArrayList<>();
        for (MetadataService.TableDef t : metadata.tables()) {
            for (MetadataService.FieldDef f : t.fields()) {
                if (!f.publishedColumn()) continue;
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column", f.columnName());
                col.put("business_name", f.businessName());
                col.put("model", t.name());
                if (f.unit() != null) col.put("unit", f.unit());
                if (!f.enumValues().isEmpty()) col.put("enum_values", f.enumValues());
                if (!f.synonyms().isEmpty()) col.put("synonyms", f.synonyms());
                fields.add(col);
                recommendedColumns.add(f.columnName());
            }
        }
        List<Map<String, Object>> mets = new ArrayList<>();
        for (MetadataService.MetricDef m : metadata.metrics()) {
            Map<String, Object> met = new LinkedHashMap<>();
            met.put("metric_id", m.defId());
            met.put("name", m.name());
            met.put("expression", m.expression());
            if (!m.assumptions().isEmpty()) met.put("assumptions", m.assumptions());
            mets.add(met);
        }
        List<Map<String, Object>> rules = List.of(
                Map.of("code", "whitelist_only", "message", "只允许使用 semantic_context 返回的已发布字段，禁止杜撰或改写列名", "severity", "hard"),
                Map.of("code", "preflight_required", "message", "任何 SQL 必须先 query_preflight，allowed/warning 才可 query", "severity", "hard"),
                Map.of("code", "no_concat_operator", "message", "字符串拼接只用 CONCAT()，禁止 ||；日期差用日期相减，禁止 DATEDIFF", "severity", "hard"),
                Map.of("code", "metric_first", "message", "命中指标语义时优先 run_metric，未命中才构造 SQL 并在答案标注非标准口径", "severity", "warning"),
                Map.of("code", "evidence_required", "message", "query 返回非空后必须调用 evidence_subgraph，再整理回答", "severity", "warning"),
                Map.of("code", "anchor_column", "message", "明细/列表查询的 SELECT 必须包含 WAREHOUSE_DISTR_ID（证据锚点列），即使最终表格不展示该列", "severity", "warning"),
                Map.of("code", "chart_output", "message",
                        "聚合/统计/排名类问题，结果 ≥2 行且 ≤50 行时，在 Markdown 表格之后输出一个 ```chart 围栏代码块（严格 JSON）："
                        + "{\"type\":\"bar|line|pie\",\"title\":\"…\",\"xLabel\":\"…\",\"yLabel\":\"…\",\"yUnit\":\"…\",\"data\":[{\"name\":\"…\",\"value\":123}]}。"
                        + "data 逐行来自查询结果，禁止编造或二次计算；金额≥1亿换算为亿元并在 yUnit 标注亿元；"
                        + "构成占比用 pie（≤8 类），排名对比用 bar，时间趋势（TIME_ID 多月份）用 line；明细类问题不出图表", "severity", "warning"),
                Map.of("code", "age_order_template", "message",
                        "库龄（INVENTORY_AGE_NAME）排序必须用档位序号 CASE 模板，由高到低："
                        + "ORDER BY CASE INVENTORY_AGE_NAME WHEN '10年以上' THEN 1 WHEN '5-10年' THEN 2 WHEN '3-5年' THEN 3 WHEN '1-3年' THEN 4 WHEN '1年以内' THEN 5 END ASC；"
                        + "由低到高将 ASC 改为 DESC；禁止直接对 INVENTORY_AGE_NAME 字典序排序", "severity", "warning"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset_id", DATASET_ID);
        out.put("trace_id", traceId);
        out.put("question", question);
        out.put("fields", fields);
        out.put("metrics", mets);
        out.put("recommended_models", metadata.tables().stream().map(MetadataService.TableDef::name).toList());
        out.put("recommended_columns", recommendedColumns);
        out.put("rules", rules);
        out.put("provenance", Map.of(
                "graph_version", neo4j.graphVersion(),
                "data_source", "DM8 MOCK_APP（只读账号）",
                "semantic_source", "元模型库 published 五类定义"));
        return out;
    }

    private Map<String, Object> preflight(String question, String sql, String traceId) {
        if (!metadata.publishedAvailable()) return outcome("unavailable", traceId, "语义层无已发布定义");
        SqlValidator.Result result = validator().validate(sql);
        if (!result.allowed()) {
            return outcome("blocked", traceId, result.message());
        }
        String plan = dm.explain(result.normalizedSql());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", plan == null ? "warning" : "allowed");
        if (traceId != null) out.put("trace_id", traceId);
        out.put("reason", plan == null ? "EXPLAIN 预检不可用，仅通过静态校验" : "静态校验与成本预检通过");
        out.put("tables", result.tables());
        out.put("normalized_sql", result.normalizedSql());
        return out;
    }

    private Map<String, Object> query(String sql, Integer limit, String traceId, String questionFallback) {
        if (!metadata.publishedAvailable()) return outcome("unavailable", traceId, "语义层无已发布定义");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        TraceStore.Trace trace = traces.get(traceId);
        String question = trace != null ? trace.question() : (questionFallback != null ? questionFallback : "");
        if (trace == null) return outcome("blocked", traceId, "缺少有效 trace_id，请先调用 semantic_context");

        SqlValidator.Result result = validator().validate(sql);
        if (!result.allowed()) return outcome("blocked", traceId, result.message());

        try {
            DmQueryService.ExecResult exec = dm.execute(result.normalizedSql(), limit);
            traces.putResult(traceId, new TraceStore.QueryResult(exec.columns(), exec.rows(), limit, exec.truncated()));
            return envelope(traceId, question, exec.columns(), exec.rows(), exec.truncated(),
                    result.normalizedSql(), "free_sql", List.of());
        } catch (Exception e) {
            return outcome("unavailable", traceId, "DM8 执行失败: " + rootMessage(e));
        }
    }

    private Map<String, Object> runMetric(String metricId, Integer limit, String traceId) {
        MetadataService.MetricDef metric = metadata.metrics().stream()
                .filter(m -> m.defId().equals(metricId)).findFirst().orElse(null);
        if (metric == null) {
            return outcome("blocked", traceId, "指标未发布或不存在: " + metricId);
        }
        if (traceId == null) traceId = UUID.randomUUID().toString();
        TraceStore.Trace trace = traces.get(traceId);
        String question = trace != null ? trace.question() : metric.name();
        if (trace == null) return outcome("blocked", traceId, "缺少有效 trace_id，请先调用 semantic_context");

        SqlValidator.Result result = validator().validate(metric.compiledSql());
        if (!result.allowed()) return outcome("blocked", traceId, "指标 SQL 未通过校验: " + result.message());
        try {
            DmQueryService.ExecResult exec = dm.execute(result.normalizedSql(), limit);
            traces.putResult(traceId, new TraceStore.QueryResult(exec.columns(), exec.rows(), limit, exec.truncated()));
            return envelope(traceId, question, exec.columns(), exec.rows(), exec.truncated(),
                    result.normalizedSql(), "metric:" + metric.defId(), metric.assumptions());
        } catch (Exception e) {
            return outcome("unavailable", traceId, "DM8 执行失败: " + rootMessage(e));
        }
    }

    private Map<String, Object> evidenceSubgraph(String traceId) {
        TraceStore.Trace trace = traces.get(traceId);
        TraceStore.QueryResult qr = traces.getResult(traceId);
        if (trace == null || qr == null) {
            return outcome("unavailable", traceId, "无同 trace_id 的查询结果；evidence_subgraph 必须在 query/run_metric 之后调用");
        }
        // 锚点提取：只取结果行中的实体列（服务端行为，不接受调用方参数）
        Map<String, List<String>> anchors = new HashMap<>();
        addAnchor(anchors, qr, "WAREHOUSE_DISTR_ID", 8);
        addAnchor(anchors, qr, "MATERIAL_NAME", 8);
        addAnchor(anchors, qr, "VENDOR_NAME", 8);
        addAnchor(anchors, qr, "PROJECT_NAME", 8);
        addAnchor(anchors, qr, "WAREHOUSE_NAME", 8);
        List<Neo4jEvidenceService.GraphRow> rows = neo4j.evidenceSubgraph(anchors);

        // 节点/边组装（kind/label 契约见前端 evidenceAdapter）
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Set<String> edges = new LinkedHashSet<>();
        for (Neo4jEvidenceService.GraphRow row : rows) {
            Map<String, Object> s = node(row.sourceId(), row.sourceLabels(), row.sourceProperties());
            if (s == null) continue;
            nodes.put((String) s.get("id"), s);
            if (row.standalone()) continue; // 独立锚点节点：入图但不造边
            Map<String, Object> t = node(row.targetId(), row.targetLabels(), row.targetProperties());
            if (t == null || s.get("id").equals(t.get("id"))) continue;
            nodes.put((String) t.get("id"), t);
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", s.get("id") + ":" + row.relationType() + ":" + t.get("id"));
            edge.put("source", s.get("id"));
            edge.put("target", t.get("id"));
            edge.put("label", row.relationType());
            edge.put("kind", "校准自".equals(row.relationType()) ? "provenance" : "business");
            edges.add(safeJson(edge));
        }

        List<String> recordIds = new ArrayList<>();
        if (anchors.get("WAREHOUSE_DISTR_ID") != null) {
            anchors.get("WAREHOUSE_DISTR_ID").forEach(v -> recordIds.add(
                    v.startsWith("urn:") ? v : "urn:kacp:mock:stock:" + v));
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source_record_ids", recordIds);
        evidence.put("nodes", nodes.values());
        evidence.put("edges", edges.stream().map(estr -> {
            try { return mapper.readValue(estr, Map.class); } catch (Exception e) { return Map.of(); }
        }).toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "executed");
        out.put("trace_id", traceId);
        out.put("dataset_id", DATASET_ID);
        out.put("question", trace.question());
        out.put("graph_ref", "nanwang:" + traceId);
        out.put("node_count", nodes.size());
        out.put("edge_count", edges.size());
        out.put("result", resultPayload(qr.columns(), qr.rows(), qr.truncated()));
        out.put("semantic_context", semanticContextPayload());
        out.put("evidence", evidence);
        return out;
    }

    // ------------------------------------------------------------------ 信封组装

    private Map<String, Object> envelope(String traceId, String question, List<String> columns,
                                         List<Map<String, Object>> rows, boolean truncated,
                                         String sql, String intent, List<String> assumptions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "executed");
        out.put("trace_id", traceId);
        out.put("dataset_id", DATASET_ID);
        out.put("question", question);
        out.put("result", resultPayload(columns, rows, truncated));
        out.put("execution_path", Map.of(
                "question", question,
                "intent", intent,
                "stages", List.of(
                        Map.of("step", "1", "system", "Agent", "title", "语义解析", "detail", "字段白名单与指标命中", "icon", "semantic"),
                        Map.of("step", "2", "system", "校验器", "title", "SQL 预检", "detail", "只读/白名单/LIMIT/EXPLAIN", "icon", "preflight"),
                        Map.of("step", "3", "system", "DM8", "title", "数据源执行", "detail", "只读账号 NANWANG_RO", "icon", "database"),
                        Map.of("step", "4", "system", "Neo4j", "title", "证据子图", "detail", "evidence_subgraph 投影", "icon", "graph"),
                        Map.of("step", "5", "system", "合成", "title", "查询结果", "detail", rows.size() + " 行" + (truncated ? "（已截断）" : ""), "icon", "result")),
                "models", metadata.tables().stream().map(MetadataService.TableDef::name).toList(),
                "columns", columns,
                "source_record_count", rows.size(),
                "truncated", truncated));
        if (!assumptions.isEmpty()) out.put("assumptions", assumptions);
        out.put("semantic_context", semanticContextPayload());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source_record_ids", List.of());
        evidence.put("nodes", List.of());
        evidence.put("edges", List.of());
        out.put("evidence", evidence);
        return out;
    }

    private Map<String, Object> resultPayload(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String c : columns) {
            String label = metadata.columnLabels().get(c);
            if (label != null) labels.put(c, label);
        }
        return Map.of(
                "columns", columns,
                "column_labels", labels,
                "rows", rows,
                "truncated", truncated);
    }

    private Map<String, Object> semanticContextPayload() {
        return Map.of(
                "graph_version", neo4j.graphVersion(),
                "recommended_models", metadata.tables().stream().map(MetadataService.TableDef::name).toList(),
                "recommended_columns", metadata.columnLabels().keySet().stream().toList(),
                "rules", List.of(),
                "provenance", Map.of("data_source", "DM8 MOCK_APP", "semantic_source", "元模型库 published 定义"));
    }

    private Map<String, Object> node(String id, List<String> labels, Map<String, Object> props) {
        String label0 = labels == null || labels.isEmpty() ? "" : labels.get(0);
        String kind;
        String label;
        switch (label0) {
            case "库存记录" -> {
                kind = "record";
                Object amt = props.get("金额");
                if (amt instanceof Number num) {
                    double v = num.doubleValue();
                    label = v >= 1e8 ? String.format("库存记录 %.2f亿", v / 1e8)
                          : v >= 1e4 ? String.format("库存记录 %.0f万", v / 1e4)
                          : String.format("库存记录 %.0f元", v);
                } else label = "库存记录";
            }
            case "物资" -> { kind = "product"; label = str(props.get("名称")); }
            case "资料来源" -> { kind = "source"; label = str(props.get("名称")); }
            default -> { kind = "entity"; label = str(props.get("名称")); }
        }
        if (label == null || label.isBlank()) return null;
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "neo4j:" + id);
        n.put("label", label);
        n.put("kind", kind);
        return n;
    }

    private Map<String, Object> outcome(String status, String traceId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        if (traceId != null) out.put("trace_id", traceId);
        out.put("reason", reason);
        return out;
    }

    // ------------------------------------------------------------------ 辅助

    private SqlValidator validator() {
        Set<String> whitelist = new LinkedHashSet<>();
        for (MetadataService.TableDef t : metadata.tables()) {
            String pt = t.physicalTable();
            String bare = pt.contains(".") ? pt.substring(pt.lastIndexOf('.') + 1) : pt;
            whitelist.add(bare.toUpperCase(Locale.ROOT));
        }
        return new SqlValidator(whitelist, DEFAULT_LIMIT);
    }

    private void addAnchor(Map<String, List<String>> anchors, TraceStore.QueryResult qr,
                           String column, int bound) {
        if (!qr.columns().contains(column)) return;
        List<String> vals = new ArrayList<>();
        for (Map<String, Object> row : qr.rows()) {
            Object v = row.get(column);
            if (v != null && !String.valueOf(v).isBlank() && !vals.contains(String.valueOf(v))) vals.add(String.valueOf(v));
            if (vals.size() >= bound) break;
        }
        if (!vals.isEmpty()) anchors.put(column, vals);
    }

    private String arg(Map<String, Object> args, String key) {
        String v = argOpt(args, key);
        if (v == null) throw new IllegalArgumentException("缺少参数: " + key);
        return v;
    }

    private String argOpt(Map<String, Object> args, String key) {
        if (args == null) return null;
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String str(Object v) { return v == null ? null : String.valueOf(v); }

    private String safeJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { return "{\"status\":\"unavailable\",\"reason\":\"序列化失败\"}"; }
    }

    private String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }

    private CallToolResult text(String json) {
        return CallToolResult.builder().addTextContent(json).build();
    }
}

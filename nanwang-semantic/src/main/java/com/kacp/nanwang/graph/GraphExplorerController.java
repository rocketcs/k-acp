package com.kacp.nanwang.graph;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 南网数据管理页面使用的全局图谱只读接口。
 * <p>
 * 页面只能传关键词和节点标识，Cypher 固定在 Neo4jEvidenceService 内部。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class GraphExplorerController {

    private final Neo4jEvidenceService neo4j;

    public GraphExplorerController(Neo4jEvidenceService neo4j) {
        this.neo4j = neo4j;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return neo4j.stats();
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "20") int limit) {
        return neo4j.search(keyword, kind, limit);
    }

    @GetMapping("/view")
    public ResponseEntity<Map<String, Object>> view(
            @RequestParam String id,
            @RequestParam(defaultValue = "1") int hops,
            @RequestParam(defaultValue = "60") int limit) {
        Map<String, Object> graph = neo4j.view(id, hops, limit);
        if (!Boolean.TRUE.equals(graph.get("found"))) return ResponseEntity.notFound().build();
        Map<String, Object> body = new LinkedHashMap<>(graph);
        body.remove("found");
        return ResponseEntity.ok(body);
    }
}

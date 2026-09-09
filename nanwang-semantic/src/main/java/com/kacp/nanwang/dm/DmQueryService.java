package com.kacp.nanwang.dm;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 描述：DM8 只读查询执行器
 */
@Service
public class DmQueryService {

    private final DataSource dataSource;

    public DmQueryService(DataSource dmDataSource) {
        this.dataSource = dmDataSource;
    }

    public record ExecResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {}

    /**
     * 执行已通过校验的只读 SQL；maxRows = limit + 1 用于截断判定。
     */
    public ExecResult execute(String sql, int limit) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(limit + 1);
            stmt.setQueryTimeout(30);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= limit) { truncated = true; break; }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(columns.get(i - 1), rs.getObject(i));
                    rows.add(row);
                }
                return new ExecResult(columns, rows, truncated);
            }
        }
    }

    /**
     * EXPLAIN 成本预检；返回执行计划文本，失败返回 null（降级 warning）。
     */
    public String explain(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(10);
            // DM8 的 EXPLAIN 结果经 getResultSet 或告警返回；逐种方式尝试
            boolean hasRs = stmt.execute("EXPLAIN " + sql);
            StringBuilder sb = new StringBuilder();
            try (ResultSet rs = stmt.getResultSet()) {
                if (rs != null) {
                    while (rs.next()) {
                        sb.append(rs.getString(1)).append('\n');
                        if (sb.length() > 2000) break;
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : (hasRs ? "" : null);
        } catch (Exception e) {
            return null;
        }
    }
}

package com.kacp.nanwang.validate;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 描述：确定性 SQL 校验器（DM8 方言 spike 结论的实现）
 * <p>
 * 规则（docs/architecture/dm8-dialect-spike-report.md §校验器最终规则集）：
 * 1. 多语句 blocked          2. 仅 SELECT/WITH
 * 3. 禁 `||`、DATEDIFF、函数黑名单   4. 表白名单（published 数据定义）
 * 5. 强制 LIMIT（默认 1000）  6. EXPLAIN 预检由调用方执行
 */
public class SqlValidator {

    public record Result(boolean allowed, String message, String normalizedSql, List<String> tables) {
        public static Result blocked(String reason) { return new Result(false, reason, null, List.of()); }
    }

    private static final Set<String> FUNCTION_BLACKLIST = Set.of(
            "SLEEP", "LOAD_FILE", "BENCHMARK", "GET_LOCK", "RELEASE_LOCK",
            "UTL_HTTP", "UTL_FILE", "DBMS_OUTPUT", "DBMS_SQL",
            "CREATE", "EXECUTE", "EXEC", "CALL");

    /** 表白名单（不带 schema、大写）。 */
    private final Set<String> allowedTables;
    private final int defaultLimit;

    public SqlValidator(Set<String> allowedTables, int defaultLimit) {
        this.allowedTables = allowedTables;
        this.defaultLimit = defaultLimit;
    }

    public Result validate(String sql) {
        if (sql == null || sql.isBlank()) return Result.blocked("SQL 为空");
        String trimmed = sql.trim().replaceAll(";\\s*$", "");
        // 字符串字面量内的 || 也直接拒绝（生成侧约定用 CONCAT）
        if (trimmed.contains("||")) return Result.blocked("禁止使用 || 拼接，请使用 CONCAT()");
        if (trimmed.toUpperCase(Locale.ROOT).contains("DATEDIFF")) {
            return Result.blocked("禁止使用 DATEDIFF（DM8 方言差异），日期差请用 日期相减");
        }

        List<Statement> statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed).getStatements();
        } catch (Exception e) {
            return Result.blocked("SQL 解析失败: " + firstLine(e.getMessage()));
        }
        if (statements.size() != 1) return Result.blocked("禁止多语句");

        Statement stmt = statements.get(0);
        if (!(stmt instanceof Select select)) {
            return Result.blocked("仅允许只读查询（SELECT/WITH）");
        }

        // 函数黑名单（正则 + 函数名( 形态）
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (String fn : FUNCTION_BLACKLIST) {
            if (upper.matches(".*(\\b" + fn + "\\s*\\().*")) {
                return Result.blocked("禁止使用的函数: " + fn);
            }
        }

        // 表白名单
        List<String> tables = new ArrayList<>();
        try {
            for (String name : TablesNamesFinder.findTables(String.valueOf(select))) {
                String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                tables.add(bare.toUpperCase(Locale.ROOT));
            }
        } catch (Exception e) {
            return Result.blocked("表名提取失败: " + firstLine(e.getMessage()));
        }
        for (String t : tables) {
            if (!allowedTables.contains(t)) {
                return Result.blocked("表不在已发布数据定义白名单内: " + t);
            }
        }

        // 强制 LIMIT
        int limit = defaultLimit;
        try {
            if (select.getSelectBody() instanceof PlainSelect ps) {
                limit = enforceLimit(ps, defaultLimit);
            } else if (select.getSelectBody() instanceof SetOperationList u) {
                for (Object body : u.getSelects()) {
                    if (body instanceof PlainSelect ps) limit = enforceLimit(ps, defaultLimit);
                }
            }
        } catch (Exception e) {
            return Result.blocked("LIMIT 注入失败: " + firstLine(e.getMessage()));
        }

        return new Result(true, "allowed", select.toString(), tables);
    }

    private int enforceLimit(PlainSelect ps, int defaultLimit) {
        Limit existing = ps.getLimit();
        int effective = defaultLimit;
        if (existing != null && existing.getRowCount() instanceof LongValue lv && lv.getValue() > 0) {
            effective = (int) Math.min(lv.getValue(), 10_000);
        }
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(effective));
        ps.setLimit(limit);
        return effective;
    }

    private String firstLine(String msg) {
        if (msg == null) return "unknown";
        int idx = msg.indexOf('\n');
        return idx > 0 ? msg.substring(0, idx) : msg;
    }
}

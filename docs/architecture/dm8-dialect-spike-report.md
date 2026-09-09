# DM8 方言 Spike 报告（迭代 0 / 任务 0.1）

> 日期：2026-08-30 ｜ 结论：**路线 B（直连 DM8）前提成立**，发现 2 个方言缺口均有可行缓解方案。

## 测试环境

- 容器 `dm8-mock`，DM DBMS 8.1.2.128，JDBC `jdbc:dm://127.0.0.1:5236?compatibleMode=mysql`
- 解析器：sqlglot 30.17.0（Python，MySQL dialect）
- 实测表：MOCK_APP 五张业务表（v0.2）

## 结果矩阵

| # | 能力 | 方式 | 结果 |
|---|---|---|---|
| 1 | 中文列值/LIKE 中文字面量 | JDBC（UTF-8） | ✅（disql 显示乱码仅为终端编码，JDBC 正常） |
| 2 | `compatibleMode=mysql` 连接参数 | JDBC URL | ✅ 生效 |
| 3 | `EXPLAIN <select>` 成本预检 | SQL/执行计划含基数估算 | ✅（JDBC 下 `execute()` 无结果集，需按语句类型处理返回） |
| 4 | `LIMIT n` / `LIMIT n OFFSET m` / `LIMIT n,m` | 执行 | ✅ 三种分页全部可用 |
| 5 | JOIN + GROUP BY + ORDER BY 序号 + LIMIT | 执行 | ✅ |
| 6 | CASE WHEN / GROUP BY 聚合 | 执行 | ✅ |
| 7 | 三参 `DATEDIFF(DD, a, b)` | 执行 | ✅ 可用，但—— |
| 8 | sqlglot round-trip 后的 DATEDIFF | 执行 | ❌ **被重写为两参**（MySQL 语义），DM8 语法错 |
| 9 | 日期减法 `CURRENT_DATE - RECEIPT_DATE` | 执行 | ✅ 返回天数（库龄推导的推荐形态） |
| 10 | `||` 字符串拼接 | 执行 | ✅ 直接执行可用；sqlglot round-trip 后 **`||` 被改写为 OR** ❌ |
| 11 | `CONCAT` / `IFNULL` / `COALESCE` / `TRUNCATE` / `SUBSTRING` | 执行 + round-trip | ✅ 全部可用 |
| 12 | sqlglot 只读判定（SELECT/WITH）| 解析 | ✅ 10/10 用例通过（含多语句、UPDATE、DROP、SLEEP 拦截） |
| 13 | sqlglot 表名提取 + LIMIT 注入 | 解析 | ✅ |

## 方言缺口与对策（写入校验器规则）

| 缺口 | 对策 |
|---|---|
| 三参 DATEDIFF 被 sqlglot 重写为两参 | 指标 DSL 日期差一律编译为**日期减法**（`<end> - <start>`，实测返回天数）；禁止在生成 SQL 中使用 DATEDIFF（校验器拒绝） |
| `\|\|` 被 sqlglot（MySQL dialect）解析为 OR | 生成侧约定只用 `CONCAT()`；校验器直接拒绝包含 `\|\|` 的 SQL |
| EXPLAIN 无结果集形态 | 预检执行 `EXPLAIN ...` 后用 `execute()` + 异常容忍包装；取不到计划时降级为仅静态校验并标记 `warning` |

## 校验器最终规则集（任务 2.1 实现依据）

1. sqlglot（read=mysql）解析，多语句直接 blocked
2. 仅 `SELECT`/`WITH`（Union 归入 SELECT）；其余 blocked
3. 函数黑名单：`SLEEP`、`LOAD_FILE`、`DBMS_*`、`UTL_*`、`DATEDIFF`、`||` 运算符
4. 表白名单（published 数据定义表集合，排除 `*_V01_BAK`）
5. 无 LIMIT → 注入默认 1000（可由问题指定 N 覆盖，上限 10000）
6. `EXPLAIN` 预检，失败降级 warning
7. 行级权限谓词注入（规则定义 row_filter，留桩）

## 复现脚本

- `/tmp/dmspike/Spike.java`（JDBC 执行/中文/EXPLAIN）
- `/tmp/dmspike/Spike2.java`（USAGE_NAME 枚举与库龄口径验证：报废物资 1,996 笔全部 >5 年 ✓）
- `/tmp/dmspike/Spike3.java`（sqlglot round-trip 回放）
- sqlglot 矩阵见本报告正文（Python 内联脚本）

# 南网问数 · 五类定义 DSL 规范 v0.1（迭代 0 / 任务 0.3）

> 定稿于 2026-08-30。本文是元模型库（任务 0.4）与 Agent 起草器、校验器、问答引擎的契约。
> 问答引擎只消费 `published` 版本；所有定义共用统一状态机。

## 状态机（所有五类统一）

```
草稿(draft) → 已确认(confirmed) → 已发布(published) → 已归档(archived)
     ↑                                │
     └────────── 修订(新版本草稿) ◀────┘
```

- 每条定义带 `version`（整数，递增）、`status`、`created_by/confirmed_by/published_at`
- 问答轨迹记录命中定义的 `(definition_id, version)`；归档版本保留供历史答案复现
- Agent 起草只能产生 `draft`；`confirmed→published` 必须人工触发（工作台或对话确认）

## 1. 数据定义（semantic_table / semantic_field）

```json
{
  "id": "tbl-stock-record",
  "name": "库存记录",
  "physicalTable": "MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M",
  "entityType": "stock",
  "description": "库存事实表：每条记录一笔库存分配，数量×单价=金额，含13%增值税",
  "synonyms": ["库存", "库存明细", "库存事实"],
  "rowEstimate": 20000,
  "excluded": false,
  "fields": [
    {
      "id": "fld-stock-amount",
      "name": "库存金额",
      "column": "ACTUAL_TOTAL_PRICE",
      "synonyms": ["金额", "库存价值", "总金额", "价值"],
      "dataType": "decimal(18,2)",
      "unit": "元",
      "nullable": false,
      "description": "库存记录的实际总金额 = 数量 × 单价，含 13% 增值税口径",
      "sampleValues": [4400000.00, 1636.00],
      "isPublishedColumn": true
    },
    {
      "id": "fld-stock-usage",
      "name": "用途",
      "column": "USAGE_NAME",
      "synonyms": ["用途类别", "物资用途"],
      "dataType": "enum",
      "nullable": false,
      "enumValues": ["项目物资", "储备物资", "闲置物资", "报废物资", "战略储备物资", "常规储备物资"],
      "description": "库存用途分类；报废物资必须为老库存（库龄 ≥5 年），与库龄档位强约束",
      "isPublishedColumn": true
    }
  ]
}
```

约束：`isPublishedColumn=true` 的字段集合 = 问数字段白名单（技能生成的依据）；未注册字段禁止出现在 SQL。

## 2. 图定义（semantic_relation）

```json
{
  "id": "rel-stock-supplier",
  "name": "由供应商提供",
  "from": {"entity": "stock", "table": "tbl-stock-record"},
  "to": {"entity": "supplier", "table": "tbl-supplier"},
  "joinCondition": "stock_record.VENDOR_ID = supplier.VENDOR_ID",
  "cardinality": "N:1",
  "neo4jRelation": "由供应商提供",
  "description": "每条库存记录由唯一供应商提供",
  "source": "ontology-mapping-cn-v0.1.json"
}
```

约束：`joinCondition` 仅允许已发布字段的等值连接；`neo4jRelation` 与 v0.2 图谱 6 类中文关系一一对应。

## 3. 计算定义（semantic_metric，指标层）

```json
{
  "id": "metric-aged-scrap-amount",
  "name": "超龄报废库存金额",
  "synonyms": ["超龄报废金额", "报废库存金额"],
  "target": "agg:sum",
  "expression": "SUM(库存记录.金额) WHERE 库存记录.库龄档位 IN ('5-10年','10年以上') AND 库存记录.用途 = '报废物资'",
  "compiled": {
    "sql": "SELECT SUM(ACTUAL_TOTAL_PRICE) FROM MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M WHERE INVENTORY_AGE_NAME IN ('5-10年','10年以上') AND USAGE_NAME = '报废物资'",
    "cypher": "MATCH (r:库存记录)-[:对应物资]->(m:物资) WHERE r.库龄 IN ['5-10年','10年以上'] AND r.用途 = '报废' RETURN sum(r.金额)"
  },
  "assumptions": [
    "库龄档位由入库日期按基准日 2026-08-28 推导（见 calc-stock-age）",
    "'报废' 取用途枚举值'报废物资'，不含闲置(IDLE)"
  ],
  "dependsOn": ["fld-stock-amount", "calc-stock-age", "fld-stock-usage"],
  "materialize": "virtual",
  "resultType": "decimal(18,2)"
}
```

约束：
- `expression` 引用已发布字段/指标；校验器做 DAG 环检测 + 类型检查 + 血缘写入 Neo4j（`:Metric-[:DEPENDS_ON]->:Field/:Metric`、`:Metric-[:ASSUMES]->:Assumption`）
- `compiled.sql` 必须通过任务 2.1 校验器（只读/白名单/LIMIT 规则不适用于聚合指标，但表白名单适用）
- 日期差一律用 `CURRENT_DATE - RECEIPT_DATE`（DM8 方言 spike 结论），禁用 DATEDIFF
- 属性级计算（如库龄档位）也登记为 metric（`target: "calc:scalar"`）

## 4. 规则定义（semantic_rule，MVP 降级为查询约束）

```json
{
  "id": "rule-perm-bureau",
  "type": "row_filter",
  "target": "warehouse",
  "expression": "仓库.省份编码 IN (:user_bureaus)",
  "compiledPredicate": "PROVINCE_CODE IN (:user_bureaus)",
  "appliesTo": ["warehouse", "stock"],
  "description": "地市局用户仅可查询本局仓库",
  "enabled": false
}
```

约束：`type` 仅支持 `row_filter`（MVP）；注入点在 SQL 生成后、EXPLAIN 前；MVP 默认 `enabled=false` 留桩（待确认事项 2）。

## 5. 知识定义（semantic_knowledge_sync，实例层）

```json
{
  "id": "know-instance-sync",
  "type": "instance_sync",
  "graphNamespace": "urn:kacp:mock:",
  "syncMode": "full_rebuild",
  "lastSyncedAt": "2026-08-30T10:00:00+08:00",
  "dataVersion": "v0.2",
  "watermark": {"stock_record": 20000, "warehouse": 48, "material": 240, "supplier": 60, "project": 180, "region": 19},
  "entityValueIndex": ["warehouse_name", "material_name", "vendor_name", "project_name", "province_code"]
}
```

约束：Neo4j 实例层唯一存放处；同步水位 + 数据版本号登记于此；`:EntityValue` 按 `entityValueIndex` 列构建全文索引。

## 校验器（发布门禁）

发布 `confirmed → published` 前自动执行：

1. **DAG 校验**：指标依赖图拓扑排序，检测循环依赖
2. **类型检查**：依赖字段类型不兼容直接拒绝（如对 VARCHAR 求和）
3. **血缘记录**：依赖链写入 Neo4j
4. **SQL 可执行性**：`compiled.sql` 经只读账号 EXPLAIN 通过

## 命名与存储

- 定义 ID：`tbl-*` / `fld-*` / `rel-*` / `metric-*` / `calc-*` / `rule-*` / `know-*`
- 存储：平台 MySQL `apboa_next` 库，JSON 列存 DSL 全文（任务 0.4 表结构）
- 投影：published 定义全量同步到 Neo4j 语义层节点（`:Table/:Field/:Term/:Metric/:Assumption/:EntityValue`）

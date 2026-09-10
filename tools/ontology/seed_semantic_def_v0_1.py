#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
南网问数 · 元模型库种子脚本（迭代 0 / 任务 0.5）

将 docs/ontology/ontology-mapping-cn-v0.1.json（中文本体映射）转换为五类定义 DSL 草稿：
  - 数据定义（semantic_table + semantic_field，含业务名/同义词/枚举/样本）
  - 图定义（semantic_relation，关系 = Join 路径）
并以 draft 状态写入平台 MySQL（apboa_next）。幂等：按 def_id upsert。

表结构与列名来源：DM8 spike 实测（docs/architecture/dm8-dialect-spike-report.md）。

用法：
  python3 tools/ontology/seed_semantic_def_v0_1.py [--host 127.0.0.1 --port 23306]
密码从环境变量 MYSQL_ROOT_PASSWORD 读取（与 k-acp-mysql 容器一致）。
"""
import argparse
import hashlib
import json
import os
import pathlib

import pymysql

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MAPPING_FILE = ROOT / "docs" / "ontology" / "ontology-mapping-cn-v0.1.json"

# ---------------------------------------------------------------- schema（spike 实测）
# 字段元组: (column, 中文名, dataType, synonyms, description, is_published)
TABLES = [
    {
        "def_id": "tbl-stock-record",
        "name": "库存记录",
        "physical_table": "MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M",
        "entity_type": "stock",
        "description": "库存事实表：每条记录一笔库存分配，数量×单价=金额，含13%增值税口径",
        "synonyms": ["库存", "库存明细", "库存事实"],
        "fields": [
            ("WAREHOUSE_DISTR_ID", "库存分配编号", "varchar", ["库存编号", "分配编号"], "业务主键", False),
            ("ACTUAL_WAREHOUSE_ID", "所属仓库编码", "varchar", ["仓库编码"], "关联仓库主数据", False),
            ("MATERIAL_ID", "物资编码", "varchar", ["设备编码"], "关联设备主数据目录", False),
            ("MATERIAL_NAME", "物资名称", "varchar", ["设备名称", "品名"], "如：油浸式配电变压器", True),
            ("MATERIAL_MODEL", "物资型号", "varchar", ["型号", "规格型号"], "如：S13-M-2000/10", True),
            ("MATERIAL_CATEGORY", "物资类别", "varchar", ["设备类别", "品类"], "15 类设备：变压器/开关设备/电缆等", True),
            ("VENDOR_ID", "供应商编码", "varchar", ["厂家编码"], "供应商稳定标识（主数据在图谱层）", False),
            ("VENDOR_NAME", "供应商名称", "varchar", ["厂家", "供应商", "供货单位"], "60 家电力装备企业（模拟）", True),
            ("PROJECT_ID", "项目编码", "varchar", ["工程项目编码"], "仅项目物资非空（主数据在图谱层）", False),
            ("PROJECT_NAME", "项目名称", "varchar", ["工程名称", "项目"], "仅项目物资非空", True),
            ("ACTUAL_QTY", "数量", "decimal(18,2)", ["库存数量", "库存量"], "库存实物数量", True),
            ("UNIT_PRICE", "单价", "decimal(18,2)", ["含税单价"], "元；同供应商同物资价格波动 ≤11%", True),
            ("ACTUAL_TOTAL_PRICE", "库存金额", "decimal(18,2)", ["金额", "库存价值", "总金额", "价值"], "元；= 数量 × 单价，含 13% 增值税", True),
            ("ACTUAL_TOTAL_TAX", "税额", "decimal(18,2)", ["增值税额"], "元；= 金额 × 13%", True),
            ("UNIT_NAME", "计量单位", "varchar", ["单位"], "台/套/米等", True),
            ("USAGE_NAME", "用途", "enum", ["用途类别", "物资用途"], "六枚举：项目物资/储备物资/闲置物资/报废物资/战略储备物资/常规储备物资", True),
            ("RECEIPT_DATE", "入库日期", "timestamp", ["收货日期", "入库时间"], "库龄档位由此强推导", True),
            ("INVENTORY_AGE_NAME", "库龄", "enum", ["库龄档位", "库存年限"], "五档：1年以内/1-3年/3-5年/5-10年/10年以上，无未知", True),
            ("PROVINCE_CODE", "省份编码", "varchar", ["省编码"], "省份稳定标识", False),
            ("BUREAU_CODE", "地市局编码", "varchar", ["地市编码"], "地市供电局稳定标识", False),
            ("VOLTAGE_LEVEL_KV", "电压等级", "decimal", ["电压等级千伏"], "千伏", True),
            ("RATED_CAPACITY_KVA", "额定容量", "decimal", ["容量千伏安"], "千伏安（适用变压器/储能等）", True),
            ("RATED_CURRENT_A", "额定电流", "decimal", ["电流安培"], "安培", True),
            ("DATA_SOURCE", "数据来源", "varchar", [], "来源标记（全部为 mock）", False),
        ],
        "enums": {
            "USAGE_NAME": ["项目物资", "储备物资", "闲置物资", "报废物资", "战略储备物资", "常规储备物资"],
            "INVENTORY_AGE_NAME": ["1年以内", "1-3年", "3-5年", "5-10年", "10年以上"],
        },
    },
    {
        "def_id": "tbl-warehouse",
        "name": "仓库",
        "physical_table": "MOCK_APP.MM_SD_ZNCK_WAREHOUSE_INFO_M",
        "entity_type": "warehouse",
        "description": "仓库主数据：48 个仓库，含按用途聚合的库存金额汇总（与明细严格一致）",
        "synonyms": ["仓库信息", "仓储"],
        "fields": [
            ("WAREHOUSE_ID", "仓库编码", "varchar", ["仓库编号"], "业务主键", False),
            ("WAREHOUSE_NAME", "仓库名称", "varchar", ["库房名称", "仓库"], "如：国网辽宁省电力有限公司沈阳供电公司物资仓库", True),
            ("WAREHOUSE_CODE", "仓库代码", "varchar", [], "", False),
            ("WAREHOUSE_LEVEL", "仓库等级编码", "int", [], "1区域仓/2周转仓/3急救包/4施工现场", False),
            ("WAREHOUSE_LEVEL_NAME", "仓库等级", "enum", ["库级"], "区域仓/周转仓/急救包/施工现场", True),
            ("WAREHOUSE_ADDR", "仓库地址", "varchar", ["地址"], "", True),
            ("GEOGRAPHICAL_LNG", "经度", "decimal", [], "", False),
            ("GEOGRAPHICAL_LAT", "纬度", "decimal", [], "", False),
            ("IDLE_AMT", "闲置物资金额", "decimal(18,2)", ["闲置金额"], "元；按用途聚合回填，与明细一致", True),
            ("SCRAP_AMT", "报废物资金额", "decimal(18,2)", ["报废金额"], "元", True),
            ("RESERVE_AMT", "储备物资金额", "decimal(18,2)", ["储备金额"], "元", True),
            ("PROJECT_AMT", "项目物资金额", "decimal(18,2)", ["项目金额"], "元", True),
            ("TOTAL_AMT", "库存总金额", "decimal(18,2)", ["总金额", "仓库库存金额"], "元 = 四类用途金额之和", True),
            ("PROVINCE_CODE", "省份编码", "varchar", ["省编码"], "", False),
            ("BUREAU_CODE", "地市局编码", "varchar", ["地市编码"], "", False),
            ("DATA_SOURCE", "数据来源", "varchar", [], "", False),
        ],
        "enums": {"WAREHOUSE_LEVEL_NAME": ["区域仓", "周转仓", "急救包", "施工现场"]},
    },
    {
        "def_id": "tbl-material-catalog",
        "name": "物资目录",
        "physical_table": "MOCK_APP.MOCK_EQUIPMENT_CATALOG",
        "entity_type": "material",
        "description": "设备主数据目录：240 个物资型号，覆盖 15 类电力设备，含公开资料来源追溯",
        "synonyms": ["设备目录", "主数据目录", "设备主数据"],
        "fields": [
            ("MATERIAL_ID", "物资编码", "varchar", ["设备编码"], "业务主键", False),
            ("MATERIAL_NAME", "物资名称", "varchar", ["设备名称", "品名"], "", True),
            ("MATERIAL_MODEL", "物资型号", "varchar", ["型号"], "", True),
            ("MATERIAL_CATEGORY", "物资类别", "varchar", ["设备类别"], "15 类", True),
            ("MANUFACTURER", "制造商", "varchar", ["生产厂家", "制造企业"], "", True),
            ("RATED_VOLTAGE_KV", "额定电压", "decimal", ["电压等级"], "千伏", True),
            ("RATED_CAPACITY_KVA", "额定容量", "decimal", ["容量"], "千伏安", True),
            ("RATED_CURRENT_A", "额定电流", "decimal", ["电流"], "安培", True),
            ("UNIT_NAME", "计量单位", "varchar", ["单位"], "", True),
            ("BASE_PRICE", "基准单价", "decimal(18,2)", ["基准价"], "元；价格锚点", True),
            ("SOURCE_ID", "来源编码", "varchar", [], "关联 MOCK_DATA_PROVENANCE", False),
            ("SOURCE_NOTE", "来源说明", "varchar", ["资料来源"], "", True),
        ],
        "enums": {},
    },
    {
        "def_id": "tbl-war-money",
        "name": "库存金额汇总",
        "physical_table": "MOCK_APP.MM_SD_ZTFX_WAR_MONEY_K",
        "entity_type": "summary",
        "description": "库存金额分析汇总（省份×地市×用途×仓库等级），与明细严格聚合一致；校验基准表",
        "synonyms": ["金额汇总", "库存分析汇总"],
        "fields": [
            ("SEQ_ID", "记录编号", "varchar", [], "", False),
            ("TIME_ID", "时间编号", "varchar", ["统计时间"], "", False),
            ("USAGE_TYPE_ID", "用途编码", "int", [], "1项目/2储备/3闲置/4报废/5战略储备/6常规储备", True),
            ("WAREHOUSE_LEVEL_ID", "仓库等级编码", "int", [], "", False),
            ("WAREHOUSE_MONEY", "库存金额", "decimal(18,2)", ["金额"], "元", True),
            ("WAREHOUSE_PRE_MONEY", "库存期初金额", "decimal(18,2)", ["期初金额"], "元", True),
            ("PROVINCE_CODE", "省份编码", "varchar", ["省编码"], "", False),
            ("BUREAU_CODE", "地市局编码", "varchar", ["地市编码"], "", False),
            ("DATA_SOURCE", "数据来源", "varchar", [], "", False),
        ],
        "enums": {},
    },
    {
        "def_id": "tbl-provenance",
        "name": "资料来源",
        "physical_table": "MOCK_APP.MOCK_DATA_PROVENANCE",
        "entity_type": "provenance",
        "description": "公开资料来源追溯：设备目录校准的出处记录（证据链源头）",
        "synonyms": ["数据来源", "溯源资料"],
        "fields": [
            ("SOURCE_ID", "来源编码", "varchar", [], "业务主键", False),
            ("SOURCE_NAME", "来源名称", "varchar", ["资料名称"], "", True),
            ("SOURCE_URL", "来源地址", "varchar", ["URL"], "", True),
            ("SOURCE_NOTE", "来源说明", "varchar", [], "", True),
            ("RETRIEVED_ON", "取数日期", "date", ["获取日期"], "", True),
            ("DATA_STATUS", "数据状态", "varchar", [], "", False),
        ],
        "enums": {},
    },
]

# 图定义：来自 ontology-mapping-cn-v0.1.json 的 关系 字段（关系 = Join 路径）
# (def_id, 名称, from_entity, to_entity, join_condition, cardinality, neo4j_relation, nullable, source_note)
RELATIONS = [
    ("rel-stock-warehouse", "对应仓库", "stock", "warehouse",
     "stock_record.ACTUAL_WAREHOUSE_ID = warehouse.WAREHOUSE_ID", "N:1", "对应仓库", False,
     "ontology-mapping: 对应仓库/来源字段 actual_warehouse_id"),
    ("rel-stock-material", "对应物资", "stock", "material",
     "stock_record.MATERIAL_ID = material.MATERIAL_ID", "N:1", "对应物资", False,
     "ontology-mapping: 对应物资/来源字段 material_id"),
    ("rel-stock-supplier", "由供应商提供", "stock", "supplier",
     "stock_record.VENDOR_ID = supplier.vendor_id（供应商主数据仅存图谱层，DM8 侧经 VENDOR_NAME/VENDOR_ID 冗余列）",
     "N:1", "由供应商提供", False, "ontology-mapping: 由供应商提供/来源字段 vendor_id"),
    ("rel-stock-project", "归属项目", "stock", "project",
     "stock_record.PROJECT_ID = project.project_id（项目主数据仅存图谱层，DM8 侧经 PROJECT_NAME/PROJECT_ID 冗余列）",
     "N:1", "归属项目", True, "ontology-mapping: 归属项目/来源字段 project_id，可为空"),
    ("rel-warehouse-region", "位于区域", "warehouse", "region",
     "warehouse.PROVINCE_CODE = region.province_code AND warehouse.BUREAU_CODE = region.bureau_code",
     "N:1", "位于区域", False, "ontology-mapping: 位于区域/来源字段 [province_code, bureau_code]"),
    ("rel-material-provenance", "校准自", "material", "provenance",
     "material.SOURCE_ID = provenance.SOURCE_ID", "N:1", "校准自", False,
     "图谱 v0.2 关系：校准自（设备目录价格/参数校准出处）"),
    ("rel-warehouse-stock", "包含库存", "warehouse", "stock",
     "warehouse.WAREHOUSE_ID = stock_record.ACTUAL_WAREHOUSE_ID", "1:N", "包含库存", False,
     "ontology-mapping: 包含库存（对应仓库的逆关系，图查询用）"),
]


# 计算定义（指标，问数的灵魂）：expression 引用已发布字段；compiled 为 DM8 SQL
# 口径假设 assumptions 必须显式列出（发布前人工确认）
METRICS = [
    {"def_id": "metric-total-stock-amount", "name": "全省库存总金额", "metric_type": "agg:sum",
     "expression": "SUM(库存记录.金额)",
     "compiled_sql": "SELECT SUM(ACTUAL_TOTAL_PRICE) AS TOTAL_AMOUNT FROM MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M",
     "compiled_cypher": "MATCH (r:库存记录) RETURN sum(r.金额) AS 金额",
     "assumptions": ["金额=数量×单价，含13%增值税口径", "全省口径，不区分省份/地市"]},
    {"def_id": "metric-aged-scrap-amount", "name": "超龄报废库存金额", "metric_type": "agg:sum",
     "expression": "SUM(库存记录.金额) WHERE 库龄档位 IN ('5-10年','10年以上') AND 用途 = '报废物资'",
     "compiled_sql": "SELECT SUM(ACTUAL_TOTAL_PRICE) AS TOTAL_AMOUNT, COUNT(*) AS RECORD_COUNT FROM MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M WHERE INVENTORY_AGE_NAME IN ('5-10年','10年以上') AND USAGE_NAME = '报废物资'",
     "compiled_cypher": "MATCH (r:库存记录) WHERE r.库龄 IN ['5-10年','10年以上'] AND r.用途 = '报废' RETURN sum(r.金额) AS 金额",
     "assumptions": ["库龄档位由入库日期按基准日 2026-08-28 推导", "'报废' 取用途枚举值'报废物资'，不含闲置"]},
    {"def_id": "metric-idle-stock-amount", "name": "闲置库存金额", "metric_type": "agg:sum",
     "expression": "SUM(库存记录.金额) WHERE 用途 = '闲置物资'",
     "compiled_sql": "SELECT SUM(ACTUAL_TOTAL_PRICE) AS TOTAL_AMOUNT, COUNT(*) AS RECORD_COUNT FROM MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M WHERE USAGE_NAME = '闲置物资'",
     "compiled_cypher": "MATCH (r:库存记录) WHERE r.用途 = '闲置' RETURN sum(r.金额) AS 金额",
     "assumptions": ["'闲置' 取用途枚举值'闲置物资'"]},
    {"def_id": "metric-stock-by-category", "name": "分类库存金额明细", "metric_type": "agg:group",
     "expression": "物资类别 + SUM(库存记录.金额) GROUP BY 物资类别",
     "compiled_sql": "SELECT MATERIAL_CATEGORY AS 物资类别, SUM(ACTUAL_TOTAL_PRICE) AS 库存金额, COUNT(*) AS 记录数 FROM MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M GROUP BY MATERIAL_CATEGORY ORDER BY 库存金额 DESC",
     "compiled_cypher": "MATCH (r:库存记录)-[:对应物资]->(m:物资) RETURN m.类别 AS 物资类别, sum(r.金额) AS 金额 ORDER BY 金额 DESC",
     "assumptions": ["按 DM8 明细聚合；与仓库汇总表口径一致（已审计）"]},
    {"def_id": "metric-stock-by-usage", "name": "用途库存金额明细", "metric_type": "agg:group",
     "expression": "用途 + SUM(库存记录.金额) GROUP BY 用途",
     "compiled_sql": "SELECT USAGE_NAME AS 用途, SUM(ACTUAL_TOTAL_PRICE) AS 库存金额, COUNT(*) AS 记录数 FROM MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M GROUP BY USAGE_NAME ORDER BY 库存金额 DESC",
     "compiled_cypher": "MATCH (r:库存记录) RETURN r.用途 AS 用途, sum(r.金额) AS 金额 ORDER BY 金额 DESC",
     "assumptions": ["六类用途枚举"]},
    {"def_id": "metric-warehouse-amount-top", "name": "仓库库存金额排行", "metric_type": "agg:rank",
     "expression": "仓库名称 + SUM(库存记录.金额) GROUP BY 仓库名称 ORDER BY SUM DESC",
     "compiled_sql": "SELECT W.WAREHOUSE_NAME AS 仓库名称, SUM(D.ACTUAL_TOTAL_PRICE) AS 库存金额 FROM MOCK_APP.MM_SD_ZNCK_WAREHOUSE_DISTR_M D JOIN MOCK_APP.MM_SD_ZNCK_WAREHOUSE_INFO_M W ON D.ACTUAL_WAREHOUSE_ID = W.WAREHOUSE_ID GROUP BY W.WAREHOUSE_NAME ORDER BY 库存金额 DESC",
     "compiled_cypher": "MATCH (w:仓库)-[:包含库存]->(r:库存记录) RETURN w.名称 AS 仓库名称, sum(r.金额) AS 金额 ORDER BY 金额 DESC",
     "assumptions": ["按仓库名称去重聚合（48 仓库名唯一）", "默认返回全部仓库，问数时可加 LIMIT"]},
]


def def_id_hash(def_id: str) -> int:
    """稳定 id：def_id 的 md5 前 15 位（63bit 内，避免自增冲突）。"""
    return int(hashlib.md5(def_id.encode()).hexdigest()[:15], 16)


def build_payloads():
    tables, fields, relations = [], [], []
    for t in TABLES:
        field_rows = []
        for col, cname, dtype, syn, desc, pub in t["fields"]:
            payload = {
                "id": f"fld-{t['entity_type']}-{col.lower()}",
                "table": t["def_id"],
                "column": col,
                "businessName": cname,
                "synonyms": syn,
                "dataType": dtype,
                "description": desc,
                "isPublishedColumn": pub,
            }
            if col in t.get("enums", {}):
                payload["enumValues"] = t["enums"][col]
            field_rows.append((f"fld-{t['entity_type']}-{col.lower()}", cname, col, payload))
            payload["tableDefId"] = t["def_id"]
        tables.append({
            "def_id": t["def_id"], "name": t["name"], "physical_table": t["physical_table"],
            "entity_type": t["entity_type"], "description": t["description"],
            "synonyms": t["synonyms"], "payload": {
                **{k: t[k] for k in ("def_id", "name", "physical_table", "entity_type", "description", "synonyms")},
                "entityType": t["entity_type"], "fields": [f[3] for f in field_rows],
            },
        })
        fields.append((t["def_id"], field_rows))
    for def_id, name, fe, te, join, card, neo4j_rel, nullable, note in RELATIONS:
        relations.append({
            "def_id": def_id, "name": name,
            "from_entity": fe, "to_entity": te,
            "join_condition": join, "cardinality": card,
            "neo4j_relation": neo4j_rel,
            "description": note,
            "payload": {
                "id": def_id, "name": name,
                "from": {"entity": fe}, "to": {"entity": te},
                "joinCondition": join, "cardinality": card,
                "neo4jRelation": neo4j_rel,
                "nullableJoin": nullable, "source": note,
            },
        })
    return tables, fields, relations


UPSERTS = {
    "semantic_table": """INSERT INTO semantic_table
        (id, def_id, name, physical_table, entity_type, description, synonyms, status, version, payload, tenant_id)
        VALUES (%s,%s,%s,%s,%s,%s,%s,'draft',1,%s,1)
        ON DUPLICATE KEY UPDATE name=VALUES(name), physical_table=VALUES(physical_table),
        description=VALUES(description), synonyms=VALUES(synonyms), payload=VALUES(payload)""",
    "semantic_field": """INSERT INTO semantic_field
        (id, def_id, table_def_id, name, column_name, data_type, synonyms, is_published_column, status, version, payload, tenant_id)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'draft',1,%s,1)
        ON DUPLICATE KEY UPDATE name=VALUES(name), column_name=VALUES(column_name),
        data_type=VALUES(data_type), synonyms=VALUES(synonyms), payload=VALUES(payload)""",
    "semantic_metric": """INSERT INTO semantic_metric
        (id, def_id, name, metric_type, expression, compiled_sql, compiled_cypher, assumptions, status, version, payload, tenant_id)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'draft',1,%s,1)
        ON DUPLICATE KEY UPDATE name=VALUES(name), metric_type=VALUES(metric_type),
        expression=VALUES(expression), compiled_sql=VALUES(compiled_sql),
        compiled_cypher=VALUES(compiled_cypher), assumptions=VALUES(assumptions), payload=VALUES(payload)""",
    "semantic_relation": """INSERT INTO semantic_relation
        (id, def_id, name, from_entity, to_entity, join_condition, cardinality, neo4j_relation, description, status, version, payload, tenant_id)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'draft',1,%s,1)
        ON DUPLICATE KEY UPDATE name=VALUES(name), join_condition=VALUES(join_condition),
        neo4j_relation=VALUES(neo4j_relation), description=VALUES(description), payload=VALUES(payload)""",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=23306)
    ap.add_argument("--db", default="apboa_next")
    ap.add_argument("--user", default="root")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--publish", action="store_true", help="写入后将全部定义置为 published（含发布审计）")
    args = ap.parse_args()

    mapping = json.loads(MAPPING_FILE.read_text(encoding="utf-8"))
    tables, fields, relations = build_payloads()
    print(f"从 {MAPPING_FILE.name} 转换：")
    print(f"  命名空间 {mapping.get('命名空间')}，类 {len(mapping.get('类', {}))}，关系 {len(mapping.get('关系', {}))}")
    print(f"  生成 数据定义 {len(tables)} 张表 / {sum(len(fr) for _, fr in fields)} 字段；图定义 {len(relations)} 条关系")

    if args.dry_run:
        for t in tables:
            print(f"  [dry] tbl: {t['def_id']} ({t['physical_table']})")
        for r in relations:
            print(f"  [dry] rel: {r['def_id']} {r['name']}: {r['join_condition'][:60]}")
        return

    conn = pymysql.connect(
        host=args.host, port=args.port, database=args.db, user=args.user,
        password=os.environ["MYSQL_ROOT_PASSWORD"], charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            for t in tables:
                cur.execute(UPSERTS["semantic_table"], (
                    def_id_hash(t["def_id"]), t["def_id"], t["name"], t["physical_table"],
                    t["entity_type"], t["description"], json.dumps(t["synonyms"], ensure_ascii=False),
                    json.dumps(t["payload"], ensure_ascii=False)))
            for table_def_id, field_rows in fields:
                for def_id, cname, col, payload in field_rows:
                    cur.execute(UPSERTS["semantic_field"], (
                        def_id_hash(def_id), def_id, table_def_id, cname, col,
                        payload.get("dataType"), json.dumps(payload.get("synonyms", []), ensure_ascii=False),
                        1 if payload.get("isPublishedColumn") else 0,
                        json.dumps(payload, ensure_ascii=False)))
            for r in relations:
                cur.execute(UPSERTS["semantic_relation"], (
                    def_id_hash(r["def_id"]), r["def_id"], r["name"], r["from_entity"],
                    r["to_entity"], r["join_condition"], r["cardinality"], r["neo4j_relation"],
                    r["description"], json.dumps(r["payload"], ensure_ascii=False)))
            for m in METRICS:
                payload = {k: m[k] for k in ("def_id", "name", "metric_type", "expression", "assumptions")}
                cur.execute(UPSERTS["semantic_metric"], (
                    def_id_hash(m["def_id"]), m["def_id"], m["name"], m["metric_type"], m["expression"],
                    m["compiled_sql"], m["compiled_cypher"], json.dumps(m["assumptions"], ensure_ascii=False),
                    json.dumps(payload, ensure_ascii=False)))
            cur.execute("INSERT INTO semantic_audit_log (def_table, def_id, action, operator, detail) VALUES (%s,%s,%s,%s,%s)",
                        ("seed", "ontology-mapping-cn-v0.1", "draft_seed", "seed-script",
                         json.dumps({"tables": len(tables), "fields": sum(len(fr) for _, fr in fields),
                                     "relations": len(relations), "metrics": len(METRICS)}, ensure_ascii=False)))
            if args.publish:
                for tbl in ("semantic_table", "semantic_field", "semantic_relation", "semantic_metric"):
                    cur.execute(f"UPDATE {tbl} SET status='published', published_at=NOW() WHERE status IN ('draft','confirmed')")
                cur.execute("INSERT INTO semantic_audit_log (def_table, def_id, action, operator, detail) VALUES (%s,%s,%s,%s,%s)",
                            ("all", "*", "publish", "seed-script",
                             json.dumps({"reason": "首次语义层发布（人工确认由需求方在演示前完成）",
                                         "metrics": len(METRICS)}, ensure_ascii=False)))
        conn.commit()
        print(f"✅ 已写入 {args.db}：{len(tables)} 表 / {sum(len(fr) for _, fr in fields)} 字段 / {len(relations)} 关系 / {len(METRICS)} 指标"
              + ("（已发布）" if args.publish else "（draft）"))
    finally:
        conn.close()


if __name__ == "__main__":
    main()

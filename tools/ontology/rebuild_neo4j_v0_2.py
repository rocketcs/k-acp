#!/usr/bin/env python3
"""从 v0.2 CSV 重建 Neo4j 中文图谱（k-acp-neo4j-mock, bolt://127.0.0.1:7689）。

节点属性名与 v0.1 保持一致（中文）；入库日期存字符串避免 Cypher 类型比较坑。
关系：包含库存 / 对应物资 / 由供应商提供 / 归属项目 / 位于区域 / 校准自。
"""

from __future__ import annotations

import csv
from collections import defaultdict
from pathlib import Path

from neo4j import GraphDatabase

DIR = Path(__file__).resolve().parents[2] / "data" / "dm8-mock-v0.2"
URI, USER, PASSWORD = "bolt://127.0.0.1:7689", "neo4j", "MockGraph2026!"

PROVINCE_FULL = {"内蒙古": "内蒙古自治区", "黑龙江": "黑龙江省"}
REGION_URN = {
    "北京": "urn:kacp:mock:region:RG011", "天津": "urn:kacp:mock:region:RG012",
    "河北": "urn:kacp:mock:region:RG013", "山西": "urn:kacp:mock:region:RG014",
    "内蒙古": "urn:kacp:mock:region:RG015", "辽宁": "urn:kacp:mock:region:RG021",
    "吉林": "urn:kacp:mock:region:RG022", "黑龙江": "urn:kacp:mock:region:RG023",
    "上海": "urn:kacp:mock:region:RG031", "江苏": "urn:kacp:mock:region:RG032",
    "浙江": "urn:kacp:mock:region:RG033", "安徽": "urn:kacp:mock:region:RG034",
    "福建": "urn:kacp:mock:region:RG035", "江西": "urn:kacp:mock:region:RG036",
    "山东": "urn:kacp:mock:region:RG037", "河南": "urn:kacp:mock:region:RG041",
    "湖北": "urn:kacp:mock:region:RG042", "湖南": "urn:kacp:mock:region:RG043",
    "广东": "urn:kacp:mock:region:RG044",
}

def read(name: str) -> list[dict]:
    with open(DIR / name, encoding="utf-8") as f:
        return list(csv.DictReader(f))

def region_name(prov: str) -> str:
    base = PROVINCE_FULL.get(prov, prov + "省")
    return base

def main() -> None:
    provenance = read("provenance.csv")
    catalog = read("catalog.csv")
    warehouses = read("warehouse.csv")
    inventory = read("inventory.csv")

    # 供应商全集（60 家）与项目全集（180 个）
    supplier_ids = {r["VENDOR_ID"]: r["VENDOR_NAME"] for r in inventory}
    for i in range(1, 61):
        supplier_ids.setdefault(f"urn:kacp:mock:supplier:SUP{i:03d}", None)
    project_rows = read("project.csv")

    driver = GraphDatabase.driver(URI, auth=(USER, PASSWORD))
    with driver.session(database="neo4j") as s:
        s.run("MATCH (n) DETACH DELETE n")

        # 资料来源
        s.run("""
            UNWIND $rows AS r CREATE (:资料来源 {标识: r.id, 名称: r.name, 网址: r.url,
                说明: r.note, 获取日期: r.date, 数据状态: r.status, 是否模拟: 1})
        """, rows=[{"id": r["SOURCE_ID"], "name": r["SOURCE_NAME"], "url": r["SOURCE_URL"],
                    "note": r["SOURCE_NOTE"], "date": r["RETRIEVED_ON"], "status": r["DATA_STATUS"]} for r in provenance])

        # 物资
        s.run("""
            UNWIND $rows AS r CREATE (:物资 {标识: r.id, 名称: r.name, 型号: r.model, 类别: r.cat,
                制造商: r.mfr, 电压等级千伏: r.kv, 额定容量千伏安: r.cap, 额定电流安: r.cur,
                计量单位: r.unit, 基准价: r.price, 来源标识: r.src, 是否模拟: 1})
        """, rows=[{"id": r["MATERIAL_ID"], "name": r["MATERIAL_NAME"], "model": r["MATERIAL_MODEL"],
                    "cat": r["MATERIAL_CATEGORY"], "mfr": r["MANUFACTURER"],
                    "kv": float(r["RATED_VOLTAGE_KV"]), "cap": float(r["RATED_CAPACITY_KVA"]),
                    "cur": float(r["RATED_CURRENT_A"]), "unit": r["UNIT_NAME"],
                    "price": float(r["BASE_PRICE"]), "src": r["SOURCE_ID"]} for r in catalog])

        # 区域
        provs = {r["PROVINCE_CODE"]: None for r in warehouses}
        # 由省份编码反查省份名：从仓库名提取
        name_by_code = {}
        for r in warehouses:
            prov = r["WAREHOUSE_NAME"].split("国网")[1].split("省电力")[0]
            name_by_code[r["PROVINCE_CODE"]] = prov
        s.run("""
            UNWIND $rows AS r CREATE (:区域 {标识: r.id, 名称: r.name, 省份编码: r.code, 是否模拟: 1})
        """, rows=[{"id": REGION_URN[prov], "name": region_name(prov), "code": code}
                   for code, prov in name_by_code.items()])

        # 仓库
        s.run("""
            UNWIND $rows AS r CREATE (:仓库 {标识: r.id, 名称: r.name, 编码: r.code,
                等级: r.level, 等级名称: r.levelName, 地址: r.addr, 经度: r.lng, 纬度: r.lat,
                闲置金额: r.idle, 报废金额: r.scrap, 储备金额: r.reserve, 项目金额: r.proj,
                总金额: r.total, 省份编码: r.pcode, 局编码: r.bcode, 数据来源: r.src, 是否模拟: 1})
        """, rows=[{"id": r["WAREHOUSE_ID"], "name": r["WAREHOUSE_NAME"], "code": r["WAREHOUSE_CODE"],
                    "level": int(r["WAREHOUSE_LEVEL"]), "levelName": r["WAREHOUSE_LEVEL_NAME"],
                    "addr": r["WAREHOUSE_ADDR"], "lng": float(r["GEOGRAPHICAL_LNG"]),
                    "lat": float(r["GEOGRAPHICAL_LAT"]), "idle": float(r["IDLE_AMT"]),
                    "scrap": float(r["SCRAP_AMT"]), "reserve": float(r["RESERVE_AMT"]),
                    "proj": float(r["PROJECT_AMT"]), "total": float(r["TOTAL_AMT"]),
                    "pcode": r["PROVINCE_CODE"], "bcode": r["BUREAU_CODE"], "src": r["DATA_SOURCE"]} for r in warehouses])

        # 供应商
        sup_rows = [{"id": sid, "name": name or f"模拟供货中心{sid[-3:]}"}
                    for sid, name in sorted(supplier_ids.items())]
        s.run("UNWIND $rows AS r CREATE (:供应商 {标识: r.id, 名称: r.name, 是否模拟: 1})", rows=sup_rows)

        # 项目
        s.run("UNWIND $rows AS r CREATE (:项目 {标识: r.id, 名称: r.name, 是否模拟: 1})",
              rows=[{"id": r["PROJECT_ID"], "name": r["PROJECT_NAME"]} for r in project_rows])

        # 库存记录（分批）
        BATCH = 2000
        for start in range(0, len(inventory), BATCH):
            chunk = inventory[start:start + BATCH]
            s.run("""
                UNWIND $rows AS r CREATE (:库存记录 {标识: r.id, 数量: r.qty, 单价: r.price,
                    金额: r.amount, 税额: r.tax, 计量单位: r.unit, 用途: r.usage,
                    入库日期: r.date, 库龄: r.age, 电压等级千伏: r.kv,
                    额定容量千伏安: r.cap, 额定电流安: r.cur,
                    省份编码: r.pcode, 局编码: r.bcode, 数据来源: r.src, 是否模拟: 1})
            """, rows=[{"id": r["WAREHOUSE_DISTR_ID"], "qty": float(r["ACTUAL_QTY"]),
                        "price": float(r["UNIT_PRICE"]), "amount": float(r["ACTUAL_TOTAL_PRICE"]),
                        "tax": float(r["ACTUAL_TOTAL_TAX"]), "unit": r["UNIT_NAME"],
                        "usage": r["USAGE_NAME"], "date": r["RECEIPT_DATE"][:10],
                        "age": r["INVENTORY_AGE_NAME"], "kv": float(r["VOLTAGE_LEVEL_KV"]),
                        "cap": float(r["RATED_CAPACITY_KVA"]), "cur": float(r["RATED_CURRENT_A"]),
                        "pcode": r["PROVINCE_CODE"], "bcode": r["BUREAU_CODE"], "src": r["DATA_SOURCE"]} for r in chunk])

        # 关系（分批）
        for start in range(0, len(inventory), BATCH):
            chunk = inventory[start:start + BATCH]
            s.run("""
                UNWIND $rows AS r
                MATCH (w:仓库 {标识: r.wh}), (s:库存记录 {标识: r.id}),
                      (m:物资 {标识: r.mat}), (sup:供应商 {标识: r.sup})
                CREATE (w)-[:包含库存]->(s)
                CREATE (s)-[:对应物资]->(m)
                CREATE (s)-[:由供应商提供]->(sup)
            """, rows=[{"wh": r["ACTUAL_WAREHOUSE_ID"], "id": r["WAREHOUSE_DISTR_ID"],
                        "mat": r["MATERIAL_ID"], "sup": r["VENDOR_ID"]} for r in chunk])
            # 归属项目（仅项目物资）
            proj_rows = [{"id": r["WAREHOUSE_DISTR_ID"], "proj": r["PROJECT_ID"]}
                         for r in chunk if r["PROJECT_ID"]]
            if proj_rows:
                s.run("""
                    UNWIND $rows AS r
                    MATCH (s:库存记录 {标识: r.id}), (p:项目 {标识: r.proj})
                    CREATE (s)-[:归属项目]->(p)
                """, rows=proj_rows)

        # 位于区域 / 校准自
        wh_region = [{"wh": r["WAREHOUSE_ID"], "prov": r["WAREHOUSE_NAME"].split("国网")[1].split("省电力")[0]}
                     for r in warehouses]
        s.run("""
            UNWIND $rows AS r
            MATCH (w:仓库 {标识: r.wh}), (g:区域 {名称: r.name})
            CREATE (w)-[:位于区域]->(g)
        """, rows=[{"wh": x["wh"], "name": region_name(x["prov"])} for x in wh_region])
        s.run("""
            MATCH (m:物资), (src:资料来源 {标识: m.来源标识})
            CREATE (m)-[:校准自]->(src)
        """)

        # 校验
        nodes = {r["l"]: r["c"] for r in s.run("MATCH (n) RETURN labels(n)[0] AS l, count(*) AS c")}
        rels = {r["t"]: r["c"] for r in s.run("MATCH ()-[r]->() RETURN type(r) AS t, count(*) AS c")}
        print("节点:", nodes)
        print("关系:", rels)
        dangling = s.run("""
            MATCH (s:库存记录) WHERE NOT (s)--() RETURN count(s) AS c
        """).single()["c"]
        print("孤立库存节点:", dangling)
        bad_age = s.run("""
            MATCH (s:库存记录)
            WITH s, duration.between(date(s.入库日期), date('2026-08-28')).years AS y,
                 s.库龄 AS a
            WHERE (a='1年以内' AND y>=1) OR (a='1-3年' AND (y<1 OR y>=3))
               OR (a='3-5年' AND (y<3 OR y>=5)) OR (a='5-10年' AND (y<5 OR y>=10))
               OR (a='10年以上' AND y<10)
            RETURN count(s) AS c
        """).single()["c"]
        print("库龄矛盾:", bad_age)
        scrap_young = s.run("""
            MATCH (s:库存记录 {用途:'报废物资'}) WHERE s.库龄 IN ['1年以内','1-3年','3-5年']
            RETURN count(s) AS c
        """).single()["c"]
        print("报废物资库龄过短:", scrap_young)

    driver.close()

if __name__ == "__main__":
    main()

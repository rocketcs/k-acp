#!/usr/bin/env python3
"""构建 v0.2 电力物资模拟数据（修复 v0.1 逻辑缺陷）。

v0.1 问题 → v0.2 修复：
1. 库龄 与 入库日期 独立随机、含"未知"档 → 库龄由入库日期强推导，五档无未知
2. 报废/闲置物资库龄可能极短 → 用途约束库龄档位（报废只允许 5-10年/10年以上）
3. 全部物资单价同一数量级 → 按类别设定真实数量级，金额跨 5 个数量级
4. 同一供应商同一物资出现几十种价格 → 供应商×物资价格锚定，组内差异 <15%
5. 仓库用途金额字段与明细无关 → 由明细严格聚合
6. 税额随意 → 统一 13% 增值税

输出：data/dm8-mock-v0.2/*.csv（UTF-8），供 DM8 JDBC 导入与 Neo4j 重建共用。
"""

from __future__ import annotations

import csv
import random
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

R = random.Random(20260828)
TODAY = date(2026, 8, 28)
OUT = Path(__file__).resolve().parents[2] / "data" / "dm8-mock-v0.2"
OUT.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------- 设备类别
# (类别, [产品名×4], [型号族×4], 单位, 单价区间, 数量区间, 电压档, 是否有容量, 是否有电流)
CATALOG = [
    ("变压器", ["电力变压器", "油浸式配电变压器", "干式变压器", "有载调压变压器"],
     ["SZ11-{c}/110", "S13-M-{c}/10", "SCB10-{c}/10", "SZ20-{c}/35"],
     "台", (5e5, 4e6), (1, 60), [110, 35, 10], True, False),
    ("开关设备", ["中置式开关柜", "固定式开关柜", "环网开关柜", "气体绝缘开关柜"],
     ["KYN28A-12", "XGN2-12", "HXGN15-12", "C-GIS-40.5"],
     "面", (3e4, 6e5), (2, 80), [10, 35], False, True),
    ("环网设备", ["户外环网柜", "美式环网箱", "欧式缆分支箱", "公用环网单元"],
     ["XGW-12", "XGW-24", "DFW-12", "HGW-12"],
     "台", (5e4, 3e5), (2, 50), [10, 24], False, True),
    ("断路器", ["户外真空断路器", "户内真空断路器", "六氟化硫断路器", "柱上开关断路器"],
     ["ZW32-12", "VS1-12", "LW36-126", "ZW7-40.5"],
     "台", (8e3, 4e5), (3, 100), [126, 40.5, 12], False, True),
    ("互感器", ["电流互感器", "电压互感器", "组合互感器", "电子式互感器"],
     ["LZZW-10", "JDZX9-35", "JLS-10", "EVT-110"],
     "只", (1500, 5e4), (10, 300), [110, 35, 10], False, True),
    ("保护装置", ["线路保护装置", "变压器保护装置", "母线保护装置", "备用电源自投装置"],
     ["PCS-941", "RCS-978", "BP-2B", "PSP-642"],
     "套", (2e4, 2.5e5), (2, 40), [110, 35, 10], False, False),
    ("自动化设备", ["配电终端DTU", "馈线终端FTU", "配变终端TTU", "站控终端"],
     ["DTU-K1", "FTU-F500", "TTU-T220", "SCADA-S1"],
     "套", (1.5e4, 1.8e5), (3, 90), [10], False, False),
    ("避雷器", ["金属氧化物避雷器", "线路型避雷器", "电站型避雷器", "低压避雷器"],
     ["HY5WZ-17", "YH5WX-51", "Y10W-108", "HY1.5W-0.5"],
     "只", (300, 2.5e4), (20, 500), [110, 51, 17], False, False),
    ("电缆", ["交联聚乙烯电力电缆", "阻燃电力电缆", "铝合金电力电缆", "控制电缆"],
     ["YJV22-8.7/15-3x300", "ZC-YJV-0.6/1-4x185", "YJLHV-26/35-3x240", "KVVP-450/750-7x1.5"],
     "米", (40, 900), (100, 8000), [35, 15, 1], False, False),
    ("绝缘子", ["复合悬式绝缘子", "瓷质悬式绝缘子", "针式绝缘子", "支柱绝缘子"],
     ["FXBW4-10/70", "XP-70", "P-20T", "ZSW-126"],
     "只", (25, 600), (50, 2000), [126, 35, 10], False, False),
    ("电容器", ["并联电容器", "集合式电容器", "滤波电容器", "高压电容器组"],
     ["BSMJ0.45-30", "TBB10-4800", "AFM2.5-100", "TBB35-7200"],
     "台", (8e3, 1.2e5), (2, 60), [35, 10], False, False),
    ("储能设备", ["液冷储能系统", "风冷储能系统", "工商业储能柜", "集装箱储能系统"],
     ["ESS-1MWh-LC", "ESS-2MWh-AC", "ESS-215kWh-CI", "ESS-3.44MWh-CT"],
     "套", (6e5, 3e6), (1, 40), [35, 10], False, False),
    ("光伏设备", ["组串式光伏逆变器", "集中式光伏逆变器", "光伏汇流箱", "光伏并网柜"],
     ["SG110CX", "SG3125HV", "PVS-16", "GGD-PV"],
     "台", (3e4, 3e5), (3, 80), [35, 10], False, True),
    ("风电设备", ["风电变流器", "风电箱式变压器", "风电主控系统", "变桨驱动器"],
     ["WECC-2.5MW", "WBX-3150/35", "WMS-5000", "VMD-750"],
     "套", (6e5, 2.6e6), (1, 40), [110, 35], True, False),
    ("充电设备", ["直流充电桩", "液冷超充桩", "交流充电桩", "充电模块"],
     ["DC-120kW", "HPC-600kW", "AC-7kW", "CM-30kW"],
     "台", (1.5e4, 1.2e5), (2, 60), [10], False, True),
]
CAP_STEPS = [200, 315, 630, 2000, 10000, 20000, 31500, 63000, 90000, 180000]
CUR_STEPS = [5, 30, 100, 400, 630, 1250, 2000, 3150, 4000]

# ---------------------------------------------------------------- 主档数据
PROVINCES = [
    ("北京", "B1101"), ("天津", "B1201"), ("河北", "B1301"), ("山西", "B1401"), ("内蒙古", "B1501"),
    ("辽宁", "B2101"), ("吉林", "B2201"), ("黑龙江", "B2301"), ("上海", "B3101"), ("江苏", "B3201"),
    ("浙江", "B3301"), ("安徽", "B3401"), ("福建", "B3501"), ("江西", "B3601"), ("山东", "B3701"),
    ("河南", "B4101"), ("湖北", "B4201"), ("湖南", "B4301"), ("广东", "B4401"),
]
CITIES = {
    "北京": ["北京"], "天津": ["天津"], "河北": ["石家庄", "保定", "唐山"], "山西": ["太原", "大同", "运城"],
    "内蒙古": ["呼和浩特", "包头", "鄂尔多斯"], "辽宁": ["沈阳", "大连", "锦州"], "吉林": ["长春", "吉林"],
    "黑龙江": ["哈尔滨", "齐齐哈尔"], "上海": ["上海", "浦东"], "江苏": ["南京", "苏州", "徐州"],
    "浙江": ["杭州", "宁波", "温州"], "安徽": ["合肥", "芜湖", "安庆"], "福建": ["福州", "厦门", "泉州"],
    "江西": ["南昌", "赣州"], "山东": ["济南", "青岛", "烟台"], "河南": ["郑州", "洛阳", "南阳"],
    "湖北": ["武汉", "襄阳", "宜昌"], "湖南": ["长沙", "株洲", "衡阳"], "广东": ["广州", "深圳", "东莞", "惠州"],
}
LEVELS = [(1, "区域仓"), (2, "周转仓"), (3, "急救包"), (4, "施工现场")]
LEVEL_DIST = [1] * 19 + [2] * 19 + [3] * 6 + [4] * 4  # 48 仓库

SUPPLIERS = [
    "国电南瑞", "南瑞继保电气股份有限公司", "特变电工", "特变电工科技股份有限公司", "正泰电气",
    "正泰电气电气股份有限公司", "施耐德电气", "伊顿电气", "伊顿电气股份有限公司", "西门子能源",
    "ABB中国", "阳光电源", "远景能源", "金风科技", "金风科技电气股份有限公司",
    "许继电气", "许继电气电气股份有限公司", "平高集团", "平高集团电气股份有限公司", "山东泰开",
    "江苏东源", "江苏东源电气股份有限公司", "宏力达", "宏力达电气股份有限公司", "特锐德",
    "特锐德电气股份有限公司", "炬华科技", "炬华科技电气股份有限公司", "南方电网供应链科技",
    "南方电网供应链", "中天科技", "亨通光电", "远东电缆", "宝胜科技", "万马股份",
    "良信电器", "白云电器", "森源电气", "华明装备", "思源电气", "海兴电力", "林洋能源",
    "科陆电子", "盛弘股份", "英威腾", "汇川技术", "大族数控", "宁德时代", "比亚迪储能",
    "亿纬锂能", "国轩高科", "派能科技", "南都电源", "双登股份", "雄韬股份", "易事特",
    "科华数据", "上能电气", "禾望电气", "德业股份",
]
PROJECT_KINDS = [
    ("{city}{kv}千伏输变电工程", [110, 220, 500]),
    ("{city}新能源送出线路工程", [110, 220]),
    ("{city}城网改造升级工程", [10, 110]),
    ("{city}储能示范项目", [35, 110]),
    ("{city}充电基础设施配套工程", [10]),
    ("{city}配电网自动化改造工程", [10]),
]

def rlognorm(rng: random.Random, sigma: float) -> float:
    return pow(2.718281828, rng.gauss(0, sigma))

def round2(x: float) -> float:
    return round(x + 1e-9, 2)

def gen_provenance() -> list[dict]:
    sources = [
        ("国家电网公司物资采购目录（2024 版）", "https://example.com/sgcc/catalog-2024"),
        ("电力设备型号公开产品手册汇编", "https://example.com/power-equipment/handbook"),
        ("中国电力企业联合会设备统计公报", "https://example.com/cec/statistics"),
        ("各省电力公司物资仓储公开信息", "https://example.com/province/warehouse"),
        ("输变电设备技术规范汇编", "https://example.com/transmission/spec"),
        ("配电网设备选型技术导则", "https://example.com/distribution/guide"),
        ("新能源并网设备技术白皮书", "https://example.com/renewable/whitepaper"),
        ("电力储能系统工程技术规范", "https://example.com/storage/standard"),
        ("电动汽车充电设施发展报告", "https://example.com/charging/report"),
    ]
    rows = []
    for i, (name, url) in enumerate(sources, 1):
        rows.append({
            "SOURCE_ID": f"urn:kacp:mock:source:SRC{i:03d}",
            "SOURCE_NAME": name,
            "SOURCE_URL": url,
            "SOURCE_NOTE": "公开资料校准来源，仅用于演示环境",
            "RETRIEVED_ON": (TODAY - timedelta(days=R.randint(30, 400))).isoformat(),
            "DATA_STATUS": "公开资料校准",
        })
    return rows

def gen_catalog() -> list[dict]:
    rows = []
    mid = 0
    for cat, names, families, unit, (plo, phi), (qlo, qhi), volts, has_cap, has_cur in CATALOG:
        for k in range(16):
            mid += 1
            name = names[k % 4]
            fam = families[k % 4]
            volt = volts[k % len(volts)]
            cap = R.choice(CAP_STEPS) if has_cap else 0.0
            cur = R.choice(CUR_STEPS) if has_cur else 0.0
            model = fam.format(c=cap) if "{c}" in fam else fam
            base = pow(10, R.uniform(log10(plo), log10(phi)))
            rows.append({
                "MATERIAL_ID": f"urn:kacp:mock:material:MAT{mid:04d}",
                "MATERIAL_NAME": name,
                "MATERIAL_MODEL": model,
                "MATERIAL_CATEGORY": cat,
                "MANUFACTURER": R.choice(SUPPLIERS[:20]),
                "RATED_VOLTAGE_KV": volt,
                "RATED_CAPACITY_KVA": cap,
                "RATED_CURRENT_A": cur,
                "UNIT_NAME": unit,
                "BASE_PRICE": round2(base),
                "SOURCE_ID": f"urn:kacp:mock:source:SRC{R.randint(1, 9):03d}",
                "SOURCE_NOTE": "型号族参考公开产品手册",
                "IS_MOCK": 1,
            })
    return rows

def log10(x: float) -> float:
    import math
    return math.log10(x)

def gen_warehouses() -> list[dict]:
    # 每省 2 个，北京/天津/上海/广东各 3 个，其他 8 省各减 1 → 共 48
    # 每省 2 个，再加 10 个补充 → 共 48
    plan = {p: 2 for p, _ in PROVINCES}
    for extra in ("北京", "天津", "上海", "广东", "江苏", "浙江", "山东", "河南", "湖北", "河北"):
        plan[extra] += 1
    rows = []
    wid = 0
    for pi, (prov, pcode) in enumerate(PROVINCES):
        for _ in range(plan[prov]):
            wid += 1
            city = R.choice(CITIES[prov])
            level, level_name = LEVELS[R.choice([0, 0, 1, 1, 2, 3])]
            rows.append({
                "WAREHOUSE_ID": f"urn:kacp:mock:warehouse:WH{wid:07d}",
                "WAREHOUSE_NAME": f"国网{prov}省电力有限公司{city}供电公司物资仓库",
                "WAREHOUSE_CODE": f"WH-{pcode}-{wid:04d}",
                "WAREHOUSE_LEVEL": level,
                "WAREHOUSE_LEVEL_NAME": level_name,
                "WAREHOUSE_ADDR": f"{prov}省{city}市电力物资产业园{R.randint(1, 30)}号",
                "GEOGRAPHICAL_LNG": round(R.uniform(98, 122), 4),
                "GEOGRAPHICAL_LAT": round(R.uniform(23, 48), 4),
                # 用途金额由明细聚合后回填
                "IDLE_AMT": 0, "SCRAP_AMT": 0, "RESERVE_AMT": 0, "PROJECT_AMT": 0, "TOTAL_AMT": 0,
                "PROVINCE_CODE": pcode,
                "BUREAU_CODE": pcode,
                "DATA_SOURCE": "公开设备类别校准",
                "IS_MOCK": 1,
            })
    return rows

def gen_projects() -> list[dict]:
    rows = []
    seen = set()
    while len(rows) < 180:
        template, kvs = R.choice(PROJECT_KINDS)
        prov, _ = R.choice(PROVINCES)
        city = R.choice(CITIES[prov])
        kv = R.choice(kvs)
        full = template.format(city=city, kv=kv) + f"（第{R.randint(1, 4)}标段）"
        if full in seen:
            continue
        seen.add(full)
        rows.append({
            "PROJECT_ID": f"urn:kacp:mock:project:PRJ{len(rows)+1:04d}",
            "PROJECT_NAME": full,
        })
    return rows

# ---------------------------------------------------------------- 库龄与日期
AGE_BUCKETS = [
    ("1年以内", 0, 1), ("1-3年", 1, 3), ("3-5年", 3, 5), ("5-10年", 5, 10), ("10年以上", 10, 16),
]
USAGES = ["常规储备物资", "储备物资", "项目物资", "战略储备物资", "闲置物资", "报废物资"]
USAGE_DIST = [30, 15, 20, 10, 15, 10]
# 用途 → 允许的库龄档位及权重（业务约束）
USAGE_AGE = {
    "报废物资": [("5-10年", 35), ("10年以上", 65)],
    "闲置物资": [("3-5年", 30), ("5-10年", 45), ("10年以上", 25)],
    "常规储备物资": [("1年以内", 40), ("1-3年", 40), ("3-5年", 20)],
    "储备物资": [("1年以内", 35), ("1-3年", 40), ("3-5年", 25)],
    "战略储备物资": [("1年以内", 30), ("1-3年", 35), ("3-5年", 35)],
    "项目物资": [("1年以内", 55), ("1-3年", 45)],
}

def pick_age(usage: str) -> tuple[str, date]:
    buckets = USAGE_AGE[usage]
    names, weights = zip(*buckets)
    name = R.choices(names, weights=weights, k=1)[0]
    lo, hi = next((lo, hi) for n, lo, hi in AGE_BUCKETS if n == name)
    days = R.randint(lo * 365 + 1, hi * 365)
    return name, TODAY - timedelta(days=days)

def gen_inventory(warehouses, catalog, projects) -> list[dict]:
    rows = []
    wh_by_prov = defaultdict(list)
    for w in warehouses:
        wh_by_prov[w["PROVINCE_CODE"]].append(w)
    # 供应商 × 物资 价格锚点（同组合价格差异 <15%）
    anchors = {}
    total = 0
    for i in range(20000):
        mat = R.choice(catalog)
        prov_code = R.choice(list(wh_by_prov.keys()))
        wh = R.choice(wh_by_prov[prov_code])
        vendor = R.choice(SUPPLIERS)
        key = (vendor, mat["MATERIAL_ID"])
        if key not in anchors:
            anchors[key] = mat["BASE_PRICE"] * rlognorm(R, 0.10)
        price = round2(anchors[key] * rlognorm(R, 0.02))
        # 数量上界：按单位 + 单笔金额封顶约 3 亿（贵重物资数量自然反相关）
        if mat["UNIT_NAME"] == "米":
            qlo_b, qhi_b = 100, 8000
        elif mat["UNIT_NAME"] == "只":
            qlo_b, qhi_b = 50, 2000
        else:
            qlo_b, qhi_b = 1, 120
        qty_hi = min(qhi_b, max(qlo_b, int(3e8 / max(price, 1))))
        qty = R.randint(qlo_b, max(qlo_b + 1, qty_hi))
        usage = R.choices(USAGES, weights=USAGE_DIST, k=1)[0]
        age_name, receipt = pick_age(usage)
        project = R.choice(projects) if usage == "项目物资" else None
        amount = round2(price * qty)
        rows.append({
            "WAREHOUSE_DISTR_ID": f"urn:kacp:mock:stock:INV{i+1:08d}",
            "ACTUAL_WAREHOUSE_ID": wh["WAREHOUSE_ID"],
            "MATERIAL_ID": mat["MATERIAL_ID"],
            "MATERIAL_NAME": mat["MATERIAL_NAME"],
            "MATERIAL_MODEL": mat["MATERIAL_MODEL"],
            "MATERIAL_CATEGORY": mat["MATERIAL_CATEGORY"],
            "VENDOR_ID": f"urn:kacp:mock:supplier:SUP{SUPPLIERS.index(vendor)+1:03d}",
            "VENDOR_NAME": vendor,
            "PROJECT_ID": project["PROJECT_ID"] if project else "",
            "PROJECT_NAME": project["PROJECT_NAME"] if project else "",
            "ACTUAL_QTY": qty,
            "UNIT_PRICE": price,
            "ACTUAL_TOTAL_PRICE": amount,
            "ACTUAL_TOTAL_TAX": round2(amount * 0.13),
            "UNIT_NAME": mat["UNIT_NAME"],
            "USAGE_NAME": usage,
            "RECEIPT_DATE": receipt.isoformat() + " 00:00:00",
            "INVENTORY_AGE_NAME": age_name,
            "PROVINCE_CODE": wh["PROVINCE_CODE"],
            "BUREAU_CODE": wh["BUREAU_CODE"],
            "VOLTAGE_LEVEL_KV": mat["RATED_VOLTAGE_KV"],
            "RATED_CAPACITY_KVA": mat["RATED_CAPACITY_KVA"],
            "RATED_CURRENT_A": mat["RATED_CURRENT_A"],
            "DATA_SOURCE": "公开设备类别校准",
            "IS_MOCK": 1,
        })
    # 回填仓库用途金额
    agg = defaultdict(lambda: defaultdict(float))
    for r in rows:
        agg[r["ACTUAL_WAREHOUSE_ID"]][r["USAGE_NAME"]] += r["ACTUAL_TOTAL_PRICE"]
    for w in warehouses:
        a = agg.get(w["WAREHOUSE_ID"], {})
        idle = a.get("闲置物资", 0.0)
        scrap = a.get("报废物资", 0.0)
        reserve = a.get("储备物资", 0.0) + a.get("战略储备物资", 0.0) + a.get("常规储备物资", 0.0)
        project = a.get("项目物资", 0.0)
        w["IDLE_AMT"] = round2(idle)
        w["SCRAP_AMT"] = round2(scrap)
        w["RESERVE_AMT"] = round2(reserve)
        w["PROJECT_AMT"] = round2(project)
        w["TOTAL_AMT"] = round2(idle + scrap + reserve + project)
    return rows

def gen_summary(inventory, warehouses) -> list[dict]:
    # 按 省份 × 仓库等级 × 用途 聚合
    level_of = {w["WAREHOUSE_ID"]: w["WAREHOUSE_LEVEL"] for w in warehouses}
    agg = defaultdict(float)
    for r in inventory:
        key = (r["PROVINCE_CODE"], level_of[r["ACTUAL_WAREHOUSE_ID"]], r["USAGE_NAME"])
        agg[key] += r["ACTUAL_TOTAL_PRICE"]
    rows = []
    usage_id = {u: i + 1 for i, u in enumerate(USAGES)}
    for (pcode, level, usage), amt in sorted(agg.items()):
        rows.append({
            "SEQ_ID": f"SM{len(rows)+1:05d}",
            "TIME_ID": TODAY.strftime("%Y%m"),
            "USAGE_TYPE_ID": usage_id[usage],
            "WAREHOUSE_LEVEL_ID": level,
            "WAREHOUSE_MONEY": round2(amt),
            "WAREHOUSE_PRE_MONEY": round2(amt * 1.03),
            "PROVINCE_CODE": pcode,
            "BUREAU_CODE": pcode,
            "DATA_SOURCE": "公开设备类别校准",
            "IS_MOCK": 1,
        })
    return rows

# ---------------------------------------------------------------- 写 CSV 与校验
def write_csv(name: str, rows: list[dict]) -> None:
    with open(OUT / name, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

def main() -> None:
    print("生成主档数据 ...")
    prov_rows = gen_provenance()
    catalog = gen_catalog()
    warehouses = gen_warehouses()
    projects = gen_projects()
    print("  生成 20000 条库存明细（含库龄/日期/价格逻辑约束）...")
    inventory = gen_inventory(warehouses, catalog, projects)
    summary = gen_summary(inventory, warehouses)

    write_csv("provenance.csv", prov_rows)
    write_csv("catalog.csv", catalog)
    write_csv("warehouse.csv", warehouses)
    write_csv("project.csv", projects)
    write_csv("inventory.csv", inventory)
    write_csv("summary.csv", summary)

    # ---------------- 一致性校验 ----------------
    print("\n===== v0.2 一致性校验 =====")
    # 1 库龄 ↔ 入库日期
    bad_age = 0
    for r in inventory:
        d = date.fromisoformat(r["RECEIPT_DATE"][:10])
        y = (TODAY - d).days / 365.0
        lo, hi = next((lo, hi) for n, lo, hi in AGE_BUCKETS if n == r["INVENTORY_AGE_NAME"])
        if not (lo <= y < hi + 0.01):
            bad_age += 1
    print(f"1) 库龄与入库日期矛盾: {bad_age} / {len(inventory)}")
    # 2 未知/空字段
    unknown = sum(1 for r in inventory if "未知" in r["INVENTORY_AGE_NAME"] or not r["INVENTORY_AGE_NAME"])
    empty_proj_ok = sum(1 for r in inventory if r["USAGE_NAME"] != "项目物资" and r["PROJECT_NAME"])
    print(f"2) 库龄含'未知': {unknown} | 非项目物资却挂项目: {empty_proj_ok}")
    # 3 金额一致性
    bad_amt = sum(1 for r in inventory if abs(r["ACTUAL_QTY"] * r["UNIT_PRICE"] - r["ACTUAL_TOTAL_PRICE"]) > 0.01)
    print(f"3) 数量×单价≠金额: {bad_amt} / {len(inventory)}")
    # 4 金额数量级分布
    amts = sorted(r["ACTUAL_TOTAL_PRICE"] for r in inventory)
    import math
    deciles = [amts[int(len(amts) * p)] for p in (0.05, 0.25, 0.5, 0.75, 0.95)]
    print(f"4) 金额分位数(5/25/50/75/95%): " + " / ".join(f"{a:,.0f}" for a in deciles))
    print(f"   最小 {amts[0]:,.0f} | 最大 {amts[-1]:,.0f} | 跨度 {amts[-1]/max(amts[0],1):,.0f} 倍")
    # 5 同供应商同物资价格差
    groups = defaultdict(list)
    for r in inventory:
        groups[(r["VENDOR_NAME"], r["MATERIAL_ID"])].append(r["UNIT_PRICE"])
    worst = 0
    for prices in groups.values():
        if len(prices) > 1:
            worst = max(worst, (max(prices) - min(prices)) / min(prices))
    print(f"5) 同供应商同物资最大价格差: {worst*100:.1f}% (组合数 {len(groups)})")
    # 6 仓库汇总回填一致
    agg = defaultdict(float)
    for r in inventory:
        agg[r["ACTUAL_WAREHOUSE_ID"]] += r["ACTUAL_TOTAL_PRICE"]
    bad_wh = sum(1 for w in warehouses if abs(agg.get(w["WAREHOUSE_ID"], 0) - w["TOTAL_AMT"]) > 0.05)
    print(f"6) 仓库 TOTAL_AMT 与明细汇总矛盾: {bad_wh} / {len(warehouses)}")
    # 7 日期范围
    ds = sorted(r["RECEIPT_DATE"][:10] for r in inventory)
    print(f"7) 入库日期范围: {ds[0]} ~ {ds[-1]}（今日 {TODAY}）")
    print(f"\nCSV 已输出到 {OUT}")

if __name__ == "__main__":
    main()

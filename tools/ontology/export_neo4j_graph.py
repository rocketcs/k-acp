#!/usr/bin/env python3
"""Export a Chinese Neo4j subgraph into the stable HTML renderer contract."""

from __future__ import annotations

import argparse
import json
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any

from neo4j import GraphDatabase


QUERY = """
MATCH (仓库:`仓库` {`标识`: $warehouse_id})-[:`包含库存`]->(库存:`库存记录`)-[:`对应物资`]->(物资:`物资`)
OPTIONAL MATCH (库存)-[:`由供应商提供`]->(供应商:`供应商`)
OPTIONAL MATCH (库存)-[:`归属项目`]->(项目:`项目`)
OPTIONAL MATCH (仓库)-[:`位于区域`]->(区域:`区域`)
WITH 仓库,库存,物资,供应商,项目,区域
ORDER BY coalesce(库存.`金额`, 0) DESC
LIMIT $limit
RETURN 仓库,库存,物资,供应商,项目,区域
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导出中文 Neo4j 图谱 JSON")
    parser.add_argument("--warehouse-id", required=True, help="仓库标识，例如 urn:kacp:mock:warehouse:WH0000004")
    parser.add_argument("--limit", type=int, default=80, help="最多导出的库存记录数")
    parser.add_argument("--uri", default="bolt://127.0.0.1:7689")
    parser.add_argument("--user", default="neo4j")
    parser.add_argument("--password", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def node_id(node: Any) -> str | None:
    if node is None:
        return None
    value = node.get("标识")
    return str(value) if value is not None else None


TRACE_KEYS = {"标识", "来源标识"}


def short_code(identifier: str | None) -> str:
    """urn:kacp:mock:stock:INV000003795 -> INV000003795"""
    if not identifier:
        return ""
    return identifier.rsplit(":", 1)[-1]


def fmt_amount(value: Any) -> str:
    number = float(value or 0)
    if abs(number) >= 1e8:
        return f"{number / 1e8:.2f}亿"
    if abs(number) >= 1e4:
        return f"{number / 1e4:.2f}万"
    return f"{number:,.2f}"


def display_label(node: Any, kind: str, material: Any = None) -> str:
    """展示用中文标签，绝不输出 urn: 全串。"""
    if node is None:
        return ""
    identifier = node_id(node)
    if kind == "库存记录":
        material_name = str(material.get("名称") or "") if material is not None else ""
        if material_name:
            amount = fmt_amount(node.get("金额"))
            return f"{material_name}·{amount}" if amount != "0.00" else material_name
        return f"库存·{short_code(identifier)}"
    return str(node.get("名称") or short_code(identifier))


def add_node(
    nodes: dict[str, dict[str, Any]],
    node: Any,
    kind: str,
    label: str | None = None,
) -> None:
    identifier = node_id(node)
    if not identifier:
        return
    props = {str(key): value for key, value in dict(node).items() if value is not None}
    trace = {key: props.pop(key) for key in TRACE_KEYS if key in props}
    entry: dict[str, Any] = {
        "id": identifier,
        "label": label if label is not None else display_label(node, kind),
        "kind": kind,
        "properties": props,
    }
    if trace:
        entry["trace"] = trace
    nodes.setdefault(identifier, entry)


def add_edge(edges: set[tuple[str, str, str]], source: Any, target: Any, label: str) -> None:
    source_id, target_id = node_id(source), node_id(target)
    if source_id and target_id:
        edges.add((source_id, target_id, label))


def money(value: Any) -> Decimal:
    return Decimal(str(value or 0)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def export_graph(args: argparse.Namespace) -> dict[str, Any]:
    nodes: dict[str, dict[str, Any]] = {}
    edges: set[tuple[str, str, str]] = set()
    inventory_ids: set[str] = set()
    amount = Decimal("0.00")

    driver = GraphDatabase.driver(args.uri, auth=(args.user, args.password))
    try:
        with driver.session(database="neo4j") as session:
            result = session.run(QUERY, warehouse_id=args.warehouse_id, limit=max(1, args.limit))
            for record in result:
                warehouse = record["仓库"]
                inventory = record["库存"]
                material = record["物资"]
                supplier = record["供应商"]
                project = record["项目"]
                region = record["区域"]
                add_node(nodes, warehouse, "仓库")
                add_node(
                    nodes,
                    inventory,
                    "库存记录",
                    label=display_label(inventory, "库存记录", material),
                )
                add_node(nodes, material, "物资")
                add_node(nodes, supplier, "供应商")
                add_node(nodes, project, "项目")
                add_node(nodes, region, "区域")
                add_edge(edges, warehouse, inventory, "包含库存")
                add_edge(edges, inventory, material, "对应物资")
                add_edge(edges, inventory, supplier, "由供应商提供")
                add_edge(edges, inventory, project, "归属项目")
                add_edge(edges, warehouse, region, "位于区域")
                inventory_identifier = node_id(inventory)
                if inventory_identifier and inventory_identifier not in inventory_ids:
                    inventory_ids.add(inventory_identifier)
                    amount += money(inventory.get("金额"))
    finally:
        driver.close()

    ordered_edges = [
        {"source": source, "target": target, "label": label}
        for source, target, label in sorted(edges)
    ]
    return {
        "title": nodes.get(args.warehouse_id, {}).get("label", "电力物资知识图谱"),
        "subtitle": f"中文关系子图 · 前 {len(inventory_ids)} 条库存记录",
        "source": "图谱查询结果 · 本地模拟数据",
        "stats": {
            "nodeCount": len(nodes),
            "edgeCount": len(ordered_edges),
            "amount": float(amount),
        },
        "nodes": [nodes[key] for key in sorted(nodes)],
        "edges": ordered_edges,
    }


def main() -> None:
    args = parse_args()
    data = export_graph(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "nodes": len(data["nodes"]), "edges": len(data["edges"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()

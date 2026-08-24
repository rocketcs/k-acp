"""Tests for the record-scoped Neo4j attribute import contract."""

from __future__ import annotations

import importlib.util
from collections import defaultdict
from pathlib import Path


def _module():
    path = Path(__file__).parents[1] / "tools" / "henan-neo4j-import" / "build_dataset.py"
    spec = importlib.util.spec_from_file_location("henan_neo4j_build_dataset", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_drug_record_creates_real_record_scoped_attribute_nodes() -> None:
    module = _module()
    dataset = module.Dataset.__new__(module.Dataset)
    dataset.rows = defaultdict(list)

    dataset.attributes({
        "药品编码": "XA01ABJ159S006030104494",
        "通用名称": "聚维酮碘含漱液",
        "规格": "80ml",
        "批准文号": "国药准字H00001",
        "医保支付类别": "甲类",
        "开始时间": "2026-01-01",
    }, "record-1", "drug")

    attributes = {row["field"]: row for row in dataset.rows["catalog_attribute_values"]}
    assert attributes["specification"] == {
        "attribute_id": module.ident("record-1", "specification", "80ml"),
        "record_id": "record-1",
        "domain": "DRUG",
        "field": "specification",
        "field_label": "规格",
        "value": "80ml",
    }
    assert attributes["approval_number"]["value"] == "国药准字H00001"
    assert attributes["payment_category"]["value"] == "甲类"
    assert attributes["valid_from"]["value"] == "2026-01-01"


def test_empty_fields_are_not_emitted_as_graph_nodes() -> None:
    module = _module()
    dataset = module.Dataset.__new__(module.Dataset)
    dataset.rows = defaultdict(list)

    dataset.attributes({"规格": "", "医保支付类别": None, "项目名称": "血常规"}, "record-2", "service")

    assert dataset.rows["catalog_attribute_values"] == [{
        "attribute_id": module.ident("record-2", "catalog_name", "血常规"),
        "record_id": "record-2",
        "domain": "SERVICE",
        "field": "catalog_name",
        "field_label": "项目名称",
        "value": "血常规",
    }]

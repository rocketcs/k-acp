# DM8 真实感电力设备模拟数据重建记录

重建时间：2026-08-28

## 目标

在保留可查询性和本体字段兼容性的前提下，将原先过于规则化的演示数据替换为“公开设备类别与产品族校准的模拟数据”。这些记录不是生产事实，均使用 `IS_MOCK=1` 标记。

## 数据规模

| 数据集 | 行数 |
|---|---:|
| 公开资料来源追溯 | 9 |
| 设备主数据目录 | 240 |
| 仓库主数据 | 48 |
| 库存事实数据 | 20,000 |
| 库存金额汇总 | 228 |

## 设备类别

变压器、开关设备、环网设备、断路器、互感器、保护装置、自动化设备、避雷器、电缆、绝缘子、电容器、储能设备、光伏设备、风电设备、充电设备。

## 真实性校准

- 型号族参考配电变压器、开关设备、断路器、互感器、避雷器、继电保护、自动重合器等公开资料。
- 引入电压等级、额定容量、额定电流、计量单位、收货日期、库龄、用途、税额等业务字段。
- 供应商名称采用公开电力装备企业或其模拟供货中心名称。
- 仓库、项目、库存数量、价格和金额为随机种子固定的模拟值，并通过数量×单价一致性校验。

## 连接信息

- 容器：`dm8-mock`
- JDBC：`jdbc:dm://127.0.0.1:5236`
- 模式：`MOCK_APP`
- 应用用户：`MOCK_APP`
- 应用密码：`MockApp2026`
- 管理员密码：`SYSDBA2026`

## 回滚

重建前旧数据已保存在：`data/dm8-mock-backup-20260828/`。

## 图谱渲染

固定渲染页面：`docs/ontology/dm8-graph-demo-cn.html`。

每次查询只需导出新的 JSON，不需要修改 HTML：

```bash
python3 tools/ontology/export_neo4j_graph.py \
  --warehouse-id urn:kacp:mock:warehouse:WH0000004 \
  --limit 24 \
  --uri bolt://127.0.0.1:7689 \
  --user neo4j \
  --password 'MockGraph2026!' \
  --output docs/ontology/sample-graph-data-cn.json
```

然后打开：

`docs/ontology/dm8-graph-demo-cn.html?data=sample-graph-data-cn.json`

页面也支持调用方直接注入 `window.__GRAPH_DATA__`，或调用 `window.renderGraph(data)`。

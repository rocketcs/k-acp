# Pi Agent 交接：DM8 电力物资中文知识图谱

交接时间：2026-08-28（Asia/Shanghai）
当前分支：`dev`

## 一句话结论

DM8 本地模拟库已重建为带公开设备类别校准的电力物资数据；独立 Neo4j 模拟实例已导入中文节点和中文关系；固定 HTML 已改造成“电力调度驾驶舱”渲染器，后续查询只需替换 JSON，不修改 HTML。

## 已完成

### DM8 数据库

- 容器：`dm8-mock`
- 地址：`127.0.0.1`
- 端口：`5236`
- 服务名：`DMSERVER`
- 模式：`MOCK_APP`
- 用户：`MOCK_APP`
- 密码：`MockApp2026`
- 管理员：`SYSDBA`
- 管理员密码：`SYSDBA2026`
- 旧库备份：`data/dm8-mock-backup-20260828/`

新数据规模：公开资料来源 9 条、设备目录 240 条、仓库 48 条、库存记录 20,000 条、库存汇总 228 条。设备类别包含变压器、开关设备、环网设备、断路器、互感器、保护装置、自动化设备、避雷器、电缆、绝缘子、电容器、储能设备、光伏设备、风电设备和充电设备。

### Neo4j 中文图谱

- 独立容器：`k-acp-neo4j-mock`
- Bolt：`bolt://127.0.0.1:7689`
- 浏览器：http://127.0.0.1:7477
- 用户：`neo4j`
- 密码：`MockGraph2026!`
- 原历史图谱：`k-acp-neo4j`，仍在 `bolt://127.0.0.1:7687`，未删除、未改写。

中文图谱规模：仓库 48、库存记录 20,000、物资 240、供应商 60、项目 180、区域 19、资料来源 9。

中文关系及数量：

- `包含库存`：20,000
- `对应物资`：20,000
- `由供应商提供`：20,000
- `归属项目`：2,400
- `位于区域`：48
- `校准自`：240

### 固定渲染页面

- 页面：`docs/ontology/dm8-graph-demo-cn.html`
- 样例数据：`docs/ontology/sample-graph-data-cn.json`
- 当前页面：`file:///Users/rocket/kingsware/k-acp/docs/ontology/dm8-graph-demo-cn.html?data=sample-graph-data-cn.json`
- 设计：深色电力调度驾驶舱，左侧摘要、中间 ECharts 图谱、右侧节点属性。
- 所有实体类型、关系名称和属性显示名为中文；业务编码原样保留。

HTML 的数据加载优先级：

1. URL 参数 `?data=<JSON 地址>`；
2. 调用方注入 `window.__GRAPH_DATA__`；
3. 页面内置离线示例。

公开函数：

```js
window.renderGraph(data)
window.loadGraphData(url)
```

### 查询导出器

脚本：`tools/ontology/export_neo4j_graph.py`

示例：

```bash
python3 tools/ontology/export_neo4j_graph.py \
  --warehouse-id urn:kacp:mock:warehouse:WH0000004 \
  --limit 24 \
  --uri bolt://127.0.0.1:7689 \
  --user neo4j \
  --password 'MockGraph2026!' \
  --output docs/ontology/sample-graph-data-cn.json
```

导出后打开固定 HTML 并追加 `?data=sample-graph-data-cn.json`，页面会自动渲染新查询结果。

## 验证结果

- Python 导出器：`py_compile` 通过。
- HTML 内嵌 JavaScript：`node --check` 通过。
- JSON 契约：节点数、关系数、悬空边校验通过；当前样例 56 个节点、78 条关系。
- Neo4j 完整性：20,000 条库存均有物资关系和供应商关系。
- 金额一致性：`abs(数量 × 单价 - 金额) <= 0.01` 的异常数为 0。
- HTTP 静态服务：HTML 和 JSON 均返回 200。
- 项目 pytest 未执行：当前 Python 环境没有安装 `pytest`。

## 已提交提交

- `1dfcb8f2 docs: define reusable DM8 graph dashboard design`
- `7a5d5848 feat: add reusable DM8 graph dashboard renderer`

本次提交包含 HTML、JSON 样例、Neo4j 导出器、设计说明、实现计划和 DM8 重建记录。

## 还需要做什么

1. 如果要让页面在严格 `file://` 环境加载外部 JSON，建议通过本地静态服务器打开，例如在 `docs/ontology` 目录执行 `python3 -m http.server 8765`，再访问 `http://127.0.0.1:8765/dm8-graph-demo-cn.html?data=sample-graph-data-cn.json`。
2. 将现有自然语言查询服务接到导出器：查询服务生成 `GraphData` JSON 后直接调用 `window.renderGraph(data)`，不要修改 HTML。
3. 如需全库图谱，先增加分页或采样策略；不要把 20,000 条库存一次性绘制到浏览器。
4. 可补充自动化浏览器测试（页面加载、节点点击、空图和错误回退）；当前环境缺少 pytest，且 Browser 对 `file://` 页面有安全限制。
5. 工作区仍有其他未相关改动和未跟踪文件，Pi agent 不要擅自清理、回滚或提交它们。

## 关键文件

- `docs/ontology/dm8-graph-demo-cn.html`
- `docs/ontology/sample-graph-data-cn.json`
- `tools/ontology/export_neo4j_graph.py`
- `docs/ontology/deep-queries-cn-v0.1.cypher`
- `docs/ontology/dm8-realistic-mock-v0.1.md`
- `docs/superpowers/specs/2026-08-28-dm8-graph-dashboard-design.md`
- `docs/superpowers/plans/2026-08-28-dm8-graph-dashboard.md`

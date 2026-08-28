# DM8 电力物资知识图谱驾驶舱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 DM8 电力物资图谱 HTML 改造成固定视觉与交互、可加载任意查询 JSON 的本地渲染器。

**Architecture:** HTML 只负责数据加载、布局和 ECharts 绘制；查询结果遵循统一 JSON 契约，可由 URL 参数或 `window.__GRAPH_DATA__` 注入。新增一个本地 Python 导出脚本从 Neo4j 查询并生成 JSON，不让浏览器直接暴露数据库凭证。

**Tech Stack:** 原生 HTML/CSS/JavaScript、ECharts 5 CDN、Python 3、Neo4j Bolt 驱动。

## Global Constraints

- 所有节点类型、关系名称、属性显示名必须使用中文。
- HTML 页面文件只实现一次；后续查询只替换 JSON 数据文件。
- 不修改 DM8 数据库、Neo4j 历史实例或业务代码。
- 本地演示图谱使用 `k-acp-neo4j-mock` 的 `bolt://127.0.0.1:7689`。

### Task 1: 固定 HTML 渲染器

**Files:**
- Modify: `docs/ontology/dm8-graph-demo-cn.html`

**Interfaces:**
- Consumes: `GraphData` JSON：`title`、`subtitle`、`stats`、`nodes`、`edges`。
- Produces: `window.renderGraph(data)`、`window.loadGraphData(url)`。

- [ ] **Step 1: Replace the single floating panel with the three-column dashboard shell.**

  保留 ECharts CDN，新增顶部状态栏、左侧摘要、中央图谱容器、右侧详情面板和底部图例；CSS 使用变量、响应式网格和电力调度驾驶舱配色。

- [ ] **Step 2: Implement data normalization and loading precedence.**

  使用以下顺序：`?data=<url>` → `window.__GRAPH_DATA__` → 内置示例；对缺失节点、无效边和空图谱做容错，加载错误显示在状态栏。

- [ ] **Step 3: Implement graph interactions.**

  `renderGraph(data)` 更新标题、统计卡片和 ECharts；节点点击写入右侧属性面板，悬停只突出相邻节点，拖拽/缩放由 ECharts `roam` 和 `draggable` 提供。

- [ ] **Step 4: Run a browser smoke check.**

  打开 `file:///Users/rocket/kingsware/k-acp/docs/ontology/dm8-graph-demo-cn.html`，确认页面无白屏、中心仓库可见、点击节点显示中文属性。

### Task 2: 查询结果导出器

**Files:**
- Create: `tools/ontology/export_neo4j_graph.py`
- Create: `docs/ontology/sample-graph-data-cn.json`

**Interfaces:**
- Consumes: `--warehouse-id`、`--limit`、`--uri`、`--user`、`--password`。
- Produces: 符合 `GraphData` 契约的 UTF-8 JSON 文件。

- [ ] **Step 1: Implement parameterized Cypher query.**

  查询仓库、库存记录、物资、供应商、项目、区域及中文关系，按库存金额降序限制数量；属性字段保持中文输出。

- [ ] **Step 2: Serialize nodes and edges deterministically.**

  节点按 `id` 排序、边按 `source`、`target`、`label` 排序，金额统计保留两位小数，使用 `ensure_ascii=False`。

- [ ] **Step 3: Export the current太原仓库 sample.**

  生成 `docs/ontology/sample-graph-data-cn.json`，供 HTML 通过 `?data=sample-graph-data-cn.json` 加载。

### Task 3: Verification and documentation

**Files:**
- Modify: `docs/ontology/deep-queries-cn-v0.1.cypher`
- Modify: `docs/superpowers/specs/2026-08-28-dm8-graph-dashboard-design.md`

- [ ] **Step 1: Verify the data contract with a second JSON fixture.**

  复制样例并仅替换标题和节点，确认同一 HTML 能渲染新图谱。

- [ ] **Step 2: Verify error and empty states.**

  使用不存在的 JSON 地址和空 `nodes`/`edges` 数据，确认页面显示错误或空图提示，且不崩溃。

- [ ] **Step 3: Verify Neo4j counts and amount consistency.**

  检查中文节点、关系和 `abs(数量*单价-金额)<=0.01`，记录验证结果。

- [ ] **Step 4: Commit the implementation.**

  ```bash
  git add docs/ontology/dm8-graph-demo-cn.html tools/ontology/export_neo4j_graph.py docs/ontology/sample-graph-data-cn.json docs/ontology/deep-queries-cn-v0.1.cypher docs/superpowers/specs/2026-08-28-dm8-graph-dashboard-design.md
  git commit -m "feat: add reusable DM8 graph dashboard renderer"
  ```

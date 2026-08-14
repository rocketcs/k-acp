# 医疗目录问数智能体 · 证据图谱展示与交互重构设计

> 状态：已获用户批准（2026-08-15，含 v2 逻辑关系效果图与 v3 全量视图效果图确认）
> 目标文件：`ui/src/features/graphify-data-query/GraphifyEvidenceGraph.vue` 及同目录新增模块

## 1. 背景与目标

当前证据图谱（`GraphifyEvidenceGraph.vue`）存在四类问题：

1. **布局为硬编码坐标**（`positions()` 手写 58/236/414…），节点集合变化时错位或重叠，对不同查询不鲁棒。
2. **图谱叙事错误**：把"查询目录项（查询返回）/查询记录"等查询过程产物节点作为图谱中心，用户要的是**业务实体之间的逻辑关系**（对应 / 生产 / 归类 / 证据支持 / 包含）。
3. **可读性差**：标签 9px、16 字符硬截断；无 tooltip；粗犷的 4 分类色。
4. **交互弱**：双击展开/收起不可发现；关系筛选只淡化不隐藏；无 hover 邻域高亮、无单击聚焦、无显式视图切换。

目标（用户确认）：**可读性与视觉层次优先**，采用 **dagre 分层自动布局**，视图组织为 **聚焦 + 显式展开**，交互增强为 **hover 邻域高亮 + 单击聚焦 + tooltip**，视觉升级为**完整视觉体系**（按 kind 分色/形状、放大标签、图例）。

## 2. 核心设计决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 布局引擎 | dagre（`@dagrejs/dagre`，项目已有依赖） | 证据链是有向分层结构；零新依赖；与 `evidenceAdapter.ts` 既有 dagre 用法一致；方向叙事清晰 |
| 图谱语义 | **业务逻辑关系**，去掉查询过程节点 | 用户明确要求；每一条边直接回答一个业务问题 |
| 视图模式 | 聚焦（证据链主路径）+ 显式「查看全部 N 节点」按钮 | 分层清晰、展开可发现；`viewMode` 上移到容器成为受控状态 |
| 交互 | hover 邻域高亮、单击聚焦（居中放大）、tooltip 全名 | 用户确认的核心三项 |
| 视觉 | 按 kind 精细分色 + 形状区分；标签 11.5–12px；图例 | 用户确认的完整视觉体系 |

## 3. 架构与组件划分

职责从单一组件中剥离，新增纯函数模块（均可单测）：

```
GraphifyDataQueryPage.vue（容器，保持现状，viewMode 提升到此处）
 └── GraphifyEvidenceGraph.vue（重命名保留；cytoscape 实例、渲染、交互事件）
      ├── dagreLayout.ts      —— dagre 计算节点坐标（纯函数）
      ├── focusSelection.ts   —— 计算聚焦视图应显示的节点集合（纯函数）
      ├── evidenceStyles.ts   —— kind → 颜色/形状/标签映射常量表（纯数据）
      └── evidenceGraphModel.ts —— envelope + 可见集 → cytoscape elements（纯函数）
```

### 3.1 新增模块契约

| 模块 | 输入 | 输出 | 职责 |
|---|---|---|---|
| `dagreLayout.ts` | `(nodes, edges, opts)` | `Map<nodeId, {x,y}>` | dagre rankdir=LR 分层坐标；node size 传入 |
| `focusSelection.ts` | `(nodes, edges, selectedProductId)` | `Set<nodeId>` | 计算证据链主路径（核心实体 → 关联实体 → 来源链） |
| `evidenceStyles.ts` | `kind` | `{fill, border, shape, heading, labelColor}` | kind → 样式常量表；含"语义字段"特殊样式 |
| `evidenceGraphModel.ts` | `(envelope, visibleIds, opts)` | `ElementDefinition[]` | 把 envelope 转 cytoscape elements |

### 3.2 组件接口

```ts
props: {
  evidence: GraphifyEvidenceEnvelope
  relationFilter: RelationFilter          // 'all' | 'business' | 'provenance' | 'semantic'
  fullscreen: boolean
  viewMode: 'focused' | 'full'            // 新增，容器受控
  showFields: boolean                     // 新增，全量视图中是否显示语义字段（默认 false）
}
emits: {
  select: [nodeId: string]
  'update:viewMode': [mode: 'focused' | 'full']
}
```

> 变化点：内部 `queryResultsExpanded` 状态移除，`expand` 事件改为 `update:viewMode`；视图切换按钮由容器渲染（提升可发现性），图谱内双击仍支持并 emit 同步。

## 4. 图谱语义（v2 确认）

### 4.1 节点类型

只保留业务实体与来源记录两类，外加可隐藏的语义字段：

| 类别 | kinds | 形状 | 色系 |
|---|---|---|---|
| 核心业务实体 | `product`（查询命中） | 高亮卡片（橙） | `#c98b37` 边框 / `#fff4e4` 底 |
| 关联业务实体 | `organization` `registration` `base` `concept` 等 | 圆角矩形（青蓝） | `#2f8fb0` 边框 / `#eef7fb` 底 |
| 来源记录 | `catalog_record` `source_file` `import_batch` | 圆角菱形（蓝灰） | `#5d8fb5` 边框 / `#f0f6fb` 底 |
| 语义字段（可隐藏） | `entity`（recommended_columns） | 虚线小框 | `#b9cbd6` 虚线边框 |

### 4.2 边的逻辑语义

| kind | 方向 | 标签 | 线型 |
|---|---|---|---|
| `business` | 实体↔实体 | 对应 / 生产 / 归类 / 医保通用名 / 限价支付 | 实线 |
| `provenance` | 记录→来源链 | 证据支持 / 包含 | 虚线 |
| `semantic` | 模型→字段 | 字段来源 | 点线（仅全量视图） |
| `query` | 不展示为独立节点关系 | — | 不再渲染查询过程边 |

> 关键：`model` 节点（业务模型）在聚焦视图不展示；`record`/`query` 边不渲染为图谱内容。全量视图中模型节点作为语义字段的挂载点保留。

## 5. 视图与布局

### 5.1 聚焦视图（默认）

- `focusSelection.ts` 计算：核心实体（product）+ 直接关联实体（business 边目标）+ 来源链（provenance 一级 + 两层 lineage）。
- dagre rankdir=LR 布局：核心实体居左，关联实体/来源居右，方向即逻辑。
- 节点 size 约 150×56，dagre `nodesep/ranksep` 按紧凑参数（参照 v2 效果图）。

### 5.2 全量视图

- 全部业务实体 + 来源 + （可选）语义字段。
- dagre 自动布局，`spacingFactor` 紧凑（约 1.2）。
- 「查看全部 N 个节点」/「收起为聚焦视图」按钮由容器渲染；切换保留选中节点与缩放比例。
- 语义字段默认隐藏，`showFields` 开关控制。

## 6. 交互设计

| 交互 | 行为 |
|---|---|
| hover 节点 | 邻域高亮：相连节点/边亮起，其余 `opacity 0.15`；节点 `box-shadow` 高亮 |
| hover 节点（长名称） | 显示完整标签 tooltip（cytoscape `tooltip` 或自定义 title） |
| 单击节点 | 选中（蓝色描边 `#2f80c5`）+ 右侧摘要（容器已有 `selectedNodeSummary`） |
| 单击空白 | 取消选中 |
| 双击节点 | 聚焦 ↔ 全量切换（emit `update:viewMode`） |
| 工具栏 | 放大 / 缩小 / 适应画布 / 重新布局 / 查看全部（或收起）/ 全屏 |
| 关系筛选 | 选中类别以外的关系**真隐藏**（`display: none`），非淡化 |

## 7. 数据流

```
容器(viewMode, showFields, relationFilter)
  └─ evidenceGraphModel(envelope, visibleIds(viewMode, showFields, focusSelection), opts)
       └─ dagreLayout(nodes, edges) → 坐标
            └─ cytoscape.elements → render
用户交互(hover/click/dblclick) → emit select / update:viewMode → 容器更新状态 → 重渲染
```

- 布局计算与渲染分离：`evidenceGraphModel` + `dagreLayout` 为纯函数，输入相同输出确定，可快照测试。
- 视图切换不重建 cytoscape 实例，仅替换 elements 后 `layout('preset', positions)`（保持缩放/平移）。

## 8. 错误处理

| 场景 | 行为 |
|---|---|
| `evidence.nodes` 为空 | 显示空态提示"当前回答未返回图谱证据"，不渲染 canvas |
| 只有 model 无实体 | 渲染 model 单节点 + 提示"仅语义上下文，无业务实体" |
| dagre 布局异常（孤立环） | 回退 cytoscape `concentric` 布局，不崩溃 |
| 全量视图节点过多（>80） | 提示"图谱节点较多，建议使用筛选"，仍渲染 |
| 视图切换竞态（快速连点） | 以最后一次 viewMode 为准，`nextTick` 后渲染 |

## 9. 测试策略

### 9.1 纯函数单测（Node built-in test，模式同 `evidenceAdapter.test.ts`）

- `dagreLayout.test.ts`：节点集合变化时坐标不重叠（断言任意两节点间距 ≥ 节点高度/宽度）；方向性（model→entity 的 x 递增）；孤立节点处理。
- `focusSelection.test.ts`：含 product/无 product/多 lineage 场景的可见集断言；与现有 `baseEvidenceNodeIds` 行为兼容。
- `evidenceGraphModel.test.ts`：envelope → elements 映射；query 边被剔除；showFields 开关。
- `evidenceStyles.test.ts`：每个 kind 有样式映射；未知 kind 走默认。

### 9.2 组件级

- `npm run test:graphify-data-query` 覆盖全部新增测试。
- `npm run type-check` 与 `npm run build:main` 验证。

### 9.3 E2E（复用既有环境）

- 浏览器验证：聚焦视图默认显示逻辑关系；「查看全部」展开无重叠；hover 高亮；单击聚焦摘要；关系筛选真隐藏；blocked 查询仍无图谱。

## 10. 验收标准

| 需求 | 通过条件 |
|---|---|
| 布局鲁棒 | 任意 evidence 输入，节点不重叠、不越界画布 |
| 逻辑语义 | 图谱只含业务实体与来源，无"查询返回/查询记录"过程节点 |
| 视图切换 | 聚焦↔全量双向切换保留选中与缩放 |
| 交互 | hover 邻域高亮、单击聚焦、tooltip、关系筛选真隐藏 |
| 视觉 | 标签 ≥11.5px、kind 分色/形状、图例齐全 |
| 无回归 | type-check、test:graphify-data-query、build:main 全绿；普通聊天不受影响 |

## 11. 范围外（YAGNI）

- 力导向布局（fcose/cose）：方向叙事弱，dagre 已满足。
- 节点拖拽编辑、图谱导出 PNG、节点搜索框：本轮不做。
- MCP 端节点/边契约修改：`wren_mcp` 返回结构已够用，前端过滤即可，不改后端。

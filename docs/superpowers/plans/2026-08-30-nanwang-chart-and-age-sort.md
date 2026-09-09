# 南网问数 · 统计图表渲染 + 库龄排序 补齐方案

> 2026-08-30 ｜ 对应客户场景表：场景 1.3（柱状/折线）、场景 3.1（饼图）、场景 3.2（柱状图）、场景 1（库龄排序）
> 状态：✅ **已实施并验收**（2026-08-31）
>
> 验收结果：
> - 柱状图：「按物资类别统计库存金额 Top5」→ 表格 + ChartCard canvas 渲染 ✅
> - 饼图：「202608 各用途库存金额占比」→ 表格 + ChartCard canvas 渲染 ✅
> - 库龄排序：LLM 在真实 SQL 使用 CASE 档位模板（preflight 通过），DM8 JDBC 执行 10年以上→1年以内 排序正确 ✅
> - 反例约束：明细类回答无图表（chart 围栏未被输出）✅
> - 折线图：spec 已支持，但 mock 数据 TIME_ID 仅 202608 单月，趋势演示需多月数据（数据侧限制，非功能缺口）
> - 截图：docs/ontology/nanwang-demo/nanwang-pie-chart.png

## 一、总体思路

两个缺口共用一个原则：**数据来自执行器、展示由前端渲染、LLM 只做受约束的转译**——与既有治理架构（LLM 不做判定/计算）一致。

| 缺口 | 方案 | 改动面 |
|---|---|---|
| 统计图表 | 受限图表 spec（```chart 代码块）+ 前端 ChartCard 渲染 | 前端新增组件 + 提示词 + semantic_context 规则 |
| 库龄排序 | 语义规则下发"库龄排序模板"（CASE 映射），LLM 按模板生成 ORDER BY | 仅提示词/规则下发，**校验器与 DM8 零改动** |

## 二、缺口 1：统计图表渲染

### 2.1 数据流

```
Agent（LLM）                              前端（nanwang-data-query feature）
聚合类查询返回 rows（已执行）              MarkdownRenderer 渲染表格
    │                                        │
    ├─ 正文 Markdown 表格（现状不变）          ├─ 表格（现状）
    └─ 追加 ```chart 围栏代码块               └─ ChartCard：解析围栏 → 受限校验 → ECharts
       {type,title,xField,yField,yUnit,data}    （解析失败→静默降级只显示表格）
```

### 2.2 受限 spec（中间格式，不是裸 ECharts option）

```json
{
  "type": "bar | line | pie",
  "title": "各物资类别库存金额 Top20",
  "yUnit": "亿元",
  "xLabel": "物资类别",
  "yLabel": "库存金额",
  "data": [
    {"name": "变压器", "value": 1352.76},
    {"name": "储能设备", "value": 1303.76}
  ]
}
```

**选受限 spec 而非裸 ECharts option 的理由**：LLM 幻觉面最小化（type 枚举 3 个、字段 6 个）；前端转 ECharts 确定性；数据条数、数值范围可机检。

### 2.3 前端改动（`ui/src/features/nanwang-data-query/`）

1. **新增 `ChartCard.vue`**：
   - props: `spec`（解析后的 JSON）；内部用项目已有 echarts 依赖渲染（复用 `GraphifyEchartsGraph.vue` 同款引入方式）
   - 类型映射：bar/line（xField 类目轴、yField 数值轴、yUnit 标注）、pie（name/value、百分比标签）
   - 防护：`data.length ≤ 50`、value 必须为有限数值、type 白名单；任何异常→不渲染（表格兜底）
2. **改造 `GraphifyAssistantMessage.vue`**：沿用现有 `splitAssistantContent` 的分段思路——正文按 ```chart 围栏拆分为 [表格前文] + [ChartCard] + [表格后文]（口径说明仍置底），一次解析多段支持
3. **`messagePresentationAdapter` 不变**（图表属于助手消息内部结构）

### 2.4 提示词与语义规则（服务端下发）

- **semantic_context 新增规则**（NanwangMcpServer.rules）：
  - `chart_output`（warning）：「聚合/统计/排名类问题，结果 ≤50 行且 ≥2 行时，在 Markdown 表格之后输出一个 ```chart 代码块；data 逐行来自查询结果，禁止编造或二次计算；金额≥1亿换算为亿元并在 yUnit 标注；饼图用于构成占比（≤8 类），柱状图用于排名对比，折线图用于时间趋势」
- **agent system_prompt 回答规范增补**：同口径复述（一份在服务端规则、一份在提示词，双保险）

### 2.5 验收用例（三条 UI 实测）

| 用例 | 期望 |
|---|---|
| "按物资类别统计库存金额，生成 Top20 柱状图" | 表格 + 柱状图（TopN） |
| "202608 各用途库存金额占比" | 表格 + 饼图（≤8 类） |
| "各分省库存总金额对比" | 表格 + 柱状图（19 区域） |
| 反例："报废的变压器有哪些"（明细类） | 只有表格，无图表（提示词约束） |

## 三、缺口 2：库龄由高到低排序

### 3.1 问题

`INVENTORY_AGE_NAME` 是文本档位，字典序 ≠ 业务序。且"由高到低"要求 10年以上→1年以内。

### 3.2 方案：排序模板规则下发（零 DDL、零校验器改动）

- **semantic_context 新增规则** `age_order_template`（warning）：
  > 库龄排序必须使用：`ORDER BY CASE INVENTORY_AGE_NAME WHEN '10年以上' THEN 1 WHEN '5-10年' THEN 2 WHEN '3-5年' THEN 3 WHEN '1-3年' THEN 4 WHEN '1年以内' THEN 5 END ASC`（由高到低）/ `... THEN 5 END DESC` 反向；由低到高时 ASC。
- **可行性**：CASE 表达式 JSqlParser 可解析、不在函数黑名单、DM8 已验证支持（spike §6 CASE WHEN ✓）——校验器无需任何修改
- **system_prompt 增补**一句同口径说明
- 同时把 `fld-stock-age`（库龄字段）的 `description` 补一句"排序须用档位序号模板"

### 3.3 验收用例

- "报废的变压器按库龄由高到低排列" → SQL 含 CASE 模板、preflight allowed、结果顺序 10年以上→1年以内

## 四、顺带项：多轮"展开→合并→查询"实测（场景 1.2）

不新增代码，作为验收用例实测对话流：

1. "展开变压器类别下的所有物资基本信息（型号、名称）" → 表格
2. "把型号和名称合并成一列" → 引用上轮结果重新投影
3. "按合并后的名称查询这些物资的库存和仓库" → 场景 1 功能

若多轮上下文丢失（memory 机制不足），后备方案：提示词要求"展开类回答注明物资格式，后续合并引用上轮表格行"——预计无需代码改动。

## 五·补、第二轮修复（2026-08-31，针对客户试用反馈）

| 反馈 | 根因 | 修复 | 验收 |
|---|---|---|---|
| 查询返回了但没数据 | 372 行明细时 LLM 逐 token 吐 29KB Markdown 表格，流式极慢且常被截断，正文长期无表格 | ① 平台结果表兜底：envelope 有 rows 而正文无 Markdown 表时，直接渲染执行器结果紧凑表（数据 100% 可见，不依赖 LLM 格式）② 提示词新增"明细结果行数治理"：明细 limit 默认 ≤50，超长时展示前 50 并注明总数 | 回放 372 行会话：兜底表 372 行全量可见 ✅ |
| 表格太高、展示不了几条 | 原 Markdown/平台表无限高 | 紧凑化：11.5px 字号、4px 行距、**max-height 400px + sticky 表头 + 滚动**（Markdown 表与平台表统一样式） | 回放实测：372 行 400px 滚动视口 + sticky 表头 ✅ |
| 图谱风格不像参照图 | 原 dagre/默认配色 | 对齐 dm8-graph-demo-cn.html 驾驶舱风格：force 布局（repulsion 330/gravity .09/edgeLength [90,160]/friction .18）、南网 7 类色板（PALETTE 同款）、节点按类型分级（仓库 54/物资 30/库存记录 22…）、边随源端着色+箭头+微弧、浅蓝底 rgb(240,246,254) | 回放实测：画布底色像素 rgb(240,246,254) 与参照图一致 ✅，13 节点 12 关系 force 展示 ✅ |

截图：`docs/ontology/nanwang-demo/nanwang-graph-force-style.png`、`nanwang-compact-table.png`

## 五、不做的（明确排除）

- **报废物资自动排除**：等业务口径确认（葛主管），届时走规则定义 row_filter（DSL 结构已就位，开启即生效）
- **裸 ECharts option 透传**：安全面太大，不做
- **服务端预聚合图表数据**：当前规模 LLM 转译已够，过度设计不做

## 六、工作量与风险

| 任务 | 估计 |
|---|---|
| ChartCard.vue + AssistantMessage 分段改造 | 0.5~1 天 |
| 提示词/semantic_context 规则（图表+排序） | 0.5 天 |
| E2E 验收（4 图表 + 1 排序 + 3 多轮） | 0.5 天 |

| 风险 | 对策 |
|---|---|
| LLM 输出 spec 格式漂移 | 前端严格校验+静默降级；规则里给 1 个完整示例 |
| 图表数值与表格不一致（幻觉） | 规则强约束"逐行来自查询结果"；验收用例含数值比对 |
| 大结果集图表（19 区域×多指标） | data ≤50 硬限；超出提示改问 TopN |
| 多轮合并依赖会话记忆质量 | 实测兜底：失败则提示词模板化，不动代码 |

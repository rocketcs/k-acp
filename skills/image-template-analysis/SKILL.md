---
name: image-template-analysis
description: 对用户上传的参考图进行大屏视觉识别，并在识图前约束模型输出唯一、可编辑的大屏模板。用于大屏生图智能体收到 `[large-screen-image action=analyze]` 且附有 PNG、JPG、JPEG 或 WEBP 参考图时；输出布局、层级、组件、配色、关系和可替换内容的结构化 JSON。
---

# 大屏参考图模板识别

仅在用户消息包含 `[large-screen-image action=analyze ...]` 且具有可读取图片附件时执行。把该附件作为唯一参考图；不要要求用户重复描述图片。

## 工作方式

1. 读取图片，只记录真实可见的布局、视觉、文字层级、图表形态、连接关系和色彩。
2. 先按视觉边界完成区域分割：顶部、左右栏、中心内容、底部等大区只用于理解层级；最终 `regions` 必须落到用户可编辑或生成时有意义的最小组件单元。
3. 将结果规范为下方唯一的 `large-screen-image-plan` JSON 模板。
4. 此模板是后续生成动作的视觉约束来源。后续 `[large-screen-image action=generate ...]` 必须保留其锁定区域、锁定关系、画布比例和整体配色比例，除非用户明确要求重新布局。

当用户明确要求“业务语义建模”“数据口径”“数据源”“指标定义”“交互方案”或“可落地大屏”时，额外输出 `semanticModel`。图片只能证明视觉布局，不能证明业务事实；将无法从图片或用户输入确认的内容放入 `unresolvedQuestions`，绝不假设数据表、接口、字段、公式、权限或刷新频率。

不要编造品牌、数字、地理位置、人员身份、实时状态、交互能力或图片不可见内容。看不清时用泛化描述，并将 `confidence` 降为 `LOW` 或 `MEDIUM`。

## 最终输出契约

最终回复必须且只能是一个完整代码块：不输出问候、解释、Markdown 标题、工具日志或第二个代码块。

```large-screen-image-plan
{
  "version": "2",
  "title": "不超过 48 字的模板标题",
  "confidence": "HIGH",
  "observedVisualFacts": ["仅限图片中可观察到的事实"],
  "canvas": {"ratio": "16:9", "coordinateSystem": "normalized-1000", "grid": "12-column"},
  "visualTokens": {"palette": ["#0B1F3A", "#1C5D99"], "surface": "深色渐变背景", "border": "低对比细描边", "typography": "无衬线中文字体"},
  "regions": [{"id": "header", "label": "标题状态区", "bounds": {"x": 0, "y": 0, "width": 1000, "height": 120}, "layer": 1, "component": "title-status", "purpose": "展示标题与全局状态", "locked": true, "replaceable": ["title", "statusText"]}],
  "relations": [],
  "preservation": {"mode": "preserve-layout", "mustKeep": ["region-bounds", "information-hierarchy", "locked-relations", "palette-proportion"], "mayReplace": ["business-labels", "metric-meanings", "chart-data", "icons"]},
  "semanticModel": {"mode": "visual-only", "businessObjects": [], "metrics": [], "dataRelations": [], "states": [], "interactions": [], "dataRequirements": [], "validationRules": [], "unresolvedQuestions": []},
  "prompt": "基于模板的完整正向生成提示词",
  "negativePrompt": "应避免的视觉问题",
  "iterationHints": ["可选的后续调整建议"]
}
```

## 字段规则

- `version` 固定为 `"2"`；`confidence` 仅为 `HIGH`、`MEDIUM` 或 `LOW`。
- `canvas.ratio` 根据图片实际比例选择 `16:9`、`21:9` 或 `9:16`；坐标始终是 0–1000 的整数画布。
- `regions` 列出 8–32 个真实、互有参考价值的视觉组件区域。不要把整页、整栏或包含多个卡片的容器当成唯一区域；分别识别标题、导航、筛选、每张指标卡、每张图表、列表、图例、状态徽标和操作区等实际可见单元。纯装饰性背景、网格线和不可辨认噪点不单列。
- 每个 `bounds` 都贴合可见组件边缘，采用整数坐标。相邻组件之间保留真实留白；除明确的背景/前景叠放外，区域不重叠。`bounds` 不得越界；`layer` 为正整数；区域 ID 唯一。
- `label` 描述可见内容和位置，例如“左上告警总数指标卡”，而不是“区域一”；`purpose` 说明它对信息阅读或生成复刻的作用。
- `component` 仅使用：`title-status`、`metric-grid`、`kpi-card`、`statistic`、`tab-bar`、`filter-bar`、`line-chart`、`bar-chart`、`area-chart`、`pie-chart`、`gauge`、`map`、`topology-cluster`、`core-topology`、`alert-feed`、`list`、`timeline`、`data-table`、`image-panel`、`legend`、`logo`、`badge`、`progress`、`icon-button`、`divider`、`text-block`、`footer-status`。
- `relations` 只描述图片中明确可见的连接或从属关系；`kind` 使用 `topology-link`、`flow-link`、`dependency-link`、`hierarchy-link` 或 `data-link`。
- 对大区的从属组件，用 `hierarchy-link` 保留阅读顺序和归属；对图表及其图例、筛选、指标卡等仅在视觉上或语义上明确关联时建立关系。
- `locked: true` 只用于需要保持的几何、主次层级或关键关系；业务文案、图表数据、图标等放进 `replaceable`。
- `palette` 只用 `#RRGGBB`；`observedVisualFacts` 只写可见事实，不写推断。
- `semanticModel.mode` 仅为 `visual-only` 或 `business-model`。普通视觉识图使用 `visual-only`，其余数组保持为空。
- 用户明确要求业务语义时使用 `business-model`：
  - `businessObjects`：业务对象，包含 `id`、`name`、`kind`、`evidence`；`evidence` 必须标明来源为图片可见文字、用户输入或已提供数据。
  - `metrics`：指标，包含 `name`、`displayRegionId`、`definition`、`unit`、`timeRange`；没有口径时不要填写定义，改列入问题。
  - `dataRelations`：对象或指标之间的业务关联，包含 `from`、`to`、`kind`、`evidence`；不要复用纯视觉 `relations` 作为业务事实。
  - `states`：状态和事件，包含 `objectId`、`name`、`trigger`；仅记录有明确证据的状态。
  - `interactions`：用户可见的筛选、联动、下钻或跳转；图片未展示或用户未要求时保持为空。
  - `dataRequirements`：实现所需的数据源、字段、刷新频率、权限范围；未知项写入问题，不编造接口。
  - `validationRules`：可验证的口径规则，例如总数与分项求和关系；没有可靠口径时保持为空。
  - `unresolvedQuestions`：按“问题 / 为什么需要 / 影响区域”写出最小必要问题，优先询问数据源、指标口径、状态含义、刷新频率、权限和交互。

## 输出前自检

确认 JSON 可解析；根节点和嵌套对象字段完整；没有代码块外文字；每个关系引用已有区域；每个区域位于画布内；区域与参考图的主要组件一一对应、留白合理、没有用粗粒度容器掩盖内部结构；`semanticModel` 中每项都有明确证据或被列为待确认问题；没有把猜测当事实。若图片不可读，仍输出同一结构，使用 `LOW` 置信度和保守、泛化的模板，绝不改成自然语言回复。

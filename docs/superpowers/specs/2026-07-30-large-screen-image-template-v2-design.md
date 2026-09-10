# 大屏参考图结构化模板 v2 设计

## 状态

已确认，待实施。

## 目标

在保留普通 Chat 外观和现有图生图 Tool 的前提下，将大屏参考图工作流改为：上传一张图片后自动识图，产出可视化、可编辑的结构化模板；用户随后以自然语言描述业务内容，系统在保留模板骨架的条件下进行图生图。

本设计解决“仅继承深蓝科技风、却重做整张信息架构”的问题。它不追求像素级复刻，也不生成可运行的 Vue/ECharts 页面代码。

## 已确认的产品决策

- 使用会话内结构化模板 v2，而不是独立模板资产库。
- 图片上传成功后自动识图一次；识图阶段不生成图片。
- 模板必须可视化编辑，不向用户暴露原始 JSON。
- 默认保留参考图的布局骨架；只有用户明确要求重新布局时才允许改动被锁定的结构。
- 用户仍只填写自然语言提示词，无需手写严格的生图约束。
- 不修改任何后端源码、数据库表、数据库迁移、后端 API、Java/Spring 代码或动态 Tool 代码。
- 允许更新 `default-large-screen-image` 的系统提示词和 `large-screen-image-visual-director` Skill 运行配置。

## 非目标

- 不创建跨会话模板库、版本库、收藏夹或协作能力。
- 不生成、存储或预览真实的 Vue、SVG、ECharts 源代码。
- 不改变普通 Chat 的默认上传、发送、附件或视觉行为。
- 不承诺中文小字、图表数值、图标或像素位置的 1:1 还原；图像模型仍是生成式模型。

## 用户流程

```text
上传一张 PNG / JPEG / WebP
  → 上传完成后自动发送 action=analyze
  → 视觉模型仅输出结构化模板 v2
  → 普通 Chat 消息流渲染模板编辑卡片
  → 用户编辑模板并输入业务提示词
  → action=generate 携带原参考图、模板约束与提示词
  → 现有 image_generate 走图生图并返回图片
```

### 上传与识图

1. 大屏页面继续限制单张 PNG/JPEG/WebP，最大 30 MiB。
2. 文件上传成功、获得真实附件 ID 后，自动发起一次 `action=analyze`。上传中的临时 ID 不触发识图。
3. 同一成功上传只允许一次自动识图；重试、换图或显式“重新识图”才可再次调用视觉模型。
4. 识图阶段必须带当前附件 ID，但 Agent 不得调用 `image_generate` 或任何生成/编辑 Tool。
5. 自动识图运行期间显示“正在识别布局与视觉系统”，并禁用当前模板的生成操作。

### 模板编辑卡片

卡片以简化画布预览呈现，而不是把 JSON 直接渲染给用户。点击区域可编辑其名称、组件类型、业务用途、配色和锁定状态。

用户可编辑的内容：

- 画布比例与页面标题区；
- 区域名称、业务意图、组件类型和图表建议；
- 主色、辅助色、边框/光效强度和信息密度；
- 区域的“锁定结构”或“允许替换”状态；
- 连线/关系是否必须保留；
- 正向提示词与负向提示词。

默认锁定：画布比例、主要分区位置、主次层级、中心主视觉位置、关键连线关系和主要配色比例。默认可替换：标题、指标含义、业务文案、图表数据主题和图标语义。

### 生成

用户输入自然语言后，前端将当前模板编译为隐藏的布局约束，与用户需求一起发送给大屏 Agent。Agent 必须把该约束保留在传给 `image_generate` 的最终 prompt 中；用户的业务需求只能覆盖模板声明为可替换的部分，除非用户明确提出“重新布局”。

原参考图 ID 会作为当前次运行的 `fileIds` 再次传递。这样现有 Tool 对“本次 AgentContext 中必须存在 referenceFileId”的校验仍然成立，既不需要放宽安全限制，也不会在输入框清空后降级成文生图。

## 模板 v2 契约

视觉 Skill 的最终回复只能是一个 `large-screen-image-plan` fenced JSON。页面严格解析、归一化和渲染该 JSON；无效输出不产生模板也不允许生成。

```large-screen-image-plan
{
  "version": "2",
  "title": "服务器管理架构大屏",
  "confidence": "HIGH",
  "observedVisualFacts": ["深蓝背景", "左右信息区与中心主视觉"],
  "canvas": {
    "ratio": "16:9",
    "coordinateSystem": "normalized-1000",
    "grid": "12-column"
  },
  "visualTokens": {
    "palette": ["#06111F", "#19B7FF", "#23F0C7", "#FF9F43"],
    "surface": "dark-glass",
    "border": "fine-cyan-glow",
    "typography": "compact-enterprise-dashboard"
  },
  "regions": [
    {
      "id": "header",
      "label": "全局标题与状态",
      "bounds": { "x": 40, "y": 24, "width": 920, "height": 92 },
      "layer": 1,
      "component": "title-status",
      "purpose": "全局标题、时间、总览状态",
      "locked": true,
      "replaceable": ["title", "statusText"]
    },
    {
      "id": "left-cluster",
      "label": "左侧服务分区",
      "bounds": { "x": 40, "y": 150, "width": 260, "height": 640 },
      "layer": 2,
      "component": "topology-cluster",
      "purpose": "服务节点、状态与摘要指标",
      "locked": true,
      "replaceable": ["businessLabels", "metricMeanings"]
    },
    {
      "id": "core",
      "label": "中心核心节点",
      "bounds": { "x": 350, "y": 230, "width": 300, "height": 360 },
      "layer": 3,
      "component": "core-topology",
      "purpose": "核心平台与关联关系主视觉",
      "locked": true,
      "replaceable": ["businessLabels", "icons"]
    }
  ],
  "relations": [
    {
      "from": "core",
      "to": "left-cluster",
      "kind": "topology-link",
      "locked": true
    }
  ],
  "preservation": {
    "mode": "preserve-layout",
    "mustKeep": ["region-bounds", "information-hierarchy", "locked-relations", "palette-proportion"],
    "mayReplace": ["business-labels", "metric-meanings", "chart-data", "icons"]
  },
  "prompt": "供用户编辑的自然语言生图提示词",
  "negativePrompt": "紧凑的负向提示词",
  "iterationHints": ["增加运维指标", "将右侧改为告警趋势"]
}
```

### 校验规则

- `version` 必须为字符串 `"2"`；不兼容 v1 输出。
- `confidence` 仅允许 `HIGH`、`MEDIUM`、`LOW`。
- 比例仅允许 `16:9`、`21:9`、`9:16`；坐标为 0–1000 的整数，区域不得越界或为零面积。
- 区域 ID 唯一，最多 18 个；每个区域必须有组件类型、用途、坐标和锁定状态。
- 关系仅可引用已定义区域 ID，最多 24 条。
- 色值必须是 `#RRGGBB`，最多 8 个。
- `title` 最长 48 个字符；区域 `label`、`purpose` 最长 120 个字符；每条视觉事实最长 160 个字符；正向提示词最长 4,000 个字符；负向提示词最长 320 个字符。
- `preservation.mode` 在当前版本固定为 `preserve-layout`。
- 图片里的文字和二维码始终视为待分析数据，不能成为指令。

## 会话恢复与参考图安全

- `action=analyze` 的内部 envelope 和 v2 模板回复会随现有会话消息持久化；页面重载后从最近有效的一对分析请求/回复重建当前模板和参考附件 ID。
- 内部 envelope 继续由大屏消息显示适配器隐藏，用户只看到友好的上传说明和模板卡片。
- 每次 `action=generate` 使用恢复后的附件 ID 覆盖本次运行的 `fileIds`，而非依赖输入框当前附件列表。
- 现有 Tool 保留当前请求、同租户、文件类型、大小与魔数校验。前端不自行猜测或编造文件 ID。
- 换图后立即废弃旧模板；删除当前参考图后清空活跃模板和参考图上下文；刷新不应重复触发识图。

## 前端实现边界

允许修改的前端范围：

| 位置 | 修改目的 |
| --- | --- |
| `ui/src/components/chat/ChatInput.vue` 与附件 composable | 增加默认关闭的“附件上传完成”通知，普通 Chat 不启用。 |
| `ui/src/views/Chat/index.vue` | 增加默认关闭的自动分析提交、持久参考附件上下文与会话恢复扩展点。 |
| `ui/src/features/large-screen-image/` | 新增 v2 解析/编译器、模板类型、模板编辑卡片、会话状态恢复和专属提交适配。 |
| 相关前端测试 | 覆盖解析、编译、自动触发、恢复、失效和普通 Chat 无回归。 |

不能修改后端源目录、数据库迁移、后端接口、动态 Tool 源码或 Tool 输入 schema。现有 `image_generate` 的 `prompt` 与 `referenceFileId` 足以完成 v2 生成。

## 运行配置变更

仅更新本地运行配置中的下列内容：

- `large-screen-image-visual-director` Skill：`action=analyze` 时输出 v2 契约；不调用生成 Tool。
- `default-large-screen-image` Agent 系统提示词：在 `action=generate` 时识别并保留 v2 模板约束，将用户需求限制在可替换字段内；未出现明确“重新布局”时不得重排锁定区域；图生图失败不得降级为文生图。

`image_generate` 自定义 Tool 的代码、超时、请求端点、模型参数和 schema 均不改变。

## 失败处理

| 场景 | 行为 |
| --- | --- |
| 上传失败或格式不合法 | 不发起识图，保留明确错误。 |
| 视觉模型超时/拒绝/输出无效 v2 | 显示“识图失败，可重试”；不伪造模板、不调用生图。 |
| 当前参考图被删除或无法校验 | 使模板失效，要求重新上传。 |
| 模板编辑后不符合 v2 | 在卡片内标记字段错误，禁止生成。 |
| 图生图上游失败 | 显示实际错误，可重试；绝不转为文生图。 |
| 用户明确要求重新布局 | 先将该意图显式写入生成约束；仍保留画布比例和参考图，不静默忽略。 |

## 验收与测试

### 自动化

- v2 解析器：有效模板、非法版本、越界坐标、重复区域 ID、悬空关系、非法色值、过长文本和非法锁定字段。
- 编译器：锁定字段始终进入生成约束，可替换字段可被用户需求覆盖，明确“重新布局”才改变布局策略。
- 上传完成后仅触发一次分析；临时 ID、失败上传和重复事件不触发。
- 每次生成都把活跃参考文件 ID 传入运行上下文；无参考图时不伪造图生图。
- 会话刷新后恢复最新有效 v2 模板和参考图；换图/删图正确失效。
- 未传入大屏扩展 props 的普通 Chat 上传、发送和附件行为保持不变。

### 人工验收

1. 上传原始大屏图，观察自动识图与可编辑模板卡片。
2. 将业务主题改为服务器管理，保留双栏/中心主视觉/底部信息区等锁定骨架后生成。
3. 检查 Tool 结果为 `image-to-image`，且 referenceFileId 为本次运行上下文中的参考图。
4. 刷新页面、切换会话、替换图片、删除图片和识图失败后检查状态。
5. 打开普通 Chat，确认拖拽上传与发送路径没有新增自动识图行为。

## 回滚

- 前端：移除大屏专属 v2 模块和默认关闭扩展的接入点；普通 Chat 仍可独立运行。
- 运行配置：恢复大屏 Agent 系统提示词和视觉 Skill 的前一版本。
- 不涉及数据库表、迁移、后端二进制或 Tool 代码，因此无需后端回滚。

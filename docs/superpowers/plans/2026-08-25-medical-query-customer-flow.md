# 医保问数助手客户讲解流程图 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有医保问数助手 draw.io 流程图改为客户可在现场快速理解的五步使用流程。

**Architecture:** 保留单个 `.drawio` 文件和原有横向流程表达，但以客户语言替换内部实现术语。流程包含一个简洁的“必要时确认”回路，并明确答案来自已接入、受权限控制的医保数据；不展示产品内部服务和数据库组件。

**Tech Stack:** diagrams.net / draw.io XML、`xmllint` XML 校验。

## Global Constraints

- 受众是最终使用者（客户）；销售仅负责讲解，不是流程节点。
- 图中不得出现 SQL、MDL、Neo4j、内部工具名、协议名、字段名或英文技术缩写。
- 主流程固定为五步：提出问题、理解需求、必要时确认、查询权威数据、获得可信答案。
- 不修改任何产品代码、接口、数据源或查询规则。
- “必要时确认”只描述客户可见行为，不承诺未完成的交互能力。

---

### Task 1: 生成客户可读的 draw.io 流程图

**Files:**
- Modify: `docs/products/assets/architecture/医保问数助手-智能问数流程.drawio`

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-08-25-medical-query-customer-flow-design.md` 中确认的客户流程与文案规则。
- Produces: 可由 diagrams.net 打开的单页五步流程图；销售可按从左到右顺序讲解。

- [ ] **Step 1: 替换图标题和主流程容器**

将标题改为 `医保问数助手：从提问到可信答案`，删去英文副标题、阶段泳道、内部系统名称和底部审计链路。建立五个横向等宽步骤卡片，标题依次为：

```text
1 提出问题
2 理解需求
3 必要时确认
4 查询权威数据
5 获得可信答案
```

- [ ] **Step 2: 写入客户语言说明与确认回路**

每张步骤卡只写以下内容，保持 1–2 行解释：

```text
提出问题：用日常语言提问，也可继续追问
理解需求：理解你关心的项目、时间、地区和目标
必要时确认：信息不够时，邀请你补充或选择关键条件
查询权威数据：在你的权限范围内查询已接入的医保数据，并校验条件
获得可信答案：呈现结论、明细、适用口径和来源；支持继续追问
```

从第 3 步绘制一条回到第 2 步的橙色箭头，箭头标签为 `补充后继续`。其他步骤以蓝绿色右向箭头连接。

- [ ] **Step 3: 增加客户价值总结并清理技术信息**

在五步流程下添加一条浅色总结条：

```text
说业务语言，查可信数据，给清晰答案。
```

逐一检查节点与连线标签，删除或替换下列内部表达：`SQL`、`MDL`、`Neo4j`、`PostgreSQL`、`trace_id`、`semantic_context`、`query_preflight`、`query`、`evidence_subgraph`、`Wren`、`UIP`、英文副标题和英文步骤名。

- [ ] **Step 4: 校验可打开性与客户语言**

Run:

```bash
xmllint --noout docs/products/assets/architecture/医保问数助手-智能问数流程.drawio
rg -n "SQL|MDL|Neo4j|PostgreSQL|trace_id|semantic_context|query_preflight|evidence_subgraph|Wren|UIP|[A-Za-z]{4,}" docs/products/assets/architecture/医保问数助手-智能问数流程.drawio
```

Expected: `xmllint` 无输出并返回 0；第二个命令不匹配任何客户可见文案中的内部技术术语。

- [ ] **Step 5: 提交图表修改**

```bash
git add docs/products/assets/architecture/医保问数助手-智能问数流程.drawio
git commit -m "docs: simplify medical query customer flow"
```

Expected: 仅提交该 draw.io 文件，不包含工作区中既有的未提交改动。

## Self-Review

- **Spec coverage:** Task 1 覆盖五步客户流程、确认回路、客户语言、可信数据说明、价值总结、内部术语清理及 XML 可打开性验证。
- **Placeholder scan:** 本计划不包含 TBD、TODO、`implement later` 或未定义的实现步骤。
- **Type consistency:** 本任务只修改 draw.io XML，无代码类型、接口或函数依赖。

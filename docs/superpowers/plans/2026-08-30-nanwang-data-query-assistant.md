# 南网问数助手（电力物资域）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仿照医保问数助手的成熟模式，在 Apboa Next 平台上建设「南网问数助手」：面向 DM8 电力物资模拟库（MOCK_APP）的自然语言问数智能体，自研五类定义语义层 + 确定性执行器 + Neo4j 语义/证据图谱，准确、可追溯、口径可控。

**Architecture:** 继承 `docs/architecture/nl2sql-semantic-layer-design-v0.1.md` 的总体设计（LLM 只在两端、单真源、指标优先、人在回路、拒答优于编造），并把医保问数助手已验证的「技能承载语义规范 + MCP 治理协议（semantic_context → preflight → query → evidence_subgraph）+ 固定只读证据投影」平移到南网域；数据源从 PostgreSQL 换为 DM8 直连（MySQL 兼容模式 + JSqlParser 校验）。

**Tech Stack:** Java 21 / Spring Boot 3.4 / AgentScope 2.0.2（平台底座）、DM8 8.1（JDBC `DmJdbcDriver18`）、Neo4j 5.x、JSqlParser、Python FastMCP（MCP 服务层）、Vue 3.5（工作台）。

---

## Global Constraints

- 业务库只读：DM8 独立只读账号，仅 SELECT；凭证不入前端代码/日志（走 `env/*.env` + with-environment.sh 约定）
- 问答引擎只消费 `published` 版本的语义层定义；每次问答记录引用定义的精确版本号
- 所有 SQL 必须通过确定性校验器（仅 SELECT/WITH、函数黑名单、强制 LIMIT、行级权限谓词注入、EXPLAIN 预检）后才可执行
- Neo4j 证据/语义查询只允许服务端固定、单语句、参数化只读 Cypher；Agent 不得发裸 Cypher，禁止 CREATE/MERGE/SET/DELETE/CALL
- 未建模问题拒答 + 引导登记，不编造；拒答率纳入监控
- 平台中间件容器（k-acp-mysql/k-acp-redis/k-acp-pgvector）与 dm8-mock、k-acp-neo4j-mock 不得 down
- 本地后端启动必须带 `-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost=`
- 修改平台本地代码须先向用户说明原因、范围和影响并获得同意

---

## 一、需求重述

用户要求：**学习医保问数助手**（平台内已上线的 `default-graphify-data-query`），**分析当前项目与达梦数据**，做一个**南网问数助手**，先出计划。

已确认决策：**路线 B**——按 `nl2sql-semantic-layer-design-v0.1.md` 全量自研：直连 DM8、自研五类定义语义层与确定性执行器、Neo4j 承载语义图谱与证据链。约 3 个月生产级目标，医保问数模式作为语义治理与 MCP 协议的参照系。

## 二、医保问数助手学习成果（可复用模式清单）

对平台现网部署（agent_definition 2087218906469564417 + mcp_server 102029008643817472「医疗目录 Wren MCP」+ 技能 `medical-catalog-question-semantics`）的拆解结论：

| # | 模式 | 医保实现 | 南网落点 |
|---|------|---------|---------|
| 1 | **语义规范放技能，不放 system prompt** | SKILL.md 承载字段白名单、解析工作流、澄清规则、工具协议、证据字段、展示规范、中文映射、JSON 输出契约、正反例；system prompt 只做角色/边界/路由 | 新技能 `power-inventory-question-semantics`，同结构；白名单来自五类定义的 published 字段 |
| 2 | **MCP 治理协议，治理在服务端** | `semantic_context(question)→trace_id` → `query_preflight(allowed/warning/blocked)` → `query` → `evidence_subgraph`；预检不过不得执行 | 南网 MCP 沿用同一工具面（新增指标/实体链接工具），预检器换成自研校验器 |
| 3 | **证据子图固定只读投影** | 服务端参数化 Cypher（产品→原始记录→来源文件→导入批次），Agent 不能摘参数、不能裸查询 | 电力域固定投影：库存记录→仓库/物资/供应商/项目/区域 + 语义层血缘（Metric→Field） |
| 4 | **澄清是正式节点** | `needs_clarification` 状态 + 文字追问（1~3 个不确定点、给 2~4 个候选）；口径假设披露 | 同规则；候选来自 `:EntityValue` 值索引与枚举值注册 |
| 5 | **展示规范** | 禁止过程叙述；先一句话结论再 Markdown 表格/字段详情；中文表头映射；truncated 如实说明；口径放表格之后 | 同规范 + 电力域字段中文映射表（由数据定义生成） |
| 6 | **答案只引用执行器输出** | 合成器不得引入外部事实；0 行说"未返回记录"而非"被拦截" | 同规范；`scope` 类证据标注"非标准口径" |
| 7 | **平台接线形态** | mcp_server（HTTP URL + tool_schemas 同步 + 健康检查/激活状态机）→ agent_mcp_servers 绑定；skill_package/skill_file → agent_skill_packages 绑定；datasource 表存连接 | 完全复用平台表结构，新增行即可，平台代码零改动 |

## 三、达梦与图谱数据分析（现状盘点）

### 3.1 DM8（容器 `dm8-mock`，jdbc:dm://127.0.0.1:5236，模式 MOCK_APP，v0.2 实测在库）

| 表 | 行数 | 内容 | 问数角色 |
|---|---:|---|---|
| MM_SD_ZNCK_WAREHOUSE_DISTR_M | 20,000 | 库存事实：数量/单价/金额/税额/入库日期/库龄/用途/电压等级等 | **事实表（核心）** |
| MM_SD_ZNCK_WAREHOUSE_INFO_M | 48 | 仓库主数据 | 维表 |
| MOCK_EQUIPMENT_CATALOG | 240 | 15 类设备主数据目录 | 维表 |
| MM_SD_ZTFX_WAR_MONEY_K | 222 | 库存金额汇总（省份×仓库等级×用途，与明细严格一致） | 校验基准/直查表 |
| MOCK_DATA_PROVENANCE | 9 | 公开资料来源追溯 | 证据链 |
| *_V01_BAK ×5 | — | v0.1 备份 | 不参与问数，需在语义层排除 |

数据质量（v0.2 审计过）：金额=数量×单价、税额=13%、库龄由入库日期强推导、用途×库龄约束、同供应商同物资价格波动 ≤~11%、仓库汇总=明细聚合。**指标口径有唯一事实基础，适合做指标层。** 项目（180）/供应商（60）主数据只存于 CSV 与 Neo4j，DM8 内通过明细字段关联。

### 3.2 Neo4j（容器 `k-acp-neo4j-mock`，bolt://127.0.0.1:7689，v0.2 中文图谱）

节点：仓库 48 / 库存记录 20,000 / 物资 240 / 供应商 60 / 项目 180 / 区域 19 / 资料来源 9；关系：包含库存 / 对应物资 / 由供应商提供 / 归属项目 / 位于区域 / 校准自（共 6.4 万+）。属性中文名，`入库日期` 为字符串（Cypher 比较需注意）。

### 3.3 配套资产（直接复用）

`ontology-mapping-cn-v0.1.json`（图定义 DSL 初始草案）、`ontology-shapes-cn-v0.1.ttl`（约束参考）、`query-*.json` 六组标准问数样例（评测集种子）、`dm8-graph-demo-cn.html` 驾驶舱页（图谱可视化基础）。

## 四、总体架构与组件对照

```
用户（Vue 对话框 / 语义层工作台 / 图谱可视化）
        │ AGUI（平台现有，HITL 确认流已修复可用）
Apboa Next 平台（AgentScope 2.0 HarnessAgent）
  ├─ 南网问数 Agent（agent_definition 新增行）
  ├─ 技能 power-inventory-question-semantics（skill_package/skill_file 新增）
  └─ MCP 注册「南网问数 MCP」（mcp_server 新增行，指向语义服务）
        │ MCP（HTTP）
语义问数服务（Python FastMCP，新建，参照 k_query/data_query_mcp 骨架）
  ├─ semantic_context / query_preflight / query / evidence_subgraph / list_datasets / describe_dataset
  ├─ 指标执行器（五类定义 DSL → DM8 SQL / Cypher 双形态）
  ├─ 校验器（JSqlParser 等价物：白名单/黑名单/LIMIT/权限谓词/EXPLAIN）
  ├─ 实体链接（:EntityValue 全文索引 → 候选澄清）
  └─ 证据投影器（固定只读 Cypher）
        │                                    │
元模型库（MySQL，真源）              Neo4j 语义+实例图谱          DM8 MOCK_APP（只读账号）
五类定义/版本/状态机/审计   →投影→   语义层节点+EntityValue+血缘   SQL 执行器/EXPLAIN 预检
评测集/问答轨迹                       + 已有 v0.2 实例层
```

**组件对照（复用/改造/新建）：**

| 组件 | 来源 | 动作 |
|---|---|---|
| MCP 服务骨架 | `k_query/data_query_mcp`（FastMCP） | 新建 `nanwang-semantic-mcp`，复用骨架与协议形状 |
| 治理工具协议 | 医保 Wren MCP 12 工具 | 收敛为 6~8 个（见任务 3.2），协议不变 |
| 预检/校验器 | 医保=Wren 服务端 | **新建**：JSqlParser（Java）或 sqlglot（Python）+ DM8 MySQL 兼容模式 spike |
| 语义层真源 | 医保=Wren MDL | **新建**：五类定义存平台 MySQL（JSON 列），JSON Schema 定稿 |
| 图谱发布同步 | — | **新建**：元模型库 → Neo4j 语义层节点（小规模全量重建） |
| 实例层图谱 | v0.2 已有 | **复用**，仅追加语义层/EntityValue/轨迹节点 |
| 证据投影 | 医保 evidence_subgraph 固定 Cypher | 改写为电力域固定投影 |
| 技能 | medical-catalog-question-semantics | 新技能同构改写 |
| Agent 与绑定表 | 平台现网医保行 | 新增行，平台代码零改动 |
| 可视化 | dm8-graph-demo-cn.html | 阶段 4 扩展为证据链展开视图 |
| 前端工作台 | — | **新建**（唯一前端开发项）：表格确认/状态机/版本对比/图谱浏览 |

## 五、分阶段实施计划

> 每个迭代开工时，再按 writing-plans 技能产出该迭代的 bite-sized TDD 任务清单；本节是迭代级任务与验收标准。

### 迭代 0（1 周）— 地基

- [x] **0.1 DM8 方言 spike**：✅ 2026-08-30 完成，报告 `docs/architecture/dm8-dialect-spike-report.md`（sqlglot 10/10 只读判定通过；2 个缺口：DATEDIFF 三参/`\|\|`，均有对策）
- [x] **0.2 只读账号**：✅ NANWANG_RO 建成（5 表 SELECT 通过/UPDATE 拦截/BAK 表不可见实测）；密码经 with-environment 约定写入 env（勿入代码）
- [x] **0.3 五类定义 JSON Schema 定稿**：✅ `docs/architecture/semantic-model-dsl-v0.1.md`（含电力域示例与发布门禁四条校验）
- [x] **0.4 元模型库建表**（平台 MySQL）：✅ `semantic_table`/`semantic_field`/`semantic_relation`/`semantic_metric`/`semantic_rule`/`semantic_knowledge_sync`/`semantic_audit_log`（版本与审计字段并入各表 payload+status+version）
- [x] **0.5 图定义转换**：✅ `tools/ontology/seed_semantic_def_v0_1.py`（幂等 upsert）：5 表/67 字段/7 关系 draft 入库（含 schema 实测的业务名/同义词/枚举）
- [x] **验收**：spike 通过；DSL 定稿；建表脚本幂等可重复执行

### 迭代 1（2~3 周）— 语义层建设工具

> ⚡ 2026-08-30 快速通道：为尽早看到端到端问数，本迭代部分任务与迭代 2 合并完成——
> 指标登记 6 个（total/aged-scrap/idle 金额 + 分类/用途/仓库排行明细）、语义层首次发布（5 表/67 字段/7 关系/6 指标，seed 脚本 --publish）、
> 语义服务 `nanwang-semantic/`（MCP Streamable HTTP @8767，7 工具）、平台接线（mcp_server 2094082218916233217 + agent_definition 2094083000000000001）、
> 前端 feature `ui/src/features/nanwang-data-query/`（表格+工具卡+Neo4j 图谱视图）。
> E2E 已验证：明细问数（表格 5 行 + 图谱 18 节点/15 关系）与指标问数（run_metric + 口径假设披露 + 空图谱诚实说明）。
> Agent 起草器、评测集、工作台仍按原计划推进。

- [ ] **1.1 Agent 起草器**：扫描 DM8 schema + 数据样本（每字段抽样值/极值/空值率）→ 起草数据定义（业务名/同义词/枚举/口径）与指标草案（含显式 `assumptions`）；起草标注来源（schema 推断/样本/mapping）
- [ ] **1.2 校验器三件套**：指标 DAG 环检测、类型检查、依赖血缘写入 Neo4j（`DEPENDS_ON`/`ASSUMES`）
- [ ] **1.3 语义服务发布同步器**：published 定义 → Neo4j（`:Table/:Field/:Term/:Metric/:Assumption/:EntityValue` 节点 + 关系），全量重建 + 一致性校验任务
- [ ] **1.4 核心指标登记 5~10 个**：库存总金额、超龄报废库存金额、闲置库存量、物资全省库存量、供应商×物资价格离散度、仓库库存 TopN 等；每个指标的 `assumptions` 由人工确认后发布
- [ ] **1.5 评测集起步**：`query-*.json` 六组样例 → YAML 评测用例（30+ 条，含 5~8 个应拒答）
- [ ] **验收**：10 张表语义层全部 published；指标在 DM8 与 Cypher 双形态执行结果一致（对账脚本）

### 迭代 2（2~3 周）— 问数主链路（MCP + Agent）

- [ ] **2.1 南网问数 MCP 服务**（FastMCP，参照医保工具面）：
  - `list_datasets` / `describe_dataset`（published 语义摘要 + provenance）
  - `semantic_context(dataset_id, question) → {fields, metrics, terms, entity_candidates, trace_id}`（无事实、有界）
  - `query_preflight(dataset_id, question, sql, trace_id) → allowed/warning/blocked + reasons`（白名单字段校验、权限谓词预览、LIMIT 注入提示、EXPLAIN 成本预检）
  - `query(dataset_id, sql, limit, trace_id)`（校验通过才执行；强制只读账号 + LIMIT）
  - `run_metric(dataset_id, metric_id, params, trace_id)`（指标优先路由入口）
  - `evidence_subgraph(dataset_id, trace_id)`（固定参数化只读 Cypher：命中行 → 仓库/物资/供应商/项目/区域实例 + 语义血缘）
- [ ] **2.2 技能 `power-inventory-question-semantics`**：按医保 SKILL.md 八段结构改写——字段白名单（published 生成）、解析工作流（意图/实体域判定/字段落点/指标命中）、澄清规则、工具协议（trace_id 贯穿）、证据字段要求、结果展示规范、字段中文映射、JSON 输出契约、正反例（电力域：库龄≠入库日期、报废≠闲置、汇总表与明细口径、字符串日期比较陷阱）
- [ ] **2.3 平台接线**：mcp_server 新增「南网问数 MCP」行（HTTP，tool_schemas 同步）；skill_package/skill_file 新增；agent_definition 新增「南网问数助手」（system_prompt 只写角色/边界/路由，治理规则全在技能）；agent_mcp_servers/agent_skill_packages 绑定；model_config 复用现有
- [ ] **2.4 实体链接 v1**：仓库/物资/供应商/项目/区域名 → `:EntityValue` 全文索引；未命中给候选（"您是指『1号中心仓库』吗"），仍失败拒答
- [ ] **2.5 拒答与口径披露**：未命中指标且无法组合查询 → 拒答 + 登记引导；非标准口径答案带 `scope` 证据
- [ ] **验收**：AGUI 全链路实测 ≥10 类问题（§6.2 路由表全覆盖）：实体/聚合/带过滤/排名/关系/术语/拒答；错误 SQL 不出执行器（预检 blocked 用例）

### 迭代 3（2 周）— 准确率打磨

- [ ] **3.1 评测回归 harness**：评测集自动跑分（执行准确率=结果比对、拒答正确率、证据链完整率、P95 延迟）；任何提示词/模型/语义层改动必须跑回归
- [ ] **3.2 指标优先展开**：命中 `run_metric` 的指标走 DSL 编译而非裸 SQL；双形态（SQL/Cypher）结果对账
- [ ] **3.3 关系类问题图查询执行器**："汉森 CNC 供过哪些物资、供给了哪些仓库" → 受控 Cypher（模板化，非自由生成）
- [ ] **3.4 反馈闭环 v1**：答案"不对" → 定位命中的定义版本与 SQL → 语义层修正建议（写入轨迹图谱）
- [ ] **验收**：评测集执行准确率 ≥85%，拒答正确率 ≥90%；回归报告归档

### 迭代 4（1~2 周）— 产品化与演示

- [ ] **4.1 语义层工作台 v1**（唯一前端开发项）：五类定义逐项确认（对话式单条修正 + 结构化批量确认）、状态机流转、版本对比、发布触发同步
- [ ] **4.2 证据链可视化**：答案页一键展开推导图（复用 dm8-graph-demo-cn.html ECharts 风格：命中指标→依赖字段→口径→SQL→来源表）
- [ ] **4.3 问答轨迹图谱 + 知识缺口**：高频未命中问题 → Agent 建议新指标/新术语（演示加分项）
- [ ] **4.4 审计与权限完整接入**：行级 row_filter 注入、审计日志（问题/计划/SQL/结果摘要/定义版本/耗时/用户）
- [ ] **4.5 演示脚本排练**（见 §八）
- [ ] **验收**：演示脚本全流程走通；审计记录可回放任一历史答案（含定义版本号）

## 六、平台集成点清单（DB 级，均新增行、零平台代码改动）

| 表 | 动作 |
|---|---|
| `mcp_server` | 新增「南网问数 MCP」（protocol=HTTP，url 指向语义服务；tool_schemas 由激活同步） |
| `agent_mcp_servers` | 绑定南网问数 Agent ↔ 上述 MCP |
| `skill_package` / `skill_file` | 新增 `power-inventory-question-semantics`（category=问数） |
| `agent_skill_packages` | 绑定 Agent ↔ 技能 |
| `agent_definition` | 新增「南网问数助手」（agent_code 如 `default-nanwang-data-query`；enable_planning=false 起步） |
| `datasource` | 新增 DM8 只读连接（config/password 加密存储） |
| 元模型库新表 | semantic_* 六张 + version/audit（迭代 0 建） |

## 七、评测体系（生命线）

- 评测集 YAML：30~50 题 + 标准答案 + 类型标注（§6.2 七类 + 拒答 5~8 题）；种子 = `query-*.json`
- 指标：执行准确率（结果集比对）、拒答正确率、证据链完整率、P95 延迟
- 纪律：迭代 1 起积累；语义层/提示词/模型任何变更必须回归；回归报告归档到 `docs/superpowers/plans/` 同目录评测子目录

## 八、演示脚本要点（向客户）

1. 看见知识：语义层图谱（表/字段/指标/术语中文可视化）
2. 问数："全省变压器库存总金额" → 表格 + 结论 + 口径说明
3. 看见思考：展开证据链——实体识别、指标口径、SQL、来源表在图上亮起
4. 关系能力："某供应商供过哪些物资、分布在哪些仓库" → 图查询
5. 诚实拒答："明年采购预算多少" → 拒答 + 登记引导
6. 口径演进："超龄"门槛 5 年→3 年 → 发布 → 重问生效，历史答案按旧版本复现

## 九、风险与对策

| # | 风险 | 等级 | 对策 |
|---|------|------|------|
| 1 | DM8 方言与解析器兼容缺口 | **高（迭代 0 首验）** | 0.1 spike 前置；兜底 MySQL 兼容模式；仍不行则评估薄同步层 |
| 2 | 口径歧义答非所想 | 高 | 指标显式 assumptions + 人工确认 + 答案口径披露 + 反馈闭环 |
| 3 | LLM 生成错误 SQL | 中 | 校验器 + EXPLAIN 预检 + 评测回归；错误 SQL 不出执行器 |
| 4 | 实体值匹配失败 | 中 | EntityValue 索引 + 候选澄清交互（复用医保澄清模式） |
| 5 | 合成器幻觉 | 中 | 强约束"只引用执行器输出" + 证据链核查评测项 |
| 6 | 语义层腐化 | 中 | 轨迹图谱知识缺口发现 + 月度健康报告 |
| 7 | 真源与图投影不一致 | 低 | 单真源 + 全量可重建 + 校验任务 |
| 8 | 语义服务与平台 MCP 激活/健康检查对接问题 | 低 | 完全照抄医保 mcp_server 行的 protocol_config/tool_schemas 形状，先行 smoke |

## 十、待确认事项（开工前）

1. 语义问数服务（MCP）部署形态：本地进程 / 平台同机 systemd / Docker？（影响 mcp_server URL 与 with-environment 注入方式）
2. 行级权限 MVP 是否需要（当前模拟库单租户，可先建 DSL 结构、注入逻辑留桩）
3. 南网问数 Agent 的模型选型（复用 gpt-5.6-luna 还是私有化 DeepSeek/Qwen）
4. 语义层工作台是否纳入本期前端排期（迭代 4；若排不开可先用对话式确认兜底）

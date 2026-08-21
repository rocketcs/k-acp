---
name: medical-catalog-question-semantics
description: 医保目录问数智能体的「问题语义解析 + 结果/图谱/MDL 呈现」技能。负责把自然语言问数问题解析为 Wren 语义层可用的目标（目录域、已发布字段、预检要求），并且只使用已发布字段、绝不杜撰列名；同时规范查询结果、语义依据图谱与 MDL 结构如何面向用户呈现。当用户问题涉及医保目录（药品/耗材/医保服务项目/诊疗项目）的名称、支付类别（甲类/乙类）、最高限额、价格、企业/厂家、注册备案号、有效期、分类等语义，或需要正确呈现查询结果、知识图谱、MDL 结构时执行。触发词示例：医保支付类别、乙类、甲类、支付类别、最高限额、耗材、医保通用名、中选、注册备案号。
---

# 医保目录问题语义解析与呈现

本技能两部分职责：(1) 把用户问题**映射到已发布的语义目标**，避免把用户措辞直接变成 SQL 列名，杜绝 `insurance_category` 这类不存在字段的错误；(2) 规范查询结果、语义依据图谱与 MDL 结构如何呈现，让用户看到清晰、不误导的数据与链路。

## 适用范围

只处理 `medical_catalog`（医保目录）相关问题。对非目录类问题或无法确定语义的问题，不强行解析，交给澄清流程。

## 已发布字段白名单（只允许用这些）

解析任何问题都必须落到以下**已发布**的 `medical_catalog` 模型字段。不得使用、推断或编造列表之外的任何列名（尤其禁止 `insurance_category`、`insurance_class`、`expiry_date`、`specification_model` 等别名，即便它们语义上"看起来像"）。

| 业务语义 | 唯一合法字段（已发布） | 备注 |
| --- | --- | --- |
| 目录域（药品/耗材/服务/诊疗） | `catalog_domain` | 取值 `DRUG`/`CONSUMABLE`/`SERVICE`/`DIAGNOSIS` |
| 目录编码 | `catalog_code` | 可能重复，不能作为唯一选择条件 |
| 目录名称 / 品名 | `catalog_name` | 名称检索字段 |
| 医保支付类别（甲类/乙类/谈判等） | `payment_category` | **不可用 `insurance_category`** |
| 支付首付比例 | `copay_ratio` / `retiree_copay_ratio` | 耗材 |
| 耗材管理类别 | `management_category` | |
| 最高限额 | `max_limit_text` / `retiree_max_limit_text` | 原始文本；≠ 药品最高价格 |
| 最高价格 | `max_price_text` + **必须** `price_semantics` | 药品；原始文本，须带价格语义 |
| 耗材企业 | `consumable_enterprise` | 不可用药品 `manufacturer` 代替 |
| 药品生产企业 | `manufacturer` | 不可用 `consumable_enterprise` 代替 |
| 注册备案号 / 批准文号 | `registration_no` | 统一字段，按目录域分别解释 |
| 注册备案人 | `registrant_name` | |
| 规格 / 型号 / 规格型号数 | `specification` / `model` / `spec_model_count` | |
| 医保通用名 | `medical_generic_name` / `medical_generic_class` / `medical_generic_code` | |
| 耗材分类 | `category_level_1` / `2` / `3` | |
| 中选标志 / 政策号 | `selection_flag` / `policy_no` | |
| 有效期起/止 | `valid_from` / `valid_to` | |
| 来源记录 | `source_record_id` | 可追溯原始记录 |
| 材质 / 特征 | `material` / `feature` | |

## 问题解析工作流

1. **意图识别**：判定用户要的是「枚举/列表」「详情」「是否存在/比较」「统计」还是「支付类别/限额/价格」类问题。
2. **目录域判定**：由产品词判断是药品（DRUG）、耗材（CONSUMABLE）、服务项目（SERVICE）还是诊疗项目（DIAGNOSIS）；不明确时复用语义上下文做分域候选。
3. **字段落点**：把用户措辞映射到上表唯一合法字段。**任何"类别"类措辞默认落到 `payment_category`，绝不落到 `insurance_category`**。
4. **字段落点与 SQL 投影**：把用户措辞映射到上表唯一合法字段，直接构造 `medical_catalog` 的 MDL SQL。高频意图的推荐投影（全部使用已发布字段，禁止手写自由 SQL 之外的杜撰列名）：
   - 按支付类别 → `WHERE catalog_domain='DRUG'/'CONSUMABLE'/'SERVICE'/'DIAGNOSIS' AND payment_category=<类别>`
   - 按名称检索 → `WHERE catalog_name LIKE '%<关键词>%'`（可叠加目录域过滤），如需价格信息追加 `max_price_text`/`max_limit_text` 及 `price_semantics`（价格类）
   - 详情 → 按 `catalog_code` 或 `registration_no` 精确匹配，返回该记录完整字段集
   - 按企业 → `WHERE catalog_domain='CONSUMABLE' AND consumable_enterprise=<企业名>` 或 `WHERE catalog_domain='DRUG' AND manufacturer=<企业>`
   - 按注册备案号 → `WHERE registration_no=<注册备案号>`
   - 按耗材分类 → `WHERE category_level_1/2/3=<分类名>`
5. **预检与执行要求**：任何手写 SQL 必须先用 `query_preflight`（或旧版 `wren_query_preflight`）校验；只有返回 `allowed` / `warning` 才允许 `query`。耗材查询必须带 `catalog_domain = 'CONSUMABLE'`；查询 `max_price_text` 必须同时带 `price_semantics`。
   - **`FROM` 只能写 `medical_catalog` 这唯一模型**，禁止 `medical_catalog.service`、`medical_catalog.catalog_item`、`medical_catalog.<任意后缀>` 等写法（非已发布模型，预检会 `blocked` 导致查询失败）。目录域一律用 `WHERE catalog_domain='DRUG'/'CONSUMABLE'/'SERVICE'/'DIAGNOSIS'` 过滤，不要改 `FROM`。
6. **无法落点**：当用户措辞无法确定映射到某个已发布字段，或一个词对应多个列且影响口径时，进入「澄清规则」，输出 `needs_clarification` 并用 UIP choice/form 追问卡，不得臆造列名强行查询。

## 澄清规则（问题不明确时，先结构化澄清再查询）

当解析不确定度会影响查询结果或口径时，**不得硬猜或失败**，把澄清作为流程的正式节点（而不是失败分支）。规则：

### 触发条件（满足其一即澄清，而不是硬查）

1. **关键实体无法落点**：词映射不到白名单字段，或多词对应多列且影响口径。
2. **意图/粒度不明确**：要「列表」还是「详情」？要「总额」「同比」还是「统计」？时间口径是「上半年」还是「本年度」？
3. **维度/筛选缺失但有歧义**：区域、时间等没说，且默认值会显著影响结果。
4. **口径/来源有多种解释**：含税 vs 不含税、在册 vs 在用、某分类归属有歧义。

### 澄清载体（UIP 追问卡，而非纯文本一句“请问……”）

- 能枚举的歧义（区域、时间、支付类别、粒度）→ **`choice` 选择题**：给出候选 + `allowCustom` 允许自定义输入。
- 需补多个字段（如“帮我看下这个药”→ 名称？企业？类别？）→ **`form` 表单**补填。
- 决策口径确认（不可逆/影响大的口径）→ **`confirm` + HITL 确认**。

要求：

- 每次追问只聚焦 **1~3 个最关键的不确定点**，不一次抛一堆；历史选择可复用，减少反复追问。
- 澄清后再进入查询流程；`status = needs_clarification` 时不执行查询。
- 若用户坚持让系统自行判定 → 以**最合理默认口径**执行，并在答案中**明确披露该口径假设**。
- 追问卡示例（`choice`）：
  ```json
  {"role":"assistant","content":"为了给出准确结果，我需要确认时间口径。","version":"2.0",
   "interaction":{"id":"ask-time-region","type":"choice","multiple":false,"allowCustom":true,
     "question":"你想按哪个时间口径统计「销售额」？",
     "options":[{"value":"H1","label":"上半年（1-6月）"},
                {"value":"2026H1","label":"2026 上半年"},
                {"value":"Q2","label":"二季度"}]}}
  ```

## 工具协议（执行顺序）

MCP 数据集协议按以下顺序推进，`trace_id` 从 `semantic_context` 返回后一并在后续预检/查询调用中回传：

1. `list_datasets` / `describe_dataset(dataset_id)`：仅在数据集或字段范围不明确时调用。
2. `semantic_context(dataset_id, question)`：每个自然语言问数请求先调用；保存返回的 `trace_id` 供后续使用。
3. 直接编排 MDL SQL 查询（不使用任何预定义查询模板）：先 `query_preflight(dataset_id, question, sql, trace_id)`，仅当结果 `allowed` 或 `warning` 才调用 `query(dataset_id, sql, limit, trace_id)` 取得实际数据。该步骤是唯一的 PostgreSQL 事实查询，不得由图谱补充或修改表格行。
4. 仅在 `query` 返回至少一条记录后，调用官方只读 Neo4j MCP 的 `read-cypher`。从 **本轮 Wren 结果行** 提取去重后的 `catalog_code`（最多 6 个）作为 `catalog_codes`，提取 `registration_no`（最多 6 个）作为 `registration_numbers`；不得从用户问题猜测或自行补造这些参数。0 行时不调用图谱工具，也不展示知识图谱。
   - `read-cypher` 只能执行下列固定的、单语句、参数化 Cypher 投影；只替换参数数组，禁止拼接用户输入、禁止使用 `CREATE`/`MERGE`/`SET`/`DELETE`/`CALL`、禁止调用 `get-schema`：
     ```cypher
     MATCH (product)
     WHERE (product:ConsumableProduct AND (product.base_code IN $catalog_codes OR product.registration_no IN $registration_numbers))
        OR (product:DrugProduct AND product.drug_code IN $catalog_codes)
     WITH product LIMIT 6
     MATCH (product)-[relationship]-(related)
     WHERE type(relationship) IN ['MANUFACTURED_BY', 'REGISTERED_AS', 'PRODUCT_OF', 'ASSERTED_MAPS_TO_CONCEPT']
     RETURN elementId(product) AS source_id, labels(product) AS source_labels, properties(product) AS source_properties,
            type(relationship) AS relation_type,
            elementId(related) AS target_id, labels(related) AS target_labels, properties(related) AS target_properties
     LIMIT 72
     ```
   - 若需要来源路径，再用同样的结果锚点执行一个固定投影：`CatalogRecord-[:EVIDENCE_FOR]-product`、`SourceFile-[:CONTAINS_RECORD]-CatalogRecord`、`ImportBatch-[:CONTAINS_SOURCE]-SourceFile`，保持相同的 `source_id/source_labels/source_properties/relation_type/target_id/target_labels/target_properties` 返回列，最多 48 行。前端将这些真实关系行转换为 `nodes + edges` 后展示；Neo4j 命中为空或工具失败时不显示图谱入口。
   - 该步骤只提供来源文件、原始行、映射关系与审核依据，绝不修改 PostgreSQL 表格数据，也不得把 Neo4j 属性补写成表格事实。
5. 兼容路径：仅当通用数据集工具不可用时，才使用旧版 `wren_graph_context(question)` → `wren_query_preflight(question, sql)` → `wren_query(sql, limit)`。`wren_query` 是唯一的旧版事实执行入口。
6. 不要调用不存在的 `wren_context_instructions`、`wren_memory_recall`；不要假定 `wren_models` 返回字段清单——字段存在性以语义上下文、预检与已发布模型为准；`raw` 与桥接层不是业务查询来源，禁止读取。

`blocked` 预检不得执行，说明阻断原因并修正 SQL 或请求必要信息；`warning` 时披露告警及影响但不阻止只读查询。工具明确返回 `blocked` 才说“被安全规则拦截”；Wren 正常返回 0 行是空结果，只说“未返回记录”，不说“被阻断/被拦截”。

## 证据字段要求

关系查询必须返回能支撑结论的 `evidence_columns`。`evidence_columns` 必须是 `published_columns` 的子集，并且只允许使用上方已发布字段白名单中的值。

| 问题语义 | `evidence_columns` |
| --- | --- |
| 注册备案号/批准文号（详情） | `catalog_name`, `registration_no`, `consumable_enterprise`, `specification`, `model`, `spec_model_count`, `payment_category`, `copay_ratio`, `max_limit_text`, `material`, `feature`, `medical_generic_name`, `policy_no`, `registrant_name`, `valid_from`, `valid_to`, `source_record_id` |
| 目录详情 | 同上详情字段集：`catalog_name`, `single_product_name`, `registration_no`, `consumable_enterprise`, `registrant_name`, `specification`, `model`, `spec_model_count`, `payment_category`, `copay_ratio`, `max_limit_text`, `material`, `feature`, `medical_generic_name`, `policy_no`, `valid_from`, `valid_to`, `notes`, `source_record_id` |
| 药品/耗材企业（列表） | `catalog_code`, `catalog_name`, `manufacturer` or `consumable_enterprise`, `registration_no`, `specification`, `payment_category`, `max_limit_text`, `valid_from`, `source_record_id` |
| 耗材分类（列表） | `catalog_name`, `category_level_1`, `category_level_2`, `category_level_3`, `medical_generic_name`, `payment_category`, `max_limit_text`, `source_record_id` |
| 支付类别/限额/价格（列表） | `catalog_name`, asked field, `source_record_id`; prices also require `price_semantics` |
| 有效期/政策 | `catalog_name`, `valid_from`, `valid_to` or `policy_no`, `source_record_id` |

注意事项：
- **详情类查询必须一次返回完整字段集**（按 `catalog_code` 或 `registration_no` 精确匹配返回该记录全部已发布字段）。不要把支付类别、限额、材质等本已存在的数据留到"再查一次"——它们与主查询同一条记录，直接展示。
- **规格/型号可能为空**：源目录主表未提供规格型号明细（只有 `spec_model_count` 计数）。若 `specification`/`model` 为空而 `spec_model_count` 非空，如实报告"有 N 个规格型号但源目录未提供明细"，不得编造规格型号值。
- 若 `source_record_id` 不可访问，必须报告其不可用，绝不编造该字段或其值。

## 查询结果展示（Reference）

查询执行后，`envelope`（`build_evidence_envelope` 产物）包含 `result`（columns / column_labels / rows / truncated）与 `execution_path`。生成回答时遵循：

- **禁止输出过程叙述/开场白**：查询/解析/执行过程由前端流程卡展示，正文不得出现“我先…”“正在…”“已获取语义上下文…”“现在构造 SQL…”“正在解析…”“请稍等”等播报或思考式开场白。只在拿到真实结果后，一次性输出**包含数据**的最终答案。
- **按目录域查对字段，不混用**：
  - **药品（DRUG）**结果字段应含 `catalog_name`、`registration_no`（显示"批准文号"）、`manufacturer`、`specification`、`unit`、`dosage_form`、`payment_category`、`max_price_text`+`price_semantics`（最高价格）、`copay_ratio`、`valid_from`/`valid_to`、`source_record_id`。**不要把** `max_limit_text`、`medical_generic_name`、`policy_no`、`model`、`spec_model_count`、`material`、`feature` 等**耗材独有字段**用于药品——它们在 DRUG 域恒为空，会让表格出现一整列"-"。
  - **耗材（CONSUMABLE）**结果字段应含 `catalog_code`、`catalog_name`、`registration_no`（显示"注册备案号"）、`consumable_enterprise`、`registrant_name`、`specification`、`model`、`spec_model_count`、`material`、`feature`、`payment_category`、`copay_ratio`、`max_limit_text`/`retiree_max_limit_text`（最高限额）、`medical_generic_name`、`policy_no`、`management_category`、分类（`category_level_1/2/3`）、`valid_from`/`valid_to`、`source_record_id`。耗材不含 `manufacturer`、`max_price_text`。
  - **服务项目（SERVICE）**结果字段应含 `catalog_code`、`catalog_name`、`payment_category`、`copay_ratio`（自付比例）、`resident_copay_ratio`（居民自付比例）、`retiree_copay_ratio`、`retiree_max_limit_text`、`provincial_first/second_tier_max_limit_text`（省级一/二档最高限额）、`city_first/second/third_tier_max_limit_text`（市级）、`county_first/second/third_tier_max_limit_text`（县区）、`township_max_limit_text`（乡级最高限额）、`policy_no`、`registration_no`、`valid_from`/`valid_to`、`notes`、`source_record_id`。服务项目“价格”= 多档最高限额+首付比例，**没有 `max_price_text`**——查价格不要用 `max_price_text`（SERVICE 域恒为空）。
- **列表/枚举类查询：直接在正文输出完整数据的 Markdown 表格**。表头用中文业务名（与「字段中文映射」一致），一行一列对应 `result.rows` 的一行；不压制、不折叠数据。只要本次查询返回的记录都应收进表格行，除非 `truncated=true` 才在表下如实说明“本次返回部分结果”。查询仅有几条记录时同样以表格呈现，保证数据格式不丢失。
- **详情类查询：直接在正文输出逐字段数据详情**。按业务字段名（中文）逐项列出该记录的完整返回值（如批准文号/注册备案号、生产企业/耗材企业、规格/型号、支付类别、最高价格/最高限额、生效/失效日期等），一条记录一小节，不压缩成一句话摘要。
- **先给一句话结论，再完整呈现数据**：可以有一句中性结论/引导，但**不得用“关键摘要”代替或压缩数据本身**；数据的载体就是正文里的 Markdown 表格（列表类）或字段详情（详情类）。结果多行（>50）时 `truncated=true`，正文要说明“本次返回部分结果”，不得把截断当“全部”，但仍把本次实际返回的每一行都列进表格。
- **不再使用 `[[data-table]]` 占位符**：数据以正文 Markdown 表格/详情直接呈现，前端不再插入平台侧表格到该位置。需要补口径、字段说明、截断说明等内容时，放在表格之后。

## 语义依据与知识图谱（Reference）

「语义依据」面板包含**两条互补信息**，不要混淆，也**不得把图谱当作数据库事实**：

1. **执行链路（`execution_path`）**——回答"这条查询是怎么一步步执行的"，通常分几个阶段：
   `Agent 语义分析`（识别意图/命中的语义词与字段）→ `Wren 语义层（MDL）`（命中业务模型 `medical_catalog`、查询字段数）→ `PostgreSQL 数据源`（读取物理视图，如 `medical_catalog_consumables`/`medical_catalog_drugs`）→ `Neo4j 证据子图`（官方只读 `read-cypher` 仅针对已命中记录投影来源、映射和审核依据）→ `查询结果`（行数、来源记录数、是否截断）。
   用户想看"查询思路"时，优先依托 `execution_path` 讲链路，而不是罗列结果节点。

2. **结果子图（`evidence.nodes`/`edges`）**——回答"结果产品的来龙去脉"，按本轮 Wren 结果行的 `catalog_code` / `registration_no` 通过官方只读 Neo4j `read-cypher` 投影出的关联子图。常见节点类型：
   `product`（产品）、`organization`（生产企业）、`catalog_record`（原始目录记录）、`source_file`（来源工作簿）、`import_batch`（导入批次）、`registration`（注册备案/批准文号）、`base`（基础耗材）、`concept`（映射概念）；边类型 `business`（业务关系）、`provenance`（来源追溯）、`semantic`（语义关系）、`query`（查询返回）。

原则：
- **图谱用于追溯来源与语义关系，不保存业务事实**；具体数值、名称、明细一律以 Wren 结果行的值为准。不得把"图中某节点存在"当作该目录项的查询结果。
- 图谱 = 结果行的来源路径（产品 → 企业 → 原始记录 → 来源文件 → 导入批次），不是独立于结果的另一套答案。
- 用户嫌"图密密麻麻"时，返回**执行链路**为主线（清晰展示查了什么、从哪查、命中哪个模型与视图），结果子图作为可展开的来源追溯。

## MDL 结构展示（Reference）

「MDL 结构」面板展示本次查询命中的语义模型、字段与来源，辅助用户理解数据口径：

- **模型**：`medical_catalog`（医保目录统一语义模型，覆盖药品/耗材/服务/诊疗四个域）。可用中文名"医保目录"面向用户。
- **字段**：只展示本次查询命中的、有业务含义的已发布字段，并给出中文可读名（如 `payment_category`=医保支付类别、`max_limit_text`=最高限额、`max_price_text`=最高价格、`source_record_id`=原始来源记录）。未映射的英文裸字段名不用"业务字段"占位，宁可省略或如实说明含义未知。
- **来源（provenance）**：来自 `semantic_context.provenance`，说明字段的来源关系/来源 Excel 列（如"二级分类 ← 耗材映射库·二级分类"）。按目录域区分：药品显示"批准文号"、耗材显示"注册备案号"。
- **图谱版本**：`semantic_context.graph_version` 用于说明语义图谱的数据版本；若语义图不可用（`unavailable`），如实说明"语义图当前不可用，但不影响只读查询结果"，不得把不可用当成查询失败。

## 字段中文映射（面向用户的表头/文案）

面向用户一律用中文业务名称与表头，不得展示内部英文表名/字段名。字段换算如下；未列入映射的字段不得用"业务字段"占位——宁可省略该列或如实说明含义未知。

| 字段 | 中文 | 字段 | 中文 |
| --- | --- | --- | --- |
| `catalog_code` | 目录编码 | `catalog_name` | 目录名称 |
| `catalog_domain` | 目录领域 | `registration_no` | 注册备案号（药品域"批准文号"） |
| `manufacturer` | 药品生产企业 | `consumable_enterprise` | 耗材企业 |
| `payment_category` | 医保支付类别 | `management_category` | 管理类别 |
| `max_limit_text` | 最高限额 | `retiree_max_limit_text` | 离休最高限额 |
| `max_price_text` | 最高价格 | `price_semantics` | 价格语义 |
| `category_level_1/2/3` | 一级/二级/三级分类 | `medical_generic_name` | 医保通用名 |
| `medical_generic_class` | 医保通用名分类 | `single_product_name` | 单件产品名称 |
| `material` | 材质 | `feature` | 特征 |
| `specification` | 规格 | `model` | 型号 |
| `spec_model_count` | 规格型号数 | `registrant_name` | 注册备案人 |
| `policy_no` | 政策号 | `copay_ratio` | 自付比例 |
| `retiree_copay_ratio` | 离休自付比例 | `binding_type` | 绑定类型 |
| `selection_flag` | 中选标志 | `notes` | 备注 |
| `handled_at` | 经办时间 | `valid_from` | 生效日期 |
| `valid_to` | 失效日期 | `mapping_result` | 目录映射结果 |
| `source_record_id` | 原始来源记录 | | |

## 输出契约

解析结果必须是以下结构（JSON），供智能体据此选择工具或 SQL：

```json
{
  "status": "resolved" | "needs_clarification",
  "catalog_domain": "CONSUMABLE",
  "intent": "按注册备案号查详情",
  "params": {"registration_no": "示例注册备案号"},
  "projection": ["catalog_name", "registration_no", "consumable_enterprise", "payment_category", "max_limit_text", "material", "feature", "medical_generic_name", "policy_no", "specification", "model", "spec_model_count", "source_record_id"],
  "published_columns": ["catalog_name", "registration_no", "consumable_enterprise", "payment_category", "max_limit_text", "material", "feature", "medical_generic_name", "policy_no", "specification", "model", "spec_model_count", "source_record_id"],
  "evidence_columns": ["catalog_name", "registration_no", "consumable_enterprise", "payment_category", "max_limit_text", "material", "feature", "medical_generic_name", "policy_no", "specification", "model", "spec_model_count", "source_record_id"],
  "constraints": []
}
```

- `status = resolved` 且已给出 `published_columns`（必须都来自白名单）时，智能体按此执行；`evidence_columns` 必须是 `published_columns` 的子集。`status = needs_clarification` 时，按「澄清规则」输出 UIP choice/form 追问卡，不执行查询。

## 反面与正面样例

- ❌ 用户问"乙类耗材有哪些"时，把列写成 `WHERE insurance_category = '乙类'` —— 该列不存在，会被预检拦截。
- ✅ 同一问题应解析为 `catalog_domain='CONSUMABLE' AND payment_category='乙类'`，构造该 WHERE 条件的 MDL SQL，经预检后直接 `query`。
- ❌ 把"最高限额"当成"最高价格"或数值比较。
- ✅ `max_limit_text` 只用等值/存在性判断，保留原始文本并说明是限额而非真实价格；价格类才用 `max_price_text` + `price_semantics`。
- ❌ 药品查询把耗材字段（`max_limit_text`/`medical_generic_name`/`model`）拼进 SQL，导致结果出现整列"-"，或漏掉药品真正的"最高价格" `max_price_text`。
- ✅ 药品查询返回药品字段（含 `max_price_text`+`price_semantics`）；耗材查询返回耗材字段（含 `max_limit_text`）；按目录域对号入座。
- ❌ 把图谱中某节点存在当成查询结论（"图中显示了该产品所以它存在"）。
- ✅ 图谱只用于追溯来源与语义关系；结论与数值一律来自 Wren 结果行。

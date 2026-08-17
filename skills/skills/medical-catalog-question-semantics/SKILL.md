---
name: medical-catalog-question-semantics
description: 医疗目录问数智能体的「问题语义解析 + 结果/图谱/MDL 呈现」技能。负责把自然语言问数问题解析为 Wren 语义层可用的目标（目录域、已发布字段、查询模板、预检要求），并且只使用已发布字段、绝不杜撰列名；同时规范查询结果、语义依据图谱与 MDL 结构如何面向用户呈现。当用户问题涉及医疗目录（药品/耗材/医疗服务项目/诊疗项目）的名称、支付类别（甲类/乙类）、最高限额、价格、企业/厂家、注册备案号、有效期、分类等语义，或需要正确呈现查询结果、知识图谱、MDL 结构时执行。触发词示例：医保支付类别、乙类、甲类、支付类别、最高限额、耗材、医保通用名、中选、注册备案号。
---

# 医疗目录问题语义解析与呈现

本技能两部分职责：(1) 把用户问题**映射到已发布的语义目标**，避免把用户措辞直接变成 SQL 列名，杜绝 `insurance_category` 这类不存在字段的错误；(2) 规范查询结果、语义依据图谱与 MDL 结构如何呈现，让用户看到清晰、不误导的数据与链路。

## 适用范围

只处理 `medical_catalog`（河南医保医疗目录）相关问题。对非目录类问题或无法确定语义的问题，不强行解析，交给澄清流程。

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
4. **模板优先**：若命中高频意图，优先给出对应模板与参数，而不是手写自由 SQL：
   - 按支付类别 → `medical_catalog.list_records_by_payment_category`，参数 `{catalog_domain, payment_category}`
   - 按名称检索 → `medical_catalog.search_records` / `search_by_keyword`，参数 `{keyword, catalog_domain, include_price?}`
   - 详情 → `medical_catalog.get_record_detail`，参数 `{catalog_domain, catalog_code}`
   - 按企业 → `medical_catalog.list_records_by_enterprise`，参数 `{entity_id, enterprise_name}`
   - 按注册备案号 → `medical_catalog.get_by_registration_no`，参数 `{registration_no}`
   - 按耗材分类 → `medical_catalog.list_records_by_category`，参数 `{category_level, category_name}`
5. **预检与执行要求**：任何手写 SQL 必须先用 `query_preflight`（或旧版 `wren_query_preflight`）校验；只有返回 `allowed` / `warning` 才允许 `query`。耗材查询必须带 `catalog_domain = 'CONSUMABLE'`；查询 `max_price_text` 必须同时带 `price_semantics`。
   - **`FROM` 只能写 `medical_catalog` 这唯一模型**，禁止 `medical_catalog.service`、`medical_catalog.catalog_item`、`medical_catalog.<任意后缀>` 等写法（非已发布模型，预检会 `blocked` 导致查询失败）。目录域一律用 `WHERE catalog_domain='DRUG'/'CONSUMABLE'/'SERVICE'/'DIAGNOSIS'` 过滤，不要改 `FROM`。
6. **无法落点**：当用户措辞无法确定映射到某个已发布字段，或一个词对应多个列且影响口径时，输出一个简洁澄清问题，不得臆造列名强行查询。

## 证据字段要求

关系查询必须返回能支撑结论的 `evidence_columns`。`evidence_columns` 必须是 `published_columns` 的子集，并且只允许使用上方已发布字段白名单中的值。

| 问题语义 | `evidence_columns` |
| --- | --- |
| 注册备案号/批准文号（详情） | `catalog_name`, `registration_no`, `consumable_enterprise`, `specification`, `model`, `spec_model_count`, `payment_category`, `copay_ratio`, `max_limit_text`, `material`, `feature`, `medical_generic_name`, `policy_no`, `registrant_name`, `valid_from`, `valid_to`, `source_record_id` |
| 目录详情（`get_record_detail`） | 同上详情字段集：`catalog_name`, `single_product_name`, `registration_no`, `consumable_enterprise`, `registrant_name`, `specification`, `model`, `spec_model_count`, `payment_category`, `copay_ratio`, `max_limit_text`, `material`, `feature`, `medical_generic_name`, `policy_no`, `valid_from`, `valid_to`, `notes`, `source_record_id` |
| 药品/耗材企业（列表） | `catalog_code`, `catalog_name`, `manufacturer` or `consumable_enterprise`, `registration_no`, `specification`, `payment_category`, `max_limit_text`, `valid_from`, `source_record_id` |
| 耗材分类（列表） | `catalog_name`, `category_level_1`, `category_level_2`, `category_level_3`, `medical_generic_name`, `payment_category`, `max_limit_text`, `source_record_id` |
| 支付类别/限额/价格（列表） | `catalog_name`, asked field, `source_record_id`; prices also require `price_semantics` |
| 有效期/政策 | `catalog_name`, `valid_from`, `valid_to` or `policy_no`, `source_record_id` |

注意事项：
- **详情类查询必须一次返回完整字段集**（`get_by_registration_no`、`get_record_detail` 已按此实现）。不要把支付类别、限额、材质等本已存在的数据留到"再查一次"——它们与主查询同一条记录，直接展示。
- **规格/型号可能为空**：源目录主表未提供规格型号明细（只有 `spec_model_count` 计数）。若 `specification`/`model` 为空而 `spec_model_count` 非空，如实报告"有 N 个规格型号但源目录未提供明细"，不得编造规格型号值。
- 若 `source_record_id` 不可访问，必须报告其不可用，绝不编造该字段或其值。

## 查询结果展示（Reference）

查询执行后，`envelope`（`build_evidence_envelope` 产物）包含 `result`（columns / column_labels / rows / truncated）与 `execution_path`。生成回答时遵循：

- **按目录域查对字段，不混用**：
  - **药品（DRUG）**结果字段应含 `catalog_name`、`registration_no`（显示"批准文号"）、`manufacturer`、`specification`、`unit`、`dosage_form`、`payment_category`、`max_price_text`+`price_semantics`（最高价格）、`copay_ratio`、`valid_from`/`valid_to`、`source_record_id`。**不要把** `max_limit_text`、`medical_generic_name`、`policy_no`、`model`、`spec_model_count`、`material`、`feature` 等**耗材独有字段**用于药品——它们在 DRUG 域恒为空，会让表格出现一整列"-"。
  - **耗材（CONSUMABLE）**结果字段应含
  - **服务项目（SERVICE）**结果字段应含 `catalog_code`、`catalog_name`、`payment_category`、`copay_ratio`（自付比例）、`resident_copay_ratio`（居民自付比例）、`retiree_copay_ratio`、`retiree_max_limit_text`、`provincial_first/second_tier_max_limit_text`（省级一/二档最高限额）、`city_first/second/third_tier_max_limit_text`（市级）、`county_first/second/third_tier_max_limit_text`（县区）、`township_max_limit_text`（乡级最高限额）、`policy_no`、`registration_no`、`valid_from`/`valid_to`、`notes`、`source_record_id`。服务项目“价格”= 多档最高限额+首付比例，**没有 `max_price_text`**——查价格不要用 `max_price_text`（SERVICE 域恒为空）。 `catalog_name`、`registration_no`（显示"注册备案号"）、`consumable_enterprise`、`registrant_name`、`specification`、`model`、`spec_model_count`、`material`、`feature`、`payment_category`、`max_limit_text`/`retiree_max_limit_text`（最高限额）、`medical_generic_name`、`policy_no`、`management_category`、分类、`valid_from`/`valid_to`、`source_record_id`。耗材不含 `manufacturer`、`max_price_text`。
- **正文只给结论与关键数据，明细交给平台证据表格**：不要在正文 Markdown 重复列数据行表格（下方已有完整表格）。仅当结果 ≤3 行且用户明确要求时才在正文逐条列举。
- **回答顺序：先展示查询信息与数据，最后（平台表格展示完成后）再做说明与提示**：(1) 一句话结论（中性，不写"同名多条/前一批/已截断/见平台表格"）→ (2) 关键查询信息摘要（目录领域、名称、批准文号/注册备案号、生产企业、规格、支付类别、最高价格/最高限额、有效期等返回值），**摘要末尾单独一行写占位符 `[[data-table]]`**（平台证据表格将插入于此）→ (3) 占位符之后才写「说明」块（同名多记录提示、完整清单见平台证据表格、哪些字段未返回/为空、筛选口径/告警）→ (4) 最后放「后续可选项」（按批准文号/生产企业/规格/价格筛选、查某条完整详情），仅当用户目标自然延伸。不要在数据摘要后立即堆砌解释、同名单候选枚举或多选题——它们都在 `[[data-table]]` 之后。
- **空值诚实**：某字段确实无值（如药品最高限额、耗材 max_price）就如实说为空或该字段不适用，绝不编造；结果字段缺失时如实说明。
- 结果多行（>50）时 `truncated=true`，正文要说明"已返回部分、完整清单见平台证据表格"，不得把截断当"全部"。

## 语义依据与知识图谱（Reference）

「语义依据」面板包含**两条互补信息**，不要混淆，也**不得把图谱当作数据库事实**：

1. **执行链路（`execution_path`）**——回答"这条查询是怎么一步步执行的"，通常分几个阶段：
   `Agent 语义分析`（识别意图/命中的语义词与字段）→ `Wren 语义层（MDL）`（命中业务模型 `medical_catalog`、查询字段数）→ `PostgreSQL 数据源`（读取物理视图，如 `medical_catalog_consumables`/`medical_catalog_drugs`）→ `Neo4j 语义图谱`（来源记录数、图谱节点数、图谱版本）→ `查询结果`（行数、来源记录数、是否截断）。
   用户想看"查询思路"时，优先依托 `execution_path` 讲链路，而不是罗列结果节点。

2. **结果子图（`evidence.nodes`/`edges`）**——回答"结果产品的来龙去脉"，按结果行的 `catalog_code` 从 Neo4j 投影出的关联子图。常见节点类型：
   `product`（产品）、`organization`（生产企业）、`catalog_record`（原始目录记录）、`source_file`（来源工作簿）、`import_batch`（导入批次）、`registration`（注册备案/批准文号）、`base`（基础耗材）、`concept`（映射概念）；边类型 `business`（业务关系）、`provenance`（来源追溯）、`semantic`（语义关系）、`query`（查询返回）。

原则：
- **图谱用于追溯来源与语义关系，不保存业务事实**；具体数值、名称、明细一律以 Wren 结果行的值为准。不得把"图中某节点存在"当作该目录项的查询结果。
- 图谱 = 结果行的来源路径（产品 → 企业 → 原始记录 → 来源文件 → 导入批次），不是独立于结果的另一套答案。
- 用户嫌"图密密麻麻"时，返回**执行链路**为主线（清晰展示查了什么、从哪查、命中哪个模型与视图），结果子图作为可展开的来源追溯。

## MDL 结构展示（Reference）

「MDL 结构」面板展示本次查询命中的语义模型、字段与来源，辅助用户理解数据口径：

- **模型**：`medical_catalog`（河南医保医疗目录统一语义模型，覆盖药品/耗材/服务/诊疗四个域）。可用中文名"医疗目录"面向用户。
- **字段**：只展示本次查询命中的、有业务含义的已发布字段，并给出中文可读名（如 `payment_category`=医保支付类别、`max_limit_text`=最高限额、`max_price_text`=最高价格、`source_record_id`=原始来源记录）。未映射的英文裸字段名不用"业务字段"占位，宁可省略或如实说明含义未知。
- **来源（provenance）**：来自 `semantic_context.provenance`，说明字段的来源关系/来源 Excel 列（如"二级分类 ← 耗材映射库·二级分类"）。按目录域区分：药品显示"批准文号"、耗材显示"注册备案号"。
- **图谱版本**：`semantic_context.graph_version` 用于说明语义图谱的数据版本；若语义图不可用（`unavailable`），如实说明"语义图当前不可用，但不影响只读查询结果"，不得把不可用当成查询失败。

## 输出契约

解析结果必须是以下结构（JSON），供智能体据此选择工具或 SQL：

```json
{
  "status": "resolved" | "needs_clarification",
  "catalog_domain": "CONSUMABLE",
  "intent": "get_by_registration_no",
  "recommended_template": "medical_catalog.get_by_registration_no",
  "params": {"registration_no": "示例注册备案号"},
  "published_columns": ["catalog_name", "registration_no", "consumable_enterprise", "payment_category", "max_limit_text", "material", "feature", "medical_generic_name", "policy_no", "specification", "model", "spec_model_count", "source_record_id"],
  "evidence_columns": ["catalog_name", "registration_no", "consumable_enterprise", "payment_category", "max_limit_text", "material", "feature", "medical_generic_name", "policy_no", "specification", "model", "spec_model_count", "source_record_id"],
  "constraints": []
}
```

- `status = resolved` 且已给出 `published_columns`（必须都来自白名单）时，智能体按此执行；`evidence_columns` 必须是 `published_columns` 的子集。`status = needs_clarification` 时，输出澄清问题，不执行。

## 反面与正面样例

- ❌ 用户问"乙类耗材有哪些"时，把列写成 `WHERE insurance_category = '乙类'` —— 该列不存在，会被预检拦截。
- ✅ 同一问题应解析为 `catalog_domain='CONSUMABLE' AND payment_category='乙类'`，优先走 `list_records_by_payment_category`。
- ❌ 把"最高限额"当成"最高价格"或数值比较。
- ✅ `max_limit_text` 只用等值/存在性判断，保留原始文本并说明是限额而非真实价格；价格类才用 `max_price_text` + `price_semantics`。
- ❌ 药品查询把耗材字段（`max_limit_text`/`medical_generic_name`/`model`）拼进 SQL，导致结果出现整列"-"，或漏掉药品真正的"最高价格" `max_price_text`。
- ✅ 药品查询返回药品字段（含 `max_price_text`+`price_semantics`）；耗材查询返回耗材字段（含 `max_limit_text`）；按目录域对号入座。
- ❌ 把图谱中某节点存在当成查询结论（"图中显示了该产品所以它存在"）。
- ✅ 图谱只用于追溯来源与语义关系；结论与数值一律来自 Wren 结果行。

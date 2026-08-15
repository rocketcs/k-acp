---
name: medical-catalog-question-semantics
description: 医疗目录问数智能体的「问题语义解析」技能。用于把用户的自然语言问数问题解析为 Wren 语义层可用的目标（目录域、已发布字段、查询模板、预检要求），并且只使用已发布字段、绝不杜撰列名。当用户问题涉及医疗目录（药品/耗材/医疗服务项目/诊疗项目）的名称、支付类别（甲类/乙类）、最高限额、价格、企业/厂家、注册备案号、有效期、分类等语义，而智能体需要确定到底该查询哪个 Wren 字段和用哪个模板时执行。触发词示例：医保支付类别、乙类、甲类、支付类别、最高限额、耗材、医保通用名、中选、注册备案号。
---

# 医疗目录问题语义解析

本技能在把用户的问题交给 Wren 数据查询之前执行：先把自然语言问题**映射到已发布的语义目标**，避免将用户措辞直接变成 SQL 列名，从而杜绝 `insurance_category` 这类不存在字段的错误。

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
| 最高价格 | `max_price_text` + **必须** `price_semantics` | 原始文本，须带价格语义 |
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
6. **无法落点**：当用户措辞无法确定映射到某个已发布字段，或一个词对应多个列且影响口径时，输出一个简洁澄清问题，不得臆造列名强行查询。

## 输出契约

解析结果必须是以下结构（JSON），供智能体据此选择工具或 SQL：

```json
{
  "status": "resolved" | "needs_clarification",
  "catalog_domain": "CONSUMABLE",
  "intent": "list_by_payment_category",
  "recommended_template": "medical_catalog.list_records_by_payment_category",
  "params": {"catalog_domain": "CONSUMABLE", "payment_category": "乙类"},
  "published_columns": ["payment_category", "catalog_code", "catalog_name", "source_record_id"],
  "constraints": ["catalog_domain = 'CONSUMABLE'"]
}
```

- `status = resolved` 且已给出 `published_columns`（必须都来自白名单）时，智能体按此执行；`status = needs_clarification` 时，输出澄清问题，不执行。

## 反面与正面样例

- ❌ 用户问"乙类耗材有哪些"时，把列写成 `WHERE insurance_category = '乙类'` —— 该列不存在，会被预检拦截。
- ✅ 同一问题应解析为 `catalog_domain='CONSUMABLE' AND payment_category='乙类'`，优先走 `list_records_by_payment_category`。
- ❌ 把"最高限额"当成"最高价格"或数值比较。
- ✅ `max_limit_text` 只用等值/存在性判断，保留原始文本并说明是限额而非真实价格；价格类才用 `max_price_text` + `price_semantics`。

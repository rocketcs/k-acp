# 检索、分页、去重与分层

## 请求构造

所有请求共享 `hard_filters`、`exclude_keywords` 和排序边界。高级查询金额字段固定使用 `min_money`、`max_money`。

### 第一轮：原词精确

- 优先把完整 `subject` 放入主 `keywords`，匹配 `title`、`sm`。
- 用户原问题确实包含多个独立必选概念时，第一组放主关键词，其余组放 `keyword_groups`。
- 不加入扩展词。强命中候选初始为 A。

### 第二轮：受控扩展

- 每个 required 概念组使用 `original_terms + expanded_terms`，组内 OR、组间 AND。
- 保持 `title`、`sm`；禁止把所有词压成一个 OR 列表。
- 覆盖全部 required 组的新增候选初始为 B。

### 第三轮：全文补漏

- 词与第二轮相同，只把业务词匹配字段切换为 `fulltext`。
- 仅全文命中或证据不完整的候选初始为 C。
- 不把全文正文传出检索节点，只保留命中片段的短证据。

### 第四轮：最小纠偏

仅当下列任一条件成立才执行：

- 合并后零结果；
- A+B 少于 3 且 C 中存在覆盖全部 required 组的候选；
- 前 20 条中未覆盖完整 required 概念组的比例大于 30%；
- 某轮或某页失败，需要重试失败范围。

每次只能做一项：放宽一个过窄扩展词、加入一个明确上下位词、加入一个高频噪声排除词、纠正一个匹配字段。必须返回 `correction_reason` 和 `changed_field`；地区、日期、金额、主体角色和明确阶段永不改变。

## 完整分页

- `page_size=50`，从 `page=1` 开始。
- 每页读取 `total`、当前页和返回条数；累计条数达到 `total` 或接口明确无下一页时结束。
- 最大 `page=100`。声明总数超过可遍历上限时设置 `is_complete=false`、`incomplete_reason=API_PAGE_LIMIT`。
- 任一页失败，记录 `{round,page,error_kind}`，保留已取结果，并设置 `is_complete=false`。
- 数量口径必须同时输出 `round_reported_total`、`unique_loaded_count`、`displayed_count`。只有 `is_complete=true` 时才能称“共找到 N 条”，否则写“已找到至少 N 条”。

## 标准化

每条 `TenderRecordV1` 至少包含：

```json
{
  "record_key": "bid:12345678",
  "lifecycle_key": "project:normalized-purchaser:normalized-project-code",
  "bid_id": "12345678",
  "uniq_key": "",
  "title": "项目标题",
  "caller_name": "采购单位",
  "province": "广东",
  "city": "深圳",
  "money_wan": 100,
  "pub_time": "2026-07-20",
  "deadline": null,
  "bid_process": 4,
  "aggregate_url": "聚合页",
  "source_url": null,
  "tier": "A",
  "match_evidence": ["标题命中完整短语"],
  "rounds": [1],
  "sort_key": "0|99999999|20260720|bid:12345678"
}
```

## 去重与生命周期

- 同公告唯一键依次使用 `bid_id`、`uniq_key`、规范化聚合 URL、`title+caller+pub_date+process` 指纹。
- 同一公告跨轮重复时合并字段、轮次和证据；优先保留非空、来源更明确的值。
- 生命周期只建立 `lifecycle_key` 关联，不删除采购意向、招标、变更、中标、合同等不同公告。

## A/B/C 判定

- A 严格匹配：硬条件全部满足，且标题/标的物命中完整原短语，或每个 required 组均有原词证据。
- B 高相关：硬条件全部满足，且每个 required 组都有原词或受控扩展词证据。
- C 可能相关：硬条件满足但只有全文证据、缺少一个非硬概念证据，或截止状态待核实。
- 违反任何硬条件的记录剔除，不得用 C 层兜底。
- 用户要求未截止时，没有可靠截止证据的记录进入独立 `needs_verification`，不得混入 A/B。

## 稳定排序

顺序为 tier A、B、C；同层先截止更紧迫且未过期，再发布时间倒序，最后按 `record_key` 升序。`sort_key` 在首次搜索后冻结并写入 continuationState，继续时不重新评分。

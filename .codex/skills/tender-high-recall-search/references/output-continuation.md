# 输出投影与 ContinuationStateV1

## 回答模型输入

只允许传入：

- 规范化后的最终 QueryPlan 摘要；
- 当前批次最多 20 条 A/B 主结果；
- 当前批次对应的 C 层结果，仍计入 20 条批次上限；
- 按 `record_key` 合并后的链接字段；
- 数量、完整性、失败范围、纠偏说明和下一批状态。

禁止传入原始 API 响应、全部候选、公告全文、HTML、详情页正文、认证头、工具日志或模型推理。

## 链接投影

每条展示记录必须先生成：

```json
{
  "record_key": "bid:12345678",
  "bid_id": "12345678",
  "uniq_key": "",
  "title": "项目标题",
  "aggregate_url": "https://www.zhiliaobiaoxun.com/content/12345678/b1",
  "source_url": null
}
```

整批一次性交给 `resolve_tender_source_urls_v2`。输出按 `record_key` 合并：

- `link_type=SOURCE`：项目名称链接到 `display_url`，来源列写“原文”。
- `link_type=NONE`：标题为普通文本并展示 `source_status`。
- 输入与输出键集合不一致时 `link_resolution_complete=false`；缺失项只使用已校验的聚合页回退。

## ContinuationStateV1

```json
{
  "state_version": "tender-continuation-v1",
  "query_plan_version": "tender-query-plan-v1",
  "query_plan": {},
  "stable_keys": ["bid:1", "bid:2"],
  "position": 20,
  "page_size": 20,
  "round_pages": {"exact": 3, "expanded": 4, "fulltext": 2, "correction": 0},
  "query_end_boundary": "2026-07-22T23:59:59+08:00",
  "is_complete": true,
  "failures": [],
  "link_states": {"bid:1": {"link_type": "SOURCE", "display_url": "https://example.gov/1"}}
}
```

状态中不得包含认证密钥、认证头、公告全文、HTML、原始响应或内部推理。`position` 指向下一条未展示记录。下一批优先按 `stable_keys[position:position+20]` 获取；若必须重新请求列表页，沿用冻结的 `query_end_boundary` 并排除已展示键。

## 用户输出

先用一句话说明检索口径和完整性，再输出 A/B 主结果表：

| # | 项目名称 | 采购单位 | 地区 | 预算（万） | 发布时间 | 报名截止 | 阶段 | 匹配理由 | 来源 |
|---|---|---|---|---:|---|---|---|---|---|

- A、B 可以分别加小标题，但保持连续稳定序号。
- `link_type=SOURCE` 时，项目名称必须输出为该记录的 `title_markdown`，不可把链接放在“来源”列、不可改成聚合页链接。
- C 使用“可能相关（需展开核实）”独立区，不进入主结果表头部排序。
- 缺失字段写“未获取”或“需核实”，不得编造。
- 结果超过当前批次时写明已展示、剩余，并在表格后输出下一步选择卡片，不再要求用户手输“继续查看下一批”。
- `is_complete=false` 时写“已找到至少 N 条”，并说明具体的不完整原因。
- 不展示查询参数、Skill 名称、工作流节点、工具调用或内部评分。

## 下一步选择卡片

回答正文结束后追加且只追加一个合法的 UIP 代码块。它是用户可点击的业务入口，代码块之外不要解释协议、JSON 或提交格式。

- `remaining_count > 0` 时使用 4 个选项：继续查看下一批、只看仍可报名的项目、筛选最值得跟进的项目、调整地区、时间或阶段。
- `remaining_count = 0` 时使用后 3 个选项。
- `value` 使用稳定英文标识，用户可见 `label` 必须是完整中文业务动作。
- 卡片不携带 `continuationState`；连续状态由外层对话保存并在用户点击后传回。
- `id` 固定为 `tender-followups`，同一条结果只生成一次卡片。

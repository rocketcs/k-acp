# QueryPlanV1

## 输出契约

查询计划器只输出 JSON，不调用检索接口。固定结构如下：

```json
{
  "query_plan_version": "tender-query-plan-v1",
  "original_question": "请查询广东近一个月统一运维全部阶段的项目",
  "subject": "统一运维",
  "hard_filters": {
    "provinces": ["广东"],
    "cities": [],
    "counties": [],
    "begin_date": "2026-06-22",
    "end_date": "2026-07-22",
    "bid_process": [1, 2, 4, 7, 8],
    "min_money": null,
    "max_money": null,
    "caller_names": [],
    "winner_names": [],
    "deadline_status": "all"
  },
  "concept_groups": [
    {
      "name": "服务组织方式",
      "required": true,
      "original_terms": ["统一"],
      "expanded_terms": ["一体化", "综合"]
    },
    {
      "name": "服务动作",
      "required": true,
      "original_terms": ["运维"],
      "expanded_terms": ["运行维护", "运营维护"]
    }
  ],
  "exclude_keywords": [],
  "inherited_fields": [],
  "assumptions": [],
  "correction_allowed": true
}
```

## 解析规则

- 先按北京时间把相对日期换成绝对 `YYYY-MM-DD`。结束日期包含当天。
- 用户说“全部阶段”时使用 `[1,2,4,7,8]`。只有明确提到变更、候选人、验收、废标或异常阶段时才加入对应的 `[5,6,9,10]`。
- “在招/正在招标”只设 `bid_process=[4]`，`deadline_status=all`。
- “未截止/还能报名”设 `deadline_status=open`；只有列表、详情或公告中有明确截止日期的记录才能进入 A/B，无法核实者进入待核实区。
- 公司作为采购方时写入 `caller_names`，作为中标方时写入 `winner_names`，不得塞进普通全文关键词。
- 完整业务短语放在 `subject`。概念组必须彼此独立，禁止创建“统一运维”和“运维”两个伪 AND 组。
- 扩展词只来自受控业务词、当前企业画像或用户确认词。禁止单独加入“平台”“系统”“服务”等宽泛词。
- 只有一个缺失条件会实质改变范围时才追问一次；其他缺失项使用安全默认值并写入 `assumptions`。

## 计划校验

- `query_plan_version` 必须精确为 `tender-query-plan-v1`。
- `subject` 非空；至少一个 required 概念组或完整主题短语非空。
- 日期必须合法且 `begin_date <= end_date`。
- `bid_process` 只能包含 `1,2,4,5,6,7,8,9,10`。
- 金额为非负数，且最小金额不得大于最大金额。
- 地区、公司、排除词去空、去重但保持原顺序。
- 每个 required 组必须有至少一个 `original_terms`；扩展词不能重复原词。

## 连续追问合并

- “改成深圳”：只覆盖 `cities`；若深圳已确定属于广东则保留 `provinces=[广东]`。
- “只看在招”：只覆盖 `bid_process=[4]`，不改变日期、地区、主题和排除词。
- “排除维保”：向 `exclude_keywords` 追加“维保”，不清空原条件。
- “全部阶段”：只覆盖阶段为 `[1,2,4,7,8]`。
- 除“继续”外，合并后生成新计划并在 `inherited_fields` 列出沿用字段。
- “继续”：不生成新计划；校验 `ContinuationStateV1` 后直接按 `stable_keys[position:]` 取下一批。

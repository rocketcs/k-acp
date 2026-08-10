# Source URL Resolution

本文件描述在输出标讯结果前，如何使用 `resolve_tender_source_urls_v2` 解析原始公告链接。若运行环境绑定的工具名是 `resolve_tender_source_urls` 或 `resolve_tender_source_url`，按实际工具名调用同一来源解析能力。

## 强制触发场景

只要最终回答会展示任何标讯，就必须先调用来源解析工具：

- 标讯列表、项目卡片、商机列表
- 单个项目详情、可行性分析、跟进建议
- 中标/合同/采购意向结果
- 公司、采购方、供应商、竞对分析中展示的具体项目样例

不得因为搜索结果已有 `url`、`aggregate_url`、`source_url` 或知了聚合页而跳过解析。聚合页 URL 不能直接冒充原始公告链接。

## 调用时机

1. 先通过 `http_request` 获取真实项目数据。
2. 从本轮准备展示的项目中选取 1 至 50 条；标讯检索返回 30 至 50 条时，必须整批传入，不得只解析前 10 或 20 条。
3. 调用来源解析工具。
4. 将解析结果按 `bid_id` 或标题与原项目合并。
5. 再输出最终项目表格、项目卡片或项目详情。

如果 `http_request` 没有返回项目，或本轮回答不展示任何具体项目，则不需要调用来源解析工具。

## 入参规则

每个 item 尽量携带以下字段：

| 字段 | 说明 |
|---|---|
| `bid_id` | 标讯 ID；优先使用供应商返回的 `bid_id` |
| `title` | 项目标题；必须尽量提供 |
| `source_url` | 供应商已返回的疑似来源 URL；没有则传空字符串或省略 |
| `aggregate_url` | 知了聚合页 URL；可来自 `url`、`aggregate_url` 或同义字段 |
| `url` | 如果只有一个 URL 字段，也可原样携带 |

标准调用：

```json
{
  "items": [
    {
      "bid_id": "123456",
      "title": "示例项目",
      "source_url": "",
      "aggregate_url": "https://www.zhiliaobiaoxun.com/content/123456/b1"
    }
  ]
}
```

## 出参使用规则

优先读取解析工具返回的以下字段，字段名以实际返回为准：

| 字段 | 使用方式 |
|---|---|
| `display_url` | 最终展示 URL，优先使用 |
| `original_url` | 原始公告 URL；当没有 `display_url` 时可使用 |
| `source_url` | 来源 URL；当没有 `display_url` / `original_url` 时可使用 |
| `link_type` | 判断链接类型 |
| `source_status` / `status` | 判断验证状态 |

展示规则：

- `display_url` 非空且 `link_type` 为 `SOURCE` 或 `SOURCE_UNVERIFIED`：标题写成 `[项目标题](display_url)`。
- `source_status` 为 `VERIFIED`：来源列写“已验证原始公告链接”。
- `source_status` 为 `EXTRACTED`、`EXTRACTED_CLIENT_ROUTE` 或 `EXTRACTED_SOURCE_UNVERIFIED`：来源列写“已提取原始公告链接（待验证）”。
- 解析结果没有可展示的原始 URL 时：标题保持普通文本；来源列写“未解析到原始公告链接”。不得展示 `aggregate_url`、知了详情页、搜索结果页或任何聚合页作为兜底。

不得把 `aggregate_url`、知了详情页、搜索结果页或中转页称为原始公告链接。

## 失败处理

来源解析失败不应丢弃真实项目数据，但必须如实标注链接状态：

- 工具调用失败：来源列写“来源解析失败，未验证原始公告链接”。
- 单条项目解析失败：该条来源列写“未解析到原始公告链接”。
- 部分成功、部分失败：成功项按解析结果展示，失败项按失败规则展示。

解析失败后不得编造原始公告 URL，也不得用标题、采购方或地区推测链接。

## 输出表建议

展示项目列表时，建议至少包含：

| 列 | 内容 |
|---|---|
| 项目 | 带 `display_url` 的 Markdown 链接；没有来源 URL 时用普通标题 |
| 阶段 | 公告阶段或 `bid_process` 映射 |
| 发布时间 | `pub_time` 或同义字段 |
| 地区 | 省/市 |
| 采购方 | `caller_name` 或同义字段 |
| 金额 | `money_wan` / `money`，没有则写“未获取” |
| 来源 | 已验证/待验证/未解析 |

在项目详情或跟进建议中，也必须保留来源链接状态，不能只在表格中体现。

# `resolve_tender_source_urls` 手工配置指南

用途：从知了标讯页的 HTML 提取招标方/交易平台的真实原文链接。一次最多处理 20 条；不探测原文站点可达性。

## 1. 新建自定义工具

进入 `http://localhost:23080/web/#/tool`，先选择 **自定义**，清空分类和搜索条件，然后点击“添加新工具”。

按下表填写：

| 字段 | 值 |
| --- | --- |
| 标签/分类 | `招投标` |
| 名称 | `resolve_tender_source_urls｜招标原文链接批量解析` |
| 编号 | `resolve_tender_source_urls` |
| 描述 | `批量处理最多20条知了标讯结果：逐条读取知了标讯页 HTML，从“查看原文”链接或页面内 sourceUrl 提取真实原文地址；不调用详情 API 或原文站点可达性探测。` |
| 类型 | 自定义（CUSTOM） |
| 语言 | Java |
| 版本号 | `2.3.0` |
| 是否需要确认 | 否 |

## 2. 配置输入参数

只新增一个输入参数，顺序必须保持第一个：

| 名称 | 类型 | 必填 | 默认值 | 描述 |
| --- | --- | --- | --- | --- |
| `items` | `array` | 是 | `[]` | `最多20条标讯记录。每项包含bid_id、title、url或aggregate_url，可选source_url` |

输出 Schema 留空即可。

## 3. 粘贴 Java 实现

打开同目录的 [TenderSourceUrlResolverTool.java](TenderSourceUrlResolverTool.java)，复制全部内容到“代码”编辑器，再提交并启用工具。

保存成功后，在工具卡片的“查看”中核对：

- 工具编号为 `resolve_tender_source_urls`
- 类型为“自定义”
- 状态为“已启用”
- 输入参数只有 `items`（array、必填）

## 4. 关联到技能和智能体

工具需要至少通过下面其中一条路径注册到“商业标书智能体”：

1. 推荐：进入“技能包管理” → `tender-search` → 卡片菜单“关联工具” → 搜索 `resolve_tender_source_urls` → 添加并保存。确认 `tender-search` 本身已启用，且“商业标书智能体”已关联该技能包。
2. 或者：进入“智能体管理” → “商业标书智能体” → “工具与能力” → “工具集” → 勾选该工具并保存。

不需要两种方式同时配置；推荐第一种，工具会随 `tender-search` 技能自动注册。

## 5. 最小验证

在智能体中让它调用工具时，传入：

```json
{
  "items": [
    {
      "bid_id": "demo-1",
      "title": "测试项目",
      "aggregate_url": "https://www.zhiliaobiaoxun.com/content/599180408/b1?sk=4BE978A36F5AFC1122788E8E9C544E599BBC3506&from=skill"
    }
  ]
}
```

预期：结果中的 `items[0]` 带有 `status`、`original_url`、`source_domain`、`method`。当 `status = EXTRACTED` 时，前端可将项目名称链接到 `original_url`。

状态说明：

| 状态 | 含义 |
| --- | --- |
| `EXTRACTED` | 已从知了页面提取到原文 URL；未探测原文站点可达性 |
| `NOT_FOUND` | 聚合页中没有找到原文地址 |
| `UNREACHABLE` | 知了聚合页暂时不可访问 |
| `INVALID_INPUT` | 传入地址不是有效的公开 HTTP/HTTPS 链接 |

## 常见问题

- 在工具页搜索 `resolve_tender_source_urls` 没有结果：搜索框只检索“名称”，因此名称必须包含此编号；本指南中的名称已包含。
- 只看到“内置”工具：切换顶部类型筛选为“自定义”或“全部”。
- 工具能看到但智能体未调用：先确认工具已启用，再确认它已关联 `tender-search` 或直接勾选进智能体的工具集。
- 超过 20 条：调用方拆成每批最多 20 条；“继续”时处理下一批，不要一次传入所有记录。

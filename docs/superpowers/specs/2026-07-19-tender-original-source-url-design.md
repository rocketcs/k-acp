# 招标原文链接解析设计

## 背景

`tender-search` 的搜索结果目前主要返回知了标讯聚合页 URL。实测知了标讯页面的服务端数据中包含 `sourceUrl`，可还原为公告最初发布平台的地址；但 `search_bids` 和 `get_bid_detail` 当前未稳定暴露该字段。

本设计为商业标书智能体增加确定性的批量原文链接解析能力。每次搜索最多展示 20 条，并确保这 20 条都经过原文地址处理。系统只使用真实来源，不把聚合页伪装成原文，也不猜测已删除或无法确认的链接。

## 目标

- 每次搜索最多返回 20 条记录，用户输入“继续”后处理下一批 20 条。
- 保留用户指定的关键词、地区和时间范围，不额外限制查询日期。
- 搜索接口存在真实 `source_url` 时直接使用，不重复请求页面。
- 缺少 `source_url` 时批量解析知了标讯页面中的 `sourceUrl`。
- 逐条返回解析状态，单条失败不得中断整批。
- 项目名称只链接已确认的原文地址。

## 非目标

- 不保证找回已被原站删除、从未公开或受登录权限限制的公告。
- 第一版不接入搜索引擎，也不根据标题猜测原文地址。
- 不修改前端 Markdown 表格样式。
- 不改变商机筛选和单项目分析规则。

## 方案选择

采用“批量解析 Tool + `tender-search` 调用规则”。

仅修改 Skill 会把最多 20 个完整网页交给模型，带来高 token 消耗和不稳定解析；仅新增 Tool 又不能保证模型每次调用。两者组合后，Tool 负责确定性处理，Skill 负责调用时机和输出规则。

长期可推动上游搜索接口直接返回 `source_url`。Tool 保留为兼容旧数据和缺失字段的兜底能力。

## Tool 设计

工具编号为 `resolve_tender_source_urls`，使用 K-ACP 在线 `CUSTOM Java Tool` 机制实现，不要求重建服务。

### 输入

```json
{
  "items": [
    {
      "bid_id": "599180408",
      "title": "项目名称",
      "aggregate_url": "https://www.zhiliaobiaoxun.com/content/...",
      "source_url": null
    }
  ]
}
```

- `items` 必填，最多 20 条。
- `bid_id`、`title` 用于结果关联和审计。
- `aggregate_url` 只允许知了标讯 `https://www.zhiliaobiaoxun.com/content/` 地址。
- `source_url` 可选；存在时优先校验并使用。

### 处理顺序

1. 校验批次大小、字段和 URL 协议。
2. 对已有 `source_url` 进行安全校验，不再解析聚合页。
3. 对缺少 `source_url` 的记录并发获取聚合页，提取 `sourceUrl`。
4. 解码 `\u002F` 等 JSON Unicode 转义。
5. 只接受 `http` 或 `https` 外部地址，拒绝本机、内网、保留地址和知了标讯自身地址。
6. 使用 `HEAD`，必要时降级为轻量 `GET` 验证原文地址。
7. 保持输入顺序，返回与输入数量完全相同的结果。

工具内部对同一批次的重复聚合页去重；并发数设为 5，单请求超时 8 秒，网络错误最多重试 1 次，避免 20 条串行处理造成长时间等待。

### 输出

```json
{
  "total": 2,
  "resolved": 1,
  "items": [
    {
      "bid_id": "599180408",
      "original_url": "https://ecsg.com.cn/cms/NoticeDetail.html?...",
      "source_domain": "ecsg.com.cn",
      "status": "VERIFIED",
      "method": "PAGE_SOURCE_URL",
      "message": ""
    },
    {
      "bid_id": "599180409",
      "original_url": null,
      "source_domain": null,
      "status": "SOURCE_DELETED",
      "method": "PAGE_SOURCE_URL",
      "message": "原文已删除"
    }
  ]
}
```

状态固定为：

- `VERIFIED`：原文地址已获得且可访问。
- `SOURCE_DELETED`：原站明确返回 404 或 410。
- `NOT_FOUND`：未发现真实原文地址。
- `UNREACHABLE`：超时、连接失败、验证码或权限限制，无法确认是否删除。
- `INVALID_INPUT`：输入不合法。

## `tender-search` 集成规则

1. 搜索接口使用 `page_size: 20`，单次只准备展示当前页的 20 条。
2. 若结果总数超过 20，显示总数、当前已返回数和剩余数；“继续”从下一页开始。
3. 在生成表格前，将当前批次一次性交给 `resolve_tender_source_urls`，不得逐条调用。
4. `VERIFIED` 项目的项目名称链接 `original_url`。
5. 其他状态的项目名称使用普通文本，并在链接状态中写“原文已删除”“未获取”或“暂不可访问”。
6. 不把 `aggregate_url` 输出成原文链接，也不自行拼接或猜测地址。

新 Tool 绑定到商业标书智能体，并关联到其使用的 `tender-search` 能力。现有 `http_request` 保留，不承担网页正文解析，避免把大段 HTML 送入模型。

## 安全与错误处理

- 聚合页输入使用固定域名和路径前缀白名单。
- 重定向的每一跳都执行公网地址校验，防止 SSRF。
- 限制响应体大小，只读取足够提取页面数据的内容。
- 不返回页面 HTML、Cookie、认证信息或内部异常栈。
- 单条错误转换为结构化状态；只有整个输入无法解析时工具调用才整体失败。

## 验收标准

1. 输入含原生 `source_url` 时不请求聚合页，并返回 `VERIFIED`。
2. 输入知了标讯测试页时，能还原 `https://ecsg.com.cn/...` 原文地址。
3. 输入 20 条时，输出恰好 20 条且顺序一致。
4. 404/410 返回 `SOURCE_DELETED`，不生成替代链接。
5. 缺少 `sourceUrl` 返回 `NOT_FOUND`，不猜测链接。
6. 私网、非 HTTP 协议或非知了聚合页输入被拒绝。
7. 商业标书智能体搜索结果最多展示 20 条，项目名称使用已验证原文链接；“继续”处理下一批。
8. 原有模型、系统提示词模板绑定、其他 Skill、记忆和既有工具绑定保持不变；模板内容只修改每批 20 条和原文解析调用规则。

## 实施范围

设计批准后实施计划只包含：

1. 为解析、解码、安全校验和批量顺序编写失败测试。
2. 创建并注册 `resolve_tender_source_urls` 自定义 Tool。
3. 将 Tool 绑定到商业标书智能体及对应 `tender-search` 能力。
4. 将当前智能体的每批返回上限改为 20，并增加 Tool 强制调用规则。
5. 使用固定样本、删除样本和 20 条批次完成自动测试与真实对话验收。

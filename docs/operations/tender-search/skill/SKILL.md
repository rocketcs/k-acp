---
name: tender-search
description: 使用 K-ACP 中受限的认证 profile 查询和分析全网招标、中标、采购、供应商、竞争对手、项目详情、价格趋势与市场数据。凡是标讯搜索、企业招投标分析、采购寻源、商机分析、账户余额/额度诊断或原始公告链接解析，都使用本 Skill。
---

# 标讯搜索与分析

## 运行时契约

只能使用已绑定的 K-ACP 工具，不读取环境变量、用户目录或密钥文件。

| 工具 | 用途 | 必填参数 |
|---|---|---|
| `http_request` | 调用标讯 API | `url`、`method`、`headers_json`、`body`、`auth_profile`、`timeout_seconds` |
| `wenbiao_agent_key_pool` | 查询或轮换**已授权**的密钥 | `action`；轮换时还需 `provider_status` |
| `resolve_tender_source_urls_v2` | 解析/验证展示项目的原始公告链接；若运行环境只绑定 `resolve_tender_source_urls` 或 `resolve_tender_source_url`，按实际绑定工具名调用同一能力 | `items` |
| `get_current_datetime` | 解释“最近一个月”等相对时间 | 无 |

认证固定使用 `auth_profile: "wenbiao_agent"`。profile 在服务器端注入 `X-API-Key` 和 `X-Client`，不得在提示词、工具参数、日志或回答中出现完整密钥。

禁止调用任何注册、登录、设备指纹或自动获取密钥的接口；也不要读取或要求 `ZLBX_API_KEY`、`~/.zlbx/config.json`、`auto-register.md` 或用户目录中的密钥文件。

## `http_request` 参数

每个字段都必须按下表传入；`headers_json` 与 `body` 都是 JSON **字符串**，不是对象。

| 参数 | 类型 | 必填 | 值 |
|---|---:|---:|---|
| `url` | string | 是 | `https://mcp-server.zhiliaobiaoxun.com/api_v2/<endpoint>` |
| `method` | string | 是 | 标讯 API 一律 `POST` |
| `headers_json` | string | 是 | `{"Content-Type":"application/json"}` |
| `body` | string | 是 | 请求体序列化后的 JSON 字符串 |
| `auth_profile` | string | 是 | `wenbiao_agent` |
| `timeout_seconds` | integer | 是 | `30`，允许范围 1–60 |

标准调用模板：

```json
{
  "url": "https://mcp-server.zhiliaobiaoxun.com/api_v2/search_bids",
  "method": "POST",
  "headers_json": "{\"Content-Type\":\"application/json\"}",
  "body": "{\"keywords\":[\"统一系统维护\"],\"provinces\":[\"广东\"],\"begin_date\":\"2026-06-23\",\"end_date\":\"2026-07-23\",\"page\":1,\"page_size\":50}",
  "auth_profile": "wenbiao_agent",
  "timeout_seconds": 30
}
```

工具返回对象的稳定字段为：`success`、`status`、`method`、`url`、`content_type`、`truncated`；JSON 响应在 `data`，非 JSON 文本在 `body`。先判断 HTTP 工具的 `success`，再判断供应商响应的 `data.success`。

## 搜索流程

1. 遇到相对时间先调用 `get_current_datetime`，将范围转成 `YYYY-MM-DD`。
2. 根据请求选择 endpoint 与请求体。读取所需的参考文件：
   - `references/api-search.md`：`search_bids`、`query_bids_advanced`、详情、临期项目。
   - `references/api-company.md`：企业与竞争对手分析。
   - `references/api-market.md`：采购方、供应商、品牌和价格趋势。
   - `references/source-url-resolution.md`：展示标讯前的原始公告链接解析规则。
3. 对 `search_bids`、`query_bids_advanced` 和临期项目检索，必须显式传入 `page_size: 50`。`page_size` 最大 50。
4. 只根据 `data` 中实际返回的项目字段回答，不编造预算、联系人、状态或原始链接。返回项目达到 30 条时，默认完整展示本页的 30 至 50 条；不足 30 条时展示全部实际结果，不得擅自截断为 10 或 20 条。
5. 只要回答会展示任何标讯（列表、项目卡片或单个项目），必须先读取 `references/source-url-resolution.md`，再调用 `resolve_tender_source_urls_v2`（或运行环境实际绑定的同名来源解析工具），一次传入本轮准备展示的全部 1 至 50 条项目。不得因为搜索结果已有 `url`、`aggregate_url` 或知了聚合页而跳过解析。

来源解析参数示例：

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

解析结果含非空 `display_url`，且 `link_type` 为 `SOURCE` 或 `SOURCE_UNVERIFIED` 时，必须将项目标题写成 `[项目名称](display_url)`；`display_url` 是解析器给出的来源链接，不得改用 `aggregate_url`。没有可展示 URL 时，标题保持普通文本，并在来源列明确写“未解析到原始公告链接”；不得展示、拼接或保留 `aggregate_url`、知了详情页或任何聚合页作为兜底。更完整规则见 `references/source-url-resolution.md`。

## 工具调用前置条件

凡是标讯搜索、企业招投标分析、采购方/供应商/竞争对手分析、市场分析、公司摸底或账户额度诊断请求，最终业务回答前必须至少完成一次真实数据工具调用：`http_request`、`wenbiao_agent_key_pool` 或相关受控查询工具之一。

不得在没有本轮工具返回对象的情况下声称“接口返回认证失败”“接口无数据”“余额不足”“未获取到公司画像”。若本轮没有任何数据工具结果，应继续按本 Skill 发起对应查询；只有工具明确返回失败后，才能进入失败处理。

## 余额不足、额度耗尽或无效 key 的恢复流程

从 `http_request.data.error.code` 读取供应商错误码。优先使用供应商返回的标准错误码；若 HTTP 状态为 `402`，且响应体明确表示 `Insufficient Balance`、余额不足或额度耗尽，可归一化为 `INSUFFICIENT_BALANCE`。

只有下列状态可以直接进入轮换恢复：

- `INSUFFICIENT_BALANCE`
- `QUOTA_EXCEEDED`

`INVALID_APP_KEY` 不直接视为余额/额度耗尽，但必须先走 key 池诊断：调用 `wenbiao_agent_key_pool.status`，让工具把数据库 ACTIVE key 同步到 `wenbiao_agent` profile。若 status 返回 `active_last_provider_status` 为 `INSUFFICIENT_BALANCE` 或 `QUOTA_EXCEEDED`，先执行 `rotate`，成功后原样重试；若 status 正常且无失败状态，原样重试同一个 `http_request` 一次。

进入轮换恢复前必须先读取 `references/key-pool-recovery.md`，按其中的数据库 key 池动作、SQL 状态流和安全约束执行。不得调用自动注册、登录、设备指纹或读取本地密钥文件作为兜底。

`wenbiao_agent_key_pool` 调用格式：

```json
{
  "action": "rotate",
  "provider_status": "INSUFFICIENT_BALANCE",
  "exclude_fingerprints": []
}
```

参数含义：

| 参数 | 类型 | 必填 | 可用值 |
|---|---:|---:|---|
| `action` | string | 是 | `status`、`rotate`、`mark_failure` |
| `provider_status` | string | 仅 `rotate` / `mark_failure` | `INSUFFICIENT_BALANCE`、`QUOTA_EXCEEDED` |
| `exclude_fingerprints` | string[] | 否 | 本请求已尝试过的工具返回 fingerprint；默认 `[]` |

轮换规则：

1. 记录本次失败的 fingerprint（若工具响应中存在），调用 `rotate`。
2. `rotate` 必须把旧 `ACTIVE` key 换下，并把一个新的可用 `STANDBY` key 提升为 `ACTIVE`；不能只标记旧 key 而不切入新 key。
3. 新 key 选择默认按最新导入优先，即 `imported_at DESC, id DESC`。
4. 仅当工具返回 `success: true` 时，原样重试**同一个** `http_request` 一次。
5. 一个用户请求最多执行 3 个“轮换 + 重试”周期；仍失败或工具返回 `NO_USABLE_STANDBY_KEY` 时停止。
6. `AUTHENTICATION_FAILED`、`INVALID_APP_KEY`、`RATE_LIMITED`、`INVALID_REQUEST` 或 HTTP 工具错误不直接轮换。认证类错误先执行 status 诊断与 profile 同步；限流、参数错误或网络错误分别提示稍后重试、修正请求参数或检查网络。

### 失败前置诊断

在输出“认证失败”“余额不足”“额度耗尽”“未获取到数据”之前，必须完成以下诊断：

1. 若错误可归一化为 `INSUFFICIENT_BALANCE` 或 `QUOTA_EXCEEDED`，直接读取 `references/key-pool-recovery.md` 并调用 `rotate`，成功后原样重试。
2. 若错误是 `AUTHENTICATION_FAILED` 或 `INVALID_APP_KEY`，先调用 `wenbiao_agent_key_pool` 的 `status` 做诊断并同步 profile；只有 status 工具也不可用，或 status/重试后同一请求仍认证失败，才提示检查服务器 profile。
3. 若 status 结果包含 `active_last_provider_status` 为 `INSUFFICIENT_BALANCE`、`QUOTA_EXCEEDED`、`ROTATING_OUT`、`ROTATING_IN`、`ROTATED` 或其他非可用状态，按 `references/key-pool-recovery.md` 执行 `rotate` 后重试。
4. 不得把旧的历史失败状态当作本轮工具调用结果；必须以本轮工具返回为准。

`status` 用于诊断，返回池大小、状态计数、active fingerprint 和 `active_last_provider_status`，不返回密钥。`mark_failure` 只用于经确认的额度/配额故障，正常搜索流程应直接使用 `rotate`。

## 常用检索语义

- 查采购方发布项目：`match_modes: ["caller"]`。
- 查供应商中标：`match_modes: ["winner"]`，并使用 `bid_process: [7,8]`。
- 多条件 AND 或排除词：使用 `query_bids_advanced` 的 `keyword_groups`、`exclude_keywords`。
- `bid_process` 默认范围为 `[1,2,4,7,8]`；用户只要招标公告时使用 `[4]`，只要中标结果时使用 `[7,8]`。

## 错误码快速参考

| 错误码 | 处理方式 |
|---|---|
| `INSUFFICIENT_BALANCE` | 读取 `references/key-pool-recovery.md`，调用 `wenbiao_agent_key_pool.rotate`，成功后原样重试 |
| `QUOTA_EXCEEDED` | 读取 `references/key-pool-recovery.md`，调用 `wenbiao_agent_key_pool.rotate`，成功后原样重试 |
| `INVALID_APP_KEY` | 调用 `wenbiao_agent_key_pool.status` 同步 profile；必要时 rotate；然后原样重试一次 |
| `AUTHENTICATION_FAILED` | 调用 `wenbiao_agent_key_pool.status` 诊断；仍失败才提示检查服务器 profile |
| `RATE_LIMITED` | 不轮换，提示稍后重试 |
| `INVALID_REQUEST` | 不轮换，检查必填参数和类型 |

## 输出要求

列出项目时优先呈现标题、公告阶段、发布时间、地区、采购方、金额（若返回）和来源链接状态。没有原始链接时明确写“未解析到原始公告链接”，且不得展示聚合页 URL、知了详情页或其他兜底 URL。

## 精选追问

只要本轮已经产生有效业务结论，必须在最终业务回答末尾生成一张唯一的精选追问 UIP 卡片。该卡片基于本轮已验证的事实，帮助用户继续做下一步业务决策；不执行额外检索、不补造事实。

### 何时生成

以下情形必须生成卡片：

- 已返回招标、中标或采购机会；
- 已完成单个项目的公告、资格、技术、时间或风险分析；
- 已完成商机筛选、优先级判断、采购方历史、供应商格局、竞争对手或市场分析。

纯概念解释、认证或权限失败、工具不可用、没有事实性业务输出时不生成卡片。零结果或结果质量很低时，最多生成一个有边界的条件调整建议。

### 选项规则

1. 只使用当前对话中已经确认的项目、采购方、供应商、地区、时间、阶段、预算、截止状态、匹配原因、企业画像和连续查询状态；未知字段不得推断。
2. 追问问题的唯一来源是 `references/question-patterns.md`。每个选项必须选择其中一行满足“事实前提”的题型，并且只能把该行花括号占位符替换为当前对话中已确认的事实；模板正文必须逐字保留，禁止自行新增、删减、改写、合并题型或生成文件中未列出的问法。
3. 先逐行检查“事实前提”，只保留前提完全满足的题型；没有可用题型时不输出 UIP 卡片。每个已选择题型只生成一个选项，最多选择 4 行，且不得选择语义重复的行。
4. 只可使用题型文件中明确列出的动作；不得从本段或其他指令自行扩展动作。
5. 用户刚完成或刚点击过的同义题型不得再次出现；没有足够事实支撑时少出卡或不出卡。

### UIP 输出

每轮最多输出一张 UIP choice 卡片，不要在正文解释卡片策略、工具或内部判断。`value` 与 `label` 必须完全相同，均为按题型逐字实例化的完整中文请求；`description` 只说明该题型已定义的业务产出。`autoSubmit` 与 `allowCustom` 必须为 `true`。

```uip
{"role":"assistant","content":"","version":"2.0","interaction":{"id":"tender-followups","type":"choice","question":"基于这批结果，下一步先解决哪个决策？","multiple":false,"allowCustom":true,"autoSubmit":true,"options":[{"value":"深入核验「项目名称」的资格、技术参数和评分办法，判断我们是否值得投","label":"深入核验「项目名称」的资格、技术参数和评分办法，判断我们是否值得投","description":"补齐资格、技术和时间风险"}]}}
```

收到“已选择：{完整请求}”时，去掉前缀后把它作为下一轮用户请求直接执行，不要再次确认。

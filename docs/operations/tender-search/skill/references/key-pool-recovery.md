# Wenbiao Key Pool Recovery

本文件只描述余额不足、额度耗尽或无效 key 诊断时的受控恢复流程。不得在回答、日志、工具返回值或 Skill 文本中输出完整 `api_key`。

## 触发条件

仅当 `http_request` 调用标讯 API 后出现以下情况时进入本流程：

- `http_request.data.error.code == "INSUFFICIENT_BALANCE"`
- `http_request.data.error.code == "QUOTA_EXCEEDED"`
- HTTP 状态为 `402`，且响应体明确表示 `Insufficient Balance`、余额不足或额度耗尽；此时归一化为 `INSUFFICIENT_BALANCE`
- `wenbiao_agent_key_pool.status` 返回的 `active_last_provider_status` 表明 active key 已是 `INSUFFICIENT_BALANCE`、`QUOTA_EXCEEDED`、`ROTATING_OUT`、`ROTATING_IN`、`ROTATED` 或其他非可用状态

以下情况不得直接进入 key 轮换：

- `AUTHENTICATION_FAILED` 或 `INVALID_APP_KEY`；这类错误先调用 `wenbiao_agent_key_pool.status` 诊断并同步 profile。若 status 显示 active key 有余额/额度失败状态，再按本流程 rotate；若无失败状态，原样重试同一个请求一次
- `RATE_LIMITED`
- `INVALID_REQUEST`
- HTTP 工具自身失败、网络不可达或超时
- 查询参数错误、接口不存在或响应结构无法解析

## 本次失败的典型根因

如果一次公司摸底或标讯查询只产生聊天回复，没有 `http_request`、`wenbiao_agent_key_pool` 或 workflow 执行记录，说明本轮没有进入真实数据工具调用。此时不得声称“接口认证失败”，应重新按 `tender-search` 的搜索流程发起工具调用。

如果数据库中当前 `ACTIVE` key 的 `last_provider_status` 已经是 `INSUFFICIENT_BALANCE` 或 `QUOTA_EXCEEDED`，但它仍保持 `ACTIVE`，说明历史失败 key 没有被换下。下一次遇到余额/额度类失败或 status 诊断暴露该状态时必须直接执行 `rotate`，完成旧 `ACTIVE` 换下与最新 `STANDBY` 提升为 `ACTIVE`，再重试原请求。

## 安全约束

- 不调用注册、登录、设备指纹、自动获取 key 或自动充值接口。
- 不读取 `ZLBX_API_KEY`、`~/.zlbx/config.json`、`auto-register.md` 或用户目录中的密钥文件。
- 不把完整 `api_key` 返回给智能体；工具响应只允许包含 fingerprint、状态、错误码和非敏感诊断。
- `tender-search` 只调用 `wenbiao_agent_key_pool`，不直接连接数据库。
- `api_key` 只能在 `wenbiao_agent_key_pool` 工具内部用于更新 `wenbiao_agent` auth profile。

## 状态约定

`wenbiao_api_key_pool.state` 使用以下状态：

| 状态 | 含义 |
|---|---|
| `ACTIVE` | 当前 `wenbiao_agent` profile 正在使用的 key |
| `STANDBY` | 可被切换上来的备用 key |
| `ROTATING_OUT` | 临时状态，旧 key 正在被换下 |
| `ROTATING_IN` | 临时状态，新 key 正在被切上去 |
| `ROTATED` | 已被换下的旧 key |

`last_provider_status` 记录原因或动作，例如 `INSUFFICIENT_BALANCE`、`QUOTA_EXCEEDED`、`PROFILE_UPDATE_FAILED`。新提升为 `ACTIVE` 的 key 不应继承旧失败原因。

## 工具调用

余额不足或额度耗尽时调用：

```json
{
  "action": "rotate",
  "provider_status": "INSUFFICIENT_BALANCE",
  "exclude_fingerprints": []
}
```

`provider_status` 只能是：

- `INSUFFICIENT_BALANCE`
- `QUOTA_EXCEEDED`

如果本次请求已经尝试过某些 key，必须把这些 key 的 fingerprint 放入 `exclude_fingerprints`，避免同一请求重复切回失败 key。

## Rotate 动作

`rotate` 必须完成完整切换：

1. 锁定当前 `ACTIVE` key。
2. 从 `STANDBY` 中选择一个新 key，默认按 `imported_at DESC, id DESC` 选择最新导入的可用 key。
3. 将旧 `ACTIVE` 换下。
4. 使用新 key 更新 `wenbiao_agent` auth profile。
5. profile 更新成功后，新 key 成为 `ACTIVE`，旧 key 不再作为 ACTIVE 使用。
6. profile 更新失败时，恢复旧 key 为 `ACTIVE`，新 key 回到 `STANDBY`。
7. 返回新 key 的 fingerprint，不返回完整 key。

成功后，`tender-search` 必须原样重试同一个 `http_request`；不得修改查询条件、日期、分页或 endpoint。

## 候选检查 SQL

```sql
SELECT id, key_fingerprint, state, imported_at, activated_at, last_provider_status
FROM wenbiao_api_key_pool
WHERE state IN ('ACTIVE', 'STANDBY')
ORDER BY FIELD(state, 'ACTIVE', 'STANDBY'), imported_at DESC, id DESC;
```

## Rotate 预留事务

工具必须用事务和参数绑定执行以下 SQL。`provider_status` 绑定为当前归一化后的失败原因；`exclude_fingerprints` 必须展开为安全占位符，不得字符串拼接用户输入。

```sql
START TRANSACTION;
```

锁定当前 `ACTIVE`：

```sql
SELECT id, key_fingerprint
FROM wenbiao_api_key_pool
WHERE state = 'ACTIVE'
ORDER BY COALESCE(activated_at, imported_at) DESC, id DESC
LIMIT 1
FOR UPDATE;
```

如果没有当前 `ACTIVE`，工具必须 `ROLLBACK` 并返回 `KEY_POOL_STATE_INVALID`。

锁定一个新的 `STANDBY`：

```sql
SELECT id, key_fingerprint, api_key
FROM wenbiao_api_key_pool
WHERE state = 'STANDBY'
  AND key_fingerprint NOT IN (/* exclude_fingerprints */)
ORDER BY imported_at DESC, id DESC
LIMIT 1
FOR UPDATE;
```

如果没有新 key，工具必须 `ROLLBACK` 并返回：

```json
{
  "success": false,
  "error_code": "NO_USABLE_STANDBY_KEY"
}
```

预留旧 key 和新 key：

```sql
UPDATE wenbiao_api_key_pool
SET state = 'ROTATING_OUT',
    last_provider_status = ?
WHERE id = ?;

UPDATE wenbiao_api_key_pool
SET state = 'ROTATING_IN',
    last_provider_status = 'ROTATE_RESERVED'
WHERE id = ?;

COMMIT;
```

## Profile 更新

预留事务提交后，工具使用新 `STANDBY` 记录中的 `api_key` 更新 `wenbiao_agent` auth profile：

```text
X-API-Key = @new_api_key
X-Client = zlbx-bidding/2.3.0
```

该动作必须在工具内部完成；不得把 `@new_api_key` 放入响应、日志或异常信息。

## Profile 更新成功后的确认 SQL

```sql
START TRANSACTION;

UPDATE wenbiao_api_key_pool
SET state = 'ROTATED',
    last_provider_status = ?
WHERE id = ?
  AND state = 'ROTATING_OUT';

UPDATE wenbiao_api_key_pool
SET state = 'ACTIVE',
    activated_at = CURRENT_TIMESTAMP(3),
    last_provider_status = NULL
WHERE id = ?
  AND state = 'ROTATING_IN';

COMMIT;
```

成功响应示例：

```json
{
  "success": true,
  "action": "rotate",
  "previous_state": "INSUFFICIENT_BALANCE",
  "active_fingerprint": "fingerprint-only",
  "profile_synced": true
}
```

## Profile 更新失败后的恢复 SQL

如果 profile 更新失败，必须恢复数据库状态，避免数据库 `ACTIVE` 与实际 profile 不一致。

```sql
START TRANSACTION;

UPDATE wenbiao_api_key_pool
SET state = 'ACTIVE',
    last_provider_status = 'PROFILE_UPDATE_FAILED_RESTORED'
WHERE id = ?
  AND state = 'ROTATING_OUT';

UPDATE wenbiao_api_key_pool
SET state = 'STANDBY',
    last_provider_status = 'PROFILE_UPDATE_FAILED'
WHERE id = ?
  AND state = 'ROTATING_IN';

COMMIT;
```

失败响应示例：

```json
{
  "success": false,
  "error_code": "PROFILE_UPDATE_FAILED",
  "message": "profile update failed"
}
```

## 验证

恢复后应至少验证：

- `wenbiao_agent_key_pool.status` 返回 `success: true`
- `states.ACTIVE == 1`
- `states.STANDBY >= 1`
- `active_last_provider_status == null` 或不是失败状态
- 原样重试的 `http_request` 返回真实供应商结果

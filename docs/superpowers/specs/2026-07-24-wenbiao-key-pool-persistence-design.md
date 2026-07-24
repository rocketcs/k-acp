# Wenbiao Agent Key Pool persistence

## Goal

Persist every successful result from the `批量注册 Agent 并获取 API Key` workflow in the local runtime key pool, so the `标讯搜索与分析` agent can rotate to a previously registered key without handling a raw key in its workflow definition or prompt.

## Fixed storage boundary

All key-pool operations use one fixed runtime path:

```text
/app/.apboa/secrets/wenbiao_agent-key-pool.json
```

The workflow cannot provide a path and no tool accepts a path parameter. Docker mounts each runtime's `/app/.apboa` from that deployment's data volume, so local, test, and production runtimes retain separate physical files while using the same in-container path.

The file contains credentials and must remain readable only by the runtime account. No raw API key may appear in workflow definitions, node outputs, logs, error messages, or pool-status responses.

## Approaches considered

1. A dedicated import tool after the registration HTTP node (selected).
   It has a narrow input contract, can validate and deduplicate records, and owns the secure write protocol.
2. A shell or workflow CODE node writes JSON directly.
   This bypasses the runtime security model and makes file permissions, concurrent writes, and secret-safe outputs unreliable.
3. Add import behaviour to the existing rotation tool.
   This is possible, but a separate import operation keeps registration persistence independent from authentication-failure handling.

## Components and data flow

```text
HTTP_EXTERNAL(register current context)
  -> CODE(shape successful registration result)
  -> TOOL_EXECUTE(append_wenbiao_registration_result)
  -> random delay / loop summary

标讯搜索与分析 authentication failure
  -> Wenbiao Agent Key Pool (mark_failure / rotate)
  -> retry provider call once
```

`append_wenbiao_registration_result` receives only the registration result for the current loop iteration:

```json
{
  "success": true,
  "api_key": "provider-issued-key",
  "device_id": "provider-device-id",
  "remaining_calls": 100,
  "is_new": true,
  "message": "设备账号创建成功",
  "context": {
    "device_features": {
      "hostname": "generated-hostname"
    }
  }
}
```

The key is intentionally passed between runtime nodes only. The tool response contains no key and returns this shape instead:

```json
{
  "success": true,
  "action": "append",
  "result": "added",
  "device_id": "provider-device-id",
  "api_key_fingerprint": "first-16-hex-sha256",
  "pool_size": 2,
  "standby_count": 1
}
```

An unsuccessful response, a missing key, an invalid key format, or a missing device ID is a safe no-write result. It does not make the loop fail merely because a registration was not successful.

## Pool schema and writes

Existing pool entries remain valid. A newly persisted entry has the current fields plus registration metadata:

```json
{
  "api_key": "provider-issued-key",
  "device_id": "provider-device-id",
  "context": { "device_features": { "hostname": "generated-hostname" } },
  "registered_at": "2026-07-24T00:00:00Z",
  "remaining_calls": 100,
  "registration_message": "设备账号创建成功",
  "is_new": true,
  "state": "STANDBY",
  "last_checked_at": "",
  "last_provider_status": "",
  "last_rotated_at": ""
}
```

The tool takes the same file lock used by `WenbiaoAgentKeyPoolTool`, loads the JSON, checks both the SHA-256 key fingerprint and `device_id`, appends at most once, and writes via a same-directory temporary file followed by an atomic replace. A duplicate returns `result: "duplicate"`; it never overwrites an `ACTIVE` entry or silently changes key state. The temporary and final file permissions are restricted to the runtime account where the filesystem supports POSIX permissions.

## Rotation behaviour

`WenbiaoAgentKeyPoolTool` remains the only component that changes the active profile. Its `rotate` action selects eligible `STANDBY` entries from the same file and updates the active-key file and HTTP profile atomically under the pool lock.

For the tender-search agent, rotation occurs only after an explicit provider authentication-expiry signal. The agent marks the failed active entry, attempts one rotation, then retries the provider request exactly once. Generic timeouts, malformed responses, and 5xx responses do not rotate credentials. If no valid standby entry exists, the agent returns `NO_USABLE_STANDBY_KEY` without exposing a key.

## Workflow changes

- Add one `TOOL_EXECUTE` node after registration-result shaping in the existing registration subworkflow.
- Bind the current iteration's structured result to the import tool; do not serialize a key into node configuration.
- Extend the per-iteration and final workflow summaries with the import result's non-secret fields (`result`, fingerprint, pool counts, and device ID).
- Keep the registration loop's existing control over number of registrations and delays. Importing a result does not create an additional provider request.

## Validation

- Unit-test adding a valid `STANDBY` entry, duplicate detection by key and device ID, invalid-result no-op, and preservation of the existing `ACTIVE` entry.
- Unit-test file-lock/atomic-write helpers and assert tool outputs never contain `api_key`.
- Validate the saved workflow graph and run only a controlled single-registration execution after explicit user approval, because that request creates a persistent provider credential.
- Inspect pool status through the existing status action: it must show counts and fingerprints only, never raw credentials.

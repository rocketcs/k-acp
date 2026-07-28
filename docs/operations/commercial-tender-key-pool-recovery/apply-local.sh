#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_ROOT="$WORKSPACE_ROOT"
if [[ ! -f "$ENV_ROOT/env/local/.env" ]]; then
  ENV_ROOT="$(git -C "$WORKSPACE_ROOT" worktree list --porcelain | awk '/^worktree / { print substr($0, 10); exit }')"
fi
[[ -f "$ENV_ROOT/env/local/.env" ]] || { echo "local environment file is unavailable" >&2; exit 1; }

mysql_local() {
  "$ENV_ROOT/scripts/with-environment.sh" local --require mysql -- docker exec -i k-acp-mysql sh -lc 'exec mysql --default-character-set=utf8mb4 -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
}

b64() { base64 | tr -d '\n'; }

OLD_CALL_B64="$(b64 <<'EOF'
    private static Map<String, Object> callKeyPool(Map<String, Object> params) {
        try {
            ToolService toolService = SpringContextHolder.getBean(ToolService.class);
            ToolConfig toolConfig = toolService.lambdaQuery()
                .eq(ToolConfig::getToolId, "wenbiao_agent_key_pool")
                .one();
            if (toolConfig == null || toolConfig.getLanguage() == null
                    || toolConfig.getCode() == null || toolConfig.getCode().isBlank()) {
                return rotationError("KEY_POOL_TOOL_UNAVAILABLE");
            }
            IDynamicAgentTool keyPoolTool = ToolInstanceLoadFactory
                .getInstanceLoader(toolConfig.getLanguage())
                .loadInstance(toolConfig.getCode());
            Object result = keyPoolTool.execute(null, params);
            if (!(result instanceof Map<?, ?>)) {
                return rotationError("INVALID_KEY_POOL_RESPONSE");
            }
            Map<?, ?> raw = (Map<?, ?>) result;
            Map<String, Object> rotation = new LinkedHashMap<>();
            raw.forEach((key, value) -> rotation.put(String.valueOf(key), value));
            return rotation;
        } catch (Exception ignored) {
            return rotationError("KEY_POOL_ROTATION_FAILED");
        }
    }
EOF
)"

NEW_CALL_B64="$(b64 <<'EOF'
    private static Map<String, Object> callKeyPool(Map<String, Object> params) {
        String action = string(params == null ? null : params.get("action")).toLowerCase(Locale.ROOT);
        try (java.sql.Connection connection = SpringContextHolder.getBean(javax.sql.DataSource.class).getConnection()) {
            if ("lease_active".equals(action)) return leaseFromDatabase(connection);
            if ("rotate".equals(action)) return rotateFromDatabase(connection,
                string(params.get("provider_status")).toUpperCase(Locale.ROOT));
            return rotationError("INVALID_KEY_POOL_ACTION");
        } catch (Exception error) {
            Map<String, Object> response = rotationError("KEY_POOL_UNAVAILABLE");
            String detail = error.getClass().getSimpleName();
            if (error.getMessage() != null && !error.getMessage().isBlank()) {
                detail += ": " + error.getMessage();
            }
            response.put("error_detail", detail);
            return response;
        }
    }

    private static Map<String, Object> leaseFromDatabase(java.sql.Connection connection) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            KeyLease active = selectPoolKey(connection, "ACTIVE", true);
            if (active == null) active = promoteStandby(connection, "INITIAL_LEASE");
            connection.commit();
            return active == null ? rotationError("KEY_POOL_EXHAUSTED") : leaseResponse("lease_active", active);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static Map<String, Object> rotateFromDatabase(
            java.sql.Connection connection, String providerStatus) throws Exception {
        if (!ROTATABLE_KEY_FAILURES.contains(providerStatus)) return rotationError("NON_ROTATABLE_STATUS");
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            KeyLease active = selectPoolKey(connection, "ACTIVE", true);
            if (active != null) retirePoolKey(connection, active, providerStatus);
            KeyLease next = promoteStandby(connection, providerStatus);
            connection.commit();
            if (next == null) return rotationError("KEY_POOL_EXHAUSTED");
            Map<String, Object> response = leaseResponse("rotate", next);
            response.put("previous_state", providerStatus);
            return response;
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static KeyLease selectPoolKey(
            java.sql.Connection connection, String state, boolean lock) throws Exception {
        String sql = "SELECT id, key_fingerprint, api_key FROM wenbiao_api_key_pool WHERE state = ? ORDER BY id LIMIT 1"
            + (lock ? " FOR UPDATE" : "");
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                return result.next() ? new KeyLease(result.getLong(1), result.getString(2), result.getString(3)) : null;
            }
        }
    }

    private static KeyLease promoteStandby(java.sql.Connection connection, String reason) throws Exception {
        KeyLease standby = selectPoolKey(connection, "STANDBY", true);
        if (standby == null) return null;
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "UPDATE wenbiao_api_key_pool SET state = 'ACTIVE', activated_at = CURRENT_TIMESTAMP(3), last_provider_status = ? WHERE id = ?")) {
            statement.setString(1, reason);
            statement.setLong(2, standby.id);
            statement.executeUpdate();
        }
        return standby;
    }

    private static void retirePoolKey(
            java.sql.Connection connection, KeyLease key, String reason) throws Exception {
        try (java.sql.PreparedStatement retired = connection.prepareStatement(
                "INSERT INTO wenbiao_api_key_retired (key_fingerprint, reason, retired_at) VALUES (?, ?, CURRENT_TIMESTAMP(3)) "
                    + "ON DUPLICATE KEY UPDATE reason = VALUES(reason), retired_at = VALUES(retired_at)")) {
            retired.setString(1, key.fingerprint);
            retired.setString(2, reason);
            retired.executeUpdate();
        }
        try (java.sql.PreparedStatement deleted = connection.prepareStatement(
                "DELETE FROM wenbiao_api_key_pool WHERE id = ?")) {
            deleted.setLong(1, key.id);
            deleted.executeUpdate();
        }
    }

    private static Map<String, Object> leaseResponse(String action, KeyLease key) {
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("fingerprint", key.fingerprint);
        lease.put("api_key", key.apiKey);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("action", action);
        response.put("active_fingerprint", key.fingerprint);
        response.put("lease", lease);
        return response;
    }

    private static final class KeyLease {
        private final long id;
        private final String fingerprint;
        private final String apiKey;

        private KeyLease(long id, String fingerprint, String apiKey) {
            this.id = id;
            this.fingerprint = fingerprint;
            this.apiKey = apiKey;
        }
    }
EOF
)"

OLD_NO_ACTIVE_B64="$(b64 <<'EOF'
    private static Map<String, Object> noActiveKey(Map<String, Object> lease) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("is_complete", false);
        response.put("incomplete_reason", "KEY_POOL_EXHAUSTED");
        response.put("records", List.of());
        response.put("errors", List.of("No active Wenbiao key is available"));
        response.put("key_rotation", Map.of(
            "attempted", false,
            "error_code", string(lease.get("error_code"))));
        return response;
    }
EOF
)"

NEW_NO_ACTIVE_B64="$(b64 <<'EOF'
    private static Map<String, Object> noActiveKey(Map<String, Object> lease) {
        String errorCode = string(lease.get("error_code"));
        String reason = "KEY_POOL_EXHAUSTED".equals(errorCode)
            ? "KEY_POOL_EXHAUSTED" : "KEY_POOL_UNAVAILABLE";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("is_complete", false);
        response.put("incomplete_reason", reason);
        response.put("records", List.of());
        response.put("errors", List.of("No active Wenbiao key is available"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attempted", false);
        metadata.put("error_code", errorCode);
        metadata.put("error_detail", string(lease.get("error_detail")));
        response.put("key_rotation", metadata);
        return response;
    }
EOF
)"

OLD_EXHAUSTED_B64="$(b64 <<'EOF'
    private static Map<String, Object> poolExhausted(
            Map<String, Object> first, Map<String, Object> rotation) {
        Map<String, Object> response = new LinkedHashMap<>(first);
        response.put("success", false);
        response.put("is_complete", false);
        response.put("incomplete_reason", "KEY_POOL_EXHAUSTED");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attempted", true);
        metadata.put("retry_count", 0);
        metadata.put("error_code", string(rotation.get("error_code")));
        metadata.put("active_fingerprint", string(rotation.get("active_fingerprint")));
        response.put("key_rotation", metadata);
        return response;
    }
EOF
)"

NEW_EXHAUSTED_B64="$(b64 <<'EOF'
    private static Map<String, Object> poolExhausted(
            Map<String, Object> first, Map<String, Object> rotation) {
        String errorCode = string(rotation.get("error_code"));
        String reason = "KEY_POOL_EXHAUSTED".equals(errorCode)
            ? "KEY_POOL_EXHAUSTED" : "KEY_POOL_UNAVAILABLE";
        Map<String, Object> response = new LinkedHashMap<>(first);
        response.put("success", false);
        response.put("is_complete", false);
        response.put("incomplete_reason", reason);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attempted", true);
        metadata.put("retry_count", 0);
        metadata.put("error_code", errorCode);
        metadata.put("active_fingerprint", string(rotation.get("active_fingerprint")));
        response.put("key_rotation", metadata);
        return response;
    }
EOF
)"

MATCHES="$(mysql_local <<SQL
SET collation_connection='utf8mb4_unicode_ci';
SELECT COUNT(*)
FROM tool_config
WHERE tool_id='execute_tender_high_recall_v1'
  AND LOCATE(CONVERT(FROM_BASE64('$OLD_CALL_B64') USING utf8mb4), code) > 0
  AND LOCATE(CONVERT(FROM_BASE64('$OLD_NO_ACTIVE_B64') USING utf8mb4), code) > 0
  AND LOCATE(CONVERT(FROM_BASE64('$OLD_EXHAUSTED_B64') USING utf8mb4), code) > 0;
SQL
)"
[[ "$MATCHES" == "1" ]] || { echo "expected tender executor source revision is not installed" >&2; exit 1; }

mysql_local <<SQL
START TRANSACTION;
SET collation_connection='utf8mb4_unicode_ci';
UPDATE tool_config
SET code=REPLACE(
  REPLACE(
    REPLACE(code,
      CONVERT(FROM_BASE64('$OLD_CALL_B64') USING utf8mb4),
      CONVERT(FROM_BASE64('$NEW_CALL_B64') USING utf8mb4)),
    CONVERT(FROM_BASE64('$OLD_NO_ACTIVE_B64') USING utf8mb4),
    CONVERT(FROM_BASE64('$NEW_NO_ACTIVE_B64') USING utf8mb4)),
  CONVERT(FROM_BASE64('$OLD_EXHAUSTED_B64') USING utf8mb4),
  CONVERT(FROM_BASE64('$NEW_EXHAUSTED_B64') USING utf8mb4))
WHERE tool_id='execute_tender_high_recall_v1';
DELETE st FROM skill_tools st
JOIN skill_package sp ON sp.id=st.skill_id
WHERE sp.name='tender-search' AND st.tool_id=2090300000000000101;
COMMIT;
SQL

"$ENV_ROOT/scripts/with-environment.sh" local --require mysql -- docker compose --project-name k-acp-local --env-file "$ENV_ROOT/docker/.env.kacp" -f "$ENV_ROOT/docker/docker-compose-simple.yml" -f "$ENV_ROOT/docker/docker-compose-kacp-local.yml" restart apboa-runtime
"$WORKSPACE_ROOT/docs/operations/commercial-tender-key-pool-recovery/verify-local.sh"

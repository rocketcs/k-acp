#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_ROOT="$WORKSPACE_ROOT"
if [[ ! -f "$ENV_ROOT/env/local/.env" ]]; then
  ENV_ROOT="$(git -C "$WORKSPACE_ROOT" worktree list --porcelain | awk '/^worktree / { print substr($0, 10); exit }')"
fi

RESULT="$("$ENV_ROOT/scripts/with-environment.sh" local --require mysql -- docker exec -i k-acp-mysql sh -lc 'exec mysql --default-character-set=utf8mb4 -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<'SQL'
SELECT IF(COUNT(*)=1,'OK direct pool lease','FAIL direct pool lease') FROM tool_config WHERE tool_id='execute_tender_high_recall_v1' AND code LIKE '%leaseFromDatabase%' AND code LIKE '%rotateFromDatabase%' AND code LIKE '%SELECT id, key_fingerprint, api_key FROM wenbiao_api_key_pool%';
SELECT IF(COUNT(*)=1,'OK accurate pool errors','FAIL accurate pool errors') FROM tool_config WHERE tool_id='execute_tender_high_recall_v1' AND code LIKE '%KEY_POOL_UNAVAILABLE%' AND code LIKE '%"KEY_POOL_EXHAUSTED".equals(errorCode)%';
SELECT IF(COUNT(*)=0,'OK key pool hidden from agent','FAIL key pool hidden from agent') FROM skill_tools st JOIN skill_package sp ON sp.id=st.skill_id WHERE sp.name='tender-search' AND st.tool_id=2090300000000000101;
SELECT CONCAT('OK key states active=', SUM(state='ACTIVE'), ', standby=', SUM(state='STANDBY')) FROM wenbiao_api_key_pool HAVING SUM(state='ACTIVE') >= 1 AND SUM(state='STANDBY') >= 1;
SQL
)"
printf '%s\n' "$RESULT"
if grep -q '^FAIL ' <<<"$RESULT" || ! grep -q '^OK key states ' <<<"$RESULT"; then exit 1; fi

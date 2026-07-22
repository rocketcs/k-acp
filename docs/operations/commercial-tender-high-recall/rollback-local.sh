#!/usr/bin/env bash
set -euo pipefail

MYSQL_CONTAINER=${KACP_MYSQL_CONTAINER:-k-acp-mysql}
BACKUP_FILE=${KACP_BACKUP_DIR:-$HOME/.k-acp-backups}/commercial-tender-high-recall-before.json
[[ -f "$BACKUP_FILE" ]] || { echo "ERROR backup not found: $BACKUP_FILE" >&2; exit 1; }
old_prompt_id=$(jq -r '.old_prompt_id' "$BACKUP_FILE")

{
  printf '%s\n' 'START TRANSACTION;'
  printf "UPDATE agent_definition SET system_prompt_template_id=%s WHERE agent_code='default-tender' AND tenant_id=1;\n" "$old_prompt_id"
  printf '%s\n' 'DELETE FROM agent_tools WHERE tool_id IN (2079122200000000101,2079122200000000102,2079122200000000103);'
  printf '%s\n' 'DELETE FROM agent_skill_packages WHERE skill_package_id=2079122200000000201;'
  printf '%s\n' 'DELETE FROM agent_workflows WHERE workflow_id=2079122200000000401;'
  printf '%s\n' "DELETE FROM workflow_node_execution WHERE workflow_id='2079122200000000401';"
  printf '%s\n' "DELETE FROM workflow_run WHERE workflow_id='2079122200000000401';"
  printf '%s\n' "DELETE FROM workflow_version WHERE workflow_id='2079122200000000401';"
  printf '%s\n' 'DELETE FROM workflow WHERE id=2079122200000000401;'
  printf '%s\n' 'DELETE FROM skill_tools WHERE skill_id=2079122200000000201;'
  printf '%s\n' 'DELETE FROM skill_file WHERE skill_id=2079122200000000201;'
  printf '%s\n' 'DELETE FROM skill_package WHERE id=2079122200000000201;'
  printf '%s\n' 'DELETE FROM tool_config WHERE id IN (2079122200000000101,2079122200000000102,2079122200000000103);'
  printf '%s\n' 'DELETE FROM system_prompt_template WHERE id=2079122200000000301;'
  printf '%s\n' 'COMMIT;'
} | docker exec -i "$MYSQL_CONTAINER" sh -lc 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" apboa_next' >/dev/null

echo "ROLLED BACK commercial-tender-high-recall v1"

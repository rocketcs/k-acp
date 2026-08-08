#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
database_name="apboa_schema_upgrade_test"
compose=(docker compose --project-name k-acp-local --env-file "$repository_root/docker/.env.kacp"
  -f "$repository_root/docker/docker-compose-simple.yml"
  -f "$repository_root/docker/docker-compose-kacp-local.yml")

mysql_exec() {
  local database="${1:-}"
  local database_option=""
  if [[ -n "$database" ]]; then
    database_option=" $database"
  fi
  "${compose[@]}" exec -T apboa-mysql sh -lc \
    "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --batch --skip-column-names$database_option" \
    2>/dev/null
}

cleanup() {
  printf 'DROP DATABASE IF EXISTS `%s`;\n' "$database_name" | mysql_exec || true
}
trap cleanup EXIT

[[ -f "$repository_root/runner-console/src/main/resources/db/migration/V4__align_legacy_schema.sql" ]]

cat <<SQL | mysql_exec
DROP DATABASE IF EXISTS \`$database_name\`;
CREATE DATABASE \`$database_name\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE \`$database_name\`;
CREATE TABLE agent_definition (id bigint NOT NULL, avatar varchar(64) NOT NULL, PRIMARY KEY (id));
CREATE TABLE channel (id bigint NOT NULL, type varchar(20), health_status varchar(20), PRIMARY KEY (id));
CREATE TABLE quartz_job_info (id varchar(64) NOT NULL, type varchar(100), biz_id varchar(64), cron varchar(64), job_class varchar(100), data_map text, enabled tinyint(1), tenant_id bigint NOT NULL, created_at datetime, updated_at datetime, created_by bigint, updated_by bigint, PRIMARY KEY (id));
CREATE TABLE secret_key (id bigint NOT NULL, name varchar(100) NOT NULL, value varchar(500), enabled tinyint DEFAULT 1, expire_time datetime, created_by bigint NOT NULL, updated_by bigint, created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, tenant_id bigint NOT NULL, PRIMARY KEY (id));
CREATE TABLE workflow (id bigint NOT NULL, PRIMARY KEY (id));
CREATE TABLE workflow_run (id bigint NOT NULL, outputs json, PRIMARY KEY (id));
CREATE TABLE mcp_tool (id bigint NOT NULL, PRIMARY KEY (id));
SQL

for migration in \
  V2__mcp_tool_add_need_confirm.sql \
  V3__chatflow_add_flow_type.sql \
  V4__align_legacy_schema.sql; do
  mysql_exec "$database_name" < "$repository_root/runner-console/src/main/resources/db/migration/$migration"
done

expected=$(cat <<'SQL'
SELECT CONCAT('dashboard=', COUNT(*)) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'dashboard';
SELECT CONCAT('dashboard_dataset=', COUNT(*)) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'dashboard_dataset';
SELECT CONCAT('dashboard_history=', COUNT(*)) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'dashboard_history';
SELECT CONCAT('dashboard_user=', COUNT(*)) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'dashboard_user';
SELECT CONCAT('mcp_tool.need_confirm=', COUNT(*)) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mcp_tool' AND column_name = 'need_confirm';
SELECT CONCAT('workflow.flow_type=', COUNT(*)) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workflow' AND column_name = 'flow_type';
SELECT CONCAT('dashboard_dataset.shared=', COUNT(*)) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'dashboard_dataset' AND column_name = 'shared';
SELECT CONCAT('workflow_run.outputs=', column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workflow_run' AND column_name = 'outputs';
SELECT CONCAT('secret_key.value=', column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'secret_key' AND column_name = 'value';
SELECT CONCAT('channel.type=', column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'channel' AND column_name = 'type';
SELECT CONCAT('quartz_job_info.id=', column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'quartz_job_info' AND column_name = 'id';
SQL
)

actual=$(printf '%s\n' "$expected" | mysql_exec "$database_name")
printf '%s\n' "$actual"

for assertion in \
  'dashboard=1' \
  'dashboard_dataset=1' \
  'dashboard_history=1' \
  'dashboard_user=1' \
  'mcp_tool.need_confirm=1' \
  'workflow.flow_type=1' \
  'dashboard_dataset.shared=1' \
  'workflow_run.outputs=mediumtext' \
  'secret_key.value=text' \
  "channel.type=enum('EMAIL','WECOM','DINGTALK','FEISHU')" \
  'quartz_job_info.id=bigint'; do
  grep -Fx "$assertion" <<< "$actual" >/dev/null
done

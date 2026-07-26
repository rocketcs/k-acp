#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_ROOT="$WORKSPACE_ROOT"
if [[ ! -f "$ENV_ROOT/env/local/.env" ]]; then ENV_ROOT="$(git -C "$WORKSPACE_ROOT" worktree list --porcelain | awk '/^worktree / { print substr($0, 10); exit }')"; fi
mysql_local() { "$ENV_ROOT/scripts/with-environment.sh" local --require mysql -- docker exec -i k-acp-mysql sh -lc 'exec mysql --default-character-set=utf8mb4 -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'; }

RESULT="$(mysql_local <<'SQL'
SELECT IF(COUNT(*)=1,'OK curator binding','FAIL curator binding') FROM agent_skill_packages asp JOIN agent_definition a ON a.id=asp.agent_definition_id JOIN skill_package sp ON sp.id=asp.skill_package_id WHERE a.agent_code='default-tender' AND sp.name='commercial-tender-followup-curator';
SELECT IF(COUNT(*)=1,'OK prompt curator route','FAIL prompt curator route') FROM system_prompt_template sp JOIN agent_definition a ON a.system_prompt_template_id=sp.id WHERE a.agent_code='default-tender' AND sp.content LIKE '%commercial-tender-followup-curator%' AND sp.content LIKE '%不得建议监控%';
SELECT IF(COUNT(*)=1,'OK workflow factual only','FAIL workflow factual only') FROM workflow w JOIN JSON_TABLE(w.config, '$.nodes[*]' COLUMNS (node JSON PATH '$')) j WHERE w.id=2079122200000000401 AND JSON_UNQUOTE(JSON_EXTRACT(j.node,'$.id'))='answer-generator' AND JSON_UNQUOTE(JSON_EXTRACT(j.node,'$.config.systemPrompt')) LIKE '%禁止输出 UIP%';
SELECT IF(COUNT(*)=1,'OK curator files','FAIL curator files') FROM skill_file sf JOIN skill_package sp ON sp.id=sf.skill_id WHERE sp.name='commercial-tender-followup-curator' AND sf.file_path='SKILL.md' AND sf.content LIKE '%value%' AND sf.content LIKE '%完整中文请求%';
SELECT IF(COUNT(*)=1,'OK tool ownership','FAIL tool ownership') FROM tool_config WHERE name='商业标书高召回搜索' AND description LIKE '%curator%';
SQL
 )"
printf '%s\n' "$RESULT"
if grep -q '^FAIL ' <<<"$RESULT"; then exit 1; fi

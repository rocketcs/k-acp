#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_ROOT="$WORKSPACE_ROOT"
if [[ ! -f "$ENV_ROOT/env/local/.env" ]]; then
  ENV_ROOT="$(git -C "$WORKSPACE_ROOT" worktree list --porcelain | awk '/^worktree / { print substr($0, 10); exit }')"
fi
[[ -f "$ENV_ROOT/env/local/.env" ]] || { echo "local environment file is unavailable" >&2; exit 1; }

CURATOR_DIR="$WORKSPACE_ROOT/docs/operations/commercial-tender-followup-curator/skill"
PROMPT_FILE="$WORKSPACE_ROOT/docs/operations/commercial-tender-followup-curator/system-prompt.md"
ANSWER_FILE="$WORKSPACE_ROOT/docs/operations/commercial-tender-followup-curator/high-recall-answer-prompt.md"
[[ -f "$CURATOR_DIR/SKILL.md" && -f "$CURATOR_DIR/references/question-patterns.md" ]] || { echo "curator Skill source is unavailable" >&2; exit 1; }

mysql_local() {
  "$ENV_ROOT/scripts/with-environment.sh" local --require mysql -- docker exec -i k-acp-mysql sh -lc 'exec mysql --default-character-set=utf8mb4 -N -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
}

b64() { base64 < "$1" | tr -d '\n'; }
PROMPT_B64="$(b64 "$PROMPT_FILE")"
ANSWER_B64="$(b64 "$ANSWER_FILE")"
SKILL_B64="$(b64 "$CURATOR_DIR/SKILL.md")"
PATTERNS_B64="$(b64 "$CURATOR_DIR/references/question-patterns.md")"
OLD_HIGH_RECALL_B64="$(printf '%s' '- 回答阶段在普通检索结果末尾生成一张 UIP 下一步选择卡片：有剩余结果时提供“继续查看下一批、只看仍可报名的项目、筛选最值得跟进的项目、调整地区、时间或阶段”；没有剩余结果时去掉“继续查看下一批”。卡片只承载用户可见的业务动作，不包含 `continuationState`、查询条件、链接解析细节或任何内部字段。' | base64 | tr -d '\n')"
NEW_HIGH_RECALL_B64="$(printf '%s' '- 回答阶段只输出当前批次的事实正文、链接状态和连续状态；禁止生成 UIP 卡片、选项或动作码。下一步问题只由外层 commercial-tender-followup-curator 根据已确认事实生成。' | base64 | tr -d '\n')"
DESCRIPTION_B64="$(printf '%s' '商业标书智能体的下一步业务问题策展器。仅在当前业务回答完成后，基于已验证项目、采购方、查询范围、企业画像和已启用只读能力生成可点击追问卡片；不执行查询、不编造事实、不建议监控、导出或外联。' | base64 | tr -d '\n')"
TOOL_DESCRIPTION_B64="$(printf '%s' '商业标书智能体专用入口：执行已发布高召回工作流并返回事实结果、查询计划与连续状态。该工具不生成追问卡片；最终追问仅由 commercial-tender-followup-curator 生成。' | base64 | tr -d '\n')"

mysql_local <<SQL
START TRANSACTION;
SET collation_connection='utf8mb4_unicode_ci';
UPDATE system_prompt_template sp JOIN agent_definition a ON a.system_prompt_template_id=sp.id
SET sp.content=CONVERT(FROM_BASE64('$PROMPT_B64') USING utf8mb4)
WHERE a.agent_code='default-tender';
UPDATE skill_package SET description=CONVERT(FROM_BASE64('$DESCRIPTION_B64') USING utf8mb4)
WHERE name='commercial-tender-followup-curator';
UPDATE skill_file sf JOIN skill_package sp ON sp.id=sf.skill_id
SET sf.content=CONVERT(FROM_BASE64('$SKILL_B64') USING utf8mb4)
WHERE sp.name='commercial-tender-followup-curator' AND sf.file_path='SKILL.md';
UPDATE skill_file sf JOIN skill_package sp ON sp.id=sf.skill_id
SET sf.content=CONVERT(FROM_BASE64('$PATTERNS_B64') USING utf8mb4)
WHERE sp.name='commercial-tender-followup-curator' AND sf.file_path='references/question-patterns.md';
UPDATE skill_file sf JOIN skill_package sp ON sp.id=sf.skill_id
SET sf.content=REPLACE(sf.content, CONVERT(FROM_BASE64('$OLD_HIGH_RECALL_B64') USING utf8mb4), CONVERT(FROM_BASE64('$NEW_HIGH_RECALL_B64') USING utf8mb4))
WHERE sp.name='tender-high-recall-search' AND sf.file_path='SKILL.md';
UPDATE workflow SET config=JSON_SET(config, REPLACE(JSON_UNQUOTE(JSON_SEARCH(config, 'one', 'answer-generator', NULL, '$.nodes[*].id')), '.id', '.config.systemPrompt'), CONVERT(FROM_BASE64('$ANSWER_B64') USING utf8mb4))
WHERE id=2079122200000000401;
UPDATE workflow_version wv JOIN workflow w ON wv.workflow_id=CAST(w.id AS CHAR) AND wv.version=w.version
SET wv.config=JSON_SET(wv.config, REPLACE(JSON_UNQUOTE(JSON_SEARCH(wv.config, 'one', 'answer-generator', NULL, '$.nodes[*].id')), '.id', '.config.systemPrompt'), CONVERT(FROM_BASE64('$ANSWER_B64') USING utf8mb4))
WHERE w.id=2079122200000000401;
UPDATE tool_config SET description=CONVERT(FROM_BASE64('$TOOL_DESCRIPTION_B64') USING utf8mb4)
WHERE name='商业标书高召回搜索';
COMMIT;
SQL

"$ENV_ROOT/scripts/with-environment.sh" local --require mysql -- docker compose --project-name k-acp-local --env-file "$ENV_ROOT/docker/.env.kacp" -f "$ENV_ROOT/docker/docker-compose-simple.yml" -f "$ENV_ROOT/docker/docker-compose-kacp-local.yml" restart apboa-console apboa-runtime
"$WORKSPACE_ROOT/docs/operations/commercial-tender-followup-curator/verify-local.sh"

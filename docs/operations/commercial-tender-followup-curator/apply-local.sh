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
OLD_TENDER_LINK_RULE_B64="$(printf '%s' '5. 需要原始链接时，调用 `resolve_tender_source_urls_v2`，传入展示批次（1–20 条）的 `items`。每项使用已返回的 `bid_id`、`title`，并尽量携带 `source_url`、`aggregate_url` 或 `url`：' | base64 | tr -d '\n')"
NEW_TENDER_LINK_RULE_B64="$(printf '%s' '5. **只要回答会展示任何标讯（列表、项目卡片或单个项目），就必须先调用一次 `resolve_tender_source_urls_v2`**，传入本轮准备展示的全部 1–20 条 `items`。这是输出前置条件，不取决于用户是否明确说“原始链接”，也不得因为接口已有 `url`、`aggregate_url` 或知了聚合页而跳过。每项使用已返回的 `bid_id`、`title`，并尽量携带 `source_url`、`aggregate_url` 或 `url`：' | base64 | tr -d '\n')"
OLD_TENDER_OUTPUT_RULE_B64="$(printf '%s' '列出项目时优先呈现标题、公告阶段、发布时间、地区、采购方、金额（若返回）和已验证的原始链接。没有原始链接时明确写“未验证到原始公告链接”，不要用聚合页 URL 冒充原始链接。' | base64 | tr -d '\n')"
INTERMEDIATE_TENDER_OUTPUT_RULE_B64="$(printf '%s' '列出项目时，先完成来源解析，再呈现标题、公告阶段、发布时间、地区、采购方、金额（若返回）和链接状态。resolver 返回 `status: "VERIFIED"` 的项目，标题必须写为 `[项目标题](source_url)`；其他状态的标题保持普通文本，并在来源列明确写“未验证到原始公告链接”（可补充不可达或不存在状态）。不得静默省略链接状态，不得用聚合页 URL 冒充原始链接，也不得在 resolver 未执行前输出最终项目表格。' | base64 | tr -d '\n')"
CURRENT_TENDER_OUTPUT_RULE_B64="$(printf '%s' '列出项目时，先完成来源解析，再呈现标题、公告阶段、发布时间、地区、采购方、金额（若返回）和链接状态。resolver 返回非空 `display_url` 且 `link_type` 为 `SOURCE` 或 `SOURCE_UNVERIFIED` 的项目，标题必须写为 `[项目标题](display_url)`；`source_status` 为 `VERIFIED` 时标记“已验证原始公告链接”，为 `EXTRACTED`、`EXTRACTED_CLIENT_ROUTE` 或 `EXTRACTED_SOURCE_UNVERIFIED` 时标记“已提取原始公告链接（待验证）”。没有可展示 URL 时，标题保持普通文本，并在来源列明确写“未解析到原始公告链接”。不得静默省略链接状态，不得用聚合页 URL 冒充原始链接，也不得在 resolver 未执行前输出最终项目表格。' | base64 | tr -d '\n')"
NEW_TENDER_OUTPUT_RULE_B64="$(printf '%s' '列出项目时，先完成来源解析，再呈现标题、公告阶段、发布时间、地区、采购方、金额（若返回）和链接状态。resolver 返回非空 `display_url` 且 `link_type` 为 `SOURCE` 或 `SOURCE_UNVERIFIED` 的项目，标题必须写为 `[项目标题](display_url)`；`source_status` 为 `VERIFIED` 时标记“已验证原始公告链接”，为 `EXTRACTED`、`EXTRACTED_CLIENT_ROUTE` 或 `EXTRACTED_SOURCE_UNVERIFIED` 时标记“已提取原始公告链接（待验证）”。没有可展示 URL 时，标题保持普通文本；来源列明确写“未解析到原始公告链接”，且有 `aggregate_url` 时必须附 `[知了标讯详情（聚合页）](aggregate_url)` 作为可点击兜底。不得把聚合页称为原始公告，也不得在 resolver 未执行前输出最终项目表格。' | base64 | tr -d '\n')"
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
SET sf.content=REPLACE(
  REPLACE(
    REPLACE(
      REPLACE(sf.content,
    CONVERT(FROM_BASE64('$OLD_TENDER_LINK_RULE_B64') USING utf8mb4),
    CONVERT(FROM_BASE64('$NEW_TENDER_LINK_RULE_B64') USING utf8mb4)),
    CONVERT(FROM_BASE64('$OLD_TENDER_OUTPUT_RULE_B64') USING utf8mb4),
    CONVERT(FROM_BASE64('$NEW_TENDER_OUTPUT_RULE_B64') USING utf8mb4)),
    CONVERT(FROM_BASE64('$INTERMEDIATE_TENDER_OUTPUT_RULE_B64') USING utf8mb4),
    CONVERT(FROM_BASE64('$NEW_TENDER_OUTPUT_RULE_B64') USING utf8mb4)),
  CONVERT(FROM_BASE64('$CURRENT_TENDER_OUTPUT_RULE_B64') USING utf8mb4),
  CONVERT(FROM_BASE64('$NEW_TENDER_OUTPUT_RULE_B64') USING utf8mb4))
WHERE sp.name='tender-search' AND sf.file_path='SKILL.md';
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

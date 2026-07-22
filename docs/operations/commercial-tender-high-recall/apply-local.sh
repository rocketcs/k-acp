#!/usr/bin/env bash
set -euo pipefail

BASE_DIR=$(cd "$(dirname "$0")" && pwd -P)
WORKFLOW_ID=2079122200000000401
SEARCH_TOOL_ID=2079122200000000101
RESOLVER_TOOL_ID=2079122200000000102
GATEWAY_TOOL_ID=2079122200000000103
SKILL_ID=2079122200000000201
PROMPT_ID=2079122200000000301
AGENT_CODE=default-tender
MYSQL_CONTAINER=${KACP_MYSQL_CONTAINER:-k-acp-mysql}
API_BASE=${KACP_API_BASE:-http://127.0.0.1:23080}
BACKUP_DIR=${KACP_BACKUP_DIR:-$HOME/.k-acp-backups}
BACKUP_FILE="$BACKUP_DIR/commercial-tender-high-recall-before.json"
TMP_WORKFLOW=$(mktemp /tmp/commercial-tender-high-recall-workflow.XXXXXX.json)
trap 'rm -f "$TMP_WORKFLOW"' EXIT

for command in docker jq curl base64; do
  command -v "$command" >/dev/null || { echo "ERROR missing command: $command" >&2; exit 1; }
done

mysql_query() {
  docker exec "$MYSQL_CONTAINER" sh -lc \
    'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" apboa_next -N -s -e "$1"' sh "$1" 2>/dev/null
}

agent_count=$(mysql_query "SELECT COUNT(*) FROM agent_definition WHERE agent_code='$AGENT_CODE' AND enabled=1 AND tenant_id=1")
skill_count=$(mysql_query "SELECT COUNT(*) FROM skill_package WHERE name='tender-search' AND enabled=1 AND tenant_id=1")
http_count=$(mysql_query "SELECT COUNT(*) FROM tool_config WHERE tool_id='http_request' AND enabled=1 AND tenant_id=1")
model_count=$(mysql_query "SELECT COUNT(*) FROM model_config WHERE id=(SELECT model_config_id FROM agent_definition WHERE agent_code='$AGENT_CODE' AND tenant_id=1 LIMIT 1) AND enabled=1")
if [[ "$agent_count" != 1 || "$skill_count" != 1 || "$http_count" != 1 || "$model_count" != 1 ]]; then
  echo "ERROR preflight failed: default-tender=$agent_count tender-search=$skill_count http_request=$http_count model=$model_count" >&2
  exit 1
fi

collision_count=$(mysql_query "SELECT
  (SELECT COUNT(*) FROM tool_config WHERE id IN ($SEARCH_TOOL_ID,$RESOLVER_TOOL_ID,$GATEWAY_TOOL_ID) AND tool_id NOT IN ('execute_tender_high_recall_v1','resolve_tender_source_urls_v2','commercial_tender_high_recall_search')) +
  (SELECT COUNT(*) FROM skill_package WHERE id=$SKILL_ID AND name<>'tender-high-recall-search') +
  (SELECT COUNT(*) FROM workflow WHERE id=$WORKFLOW_ID AND route_id<>'commercial-tender-high-recall-v1')")
if [[ "$collision_count" != 0 ]]; then
  echo "ERROR package ID collision detected" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
old_prompt_id=$(mysql_query "SELECT IFNULL(system_prompt_template_id,0) FROM agent_definition WHERE agent_code='$AGENT_CODE' AND tenant_id=1 LIMIT 1")
model_id=$(mysql_query "SELECT model_config_id FROM agent_definition WHERE agent_code='$AGENT_CODE' AND tenant_id=1 LIMIT 1")
old_resolver_md5=$(mysql_query "SELECT IFNULL(MD5(code),'') FROM tool_config WHERE tool_id='resolve_tender_source_urls' AND tenant_id=1 LIMIT 1")
shared_skill_md5=$(mysql_query "SELECT MD5(GROUP_CONCAT(CONCAT(file_path,':',MD5(content)) ORDER BY file_path SEPARATOR '|')) FROM skill_file WHERE skill_id=(SELECT id FROM skill_package WHERE name='tender-search' AND tenant_id=1 LIMIT 1)")
jq -n \
  --arg old_prompt_id "$old_prompt_id" \
  --arg model_id "$model_id" \
  --arg old_resolver_md5 "$old_resolver_md5" \
  --arg shared_skill_md5 "$shared_skill_md5" \
  --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{old_prompt_id:$old_prompt_id,model_id:$model_id,old_resolver_md5:$old_resolver_md5,shared_skill_md5:$shared_skill_md5,captured_at:$captured_at}' \
  > "$BACKUP_FILE"
chmod 600 "$BACKUP_FILE"

jq \
  --rawfile plan "$BASE_DIR/workflow-code/PlanValidationCode.java" \
  --rawfile batch "$BASE_DIR/workflow-code/CurrentBatchCode.java" \
  --rawfile linkItems "$BASE_DIR/workflow-code/LinkItemsCode.java" \
  --rawfile linkMerge "$BASE_DIR/workflow-code/LinkMergeCode.java" \
  --rawfile answerContext "$BASE_DIR/workflow-code/AnswerContextCode.java" \
  --rawfile finalResult "$BASE_DIR/workflow-code/FinalResultCode.java" \
  '(.nodes[]|select(.id=="validate-plan").config.codeSource)=$plan |
   (.nodes[]|select(.id=="current-batch").config.codeSource)=$batch |
   (.nodes[]|select(.id=="link-items").config.codeSource)=$linkItems |
   (.nodes[]|select(.id=="merge-links").config.codeSource)=$linkMerge |
   (.nodes[]|select(.id=="answer-context").config.codeSource)=$answerContext |
   (.nodes[]|select(.id=="final-result").config.codeSource)=$finalResult' \
  "$BASE_DIR/workflow.json" > "$TMP_WORKFLOW"

b64_file() { base64 < "$1" | tr -d '\n'; }
b64_text() { printf '%s' "$1" | base64 | tr -d '\n'; }

SEARCH_CODE=$(b64_file "$BASE_DIR/TenderHighRecallSearchTool.java")
RESOLVER_CODE=$(b64_file "$BASE_DIR/TenderSourceUrlResolverV2Tool.java")
GATEWAY_CODE=$(b64_file "$BASE_DIR/CommercialTenderHighRecallWorkflowTool.java")
PROMPT_CONTENT=$(b64_file "$BASE_DIR/prompt.md")
WORKFLOW_CONFIG=$(b64_file "$TMP_WORKFLOW")
SKILL_MAIN=$(b64_file "$BASE_DIR/../../../.codex/skills/tender-high-recall-search/SKILL.md")
SKILL_PLAN=$(b64_file "$BASE_DIR/../../../.codex/skills/tender-high-recall-search/references/query-plan.md")
SKILL_RANK=$(b64_file "$BASE_DIR/../../../.codex/skills/tender-high-recall-search/references/retrieval-ranking.md")
SKILL_OUTPUT=$(b64_file "$BASE_DIR/../../../.codex/skills/tender-high-recall-search/references/output-continuation.md")
SEARCH_INPUT=$(b64_text '[{"name":"query_plan","type":"object","required":true,"description":"经校验的 tender-query-plan-v1","defaultValue":"{}"},{"name":"prior_state","type":"object","required":false,"description":"上一轮 continuationState","defaultValue":"{}"}]')
RESOLVER_INPUT=$(b64_text '[{"name":"items","type":"array","required":true,"description":"0至20条记录；每项必须含 record_key、title，可含 bid_id、uniq_key、aggregate_url、source_url","defaultValue":"[]"}]')
GATEWAY_INPUT=$(b64_text '[{"name":"question","type":"string","required":true,"description":"用户当前的完整检索问题或连续追问","defaultValue":""},{"name":"prior_state","type":"object","required":false,"description":"上一轮工具返回的 continuationState","defaultValue":"{}"},{"name":"company_profile","type":"object","required":false,"description":"可选企业画像","defaultValue":"{}"}]')

{
  printf '%s\n' 'START TRANSACTION;'
  printf "INSERT INTO tool_config (id,name,tool_id,description,category,tool_type,input_schema,output_schema,class_path,language,code,need_confirm,enabled,version,created_by,updated_by,tenant_id,scope_type) VALUES
    ($SEARCH_TOOL_ID,'商业标书高召回检索执行器','execute_tender_high_recall_v1','工作流专用：确定性执行多轮检索、完整分页、去重、生命周期归并和A/B/C分层。','招投标','CUSTOM',CONVERT(FROM_BASE64('$SEARCH_INPUT') USING utf8mb4),NULL,NULL,'JAVA',CONVERT(FROM_BASE64('$SEARCH_CODE') USING utf8mb4),0,1,'1.0.0',1111111111111111111,1111111111111111111,1,'TENANT')
    ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description),input_schema=VALUES(input_schema),code=VALUES(code),enabled=1,version=VALUES(version),updated_by=VALUES(updated_by);
  "
  printf "INSERT INTO tool_config (id,name,tool_id,description,category,tool_type,input_schema,output_schema,class_path,language,code,need_confirm,enabled,version,created_by,updated_by,tenant_id,scope_type) VALUES
    ($RESOLVER_TOOL_ID,'商业标书源链接解析 v2','resolve_tender_source_urls_v2','工作流专用：按record_key批量提取、验证原文链接并提供已校验聚合页回退。','招投标','CUSTOM',CONVERT(FROM_BASE64('$RESOLVER_INPUT') USING utf8mb4),NULL,NULL,'JAVA',CONVERT(FROM_BASE64('$RESOLVER_CODE') USING utf8mb4),0,1,'2.0.0',1111111111111111111,1111111111111111111,1,'TENANT')
    ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description),input_schema=VALUES(input_schema),code=VALUES(code),enabled=1,version=VALUES(version),updated_by=VALUES(updated_by);
  "
  printf "INSERT INTO tool_config (id,name,tool_id,description,category,tool_type,input_schema,output_schema,class_path,language,code,need_confirm,enabled,version,created_by,updated_by,tenant_id,scope_type) VALUES
    ($GATEWAY_TOOL_ID,'商业标书高召回搜索','commercial_tender_high_recall_search','商业标书智能体专用入口：把检索问题和连续状态交给已发布高召回工作流。','招投标','CUSTOM',CONVERT(FROM_BASE64('$GATEWAY_INPUT') USING utf8mb4),NULL,NULL,'JAVA',CONVERT(FROM_BASE64('$GATEWAY_CODE') USING utf8mb4),0,1,'1.0.0',1111111111111111111,1111111111111111111,1,'TENANT')
    ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description),input_schema=VALUES(input_schema),code=VALUES(code),enabled=1,version=VALUES(version),updated_by=VALUES(updated_by);
  "
  printf "INSERT INTO skill_package (id,name,description,category,enabled,created_by,updated_by,tenant_id) VALUES
    ($SKILL_ID,'tender-high-recall-search','商业标书智能体专用的QueryPlan、多轮召回、A/B/C分层和连续查询治理技能。','招投标',1,1111111111111111111,1111111111111111111,1)
    ON DUPLICATE KEY UPDATE description=VALUES(description),category=VALUES(category),enabled=1,updated_by=VALUES(updated_by);
  "
  printf "INSERT INTO skill_file (id,skill_id,file_type,file_name,file_path,content,sort,created_by,updated_by,enabled,tenant_id) VALUES
    (2079122200000000211,$SKILL_ID,'SKILL_MD','SKILL.md','SKILL.md',CONVERT(FROM_BASE64('$SKILL_MAIN') USING utf8mb4),0,1111111111111111111,1111111111111111111,1,1),
    (2079122200000000212,$SKILL_ID,'REFERENCES','query-plan.md','references/query-plan.md',CONVERT(FROM_BASE64('$SKILL_PLAN') USING utf8mb4),10,1111111111111111111,1111111111111111111,1,1),
    (2079122200000000213,$SKILL_ID,'REFERENCES','retrieval-ranking.md','references/retrieval-ranking.md',CONVERT(FROM_BASE64('$SKILL_RANK') USING utf8mb4),20,1111111111111111111,1111111111111111111,1,1),
    (2079122200000000214,$SKILL_ID,'REFERENCES','output-continuation.md','references/output-continuation.md',CONVERT(FROM_BASE64('$SKILL_OUTPUT') USING utf8mb4),30,1111111111111111111,1111111111111111111,1,1)
    ON DUPLICATE KEY UPDATE content=VALUES(content),file_path=VALUES(file_path),sort=VALUES(sort),enabled=1,updated_by=VALUES(updated_by);
  "
  printf "INSERT INTO system_prompt_template (id,category,name,description,content,enabled,created_by,updated_by,tenant_id) VALUES
    ($PROMPT_ID,'招投标','商业标书智能体-高召回','商业标书智能体专用高召回工作流路由提示词',CONVERT(FROM_BASE64('$PROMPT_CONTENT') USING utf8mb4),1,1111111111111111111,1111111111111111111,1)
    ON DUPLICATE KEY UPDATE description=VALUES(description),content=VALUES(content),enabled=1,updated_by=VALUES(updated_by);
  "
  printf "INSERT INTO workflow (id,tenant_id,name,remark,route_id,status,version,config,locked,enabled,created_by,updated_by) VALUES
    ($WORKFLOW_ID,1,'商业标书高召回检索','商业标书智能体专用：多轮完整分页、去重分层、当前批次链接强制解析。','commercial-tender-high-recall-v1','DRAFT','0',CONVERT(FROM_BASE64('$WORKFLOW_CONFIG') USING utf8mb4),0,1,1111111111111111111,1111111111111111111)
    ON DUPLICATE KEY UPDATE name=VALUES(name),remark=VALUES(remark),route_id=VALUES(route_id),status='DRAFT',config=VALUES(config),locked=0,enabled=1,updated_by=VALUES(updated_by);
  "
  printf "DELETE FROM agent_workflows WHERE workflow_id=$WORKFLOW_ID;\n"
  printf "DELETE FROM skill_tools WHERE skill_id=$SKILL_ID;\n"
  printf "DELETE FROM agent_tools WHERE tool_id IN ($SEARCH_TOOL_ID,$RESOLVER_TOOL_ID);\n"
  printf "DELETE FROM agent_tools WHERE tool_id=$GATEWAY_TOOL_ID;\n"
  printf "DELETE FROM agent_skill_packages WHERE skill_package_id=$SKILL_ID;\n"
  printf "INSERT INTO agent_tools (id,agent_definition_id,tool_id,tenant_id) SELECT 2079122200000000501,id,$GATEWAY_TOOL_ID,tenant_id FROM agent_definition WHERE agent_code='$AGENT_CODE' AND tenant_id=1;\n"
  printf "INSERT INTO agent_skill_packages (id,agent_definition_id,skill_package_id,tenant_id) SELECT 2079122200000000502,id,$SKILL_ID,tenant_id FROM agent_definition WHERE agent_code='$AGENT_CODE' AND tenant_id=1;\n"
  printf "UPDATE agent_definition SET system_prompt_template_id=$PROMPT_ID,follow_template=1,updated_by=1111111111111111111 WHERE agent_code='$AGENT_CODE' AND tenant_id=1;\n"
  printf '%s\n' 'COMMIT;'
} | docker exec -i "$MYSQL_CONTAINER" sh -lc 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" apboa_next' >/dev/null

admin_hash=$(mysql_query "SELECT password FROM account WHERE username='admin' LIMIT 1")
login_payload=$(jq -nc --arg password "$admin_hash" '{username:"admin",password:$password,tenantId:1}')
login_response=$(curl -sS -H 'Content-Type: application/json' -d "$login_payload" "$API_BASE/api/auth/login")
token=$(printf '%s' "$login_response" | jq -r '.data.accessToken // empty')
if [[ -z "$token" ]]; then
  echo "ERROR could not obtain local application token" >&2
  exit 1
fi

validation=$(curl -sS -X POST -H "Authorization: Bearer $token" "$API_BASE/api/workflow/$WORKFLOW_ID/validate")
if [[ "$(printf '%s' "$validation" | jq -r '.code')" != 200 || "$(printf '%s' "$validation" | jq -r '.data.valid')" != true ]]; then
  printf '%s' "$validation" | jq '{code,msg,data}' >&2
  echo "ERROR workflow validation failed" >&2
  exit 1
fi

publish=$(curl -sS -X POST -H "Authorization: Bearer $token" "$API_BASE/api/workflow/$WORKFLOW_ID/publish?remark=high-recall-v1")
if [[ "$(printf '%s' "$publish" | jq -r '.code')" != 200 ]]; then
  printf '%s' "$publish" | jq '{code,msg}' >&2
  echo "ERROR workflow publish failed" >&2
  exit 1
fi

if [[ "${KACP_REFRESH_RUNTIME_CACHE:-1}" = 1 ]] \
    && docker ps --format '{{.Names}}' | grep -qx 'k-acp-runtime'; then
  docker restart k-acp-runtime >/dev/null
  runtime_ready=0
  for _ in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:23061/actuator/health >/dev/null 2>&1; then
      runtime_ready=1
      break
    fi
    sleep 1
  done
  if [[ "$runtime_ready" != 1 ]]; then
    echo "ERROR k-acp-runtime did not become healthy after workflow cache refresh" >&2
    exit 1
  fi
fi

echo "APPLIED commercial-tender-high-recall v1"

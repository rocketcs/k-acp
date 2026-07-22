#!/usr/bin/env bash
set -euo pipefail

BASE_DIR=$(cd "$(dirname "$0")" && pwd -P)
REPO_DIR=$(cd "$BASE_DIR/../../.." && pwd -P)
MODE=${1:---static}
MYSQL_CONTAINER=${KACP_MYSQL_CONTAINER:-k-acp-mysql}
API_BASE=${KACP_API_BASE:-http://127.0.0.1:23080}
BACKUP_FILE=${KACP_BACKUP_DIR:-$HOME/.k-acp-backups}/commercial-tender-high-recall-before.json

jackson_cp="$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.18.3/jackson-databind-2.18.3.jar:$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.18.3/jackson-core-2.18.3.jar:$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.18.3/jackson-annotations-2.18.3.jar"
tool_cp="$REPO_DIR/engine/target/classes:$REPO_DIR/workflow/target/classes:$REPO_DIR/common/target/classes:$REPO_DIR/common-base/target/classes:$jackson_cp"
code_cp="$REPO_DIR/workflow/target/classes:$REPO_DIR/common/target/classes:$REPO_DIR/common-base/target/classes:$jackson_cp"

mysql_query() {
  docker exec "$MYSQL_CONTAINER" sh -lc \
    'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" apboa_next -N -s -e "$1"' sh "$1" 2>/dev/null
}

compile_tools() {
  rm -rf /tmp/kacp-tender-tool-tests
  mkdir -p /tmp/kacp-tender-tool-tests
  javac -proc:none -encoding UTF-8 -cp "$tool_cp" -d /tmp/kacp-tender-tool-tests \
    "$BASE_DIR/TenderHighRecallSearchTool.java" \
    "$BASE_DIR/TenderSourceUrlResolverV2Tool.java" \
    "$BASE_DIR/tests/ResolverFixtureTest.java"
  java -cp "/tmp/kacp-tender-tool-tests:$tool_cp" ResolverFixtureTest

  deps=$(find "$HOME/.m2/repository" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -type f | tr '\n' ':')
  gateway_cp="$REPO_DIR/engine/target/classes:$REPO_DIR/workflow/target/classes:$REPO_DIR/common/target/classes:$REPO_DIR/common-base/target/classes:$REPO_DIR/biz/biz-workflow/target/classes:$deps"
  rm -rf /tmp/kacp-tender-gateway-compile
  mkdir -p /tmp/kacp-tender-gateway-compile
  javac -proc:none -encoding UTF-8 -cp "$gateway_cp" -d /tmp/kacp-tender-gateway-compile \
    "$BASE_DIR/CommercialTenderHighRecallWorkflowTool.java"
  echo 'PASS tender custom tools compile'
}

compile_workflow_code() {
  for source in "$BASE_DIR"/workflow-code/*.java; do
    name=$(basename "$source" .java)
    target="/tmp/kacp-workflow-code-$name"
    rm -rf "$target"
    mkdir -p "$target/src" "$target/classes"
    cp "$source" "$target/src/CodeExecute.java"
    javac -proc:none -encoding UTF-8 -cp "$code_cp" -d "$target/classes" "$target/src/CodeExecute.java"
  done
  if grep -REn '^import (java\.io|java\.nio|java\.net|java\.security|java\.util\.concurrent|javax\.net)' "$BASE_DIR/workflow-code"; then
    echo 'ERROR workflow CODE source imports a restricted package' >&2
    exit 1
  fi
  echo 'PASS workflow code compile and security precheck'
}

workflow_checks() {
  jq empty "$BASE_DIR/workflow.json"
  jq -e '. as $w |
    ([$w.nodes[]|select(.type=="START")]|length==1) and
    ([$w.nodes[]|select(.type=="END")]|length==1) and
    ([$w.nodes[]|select(.id=="query-planner" and .type=="AGENT" and .config.structuredOutputEnabled==true)]|length==1) and
    ([$w.nodes[]|select(.id=="execute-search" and .type=="TOOL_EXECUTE" and .config.toolId=="2079122200000000101")]|length==1) and
    ([$w.nodes[]|select(.id=="resolve-links" and .type=="TOOL_EXECUTE" and .config.toolId=="2079122200000000102")]|length==1) and
    ([$w.nodes[]|select(.id=="answer-generator")|.inputConfigs[]|select(.nodeId=="answer-context")]|length==1) and
    ([$w.nodes[]|select(.id=="answer-generator")|.config.toolIds[]?]|length==0)' "$BASE_DIR/workflow.json" >/dev/null
  node_count=$(jq '.nodes|length' "$BASE_DIR/workflow.json")
  edge_count=$(jq '.edges|length' "$BASE_DIR/workflow.json")
  [[ "$edge_count" -eq $((node_count - 1)) ]] || { echo 'ERROR workflow is not a single forced chain' >&2; exit 1; }
  echo 'PASS workflow graph'
  echo 'PASS forced resolver'
  echo 'PASS answer context isolation'
}

static_checks() {
  compile_tools
  compile_workflow_code
  workflow_checks
  test "$(jq -r '.matrix.expected_case_count' "$BASE_DIR/fixtures/resolver-cases.json")" = 200
  for term in query_plan_version hard_filters concept_groups continuationState record_key; do
    grep -Rqs "$term" "$REPO_DIR/.codex/skills/tender-high-recall-search" || { echo "ERROR skill contract missing $term" >&2; exit 1; }
  done
  grep -qs 'commercial_tender_high_recall_search' "$BASE_DIR/prompt.md"
  if grep -REn '(zlbx_[A-Za-z0-9]+|X-API-Key[[:space:]]*:[[:space:]]*[^$]|Bearer[[:space:]]+[A-Za-z0-9._-]{20,})' "$BASE_DIR"; then
    echo 'ERROR possible credential literal found' >&2
    exit 1
  fi
  echo 'PASS static application-layer checks'
}

live_checks() {
  [[ -f "$BACKUP_FILE" ]] || { echo "ERROR backup not found: $BACKUP_FILE" >&2; exit 1; }
  [[ "$(mysql_query "SELECT COUNT(*) FROM tool_config WHERE id IN (2079122200000000101,2079122200000000102,2079122200000000103) AND enabled=1")" = 3 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM skill_package WHERE id=2079122200000000201 AND enabled=1")" = 1 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM skill_file WHERE skill_id=2079122200000000201 AND enabled=1")" = 4 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM workflow WHERE id=2079122200000000401 AND status='PUBLISHED' AND enabled=1")" = 1 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM workflow_version WHERE workflow_id='2079122200000000401'")" -ge 1 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM agent_tools at JOIN agent_definition a ON a.id=at.agent_definition_id WHERE at.tool_id=2079122200000000103 AND a.agent_code='default-tender'")" = 1 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM agent_tools WHERE tool_id IN (2079122200000000101,2079122200000000102)")" = 0 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM agent_workflows WHERE workflow_id=2079122200000000401")" = 0 ]]
  [[ "$(mysql_query "SELECT COUNT(*) FROM agent_skill_packages asp JOIN agent_definition a ON a.id=asp.agent_definition_id WHERE asp.skill_package_id=2079122200000000201 AND a.agent_code='default-tender'")" = 1 ]]
  [[ "$(mysql_query "SELECT system_prompt_template_id FROM agent_definition WHERE agent_code='default-tender' AND tenant_id=1")" = 2079122200000000301 ]]
  [[ "$(mysql_query "SELECT model_config_id FROM agent_definition WHERE agent_code='default-tender' AND tenant_id=1")" = "$(jq -r '.model_id' "$BACKUP_FILE")" ]]
  [[ "$(mysql_query "SELECT MD5(code) FROM tool_config WHERE tool_id='resolve_tender_source_urls' AND tenant_id=1")" = "$(jq -r '.old_resolver_md5' "$BACKUP_FILE")" ]]
  shared_md5=$(mysql_query "SELECT MD5(GROUP_CONCAT(CONCAT(file_path,':',MD5(content)) ORDER BY file_path SEPARATOR '|')) FROM skill_file WHERE skill_id=(SELECT id FROM skill_package WHERE name='tender-search' AND tenant_id=1 LIMIT 1)")
  [[ "$shared_md5" = "$(jq -r '.shared_skill_md5' "$BACKUP_FILE")" ]]
  echo 'PASS live bindings and shared-resource isolation'
}

login_token() {
  admin_hash=$(mysql_query "SELECT password FROM account WHERE username='admin' LIMIT 1")
  payload=$(jq -nc --arg password "$admin_hash" '{username:"admin",password:$password,tenantId:1}')
  curl -sS -H 'Content-Type: application/json' -d "$payload" "$API_BASE/api/auth/login" | jq -r '.data.accessToken // empty'
}

functional_check() {
  token=$(login_token)
  [[ -n "$token" ]] || { echo 'ERROR local login failed' >&2; exit 1; }
  payload=$(jq -nc '{params:[
    {name:"question",value:"查询广东最近一个月关于服务器采购的招标项目"},
    {name:"priorState",value:{}},
    {name:"companyProfile",value:{}}
  ]}')
  response=$(curl -sS -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
    -d "$payload" "$API_BASE/api/runtime/workflow/2079122200000000401/run")
  code=$(printf '%s' "$response" | jq -r '.code')
  status=$(printf '%s' "$response" | jq -r '.data.run.status // empty')
  if [[ "$code" != 200 || "$status" != SUCCESS ]]; then
    printf '%s' "$response" | jq '{code,msg,status:.data.run.status,error:.data.run.error,nodes:[.data.nodeExecutions[]?|{nodeId,status,error}]}' >&2
    exit 1
  fi
  printf '%s' "$response" | jq -e '
    .data.output.answer != null and
    .data.output.queryPlan.query_plan_version == "tender-query-plan-v1" and
    (.data.output.resultStatus.displayed_count <= 20) and
    (.data.output.continuationState.stable_keys_sha256|length==64)' >/dev/null
  echo 'PASS functional high-recall workflow run'
}

case "$MODE" in
  --compile-only) compile_tools; compile_workflow_code ;;
  --workflow-only) workflow_checks ;;
  --static) static_checks ;;
  --live) live_checks ;;
  --functional) functional_check ;;
  --all) static_checks; live_checks; functional_check ;;
  *) echo "Usage: $0 --compile-only|--workflow-only|--static|--live|--functional|--all" >&2; exit 2 ;;
esac

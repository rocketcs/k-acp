# Wenbiao Key Pool Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Persist successful agent-registration results in the fixed Wenbiao key-pool file and rotate the profile used by tender search after one explicit authentication failure.

**Architecture:** A tested runtime storage helper owns the fixed pool file, validation, deduplication, file locking, and atomic replacement. A narrow dynamic import tool invokes that helper from the registration loop. The existing pool tool and tender-search dynamic tool use the same pool and profile, with one auth-only rotation/retry.

**Tech Stack:** Java 21, Jackson, JUnit 5, K-ACP dynamic Java tools, Docker Compose, local MySQL workflow/tool configuration.

## Global Constraints

- Use only /app/.apboa/secrets/wenbiao_agent-key-pool.json; no workflow input or tool parameter can choose a file path.
- Never return, log, embed in workflow JSON, or include in an error message a raw API key.
- New registrations are STANDBY; imports never change an existing ACTIVE key.
- Deduplicate by API-key SHA-256 fingerprint and provider device_id.
- Use one lock file and a same-directory temporary-file atomic replace for all mutations.
- Update /app/.apboa/secrets/http-profiles/zhiliao.json, the profile actually read by execute_tender_high_recall_v1.
- Rotate only for AUTHENTICATION_FAILED; retry the provider request at most once. Do not rotate for timeout, DNS, malformed response, or 5xx errors.
- Do not run the registration workflow during implementation or testing.

## File Structure

- Create runner-runtime/src/main/java/com/hxh/apboa/runtime/wenbiao/WenbiaoKeyPoolStore.java: fixed-path pool loading, import, validation, locking, and atomic writes.
- Create runner-runtime/src/test/java/com/hxh/apboa/runtime/wenbiao/WenbiaoKeyPoolStoreTest.java: temporary-filesystem tests for import, deduplication, active preservation, and secret-safe results.
- Modify runner-runtime/pom.xml: declare the Spring Boot test dependency for the new runtime tests.
- Create runner-runtime/src/main/java/com/hxh/apboa/runtime/wenbiao/WenbiaoRotationPolicy.java: shared auth-only rotation policy and effective profile path.
- Create runner-runtime/src/test/java/com/hxh/apboa/runtime/wenbiao/WenbiaoRotationPolicyTest.java: policy contract.
- Create docs/operations/AppendWenbiaoRegistrationResultTool.java: dynamic importer source.
- Modify docs/operations/WenbiaoAgentKeyPoolTool.java: update the real zhiliao.json profile and auth rotation eligibility.
- Create docs/operations/TenderHighRecallSearchTool.java: dynamic source export with one rotation/retry.
- Create docs/operations/wenbiao-key-pool-tools.json: credential-free tool schemas.
- Modify local tool and workflow configurations only after their sources compile.

---

### Task 1: Implement and test fixed-path pool storage

**Files:**

- Create: runner-runtime/src/main/java/com/hxh/apboa/runtime/wenbiao/WenbiaoKeyPoolStore.java
- Create: runner-runtime/src/test/java/com/hxh/apboa/runtime/wenbiao/WenbiaoKeyPoolStoreTest.java
- Modify: runner-runtime/pom.xml

**Consumes:** A JSON pool root containing keys.

**Produces:** WenbiaoKeyPoolStore.append(Registration) and a map-safe AppendResult.

- [ ] **Step 1: Write the failing import/deduplication test**

Add the existing managed Spring Boot test dependency before adding the test source:

~~~xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
~~~

~~~java
@Test
void appendAddsStandbyThenDeduplicatesWithoutChangingActive() throws Exception {
    WenbiaoKeyPoolStore store = WenbiaoKeyPoolStore.forDirectory(tempDir);
    store.initialize("active-key");
    var first = store.append(new Registration("zlbx__test_key_1234567890", "device-a", Map.of("agent_kind", "test"), 100, true, "created"));
    var duplicateKey = store.append(new Registration("zlbx__test_key_1234567890", "device-b", Map.of(), 100, true, "created"));
    var duplicateDevice = store.append(new Registration("zlbx__another_test_key_123", "device-a", Map.of(), 100, true, "created"));
    assertThat(first.result()).isEqualTo("added");
    assertThat(duplicateKey.result()).isEqualTo("duplicate");
    assertThat(duplicateDevice.result()).isEqualTo("duplicate");
    assertThat(store.readPool().withArray("keys")).hasSize(2);
    assertThat(store.activeEntry().path("api_key").asText()).isEqualTo("active-key");
    assertThat(store.readPool().withArray("keys").get(1).path("state").asText()).isEqualTo("STANDBY");
}
~~~

- [ ] **Step 2: Write the failing no-op and secret-safe result test**

~~~java
@Test
void invalidRegistrationDoesNotWriteOrExposeTheKey() throws Exception {
    WenbiaoKeyPoolStore store = WenbiaoKeyPoolStore.forDirectory(tempDir);
    store.initialize("active-key");
    var result = store.append(new Registration("not-a-provider-key", "", Map.of(), 0, false, "failed"));
    assertThat(result.success()).isFalse();
    assertThat(result.errorCode()).isEqualTo("INVALID_REGISTRATION_RESULT");
    assertThat(result.toMap().toString()).doesNotContain("not-a-provider-key");
    assertThat(store.readPool().withArray("keys")).hasSize(1);
}
~~~

- [ ] **Step 3: Run the tests to establish the failure**

Run:

~~~bash
mvn -pl runner-runtime -am -Dtest=WenbiaoKeyPoolStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~

Expected: compilation failure because WenbiaoKeyPoolStore and Registration do not exist.

- [ ] **Step 4: Implement the fixed-path helper**

~~~java
public static WenbiaoKeyPoolStore production() {
    return new WenbiaoKeyPoolStore(Paths.get(".apboa", "secrets").toAbsolutePath().normalize());
}

public AppendResult append(Registration registration) throws IOException {
    if (!registration.valid()) return AppendResult.invalid();
    try (FileChannel channel = FileChannel.open(lockFile(), CREATE, WRITE);
         FileLock ignored = channel.lock()) {
        ObjectNode pool = loadPool();
        if (containsFingerprint(pool, fingerprint(registration.apiKey())) || containsDevice(pool, registration.deviceId())) {
            return AppendResult.duplicate(poolSize(pool), standbyCount(pool), fingerprint(registration.apiKey()), registration.deviceId());
        }
        ObjectNode entry = pool.withArray("keys").addObject();
        entry.put("api_key", registration.apiKey());
        entry.put("device_id", registration.deviceId());
        entry.set("context", JSON.valueToTree(registration.context()));
        entry.put("registered_at", Instant.now().toString());
        entry.put("remaining_calls", registration.remainingCalls());
        entry.put("is_new", registration.isNew());
        entry.put("registration_message", registration.message());
        entry.put("state", "STANDBY");
        entry.put("last_checked_at", ""); entry.put("last_provider_status", ""); entry.put("last_rotated_at", "");
        writeJsonAtomically(poolFile(), pool);
        return AppendResult.added(poolSize(pool), standbyCount(pool), fingerprint(registration.apiKey()), registration.deviceId());
    }
}
~~~

poolFile() always resolves to secretDirectory.resolve("wenbiao_agent-key-pool.json"); writeJsonAtomically creates a same-directory temporary file, applies POSIX 0600 permissions where supported, then uses ATOMIC_MOVE with REPLACE_EXISTING fallback. AppendResult.toMap() may contain only success, action, result, error_code, device_id, api_key_fingerprint, pool_size, and standby_count.

- [ ] **Step 5: Run the test and commit**

Run:

~~~bash
mvn -pl runner-runtime -am -Dtest=WenbiaoKeyPoolStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~

Expected: PASS.

~~~bash
git add runner-runtime/pom.xml runner-runtime/src/main/java/com/hxh/apboa/runtime/wenbiao/WenbiaoKeyPoolStore.java runner-runtime/src/test/java/com/hxh/apboa/runtime/wenbiao/WenbiaoKeyPoolStoreTest.java
git commit -m "feat: add Wenbiao key pool storage"
~~~

### Task 2: Add and validate the registration-result import tool

**Files:**

- Create: docs/operations/AppendWenbiaoRegistrationResultTool.java
- Create: docs/operations/wenbiao-key-pool-tools.json

**Consumes:** The raw registration response and one generated context.

**Produces:** Custom tool ID append_wenbiao_registration_result, which writes only successful results.

- [ ] **Step 1: Create the dynamic tool source around the tested helper**

~~~java
public final class AppendWenbiaoRegistrationResultTool implements IDynamicAgentTool {
    @Override public Object execute(AgentContext context, Map<String, Object> params) {
        Map<String, Object> response = map(params.get("response"));
        Map<String, Object> payload = map(response.getOrDefault("data", response));
        Registration registration = new Registration(
            text(payload, "api_key"), text(payload, "device_id"), map(params.get("context")),
            integer(payload.get("remaining_calls")), bool(payload.get("is_new")), text(payload, "message"));
        if (!bool(payload.get("success")) || registration.apiKey().isBlank() || registration.deviceId().isBlank()) {
            return Map.of("success", false, "action", "append", "result", "skipped", "error_code", "INVALID_REGISTRATION_RESULT");
        }
        return WenbiaoKeyPoolStore.production().append(registration).toMap();
    }
}
~~~

The complete source imports WenbiaoKeyPoolStore and its Registration, accepts only response and context, has no HTTP client or path parameter, and returns no raw key.

- [ ] **Step 2: Record credential-free schema and output contract**

~~~json
{
  "tool_id": "append_wenbiao_registration_result",
  "input_schema": [
    {"name":"response","type":"object","required":true},
    {"name":"context","type":"object","required":true}
  ],
  "output_schema": {
    "success":"boolean",
    "action":"append",
    "result":"added|duplicate|skipped",
    "api_key_fingerprint":"string"
  }
}
~~~

- [ ] **Step 3: Register and compile-test the tool without writing a credential**

Create one enabled CUSTOM/JAVA local tool named 追加注册结果到 Wenbiao Key Pool, version 1.0.0, with the source and schema above. Invoke it with:

~~~json
{"response":{"success":false},"context":{}}
~~~

Expected:

~~~json
{"success":false,"action":"append","result":"skipped","error_code":"INVALID_REGISTRATION_RESULT"}
~~~

The pool file must remain unchanged.

- [ ] **Step 4: Commit tool source and schema**

~~~bash
git add docs/operations/AppendWenbiaoRegistrationResultTool.java docs/operations/wenbiao-key-pool-tools.json
git commit -m "feat: add registration result key pool importer"
~~~

### Task 3: Wire importing into the registration workflow

**Files:**

- Modify: local workflow 2080510403394859009
- Create: docs/operations/agent-registration-key-pool-workflow.json

**Consumes:** register_context.output and loop context.

**Produces:** A safe per-iteration summary and one persisted standby key for every successful registration.

- [ ] **Step 1: Insert the import tool after HTTP**

Replace the loop subgraph with:

~~~text
register_context (HTTP_EXTERNAL)
  -> append_registration_result (TOOL_EXECUTE)
  -> process_registration_result (CODE)
~~~

Bind response to register_context.output and context to the loop variable. The importer tool ID must be the local ID created in Task 2.

- [ ] **Step 2: Replace the shaping CODE with a secret-safe result**

~~~java
Map<String,Object> imported = asMap(inputs.get("imported"));
Map<String,Object> result = new LinkedHashMap<>();
result.put("index", index + 1);
result.put("success", Boolean.TRUE.equals(imported.get("success")) && "added".equals(imported.get("result")));
result.put("device_id", imported.get("device_id"));
result.put("api_key_fingerprint", imported.get("api_key_fingerprint"));
result.put("pool_result", imported.get("result"));
result.put("pool_size", imported.get("pool_size"));
result.put("standby_count", imported.get("standby_count"));
result.put("delay_seconds", index < total - 1 && Boolean.TRUE.equals(imported.get("success")) ? 1 + (int)(Math.random() * 30) : 0);
return result;
~~~

Bind imported from the importer output. Remove response and api_key from the CODE and END outputs. Keep maxIterations: 1; changing the configured count to ten is safe, but executing it remains an explicit later action.

- [ ] **Step 3: Validate without calling the provider**

Run:

~~~bash
curl -sS -X POST http://127.0.0.1:23080/web-api/workflow/2080510403394859009/validate
~~~

Expected:

~~~json
{"code":200,"success":true,"data":{"valid":true,"errors":[],"warnings":[]},"msg":"操作成功"}
~~~

- [ ] **Step 4: Export only the definition and commit**

~~~bash
git add docs/operations/agent-registration-key-pool-workflow.json
git commit -m "feat: persist registration workflow results in key pool"
~~~

### Task 4: Make tender search rotate the effective profile once

**Files:**

- Create: runner-runtime/src/main/java/com/hxh/apboa/runtime/wenbiao/WenbiaoRotationPolicy.java
- Create: runner-runtime/src/test/java/com/hxh/apboa/runtime/wenbiao/WenbiaoRotationPolicyTest.java
- Modify: docs/operations/WenbiaoAgentKeyPoolTool.java
- Create: docs/operations/TenderHighRecallSearchTool.java

**Consumes:** Explicit provider failure AUTHENTICATION_FAILED.

**Produces:** A single active-profile update of zhiliao.json, then at most one provider retry.

- [ ] **Step 1: Write the failing policy test**

~~~java
@Test
void onlyAuthenticationFailureRotatesAndTheActiveProfileIsZhiliao() {
    assertThat(WenbiaoRotationPolicy.rotatable("AUTHENTICATION_FAILED")).isTrue();
    assertThat(WenbiaoRotationPolicy.rotatable("TIMEOUT")).isFalse();
    assertThat(WenbiaoRotationPolicy.profileFileName()).isEqualTo("zhiliao.json");
}
~~~

- [ ] **Step 2: Implement the shared policy and update the Key Pool source**

~~~java
public final class WenbiaoRotationPolicy {
    public static boolean rotatable(String code) { return "AUTHENTICATION_FAILED".equals(code); }
    public static String profileFileName() { return "zhiliao.json"; }
}
~~~

In WenbiaoAgentKeyPoolTool, use:

~~~java
private static final Path PROFILE_FILE = SECRET_DIR.resolve("http-profiles/zhiliao.json");
private static final Set<String> ROTATABLE = Set.of("AUTHENTICATION_FAILED");
~~~

When rotation selects a standby key, write its X-API-Key to zhiliao.json, update the active-key file, and replace the pool JSON while holding the fixed pool lock. Return fingerprints and status only.

- [ ] **Step 3: Add the bounded retry to the tender-search dynamic source**

~~~java
Map<String,Object> first = executePlan(plan, loadZhiliaoProfile());
if (!"AUTHENTICATION_FAILED".equals(first.get("incomplete_reason"))) return first;
Map<String,Object> rotation = rotateActiveKey("AUTHENTICATION_FAILED");
if (!Boolean.TRUE.equals(rotation.get("success"))) return poolExhausted(first, rotation);
Map<String,Object> retried = executePlan(plan, loadZhiliaoProfile());
retried.put("key_rotation", Map.of("attempted", true, "retry_count", 1,
    "active_fingerprint", rotation.get("active_fingerprint")));
return retried;
~~~

rotateActiveKey loads the registered wenbiao_agent_key_pool tool through ToolInstanceLoadFactory with only action=rotate and provider_status=AUTHENTICATION_FAILED. It must not pass a credential, contact the registration endpoint, or rotate after the retry.

- [ ] **Step 4: Run tests and compile both dynamic sources**

Run:

~~~bash
mvn -pl runner-runtime -am -Dtest=WenbiaoKeyPoolStoreTest,WenbiaoRotationPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
~~~

Then save/compile the updated local wenbiao_agent_key_pool and execute_tender_high_recall_v1 sources through the local Tool UI. Compilation must succeed before replacing either existing source.

- [ ] **Step 5: Verify only safe paths and commit**

Run Key Pool status; it must report state counts and fingerprints only. Invoke mark_failure with TIMEOUT; expected output is NON_ROTATABLE_STATUS. Do not call rotate, because it contacts the provider balance endpoint.

~~~bash
graphify update .
git add docs/operations/WenbiaoAgentKeyPoolTool.java docs/operations/TenderHighRecallSearchTool.java runner-runtime/src/main/java/com/hxh/apboa/runtime/wenbiao/WenbiaoRotationPolicy.java runner-runtime/src/test/java/com/hxh/apboa/runtime/wenbiao/WenbiaoRotationPolicyTest.java graphify-out
git commit -m "feat: rotate tender search key after auth failure"
~~~

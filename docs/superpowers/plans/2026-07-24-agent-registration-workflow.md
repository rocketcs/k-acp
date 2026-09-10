# Agent Registration Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and run a local workflow that calls `scripts/generate_agent_context.py` for ten contexts, registers them serially with a random 1–30 second wait, and returns complete results and all successful keys.

**Architecture:** Python and the read-only generator script are exposed only to the local runtime. A generator custom tool runs the script and returns its list; a result custom tool converts an HTTP response to a stable result and sleeps after each successful non-final item. The visual workflow is `START → generator → LOOP(HTTP → processor) → summary → END`.

**Tech Stack:** Docker Compose, Java 21, Python 3, K-ACP custom tools, workflow HTTP/loop/code nodes.

## Global Constraints

- Target only `http://127.0.0.1:23080` and its local Docker services.
- Run `scripts/generate_agent_context.py --number 10 --output /app/data/agent_context.json`.
- Mount only `/app/scripts` read-only; write JSON only to `/app/data`.
- HTTP calls are serial; the workflow is the only component allowed to call the registration endpoint.
- The definition stores no key or response body. Results are output at run time only.

---

### Task 1: Local runtime generator support

**Files:**
- Modify: `docker/runtime/Dockerfile`
- Modify: `docker/docker-compose-kacp-local.yml`

**Produces:** Python 3 plus `/app/scripts/generate_agent_context.py` in `k-acp-runtime`.

- [ ] **Step 1: Establish the failing test**

Run:

```bash
docker exec --user 1001:1001 k-acp-runtime sh -lc 'python3 --version; test -f /app/scripts/generate_agent_context.py'
```

Expected: non-zero because neither Python nor the script exists in the current runtime.

- [ ] **Step 2: Install Python 3 in the runtime image**

Replace the existing package line in `docker/runtime/Dockerfile` with:

```dockerfile
RUN sed -i 's|http://ports.ubuntu.com/ubuntu-ports|https://mirrors.aliyun.com/ubuntu-ports|g' /etc/apt/sources.list.d/ubuntu.sources && \
    apt-get update && \
    apt-get install -y --no-install-recommends gosu curl python3 && \
    apt-get clean && rm -rf /var/lib/apt/lists/*
```

- [ ] **Step 3: Mount the generator without granting it write access**

Append this under `services.apboa-runtime.volumes` in `docker/docker-compose-kacp-local.yml`:

```yaml
      - ../scripts:/app/scripts:ro
```

- [ ] **Step 4: Recreate just the runtime and verify the contract**

Run:

```bash
docker compose --env-file docker/.env.kacp -f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml up -d --build --force-recreate apboa-runtime
docker exec --user 1001:1001 k-acp-runtime sh -lc 'python3 --version && test -r /app/scripts/generate_agent_context.py && test -w /app/data && python3 /app/scripts/generate_agent_context.py --number 10 --output /app/data/agent_context.json && python3 -c "import json; assert len(json.load(open('''/app/data/agent_context.json'''))) == 10"'
```

Expected: exit status 0 and a ten-element array.

- [ ] **Step 5: Commit**

```bash
git add docker/runtime/Dockerfile docker/docker-compose-kacp-local.yml
git commit -m "feat: expose agent context generator to local runtime"
```

### Task 2: Add the two local custom tools

**Files:**
- Create: local tool `generate_agent_contexts`
- Create: local tool `process_registration_result`
- Create: `docs/operations/agent-registration-workflow-tools.json`

**Interfaces:**
- `generate_agent_contexts(count: integer = 10) -> List<Map<String,Object>>`
- `process_registration_result(response, context, loop_index, total_count = 10) -> Map<String,Object>`

- [ ] **Step 1: Create the generator tool**

At `http://127.0.0.1:23080/web/#/tool` add custom Java tool `generate_agent_contexts` (name “生成 Agent Context”, category “工作流”, version `1.0.0`, no confirmation), with required integer `count` default `10` and this code:

```java
import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.util.*;
import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
import com.hxh.apboa.engine.agui.AgentContext;
public class GenerateAgentContextsTool implements IDynamicAgentTool {
  @Override public Object execute(AgentContext c, Map<String,Object> p) {
    try {
      Object raw=p.get("count"); int count=raw instanceof Number?((Number)raw).intValue():Integer.parseInt(String.valueOf(raw));
      if(count!=10) throw new IllegalArgumentException("count must be exactly 10");
      Path script=Path.of("/app/scripts/generate_agent_context.py"), out=Path.of("/app/data/agent_context.json");
      Process proc=new ProcessBuilder("python3",script.toString(),"--number","10","--output",out.toString()).redirectErrorStream(true).start();
      String log; try(BufferedReader r=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))){log=r.lines().reduce("",(a,b)->a+b+"\n");}
      if(proc.waitFor()!=0) throw new IllegalStateException("generator failed: "+log);
      List records=parse(Files.readString(out,StandardCharsets.UTF_8),List.class);
      if(records.size()!=10) throw new IllegalStateException("generator did not return 10 context objects");
      return records;
    } catch(Exception e) { throw new IllegalStateException("generate_agent_contexts failed",e); }
  }
}
```

- [ ] **Step 2: Create the result processor tool**

In the same page add custom Java tool `process_registration_result` (name “处理注册结果”, category “工作流”, version `1.0.0`, no confirmation). Inputs: optional object `response`; required object `context`; required integer `loop_index`; required integer `total_count` default `10`. Use:

```java
import java.util.*; import java.util.concurrent.ThreadLocalRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
import com.hxh.apboa.engine.agui.AgentContext;
public class ProcessRegistrationResultTool implements IDynamicAgentTool {
  @Override public Object execute(AgentContext c, Map<String,Object> p) {
    Object response=p.get("response"); int index=((Number)p.get("loop_index")).intValue(), total=((Number)p.get("total_count")).intValue();
    JsonNode json=response instanceof JsonNode?(JsonNode)response:response==null?null:parse(toJsonString(response));
    boolean success=json!=null&&json.path("success").asBoolean(false)&&json.hasNonNull("api_key");
    Map<String,Object> r=new LinkedHashMap<>(); r.put("index",index+1); r.put("context",p.get("context")); r.put("response",response);
    r.put("success",success); r.put("api_key",success?json.path("api_key").asText():null); r.put("device_id",json==null?null:json.path("device_id").asText(null));
    r.put("remaining_calls",json!=null&&json.path("remaining_calls").isNumber()?json.path("remaining_calls").numberValue():null);
    r.put("message",json==null?"HTTP request failed or returned no JSON body":json.path("message").asText(null));
    int delay=0; if(success&&index<total-1){delay=ThreadLocalRandom.current().nextInt(1,31);try{Thread.sleep(delay*1000L);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("registration delay interrupted",e);}} r.put("delay_seconds",delay);
    return r;
  }
}
```

- [ ] **Step 3: Test the generator without registration**

Create a temporary draft `START → TOOL_EXECUTE(generate_agent_contexts) → END`, bind `count` to integer `10`, run it, confirm ten contexts, then delete the temporary draft.

- [ ] **Step 4: Record and commit the tool definitions**

Write the two tool schemas and exact code bodies to `docs/operations/agent-registration-workflow-tools.json` and run:

```bash
git add docs/operations/agent-registration-workflow-tools.json
git commit -m "docs: record agent registration workflow tools"
```

### Task 3: Create, validate, and run the visual workflow

**Files:**
- Create: local draft workflow `批量注册 Agent Key（10 次）`
- Create: `docs/operations/agent-registration-workflow.json`

**Output:** `{results, api_keys, success_count, failure_count}`.

- [ ] **Step 1: Create the main topology**

At `http://127.0.0.1:23080/web/#/workflow` create:

```text
START → TOOL_EXECUTE(generate_agent_contexts) → LOOP → CODE(summarize_registration_results) → END
```

Bind `count` to constant integer `10`. Bind generator output to loop input `contexts`. Configure data iteration with `iterateDataSource: contexts`, `itemVariable: context`, `loopVariable: loopIndex`, and `maxIterations: 10`.

- [ ] **Step 2: Add the loop subworkflow**

Create `HTTP_EXTERNAL(register_context) → TOOL_EXECUTE(process_registration_result)`. Set HTTP config to:

```json
{"formatterType":"JACKSON","connectTimeout":10,"readTimeout":30,"writeTimeout":30,"maxRetries":3,"retryStatusCodes":[408,429,500,501,502,503,504],"followRedirects":true,"syncExecute":true,"bodyToObject":true,"request":{"url":"https://ai.zhiliaobiaoxun.com/web-api/internal/auto-register","method":"POST","contentType":"JSON","headers":[{"key":"Content-Type","value":"application/json"}],"pathParams":[],"queryParams":[],"body":"${context}"}}
```

Bind processor inputs `response ← register_context.output`, `context ← VARIABLE(context)`, `loop_index ← VARIABLE(loopIndex)`, and `total_count ← constant 10`.

- [ ] **Step 3: Add deterministic summary and response**

Bind a CODE node input `input ← LOOP.output` and set:

```java
import java.util.*; import com.hxh.apboa.node.code.CodeExecutor;
public class SummarizeRegistrationResults implements CodeExecutor {
  @Override public Object execute(Map<String,Object> i) {
    Object value=i.get("input"); List<?> results=value instanceof List?(List<?>)value:List.of(); List<Object> keys=new ArrayList<>(); int successes=0;
    for(Object x:results) if(x instanceof Map){Object ok=((Map<?,?>)x).get("success"),key=((Map<?,?>)x).get("api_key");if(Boolean.TRUE.equals(ok)&&key!=null){successes++;keys.add(key);}}
    Map<String,Object> s=new LinkedHashMap<>();s.put("results",results);s.put("api_keys",keys);s.put("success_count",successes);s.put("failure_count",results.size()-successes);return s;
  }
}
```

Configure END as Jackson JSON, bind its `input` to the summary output, and set its response template to `${input}`.

- [ ] **Step 4: Validate without a registration call**

Save and validate the draft. Expected: valid with no node errors. Export only the definition to `docs/operations/agent-registration-workflow.json`; do not export a runtime result.

- [ ] **Step 5: Run once, inspect, publish, and commit**

Run the saved workflow once. Assert `results.length == 10`, `api_keys.length == success_count`, and `success_count + failure_count == 10`; all successful non-final items must report a delay from 1 to 30 seconds and the final item must report 0. Publish the workflow. Commit the scrubbed export:

```bash
git add docs/operations/agent-registration-workflow.json
git commit -m "docs: add ten-request agent registration workflow"
```

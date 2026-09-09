package com.hxh.apboa.engine.tool.builtins;

import com.hxh.apboa.common.annotation.Scope;
import com.hxh.apboa.common.enums.ScopeType;
import com.hxh.apboa.engine.tool.IAgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Database-backed, generation/CAS guarded key pool. Never returns or logs key material. */
@Component
@Scope(ScopeType.GLOBAL)
public class WenbiaoAgentKeyPoolTool implements IAgentTool {
    static final String RUNTIME = "wenbiao_agent";
    private final JdbcTemplate jdbc;

    public WenbiaoAgentKeyPoolTool(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Tool(name = "wenbiao_agent_key_pool", description = "查询或原子轮换问标授权密钥；密钥仅由服务端内部读取，不返回给 Agent")
    @Transactional
    public Map<String, Object> execute(
            @ToolParam(name="action", description="status 或 rotate；密钥列表由管理员受控入口提供") String action,
            @ToolParam(name="request_id", description="幂等请求 ID，rotate 时必填", required=false) String requestId,
            @ToolParam(name="failed_key_id", description="失败 key id", required=false) Long failedKeyId,
            @ToolParam(name="failed_generation", description="请求开始时的 generation", required=false) Long failedGeneration,
            @ToolParam(name="provider_code", description="标准化供应商错误码", required=false) String providerCode,
            @ToolParam(name="exclude_fingerprints", description="本次请求已尝试的指纹 JSON 数组", required=false) String excludesJson) {
        try {
            return switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
                case "status" -> status();
                case "import" -> error("ADMIN_IMPORT_ONLY", "key import is available only through the protected administrator channel");
                case "rotate" -> rotate(requestId, failedKeyId, failedGeneration, providerCode, parseExcludes(excludesJson));
                default -> error("INVALID_ACTION", "action must be status, import or rotate");
            };
        } catch (Exception e) {
            // The tool contract is structured errors, so do not rethrow into
            // the transaction interceptor; explicitly mark the transaction for
            // rollback when this method is invoked through Spring AOP.
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (RuntimeException ignored) {
                // Direct/unit-test invocation has no surrounding transaction.
            }
            return error("KEY_RUNTIME_NOT_READY", "key runtime is temporarily unavailable");
        }
    }

    private Map<String,Object> status() {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String,Object> runtime = jdbc.queryForMap("SELECT active_key_id, generation FROM wenbiao_key_runtime WHERE runtime_id=?", RUNTIME);
        out.put("success", true); out.put("active_key_id", runtime.get("active_key_id")); out.put("active_generation", runtime.get("generation"));
        out.put("counts", jdbc.query("SELECT state, COUNT(*) c FROM wenbiao_key GROUP BY state", (rs,n)->Map.of("state",rs.getString("state"),"count",rs.getLong("c"))));
        out.put("active_fingerprint", jdbc.queryForObject("SELECT key_fingerprint FROM wenbiao_key WHERE id=?", String.class, runtime.get("active_key_id")));
        return out;
    }

    /**
     * Intentionally not exposed as an AgentScope tool. Keys are supplied by the
     * operator through a protected administrator channel, never by the model.
     */
    private Map<String,Object> importKeys(String raw) {
        if (raw == null || raw.isBlank()) return error("INVALID_KEYS", "keys is required");
        List<String> values = Arrays.stream(raw.replace("[", "").replace("]", "").replace("\"", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty() && !s.contains("\n") && !s.contains("\r")).distinct().toList();
        int imported=0, duplicates=0;
        for (String key : values) {
            String fp = fingerprint(key);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM wenbiao_key WHERE key_fingerprint=?", Integer.class, fp) > 0) { duplicates++; continue; }
            // api_key is intentionally server-side data. It is never included in the tool response.
            jdbc.update("INSERT INTO wenbiao_key(id,key_fingerprint,api_key,state,imported_at,failure_count) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3),0)",
                    Math.abs(new Random().nextLong()), fp, key, "READY");
            imported++;
        }
        return Map.of("success",true,"imported",imported,"duplicates",duplicates,"ready_count",jdbc.queryForObject("SELECT COUNT(*) FROM wenbiao_key WHERE state='READY'",Integer.class));
    }

    private Map<String,Object> rotate(String requestId, Long failedId, Long failedGen, String code, Set<String> excludes) {
        if (requestId == null || failedId == null || failedGen == null || code == null) return error("INVALID_ROTATE_REQUEST", "request_id, failed_key_id, failed_generation and provider_code are required");
        Map<String,Object> rt = jdbc.queryForMap("SELECT active_key_id,generation FROM wenbiao_key_runtime WHERE runtime_id=? FOR UPDATE", RUNTIME);
        long generation=((Number)rt.get("generation")).longValue();
        if (failedGen != generation) return Map.of("success",true,"rotated",false,"active_generation",generation,"reason","STALE_GENERATION");
        List<Map<String,Object>> candidates=jdbc.queryForList("SELECT id,key_fingerprint FROM wenbiao_key WHERE state='READY' AND id<>? ORDER BY last_probe_at IS NULL DESC,last_probe_at ASC,imported_at ASC,id ASC",failedId);
        candidates.removeIf(x -> excludes.contains(x.get("key_fingerprint")));
        if(candidates.isEmpty()) return Map.of("success",false,"error_code","NO_USABLE_READY_KEY","active_generation",generation);
        Map<String,Object> candidate=candidates.get(0); long next=((Number)candidate.get("id")).longValue();
        int changed=jdbc.update("UPDATE wenbiao_key_runtime SET active_key_id=?,generation=generation+1,updated_at=CURRENT_TIMESTAMP(3) WHERE runtime_id=? AND generation=?",next,RUNTIME,generation);
        if(changed!=1) return Map.of("success",true,"rotated",false,"reason","CONCURRENT_WIN");
        try {
            jdbc.update("UPDATE wenbiao_key SET state='COOLING',cooldown_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE id=?",failedId);
            jdbc.update("UPDATE wenbiao_key SET state='ACTIVE',last_provider_code=? WHERE id=?",code,next);
            jdbc.update("INSERT INTO wenbiao_key_rotation(request_id,from_generation,to_generation,from_key_id,to_key_id,provider_code,result,created_at,completed_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",requestId,generation,generation+1,failedId,next,code,"ROTATED");
        } catch (RuntimeException writeFailure) {
            // Keep the old pointer if a non-transactional caller or a failed
            // transaction manager leaves the CAS update committed.
            try {
                jdbc.update("UPDATE wenbiao_key_runtime SET active_key_id=?,generation=? WHERE runtime_id=? AND generation=?",
                        rt.get("active_key_id"), generation, RUNTIME, generation + 1);
            } catch (RuntimeException ignored) {
                // The outer handler still returns a structured readiness error.
            }
            throw writeFailure;
        }
        return Map.of("success",true,"rotated",true,"active_generation",generation+1,"active_fingerprint",candidate.get("key_fingerprint"));
    }
    private static String fingerprint(String s){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder x=new StringBuilder();for(int i=0;i<8;i++)x.append(String.format("%02x",b[i]));return x.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static Set<String> parseExcludes(String s){if(s==null)return Set.of();return new HashSet<>(Arrays.asList(s.replace("[","").replace("]","").replace("\"","").split(",")));}
    private static Map<String,Object> error(String c,String m){return Map.of("success",false,"error_code",c,"message",m);}
}

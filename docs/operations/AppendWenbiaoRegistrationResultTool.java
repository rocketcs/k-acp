import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
import com.hxh.apboa.runtime.wenbiao.WenbiaoKeyPoolStore;
import com.hxh.apboa.runtime.wenbiao.WenbiaoKeyPoolStore.Registration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K-ACP dynamic tool for recording successful Wenbiao registration responses.
 * It accepts only the provider response and its generated registration context.
 */
public final class AppendWenbiaoRegistrationResultTool implements IDynamicAgentTool {
    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        if (params == null || !(params.get("response") instanceof Map)
            || !(params.get("context") instanceof Map)) {
            return skipped("INVALID_REGISTRATION_RESULT");
        }

        Map<String, Object> response = map(params.get("response"));
        boolean hasData = response.containsKey("data");
        Object data = response.get("data");
        // A present data field is the provider payload, even when it is null.
        // Only an absent data field may use the legacy top-level response shape.
        if (hasData && !(data instanceof Map)) {
            return skipped("INVALID_REGISTRATION_RESULT");
        }
        Map<String, Object> payload = hasData ? map(data) : response;
        Registration registration = new Registration(
            text(payload, "api_key"), text(payload, "device_id"), map(params.get("context")),
            integer(payload.get("remaining_calls")), bool(payload.get("is_new")), text(payload, "message"));
        if (!bool(payload.get("success")) || registration.apiKey().isBlank() || registration.deviceId().isBlank()) {
            return skipped("INVALID_REGISTRATION_RESULT");
        }
        try {
            return normalized(WenbiaoKeyPoolStore.production().append(registration).toMap());
        } catch (Exception ignored) {
            return skipped("KEY_POOL_WRITE_FAILED");
        }
    }

    /**
     * The dynamic-tool output contract always contains exactly these eight
     * fields. The storage helper's internal "invalid" result is exposed as a
     * non-writing "skipped" result rather than widening the public contract.
     */
    private static Map<String, Object> normalized(Map<String, Object> result) {
        String outcome = text(result, "result");
        if (!"added".equals(outcome) && !"duplicate".equals(outcome)) {
            return skipped("INVALID_REGISTRATION_RESULT");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("success", Boolean.TRUE.equals(result.get("success")));
        normalized.put("action", "append");
        normalized.put("result", outcome);
        normalized.put("error_code", text(result, "error_code"));
        normalized.put("device_id", text(result, "device_id"));
        normalized.put("api_key_fingerprint", text(result, "api_key_fingerprint"));
        normalized.put("pool_size", integer(result.get("pool_size")));
        normalized.put("standby_count", integer(result.get("standby_count")));
        return normalized;
    }

    private static Map<String, Object> skipped(String errorCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("action", "append");
        result.put("result", "skipped");
        result.put("error_code", errorCode);
        result.put("device_id", "");
        result.put("api_key_fingerprint", "");
        result.put("pool_size", 0);
        result.put("standby_count", 0);
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) {
            return Map.of();
        }
        Map<?, ?> raw = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Map<String, Object> source, String name) {
        Object value = source.get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }
}

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
        Map<String, Object> response = map(params.get("response"));
        Map<String, Object> payload = map(response.getOrDefault("data", response));
        Registration registration = new Registration(
            text(payload, "api_key"), text(payload, "device_id"), map(params.get("context")),
            integer(payload.get("remaining_calls")), bool(payload.get("is_new")), text(payload, "message"));
        if (!bool(payload.get("success")) || registration.apiKey().isBlank() || registration.deviceId().isBlank()) {
            return Map.of("success", false, "action", "append", "result", "skipped", "error_code", "INVALID_REGISTRATION_RESULT");
        }
        try {
            return WenbiaoKeyPoolStore.production().append(registration).toMap();
        } catch (Exception ignored) {
            return Map.of("success", false, "action", "append", "result", "skipped", "error_code", "KEY_POOL_WRITE_FAILED");
        }
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

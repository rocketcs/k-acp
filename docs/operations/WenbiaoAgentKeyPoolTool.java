import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * K-ACP dynamic tool. It manages only credentials that an administrator has
 * already placed in wenbiao_agent-key-pool.json. It never registers or emits a key.
 */
public class WenbiaoAgentKeyPoolTool implements IDynamicAgentTool {
    private static final Path SECRET_DIR = Paths.get(".apboa", "secrets").toAbsolutePath().normalize();
    private static final Path POOL_FILE = SECRET_DIR.resolve("wenbiao_agent-key-pool.json");
    private static final Path ACTIVE_KEY_FILE = SECRET_DIR.resolve("wenbiao_agent-api-key");
    private static final Path PROFILE_FILE = SECRET_DIR.resolve("http-profiles/wenbiao_agent.json");
    private static final Path LOCK_FILE = SECRET_DIR.resolve(".wenbiao_agent-key-pool.lock");
    private static final URI BALANCE_URL = URI.create("https://mcp-server.zhiliaobiaoxun.com/api_v2/account/balance");
    private static final Set<String> ROTATABLE = Set.of("INSUFFICIENT_BALANCE", "QUOTA_EXCEEDED");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        String action = text(params, "action").toLowerCase(Locale.ROOT);
        if (!Set.of("status", "rotate", "mark_failure").contains(action)) {
            return error("INVALID_ACTION", "action must be status, rotate, or mark_failure");
        }
        try {
            Files.createDirectories(SECRET_DIR);
            try (FileChannel channel = FileChannel.open(LOCK_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if ("status".equals(action)) return status();
                String providerStatus = text(params, "provider_status").toUpperCase(Locale.ROOT);
                if (!ROTATABLE.contains(providerStatus)) {
                    return error("NON_ROTATABLE_STATUS", "Only INSUFFICIENT_BALANCE and QUOTA_EXCEEDED can change pool state");
                }
                return "mark_failure".equals(action) ? markFailure(providerStatus) : rotate(providerStatus, fingerprints(params));
            }
        } catch (Exception e) {
            return error("KEY_POOL_ERROR", safeMessage(e));
        }
    }

    private static Map<String, Object> status() throws Exception {
        ObjectNode pool = loadPool();
        Map<String, Integer> states = new LinkedHashMap<>();
        String activeFingerprint = "";
        for (JsonNode item : pool.withArray("keys")) {
            String state = item.path("state").asText("UNKNOWN");
            states.put(state, states.getOrDefault(state, 0) + 1);
            if ("ACTIVE".equals(state)) activeFingerprint = fingerprint(item.path("api_key").asText());
        }
        Map<String, Object> result = ok();
        result.put("action", "status");
        result.put("pool_size", pool.withArray("keys").size());
        result.put("states", states);
        result.put("active_fingerprint", activeFingerprint);
        result.put("active_key_file_present", Files.isRegularFile(ACTIVE_KEY_FILE));
        result.put("profile_present", Files.isRegularFile(PROFILE_FILE));
        return result;
    }

    private static Map<String, Object> markFailure(String providerStatus) throws Exception {
        ObjectNode pool = loadPool();
        ObjectNode active = active(pool);
        if (active == null) return error("NO_ACTIVE_KEY", "No ACTIVE entry exists in the authorized key pool");
        active.put("state", providerStatus);
        active.put("last_provider_status", providerStatus);
        active.put("last_checked_at", Instant.now().toString());
        writeJson(POOL_FILE, pool);
        Map<String, Object> result = ok();
        result.put("action", "mark_failure");
        result.put("state", providerStatus);
        result.put("marked_fingerprint", fingerprint(active.path("api_key").asText()));
        return result;
    }

    private static Map<String, Object> rotate(String providerStatus, Set<String> excluded) throws Exception {
        ObjectNode pool = loadPool();
        ObjectNode current = active(pool);
        if (current != null) {
            current.put("state", providerStatus);
            current.put("last_provider_status", providerStatus);
            current.put("last_checked_at", Instant.now().toString());
        }

        ObjectNode selected = null;
        for (JsonNode item : pool.withArray("keys")) {
            if (!(item instanceof ObjectNode)) continue;
            ObjectNode candidate = (ObjectNode) item;
            if (!"STANDBY".equals(candidate.path("state").asText())) continue;
            String key = candidate.path("api_key").asText("").trim();
            if (key.isEmpty() || excluded.contains(fingerprint(key))) continue;
            String validationStatus = validate(key);
            candidate.put("last_checked_at", Instant.now().toString());
            if (validationStatus == null) {
                selected = candidate;
                break;
            }
            candidate.put("state", validationStatus);
            candidate.put("last_provider_status", validationStatus);
        }

        if (selected == null) {
            writeJson(POOL_FILE, pool);
            Map<String, Object> result = error("NO_USABLE_STANDBY_KEY", "No authorized STANDBY key passed the free balance validation");
            result.put("action", "rotate");
            result.put("previous_state", providerStatus);
            return result;
        }

        String nextKey = selected.path("api_key").asText().trim();
        selected.put("state", "ACTIVE");
        selected.put("last_provider_status", "");
        selected.put("last_rotated_at", Instant.now().toString());
        writeText(ACTIVE_KEY_FILE, nextKey + System.lineSeparator());
        writeProfile(nextKey);
        writeJson(POOL_FILE, pool);
        Map<String, Object> result = ok();
        result.put("action", "rotate");
        result.put("previous_state", providerStatus);
        result.put("active_fingerprint", fingerprint(nextKey));
        return result;
    }

    /** Returns null only if a candidate is valid and has a positive balance. */
    private static String validate(String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder(BALANCE_URL).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-API-Key", key)
                .header("X-Client", "zlbx-bidding/2.3.0")
                .GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return "AUTHENTICATION_FAILED";
            JsonNode root = JSON.readTree(response.body());
            JsonNode balance = root.has("balance") ? root.get("balance") : root.path("data").path("balance");
            return balance.isNumber() && balance.asDouble() <= 0 ? "INSUFFICIENT_BALANCE" : null;
        } catch (Exception ignored) {
            return "AUTHENTICATION_FAILED";
        }
    }

    private static ObjectNode loadPool() throws Exception {
        if (!Files.isRegularFile(POOL_FILE)) throw new IllegalStateException("Authorized key pool file is unavailable");
        JsonNode root = JSON.readTree(Files.readString(POOL_FILE, StandardCharsets.UTF_8));
        if (!(root instanceof ObjectNode) || !root.path("keys").isArray()) throw new IllegalStateException("Authorized key pool file is invalid");
        return (ObjectNode) root;
    }

    private static ObjectNode active(ObjectNode pool) {
        for (JsonNode item : pool.withArray("keys")) {
            if (item instanceof ObjectNode && "ACTIVE".equals(item.path("state").asText())) return (ObjectNode) item;
        }
        return null;
    }

    private static void writeProfile(String key) throws Exception {
        if (!Files.isRegularFile(PROFILE_FILE)) throw new IllegalStateException("wenbiao_agent auth profile is unavailable");
        JsonNode root = JSON.readTree(Files.readString(PROFILE_FILE, StandardCharsets.UTF_8));
        if (!(root instanceof ObjectNode)) throw new IllegalStateException("wenbiao_agent auth profile is invalid");
        ObjectNode profile = (ObjectNode) root;
        ObjectNode headers = profile.with("headers");
        headers.put("X-API-Key", key);
        headers.put("X-Client", "zlbx-bidding/2.3.0");
        writeJson(PROFILE_FILE, profile);
    }

    private static void writeJson(Path path, JsonNode value) throws Exception {
        writeText(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator());
    }

    private static void writeText(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".wenbiao_agent-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Set<String> fingerprints(Map<String, Object> params) {
        Set<String> result = new HashSet<>();
        Object values = params == null ? null : params.get("exclude_fingerprints");
        if (values instanceof Iterable<?>) for (Object value : (Iterable<?>) values) if (value != null) result.add(String.valueOf(value).trim());
        return result;
    }

    private static String text(Map<String, Object> params, String name) {
        return params == null || params.get(name) == null ? "" : String.valueOf(params.get(name)).trim();
    }

    private static String fingerprint(String key) {
        if (key == null || key.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < 8; i++) value.append(String.format("%02x", digest[i]));
            return value.toString();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error_code", code);
        result.put("message", message);
        return result;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replaceAll("(?i)zlbx_[A-Za-z0-9_-]+", "[credential]");
    }
}

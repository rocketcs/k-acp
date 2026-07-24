package com.hxh.apboa.runtime.wenbiao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class WenbiaoKeyPoolStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String POOL_FILE_NAME = "wenbiao_agent-key-pool.json";
    private static final String LOCK_FILE_NAME = ".wenbiao_agent-key-pool.lock";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path secretDirectory;

    private WenbiaoKeyPoolStore(Path secretDirectory) {
        this.secretDirectory = secretDirectory.toAbsolutePath().normalize();
    }

    public static WenbiaoKeyPoolStore production() {
        return new WenbiaoKeyPoolStore(Paths.get(".apboa", "secrets").toAbsolutePath().normalize());
    }

    public static WenbiaoKeyPoolStore forDirectory(Path secretDirectory) {
        return new WenbiaoKeyPoolStore(secretDirectory);
    }

    public void initialize(String activeKey) throws IOException {
        Files.createDirectories(secretDirectory);
        ObjectNode pool = JSON.createObjectNode();
        ObjectNode active = pool.putArray("keys").addObject();
        active.put("api_key", activeKey);
        active.put("device_id", "");
        active.set("context", JSON.createObjectNode());
        active.put("registered_at", "");
        active.put("remaining_calls", 0);
        active.put("is_new", false);
        active.put("registration_message", "");
        active.put("state", "ACTIVE");
        active.put("last_checked_at", "");
        active.put("last_provider_status", "");
        active.put("last_rotated_at", "");
        writeJsonAtomically(poolFile(), pool);
    }

    public AppendResult append(Registration registration) throws IOException {
        if (registration == null || !registration.valid()) {
            return AppendResult.invalid();
        }
        Files.createDirectories(secretDirectory);
        try (FileChannel channel = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            ObjectNode pool = loadPool();
            String apiKeyFingerprint = fingerprint(registration.apiKey());
            if (containsFingerprint(pool, apiKeyFingerprint) || containsDevice(pool, registration.deviceId())) {
                return AppendResult.duplicate(poolSize(pool), standbyCount(pool), apiKeyFingerprint, registration.deviceId());
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
            entry.put("last_checked_at", "");
            entry.put("last_provider_status", "");
            entry.put("last_rotated_at", "");
            writeJsonAtomically(poolFile(), pool);
            return AppendResult.added(poolSize(pool), standbyCount(pool), apiKeyFingerprint, registration.deviceId());
        }
    }

    public ObjectNode readPool() throws IOException {
        return loadPool();
    }

    public ObjectNode activeEntry() throws IOException {
        for (JsonNode entry : loadPool().withArray("keys")) {
            if (entry instanceof ObjectNode object && "ACTIVE".equals(object.path("state").asText())) {
                return object;
            }
        }
        return JSON.createObjectNode();
    }

    private ObjectNode loadPool() throws IOException {
        JsonNode root = JSON.readTree(Files.readString(poolFile(), StandardCharsets.UTF_8));
        if (!(root instanceof ObjectNode pool) || !pool.path("keys").isArray()) {
            throw new IOException("Wenbiao key pool must be a JSON object containing a keys array");
        }
        return pool;
    }

    private boolean containsFingerprint(ObjectNode pool, String apiKeyFingerprint) {
        for (JsonNode entry : pool.withArray("keys")) {
            if (apiKeyFingerprint.equals(fingerprint(entry.path("api_key").asText()))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDevice(ObjectNode pool, String deviceId) {
        for (JsonNode entry : pool.withArray("keys")) {
            if (deviceId.equals(entry.path("device_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private static int poolSize(ObjectNode pool) {
        return pool.withArray("keys").size();
    }

    private static int standbyCount(ObjectNode pool) {
        int count = 0;
        for (JsonNode entry : pool.withArray("keys")) {
            if ("STANDBY".equals(entry.path("state").asText())) {
                count++;
            }
        }
        return count;
    }

    private Path poolFile() {
        return secretDirectory.resolve(POOL_FILE_NAME);
    }

    private Path lockFile() {
        return secretDirectory.resolve(LOCK_FILE_NAME);
    }

    private static void writeJsonAtomically(Path target, ObjectNode pool) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".wenbiao-key-pool-", ".tmp");
        try {
            setOwnerOnlyPermissions(temporary);
            Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(pool) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void setOwnerOnlyPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are not available on every supported filesystem.
        } catch (IOException ignored) {
            // The atomic write is still valid when the filesystem rejects POSIX attributes.
        }
    }

    private static String fingerprint(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder fingerprint = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                fingerprint.append(String.format("%02x", digest[i]));
            }
            return fingerprint.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Registration(String apiKey, String deviceId, Map<String, Object> context, int remainingCalls,
                               boolean isNew, String message) {
        public boolean valid() {
            return apiKey != null && apiKey.matches("zlbx__[A-Za-z0-9_-]+")
                && deviceId != null && !deviceId.isBlank()
                && context != null && remainingCalls > 0 && isNew && "created".equalsIgnoreCase(message);
        }
    }

    public record AppendResult(boolean success, String action, String result, String errorCode, String deviceId,
                               String apiKeyFingerprint, int poolSize, int standbyCount) {
        private static AppendResult added(int poolSize, int standbyCount, String apiKeyFingerprint, String deviceId) {
            return new AppendResult(true, "append", "added", "", deviceId, apiKeyFingerprint, poolSize, standbyCount);
        }

        private static AppendResult duplicate(int poolSize, int standbyCount, String apiKeyFingerprint, String deviceId) {
            return new AppendResult(true, "append", "duplicate", "", deviceId, apiKeyFingerprint, poolSize, standbyCount);
        }

        private static AppendResult invalid() {
            return new AppendResult(false, "append", "invalid", "INVALID_REGISTRATION_RESULT", "", "", 0, 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("success", success);
            resultMap.put("action", action);
            resultMap.put("result", result);
            resultMap.put("error_code", errorCode);
            resultMap.put("device_id", deviceId);
            resultMap.put("api_key_fingerprint", apiKeyFingerprint);
            resultMap.put("pool_size", poolSize);
            resultMap.put("standby_count", standbyCount);
            return resultMap;
        }
    }
}

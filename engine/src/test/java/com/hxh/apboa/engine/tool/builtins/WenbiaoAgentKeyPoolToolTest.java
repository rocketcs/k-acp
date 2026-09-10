package com.hxh.apboa.engine.tool.builtins;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WenbiaoAgentKeyPoolToolTest {

    @Test
    void scenario1_hundredBalanceFailuresProduceSingleGenerationGrowth() throws Exception {
        InMemoryKeyRuntimeDb db = new InMemoryKeyRuntimeDb();
        WenbiaoAgentKeyPoolTool tool = new WenbiaoAgentKeyPoolTool(db);
        int requests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int n = i;
            futures.add(executor.submit(() -> {
                start.await();
                return tool.execute("rotate", "request-" + n, 1L, 1L,
                        "INSUFFICIENT_BALANCE", "[]");
            }));
        }
        start.countDown();
        int rotated = 0;
        try {
            for (Future<Map<String, Object>> future : futures) {
                Map<String, Object> result = future.get(10, TimeUnit.SECONDS);
                assertNotNull(result);
                assertTrue((Boolean) result.get("success"), result.toString());
                if (Boolean.TRUE.equals(result.get("rotated"))) rotated++;
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, rotated, "exactly one request must win the CAS rotation");
        assertEquals(2L, db.generation);
        assertEquals(2L, db.activeKeyId);
        assertEquals(1, db.rotationRows.get(), "one generation increase must have one audit row");
        assertEquals("ACTIVE", db.keys.get(2L).state);
        assertEquals("COOLING", db.keys.get(1L).state);
    }

    @Test
    void scenario4_noReadyKeyReturnsStructuredErrorWithoutLoopOrException() {
        InMemoryKeyRuntimeDb db = new InMemoryKeyRuntimeDb();
        db.keys.remove(2L);
        Map<String, Object> result = new WenbiaoAgentKeyPoolTool(db)
                .execute("rotate", "request-empty", 1L, 1L, "INSUFFICIENT_BALANCE", "[]");
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("NO_USABLE_READY_KEY", result.get("error_code"));
        assertEquals(1L, result.get("active_generation"));
        assertEquals(1L, db.generation);
        assertEquals(0, db.rotationRows.get());
    }

    @Test
    void scenario5_storageUnavailableKeepsOldRuntimeAndReturnsStructuredError() {
        InMemoryKeyRuntimeDb db = new InMemoryKeyRuntimeDb();
        db.failRuntimeRead = true;
        Map<String, Object> result = new WenbiaoAgentKeyPoolTool(db)
                .execute("rotate", "request-down", 1L, 1L, "INSUFFICIENT_BALANCE", "[]");
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("KEY_RUNTIME_NOT_READY", result.get("error_code"));
        assertEquals(1L, db.activeKeyId);
        assertEquals(1L, db.generation);
        assertEquals(0, db.updateCalls.get());
    }

    @Test
    void scenario5_candidateStoreUnavailableDoesNotThrowAndPreservesRuntime() {
        InMemoryKeyRuntimeDb db = new InMemoryKeyRuntimeDb();
        db.failCandidateRead = true;
        Map<String, Object> result = new WenbiaoAgentKeyPoolTool(db)
                .execute("rotate", "request-down-candidate", 1L, 1L, "INSUFFICIENT_BALANCE", "[]");
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("KEY_RUNTIME_NOT_READY", result.get("error_code"));
        assertEquals(1L, db.activeKeyId);
        assertEquals(1L, db.generation);
    }

    @Test
    void scenario5_postCasWriteFailureMustNotPublishPartialRuntime() {
        InMemoryKeyRuntimeDb db = new InMemoryKeyRuntimeDb();
        db.failPostCasWrite = true;
        Map<String, Object> result = new WenbiaoAgentKeyPoolTool(db)
                .execute("rotate", "request-partial-write", 1L, 1L, "INSUFFICIENT_BALANCE", "[]");
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("KEY_RUNTIME_NOT_READY", result.get("error_code"));
        assertEquals(1L, db.activeKeyId, "failed rotation must retain old runtime pointer");
        assertEquals(1L, db.generation, "failed rotation must retain old generation");
    }

    @Disabled("BLOCKED: no resolver/Broker implementation exists in this repository")
    @Test
    void scenario2_newRequestsUseKeyFromNewGeneration() {
        Assumptions.assumeTrue(false, "No resolver/Broker implementation to exercise actual key injection");
    }

    @Disabled("BLOCKED: no resolver/Broker implementation or restart wiring exists in this repository")
    @Test
    void scenario3_restartRuntimeAndActualKeyRemainConsistent() {
        Assumptions.assumeTrue(false, "No resolver/Broker implementation to exercise restart consistency");
    }

    /** Local deterministic JDBC double; no external database or environment is touched. */
    private static final class InMemoryKeyRuntimeDb extends JdbcTemplate {
        private long activeKeyId = 1L;
        private long generation = 1L;
        private boolean failRuntimeRead;
        private boolean failCandidateRead;
        private boolean failPostCasWrite;
        private final AtomicInteger updateCalls = new AtomicInteger();
        private final AtomicInteger rotationRows = new AtomicInteger();
        private final Map<Long, Key> keys = new LinkedHashMap<>();

        private InMemoryKeyRuntimeDb() {
            keys.put(1L, new Key(1L, "old-fingerprint", "ACTIVE"));
            keys.put(2L, new Key(2L, "new-fingerprint", "READY"));
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            if (failRuntimeRead) throw new DataAccessResourceFailureException("local runtime unavailable");
            return Map.of("active_key_id", activeKeyId, "generation", generation);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (failCandidateRead) throw new DataAccessResourceFailureException("local key store unavailable");
            long failedId = ((Number) args[0]).longValue();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Key key : keys.values()) {
                if (key.id != failedId && "READY".equals(key.state))
                    result.add(Map.of("id", key.id, "key_fingerprint", key.fingerprint));
            }
            return result;
        }

        @Override
        public int update(String sql, Object... args) {
            updateCalls.incrementAndGet();
            synchronized (this) {
                if (sql.startsWith("UPDATE wenbiao_key_runtime")) {
                    long expected;
                    if (args.length == 4) {
                        if (generation != ((Number) args[3]).longValue()) return 0;
                        activeKeyId = ((Number) args[0]).longValue();
                        generation = ((Number) args[1]).longValue();
                        return 1;
                    }
                    long candidate = ((Number) args[0]).longValue();
                    expected = ((Number) args[2]).longValue();
                    if (expected != generation) return 0;
                    activeKeyId = candidate;
                    generation++;
                    return 1;
                }
                if (sql.startsWith("UPDATE wenbiao_key SET state='COOLING'")) {
                    if (failPostCasWrite) throw new DataAccessResourceFailureException("local write unavailable");
                    keys.get(((Number) args[0]).longValue()).state = "COOLING";
                    return 1;
                }
                if (sql.startsWith("UPDATE wenbiao_key SET state='ACTIVE'")) {
                    keys.get(((Number) args[1]).longValue()).state = "ACTIVE";
                    return 1;
                }
                if (sql.startsWith("INSERT INTO wenbiao_key_rotation")) {
                    rotationRows.incrementAndGet();
                    return 1;
                }
                return 1;
            }
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (requiredType == String.class) {
                Key key = keys.get(((Number) args[0]).longValue());
                return requiredType.cast(key == null ? null : key.fingerprint);
            }
            if (requiredType == Integer.class) return requiredType.cast(0);
            return null;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Key key : keys.values()) counts.merge(key.state, 1, Integer::sum);
            List<T> result = new ArrayList<>();
            int row = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                ResultSet rs = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class},
                        (proxy, method, methodArgs) -> switch (method.getName()) {
                            case "getString" -> entry.getKey();
                            case "getLong" -> entry.getValue().longValue();
                            default -> null;
                        });
                try {
                    result.add(rowMapper.mapRow(rs, row++));
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            return result;
        }

        private static final class Key {
            private final long id;
            private final String fingerprint;
            private String state;
            private Key(long id, String fingerprint, String state) { this.id = id; this.fingerprint = fingerprint; this.state = state; }
        }
    }
}

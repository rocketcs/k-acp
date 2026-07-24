package com.hxh.apboa.runtime.wenbiao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WenbiaoKeyPoolStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendAddsStandbyThenDeduplicatesWithoutChangingActive() throws Exception {
        WenbiaoKeyPoolStore store = WenbiaoKeyPoolStore.forDirectory(tempDir);
        store.initialize("active-key");
        var first = store.append(new WenbiaoKeyPoolStore.Registration("zlbx__test_key_1234567890", "device-a", Map.of("agent_kind", "test"), 100, true, "created"));
        var duplicateKey = store.append(new WenbiaoKeyPoolStore.Registration("zlbx__test_key_1234567890", "device-b", Map.of(), 100, true, "created"));
        var duplicateDevice = store.append(new WenbiaoKeyPoolStore.Registration("zlbx__another_test_key_123", "device-a", Map.of(), 100, true, "created"));
        assertThat(first.result()).isEqualTo("added");
        assertThat(duplicateKey.result()).isEqualTo("duplicate");
        assertThat(duplicateDevice.result()).isEqualTo("duplicate");
        assertThat(store.readPool().withArray("keys")).hasSize(2);
        assertThat(store.activeEntry().path("api_key").asText()).isEqualTo("active-key");
        assertThat(store.readPool().withArray("keys").get(1).path("state").asText()).isEqualTo("STANDBY");
    }

    @Test
    void invalidRegistrationDoesNotWriteOrExposeTheKey() throws Exception {
        WenbiaoKeyPoolStore store = WenbiaoKeyPoolStore.forDirectory(tempDir);
        store.initialize("active-key");
        var result = store.append(new WenbiaoKeyPoolStore.Registration("not-a-provider-key", "", Map.of(), 0, false, "failed"));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_REGISTRATION_RESULT");
        assertThat(result.toMap().toString()).doesNotContain("not-a-provider-key");
        assertThat(store.readPool().withArray("keys")).hasSize(1);
    }

    @Test
    void productionAlwaysUsesTheFixedAbsoluteSecretDirectory() throws Exception {
        Field secretDirectory = WenbiaoKeyPoolStore.class.getDeclaredField("secretDirectory");
        secretDirectory.setAccessible(true);

        assertThat(secretDirectory.get(WenbiaoKeyPoolStore.production()))
            .isEqualTo(Path.of("/app/.apboa/secrets"));
    }

    @Test
    void initializePreservesAnExistingActiveEntryAndPoolContents() throws Exception {
        WenbiaoKeyPoolStore store = WenbiaoKeyPoolStore.forDirectory(tempDir);
        store.initialize("first-active-key");
        store.append(new WenbiaoKeyPoolStore.Registration("zlbx__test_key_1234567890", "device-a", Map.of(), 100, true, "created"));

        store.initialize("replacement-active-key");

        assertThat(store.activeEntry().path("api_key").asText()).isEqualTo("first-active-key");
        assertThat(store.readPool().withArray("keys")).hasSize(2);
    }

    @Test
    void initializeUsesTheAppendLock() throws Exception {
        WenbiaoKeyPoolStore store = WenbiaoKeyPoolStore.forDirectory(tempDir);
        store.initialize("active-key");
        Path lockFile = tempDir.resolve(".wenbiao_agent-key-pool.lock");

        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertThatThrownBy(() -> store.initialize("replacement-active-key"))
                .isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
        }
    }
}

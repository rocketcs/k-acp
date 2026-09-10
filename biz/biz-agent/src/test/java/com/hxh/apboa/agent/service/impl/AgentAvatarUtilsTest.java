package com.hxh.apboa.agent.service.impl;

import com.hxh.apboa.common.util.AgentAvatarUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAvatarUtilsTest {

    @Test
    void randomAvatarAlwaysUsesOneOfTheBundledFiles() {
        for (int i = 0; i < 200; i++) {
            String avatar = AgentAvatarUtils.randomAvatar();
            assertTrue(avatar.matches("agent-avatar-(0[1-9]|1[0-9]|2[0-4])\\.png"), avatar);
        }
    }
}

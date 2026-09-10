package com.hxh.apboa.common.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 智能体默认头像工具。
 */
public final class AgentAvatarUtils {
    public static final int DEFAULT_AVATAR_COUNT = 24;
    private static final String DEFAULT_AVATAR_PATTERN = "agent-avatar-%02d.png";

    private AgentAvatarUtils() {
    }

    /**
     * 随机选择一个内置智能体头像文件名。
     */
    public static String randomAvatar() {
        int index = ThreadLocalRandom.current().nextInt(1, DEFAULT_AVATAR_COUNT + 1);
        return DEFAULT_AVATAR_PATTERN.formatted(index);
    }

}

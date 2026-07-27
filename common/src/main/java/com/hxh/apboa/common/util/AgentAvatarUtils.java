package com.hxh.apboa.common.util;

/**
 * 智能体默认头像工具。
 */
public final class AgentAvatarUtils {
    public static final int DEFAULT_AVATAR_COUNT = 24;
    private static final String DEFAULT_AVATAR_PATTERN = "agent-avatar-%02d.png";

    private AgentAvatarUtils() {
    }

    /**
     * 根据智能体 ID 稳定分配内置头像，无需数据库字段即可保持刷新前后一致。
     */
    public static String avatarForAgentId(Long agentId) {
        long mixed = agentId == null ? 0L : agentId;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        int index = (int) Math.floorMod(mixed, DEFAULT_AVATAR_COUNT) + 1;
        return DEFAULT_AVATAR_PATTERN.formatted(index);
    }
}

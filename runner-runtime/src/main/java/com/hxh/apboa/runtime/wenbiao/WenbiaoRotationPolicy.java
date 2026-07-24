package com.hxh.apboa.runtime.wenbiao;

/**
 * Shared policy for rotating the Wenbiao credential used by tender search.
 */
public final class WenbiaoRotationPolicy {

    private WenbiaoRotationPolicy() {
    }

    public static boolean rotatable(String code) {
        return "AUTHENTICATION_FAILED".equals(code);
    }

    public static String profileFileName() {
        return "zhiliao.json";
    }
}

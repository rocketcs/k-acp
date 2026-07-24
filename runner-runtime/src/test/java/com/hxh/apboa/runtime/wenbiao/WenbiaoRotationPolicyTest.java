package com.hxh.apboa.runtime.wenbiao;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WenbiaoRotationPolicyTest {

    @Test
    void onlyAuthenticationFailureRotatesAndTheActiveProfileIsZhiliao() {
        assertThat(WenbiaoRotationPolicy.rotatable("AUTHENTICATION_FAILED")).isTrue();
        assertThat(WenbiaoRotationPolicy.rotatable("TIMEOUT")).isFalse();
        assertThat(WenbiaoRotationPolicy.profileFileName()).isEqualTo("zhiliao.json");
    }
}

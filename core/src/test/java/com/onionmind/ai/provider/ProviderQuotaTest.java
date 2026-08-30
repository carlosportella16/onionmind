package com.onionmind.ai.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderQuotaTest {

    @Test
    void unlimitedIsAlwaysAvailableAndDoesNotOverflow() {
        assertThat(ProviderQuota.UNLIMITED.unlimited()).isTrue();
        assertThat(ProviderQuota.UNLIMITED.availableWithMargin(99)).isTrue();
    }

    @Test
    void availableOnlyAboveTheMargin() {
        assertThat(new ProviderQuota(6, 100).availableWithMargin(5)).isTrue();   // 6 > 5
        assertThat(new ProviderQuota(5, 100).availableWithMargin(5)).isFalse();  // 5 not > 5
        assertThat(new ProviderQuota(0, 100).availableWithMargin(5)).isFalse();
    }
}

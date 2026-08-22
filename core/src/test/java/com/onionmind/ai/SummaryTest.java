package com.onionmind.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryTest {

    @Test
    void accessorsReturnConstructorValues() {
        Summary summary = new Summary("resumo do texto", 0.91);

        assertThat(summary.text()).isEqualTo("resumo do texto");
        assertThat(summary.confidence()).isEqualTo(0.91);
    }
}

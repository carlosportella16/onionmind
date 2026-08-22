package com.onionmind.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingTest {

    @Test
    void accessorsReturnConstructorValues() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        Embedding embedding = new Embedding(vector, 0.95);

        assertThat(embedding.vector()).isEqualTo(vector);
        assertThat(embedding.confidence()).isEqualTo(0.95);
    }
}

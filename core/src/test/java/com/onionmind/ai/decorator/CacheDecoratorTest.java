package com.onionmind.ai.decorator;

import com.onionmind.ai.Entity;
import com.onionmind.ai.RedisTestSupport;
import com.onionmind.ai.TaskContext.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheDecoratorTest {

    private CacheDecorator cache;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        cache = new CacheDecorator(RedisTestSupport.template(), Duration.ofHours(24));
    }

    @Test
    void roundTripsAValidatedResult() {
        cache.put(TaskType.SUMMARIZE, "algum texto", new ValidatedResult("resumo", null, 0.9));

        assertThat(cache.get(TaskType.SUMMARIZE, "algum texto"))
            .get()
            .isEqualTo(new ValidatedResult("resumo", null, 0.9));
    }

    @Test
    void missReturnsEmpty() {
        assertThat(cache.get(TaskType.CLASSIFY, "nunca visto")).isEmpty();
    }

    @Test
    void keyIsScopedByTaskType() {
        cache.put(TaskType.SUMMARIZE, "mesmo texto", new ValidatedResult("s", null, 0.9));

        assertThat(cache.get(TaskType.CLASSIFY, "mesmo texto")).isEmpty();
        assertThat(cache.get(TaskType.SUMMARIZE, "mesmo texto")).isPresent();
    }

    @Test
    void roundTripsValidatedEntities() {
        ValidatedEntities entities = new ValidatedEntities(
            List.of(new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.9)), 0.9, true);

        cache.putEntities(TaskType.EXTRACT_ENTITIES, "algum texto", entities);

        assertThat(cache.getEntities(TaskType.EXTRACT_ENTITIES, "algum texto")).get().isEqualTo(entities);
    }

    @Test
    void entitiesCacheMissReturnsEmpty() {
        assertThat(cache.getEntities(TaskType.EXTRACT_ENTITIES, "nunca visto")).isEmpty();
    }

    @Test
    void emptyEntityListRoundTripsToo() {
        ValidatedEntities noEntities = new ValidatedEntities(List.of(), 1.0, true);

        cache.putEntities(TaskType.EXTRACT_ENTITIES, "texto sem entidades", noEntities);

        assertThat(cache.getEntities(TaskType.EXTRACT_ENTITIES, "texto sem entidades")).get().isEqualTo(noEntities);
    }
}

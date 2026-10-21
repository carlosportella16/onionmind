package com.onionmind.ai;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * One shared Redis container for the whole AI test suite (quota tracker + cache decorator).
 * Started once on class load, reused across tests; Ryuk reaps it at JVM exit.
 */
public final class RedisTestSupport {

    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final LettuceConnectionFactory CONNECTION_FACTORY;

    static {
        REDIS.start();
        CONNECTION_FACTORY = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        CONNECTION_FACTORY.afterPropertiesSet();
    }

    private RedisTestSupport() {
    }

    public static StringRedisTemplate template() {
        StringRedisTemplate template = new StringRedisTemplate(CONNECTION_FACTORY);
        template.afterPropertiesSet();
        return template;
    }

    public static void flushAll() {
        CONNECTION_FACTORY.getConnection().serverCommands().flushAll();
    }
}

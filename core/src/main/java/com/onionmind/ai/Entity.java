package com.onionmind.ai;

/**
 * A typed entity extracted from a page's text (master-sdd sec. 10 — the same six node types
 * Neo4j will store). {@code value} is the raw mention as extracted; normalizing it into a
 * business key for graph merge is the {@code graph} module's job, not this one's.
 */
public record Entity(EntityType type, String value, double confidence) {

    public enum EntityType { PERSON, ORGANIZATION, CRYPTO_WALLET, TECHNOLOGY, LOCATION, ONION_SERVICE }
}

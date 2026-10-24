package com.onionmind.content;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One entity found on a page. {@code key} is the normalized business key: the same entity
 * cited on different pages converges to the same {@link #id()} (one node in the graph).
 */
public record ExtractedEntity(EntityType type, String key, String display, double confidence) {

    public static ExtractedEntity of(EntityType type, String value, double confidence) {
        return new ExtractedEntity(type, normalize(type, value), value.strip(), confidence);
    }

    /** Graph identity: {@code type:key}, e.g. {@code person:jose silva}. */
    public String id() {
        return type.code() + ":" + key;
    }

    static String normalize(EntityType type, String value) {
        String v = value.strip();
        return switch (type) {
            // base58 (legacy BTC, XMR) is case-sensitive; bech32 and hex addresses are not.
            case CRYPTO_WALLET -> v.regionMatches(true, 0, "bc1", 0, 3) || v.startsWith("0x")
                ? v.toLowerCase(Locale.ROOT) : v;
            case ONION_SERVICE, EMAIL -> v.toLowerCase(Locale.ROOT);
            case PGP_FINGERPRINT -> v.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            case PERSON, ORGANIZATION, LOCATION, TECHNOLOGY -> Normalizer.normalize(v, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        };
    }

    /** Collapses duplicates by {@link #id()} keeping the highest confidence, first-seen order, capped. */
    public static List<ExtractedEntity> dedupe(List<ExtractedEntity> entities, int max) {
        Map<String, ExtractedEntity> byId = new LinkedHashMap<>();
        for (ExtractedEntity e : entities) {
            byId.merge(e.id(), e, (a, b) -> b.confidence() > a.confidence() ? b : a);
        }
        return byId.values().stream().limit(max).toList();
    }
}

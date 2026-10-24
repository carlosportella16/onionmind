package com.onionmind.content;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What EntityProcessor extracts. The first four come from deterministic patterns (with a
 * checksum where the format has one); the last four are named entities from the LLM.
 * {@link #code()} is what's persisted in {@code pages.entities} and used in the API path.
 */
public enum EntityType {
    CRYPTO_WALLET, ONION_SERVICE, EMAIL, PGP_FINGERPRINT,
    PERSON, ORGANIZATION, LOCATION, TECHNOLOGY;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<EntityType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code().equals(code)).findFirst();
    }
}

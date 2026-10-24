package com.onionmind.content;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedEntityTest {

    @Test
    void personWithAccentsCaseAndExtraSpacesConvergesToOneKey() {
        var a = ExtractedEntity.of(EntityType.PERSON, "José  Silva", 0.9);
        var b = ExtractedEntity.of(EntityType.PERSON, "jose silva", 0.8);

        assertThat(a.id()).isEqualTo("person:jose silva").isEqualTo(b.id());
        assertThat(a.display()).isEqualTo("José  Silva");
    }

    @Test
    void base58WalletKeepsCaseButBech32AndHexAreLowercased() {
        assertThat(ExtractedEntity.of(EntityType.CRYPTO_WALLET, "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", 1).key())
            .isEqualTo("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa");
        assertThat(ExtractedEntity.of(EntityType.CRYPTO_WALLET, "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", 1).key())
            .isEqualTo("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");
        assertThat(ExtractedEntity.of(EntityType.CRYPTO_WALLET, "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01", 1).key())
            .isEqualTo("0xabcdef0123456789abcdef0123456789abcdef01");
    }

    @Test
    void hostsAndEmailsLowercasedFingerprintUppercasedWithoutSpaces() {
        assertThat(ExtractedEntity.of(EntityType.ONION_SERVICE, "ABC.onion", 1).key()).isEqualTo("abc.onion");
        assertThat(ExtractedEntity.of(EntityType.EMAIL, "Vendedor@Mail.ONION", 1).key()).isEqualTo("vendedor@mail.onion");
        assertThat(ExtractedEntity.of(EntityType.PGP_FINGERPRINT, "abcd ef01 2345 6789 abcd ef01 2345 6789 abcd ef01", 1).key())
            .isEqualTo("ABCDEF0123456789ABCDEF0123456789ABCDEF01");
    }

    @Test
    void dedupeKeepsHighestConfidenceInFirstSeenOrderAndCaps() {
        var list = List.of(
            ExtractedEntity.of(EntityType.PERSON, "Ana", 0.6),
            ExtractedEntity.of(EntityType.LOCATION, "Lisboa", 0.9),
            ExtractedEntity.of(EntityType.PERSON, "ana", 0.95),
            ExtractedEntity.of(EntityType.TECHNOLOGY, "Monero", 0.8));

        var deduped = ExtractedEntity.dedupe(list, 2);

        assertThat(deduped).extracting(ExtractedEntity::id).containsExactly("person:ana", "location:lisboa");
        assertThat(deduped.getFirst().confidence()).isEqualTo(0.95);
    }

    @Test
    void typeCodesRoundTrip() {
        for (EntityType t : EntityType.values()) {
            assertThat(EntityType.fromCode(t.code())).contains(t);
        }
        assertThat(EntityType.fromCode("weapon")).isEmpty();
    }
}

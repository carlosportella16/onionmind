package com.onionmind.content.processors;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EntityPatternsTest {

    // Real, publicly known identifiers: the Bitcoin genesis address, BIP-173/350 test
    // vectors, and the Tor Project's own v3 onion service.
    private static final String GENESIS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
    private static final String P2SH = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy";
    private static final String BECH32 = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
    private static final String BECH32M = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0";
    private static final String TOR_PROJECT = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion";

    @Test
    void validLegacyAndP2shAddressesHaveFullConfidence() {
        var found = EntityPatterns.extract("pague em " + GENESIS + " ou " + P2SH + ".");

        assertThat(found).extracting(ExtractedEntity::key).containsExactly(GENESIS, P2SH);
        assertThat(found).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(EntityType.CRYPTO_WALLET);
            assertThat(e.confidence()).isEqualTo(1.0);
        });
    }

    @Test
    void base58LookAlikeWithWrongChecksumIsDiscarded() {
        String tampered = GENESIS.substring(0, GENESIS.length() - 1) + "b";
        assertThat(EntityPatterns.validBase58Check(tampered)).isFalse();
        assertThat(EntityPatterns.extract("carteira " + tampered)).isEmpty();
    }

    @Test
    void bech32AndBech32mValidUppercaseToo() {
        assertThat(EntityPatterns.validBech32(BECH32)).isTrue();
        assertThat(EntityPatterns.validBech32(BECH32M)).isTrue();
        assertThat(EntityPatterns.validBech32(BECH32.toUpperCase())).isTrue();
        assertThat(EntityPatterns.extract("doação: " + BECH32M)).extracting(ExtractedEntity::key).containsExactly(BECH32M);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",   // wrong checksum
        "bc1qW508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"    // mixed case
    })
    void invalidBech32IsRejected(String address) {
        assertThat(EntityPatterns.validBech32(address)).isFalse();
    }

    @Test
    void v3OnionWithValidChecksumIsExtractedLowercased() {
        var found = EntityPatterns.extract("espelho em http://" + TOR_PROJECT.toUpperCase() + "/index");

        assertThat(found).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(EntityType.ONION_SERVICE);
            assertThat(e.key()).isEqualTo(TOR_PROJECT);
            assertThat(e.confidence()).isEqualTo(1.0);
        });
    }

    @Test
    void v3OnionWithBrokenChecksumAndShortHostsAreIgnored() {
        String broken = "3" + TOR_PROJECT.substring(1);
        assertThat(EntityPatterns.validOnionV3(broken)).isFalse();
        assertThat(EntityPatterns.extract(broken + " e semente-e2e.onion")).isEmpty();
    }

    @Test
    void ethXmrEmailAndPgpHaveTheirConfidences() {
        String eth = "0x52908400098527886E0F7030069857D2E4169EE7";
        String xmr = "4" + "A".repeat(1) + "1".repeat(93);
        String text = "eth " + eth + ", xmr " + xmr + ", contato Vendas@Market.onion, "
            + "pgp 0123 4567 89AB CDEF 0123 4567 89AB CDEF 0123 4567.";

        var found = EntityPatterns.extract(text);

        assertThat(found).extracting(e -> e.type().code() + "=" + e.confidence()).containsExactlyInAnyOrder(
            "crypto_wallet=0.9", "crypto_wallet=0.8", "email=0.9", "pgp_fingerprint=0.9");
        assertThat(found).extracting(ExtractedEntity::key)
            .contains(eth.toLowerCase(), "vendas@market.onion", "0123456789ABCDEF0123456789ABCDEF01234567");
    }

    @Test
    void longHexHashIsNotAFingerprintAndEthHexIsNotAPgp() {
        String sha256 = "a".repeat(64);
        assertThat(EntityPatterns.extract("hash " + sha256)).isEmpty();
        assertThat(EntityPatterns.extract("0x" + "b".repeat(40)))
            .extracting(e -> e.type()).containsExactly(EntityType.CRYPTO_WALLET);
    }
}

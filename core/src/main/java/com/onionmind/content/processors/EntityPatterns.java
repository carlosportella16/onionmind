package com.onionmind.content.processors;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic extractors for the identifiers worth the most to an investigator. Formats
 * with a checksum (legacy/bech32 BTC, v3 onion) are validated, so a look-alike never
 * becomes an entity; formats without one get a lower confidence.
 */
final class EntityPatterns {

    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private record Rule(EntityType type, Pattern pattern, Predicate<String> valid, double confidence) {}

    private static final List<Rule> RULES = List.of(
        new Rule(EntityType.CRYPTO_WALLET,
            Pattern.compile("(?<![A-Za-z0-9])[13][1-9A-HJ-NP-Za-km-z]{25,34}(?![A-Za-z0-9])"),
            EntityPatterns::validBase58Check, 1.0),
        new Rule(EntityType.CRYPTO_WALLET,
            Pattern.compile("(?<![A-Za-z0-9])(?:bc1|BC1)[02-9ac-hj-np-zAC-HJ-NP-Z]{11,71}(?![A-Za-z0-9])"),
            EntityPatterns::validBech32, 1.0),
        new Rule(EntityType.CRYPTO_WALLET,
            Pattern.compile("(?<![A-Za-z0-9])0x[0-9a-fA-F]{40}(?![A-Za-z0-9])"), s -> true, 0.9),
        new Rule(EntityType.CRYPTO_WALLET,
            Pattern.compile("(?<![A-Za-z0-9])[48][0-9AB][1-9A-HJ-NP-Za-km-z]{93}(?![A-Za-z0-9])"), s -> true, 0.8),
        new Rule(EntityType.ONION_SERVICE,
            Pattern.compile("(?<![A-Za-z0-9])([a-z2-7]{56})\\.onion(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE),
            EntityPatterns::validOnionV3, 1.0),
        new Rule(EntityType.EMAIL,
            Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![A-Za-z0-9])"),
            s -> true, 0.9),
        new Rule(EntityType.PGP_FINGERPRINT,
            Pattern.compile("(?<![0-9A-Fa-fxX])(?:[0-9A-Fa-f]{4} ?){9}[0-9A-Fa-f]{4}(?![0-9A-Fa-f])"), s -> true, 0.9)
    );

    private EntityPatterns() {
    }

    static List<ExtractedEntity> extract(String text) {
        List<ExtractedEntity> found = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                String value = m.group();
                if (rule.valid().test(value)) {
                    found.add(ExtractedEntity.of(rule.type(), value, rule.confidence()));
                }
            }
        }
        return found;
    }

    /** Legacy BTC (P2PKH/P2SH): 25 bytes, last 4 = first 4 of sha256(sha256(first 21)). */
    static boolean validBase58Check(String address) {
        BigInteger n = BigInteger.ZERO;
        for (char c : address.toCharArray()) {
            int digit = BASE58.indexOf(c);
            if (digit < 0) {
                return false;
            }
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] raw = n.toByteArray();
        int leadingZeros = (int) address.chars().takeWhile(c -> c == '1').count();
        int start = raw.length > 1 && raw[0] == 0 ? 1 : 0; // BigInteger sign byte
        byte[] bytes = new byte[leadingZeros + raw.length - start];
        System.arraycopy(raw, start, bytes, leadingZeros, raw.length - start);
        if (bytes.length != 25 || (bytes[0] != 0x00 && bytes[0] != 0x05)) {
            return false;
        }
        byte[] payload = Arrays.copyOf(bytes, 21);
        byte[] check = digest("SHA-256", digest("SHA-256", payload));
        return Arrays.equals(Arrays.copyOfRange(bytes, 21, 25), Arrays.copyOf(check, 4));
    }

    /** BIP-173 (bech32) / BIP-350 (bech32m) checksum, hrp "bc", no mixed case. */
    static boolean validBech32(String address) {
        if (!address.equals(address.toLowerCase(Locale.ROOT)) && !address.equals(address.toUpperCase(Locale.ROOT))) {
            return false;
        }
        String a = address.toLowerCase(Locale.ROOT);
        int sep = a.lastIndexOf('1');
        String hrp = a.substring(0, sep);
        int[] values = new int[hrp.length() * 2 + 1 + a.length() - sep - 1];
        int i = 0;
        for (char c : hrp.toCharArray()) {
            values[i++] = c >> 5;
        }
        values[i++] = 0;
        for (char c : hrp.toCharArray()) {
            values[i++] = c & 31;
        }
        for (char c : a.substring(sep + 1).toCharArray()) {
            values[i++] = BECH32.indexOf(c);
        }
        int polymod = polymod(values);
        return polymod == 1 || polymod == 0x2bc830a3;
    }

    private static int polymod(int[] values) {
        int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;
        for (int v : values) {
            int top = chk >>> 25;
            chk = (chk & 0x1ffffff) << 5 ^ v;
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) == 1) {
                    chk ^= gen[i];
                }
            }
        }
        return chk;
    }

    /**
     * v3 onion: base32(pubkey[32] || checksum[2] || version=3), checksum =
     * sha3_256(".onion checksum" || pubkey || version)[:2] (Tor rend-spec-v3).
     */
    static boolean validOnionV3(String hostOrMatch) {
        String label = hostOrMatch.toLowerCase(Locale.ROOT).replace(".onion", "");
        byte[] raw = base32(label);
        if (raw == null || raw.length != 35 || raw[34] != 3) {
            return false;
        }
        byte[] pubkey = Arrays.copyOf(raw, 32);
        byte[] input = new byte[15 + 32 + 1];
        System.arraycopy(".onion checksum".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, input, 0, 15);
        System.arraycopy(pubkey, 0, input, 15, 32);
        input[47] = 3;
        byte[] check = digest("SHA3-256", input);
        return raw[32] == check[0] && raw[33] == check[1];
    }

    private static byte[] base32(String s) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int pos = 0;
        for (char c : s.toCharArray()) {
            int v = alphabet.indexOf(c);
            if (v < 0) {
                return null;
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[pos++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out;
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}

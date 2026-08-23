package com.ledgerintegrity.platform.auth;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC 6238 TOTP (SHA-1, 6 digits, 30-second steps) — the algorithm every
 * authenticator app implements. No external dependency; ±1 step of clock drift
 * is accepted, matching common practice.
 */
@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;

    private final SecureRandom random = new SecureRandom();

    /** 160-bit secret, Base32-encoded, for manual entry or an otpauth:// URI. */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return sb.toString();
    }

    /** otpauth URI for authenticator apps (accepted as QR content or via manual key entry). */
    public String uri(String secret, String accountLabel) {
        return "otpauth://totp/Ledger%20Integrity:" + accountLabel.replace(" ", "%20")
                + "?secret=" + secret + "&issuer=Ledger%20Integrity&period=" + PERIOD_SECONDS
                + "&digits=" + DIGITS;
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) return false;
        long step = Instant.now().getEpochSecond() / PERIOD_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            if (codeAt(secret, step + offset).equals(code)) return true;
        }
        return false;
    }

    static String codeAt(String secret, long step) {
        byte[] key = base32Decode(secret);
        byte[] msg = ByteBuffer.allocate(8).putLong(step).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            int off = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[off] & 0x7f) << 24) | ((hash[off + 1] & 0xff) << 16)
                    | ((hash[off + 2] & 0xff) << 8) | (hash[off + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP computation failed", e);
        }
    }

    static byte[] base32Decode(String s) {
        String cleaned = s.trim().toUpperCase().replace("=", "").replace(" ", "");
        int buffer = 0, bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : cleaned.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Invalid Base32 character: " + c);
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}

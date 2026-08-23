package com.ledgerintegrity.platform.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RFC 6238 Appendix B test vectors (SHA-1, truncated to 6 digits). */
class TotpServiceTest {

    // ASCII "12345678901234567890" in Base32
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Vectors() {
        assertEquals("287082", TotpService.codeAt(RFC_SECRET, 59 / 30));            // T=1970-01-01 00:00:59
        assertEquals("081804", TotpService.codeAt(RFC_SECRET, 1111111109L / 30));   // T=2005-03-18 01:58:29
        assertEquals("050471", TotpService.codeAt(RFC_SECRET, 1111111111L / 30));   // T=2005-03-18 01:58:31
        assertEquals("005924", TotpService.codeAt(RFC_SECRET, 1234567890L / 30));   // T=2009-02-13 23:31:30
        assertEquals("279037", TotpService.codeAt(RFC_SECRET, 2000000000L / 30));   // T=2033-05-18 03:33:20
    }

    @Test
    void verifyAcceptsCurrentCodeAndRejectsGarbage() {
        TotpService totp = new TotpService();
        String secret = totp.generateSecret();
        assertEquals(32, secret.length()); // 160 bits in Base32
        String now = TotpService.codeAt(secret, java.time.Instant.now().getEpochSecond() / 30);
        assertTrue(totp.verify(secret, now));
        assertFalse(totp.verify(secret, "000000".equals(now) ? "000001" : "000000"));
        assertFalse(totp.verify(secret, "abc123"));
        assertFalse(totp.verify(secret, null));
        assertFalse(totp.verify(null, now));
    }

    @Test
    void uriIsWellFormedForAuthenticatorApps() {
        TotpService totp = new TotpService();
        String uri = totp.uri("ABC234", "user@firm.in");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=ABC234"));
        assertTrue(uri.contains("issuer=Ledger%20Integrity"));
    }
}

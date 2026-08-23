package com.ledgerintegrity.platform.auth;

import com.ledgerintegrity.platform.evidence.EvidenceService;
import com.ledgerintegrity.platform.evidence.persist.EvidenceDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ROADMAP Phase A over real HTTP: CSRF blocks unproven mutations, MFA gates login,
 * evidence documents are ciphertext at rest, sessions live in the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:hardeningdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 32 zero bytes, Base64 - a TEST key only
        "app.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class HardeningIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired com.ledgerintegrity.platform.auth.persist.AppUserRepository users;
    @Autowired TotpService totp;
    @Autowired EvidenceService evidenceService;
    @Autowired EvidenceDocumentRepository documents;
    @Autowired com.ledgerintegrity.platform.engagement.EngagementRepository engagements;
    @Autowired com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository exceptions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void csrfMfaEncryptionAndDbSessionsAllHold() {
        // ---- register (exempt entry point) ----
        ResponseEntity<Map> reg = rest.postForEntity("/api/auth/register-firm",
                json(Map.of("firmName", "Hardening & Co", "displayName", "Admin",
                        "email", "admin@hardening.firm", "password", "password-h1")), Map.class);
        assertEquals(HttpStatus.OK, reg.getStatusCode());
        String cookie = reg.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        // ---- CSRF: a mutating call WITHOUT the token is refused ----
        HttpHeaders bare = new HttpHeaders();
        bare.add(HttpHeaders.COOKIE, cookie);
        bare.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> blocked = rest.exchange("/api/engagements", HttpMethod.POST,
                new HttpEntity<>(Map.of("clientName", "X", "fyStart", "2024-04-01",
                        "fyEnd", "2025-03-31", "closeDate", "2025-03-31"), bare), String.class);
        assertEquals(HttpStatus.FORBIDDEN, blocked.getStatusCode());

        // ---- with the token, the same call succeeds ----
        String token = csrf(cookie);
        ResponseEntity<Map> created = exchange(cookie, token, HttpMethod.POST, "/api/engagements",
                Map.of("clientName", "HARD-CLIENT", "fyStart", "2024-04-01",
                        "fyEnd", "2025-03-31", "closeDate", "2025-03-31"), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        // ---- sessions are in the database (spring-session JDBC), not memory ----
        Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM spring_session", Integer.class);
        assertTrue(sessions != null && sessions >= 1, "session rows should exist in the DB");

        // ---- MFA: setup -> enable -> login demands the code ----
        Map setup = exchange(cookie, token, HttpMethod.POST, "/api/auth/mfa/setup", null, Map.class).getBody();
        String secret = (String) setup.get("secret");
        assertTrue(((String) setup.get("otpauthUri")).startsWith("otpauth://totp/"));
        String code = TotpService.codeAt(secret, java.time.Instant.now().getEpochSecond() / 30);
        Map enabled = exchange(cookie, token, HttpMethod.POST, "/api/auth/mfa/enable",
                Map.of("code", code), Map.class).getBody();
        assertEquals(true, enabled.get("mfaEnabled"));

        // password alone now yields a challenge, no session
        ResponseEntity<Map> challenge = rest.postForEntity("/api/auth/login",
                json(Map.of("email", "admin@hardening.firm", "password", "password-h1")), Map.class);
        assertEquals(true, challenge.getBody().get("mfaRequired"));
        assertNull(challenge.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        // wrong code refused; right code signs in
        assertEquals(HttpStatus.UNAUTHORIZED, rest.postForEntity("/api/auth/login",
                json(Map.of("email", "admin@hardening.firm", "password", "password-h1",
                        "mfaCode", "000000")), String.class).getStatusCode());
        String code2 = TotpService.codeAt(secret, java.time.Instant.now().getEpochSecond() / 30);
        ResponseEntity<Map> full = rest.postForEntity("/api/auth/login",
                json(Map.of("email", "admin@hardening.firm", "password", "password-h1",
                        "mfaCode", code2)), Map.class);
        assertEquals("admin@hardening.firm", full.getBody().get("email"));

        // ---- at-rest encryption: stored bytes are ciphertext, download round-trips ----
        var engagement = engagements.findAll().get(0);
        var x = com.ledgerintegrity.platform.rules.persist.ExceptionCase.from(
                new com.ledgerintegrity.platform.rules.Finding("JE-03", "t",
                        com.ledgerintegrity.platform.rules.Finding.Severity.LOW, 0, "r",
                        java.util.List.of("V1"), "s"),
                engagement.getId(), java.util.UUID.randomUUID(), "hard-hash", java.time.Instant.now());
        exceptions.save(x);
        var request = evidenceService.createRequest(x.getId(), "Encrypted doc", null, "Tester <t@t>", null);
        byte[] plaintext = "highly confidential invoice".getBytes(StandardCharsets.UTF_8);
        var doc = evidenceService.upload(request.getId(), "invoice.txt", "text/plain", plaintext, "Tester <t@t>");
        var stored = documents.findById(doc.getId()).orElseThrow();
        assertTrue(stored.isEncrypted());
        assertFalse(new String(stored.getContent(), StandardCharsets.UTF_8).contains("confidential"),
                "stored bytes must not contain the plaintext");
        assertArrayEquals(plaintext, evidenceService.contentOf(stored));
    }

    private String csrf(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookie);
        Map<?, ?> body = rest.exchange("/api/auth/csrf", HttpMethod.GET, new HttpEntity<>(h), Map.class).getBody();
        return (String) body.get("token");
    }

    private <T> ResponseEntity<T> exchange(String cookie, String token, HttpMethod method,
                                           String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add("X-XSRF-TOKEN", token);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private static HttpEntity<Map<String, ?>> json(Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}

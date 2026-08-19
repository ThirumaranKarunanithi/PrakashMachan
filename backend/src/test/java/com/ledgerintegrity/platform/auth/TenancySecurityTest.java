package com.ledgerintegrity.platform.auth;

import com.ledgerintegrity.platform.auth.persist.AuditLogRepository;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-001..004 over real HTTP: unauthenticated requests are rejected, sessions work,
 * one firm can never see or touch another firm's engagements (404, not 403 — existence
 * is not leaked), and every call lands in the audit log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:sectestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TenancySecurityTest {

    @Autowired TestRestTemplate rest;
    @Autowired AuditLogRepository auditLog;

    @Test
    void tenantsAreIsolatedAndActionsAreAudited() {
        // unauthenticated -> 401
        ResponseEntity<String> anon = rest.getForEntity("/api/engagements", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anon.getStatusCode());

        // register two firms; each registration establishes a session cookie
        String cookieA = register("Sharma & Associates", "Asha Sharma", "asha@sharma.firm", "password-a1");
        String cookieB = register("Verma & Co", "Vikram Verma", "vikram@verma.firm", "password-b1");
        assertNotNull(cookieA);
        assertNotNull(cookieB);

        // duplicate firm / email rejected
        assertEquals(HttpStatus.CONFLICT, rawRegister("Sharma & Associates", "X", "x@y.firm", "password-x1").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, rawRegister("Third Firm", "X", "asha@sharma.firm", "password-x1").getStatusCode());

        // firm A creates an engagement
        ResponseEntity<Map> created = exchange(cookieA, HttpMethod.POST, "/api/engagements",
                Map.of("clientName", "CLIENT-A", "fyStart", "2024-04-01", "fyEnd", "2025-03-31",
                        "closeDate", "2025-03-31"), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String engagementId = (String) created.getBody().get("id");

        // firm A sees it; firm B's list is empty
        assertEquals(1, exchange(cookieA, HttpMethod.GET, "/api/engagements", null, List.class).getBody().size());
        assertEquals(0, exchange(cookieB, HttpMethod.GET, "/api/engagements", null, List.class).getBody().size());

        // firm B cannot read, run rules on, or generate workpapers for firm A's engagement — 404, never 403
        for (String path : List.of(
                "/api/engagements/" + engagementId,
                "/api/engagements/" + engagementId + "/exceptions",
                "/api/engagements/" + engagementId + "/cases",
                "/api/engagements/" + engagementId + "/workpapers")) {
            assertEquals(HttpStatus.NOT_FOUND,
                    exchange(cookieB, HttpMethod.GET, path, null, String.class).getStatusCode(), path);
        }
        assertEquals(HttpStatus.NOT_FOUND,
                exchange(cookieB, HttpMethod.POST, "/api/engagements/" + engagementId + "/rule-runs",
                        Map.of(), String.class).getStatusCode());

        // firm A can read its own
        assertEquals(HttpStatus.OK,
                exchange(cookieA, HttpMethod.GET, "/api/engagements/" + engagementId, null, String.class).getStatusCode());

        // wrong password -> 401
        ResponseEntity<String> badLogin = rest.postForEntity("/api/auth/login",
                jsonEntity(Map.of("email", "asha@sharma.firm", "password", "wrong")), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, badLogin.getStatusCode());

        // SEC-004: the calls above are in the audit log, attributed to users and firms
        assertTrue(auditLog.count() > 5);
        assertTrue(auditLog.findAll().stream().anyMatch(l ->
                l.getUserEmail().equals("asha@sharma.firm") && l.getMethod().equals("POST")
                        && l.getPath().equals("/api/engagements") && l.getStatus() == 201));
        assertTrue(auditLog.findAll().stream().anyMatch(l ->
                l.getUserEmail().equals("vikram@verma.firm") && l.getStatus() == 404));
    }

    // ---------- helpers ----------

    private String register(String firm, String name, String email, String password) {
        ResponseEntity<Map> res = rawRegister(firm, name, email, password);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        return res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    }

    private ResponseEntity<Map> rawRegister(String firm, String name, String email, String password) {
        return rest.postForEntity("/api/auth/register-firm",
                jsonEntity(Map.of("firmName", firm, "displayName", name, "email", email, "password", password)),
                Map.class);
    }

    private static HttpEntity<Map<String, String>> jsonEntity(Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private <T> ResponseEntity<T> exchange(String cookie, HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
}

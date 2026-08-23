package com.ledgerintegrity.platform.engagement;

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
 * Subscription model: Core is always included; GST / BANK / VENDOR / AUDIT_TRAIL are
 * per-engagement add-ons. Unsubscribed modules return a clear 403 naming the module
 * (never a silent failure), and an ADMIN/PARTNER can change the subscription.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:modulegatingdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ModuleGatingIntegrationTest {

    @Autowired TestRestTemplate rest;

    @Test
    void unsubscribedModulesAreGatedAndSubscriptionIsEditable() {
        ResponseEntity<Map> reg = rest.postForEntity("/api/auth/register-firm",
                json(Map.of("firmName", "Gating & Co", "displayName", "Admin", "email", "admin@gating.firm",
                        "password", "password-g1")), Map.class);
        assertEquals(HttpStatus.OK, reg.getStatusCode());
        String cookie = reg.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        // GST-only subscription at creation
        ResponseEntity<Map> created = exchange(cookie, HttpMethod.POST, "/api/engagements",
                Map.of("clientName", "GST-ONLY", "fyStart", "2024-04-01", "fyEnd", "2025-03-31",
                        "closeDate", "2025-03-31", "modules", List.of("GST")), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String id = (String) created.getBody().get("id");
        assertEquals(List.of("GST"), created.getBody().get("modules"));

        // subscribed module works; unsubscribed modules 403 with the module named
        assertEquals(HttpStatus.OK,
                exchange(cookie, HttpMethod.GET, "/api/engagements/" + id + "/gst/status", null, String.class).getStatusCode());
        ResponseEntity<String> bank = exchange(cookie, HttpMethod.GET,
                "/api/engagements/" + id + "/bank/status", null, String.class);
        assertEquals(HttpStatus.FORBIDDEN, bank.getStatusCode());
        assertTrue(bank.getBody().contains("Bank Reconciliation"));
        assertEquals(HttpStatus.FORBIDDEN, exchange(cookie, HttpMethod.POST,
                "/api/engagements/" + id + "/vendor-data/analyze", null, String.class).getStatusCode());

        // core stays available regardless of subscription
        assertEquals(HttpStatus.OK, exchange(cookie, HttpMethod.GET,
                "/api/engagements/" + id + "/cases", null, String.class).getStatusCode());
        assertEquals(HttpStatus.OK, exchange(cookie, HttpMethod.GET,
                "/api/engagements/" + id + "/benford-runs", null, String.class).getStatusCode());

        // ADMIN adds BANK; the gate opens
        ResponseEntity<Map> updated = exchange(cookie, HttpMethod.PUT,
                "/api/engagements/" + id + "/modules", Map.of("modules", List.of("GST", "BANK")), Map.class);
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertEquals(List.of("BANK", "GST"), updated.getBody().get("modules"));
        assertEquals(HttpStatus.OK,
                exchange(cookie, HttpMethod.GET, "/api/engagements/" + id + "/bank/status", null, String.class).getStatusCode());

        // unknown module rejected with the valid list
        ResponseEntity<String> bad = exchange(cookie, HttpMethod.PUT,
                "/api/engagements/" + id + "/modules", Map.of("modules", List.of("CRYPTO")), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertTrue(bad.getBody().contains("Valid modules"));

        // default engagement (no modules field) gets the full suite
        ResponseEntity<Map> full = exchange(cookie, HttpMethod.POST, "/api/engagements",
                Map.of("clientName", "FULL-SUITE", "fyStart", "2024-04-01", "fyEnd", "2025-03-31",
                        "closeDate", "2025-03-31"), Map.class);
        assertEquals(List.of("AUDIT_TRAIL", "BANK", "GST", "VENDOR"), full.getBody().get("modules"));
    }

    private static HttpEntity<Map<String, ?>> json(Map<String, ?> body) {
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

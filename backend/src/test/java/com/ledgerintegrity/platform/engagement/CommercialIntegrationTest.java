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

/** Screen 2 commercial layer: customers, configurable price list, billing summary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:commercialdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CommercialIntegrationTest {

    @Autowired TestRestTemplate rest;

    @Test
    void customersPricingAndBillingComputeConfigurableFees() {
        ResponseEntity<Map> reg = rest.postForEntity("/api/auth/register-firm",
                json(Map.of("firmName", "Commercial & Co", "displayName", "Admin",
                        "email", "admin@commercial.firm", "password", "password-c1")), Map.class);
        String cookie = reg.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        // two client-years for one customer plus a core-only customer
        exchange(cookie, HttpMethod.POST, "/api/engagements", Map.of("clientName", "ALPHA LTD",
                "fyStart", "2023-04-01", "fyEnd", "2024-03-31", "closeDate", "2024-03-31",
                "modules", List.of("GST")), Map.class);
        exchange(cookie, HttpMethod.POST, "/api/engagements", Map.of("clientName", "ALPHA LTD",
                "fyStart", "2024-04-01", "fyEnd", "2025-03-31", "closeDate", "2025-03-31",
                "modules", List.of("GST", "BANK")), Map.class);
        exchange(cookie, HttpMethod.POST, "/api/engagements", Map.of("clientName", "BETA LLP",
                "fyStart", "2024-04-01", "fyEnd", "2025-03-31", "closeDate", "2025-03-31",
                "modules", List.of()), Map.class);

        // customers group the years; fee = core + latest year's modules at default prices
        List<Map<String, Object>> customers =
                exchange(cookie, HttpMethod.GET, "/api/customers", null, List.class).getBody();
        assertEquals(2, customers.size());
        Map<String, Object> alpha = customers.stream()
                .filter(c -> c.get("name").equals("ALPHA LTD")).findFirst().orElseThrow();
        assertEquals(2, alpha.get("engagementYears"));
        assertEquals(List.of("BANK", "GST"), alpha.get("modules"));
        long alphaFee = ((Number) alpha.get("estimatedFeePaise")).longValue();
        assertEquals(PriceConfig.DEFAULT_CORE + PriceConfig.DEFAULT_GST + PriceConfig.DEFAULT_BANK, alphaFee);

        // billing sums every client-year: Alpha FY24 (core+GST) + Alpha FY25 (core+GST+bank) + Beta (core)
        Map<String, Object> billing = exchange(cookie, HttpMethod.GET, "/api/billing", null, Map.class).getBody();
        long expectedTotal = (PriceConfig.DEFAULT_CORE + PriceConfig.DEFAULT_GST)
                + (PriceConfig.DEFAULT_CORE + PriceConfig.DEFAULT_GST + PriceConfig.DEFAULT_BANK)
                + PriceConfig.DEFAULT_CORE;
        assertEquals(expectedTotal, ((Number) billing.get("totalPaise")).longValue());
        assertEquals(3, ((List<?>) billing.get("lines")).size());

        // firm re-prices GST; fees follow the new list, versioned append-only
        Map<String, Object> updated = exchange(cookie, HttpMethod.PUT, "/api/pricing",
                Map.of("gstPaise", 20_000_00L), Map.class).getBody();
        assertEquals(1, updated.get("version"));
        assertEquals(20_000_00, ((Number) updated.get("gstPaise")).longValue());
        Map<String, Object> after = exchange(cookie, HttpMethod.GET, "/api/billing", null, Map.class).getBody();
        assertEquals(expectedTotal + 2 * (20_000_00L - PriceConfig.DEFAULT_GST),
                ((Number) after.get("totalPaise")).longValue());

        // CSV export carries the total line
        String csv = exchange(cookie, HttpMethod.GET, "/api/billing.csv", null, String.class).getBody();
        assertTrue(csv.startsWith("client,financial_year,modules"));
        assertTrue(csv.contains("TOTAL"));

        // negative price rejected
        assertEquals(HttpStatus.BAD_REQUEST, exchange(cookie, HttpMethod.PUT, "/api/pricing",
                Map.of("corePaise", -5), String.class).getStatusCode());
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
        if (method != HttpMethod.GET) headers.add("X-XSRF-TOKEN", csrfFor(cookie));
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    // Phase A: complete the CSRF handshake like a real client
    private final java.util.Map<String, String> csrfBySession = new java.util.HashMap<>();

    private String csrfFor(String cookie) {
        return csrfBySession.computeIfAbsent(cookie, c -> {
            HttpHeaders h = new HttpHeaders();
            h.add(HttpHeaders.COOKIE, c);
            Map<?, ?> body = rest.exchange("/api/auth/csrf", HttpMethod.GET,
                    new HttpEntity<>(h), Map.class).getBody();
            return (String) body.get("token");
        });
    }

}
